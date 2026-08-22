package com.myfile.ui.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.myfile.ui.R
import java.io.File

/**
 * Xử lý mở file dùng chung cho toàn app (thay cho việc mỗi Activity tự lặp lại code).
 *
 * Hành vi mặc định khi chạm vào 1 file (giống Cx File Explorer):
 * 1) Thử mở bằng ACTION_VIEW với mime đoán được — nếu có nhiều app phù hợp,
 *    hệ thống Android tự hiện hộp thoại "Open with" (ảnh 1) như bình thường.
 * 2) Nếu KHÔNG có app nào xử lý được, tự động hiện hộp thoại "Mở dạng"
 *    (Văn bản / Hình ảnh / Âm thanh / Video / Khác) để ép kiểu mở (ảnh 2),
 *    thay vì chỉ báo lỗi "không mở được".
 */
object FileOpenUtils {

    /** Trả về content:// Uri hợp lệ cho cả file local (qua FileProvider) lẫn file SAF (đã là content://). */
    fun resolveUri(activity: Activity, path: String): Uri {
        return if (path.startsWith("content://")) {
            Uri.parse(path)
        } else {
            FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", File(path))
        }
    }

    fun guessMime(fileName: String, knownMime: String? = null): String {
        return knownMime
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
            ?: "*/*"
    }

    /** Mở file theo hành vi mặc định (chạm 1 lần vào file trong danh sách). */
    fun openDefault(activity: Activity, rootView: View, path: String, fileName: String, knownMime: String? = null) {
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "pdf" -> { openInViewer(activity, com.myfile.ui.PdfViewerActivity::class.java, com.myfile.ui.PdfViewerActivity.EXTRA_FILE_PATH, path); return }
            "docx" -> { openInViewer(activity, com.myfile.ui.DocxViewerActivity::class.java, com.myfile.ui.DocxViewerActivity.EXTRA_FILE_PATH, path); return }
            "xlsx" -> { openInViewer(activity, com.myfile.ui.XlsxViewerActivity::class.java, com.myfile.ui.XlsxViewerActivity.EXTRA_FILE_PATH, path); return }
        }
        val uri = safeResolveUri(activity, rootView, path) ?: return
        val mime = guessMime(fileName, knownMime)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(activity.packageManager) == null) {
            showOpenAsDialog(activity, rootView, uri)
            return
        }
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showOpenAsDialog(activity, rootView, uri)
        }
    }

    /** "Mở bằng": luôn ép hệ thống hiện danh sách app để chọn (ảnh 1); rơi về "Mở dạng" nếu danh sách rỗng. */
    fun openWithChooser(activity: Activity, rootView: View, path: String, fileName: String, knownMime: String? = null) {
        val uri = safeResolveUri(activity, rootView, path) ?: return
        val mime = guessMime(fileName, knownMime)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(activity.packageManager) == null) {
            showOpenAsDialog(activity, rootView, uri)
            return
        }
        try {
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.btn_open_with)))
        } catch (e: ActivityNotFoundException) {
            showOpenAsDialog(activity, rootView, uri)
        }
    }

    private fun openInViewer(activity: Activity, target: Class<*>, extraKey: String, path: String) {
        val intent = Intent(activity, target).apply { putExtra(extraKey, path) }
        activity.startActivity(intent)
    }

    private fun safeResolveUri(activity: Activity, rootView: View, path: String): Uri? {
        return try {
            resolveUri(activity, path)
        } catch (e: Exception) {
            Snackbar.make(rootView, activity.getString(R.string.no_app_to_open), Snackbar.LENGTH_SHORT).show()
            null
        }
    }

    /** Hộp thoại "Mở dạng" (ảnh 2): ép mime tổng quát khi không có app nào phù hợp với mime gốc. */
    private fun showOpenAsDialog(activity: Activity, rootView: View, uri: Uri) {
        val labels = arrayOf(
            activity.getString(R.string.open_as_text),
            activity.getString(R.string.open_as_image),
            activity.getString(R.string.open_as_audio),
            activity.getString(R.string.open_as_video),
            activity.getString(R.string.open_as_other)
        )
        val forcedMimes = arrayOf("text/plain", "image/*", "audio/*", "video/*", "*/*")
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.open_as_title)
            .setMessage(R.string.open_as_hint)
            .setItems(labels) { _, which ->
                val forcedIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, forcedMimes[which])
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    activity.startActivity(forcedIntent)
                } catch (e: ActivityNotFoundException) {
                    Snackbar.make(rootView, activity.getString(R.string.no_app_to_open), Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
