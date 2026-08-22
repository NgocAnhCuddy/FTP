package com.myfile.ui.dlna

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.myfile.ui.MainActivity
import com.myfile.ui.R

/**
 * Service phát media do THIẾT BỊ KHÁC gửi tới (vd BubbleUPnP, TV, điện thoại khác cast qua
 * DLNA "Play to") — biến MyFile Manager thành một UPnP MediaRenderer thật sự, không chỉ
 * MediaServer. Dùng chung kiểu ExoPlayer + MediaSessionService như AudioPlaybackService để
 * chạy nền ổn định (tắt màn hình vẫn phát, có notification điều khiển), khác ở chỗ URL phát
 * không do người dùng chọn trong app mà do lệnh SOAP AVTransport (RendererSoapController) nạp
 * vào qua setMedia()/play()/pause()/stop().
 *
 * RendererSoapController giữ 1 tham chiếu tĩnh tới service đang chạy (qua Companion.instance)
 * để chuyển lệnh SetAVTransportURI/Play/Pause/Stop/Seek từ luồng HTTP server sang luồng chính
 * nơi ExoPlayer sống — ExoPlayer bắt buộc phải được gọi từ 1 thread cố định (Main thread).
 */
class RendererPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()

        // Guard: tránh tạo session thứ 2 trùng ID nếu onCreate() vô tình được gọi lại
        // trong khi session cũ chưa release xong (giống AudioPlaybackService).
        if (mediaSession != null) return

        // BẮT BUỘC gọi startForeground() ngay trong vài giây kể từ khi hệ thống khởi động
        // service này (RendererCastService gọi startForegroundService()). Trước đây không có
        // dòng nào gọi thẳng — chỉ trông chờ MediaSessionService của Media3 tự làm việc đó khi
        // có media đang phát, nhưng lúc mới bật "Nhận phát từ thiết bị khác" thì CHƯA CÓ gì để
        // phát (đang chờ lệnh SetAVTransportURI từ thiết bị điều khiển). Nếu không phát gì
        // trong vài giây, Android (12+) ném ForegroundServiceDidNotStartInTimeException, làm
        // CRASH TOÀN APP — đúng hiện tượng "bật lên là bị đóng". Dùng 1 notification tối giản
        // ngay lập tức; Media3 sẽ tự thay bằng notification đầy đủ (có nút play/pause) khi có
        // media thật sự bắt đầu phát.
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildPlaceholderNotification())

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                RendererState.notifyStateChanged(player)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                RendererState.notifyStateChanged(player)
            }
        })

        val openAppIntent = Intent(this, MainActivity::class.java)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = try {
            MediaSession.Builder(this, player)
                .setId(SESSION_ID)
                .setSessionActivity(sessionActivityPendingIntent)
                .build()
        } catch (e: IllegalStateException) {
            // Cùng lý do với AudioPlaybackService: session cũ (cùng ID, cùng process) chưa
            // kịp release() khi OS restart service -> không để crash cả app.
            player.release()
            stopSelf()
            return
        }

        instance = this
    }

    private fun buildPlaceholderNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_renderer_running))
            .setContentText(getString(R.string.notif_tap_to_open))
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_renderer_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** Nạp URL media mới do thiết bị điều khiển gửi tới (SetAVTransportURI) và chuẩn bị phát. */
    fun setMedia(url: String) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
    }

    fun play() {
        player.playWhenReady = true
    }

    fun pause() {
        player.playWhenReady = false
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
    }

    fun seekToMillis(positionMs: Long) {
        player.seekTo(positionMs)
    }

    fun setVolumePercent(percent: Int) {
        player.volume = (percent.coerceIn(0, 100) / 100f)
    }

    /** Trạng thái hiện tại dùng để trả lời GetTransportInfo/GetPositionInfo cho controller. */
    fun currentUri(): String? = player.currentMediaItem?.localConfiguration?.uri?.toString()
    fun isPlaying(): Boolean = player.isPlaying
    fun isPaused(): Boolean = !player.playWhenReady && player.playbackState != androidx.media3.common.Player.STATE_IDLE
    fun durationMillis(): Long = player.duration.coerceAtLeast(0)
    fun positionMillis(): Long = player.currentPosition.coerceAtLeast(0)
    fun volumePercent(): Int = (player.volume * 100).toInt()

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = mediaSession?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "renderer_playback_channel"
        private const val NOTIFICATION_ID = 1004
        private const val SESSION_ID = "com.myfile.ui.dlna.RendererPlaybackService"

        @Volatile private var instance: RendererPlaybackService? = null
        fun getRunningInstance(): RendererPlaybackService? = instance
    }
}

/** Cầu nối trạng thái phát (đổi từ luồng Main của ExoPlayer) sang nơi khác cần đọc (UI, log). */
object RendererState {
    @Volatile var lastKnownIsPlaying: Boolean = false
        private set

    fun notifyStateChanged(player: ExoPlayer) {
        lastKnownIsPlaying = player.isPlaying
    }
}
