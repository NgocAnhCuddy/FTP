package com.myfile.ui.adapters

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.github.chrisbanes.photoview.PhotoView
import com.myfile.ui.R
import com.myfile.ui.model.MediaItem
import java.util.Locale

private const val TYPE_IMAGE = 0
private const val TYPE_VIDEO = 1
private const val SEEK_STEP_MS = 15_000L
private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * Adapter cho ViewPager2 trong MediaViewerActivity: mỗi trang là 1 ảnh (Coil, có cache)
 * hoặc 1 video.
 *
 * Video dùng chung [MediaController] (Media3) với [com.myfile.ui.media.AudioPlaybackService] —
 * cùng 1 service MediaSessionService dùng cho audio. Nhờ vậy video cũng có notification điều
 * khiển và TIẾP TỤC PHÁT (âm thanh) khi rời màn hình xem, giống hành vi audio nền đã có sẵn.
 * [controller] được [com.myfile.ui.MediaViewerActivity] kết nối 1 lần rồi truyền vào đây —
 * adapter không tự connect/release để tránh mở nhiều kết nối trùng khi RecyclerView tạo/hủy
 * nhiều ViewHolder.
 */
class MediaPagerAdapter(
    private val items: List<MediaItem>,
    private val controller: MediaController?
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var currentActivePosition = -1

    override fun getItemViewType(position: Int): Int =
        if (items[position].isVideo) TYPE_VIDEO else TYPE_IMAGE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_VIDEO) {
            VideoPageHolder(inflater.inflate(R.layout.item_media_page_video, parent, false), controller)
        } else {
            ImagePageHolder(inflater.inflate(R.layout.item_media_page_image, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is ImagePageHolder -> holder.bind(item)
            is VideoPageHolder -> {
                holder.bind(item)
                // Trang này vừa được (re)bind — nếu đây đúng là trang đang active (ví dụ
                // ViewPager2 tái tạo ViewHolder do cuộn qua lại), phục hồi lại trạng thái active
                // thay vì để nó nằm im chờ activate() không bao giờ được gọi lại.
                if (position == currentActivePosition) holder.activate()
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is VideoPageHolder) holder.detach()
    }

    /**
     * Gọi từ MediaViewerActivity mỗi khi ViewPager2 xác nhận trang [position] là trang đang
     * hiển thị (ViewPager2.OnPageChangeCallback.onPageSelected). Chỉ trang này được phép chiếm
     * MediaController dùng chung — mọi trang video khác (kể cả đã được ViewHolder tạo sẵn do
     * preload) phải bị deactivate() trước, tránh 2 PlayerView tranh giành cùng 1 Player.
     *
     * [recyclerView] là RecyclerView nội bộ của ViewPager2 (ViewPager2.getChildAt(0)) — dùng để
     * tìm đúng ViewHolder theo adapter position mà không cần tự quản lý danh sách ViewHolder.
     */
    fun setActivePage(recyclerView: RecyclerView, position: Int) {
        if (currentActivePosition == position) return
        val previousPosition = currentActivePosition
        currentActivePosition = position

        if (previousPosition in items.indices) {
            (recyclerView.findViewHolderForAdapterPosition(previousPosition) as? VideoPageHolder)?.deactivate()
        }
        (recyclerView.findViewHolderForAdapterPosition(position) as? VideoPageHolder)?.activate()
    }

    class ImagePageHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView = itemView.findViewById<PhotoView>(R.id.image_view)
        private val progress = itemView.findViewById<android.widget.ProgressBar>(R.id.page_progress)
        private val errorText = itemView.findViewById<android.widget.TextView>(R.id.tv_load_error)

        fun bind(item: MediaItem) {
            progress.visibility = View.VISIBLE
            errorText.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            // Reset về zoom 1x khi bind: ViewPager2/RecyclerView TÁI SỬ DỤNG view này khi vuốt qua
            // ảnh khác — nếu không reset, ảnh mới sẽ hiện lại đúng mức zoom/pan của ảnh cũ để lại.
            imageView.setScale(1f, false)
            imageView.load(Uri.parse(item.uri)) {
                // Bật lại hardware bitmap CHỈ RIÊNG request này: ImageLoader toàn cục tắt hardware
                // bitmap (MyFileApp.kt) để tránh crash khi kết hợp với crossfade — nhưng software
                // decode cho ảnh full-size (12-50MP từ camera) chậm hơn hardware (GPU) rất nhiều,
                // đây là nguyên nhân chính gây lag/giật lần đầu mở ảnh full-screen. Tắt crossfade
                // ở request này (không cần vì đã có ProgressBar riêng) để không đụng lại crash cũ.
                allowHardware(true)
                crossfade(false)
                listener(
                    onSuccess = { _, _ -> progress.visibility = View.GONE },
                    onError = { _, _ ->
                        // Coil/BitmapFactory không giải mã được (thường gặp nhất: ảnh HEIC/HEIF
                        // trên thiết bị Android không có codec hỗ trợ định dạng này) — hiện rõ
                        // lý do thay vì để màn đen im lặng khiến người dùng tưởng app bị treo.
                        progress.visibility = View.GONE
                        imageView.visibility = View.GONE
                        val isHeic = item.name.substringAfterLast('.', "").lowercase() in setOf("heic", "heif")
                        errorText.text = if (isHeic) {
                            "Không thể xem trước ảnh HEIC (${item.name}) — thiết bị không hỗ trợ giải mã định dạng này."
                        } else {
                            "Không thể mở ảnh ${item.name}"
                        }
                        errorText.visibility = View.VISIBLE
                    }
                )
            }
        }
    }

    class VideoPageHolder(
        itemView: View,
        private val controller: MediaController?
    ) : RecyclerView.ViewHolder(itemView) {

        private val playerView = itemView.findViewById<PlayerView>(R.id.player_view)
        private val playOverlay = itemView.findViewById<android.widget.ImageView>(R.id.btn_play_overlay)
        private val progress = itemView.findViewById<android.widget.ProgressBar>(R.id.video_progress)
        private val controls = itemView.findViewById<View>(R.id.video_controls)
        private val seekBar = itemView.findViewById<SeekBar>(R.id.seek_bar)
        private val tvPosition = itemView.findViewById<android.widget.TextView>(R.id.tv_position)
        private val tvDuration = itemView.findViewById<android.widget.TextView>(R.id.tv_duration)
        private val btnPlayPauseSmall = itemView.findViewById<android.widget.ImageView>(R.id.btn_play_pause_small)
        private val btnRewind = itemView.findViewById<android.widget.ImageView>(R.id.btn_rewind_15)
        private val btnForward = itemView.findViewById<android.widget.ImageView>(R.id.btn_forward_15)
        private val btnSpeed = itemView.findViewById<TextView>(R.id.btn_speed)
        private val btnRepeatAb = itemView.findViewById<TextView>(R.id.btn_repeat_ab)
        private val btnRepeatTrack = itemView.findViewById<android.widget.ImageView>(R.id.btn_repeat_track)

        // Bật/tắt lặp lại nguyên video khi phát hết (Player.REPEAT_MODE_ONE), độc lập với lặp
        // A-B (lặp 1 đoạn cụ thể). Cùng logic đã thêm cho AudioPlayerActivity — trước đây
        // ExoPlayer dùng chung không đặt repeatMode nên mặc định REPEAT_MODE_OFF, hết video
        // là dừng hẳn không tự phát lại như audio đang gặp.
        private var isRepeatTrackOn = false

        private var boundItem: MediaItem? = null
        private var boundUri: String? = null
        private var isUserSeeking = false
        private var isActive = false
        private val progressHandler = Handler(Looper.getMainLooper())

        // Lặp A-B riêng cho từng trang video (giữ theo boundUri) — bấm lần 1 đặt A, lần 2 đặt B
        // và bật lặp, lần 3 tắt. Reset khi bind() sang video khác để không lặp nhầm đoạn cũ.
        private var repeatPointA: Long? = null
        private var repeatPointB: Long? = null

        private val progressRunnable = object : Runnable {
            override fun run() {
                updateProgress()
                progressHandler.postDelayed(this, 300)
            }
        }

        // Chỉ lắng nghe player khi trang này ĐANG hiển thị nội dung của chính nó — tránh trang
        // video khác (đã bị recycle) vẫn nhận callback của player dùng chung và tự ý update UI.
        private val playerListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!isCurrentUri()) return
                when (playbackState) {
                    Player.STATE_BUFFERING -> progress.visibility = View.VISIBLE
                    Player.STATE_READY -> {
                        // Nơi đã thiếu ở bản cũ: ẩn vòng xoay NGAY khi player thật sự sẵn sàng
                        // phát khung hình, dù đang play hay đang pause — trước đây progress chỉ
                        // ẩn lúc "prepared" (trước khi bấm play) rồi không có nơi nào ẩn lại sau
                        // start(), khiến vòng xoay che video dù video đã chạy ngầm bên dưới.
                        progress.visibility = View.GONE
                        seekBar.max = 1000
                    }
                    Player.STATE_ENDED -> {
                        progress.visibility = View.GONE
                        playOverlay.visibility = View.VISIBLE
                        btnPlayPauseSmall.setImageResource(R.drawable.ic_play)
                    }
                    Player.STATE_IDLE -> progress.visibility = View.GONE
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isCurrentUri()) return
                playOverlay.visibility = if (isPlaying) View.GONE else View.VISIBLE
                btnPlayPauseSmall.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!isCurrentUri()) return
                progress.visibility = View.GONE
                playOverlay.visibility = View.VISIBLE
                controls.visibility = View.GONE
                android.util.Log.w("MediaPagerAdapter", "Lỗi phát video: ${error.message}")
            }
        }

        private fun isCurrentUri(): Boolean = controller?.currentMediaItem?.mediaId == boundUri

        /**
         * bind() giờ CHỈ chuẩn bị UI tĩnh (nút play overlay che video, ẩn thanh điều khiển) —
         * KHÔNG đụng vào controller nữa. Trước đây bind() gán playerView.player = controller và
         * gọi setMediaItem()/play() ngay lập tức cho MỌI trang video được ViewHolder tạo ra, kể
         * cả trang chỉ đang được ViewPager2 PRELOAD (offscreenPageLimit = 1) chứ người dùng chưa
         * thực sự vuốt tới. Vì toàn app chỉ dùng CHUNG 1 MediaController/ExoPlayer (chia sẻ với
         * audio nền), 2 trang video kề nhau (trang đang xem + trang kế bên được preload) cùng
         * gọi playerView.player = controller gần như đồng thời → 2 PlayerView khác nhau tranh
         * giành cùng 1 Player/Surface, ExoPlayer/SurfaceView không hỗ trợ an toàn việc này và ném
         * exception/crash native — đúng lúc "lần đầu xem video" vì đó là lúc service cold-start
         * chậm, khiến việc bind 2 trang dồn lại gần nhau hơn bình thường.
         *
         * Sửa: chỉ trang THỰC SỰ đang hiển thị (do MediaViewerActivity báo qua activate() từ
         * ViewPager2.onPageSelected) mới được gán controller và phát. Trang preload chỉ hiện nút
         * play tĩnh, không chạm tới Player cho tới khi người dùng thực sự vuốt tới nó.
         */
        fun bind(item: MediaItem) {
            boundItem = item
            boundUri = item.uri
            isActive = false
            progress.visibility = View.GONE
            controls.visibility = View.GONE
            playOverlay.visibility = View.VISIBLE
            playerView.player = null
            btnPlayPauseSmall.setImageResource(R.drawable.ic_play)
            // ViewHolder có thể được RecyclerView tái sử dụng cho 1 video khác — điểm A/B của
            // video cũ không còn ý nghĩa, phải xoá để không lặp nhầm đoạn.
            repeatPointA = null
            repeatPointB = null
            btnRepeatAb.text = itemView.context.getString(R.string.audio_repeat_ab)
            btnRepeatAb.alpha = 0.8f
            btnSpeed.text = itemView.context.getString(R.string.audio_speed_1x)
            // Reset lặp-nguyên-video về OFF mỗi khi ViewHolder được bind sang 1 video khác —
            // không cho trạng thái bật lặp của video trước "dính" sang video mới do tái sử dụng
            // ViewHolder của RecyclerView.
            isRepeatTrackOn = false
            btnRepeatTrack.setColorFilter(android.graphics.Color.parseColor("#CCFFFFFF"))

            playOverlay.setOnClickListener {
                // Người dùng bấm play trên trang chưa active (vd controller null lúc đầu rồi
                // adapter được thay bằng bản có controller) — coi như activate ngay tại đây.
                activate()
                controller?.play()
            }
        }

        /** Gọi khi ViewPager2 xác nhận đây là trang ĐANG hiển thị — lúc này mới thực sự chiếm controller dùng chung và phát. */
        fun activate() {
            val item = boundItem ?: return
            val c = controller
            if (c == null) {
                // Chưa kết nối được service nền (hiếm, ví dụ hệ thống chặn foreground service) —
                // vẫn báo lỗi rõ ràng thay vì treo vòng xoay vô thời hạn.
                progress.visibility = View.GONE
                playOverlay.visibility = View.VISIBLE
                return
            }
            if (isActive) return
            isActive = true

            progress.visibility = View.VISIBLE
            playOverlay.visibility = View.GONE
            btnPlayPauseSmall.setImageResource(R.drawable.ic_pause)

            playerView.player = c
            c.addListener(playerListener)

            val mediaItem = ExoMediaItem.Builder()
                .setMediaId(item.uri)
                .setUri(item.uri)
                .build()
            c.setMediaItem(mediaItem)
            c.prepare()
            c.play()

            playerView.setOnClickListener {
                controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
            btnPlayPauseSmall.setOnClickListener {
                if (c.isPlaying) c.pause() else c.play()
            }
            btnRewind.setOnClickListener {
                c.seekTo((c.currentPosition - SEEK_STEP_MS).coerceAtLeast(0))
            }
            btnForward.setOnClickListener {
                val dur = c.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                c.seekTo((c.currentPosition + SEEK_STEP_MS).coerceAtMost(dur))
            }
            btnSpeed.setOnClickListener {
                val popup = PopupMenu(itemView.context, btnSpeed)
                SPEED_OPTIONS.forEachIndexed { index, speed -> popup.menu.add(0, index, index, formatSpeedLabel(speed)) }
                popup.setOnMenuItemClickListener { menuItem ->
                    val speed = SPEED_OPTIONS.getOrNull(menuItem.itemId) ?: return@setOnMenuItemClickListener false
                    c.setPlaybackSpeed(speed)
                    btnSpeed.text = formatSpeedLabel(speed)
                    true
                }
                popup.show()
            }
            btnRepeatAb.setOnClickListener { toggleRepeatAb(c) }
            btnRepeatTrack.setOnClickListener {
                isRepeatTrackOn = !isRepeatTrackOn
                c.repeatMode = if (isRepeatTrackOn) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                btnRepeatTrack.setColorFilter(
                    if (isRepeatTrackOn) android.graphics.Color.parseColor("#4FC3F7")
                    else android.graphics.Color.parseColor("#CCFFFFFF")
                )
            }
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && c.duration > 0) {
                        tvPosition.text = formatTime(progress * c.duration / 1000)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { isUserSeeking = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    isUserSeeking = false
                    val bar = seekBar ?: return
                    if (c.duration > 0) c.seekTo(bar.progress * c.duration / 1000)
                }
            })

            controls.visibility = View.VISIBLE
            progressHandler.post(progressRunnable)
        }

        /**
         * Gọi khi trang này KHÔNG còn là trang đang xem nữa (người dùng vuốt sang trang khác)
         * nhưng ViewHolder vẫn có thể còn tồn tại (preload phía sau) — nhả controller ra để
         * trang mới có thể chiếm mà không tranh chấp, nhưng KHÔNG dừng phát nhạc (đúng yêu cầu
         * video tiếp tục phát nền): chỉ ngắt UI khỏi player, ExoPlayer thật vẫn tiếp tục chạy
         * trong service, chỉ là trang này không còn hiển thị/điều khiển nó nữa.
         */
        fun deactivate() {
            if (!isActive) return
            isActive = false
            progressHandler.removeCallbacks(progressRunnable)
            controller?.removeListener(playerListener)
            playerView.player = null
            seekBar.setOnSeekBarChangeListener(null)
            btnSpeed.setOnClickListener(null)
            btnRepeatAb.setOnClickListener(null)
            btnRepeatTrack.setOnClickListener(null)
        }

        private fun updateProgress() {
            val c = controller ?: return
            if (!isCurrentUri() || isUserSeeking) return
            val duration = c.duration.coerceAtLeast(0)
            val position = c.currentPosition.coerceAtLeast(0)
            if (duration > 0) {
                seekBar.progress = (position * 1000 / duration).toInt()
            }
            tvPosition.text = formatTime(position)
            tvDuration.text = formatTime(duration)

            val pointA = repeatPointA
            val pointB = repeatPointB
            if (pointA != null && pointB != null && position >= pointB) {
                c.seekTo(pointA)
            }
        }

        /**
         * Bấm lần 1: đặt điểm A tại vị trí hiện tại.
         * Bấm lần 2: đặt điểm B (phải sau A) -> bắt đầu lặp giữa A-B.
         * Bấm lần 3: tắt lặp, xoá 2 điểm.
         */
        private fun toggleRepeatAb(c: MediaController) {
            val position = c.currentPosition.coerceAtLeast(0)
            when {
                repeatPointA == null -> {
                    repeatPointA = position
                    btnRepeatAb.text = "A"
                    btnRepeatAb.alpha = 1f
                }
                repeatPointB == null -> {
                    val pointA = repeatPointA ?: return
                    if (position <= pointA) return
                    repeatPointB = position
                    btnRepeatAb.text = "A-B"
                }
                else -> {
                    repeatPointA = null
                    repeatPointB = null
                    btnRepeatAb.text = itemView.context.getString(R.string.audio_repeat_ab)
                    btnRepeatAb.alpha = 0.8f
                }
            }
        }

        private fun formatSpeedLabel(speed: Float): String {
            val trimmed = if (speed == speed.toLong().toFloat()) speed.toLong().toString() else speed.toString()
            return "${trimmed}x"
        }

        private fun formatTime(ms: Long): String {
            val totalSeconds = ms / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }

        /** Gọi khi trang bị RecyclerView tái sử dụng cho nội dung khác — KHÔNG dừng phát nhạc
         * nền của video (đúng yêu cầu chạy nền), chỉ ngắt UI khỏi player và bỏ lắng nghe. */
        fun detach() {
            deactivate()
        }
    }
}
