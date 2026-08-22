package com.myfile.ui

import android.os.Bundle
import android.widget.PopupMenu
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.myfile.ui.databinding.ActivityAudioPlayerBinding
import com.myfile.ui.media.AudioPlayerController
import com.myfile.ui.media.AudioTrack
import java.util.Locale

private const val SEEK_STEP_MS = 10_000L
private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * Màn hình phát audio chạy nền kiểu VLC: điều khiển ở đây chỉ là 1 "cửa sổ" hiển thị trạng thái
 * của AudioPlaybackService — đóng màn hình này không dừng phát nhạc, notification vẫn điều khiển
 * được bình thường (đúng hành vi player nền thật sự, không phải chỉ chạy khi có UI mở).
 */
class AudioPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAudioPlayerBinding
    private val playerController = AudioPlayerController(this)
    private var controller: MediaController? = null

    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isUserSeeking = false
    private var currentSpeed = 1.0f

    // Lặp A-B: bấm lần 1 đặt điểm A tại vị trí hiện tại, bấm lần 2 đặt điểm B (bật lặp giữa
    // A-B), bấm lần 3 tắt và xoá 2 điểm — đúng hành vi "Lặp lại A-B" quen thuộc kiểu VLC.
    private var repeatPointA: Long? = null
    private var repeatPointB: Long? = null

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            progressHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val uris = intent.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()
        val names = intent.getStringArrayListExtra(EXTRA_NAMES) ?: arrayListOf()
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)

        binding.btnPlayPause.setOnClickListener { playerController.playPause() }
        binding.btnNext.setOnClickListener { playerController.next() }
        binding.btnPrevious.setOnClickListener { playerController.previous() }
        binding.btnRewind10.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            c.seekTo((c.currentPosition - SEEK_STEP_MS).coerceAtLeast(0))
        }
        binding.btnForward10.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            val dur = c.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            c.seekTo((c.currentPosition + SEEK_STEP_MS).coerceAtMost(dur))
        }
        binding.btnSpeed.setOnClickListener { showSpeedMenu() }
        binding.btnRepeatAb.setOnClickListener { toggleRepeatAb() }
        binding.btnRepeatTrack.setOnClickListener { toggleRepeatTrack() }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.tvPosition.text = formatTime(progress.toLong())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isUserSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                seekBar?.let { playerController.seekTo(it.progress.toLong()) }
            }
        })

        playerController.connect { mediaController ->
            // Guard bắt buộc: connect() bất đồng bộ (khởi động/kết nối AudioPlaybackService),
            // callback có thể tới SAU KHI người dùng đã bấm back và Activity đang/đã bị hủy —
            // đặc biệt khi service cold-start chậm. Không có guard này trước đây, callback vẫn
            // chạy tiếp: gán controller field SAU khi onDestroy() đã chạy xong (lúc đó
            // controller còn null nên removeListener() không có tác dụng) khiến listener bị leak
            // vĩnh viễn, đồng thời updateTrackInfo()/updatePlayPauseIcon() thao tác lên binding
            // của 1 Activity đã hủy — cùng họ lỗi với "crash không ổn định, khó tái hiện".
            if (isFinishing || isDestroyed) return@connect
            controller = mediaController
            if (uris.isNotEmpty()) {
                val tracks = uris.indices.map { i ->
                    AudioTrack(uri = uris[i], title = names.getOrElse(i) { "" })
                }
                playerController.playPlaylist(tracks, startIndex.coerceIn(0, tracks.size - 1))
            }
            mediaController.addListener(playerListener)
            updateTrackInfo()
            updatePlayPauseIcon()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            updateTrackInfo()
            // Đổi bài (kể cả tự next) -> điểm A/B của bài cũ không còn ý nghĩa với bài mới,
            // xoá để tránh lặp nhầm đoạn của bài trước sang bài đang phát.
            repeatPointA = null
            repeatPointB = null
            binding.btnRepeatAb.text = getString(R.string.audio_repeat_ab)
            binding.btnRepeatAb.setTextColor(getColor(R.color.text_secondary))
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            updatePlayPauseIcon()
        }
    }

    private fun updateTrackInfo() {
        val c = controller ?: return
        val title = c.mediaMetadata.title?.toString().orEmpty().ifBlank { getString(R.string.title_audio_player) }
        binding.tvTrackTitle.text = title
        val index = c.currentMediaItemIndex
        val count = c.mediaItemCount
        binding.tvPlaylistPosition.text = if (count > 1) "${index + 1} / $count" else ""
        binding.seekBar.max = c.duration.coerceAtLeast(0).toInt()
        binding.tvDuration.text = formatTime(c.duration.coerceAtLeast(0))
    }

    private fun updatePlayPauseIcon() {
        val isPlaying = controller?.isPlaying == true
        binding.btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateProgress() {
        val c = controller ?: return
        if (isUserSeeking) return
        val position = c.currentPosition.coerceAtLeast(0)
        val duration = c.duration.coerceAtLeast(0)
        if (binding.seekBar.max != duration.toInt()) binding.seekBar.max = duration.toInt()
        binding.seekBar.progress = position.toInt()
        binding.tvPosition.text = formatTime(position)
        binding.tvDuration.text = formatTime(duration)

        // Đang bật lặp A-B và vị trí phát đã vượt qua điểm B -> nhảy ngay về điểm A để lặp lại
        // đúng đoạn đã chọn, thay vì để chạy tiếp ra ngoài đoạn.
        val pointB = repeatPointB
        val pointA = repeatPointA
        if (pointB != null && pointA != null && position >= pointB) {
            c.seekTo(pointA)
        }
    }

    /** Menu chọn tốc độ phát 0.5x-2x, dùng setPlaybackSpeed() có sẵn của Media3 Player. */
    private fun showSpeedMenu() {
        val c = controller ?: return
        val popup = PopupMenu(this, binding.btnSpeed)
        SPEED_OPTIONS.forEachIndexed { index, speed ->
            popup.menu.add(0, index, index, formatSpeedLabel(speed))
        }
        popup.setOnMenuItemClickListener { item ->
            val speed = SPEED_OPTIONS.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
            currentSpeed = speed
            c.setPlaybackSpeed(speed)
            binding.btnSpeed.text = formatSpeedLabel(speed)
            true
        }
        popup.show()
    }

    private fun formatSpeedLabel(speed: Float): String {
        val trimmed = if (speed == speed.toLong().toFloat()) {
            speed.toLong().toString()
        } else {
            speed.toString()
        }
        return "${trimmed}x"
    }

    /**
     * Bấm lần 1: đặt điểm A tại vị trí hiện tại.
     * Bấm lần 2: đặt điểm B tại vị trí hiện tại (phải sau A) -> bắt đầu lặp giữa A-B.
     * Bấm lần 3: tắt lặp, xoá 2 điểm, quay về trạng thái ban đầu.
     */
    private fun toggleRepeatAb() {
        val c = controller ?: return
        val position = c.currentPosition.coerceAtLeast(0)
        when {
            repeatPointA == null -> {
                repeatPointA = position
                binding.btnRepeatAb.text = "A"
                binding.btnRepeatAb.setTextColor(getColor(R.color.primary))
                android.widget.Toast.makeText(this, R.string.audio_repeat_ab_set_a, android.widget.Toast.LENGTH_SHORT).show()
            }
            repeatPointB == null -> {
                val pointA = repeatPointA ?: return
                if (position <= pointA) {
                    android.widget.Toast.makeText(this, R.string.audio_repeat_ab_set_a, android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
                repeatPointB = position
                binding.btnRepeatAb.text = "A-B"
                android.widget.Toast.makeText(this, R.string.audio_repeat_ab_set_b, android.widget.Toast.LENGTH_SHORT).show()
            }
            else -> {
                repeatPointA = null
                repeatPointB = null
                binding.btnRepeatAb.text = getString(R.string.audio_repeat_ab)
                binding.btnRepeatAb.setTextColor(getColor(R.color.text_secondary))
                android.widget.Toast.makeText(this, R.string.audio_repeat_ab_cleared, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Bật/tắt lặp lại bài đang phát khi hết bài. Dùng Player.REPEAT_MODE_ONE (Media3/ExoPlayer)
     * — mặc định là REPEAT_MODE_OFF nên trước đây hết bài là dừng hẳn, không tự lặp lại. Nút này
     * độc lập với A-B: A-B lặp 1 đoạn cụ thể trong bài, còn nút này lặp NGUYÊN bài từ đầu tới
     * cuối một cách đơn giản, giống nút lặp quen thuộc trên mọi trình phát nhạc khác.
     */
    private var isRepeatTrackOn = false
    private fun toggleRepeatTrack() {
        val c = controller ?: return
        isRepeatTrackOn = !isRepeatTrackOn
        c.repeatMode = if (isRepeatTrackOn) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        binding.btnRepeatTrack.setColorFilter(
            getColor(if (isRepeatTrackOn) R.color.primary else R.color.text_secondary)
        )
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    override fun onStart() {
        super.onStart()
        progressHandler.post(progressRunnable)
    }

    override fun onStop() {
        progressHandler.removeCallbacks(progressRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        controller?.removeListener(playerListener)
        playerController.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URIS = "extra_audio_uris"
        const val EXTRA_NAMES = "extra_audio_names"
        const val EXTRA_START_INDEX = "extra_audio_start_index"
    }
}
