package com.myfile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.myfile.ui.adapters.DlnaDeviceAdapter
import com.myfile.ui.databinding.ActivityCastToTvBinding
import com.myfile.ui.dlna.DlnaCastController
import com.myfile.ui.dlna.DlnaDevice
import com.myfile.ui.dlna.DlnaDiscovery
import com.myfile.ui.server.MediaCastService
import com.myfile.ui.util.LogBus
import kotlinx.coroutines.launch
import java.io.File

/**
 * Màn hình "Phát lên TV": khởi động máy chủ HTTP nền (MediaCastService), hiển thị link để
 * mở thủ công bằng trình duyệt/VLC trên TV, và cho phép dò + cast tự động tới TV hỗ trợ DLNA
 * (TV tự kéo dữ liệu từ link đó về, app chỉ ra lệnh).
 */
class CastToTvActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCastToTvBinding
    private lateinit var deviceAdapter: DlnaDeviceAdapter

    private var castService: MediaCastService? = null
    private var streamUrl: String? = null
    private var mimeType: String = "video/mp4"

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            castService = (service as MediaCastService.LocalBinder).getService()
            registerAndBuildLink()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            castService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCastToTvBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: ""
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, true)
        mimeType = if (isVideo) "video/mp4" else "image/jpeg"

        if (filePath.isNullOrBlank() || !File(filePath).exists()) {
            Toast.makeText(this, getString(R.string.cast_no_devices), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.tvFileName.text = fileName

        deviceAdapter = DlnaDeviceAdapter { device -> sendToDevice(device) }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = deviceAdapter

        binding.btnCopyLink.setOnClickListener { copyLinkToClipboard() }
        binding.btnScanDevices.setOnClickListener { scanForDevices() }

        // Khởi động (hoặc kết nối tới) MediaCastService — server chạy trong Service để sống
        // được khi màn hình tắt hoặc người dùng rời app trong lúc TV đang phát.
        val serviceIntent = Intent(this, MediaCastService::class.java).apply {
            action = MediaCastService.ACTION_START
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(Intent(this, MediaCastService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

        pendingFilePath = filePath
    }

    private var pendingFilePath: String? = null

    private fun registerAndBuildLink() {
        val path = pendingFilePath ?: return
        val file = File(path)
        val url = castService?.registerAndGetUrl(file)
        streamUrl = url
        binding.tvStreamLink.text = url ?: getString(R.string.cast_no_devices)
    }

    private fun copyLinkToClipboard() {
        val url = streamUrl ?: return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("stream_url", url))
        Toast.makeText(this, getString(R.string.cast_link_copied), Toast.LENGTH_SHORT).show()
    }

    private fun scanForDevices() {
        binding.progressScan.visibility = android.view.View.VISIBLE
        binding.tvEmptyDevices.visibility = android.view.View.GONE
        lifecycleScope.launch {
            val devices = try {
                DlnaDiscovery.discover()
            } catch (e: Exception) {
                LogBus.error("Lỗi khi dò thiết bị DLNA", source = "DLNA", throwable = e)
                emptyList()
            }
            binding.progressScan.visibility = android.view.View.GONE
            deviceAdapter.submitList(devices)
            binding.tvEmptyDevices.visibility = if (devices.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun sendToDevice(device: DlnaDevice) {
        val url = streamUrl
        if (url == null) {
            Toast.makeText(this, getString(R.string.cast_sent_failed), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val ok = DlnaCastController.playUrl(device, url, mimeType)
            val message = if (ok) {
                getString(R.string.cast_sent_success, device.friendlyName)
            } else {
                getString(R.string.cast_sent_failed)
            }
            Toast.makeText(this@CastToTvActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        try {
            unbindService(serviceConnection)
        } catch (e: Exception) {
            // service có thể đã tự dừng/unbind trước đó — bỏ qua
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_IS_VIDEO = "extra_is_video"
    }
}
