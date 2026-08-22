package com.myfile.ui.client

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.myfile.ui.model.FtpConnectionProfile
import com.myfile.ui.model.RemoteFile
import com.myfile.ui.util.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.EnumSet

/**
 * Bọc thư viện smbj để kết nối chia sẻ mạng SMB2/SMB3 (Windows Network Share, NAS,
 * Samba trên Linux). Cùng interface RemoteClient với FTP/SFTP.
 *
 * Lưu ý đường dẫn: với SMB, "đường dẫn" trong 1 share dùng dấu \ nội bộ, nhưng để nhất
 * quán với FTP/SFTP trong toàn app, ta biểu diễn RemoteFile.path bằng dấu "/" và chỉ
 * chuyển sang "\" ngay trước khi gọi API của smbj.
 */
class SmbClientManager : RemoteClient {

    private var smbClient: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    override val isConnected: Boolean
        get() = share != null && connection?.isConnected == true

    override suspend fun connect(profile: FtpConnectionProfile): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (profile.smbShareName.isBlank()) {
                return@withContext Result.failure(Exception("Thiếu tên thư mục chia sẻ (share)"))
            }
            val config = SmbConfig.builder()
                .withTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                // SMB2/3 hỗ trợ đọc/ghi theo khối lớn (multi-credit); mặc định của smbj khá
                // dè dặt (~64KB), nâng lên 1MB giúp tận dụng băng thông LAN thật sự thay vì
                // bị giới hạn bởi số round-trip cần thiết để truyền file lớn.
                .withReadBufferSize(1024 * 1024)
                .withWriteBufferSize(1024 * 1024)
                .withTransactBufferSize(1024 * 1024)
                .build()
            val client = SMBClient(config)
            val port = if (profile.port in 1..65535) profile.port else 445
            val conn = client.connect(profile.host, port)
            val authContext = if (profile.username.isBlank()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(profile.username, profile.password.toCharArray(), profile.smbDomain.ifBlank { null })
            }
            val sess = conn.authenticate(authContext)
            val connectedShare = sess.connectShare(profile.smbShareName) as? DiskShare
                ?: throw Exception("Không thể mở share '${profile.smbShareName}'")

            smbClient = client
            connection = conn
            session = sess
            share = connectedShare
            Result.success(Unit)
        } catch (e: Exception) {
            try { session?.close() } catch (ignored: Exception) {}
            try { connection?.close() } catch (ignored: Exception) {}
            smbClient = null; connection = null; session = null; share = null
            LogBus.error("Kết nối SMB tới ${profile.host}/${profile.smbShareName} thất bại", source = "SMB", throwable = e)
            Result.failure(Exception("Kết nối SMB thất bại: ${e.message}"))
        }
    }

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        try {
            share?.close()
            session?.close()
            connection?.close()
        } catch (e: Exception) {
            // ignore
        } finally {
            share = null; session = null; connection = null; smbClient = null
        }
        Unit
    }

    /** Chuyển "/thư/muc/con" (kiểu FTP) sang "thư\muc\con" (kiểu SMB), bỏ dấu / đầu. */
    private fun toSmbPath(path: String): String =
        path.trim('/').replace("/", "\\")

    override suspend fun listFiles(path: String): Result<List<RemoteFile>> = withContext(Dispatchers.IO) {
        val diskShare = share ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            val smbPath = toSmbPath(path)
            val entries = diskShare.list(smbPath)
            val basePath = path.trimEnd('/').ifEmpty { "" }
            val result = entries
                .filter { it.fileName != "." && it.fileName != ".." }
                .map { info ->
                    val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value.toLong()) != 0L
                    val childPath = if (basePath.isEmpty()) "/${info.fileName}" else "$basePath/${info.fileName}"
                    RemoteFile(
                        name = info.fileName,
                        path = childPath,
                        isDirectory = isDir,
                        size = info.endOfFile,
                        modifiedTime = info.changeTime.toEpochMillis()
                    )
                }
                .sortedWith(compareByDescending<RemoteFile> { it.isDirectory }.thenBy { it.name.lowercase() })
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(Exception("Không thể liệt kê thư mục: ${e.message}"))
        }
    }

    override suspend fun uploadFile(localFile: File, remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val diskShare = share ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            val smbPath = toSmbPath(remotePath)
            val file = diskShare.openFile(
                smbPath,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null
            )
            file.use { remote ->
                localFile.inputStream().use { input ->
                    // Buffer copy mặc định của Kotlin (8KB) là nút thắt cổ chai thật sự khi
                    // negotiate buffer SMB đã tăng lên 1MB — khớp kích thước 2 bên để tận
                    // dụng hết băng thông thay vì bị giới hạn bởi số lần copy nhỏ lẻ.
                    remote.outputStream.use { output -> input.copyTo(output, bufferSize = 256 * 1024) }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            LogBus.error("Tải lên SMB thất bại: $remotePath", source = "SMB", throwable = e)
            Result.failure(Exception("Tải lên thất bại: ${e.message}"))
        }
    }

    override suspend fun downloadFile(remotePath: String, localFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        val diskShare = share ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            val smbPath = toSmbPath(remotePath)
            val file = diskShare.openFile(
                smbPath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            file.use { remote ->
                remote.inputStream.use { input ->
                    localFile.outputStream().use { output -> input.copyTo(output, bufferSize = 256 * 1024) }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            LogBus.error("Tải xuống SMB thất bại: $remotePath", source = "SMB", throwable = e)
            Result.failure(Exception("Tải xuống thất bại: ${e.message}"))
        }
    }

    override suspend fun deleteFile(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val diskShare = share ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            diskShare.rm(toSmbPath(remotePath))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Xóa thất bại: ${e.message}"))
        }
    }

    override suspend fun deleteDirectory(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val diskShare = share ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            diskShare.rmdir(toSmbPath(remotePath), true)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Xóa thư mục thất bại: ${e.message}"))
        }
    }

    override suspend fun makeDirectory(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val diskShare = share ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            diskShare.mkdir(toSmbPath(remotePath))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Tạo thư mục thất bại: ${e.message}"))
        }
    }

    override suspend fun rename(fromPath: String, toPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val diskShare = share ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            val file = diskShare.openFile(
                toSmbPath(fromPath),
                EnumSet.of(AccessMask.GENERIC_ALL),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            file.use { it.rename(toSmbPath(toPath)) }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Đổi tên thất bại: ${e.message}"))
        }
    }

    override suspend fun estimateUsedSpace(maxDepth: Int, maxEntries: Int): Result<Long> = withContext(Dispatchers.IO) {
        val diskShare = share ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            var total = 0L
            var count = 0
            fun scan(path: String, depth: Int) {
                if (depth > maxDepth || count >= maxEntries) return
                val entries = try { diskShare.list(toSmbPath(path)) } catch (e: Exception) { return }
                for (info in entries) {
                    if (info.fileName == "." || info.fileName == "..") continue
                    if (count >= maxEntries) return
                    count++
                    val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value.toLong()) != 0L
                    val childPath = if (path.isEmpty()) "/${info.fileName}" else "$path/${info.fileName}"
                    if (isDir) {
                        scan(childPath, depth + 1)
                    } else {
                        total += info.endOfFile
                    }
                }
            }
            scan("", 0)
            Result.success(total)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
