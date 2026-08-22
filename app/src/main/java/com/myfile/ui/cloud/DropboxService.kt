package com.myfile.ui.cloud

import android.content.Context
import com.myfile.ui.model.CloudProvider
import com.myfile.ui.model.RemoteFile
import com.myfile.ui.util.SecurePrefs
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * Dropbox API v2 dùng đường dẫn logic (path), không dùng id như Box cho hầu hết thao tác,
 * ngoại trừ download/upload nội dung phải gọi qua content.dropboxapi.com với header Dropbox-API-Arg.
 */
class DropboxService(private val context: Context) : CloudFileService {

    private val api by lazy { RetrofitFactory.dropbox(context) }
    private val prefs = SecurePrefs.getInstance(context)
    private val gson = Gson()

    override suspend fun isLinked(): Boolean =
        !prefs.getCloudAccessToken(CloudProvider.DROPBOX).isNullOrEmpty()

    override suspend fun listFiles(folderId: String): Result<List<RemoteFile>> = withContext(Dispatchers.IO) {
        try {
            // folderId ở Dropbox chính là path, rỗng = "" (thư mục gốc)
            val response = api.listFolder(DropboxListFolderRequest(path = folderId))
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Lỗi Dropbox (${response.code()}): ${response.errorBody()?.string().orEmpty()}"))
            }
            val items = response.body()?.entries.orEmpty().map { entry ->
                RemoteFile(
                    name = entry.name,
                    path = entry.path_lower ?: entry.name,
                    isDirectory = entry.id?.startsWith("id:") == true && entry.size == 0L && entry.server_modified == null,
                    size = entry.size,
                    cloudFileId = entry.path_lower
                )
            }
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uploadFile(localFile: File, parentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val client = RetrofitFactory.okHttpFor(context, CloudProvider.DROPBOX)
            val destPath = if (parentId.isBlank()) "/${localFile.name}" else "$parentId/${localFile.name}"
            val apiArg = """{"path":"$destPath","mode":"overwrite","autorename":false,"mute":false}"""
            val body: RequestBody = localFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("https://content.dropboxapi.com/2/files/upload")
                .addHeader("Dropbox-API-Arg", apiArg)
                .addHeader("Content-Type", "application/octet-stream")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext Result.failure(Exception("Tải lên Dropbox thất bại (${response.code}): ${response.body?.string().orEmpty()}"))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun downloadFile(cloudFileId: String, destination: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val client = RetrofitFactory.okHttpFor(context, CloudProvider.DROPBOX)
            val apiArg = """{"path":"$cloudFileId"}"""
            val request = Request.Builder()
                .url("https://content.dropboxapi.com/2/files/download")
                .addHeader("Dropbox-API-Arg", apiArg)
                .post(RequestBody.create(null, ByteArray(0)))
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext Result.failure(Exception("Tải xuống Dropbox thất bại (${response.code}): ${response.body?.string().orEmpty()}"))
            response.body?.byteStream()?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteFile(cloudFileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = api.delete(DropboxDeleteRequest(path = cloudFileId))
            if (!response.isSuccessful) return@withContext Result.failure(Exception("Xóa thất bại (${response.code()}): ${response.errorBody()?.string().orEmpty()}"))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createFolder(name: String, parentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val path = if (parentId.isBlank()) "/$name" else "$parentId/$name"
            val response = api.createFolder(DropboxCreateFolderRequest(path))
            if (!response.isSuccessful) return@withContext Result.failure(Exception("Tạo thư mục thất bại (${response.code()}): ${response.errorBody()?.string().orEmpty()}"))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun unlink() {
        prefs.clearCloudToken(CloudProvider.DROPBOX)
    }

    override suspend fun getStorageQuota(): Result<CloudStorageQuota> = withContext(Dispatchers.IO) {
        try {
            val response = api.getSpaceUsage()
            if (!response.isSuccessful) return@withContext Result.failure(Exception("Lỗi lấy dung lượng Dropbox: ${response.code()}"))
            val usage = response.body()
            Result.success(CloudStorageQuota(usedBytes = usage?.used ?: 0L, totalBytes = usage?.allocation?.allocated ?: 0L))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
