package com.myfile.ui.client

import com.myfile.ui.model.FtpConnectionProfile
import com.myfile.ui.model.RemoteFile
import java.io.File

/**
 * Interface chung cho mọi client kết nối máy chủ từ xa (FTP, SFTP, SMB).
 * FileBrowserActivity chỉ thao tác qua interface này, không cần biết giao thức cụ thể,
 * nên UI duyệt file/upload/download/xóa/đổi tên dùng chung 1 luồng code cho cả 3 loại.
 */
interface RemoteClient {

    val isConnected: Boolean

    suspend fun connect(profile: FtpConnectionProfile): Result<Unit>

    suspend fun disconnect()

    suspend fun listFiles(path: String): Result<List<RemoteFile>>

    suspend fun uploadFile(localFile: File, remotePath: String): Result<Unit>

    suspend fun downloadFile(remotePath: String, localFile: File): Result<Unit>

    suspend fun deleteFile(remotePath: String): Result<Unit>

    suspend fun deleteDirectory(remotePath: String): Result<Unit>

    suspend fun makeDirectory(remotePath: String): Result<Unit>

    suspend fun rename(fromPath: String, toPath: String): Result<Unit>

    /** Ước tính dung lượng đã dùng (không phải mọi giao thức/server đều hỗ trợ tổng dung lượng thật). */
    suspend fun estimateUsedSpace(maxDepth: Int = 3, maxEntries: Int = 2000): Result<Long>

    companion object {
        /** Factory: tạo đúng implementation theo loại kết nối trong profile. */
        fun forProfile(profile: FtpConnectionProfile): RemoteClient = when (profile.type) {
            com.myfile.ui.model.ConnectionType.FTP -> FtpClientManager()
            com.myfile.ui.model.ConnectionType.SFTP -> SftpClientManager()
            com.myfile.ui.model.ConnectionType.SMB -> SmbClientManager()
        }
    }
}
