package com.myfile.ui.server

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
import com.myfile.ui.R
import com.myfile.ui.model.FtpUser
import com.myfile.ui.MainActivity
import com.myfile.ui.util.LogBus
import com.myfile.ui.util.SecurePrefs

/**
 * Foreground Service giữ cho FTP server chạy ổn định ngay cả khi app ở nền,
 * tránh bị Android hệ thống kill tiến trình.
 */
class FtpServerService : Service() {

    private val binder = LocalBinder()
    private val ftpServerManager = FtpServerManager()

    inner class LocalBinder : Binder() {
        fun getService(): FtpServerService = this@FtpServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startFtpServer()
            ACTION_STOP -> stopFtpServer()
        }
        return START_STICKY
    }

    private fun startFtpServer() {
        val prefs = SecurePrefs.getInstance(applicationContext)
        // Mặc định dùng bộ nhớ trong THẬT của máy (/storage/emulated/0), không phải thư mục
        // riêng của app (Android/data/...) — thư mục riêng luôn trống với app khác/máy khác,
        // đó là lý do trước đây duyệt FTP vào không thấy file nào.
        val rootPath = prefs.rootFolderUri
            ?: android.os.Environment.getExternalStorageDirectory()?.absolutePath
            ?: applicationContext.getExternalFilesDir(null)?.absolutePath
            ?: filesDir.absolutePath
        val port = prefs.serverPort
        val users: List<FtpUser> = prefs.loadFtpUsers()

        startForeground(NOTIFICATION_ID, buildNotification(running = true))

        try {
            val localIp = com.myfile.ui.util.NetworkUtils.getLocalIpAddress(applicationContext)
            ftpServerManager.start(port = port, rootPath = rootPath, users = users, localIp = localIp)
            isRunningStatic = true
        } catch (e: Exception) {
            LogBus.error("Lỗi khởi động máy chủ FTP (cổng $port)", source = "FTP", throwable = e)
            isRunningStatic = false
            stopSelf()
        }
    }

    private fun stopFtpServer() {
        ftpServerManager.stop()
        isRunningStatic = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun isServerRunning(): Boolean = ftpServerManager.isRunning

    override fun onDestroy() {
        ftpServerManager.stop()
        isRunningStatic = false
        super.onDestroy()
    }

    private fun buildNotification(running: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_server_running))
            .setContentText(getString(R.string.notif_tap_to_open))
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentIntent(pendingIntent)
            .setOngoing(running)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.myfile.ui.action.START"
        const val ACTION_STOP = "com.myfile.ui.action.STOP"
        private const val CHANNEL_ID = "ftp_server_channel"
        private const val NOTIFICATION_ID = 1001

        // Cờ trạng thái tĩnh, đơn giản và tin cậy trong phạm vi 1 process của app:
        // cho phép UI (Fragment) biết server có đang chạy hay không kể cả khi
        // Fragment vừa mới được tạo lại (ví dụ sau khi rời rồi quay lại tab),
        // thay vì dựa vào 1 biến boolean riêng dễ bị mất đồng bộ với thực tế.
        @Volatile private var isRunningStatic: Boolean = false

        fun isRunning(): Boolean = isRunningStatic
    }
}
