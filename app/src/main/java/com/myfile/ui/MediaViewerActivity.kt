package com.myfile.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.myfile.ui.adapters.MediaPagerAdapter
import com.myfile.ui.databinding.ActivityMediaViewerBinding
import com.myfile.ui.media.AudioPlayerController
import com.myfile.ui.model.MediaItem

private const val COUNTER_AUTO_HIDE_MS = 5_000L

/**
 * Trình xem ảnh/video trực tiếp trong app, không phải mở app ngoài. Nhận danh sách các mục
 * cùng danh mục (Ảnh hoặc Video) và vị trí bắt đầu, cho vuốt trái/phải để chuyển mục kế tiếp
 * giống trải nghiệm thư viện ảnh tiêu chuẩn.
 *
 * Kết nối 1 lần tới AudioPlaybackService (dùng chung service phát audio nền có sẵn) để lấy
 * MediaController cho video: nhờ đó video phát qua ExoPlayer thật (có thanh tua, không còn bug
 * vòng xoay loading vô hạn của VideoView cũ) và TIẾP TỤC PHÁT khi rời màn hình này, giống hệt
 * hành vi audio nền đã có.
 */
class MediaViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMediaViewerBinding
    private lateinit var items: List<MediaItem>
    private val playerController = AudioPlayerController(this)

    // Tự ẩn ô đếm "1/N" sau 5 giây không thao tác — hiện lại và reset đếm giờ mỗi khi người
    // dùng chạm vào màn hình (vuốt đổi trang, tap play/pause...) hoặc khi đổi sang trang khác.
    private val hideCounterHandler = Handler(Looper.getMainLooper())
    private val hideCounterRunnable = Runnable {
        binding.tvCounter.animate().alpha(0f).setDuration(200).start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uris = intent.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()
        val names = intent.getStringArrayListExtra(EXTRA_NAMES) ?: arrayListOf()
        val realPaths = intent.getStringArrayListExtra(EXTRA_REAL_PATHS) ?: arrayListOf()
        val videoFlags = intent.getBooleanArrayExtra(EXTRA_IS_VIDEO) ?: BooleanArray(uris.size)
        val startPosition = intent.getIntExtra(EXTRA_START_POSITION, 0)

        items = uris.indices.map { i ->
            MediaItem(
                uri = uris[i],
                name = names.getOrElse(i) { "" },
                isVideo = videoFlags.getOrElse(i) { false },
                realPath = realPaths.getOrNull(i)?.ifBlank { null }
            )
        }

        if (items.isEmpty()) {
            finish()
            return
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_media_viewer)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.action_cast) {
                openCastScreen()
                true
            } else {
                false
            }
        }

        // Tải trước 1 trang mỗi bên khi vuốt — ảnh kế tiếp đã giải mã sẵn trong lúc người dùng
        // còn đang xem ảnh hiện tại, vuốt sang không phải chờ decode mới thấy mượt.
        binding.viewPager.offscreenPageLimit = 1
        val clampedStart = startPosition.coerceIn(0, items.size - 1)
        updateHeader(clampedStart)

        val hasVideo = items.any { it.isVideo }
        if (hasVideo) {
            // Chỉ kết nối service nếu trang này thực sự có video — trang toàn ảnh không cần khởi
            // động MediaSessionService, tránh tốn tài nguyên không cần thiết.
            playerController.connect(
                onReady = { controller ->
                    // isFinishing/isDestroyed: callback async có thể tới SAU KHI người dùng đã
                    // bấm back và Activity đang/đã bị hủy (đặc biệt khi service khởi động nguội
                    // lần đầu và mất nhiều thời gian hơn) — gán adapter/setCurrentItem lúc đó
                    // thao tác lên view đã hủy và ném IllegalStateException -> crash.
                    if (isFinishing || isDestroyed) return@connect
                    val adapter = MediaPagerAdapter(items, controller)
                    binding.viewPager.adapter = adapter
                    binding.viewPager.setCurrentItem(clampedStart, false)
                    activateCurrentPage(adapter, clampedStart)
                },
                onError = {
                    // Không kết nối được service (cold start thất bại, bị hệ thống chặn
                    // foreground service, v.v.) — vẫn cho xem video bằng adapter không có
                    // controller thay vì để màn hình treo/crash. MediaPagerAdapter đã xử lý
                    // controller == null: hiện nút play với thông báo lỗi rõ ràng thay vì
                    // vòng xoay loading vô hạn.
                    if (isFinishing || isDestroyed) return@connect
                    binding.viewPager.adapter = MediaPagerAdapter(items, null)
                    binding.viewPager.setCurrentItem(clampedStart, false)
                }
            )
        } else {
            binding.viewPager.adapter = MediaPagerAdapter(items, null)
            binding.viewPager.setCurrentItem(clampedStart, false)
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateHeader(position)
                (binding.viewPager.adapter as? MediaPagerAdapter)?.let { activateCurrentPage(it, position) }
                scheduleHideCounter()
            }
        })

        scheduleHideCounter()
    }

    /**
     * Bắt TẤT CẢ sự kiện chạm trong toàn màn hình (kể cả chạm vào video, nút play/pause, seek
     * bar... nằm sâu trong ViewHolder của ViewPager2) để reset lại đếm giờ 5 giây — không dùng
     * setOnTouchListener trên 1 view cụ thể vì các nút điều khiển bên trong ViewPager2 sẽ tiêu
     * thụ sự kiện trước khi tới được view cha đó.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            scheduleHideCounter()
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Hiện lại ô đếm "1/N" ngay lập tức rồi đặt lịch ẩn sau 5 giây kể từ bây giờ — gọi lại mỗi lần có tương tác để reset đếm giờ. */
    private fun scheduleHideCounter() {
        hideCounterHandler.removeCallbacks(hideCounterRunnable)
        binding.tvCounter.animate().cancel()
        binding.tvCounter.alpha = 1f
        hideCounterHandler.postDelayed(hideCounterRunnable, COUNTER_AUTO_HIDE_MS)
    }

    /**
     * NGUYÊN NHÂN CRASH "LẦN ĐẦU XEM VIDEO": ViewPager2 dùng offscreenPageLimit = 1 nên preload
     * sẵn 1 trang kế bên trước khi người dùng thực sự vuốt tới. Nếu trang preload đó CŨNG LÀ
     * VIDEO, trước đây MediaPagerAdapter gán controller (MediaController dùng chung với audio
     * nền) cho CẢ 2 PlayerView cùng lúc — 2 view tranh giành 1 Player/Surface, ExoPlayer không
     * hỗ trợ an toàn việc này và crash. Rõ nhất ở lần mở đầu tiên vì service cold-start chậm
     * khiến việc bind 2 trang dồn gần nhau hơn bình thường.
     *
     * Cần lấy đúng RecyclerView NỘI BỘ của ViewPager2 (child đầu tiên) để adapter tìm đúng
     * ViewHolder theo position và chỉ activate() trang thực sự đang xem.
     */
    private fun activateCurrentPage(adapter: MediaPagerAdapter, position: Int) {
        val viewPager = binding.viewPager
        // post{}: ngay sau setCurrentItem(), RecyclerView nội bộ của ViewPager2 có thể CHƯA kịp
        // tạo/bind ViewHolder cho vị trí này (layout pass chạy bất đồng bộ) — gọi ngay lập tức có
        // thể tìm thấy null và bỏ lỡ lần activate() đầu tiên (trang mở ra không tự phát). post()
        // đợi 1 vòng lặp message queue để layout ổn định trước khi tìm ViewHolder.
        viewPager.post {
            if (isFinishing || isDestroyed) return@post
            val recyclerView = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return@post
            adapter.setActivePage(recyclerView, position)
        }
    }

    override fun onDestroy() {
        // KHÔNG gọi player.stop()/pause() ở đây: mục đích là để video tiếp tục phát nền qua
        // AudioPlaybackService giống audio, giống trải nghiệm VLC. Chỉ giải phóng MediaController
        // (kết nối phía client) — ExoPlayer thật trong service vẫn sống và tiếp tục phát.
        hideCounterHandler.removeCallbacks(hideCounterRunnable)
        playerController.release()
        super.onDestroy()
    }

    private fun updateHeader(position: Int) {
        val item = items.getOrNull(position) ?: return
        binding.toolbar.title = item.name
        binding.tvCounter.text = "${position + 1} / ${items.size}"
    }

    private fun openCastScreen() {
        val current = items.getOrNull(binding.viewPager.currentItem) ?: return
        val realPath = current.realPath
        if (realPath == null) {
            android.widget.Toast.makeText(
                this, getString(R.string.cast_no_devices), android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        val intent = Intent(this, CastToTvActivity::class.java).apply {
            putExtra(CastToTvActivity.EXTRA_FILE_PATH, realPath)
            putExtra(CastToTvActivity.EXTRA_FILE_NAME, current.name)
            putExtra(CastToTvActivity.EXTRA_IS_VIDEO, current.isVideo)
        }
        startActivity(intent)
    }

    companion object {
        const val EXTRA_URIS = "extra_uris"
        const val EXTRA_NAMES = "extra_names"
        const val EXTRA_REAL_PATHS = "extra_real_paths"
        const val EXTRA_IS_VIDEO = "extra_is_video"
        const val EXTRA_START_POSITION = "extra_start_position"
    }
}
