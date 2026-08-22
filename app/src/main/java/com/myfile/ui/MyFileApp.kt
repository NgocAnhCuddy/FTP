package com.myfile.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.decode.GifDecoder
import coil.memory.MemoryCache
import coil.disk.DiskCache

/**
 * Cấu hình Coil (thư viện load thumbnail ảnh/video) riêng thay vì dùng mặc định:
 * - Bộ nhớ cache giới hạn 15% RAM khả dụng thay vì mặc định 25% — tránh app bị hệ thống
 *   kill nền trên máy RAM thấp (2-4GB) khi lướt thư viện ảnh nhiều.
 * - Bitmap RGB_565 thay vì ARGB_8888 cho thumbnail — giảm ~50% bộ nhớ mỗi ảnh, quan trọng
 *   với GPU/RAM yếu trên chip MediaTek/Exynos tầm trung.
 * - ImageDecoderDecoder: Coil KHÔNG bật sẵn decoder này mặc định. Thiếu nó khiến mọi ảnh
 *   HEIC/HEIF (định dạng chụp mặc định của iPhone từ iOS 11, và nhiều Android Camera app)
 *   không giải mã được — hiện màn đen/lỗi khi xem trong Trình xem ảnh và không hiện thumbnail
 *   ở chế độ lưới. Chỉ khả dụng từ Android 9 (API 28) trở lên do phụ thuộc android.graphics.ImageDecoder;
 *   máy Android 7-8 vẫn sẽ không xem trước được HEIC (giới hạn của hệ điều hành, không phải app).
 * - allowHardware(false): BẮT BUỘC khi dùng chung Coil + crossfade(true). Trên API 26+, cả
 *   BitmapFactoryDecoder (jpg/png thường) lẫn ImageDecoderDecoder (heic/heif) đều có thể trả về
 *   HARDWARE bitmap. Hiệu ứng crossfade lại vẽ bitmap bằng software Canvas (View.draw thường) —
 *   mà software canvas KHÔNG vẽ được hardware bitmap, ném thẳng "IllegalArgumentException:
 *   Software rendering doesn't support hardware bitmaps" và crash app ngay khi mở ẢNH (bất kỳ
 *   định dạng nào — jpg, png, heic đều dính, không riêng gì HEIC). Video không bị vì không qua
 *   đường decode bitmap này. Tắt hardware bitmap cũng là điều kiện để bitmapConfig(RGB_565) ở
 *   dưới thực sự có tác dụng — hardware bitmap luôn bỏ qua bitmapConfig được set.
 */
class MyFileApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()

        com.myfile.ui.util.LogBus.init(this)

        // Crash handler toàn cục: bắt MỌI exception không được xử lý ở bất kỳ đâu trong app
        // (kể cả coroutine không có try/catch, background thread...) TRƯỚC KHI hệ thống Android
        // kill tiến trình. Không có handler này, người dùng chỉ thấy app "tự out về màn hình
        // chính" mà không có bất kỳ dấu vết gì trong Bảng điều khiển gỡ lỗi — vì log cũ hoàn
        // toàn nằm trong RAM và biến mất cùng lúc tiến trình chết. LogBus.crash() ghi đồng bộ
        // xuống file NGAY LẬP TỨC, nên dù tiến trình chết ngay dòng sau, log đã an toàn trên
        // đĩa — mở lại app sẽ thấy ngay nguyên nhân ở tab "Cảnh báo"/"Tất cả".
        //
        // Sau khi ghi log xong, VẪN gọi lại default handler gốc của hệ thống (defaultHandler)
        // để hành vi crash cuối cùng (kill tiến trình, dialog "Ứng dụng đã dừng" của OS nếu
        // debug build...) diễn ra bình thường như trước — không cố "nuốt" crash rồi cho app
        // chạy tiếp ở trạng thái không xác định, việc đó nguy hiểm hơn là để crash xảy ra.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                com.myfile.ui.util.LogBus.crash(throwable)
            } catch (e: Exception) {
                // Ghi log lỗi cũng không được phép ném exception mới đè lên exception gốc.
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        registerActivityLifecycleCallbacks(AppLockCallbacks())
    }

    /**
     * Theo dõi vòng đời TOÀN BỘ Activity trong app để tự động hiện [AppLockActivity] đúng lúc
     * app quay lại từ nền (home, chuyển app khác, khoá màn hình thiết bị...) — KHÔNG hiện khi
     * chỉ đơn thuần chuyển từ Activity A sang Activity B bên trong chính app (đó không phải lúc
     * app "rời khỏi" người dùng).
     *
     * Cách phân biệt 2 trường hợp: đếm số Activity đang ở trạng thái "started" (đã gọi
     * onStart, chưa gọi onStop) bằng [startedActivityCount]. Khi số đếm chuyển từ 0 → 1, nghĩa
     * là toàn bộ tiến trình app VỪA quay lại foreground sau khi hoàn toàn ở nền — đây đúng lúc
     * cần khoá. Khi chuyển A → B trong cùng app, B.onStart() luôn chạy TRƯỚC A.onStop() (vòng
     * đời Android đảm bảo thứ tự này), nên count không bao giờ về 0 giữa 2 màn hình nội bộ.
     */
    private inner class AppLockCallbacks : android.app.Application.ActivityLifecycleCallbacks {
        private var startedActivityCount = 0

        override fun onActivityStarted(activity: android.app.Activity) {
            val wasInBackground = startedActivityCount == 0
            startedActivityCount++
            if (wasInBackground && activity !is AppLockActivity) {
                maybeShowLockScreen(activity)
            }
        }

        override fun onActivityStopped(activity: android.app.Activity) {
            startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
        }

        private fun maybeShowLockScreen(activity: android.app.Activity) {
            // BỌC TRY-CATCH TOÀN BỘ: đây là callback vòng đời (onActivityStarted) chạy cho MỌI
            // Activity, MỌI lần mở app — không có nơi nào khác bắt lỗi hộ nó. SecurePrefs dùng
            // EncryptedSharedPreferences (Android Keystore), API này CÓ THỂ ném exception trên
            // một số thiết bị/ROM tuỳ biến có Keystore bị lỗi — trước khi thêm App Lock, lỗi đó
            // (nếu có) chỉ ảnh hưởng 1-2 màn hình cụ thể lúc người dùng chủ động thao tác; giờ
            // nó chạy ở MỌI lần mở app nên phải tuyệt đối an toàn, không được để exception nào
            // lọt ra ngoài gây crash toàn app ngay từ màn hình đầu tiên.
            try {
                val prefs = com.myfile.ui.util.SecurePrefs.getInstance(activity)
                if (!prefs.appLockEnabled || prefs.appLockPinHash == null) return
                val intent = android.content.Intent(activity, AppLockActivity::class.java).apply {
                    putExtra(AppLockActivity.EXTRA_MODE, AppLockActivity.MODE_UNLOCK)
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                com.myfile.ui.util.LogBus.error("Lỗi khi kiểm tra khoá app, bỏ qua để không crash", "APP_LOCK", e)
            }
        }

        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: android.app.Activity) {}
        override fun onActivityPaused(activity: android.app.Activity) {}
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: android.app.Activity) {}
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                }
                add(GifDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("thumb_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowHardware(false)
            .crossfade(true)
            .build()
    }
}
