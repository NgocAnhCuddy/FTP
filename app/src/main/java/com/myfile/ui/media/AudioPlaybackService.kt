package com.myfile.ui.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.myfile.ui.MainActivity
import com.myfile.ui.R

/**
 * Service phát audio (mp3/m4a/flac/wav...) chạy nền kiểu VLC: tắt màn hình vẫn phát tiếp,
 * có notification hệ thống với nút play/pause/next/prev (tự động render bởi Media3
 * MediaSessionService — không cần tự vẽ notification tay).
 *
 * Dùng chung được cho cả trình duyệt file cục bộ lẫn stream qua HTTP/FTP: chỉ cần đưa vào
 * 1 MediaItem có URI hợp lệ (file://, content://, hoặc http://).
 */
class AudioPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()

        // Guard: nếu onCreate() bị gọi lại trong khi session cũ chưa release xong
        // (ví dụ service restart nhanh), không tạo session thứ 2 với cùng ID -> tránh
        // "IllegalStateException: Session ID must be unique".
        if (mediaSession != null) return

        // BẮT BUỘC gọi startForeground() ngay trong vài giây kể từ khi hệ thống khởi động
        // service này (Media3 MediaController connect tới service này qua startForegroundService()
        // ngầm). Trước đây không có dòng nào gọi thẳng — chỉ trông chờ Media3 tự làm việc đó khi
        // có media đang PHÁT, nhưng nếu người dùng mở màn phát nhạc rồi thoát ngay/mạng chậm khiến
        // buffer trước khi phát, có thể vượt quá thời gian cho phép. Android 12+ sẽ ném
        // ForegroundServiceDidNotStartInTimeException, làm CRASH TOÀN APP dù người dùng chỉ vừa
        // thoát màn hình phát nhạc chứ chưa đóng app - đúng hiện tượng "thoát ra là bị tắt". Dùng
        // 1 notification tối giản ngay lập tức; Media3 sẽ tự thay bằng notification đầy đủ (có nút
        // play/pause) ngay khi có media thật sự bắt đầu phát.
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildPlaceholderNotification())

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true) // tự pause khi rút tai nghe, tránh phát to bất ngờ qua loa ngoài
            .build()

        val openAppIntent = Intent(this, MainActivity::class.java)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Đặt ID cố định + duy nhất cho session, tránh việc Media3 tự sinh ID rỗng
        // dễ đụng ID với 1 instance cũ (của cùng process) chưa kịp release.
        mediaSession = try {
            MediaSession.Builder(this, player)
                .setId(SESSION_ID)
                .setSessionActivity(sessionActivityPendingIntent)
                .build()
        } catch (e: IllegalStateException) {
            // Trường hợp hiếm: OS (đặc biệt ColorOS/MIUI) kill service không qua onDestroy(),
            // session cũ cùng ID trong process chưa kịp release() -> Media3 ném
            // "Session ID must be unique". Không để lỗi này làm crash toàn app - dừng
            // service gọn gàng thay vì ném RuntimeException lên ActivityThread.
            player.release()
            stopSelf()
            return
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** Cho phép Activity trong cùng process lấy trực tiếp player để build playlist/queue. */
    fun getPlayer(): ExoPlayer = player

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Nếu người dùng vuốt app khỏi Recent Apps mà không đang phát nhạc, dừng service luôn
        // thay vì giữ nền vô ích; nếu đang phát thì vẫn giữ để giống hành vi của VLC.
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun buildPlaceholderNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_stream_running))
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
                getString(R.string.notif_stream_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val SESSION_ID = "com.myfile.ui.media.AudioPlaybackService"
        private const val CHANNEL_ID = "audio_playback_channel"
        private const val NOTIFICATION_ID = 1005
    }
}
