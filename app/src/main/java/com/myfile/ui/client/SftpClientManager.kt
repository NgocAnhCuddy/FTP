package com.myfile.ui.client

import com.myfile.ui.model.FtpConnectionProfile
import com.myfile.ui.model.RemoteFile
import com.myfile.ui.util.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.File

/**
 * Bọc thư viện SSHJ để kết nối SFTP (SSH File Transfer Protocol) tới máy chủ khác.
 * Cùng interface RemoteClient với FTP/SMB nên FileBrowserActivity dùng chung 1 luồng code.
 */
class SftpClientManager : RemoteClient {

    private var ssh: SSHClient? = null
    private var sftp: SFTPClient? = null

    override val isConnected: Boolean
        get() = ssh?.isConnected == true && sftp != null

    override suspend fun connect(profile: FtpConnectionProfile): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val client = SSHClient()
            // Chấp nhận mọi host key: phù hợp cho kết nối LAN/NAS cá nhân nơi người dùng
            // tự nhập IP thủ công, tương tự cách hầu hết app FTP/SFTP di động xử lý.
            client.addHostKeyVerifier(PromiscuousVerifier())
            client.connectTimeout = 8000
            // Ping định kỳ giữ kết nối sống khi truyền file lớn/mạng chập chờn, tránh bị
            // router/NAT ngắt kết nối giữa chừng (nguyên nhân phổ biến gây "treo" khi tải file lớn).
            client.connection.keepAlive.keepAliveInterval = 15
            client.connect(profile.host, profile.port)

            if (profile.sftpPrivateKeyPath.isNotBlank() && File(profile.sftpPrivateKeyPath).exists()) {
                val keys = client.loadKeys(profile.sftpPrivateKeyPath)
                client.authPublickey(profile.username, keys)
            } else {
                client.authPassword(profile.username, profile.password)
            }

            ssh = client
            sftp = client.newSFTPClient()
            Result.success(Unit)
        } catch (e: Exception) {
            try { ssh?.disconnect() } catch (ignored: Exception) {}
            ssh = null
            sftp = null
            LogBus.error("Kết nối SFTP tới ${profile.host}:${profile.port} thất bại", source = "SFTP", throwable = e)
            Result.failure(Exception("Kết nối SFTP thất bại: ${e.message}"))
        }
    }

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        try {
            sftp?.close()
            ssh?.disconnect()
        } catch (e: Exception) {
            // ignore
        } finally {
            sftp = null
            ssh = null
        }
        Unit
    }

    override suspend fun listFiles(path: String): Result<List<RemoteFile>> = withContext(Dispatchers.IO) {
        val client = sftp ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            val normalized = path.ifBlank { "/" }
            val entries = client.ls(normalized)
            val result = entries
                .filter { it.name != "." && it.name != ".." }
                .map { e ->
                    val fullPath = if (normalized.endsWith("/")) "$normalized${e.name}" else "$normalized/${e.name}"
                    RemoteFile(
                        name = e.name,
                        path = fullPath,
                        isDirectory = e.attributes.type == FileMode.Type.DIRECTORY,
                        size = e.attributes.size,
                        modifiedTime = e.attributes.mtime * 1000L
                    )
                }
                .sortedWith(compareByDescending<RemoteFile> { it.isDirectory }.thenBy { it.name.lowercase() })
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(Exception("Không thể liệt kê thư mục: ${e.message}"))
        }
    }

    override suspend fun uploadFile(localFile: File, remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val client = sftp ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            client.put(localFile.absolutePath, remotePath)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Tải lên thất bại: ${e.message}"))
        }
    }

    override suspend fun downloadFile(remotePath: String, localFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        val client = sftp ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            client.get(remotePath, localFile.absolutePath)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Tải xuống thất bại: ${e.message}"))
        }
    }

    override suspend fun deleteFile(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val client = sftp ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            client.rm(remotePath)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Xóa thất bại: ${e.message}"))
        }
    }

    override suspend fun deleteDirectory(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val client = sftp ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            client.rmdir(remotePath)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Xóa thư mục thất bại: ${e.message}"))
        }
    }

    override suspend fun makeDirectory(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val client = sftp ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            client.mkdir(remotePath)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Tạo thư mục thất bại: ${e.message}"))
        }
    }

    override suspend fun rename(fromPath: String, toPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val client = sftp ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            client.rename(fromPath, toPath)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Đổi tên thất bại: ${e.message}"))
        }
    }

    override suspend fun estimateUsedSpace(maxDepth: Int, maxEntries: Int): Result<Long> = withContext(Dispatchers.IO) {
        val client = sftp ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            var total = 0L
            var count = 0
            fun scan(path: String, depth: Int) {
                if (depth > maxDepth || count >= maxEntries) return
                val entries = try { client.ls(path) } catch (e: Exception) { return }
                for (e in entries) {
                    if (e.name == "." || e.name == "..") continue
                    if (count >= maxEntries) return
                    count++
                    val fullPath = if (path.endsWith("/")) "$path${e.name}" else "$path/${e.name}"
                    if (e.attributes.type == FileMode.Type.DIRECTORY) {
                        scan(fullPath, depth + 1)
                    } else {
                        total += e.attributes.size
                    }
                }
            }
            scan("/", 0)
            Result.success(total)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
