package com.myfile.ui.util

import android.app.Activity
import android.content.Intent
import com.myfile.ui.R

/**
 * Hoạt ảnh chuyển màn hình dùng chung cho toàn app: trượt nhẹ + mờ dần,
 * để việc mở/đóng màn hình mượt mà và nhất quán thay vì hiệu ứng mặc định cứng của Android.
 */
object ActivityTransitions {

    /** Gọi ngay sau startActivity() khi đi tới màn hình mới (tiến lên). */
    fun forward(activity: Activity) {
        activity.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    /** Gọi ngay sau finish() khi quay lại màn hình trước (lùi về). */
    fun backward(activity: Activity) {
        activity.overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    /** Chuyển mờ dần, dùng cho các màn hình không có cảm giác "tiến/lùi" rõ ràng (vd. mở dialog toàn màn hình). */
    fun fade(activity: Activity) {
        activity.overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    /** Tiện ích gói gọn startActivity + hoạt ảnh tiến tới trong 1 lệnh. */
    fun startForward(activity: Activity, intent: Intent) {
        activity.startActivity(intent)
        forward(activity)
    }
}
