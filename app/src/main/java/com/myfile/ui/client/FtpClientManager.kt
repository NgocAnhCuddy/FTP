package com.myfile.ui.client

import com.myfile.ui.model.FtpConnectionProfile
import com.myfile.ui.model.RemoteFile
import com.myfile.ui.util.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Bọc Apache Commons Net FTPClient để kết nối tới FTP server khác, duyệt và
 * truyền file. Mọi thao tác mạng chạy trên Dispatchers.IO qua coroutines.
 */
class FtpClientManager : RemoteClient {

    private var client: FTPClient? = null

    override val isConnected: Boolean
        get() = client?.isConnected == true

    override suspend fun connect(profile: FtpConnectionProfile): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val ftp = FTPClient()
            ftp.connectTimeout = 8000
            // Tắt thuật toán Nagle: dữ liệu điều khiển FTP là các lệnh nhỏ, gửi ngay lập tức
            // thay vì đợi gộp gói giúp giảm độ trễ, đặc biệt rõ khi duyệt nhiều thư mục liên tiếp.
            ftp.tcpNoDelay = true
            ftp.connect(profile.host, profile.port)

            val reply = ftp.replyCode
            if (!FTPReply.isPositiveCompletion(reply)) {
                ftp.disconnect()
                return@withContext Result.failure(Exception("Máy chủ từ chối kết nối (mã $reply)"))
            }

            val loginOk = ftp.login(profile.username, profile.password)
            if (!loginOk) {
                ftp.disconnect()
                return@withContext Result.failure(Exception("Sai tên đăng nhập hoặc mật khẩu"))
            }

            if (profile.passiveMode) {
                ftp.enterLocalPassiveMode()
            } else {
                ftp.enterLocalActiveMode()
            }
            ftp.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)
            // Tăng buffer từ 64KB lên 256KB: giảm số lần round-trip đọc/ghi socket khi
            // truyền file lớn, cải thiện rõ tốc độ upload/download trên mạng LAN nhanh.
            ftp.bufferSize = 1024 * 256
            ftp.controlKeepAliveTimeout = 30 // gửi NOOP giữ kết nối khi tải file rất lớn/chậm

            client = ftp
            Result.success(Unit)
        } catch (e: Exception) {
            LogBus.error("Kết nối FTP tới ${profile.host}:${profile.port} thất bại", source = "FTP", throwable = e)
            Result.failure(e)
        }
    }

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        try {
            client?.logout()
            client?.disconnect()
        } catch (e: Exception) {
            // ignore
        } finally {
            client = null
        }
        Unit
    }

    override suspend fun listFiles(path: String): Result<List<RemoteFile>> = withContext(Dispatchers.IO) {
        val ftp = client ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            val files: Array<FTPFile> = ftp.listFiles(path)
            val result = files
                .filter { it.name != "." && it.name != ".." }
                .map { f ->
                    RemoteFile(
                        name = f.name,
                        path = if (path.endsWith("/")) "$path${f.name}" else "$path/${f.name}",
                        isDirectory = f.isDirectory,
                        size = f.size,
                        modifiedTime = f.timestamp?.timeInMillis ?: 0L
                    )
                }
                .sortedWith(compareByDescending<RemoteFile> { it.isDirectory }.thenBy { it.name.lowercase() })
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uploadFile(localFile: File, remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val ftp = client ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            localFile.inputStream().use { input: InputStream ->
                val ok = ftp.storeFile(remotePath, input)
                if (!ok) return@withContext Result.failure(Exception("Tải lên thất bại: ${ftp.replyString}"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            LogBus.error("Tải lên thất bại: $remotePath", source = "FTP", throwable = e)
            Result.failure(e)
        }
    }

    override suspend fun downloadFile(remotePath: String, localFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        val ftp = client ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            localFile.outputStream().use { output: OutputStream ->
                val ok = ftp.retrieveFile(remotePath, output)
                if (!ok) return@withContext Result.failure(Exception("Tải xuống thất bại: ${ftp.replyString}"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            LogBus.error("Tải xuống thất bại: $remotePath", source = "FTP", throwable = e)
            Result.failure(e)
        }
    }

    override suspend fun deleteFile(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val ftp = client ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            val ok = ftp.deleteFile(remotePath)
            if (!ok) return@withContext Result.failure(Exception("Xóa thất bại: ${ftp.replyString}"))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteDirectory(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val ftp = client ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            val ok = ftp.removeDirectory(remotePath)
            if (!ok) return@withContext Result.failure(Exception("Xóa thư mục thất bại: ${ftp.replyString}"))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun makeDirectory(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val ftp = client ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            val ok = ftp.makeDirectory(remotePath)
            if (!ok) return@withContext Result.failure(Exception("Tạo thư mục thất bại: ${ftp.replyString}"))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun rename(fromPath: String, toPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val ftp = client ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            val ok = ftp.rename(fromPath, toPath)
            if (!ok) return@withContext Result.failure(Exception("Đổi tên thất bại: ${ftp.replyString}"))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * FTP chuẩn không có lệnh quota chung, nên ước tính dung lượng ĐÃ DÙNG bằng cách
     * duyệt đệ quy thư mục gốc và cộng dồn kích thước file (giới hạn độ sâu/số lượng
     * để tránh treo với server lớn). Không có khái niệm "tổng dung lượng" nên trả về null cho total.
     */
    override suspend fun estimateUsedSpace(maxDepth: Int, maxEntries: Int): Result<Long> = withContext(Dispatchers.IO) {
        val ftp = client ?: return@withContext Result.failure(Exception("Chưa kết nối"))
        try {
            var total = 0L
            var count = 0
            fun scan(path: String, depth: Int) {
                if (depth > maxDepth || count >= maxEntries) return
                val files = ftp.listFiles(path) ?: return
                for (f in files) {
                    if (f.name == "." || f.name == "..") continue
                    if (count >= maxEntries) return
                    count++
                    if (f.isDirectory) {
                        val childPath = if (path.endsWith("/")) "$path${f.name}" else "$path/${f.name}"
                        scan(childPath, depth + 1)
                    } else {
                        total += f.size
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
