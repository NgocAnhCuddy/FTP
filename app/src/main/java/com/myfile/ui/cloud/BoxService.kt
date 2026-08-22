package com.myfile.ui.cloud

import android.content.Context
import com.myfile.ui.model.CloudProvider
import com.myfile.ui.model.RemoteFile
import com.myfile.ui.util.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class BoxService(private val context: Context) : CloudFileService {

    private val api by lazy { RetrofitFactory.box(context) }
    private val prefs = SecurePrefs.getInstance(context)

    override suspend fun isLinked(): Boolean =
        !prefs.getCloudAccessToken(CloudProvider.BOX).isNullOrEmpty()

    override suspend fun listFiles(folderId: String): Result<List<RemoteFile>> = withContext(Dispatchers.IO) {
        try {
            val id = folderId.ifBlank { "0" } // "0" = thư mục gốc trong Box API
            val response = api.listItems(id)
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Lỗi Box: ${response.code()}"))
            }
            val items = response.body()?.entries.orEmpty().map {
                RemoteFile(
                    name = it.name,
                    path = it.name,
                    isDirectory = it.type == "folder",
                    size = it.size,
                    cloudFileId = it.id
                )
            }
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uploadFile(localFile: File, parentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val client = RetrofitFactory.okHttpFor(context, CloudProvider.BOX)
            val parent = parentId.ifBlank { "0" }
            val attributes = """{"name":"${localFile.name}","parent":{"id":"$parent"}}"""
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("attributes", attributes)
                .addFormDataPart(
                    "file", localFile.name,
                    localFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
                )
                .build()
            val request = Request.Builder()
                .url("https://upload.box.com/api/2.0/files/content")
                .post(requestBody)
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext Result.failure(Exception("Tải lên Box thất bại: ${response.code}"))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun downloadFile(cloudFileId: String, destination: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = api.downloadFile(cloudFileId)
            if (!response.isSuccessful) return@withContext Result.failure(Exception("Tải xuống Box thất bại"))
            response.body()?.byteStream()?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteFile(cloudFileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Thử xóa như file trước; nếu lỗi thì thử như folder (đơn giản hóa;
            // trong thực tế nên biết trước type từ RemoteFile.isDirectory).
            val response = api.deleteFile(cloudFileId)
            if (!response.isSuccessful) {
                val folderResponse = api.deleteFolder(cloudFileId)
                if (!folderResponse.isSuccessful) return@withContext Result.failure(Exception("Xóa thất bại"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createFolder(name: String, parentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val parent = parentId.ifBlank { "0" }
            val body = mapOf("name" to name, "parent" to mapOf("id" to parent))
            val response = api.createFolder(body)
            if (!response.isSuccessful) return@withContext Result.failure(Exception("Tạo thư mục thất bại"))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun unlink() {
        prefs.clearCloudToken(CloudProvider.BOX)
    }

    override suspend fun getStorageQuota(): Result<CloudStorageQuota> = withContext(Dispatchers.IO) {
        try {
            val response = api.getCurrentUser()
            if (!response.isSuccessful) return@withContext Result.failure(Exception("Lỗi lấy dung lượng Box: ${response.code()}"))
            val user = response.body()
            Result.success(CloudStorageQuota(usedBytes = user?.space_used ?: 0L, totalBytes = user?.space_amount ?: 0L))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
