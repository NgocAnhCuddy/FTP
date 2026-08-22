package com.myfile.ui.cloud

import com.myfile.ui.model.RemoteFile
import java.io.File

/** Dung lượng tài khoản cloud: đã dùng / tổng, tính theo byte. null nếu provider không cung cấp thông tin này. */
data class CloudStorageQuota(val usedBytes: Long, val totalBytes: Long)

/**
 * Hợp đồng chung cho mọi dịch vụ cloud (Google Drive, Dropbox, Box).
 * Mỗi provider implement lớp này bằng REST API riêng của họ, nhưng UI (CloudBrowserActivity)
 * chỉ cần thao tác qua interface này, không quan tâm chi tiết provider.
 */
interface CloudFileService {

    /** true nếu đã có access token hợp lệ (đã liên kết tài khoản). */
    suspend fun isLinked(): Boolean

    /** Liệt kê file/thư mục trong 1 folder. folderId rỗng = thư mục gốc. */
    suspend fun listFiles(folderId: String): Result<List<RemoteFile>>

    /** Tải file lên thư mục cha parentId. */
    suspend fun uploadFile(localFile: File, parentId: String): Result<Unit>

    /** Tải file về máy theo cloudFileId. */
    suspend fun downloadFile(cloudFileId: String, destination: File): Result<Unit>

    /** Xóa file/thư mục theo id. */
    suspend fun deleteFile(cloudFileId: String): Result<Unit>

    /** Tạo thư mục mới trong parentId. */
    suspend fun createFolder(name: String, parentId: String): Result<Unit>

    /** Ngắt liên kết tài khoản (xóa token đã lưu). */
    fun unlink()

    /** Lấy dung lượng đã dùng/tổng của tài khoản, hiển thị dưới dạng thanh mini có màu khi vào kết nối. */
    suspend fun getStorageQuota(): Result<CloudStorageQuota>
}
