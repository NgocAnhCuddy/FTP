package com.myfile.ui.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Phát hiện thẻ nhớ SD (bộ nhớ ngoài tháo rời được, khác với bộ nhớ trong chính) và quản lý
 * quyền truy cập SAF (Storage Access Framework) cho nó.
 *
 * Từ Android 10 trở lên, app không thể ghi trực tiếp qua java.io.File vào SD card thật
 * (khác thư mục riêng của app) nếu không có quyền MANAGE_EXTERNAL_STORAGE toàn cục — mà
 * quyền đó Google Play hạn chế cấp. Cách đáng tin cậy và được Android chính thức hỗ trợ là
 * SAF: người dùng chọn thư mục gốc SD card 1 lần qua ACTION_OPEN_DOCUMENT_TREE, app xin
 * quyền vĩnh viễn (persistable permission) trên Uri đó rồi thao tác qua DocumentFile.
 */
object SdCardUtils {

    /** Trả về đường dẫn thư mục gốc của thẻ SD tháo rời được, nếu thiết bị có gắn 1 thẻ. */
    fun findSdCardPath(context: Context): String? {
        try {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val volumes = storageManager.storageVolumes
                for (volume in volumes) {
                    if (volume.isRemovable && !volume.isPrimary) {
                        // API cấp path trực tiếp chỉ có ở tối thiểu Android 11 (R) qua directory;
                        // với các bản cũ hơn, suy ra path qua reflection an toàn (chỉ đọc, có try/catch).
                        val path = getVolumePathCompat(volume)
                        if (path != null) return path
                    }
                }
            }
        } catch (e: Exception) {
            // Bỏ qua — một số thiết bị/ROM tùy biến có thể không hỗ trợ đầy đủ StorageManager API
        }
        return fallbackScanExternalDirs(context)
    }

    private fun getVolumePathCompat(volume: android.os.storage.StorageVolume): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                volume.directory?.absolutePath
            } else {
                // Reflection cho Android 7–10: StorageVolume có method ẩn getPath()/getPathFile()
                val method = volume.javaClass.getMethod("getPath")
                method.invoke(volume) as? String
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Dự phòng: quét /storage/XXXX-XXXX kiểu thẻ SD phổ biến trên nhiều thiết bị Android. */
    private fun fallbackScanExternalDirs(context: Context): String? {
        return try {
            val dirs = context.getExternalFilesDirs(null)
            for (dir in dirs) {
                if (dir == null) continue
                if (Environment.isExternalStorageRemovable(dir)) {
                    // Cắt path app-specific để lấy về gốc thẻ, VD:
                    // /storage/1234-5678/Android/data/com.myfile.ui/files -> /storage/1234-5678
                    val path = dir.absolutePath.substringBefore("/Android/data")
                    if (path.isNotBlank() && File(path).exists()) return path
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Quyền SAF (đọc/ghi thật qua DocumentFile) ----------

    fun hasSavedTreeUri(context: Context): Boolean = loadTreeUri(context) != null

    fun loadTreeUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_TREE_URI, null) ?: return null
        return try { Uri.parse(raw) } catch (e: Exception) { null }
    }

    /** Lưu quyền vĩnh viễn trên Uri thư mục SD card mà người dùng vừa chọn qua SAF picker. */
    fun saveTreeUri(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // một số provider không hỗ trợ persistable — vẫn lưu Uri để dùng trong phiên hiện tại
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    fun clearTreeUri(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_TREE_URI).apply()
    }

    /** Thư mục gốc SAF (DocumentFile) đã được cấp quyền, hoặc null nếu chưa cấp/quyền đã mất hiệu lực. */
    fun getRootDocumentFile(context: Context): DocumentFile? {
        val uri = loadTreeUri(context) ?: return null
        return try {
            val doc = DocumentFile.fromTreeUri(context, uri)
            if (doc != null && doc.exists() && doc.canRead()) doc else null
        } catch (e: Exception) {
            null
        }
    }

    private const val PREFS_NAME = "sdcard_prefs"
    private const val KEY_TREE_URI = "sd_tree_uri"
}
