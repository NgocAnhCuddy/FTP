package com.myfile.ui.dlna

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.myfile.ui.MainActivity
import com.myfile.ui.R
import com.myfile.ui.util.LogBus
import com.myfile.ui.util.NetworkUtils

/**
 * Foreground Service giữ RendererServer (HTTP/SOAP) + RendererSsdpResponder sống khi app ở
 * nền — vai trò UPnP MediaRenderer (nhận cast TỪ thiết bị khác), song song và độc lập với
 * MediaCastService (vai trò MediaServer, chia sẻ file CHO thiết bị khác).
 *
 * Tự khởi động RendererPlaybackService (ExoPlayer) cùng lúc vì renderer vô nghĩa nếu không
 * có gì để phát nhạc/video nhận được.
 */
class RendererCastService : Service() {

    private val binder = LocalBinder()
    private var httpServer: RendererServer? = null
    private var ssdpResponder: RendererSsdpResponder? = null

    inner class LocalBinder : Binder() {
        fun getService(): RendererCastService = this@RendererCastService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRenderer()
            ACTION_STOP -> stopRenderer()
        }
        return START_STICKY
    }

    private fun startRenderer() {
        if (httpServer != null) return
        startForeground(NOTIFICATION_ID, buildNotification())
        try {
            val server = RendererServer(RENDERER_PORT)
            server.start(SOCKET_READ_TIMEOUT_MS, false)
            httpServer = server
            isRunningStatic = true

            // Khởi động luôn service phát nhạc/video (ExoPlayer) — không phát gì cho tới khi
            // có lệnh SetAVTransportURI từ thiết bị điều khiển gửi tới.
            ContextCompat.startForegroundService(
                this, Intent(this, RendererPlaybackService::class.java)
            )

            val ip = NetworkUtils.getLocalIpAddress(applicationContext)
            if (ip != null) {
                val responder = RendererSsdpResponder(httpPort = RENDERER_PORT, localIp = ip)
                responder.start()
                ssdpResponder = responder
                LogBus.success("Đã bật nhận phát từ thiết bị khác (DLNA Renderer)", source = "DLNA")
            } else {
                LogBus.warning("Không lấy được IP LAN, thiết bị khác sẽ không tự tìm thấy renderer (SSDP)", source = "DLNA")
            }
        } catch (e: Exception) {
            LogBus.error("Không thể khởi động renderer (cổng $RENDERER_PORT)", source = "DLNA", throwable = e)
            isRunningStatic = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRenderer() {
        ssdpResponder?.stop()
        ssdpResponder = null
        httpServer?.stop()
        httpServer = null
        isRunningStatic = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun isRendererRunning(): Boolean = httpServer != null

    override fun onDestroy() {
        ssdpResponder?.stop()
        ssdpResponder = null
        httpServer?.stop()
        httpServer = null
        isRunningStatic = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
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

    companion object {
        const val ACTION_START = "com.myfile.ui.action.RENDERER_START"
        const val ACTION_STOP = "com.myfile.ui.action.RENDERER_STOP"
        const val RENDERER_PORT = 8091
        private const val SOCKET_READ_TIMEOUT_MS = 15000
        private const val CHANNEL_ID = "renderer_cast_channel"
        private const val NOTIFICATION_ID = 1003

        @Volatile private var isRunningStatic: Boolean = false
        fun isRunning(): Boolean = isRunningStatic
    }
}
