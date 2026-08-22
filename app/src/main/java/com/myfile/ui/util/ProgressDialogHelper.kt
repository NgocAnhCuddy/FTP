package com.myfile.ui.util

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfile.ui.databinding.DialogProgressBinding
import java.util.Locale

/**
 * Dialog tiến trình dùng chung cho các thao tác chạy lâu và có thể tính % (nén, giải nén,
 * sao chép/di chuyển file). Hiện % hoàn thành + thời gian đã trôi qua dạng mm:ss, tự cập nhật
 * mỗi 200ms bằng Handler trên main thread — an toàn khi gọi update() liên tục từ luồng IO
 * (mọi lệnh gọi được post lên main thread bên trong).
 */
class ProgressDialogHelper(activity: Activity, titleRes: Int) {

    private val binding = DialogProgressBinding.inflate(activity.layoutInflater)
    private val dialog = MaterialAlertDialogBuilder(activity)
        .setView(binding.root)
        .setCancelable(false)
        .create()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startTime = System.currentTimeMillis()

    init {
        binding.tvProgressTitle.setText(titleRes)
        binding.tvProgressPercent.text = "0%"
        binding.tvProgressTime.text = "00:00"
        dialog.show()
    }

    /** Cập nhật tên file hiện đang xử lý (hiện dưới tiêu đề), gọi an toàn từ bất kỳ luồng nào. */
    fun setCurrentFile(name: String) {
        mainHandler.post {
            if (dialog.isShowing) binding.tvProgressFilename.text = name
        }
    }

    /** Cập nhật % dựa trên [done]/[total] byte, gọi an toàn từ bất kỳ luồng nào (kể cả luồng IO). */
    fun update(done: Long, total: Long) {
        val percent = if (total > 0) ((done.toDouble() / total) * 100).toInt().coerceIn(0, 100) else 0
        mainHandler.post {
            if (!dialog.isShowing) return@post
            binding.progressBar.progress = percent
            binding.tvProgressPercent.text = "$percent%"
            binding.tvProgressTime.text = formatElapsed(System.currentTimeMillis() - startTime)
        }
    }

    fun dismiss() {
        mainHandler.post { if (dialog.isShowing) dialog.dismiss() }
    }

    private fun formatElapsed(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}
