package com.myfile.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.myfile.ui.adapters.RemoteDidlAdapter
import com.myfile.ui.adapters.RemoteServerAdapter
import com.myfile.ui.databinding.ActivityRemoteServersBinding
import com.myfile.ui.dlna.RemoteContentDirectoryClient
import com.myfile.ui.dlna.RemoteDidlItem
import com.myfile.ui.dlna.RemoteMediaServer
import com.myfile.ui.util.LogBus
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Màn hình "Duyệt máy chủ DLNA khác" — phần control point kiểu BubbleUPnP: dò các MediaServer
 * khác trong mạng (NAS, điện thoại khác cũng chạy MyFile Manager, TV chia sẻ file...), duyệt
 * thư mục của họ, và phát trực tiếp file tìm được (không tải về máy — mở URL qua mạng).
 *
 * 2 trạng thái dùng chung 1 RecyclerView:
 *  - Chưa chọn server: hiển thị danh sách server tìm thấy (RemoteServerAdapter).
 *  - Đã chọn server: hiển thị nội dung thư mục hiện tại (RemoteDidlAdapter), có ngăn xếp
 *    breadcrumb (folderStack) để hỗ trợ nút "Lên thư mục cha" và nút Back của hệ thống.
 */
class RemoteServersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRemoteServersBinding
    private lateinit var serverAdapter: RemoteServerAdapter
    private lateinit var didlAdapter: RemoteDidlAdapter

    private var currentServer: RemoteMediaServer? = null
    /** Ngăn xếp (objectId, tên hiển thị) các thư mục đã vào, để hỗ trợ quay lại đúng đường dẫn. */
    private val folderStack = ArrayDeque<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteServersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            // handleBackNavigation() chỉ xử lý khi đang ở trong 1 server/thư mục con (điều hướng
            // "lùi 1 cấp"); ở màn hình gốc (chưa chọn server) nó trả về false và trước đây
            // KHÔNG CÓ gì gọi tiếp — khiến nút back trên toolbar không phản hồi gì cả. Phải tự
            // đóng Activity trong trường hợp đó, giống hành vi nút back hệ thống.
            if (!handleBackNavigation()) finish()
        }

        serverAdapter = RemoteServerAdapter { server -> openServer(server) }
        didlAdapter = RemoteDidlAdapter { entry -> handleEntryClick(entry) }

        binding.rvList.layoutManager = LinearLayoutManager(this)
        binding.rvList.adapter = serverAdapter

        binding.btnScanDevices.setOnClickListener { scanForServers() }
        binding.btnGoUp.setOnClickListener { goUpOneLevel() }

        onBackPressedDispatcher.addCallback(this) {
            // handleBackNavigation() trả false nghĩa là không còn gì để "lùi 1 cấp" bên trong
            // màn hình này (đang ở danh sách server gốc) — phải thoát Activity thẳng, KHÔNG
            // disable rồi gọi lại dispatcher (cách cũ dễ khiến back bị "nuốt" im lặng khi đây
            // là callback duy nhất đăng ký, khiến nút back hệ thống trông như không hoạt động).
            if (!handleBackNavigation()) finish()
        }

        scanForServers()
    }

    /** Trả về true nếu đã xử lý (đang trong thư mục con hoặc trong 1 server) — chặn back mặc định của Activity. */
    private fun handleBackNavigation(): Boolean {
        return when {
            folderStack.size > 1 -> { goUpOneLevel(); true }
            currentServer != null -> { backToServerList(); true }
            else -> false
        }
    }

    // ---------------- danh sách server ----------------

    private fun scanForServers() {
        binding.progressScan.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        binding.rvList.adapter = serverAdapter
        lifecycleScope.launch {
            val servers = try {
                RemoteContentDirectoryClient.discoverServers()
            } catch (e: Exception) {
                LogBus.error("Lỗi khi dò máy chủ DLNA", source = "DLNA", throwable = e)
                emptyList()
            }
            binding.progressScan.visibility = View.GONE
            serverAdapter.submitList(servers)
            binding.tvEmpty.text = getString(R.string.remote_no_servers)
            binding.tvEmpty.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun openServer(server: RemoteMediaServer) {
        currentServer = server
        folderStack.clear()
        folderStack.push("0" to server.friendlyName)
        binding.toolbar.title = server.friendlyName
        binding.layoutPathBar.visibility = View.VISIBLE
        binding.btnScanDevices.visibility = View.GONE
        binding.rvList.adapter = didlAdapter
        loadCurrentFolder()
    }

    private fun backToServerList() {
        currentServer = null
        folderStack.clear()
        binding.toolbar.title = getString(R.string.title_remote_servers)
        binding.layoutPathBar.visibility = View.GONE
        binding.btnScanDevices.visibility = View.VISIBLE
        binding.rvList.adapter = serverAdapter
        binding.tvEmpty.visibility = View.GONE
    }

    // ---------------- duyệt thư mục trong 1 server ----------------

    private fun goUpOneLevel() {
        if (folderStack.size <= 1) {
            backToServerList()
            return
        }
        folderStack.pop()
        loadCurrentFolder()
    }

    private fun loadCurrentFolder() {
        val server = currentServer ?: return
        val (objectId, name) = folderStack.peek() ?: ("0" to server.friendlyName)
        binding.tvCurrentPath.text = folderStack.reversed().joinToString(" / ") { it.second }
        binding.progressScan.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE

        lifecycleScope.launch {
            val entries = try {
                RemoteContentDirectoryClient.browse(server, objectId)
            } catch (e: Exception) {
                LogBus.error("Lỗi khi duyệt máy chủ ${server.friendlyName}", source = "DLNA", throwable = e)
                null
            }
            binding.progressScan.visibility = View.GONE
            if (entries == null) {
                Toast.makeText(this@RemoteServersActivity, getString(R.string.remote_browse_failed), Toast.LENGTH_SHORT).show()
                didlAdapter.submitList(emptyList())
                binding.tvEmpty.text = getString(R.string.remote_browse_failed)
                binding.tvEmpty.visibility = View.VISIBLE
                return@launch
            }
            didlAdapter.submitList(entries)
            binding.tvEmpty.text = getString(R.string.remote_empty_folder)
            binding.tvEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun handleEntryClick(entry: RemoteDidlItem) {
        if (entry.isContainer) {
            folderStack.push(entry.id to entry.title)
            loadCurrentFolder()
            return
        }
        val url = entry.resUrl
        if (url.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.remote_open_failed), Toast.LENGTH_SHORT).show()
            return
        }
        playRemoteFile(url, entry.title, entry.mimeType)
    }

    /** Mở file từ xa bằng chính màn hình xem ảnh/video hoặc phát nhạc có sẵn trong app — không tải về máy. */
    private fun playRemoteFile(url: String, name: String, mimeType: String?) {
        val isAudio = mimeType?.startsWith("audio") == true
        if (isAudio) {
            val intent = android.content.Intent(this, AudioPlayerActivity::class.java).apply {
                putStringArrayListExtra(AudioPlayerActivity.EXTRA_URIS, arrayListOf(url))
                putStringArrayListExtra(AudioPlayerActivity.EXTRA_NAMES, arrayListOf(name))
                putExtra(AudioPlayerActivity.EXTRA_START_INDEX, 0)
            }
            startActivity(intent)
        } else {
            val isVideo = mimeType?.startsWith("video") == true
            val intent = android.content.Intent(this, MediaViewerActivity::class.java).apply {
                putStringArrayListExtra(MediaViewerActivity.EXTRA_URIS, arrayListOf(url))
                putStringArrayListExtra(MediaViewerActivity.EXTRA_NAMES, arrayListOf(name))
                putStringArrayListExtra(MediaViewerActivity.EXTRA_REAL_PATHS, arrayListOf<String>())
                putExtra(MediaViewerActivity.EXTRA_IS_VIDEO, booleanArrayOf(isVideo))
                putExtra(MediaViewerActivity.EXTRA_START_POSITION, 0)
            }
            startActivity(intent)
        }
    }
}
