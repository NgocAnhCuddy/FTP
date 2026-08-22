package com.myfile.ui.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Xin miễn trừ khỏi trình quản lý pin của hệ thống — cần thiết để các foreground service
 * (FTP/SFTP/Media Cast) không bị OneUI (Samsung) hoặc HyperOS/MIUI (Xiaomi) tự động dừng sau
 * vài phút chạy nền, dù đã khai đúng foregroundServiceType trong Manifest.
 *
 * 2 hãng này có trình quản lý pin RIÊNG, mạnh tay hơn nhiều so với Android gốc (AOSP):
 *  - Samsung OneUI: "Đưa ứng dụng vào chế độ ngủ đông" (Put unused apps to sleep) / Adaptive
 *    Battery tự động hạn chế app không tương tác >3 ngày, và "Battery" > "Background usage
 *    limits" > "Sleeping/Deep sleeping apps" có thể kill app đang chạy nền dù có foreground
 *    service, nếu người dùng chưa từng thêm app vào "Never sleeping apps".
 *  - Xiaomi HyperOS/MIUI: yêu cầu bật riêng biệt CẢ 3 mục — "Autostart" (Tự khởi động),
 *    "Background autostart" (khác Autostart, nằm trong "Other permissions"/quyền khác), và
 *    "Battery saver" > "No restrictions" cho từng app — thiếu 1 trong 3 vẫn có thể bị kill.
 *
 * Cách tiếp cận: (1) dùng API chuẩn Android (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) trước —
 * hoạt động trên mọi hãng bao gồm cả Samsung/Xiaomi ở mức cơ bản; (2) NẾU phát hiện đang chạy
 * trên Xiaomi, cung cấp thêm lối tắt tới đúng màn "Autostart" của MIUI/HyperOS — màn này KHÔNG
 * có API chuẩn Android tương đương, chỉ có thể mở qua ComponentName cố định do Xiaomi định
 * nghĩa (không đảm bảo tồn tại/đúng như mong đợi trên MỌI phiên bản MIUI/HyperOS, nên luôn bọc
 * try-catch và có phương án dự phòng).
 */
object BatteryOptimizationUtils {

    /** true nếu app ĐÃ được miễn trừ tối ưu hoá pin (không cần hỏi lại). */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Mở hộp thoại hệ thống xin miễn trừ trực tiếp (không cần vào Settings thủ công) — đây là
     * API chuẩn Android, hoạt động trên MỌI hãng máy bao gồm Samsung/Xiaomi, nhưng Xiaomi vẫn
     * có thể yêu cầu thêm 2 bước khác (xem [openManufacturerAutostartSettings]).
     */
    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return // API này chỉ có từ Android 6.0
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Một số ROM (đặc biệt vài bản HyperOS/MIUI tuỳ biến sâu) gỡ bỏ màn hình này khỏi
            // hệ thống — fallback sang màn danh sách tối ưu hoá pin chung, ít trực tiếp hơn
            // nhưng vẫn cho phép người dùng tự tìm app và bỏ chọn tối ưu hoá thủ công.
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                LogBus.warning("Không mở được màn cài đặt tối ưu hoá pin trên thiết bị này", "BATTERY_OPT")
            }
        }
    }

    /** true nếu thiết bị đang chạy MIUI/HyperOS (Xiaomi/Redmi/POCO) — dựa vào Build.MANUFACTURER. */
    fun isXiaomiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer == "xiaomi" || manufacturer == "redmi" || manufacturer == "poco"
    }

    /** true nếu thiết bị đang chạy OneUI (Samsung). */
    fun isSamsungDevice(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /**
     * Mở màn "Quyền tự khởi động" (Autostart) riêng của MIUI/HyperOS — KHÔNG có API Android
     * chuẩn cho màn này, dùng ComponentName nội bộ do Xiaomi định nghĩa. Danh sách activity thử
     * lần lượt vì tên package/class đã đổi qua nhiều phiên bản MIUI → HyperOS; không có gì đảm
     * bảo activity nào tồn tại trên 1 máy cụ thể, nên PHẢI thử lần lượt và bọc try-catch từng
     * bước — nếu tất cả thất bại, coi như không có sẵn màn này trên thiết bị đó (một số bản
     * HyperOS mới đã gộp Autostart vào App Info thông thường, không còn màn riêng).
     */
    fun openXiaomiAutostartSettings(context: Context): Boolean {
        val candidates = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.securitycenter" to "com.miui.securitycenter.permission.AppPermissionsEditorActivity"
        )
        for ((pkg, cls) in candidates) {
            try {
                val intent = Intent().apply {
                    component = android.content.ComponentName(pkg, cls)
                }
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                // Thử candidate tiếp theo.
            }
        }
        return false
    }

    /**
     * Mở màn chi tiết app trong Settings hệ thống (App Info) — luôn hoạt động trên MỌI thiết
     * bị/hãng, dùng làm phương án dự phòng cuối cùng khi các lối tắt riêng của hãng thất bại.
     * Từ màn này người dùng tự tìm mục Battery/Pin để bật "No restrictions"/"Không giới hạn".
     */
    fun openAppInfoSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            LogBus.warning("Không mở được màn thông tin ứng dụng", "BATTERY_OPT")
        }
    }
}
