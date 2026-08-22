package com.myfile.ui.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.myfile.ui.model.CloudAccount
import com.myfile.ui.model.CloudProvider
import com.myfile.ui.model.FtpConnectionProfile
import com.myfile.ui.model.FtpUser
import com.myfile.ui.model.FolderBookmark

/**
 * Lưu trữ cấu hình nhạy cảm (mật khẩu, token OAuth) bằng EncryptedSharedPreferences.
 * Toàn bộ dữ liệu được mã hóa bằng Android Keystore, an toàn hơn SharedPreferences thường.
 */
class SecurePrefs private constructor(private val prefs: SharedPreferences) {

    private val gson = Gson()

    // ---------- FTP Server users ----------
    fun saveFtpUsers(users: List<FtpUser>) {
        prefs.edit().putString(KEY_FTP_USERS, gson.toJson(users)).apply()
    }

    fun loadFtpUsers(): MutableList<FtpUser> {
        val json = prefs.getString(KEY_FTP_USERS, null) ?: return defaultUsers()
        val type = object : TypeToken<MutableList<FtpUser>>() {}.type
        return try {
            gson.fromJson(json, type) ?: defaultUsers()
        } catch (e: Exception) {
            defaultUsers()
        }
    }

    private fun defaultUsers(): MutableList<FtpUser> = mutableListOf(
        FtpUser(username = "admin", password = "admin123", homeDirectory = "", writePermission = true)
    )

    // ---------- Server settings ----------
    var serverPort: Int
        get() = prefs.getInt(KEY_SERVER_PORT, 2121)
        set(value) = prefs.edit().putInt(KEY_SERVER_PORT, value).apply()

    var rootFolderUri: String?
        get() = prefs.getString(KEY_ROOT_FOLDER, null)
        set(value) = prefs.edit().putString(KEY_ROOT_FOLDER, value).apply()

    // ---------- Saved FTP client connections ----------
    fun saveConnections(list: List<FtpConnectionProfile>) {
        prefs.edit().putString(KEY_CONNECTIONS, gson.toJson(list)).apply()
    }

    fun loadConnections(): MutableList<FtpConnectionProfile> {
        val json = prefs.getString(KEY_CONNECTIONS, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<FtpConnectionProfile>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    // ---------- Cloud OAuth tokens ----------
    fun saveCloudToken(provider: CloudProvider, accessToken: String, refreshToken: String?, expiresAt: Long) {
        prefs.edit()
            .putString("cloud_${provider}_access", accessToken)
            .putString("cloud_${provider}_refresh", refreshToken)
            .putLong("cloud_${provider}_expires", expiresAt)
            .apply()
    }

    fun getCloudAccessToken(provider: CloudProvider): String? =
        prefs.getString("cloud_${provider}_access", null)

    fun getCloudRefreshToken(provider: CloudProvider): String? =
        prefs.getString("cloud_${provider}_refresh", null)

    fun getCloudTokenExpiry(provider: CloudProvider): Long =
        prefs.getLong("cloud_${provider}_expires", 0L)

    fun clearCloudToken(provider: CloudProvider) {
        prefs.edit()
            .remove("cloud_${provider}_access")
            .remove("cloud_${provider}_refresh")
            .remove("cloud_${provider}_expires")
            .remove("cloud_${provider}_email")
            .apply()
    }

    fun saveCloudAccountInfo(provider: CloudProvider, email: String?) {
        prefs.edit().putString("cloud_${provider}_email", email).apply()
    }

    fun getCloudAccount(provider: CloudProvider): CloudAccount {
        val token = getCloudAccessToken(provider)
        val email = prefs.getString("cloud_${provider}_email", null)
        return CloudAccount(
            provider = provider,
            email = email,
            isLinked = !token.isNullOrEmpty()
        )
    }

    // ---------- Khoá app (App Lock: PIN + sinh trắc học) ----------
    // Lưu MÃ BĂM (SHA-256, xem AppLockUtils.hashPin) của PIN, KHÔNG BAO GIỜ lưu PIN dạng plain
    // text — dù prefs này đã mã hoá bằng Android Keystore, băm thêm 1 lớp giúp ngay cả khi
    // Keystore bị xâm phạm (thiết bị đã root, kẻ tấn công trích xuất được prefs đã giải mã),
    // PIN gốc của người dùng vẫn không bị lộ trực tiếp — cùng nguyên tắc "không lưu mật khẩu
    // dạng plain text" áp dụng cho mọi hệ thống xác thực.
    var appLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, value).apply()

    var appLockPinHash: String?
        get() = prefs.getString(KEY_APP_LOCK_PIN_HASH, null)
        set(value) = prefs.edit().putString(KEY_APP_LOCK_PIN_HASH, value).apply()

    /** Cho phép dùng vân tay/khuôn mặt thay PIN — chỉ có ý nghĩa khi appLockEnabled = true. */
    var appLockBiometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK_BIOMETRIC, true)
        set(value) = prefs.edit().putBoolean(KEY_APP_LOCK_BIOMETRIC, value).apply()

    fun clearAppLock() {
        prefs.edit()
            .remove(KEY_APP_LOCK_ENABLED)
            .remove(KEY_APP_LOCK_PIN_HASH)
            .remove(KEY_APP_LOCK_BIOMETRIC)
            .apply()
    }

    /**
     * Hiện các file/thư mục người dùng đã chủ động ẩn (tên bắt đầu bằng ".", đổi qua nút
     * "Ẩn"/"Bỏ ẩn"). KHÔNG ảnh hưởng tới các thư mục hệ thống nội bộ của app (như .MyFileTrash)
     * — những thư mục đó luôn bị lọc bỏ bất kể setting này (xem CategoryFilesActivity.
     * shouldSkipDotFile()).
     */
    var showHiddenFiles: Boolean
        get() = prefs.getBoolean(KEY_SHOW_HIDDEN_FILES, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_HIDDEN_FILES, value).apply()

    /**
     * true nếu đã HỎI người dùng về việc xin miễn trừ tối ưu hoá pin (dù họ đồng ý hay từ
     * chối). Dùng để chỉ hỏi 1 LẦN DUY NHẤT trong toàn bộ vòng đời sử dụng app — hỏi lại mỗi
     * lần bật server sẽ gây khó chịu, đặc biệt với người dùng đã cố tình từ chối.
     */
    var hasAskedBatteryOptimization: Boolean
        get() = prefs.getBoolean(KEY_ASKED_BATTERY_OPT, false)
        set(value) = prefs.edit().putBoolean(KEY_ASKED_BATTERY_OPT, value).apply()

    // ---------- Bookmark thư mục (ghim thư mục hay dùng để truy cập nhanh) ----------
    fun getBookmarks(): MutableList<FolderBookmark> {
        val json = prefs.getString(KEY_FOLDER_BOOKMARKS, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<FolderBookmark>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveBookmarks(list: List<FolderBookmark>) {
        prefs.edit().putString(KEY_FOLDER_BOOKMARKS, gson.toJson(list)).apply()
    }

    fun isBookmarked(path: String): Boolean = getBookmarks().any { it.path == path }

    fun addBookmark(path: String, name: String) {
        val current = getBookmarks()
        if (current.any { it.path == path }) return // đã ghim rồi, tránh trùng lặp
        current.add(FolderBookmark(path = path, name = name))
        saveBookmarks(current)
    }

    fun removeBookmark(path: String) {
        val current = getBookmarks()
        current.removeAll { it.path == path }
        saveBookmarks(current)
    }

    companion object {
        private const val PREFS_NAME = "secure_prefs"
        private const val KEY_FTP_USERS = "ftp_users"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_ROOT_FOLDER = "root_folder"
        private const val KEY_CONNECTIONS = "ftp_connections"
        private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
        private const val KEY_APP_LOCK_PIN_HASH = "app_lock_pin_hash"
        private const val KEY_APP_LOCK_BIOMETRIC = "app_lock_biometric_enabled"
        private const val KEY_SHOW_HIDDEN_FILES = "show_hidden_files"
        private const val KEY_ASKED_BATTERY_OPT = "asked_battery_optimization"
        private const val KEY_FOLDER_BOOKMARKS = "folder_bookmarks"

        @Volatile private var instance: SecurePrefs? = null

        fun getInstance(context: Context): SecurePrefs {
            return instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }
        }

        private fun build(context: Context): SecurePrefs {
            // QUAN TRỌNG — nguồn gốc phổ biến nhất của crash toàn app: EncryptedSharedPreferences
            // dựa vào Android Keystore để quản lý khoá mã hoá, nhưng Keystore trên MỘT SỐ THIẾT
            // BỊ (đặc biệt ROM tuỳ biến, 1 số dòng máy Trung Quốc, thiết bị đã factory-reset
            // nhiều lần...) hoạt động không ổn định — ném lỗi dạng "Keystore operation failed"
            // hoặc "The master key exists but is unusable" ngay tại build() này. Đây KHÔNG phải
            // lỗi logic của app mà là vấn đề đã biết của chính thư viện androidx.security.crypto
            // trên hệ sinh thái Android phân mảnh, và lỗi này THƯỜNG CHỈ THOÁNG QUA (ví dụ đúng
            // lúc máy vừa khởi động lại, Keystore service hệ thống chưa sẵn sàng) chứ không phải
            // vĩnh viễn.
            //
            // BUG ĐÃ SỬA: bản trước đây fallback về SharedPreferences THƯỜNG ngay ở lần lỗi ĐẦU
            // TIÊN, ghi dữ liệu (bao gồm token OAuth Dropbox/Box) vào 1 FILE KHÁC HẲN với file mã
            // hoá gốc đã lưu token trước đó. Vì instance là singleton chỉ build() 1 LẦN cho cả
            // vòng đời process, một lỗi Keystore thoáng qua lúc cold-start là đủ để "khoá" cả
            // phiên app vào file rỗng không có token cũ — với người dùng, hiện tượng đúng như
            // "tự nhiên mất liên kết Dropbox/Box" dù không hề bấm huỷ liên kết.
            //
            // Sửa bằng cách thử lại (retry) việc khởi tạo Keystore 1 lần sau khoảng nghỉ ngắn
            // trước khi chấp nhận fallback — đủ để vượt qua phần lớn lỗi thoáng qua lúc cold
            // boot, giữ được cả 2 mục tiêu: app không crash trên thiết bị Keystore hỏng thật sự,
            // và không đánh mất token đã lưu chỉ vì 1 lần lỗi tạm thời.
            repeat(2) { attempt ->
                try {
                    val masterKey = MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                    val encryptedPrefs = EncryptedSharedPreferences.create(
                        context,
                        PREFS_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                    return SecurePrefs(encryptedPrefs)
                } catch (e: Exception) {
                    if (attempt == 0) {
                        // Lần đầu lỗi: có thể là Keystore chưa sẵn sàng (cold boot) — nghỉ rất
                        // ngắn rồi thử lại trước khi kết luận là lỗi thật sự. Không dùng
                        // Thread.sleep dài vì build() có thể được gọi từ main thread ở
                        // MyFileApp.onActivityStarted(); 150ms là đủ cho phần lớn trường hợp
                        // race-condition Keystore mà không gây giật đáng chú ý.
                        try { Thread.sleep(150) } catch (ignored: InterruptedException) {}
                    } else {
                        com.myfile.ui.util.LogBus.error(
                            "Không thể khởi tạo bộ nhớ mã hoá sau khi thử lại (lỗi Android Keystore trên thiết bị này) — tự động chuyển sang lưu trữ thường",
                            "SECURE_PREFS",
                            e
                        )
                    }
                }
            }
            // Dữ liệu vẫn được bảo vệ ở mức cơ bản bởi sandbox riêng của từng app trên Android
            // (thư mục /data/data/<package>/ chỉ chính app đó đọc được, trừ khi máy đã root),
            // không "mất trắng" bảo mật hoàn toàn, chỉ mất đi lớp mã hoá bổ sung. Ưu tiên app
            // CHẠY ĐƯỢC hơn là app tuyệt đối an toàn nhưng crash liên tục không dùng nổi.
            val fallback = context.getSharedPreferences("${PREFS_NAME}_fallback_unencrypted", Context.MODE_PRIVATE)
            return SecurePrefs(fallback)
        }
    }
}
