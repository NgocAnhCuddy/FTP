package com.myfile.ui.cloud

import android.content.Context
import android.content.Intent
import com.myfile.ui.model.CloudProvider
import com.myfile.ui.model.RemoteFile
import com.myfile.ui.util.SecurePrefs
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File as JavaFile
import java.io.FileOutputStream

/**
 * Google Drive dùng luồng đăng nhập riêng (Google Sign-In SDK) thay vì AppAuth,
 * vì đây là cách Google khuyến nghị chính thức trên Android, đơn giản và an toàn hơn.
 */
class GoogleDriveService(private val context: Context) : CloudFileService {

    private val prefs = SecurePrefs.getInstance(context)

    fun signInClient(): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            // Đổi từ DRIVE_FILE sang DRIVE: DRIVE_FILE là phạm vi hẹp có chủ đích của Google —
            // app chỉ thấy được file do CHÍNH app này tạo ra hoặc được người dùng chọn qua Google
            // Picker, không bao giờ thấy được các file có sẵn trong Drive (kể cả khi đã cấp
            // quyền) — đây là lý do "Thư mục trống" dù tài khoản Drive thật có rất nhiều file.
            // DRIVE là toàn quyền đọc/ghi mọi file trong Drive, đúng với kỳ vọng app này hoạt
            // động như 1 trình duyệt file đầy đủ (giống Dropbox/Box đã làm).
            .requestScopes(com.google.android.gms.common.api.Scope(DriveScopes.DRIVE))
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    fun getSignInIntent(): Intent = signInClient().signInIntent

    fun handleSignInResult(account: GoogleSignInAccount) {
        prefs.saveCloudAccountInfo(CloudProvider.GOOGLE_DRIVE, account.email)
        // Đánh dấu đã liên kết bằng cách lưu email làm "access token" giả lập;
        // token thật được GoogleAccountCredential tự làm mới ngầm dựa trên tài khoản đã chọn.
        prefs.saveCloudToken(CloudProvider.GOOGLE_DRIVE, account.email ?: "linked", null, Long.MAX_VALUE)
    }

    private fun driveClient(): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE))
        credential.selectedAccount = account.account
        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("FTP Manager")
            .build()
    }

    /**
     * Thông báo lỗi phù hợp khi driveClient() trả null: phân biệt "chưa từng liên kết" với
     * "đã từng liên kết nhưng phiên đăng nhập cục bộ (Play Services) đã mất, cần đăng nhập lại"
     * — 2 trường hợp cần hướng dẫn khác nhau cho người dùng.
     */
    private fun notLinkedMessage(): String =
        if (!prefs.getCloudAccessToken(CloudProvider.GOOGLE_DRIVE).isNullOrEmpty())
            "Phiên đăng nhập Google Drive đã hết hạn trên máy, vui lòng liên kết lại"
        else
            "Chưa liên kết Google Drive"

    override suspend fun isLinked(): Boolean {
        // BUG ĐÃ SỬA: trước đây chỉ check GoogleSignIn.getLastSignedInAccount(context), tức là
        // phụ thuộc HOÀN TOÀN vào cache session nội bộ của Google Play Services SDK, tách biệt
        // khỏi SecurePrefs (nơi Dropbox/Box lưu trạng thái liên kết). Cache này có thể tự mất mà
        // KHÔNG do người dùng chủ động đăng xuất — ví dụ Play Services tự cập nhật, bị hệ thống
        // dọn cache trên 1 số ROM tuỳ biến, hoặc tài khoản bị Android gỡ khỏi AccountManager (đổi
        // mật khẩu Google, thu hồi quyền truy cập app từ phía Google...). Khi đó UI hiển thị
        // "Chưa liên kết" dù người dùng chưa hề bấm huỷ liên kết gì cả — đúng hiện tượng đang gặp.
        //
        // driveClient() CHỈ hoạt động được khi GoogleSignIn còn account (nó không đọc token từ
        // SecurePrefs) — vì vậy "đã liên kết" thực chất phải neo theo Play Services. Nhưng nếu
        // SecurePrefs có token đã lưu mà Play Services lại mất account, đó không phải là "chưa
        // từng liên kết" mà là "phiên đăng nhập cục bộ bị mất, cần đăng nhập lại" -> vẫn trả về
        // true để KHÔNG xoá lịch sử liên kết khỏi UI, nhưng driveClient() bên dưới sẽ tự phát
        // hiện thiếu account và trả lỗi rõ ràng "Chưa liên kết Google Drive" khi thực sự gọi API,
        // thay vì âm thầm coi như chưa từng liên kết.
        val hasPlayServicesAccount = GoogleSignIn.getLastSignedInAccount(context) != null
        val hasSavedToken = !prefs.getCloudAccessToken(CloudProvider.GOOGLE_DRIVE).isNullOrEmpty()

        if (hasPlayServicesAccount && !hasSavedToken) {
            // Play Services vẫn nhớ tài khoản nhưng SecurePrefs bị mất dấu vết (ví dụ do
            // SecurePrefs vừa rơi vào fallback không mã hoá) -> đồng bộ lại ngay, tránh hiển
            // thị sai và tránh phải đăng nhập lại dù thực chất vẫn còn phiên hợp lệ.
            val account = GoogleSignIn.getLastSignedInAccount(context)
            prefs.saveCloudAccountInfo(CloudProvider.GOOGLE_DRIVE, account?.email)
            prefs.saveCloudToken(CloudProvider.GOOGLE_DRIVE, account?.email ?: "linked", null, Long.MAX_VALUE)
        }

        return hasPlayServicesAccount || hasSavedToken
    }

    /** Kết quả của 1 lệnh gọi Drive API cần xử lý consent bổ sung trước khi thử lại. */
    class NeedsUserConsentException(val intent: Intent) : Exception("Cần người dùng cấp thêm quyền truy cập Google Drive")

    /**
     * Bọc mọi lệnh gọi Drive API: bắt riêng UserRecoverableAuthIOException — đây KHÔNG phải lỗi
     * cấu hình (API key/SHA-1 sai) mà là bước consent RIÊNG BIỆT mà scope drive.file luôn đòi hỏi
     * ở lần gọi API thật sự đầu tiên, tách biệt hoàn toàn khỏi màn hình "Đăng nhập bằng Google"
     * ban đầu (đó chỉ xác thực danh tính, không cấp quyền Drive). Trước đây exception này rơi
     * thẳng vào catch chung, in ra message ngắn gọn dạng "key error" (message thật của lớp
     * UserRecoverableAuthIOException không hề nhắc gì đến "cần cấp quyền" một cách rõ ràng),
     * khiến người dùng và cả người debug hiểu lầm là lỗi cấu hình API key/SHA-1 — trong khi thực
     * ra dòng sửa duy nhất cần thiết chỉ là hứng đúng Intent này và show ra cho người dùng bấm
     * "Cho phép" một lần, y hệt cơ chế cấp quyền runtime bình thường.
     */
    private fun <T> withDriveConsentHandling(block: () -> T): T {
        try {
            return block()
        } catch (e: com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException) {
            throw NeedsUserConsentException(e.intent)
        }
    }

    override suspend fun listFiles(folderId: String): Result<List<RemoteFile>> = withContext(Dispatchers.IO) {
        try {
            val drive = driveClient() ?: return@withContext Result.failure(Exception(notLinkedMessage()))
            val parent = folderId.ifBlank { "root" }
            val result = withDriveConsentHandling {
                drive.files().list()
                    .setQ("'$parent' in parents and trashed = false")
                    .setFields("files(id, name, mimeType, size, modifiedTime)")
                    .execute()
            }

            val items = result.files.orEmpty().map { f: DriveFile ->
                RemoteFile(
                    name = f.name,
                    path = f.name,
                    isDirectory = f.mimeType == "application/vnd.google-apps.folder",
                    size = f.getSize() ?: 0L,
                    cloudFileId = f.id
                )
            }
            Result.success(items)
        } catch (e: Exception) {
            com.myfile.ui.util.LogBus.warning(
                "Google Drive listFiles lỗi (${e.javaClass.simpleName}): ${e.message}",
                source = "CLOUD"
            )
            // Log THÊM toàn bộ stacktrace riêng cho lỗi Drive — "key error" (IllegalArgumentException)
            // là message quá ngắn để biết chính xác dòng nào trong thư viện Google API Client ném
            // ra nó; error() thường chỉ trích frame đầu tiên thuộc app, vô ích khi lỗi nằm hẳn bên
            // trong thư viện bên thứ 3 — dùng crash() ở đây để có đủ stacktrace chẩn đoán, dù đây
            // không phải crash thật (app vẫn chạy tiếp bình thường sau lỗi này).
            com.myfile.ui.util.LogBus.crash(e)
            Result.failure(e)
        }
    }

    override suspend fun uploadFile(localFile: JavaFile, parentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val drive = driveClient() ?: return@withContext Result.failure(Exception(notLinkedMessage()))
            val metadata = DriveFile().apply {
                name = localFile.name
                parents = listOf(parentId.ifBlank { "root" })
            }
            val content = com.google.api.client.http.FileContent("application/octet-stream", localFile)
            drive.files().create(metadata, content).setFields("id").execute()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun downloadFile(cloudFileId: String, destination: JavaFile): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val drive = driveClient() ?: return@withContext Result.failure(Exception(notLinkedMessage()))
            FileOutputStream(destination).use { out ->
                drive.files().get(cloudFileId).executeMediaAndDownloadTo(out)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteFile(cloudFileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val drive = driveClient() ?: return@withContext Result.failure(Exception(notLinkedMessage()))
            drive.files().delete(cloudFileId).execute()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createFolder(name: String, parentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val drive = driveClient() ?: return@withContext Result.failure(Exception(notLinkedMessage()))
            val metadata = DriveFile().apply {
                this.name = name
                mimeType = "application/vnd.google-apps.folder"
                parents = listOf(parentId.ifBlank { "root" })
            }
            drive.files().create(metadata).setFields("id").execute()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun unlink() {
        signInClient().signOut()
        prefs.clearCloudToken(CloudProvider.GOOGLE_DRIVE)
    }

    override suspend fun getStorageQuota(): Result<com.myfile.ui.cloud.CloudStorageQuota> = withContext(Dispatchers.IO) {
        try {
            val drive = driveClient() ?: return@withContext Result.failure(Exception(notLinkedMessage()))
            val about = drive.about().get().setFields("storageQuota").execute()
            val quota = about.storageQuota
            val used = quota?.usage ?: 0L
            val total = quota?.limit ?: 0L // Drive trả về null nếu tài khoản có dung lượng "không giới hạn"
            Result.success(com.myfile.ui.cloud.CloudStorageQuota(used, total))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
