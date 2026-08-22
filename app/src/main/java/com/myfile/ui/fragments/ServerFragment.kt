package com.myfile.ui.fragments

import com.myfile.ui.R
import android.animation.AnimatorInflater
import android.animation.AnimatorSet
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.myfile.ui.databinding.DialogAddUserBinding
import com.myfile.ui.databinding.FragmentServerBinding
import com.myfile.ui.model.FtpUser
import com.myfile.ui.dlna.RendererCastService
import com.myfile.ui.server.FtpServerService
import com.myfile.ui.server.MediaCastService
import com.myfile.ui.LogActivity
import com.myfile.ui.adapters.UserAdapter
import com.myfile.ui.util.ActivityTransitions
import com.myfile.ui.util.NetworkUtils
import com.myfile.ui.util.SecurePrefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File

class ServerFragment : Fragment() {

    private var _binding: FragmentServerBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: SecurePrefs
    private lateinit var userAdapter: UserAdapter
    private var serverRunning = false
    private var mediaServerRunning = false
    private var rendererRunning = false
    private var pulseAnimator: AnimatorSet? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentServerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = SecurePrefs.getInstance(requireContext())

        // Nội dung có thể cuộn — thêm padding-bottom động để phần cuối (nút bật/tắt máy chủ)
        // không dính sát mép gesture bar OneUI/HyperOS khi cuộn hết xuống đáy.
        com.myfile.ui.util.WindowInsetsUtils.applyBottomInsetPadding((view as ViewGroup).getChildAt(0))

        // Đọc trạng thái THẬT từ service thay vì mặc định false — tránh trường hợp
        // server vẫn đang chạy dưới nền nhưng UI hiển thị sai là "đã dừng" sau khi
        // rời tab/rotate màn hình khiến Fragment bị tạo lại.
        serverRunning = FtpServerService.isRunning()

        setupUserList()
        binding.etPort.setText(prefs.serverPort.toString())
        updateAddressLabel()

        binding.btnToggleServer.setOnClickListener { toggleServer() }
        binding.btnAddUser.setOnClickListener { showAddUserDialog() }
        binding.btnShowQr.setOnClickListener { showQrDialog() }
        binding.btnViewLogs.setOnClickListener {
            startActivity(Intent(requireContext(), LogActivity::class.java))
            activity?.let { ActivityTransitions.forward(it) }
        }
        binding.btnChooseFolder.setOnClickListener { showChooseFolderDialog() }

        val defaultRoot = android.os.Environment.getExternalStorageDirectory()?.absolutePath
        binding.tvRootFolder.text = prefs.rootFolderUri ?: defaultRoot ?: "(mặc định)"

        mediaServerRunning = MediaCastService.isRunning()
        binding.btnToggleMediaServer.setOnClickListener { toggleMediaServer() }
        binding.btnMediaShowQr.setOnClickListener { showMediaQrDialog() }
        binding.btnMediaCopyLink.setOnClickListener { copyMediaLink() }

        rendererRunning = RendererCastService.isRunning()
        binding.btnToggleRenderer.setOnClickListener { toggleRenderer() }
        binding.btnBrowseRemoteServers.setOnClickListener {
            startActivity(Intent(requireContext(), com.myfile.ui.RemoteServersActivity::class.java))
        }

        updateServerUiState()
        updateMediaServerUiState()
        updateRendererUiState()
    }

    override fun onResume() {
        super.onResume()
        // Đồng bộ lại mỗi khi quay lại tab này, để không bao giờ hiển thị lệch trạng thái thật.
        val actuallyRunning = FtpServerService.isRunning()
        if (actuallyRunning != serverRunning) {
            serverRunning = actuallyRunning
            updateServerUiState()
        }
        val mediaActuallyRunning = MediaCastService.isRunning()
        if (mediaActuallyRunning != mediaServerRunning) {
            mediaServerRunning = mediaActuallyRunning
            updateMediaServerUiState()
        }
        val rendererActuallyRunning = RendererCastService.isRunning()
        if (rendererActuallyRunning != rendererRunning) {
            rendererRunning = rendererActuallyRunning
            updateRendererUiState()
        }
    }

    /** Bật/tắt vai trò Renderer: cho phép thiết bị khác "Phát tới" MyFile Manager (kiểu BubbleUPnP). */
    private fun toggleRenderer() {
        val intent = Intent(requireContext(), RendererCastService::class.java)
        if (!rendererRunning) {
            intent.action = RendererCastService.ACTION_START
            requireContext().startForegroundService(intent)
        } else {
            intent.action = RendererCastService.ACTION_STOP
            requireContext().startService(intent)
        }
        rendererRunning = !rendererRunning
        updateRendererUiState()
        // Service có thể tự stopSelf() gần như ngay lập tức nếu khởi động thất bại (VD cổng bận,
        // lỗi mạng) — trước đây UI vẫn hiển thị "đang chạy" cho tới tận lần onResume() kế tiếp,
        // trông như server "tự tắt" không rõ lý do. Kiểm tra lại trạng thái thật sau một nhịp
        // ngắn để phản ánh đúng ngay lập tức.
        binding.root.postDelayed({
            if (_binding == null) return@postDelayed
            val actuallyRunning = RendererCastService.isRunning()
            if (actuallyRunning != rendererRunning) {
                rendererRunning = actuallyRunning
                updateRendererUiState()
                if (!actuallyRunning) {
                    com.google.android.material.snackbar.Snackbar.make(
                        binding.root, getString(com.myfile.ui.R.string.error_generic), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
        }, 600)
    }

    private fun updateRendererUiState() {
        if (rendererRunning) {
            binding.tvRendererStatus.text = getString(com.myfile.ui.R.string.renderer_running)
            binding.btnToggleRenderer.text = getString(com.myfile.ui.R.string.btn_stop_renderer)
            binding.btnToggleRenderer.setIconResource(com.myfile.ui.R.drawable.ic_stop)
        } else {
            binding.tvRendererStatus.text = getString(com.myfile.ui.R.string.renderer_stopped)
            binding.btnToggleRenderer.text = getString(com.myfile.ui.R.string.btn_start_renderer)
            binding.btnToggleRenderer.setIconResource(com.myfile.ui.R.drawable.ic_play)
        }
    }

    /** Bật/tắt "Máy chủ Media": chia sẻ toàn bộ thư mục gốc hiện tại để TV duyệt qua DLNA hoặc trình duyệt. */
    private fun toggleMediaServer() {
        val intent = Intent(requireContext(), MediaCastService::class.java)
        if (!mediaServerRunning) {
            val root = binding.tvRootFolder.text?.toString()
                ?: android.os.Environment.getExternalStorageDirectory()?.absolutePath
            intent.action = MediaCastService.ACTION_START
            intent.putExtra(MediaCastService.EXTRA_ROOT_FOLDER, root)
            requireContext().startForegroundService(intent)
        } else {
            intent.action = MediaCastService.ACTION_STOP
            requireContext().startService(intent)
        }
        mediaServerRunning = !mediaServerRunning
        updateMediaServerUiState()
        // Cùng lý do như toggleRenderer(): xác nhận lại trạng thái thật sau một nhịp ngắn,
        // tránh UI kẹt ở "đang chạy" trong khi service đã tự dừng do khởi động thất bại.
        binding.root.postDelayed({
            if (_binding == null) return@postDelayed
            val actuallyRunning = MediaCastService.isRunning()
            if (actuallyRunning != mediaServerRunning) {
                mediaServerRunning = actuallyRunning
                updateMediaServerUiState()
                if (!actuallyRunning) {
                    com.google.android.material.snackbar.Snackbar.make(
                        binding.root, getString(com.myfile.ui.R.string.error_generic), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
        }, 600)
    }

    private fun updateMediaServerUiState() {
        if (mediaServerRunning) {
            binding.tvMediaStatus.text = getString(com.myfile.ui.R.string.media_server_running)
            binding.btnToggleMediaServer.text = getString(com.myfile.ui.R.string.btn_stop_media_server)
            binding.btnToggleMediaServer.setIconResource(com.myfile.ui.R.drawable.ic_stop)
            val ip = NetworkUtils.getLocalIpAddress(requireContext()) ?: "—"
            binding.tvMediaAddress.text = "http://$ip:${MediaCastService.STREAM_PORT}/browse/"
        } else {
            binding.tvMediaStatus.text = getString(com.myfile.ui.R.string.media_server_stopped)
            binding.btnToggleMediaServer.text = getString(com.myfile.ui.R.string.btn_start_media_server)
            binding.btnToggleMediaServer.setIconResource(com.myfile.ui.R.drawable.ic_cast)
            binding.tvMediaAddress.text = ""
        }
    }

    private fun copyMediaLink() {
        if (!mediaServerRunning) {
            com.google.android.material.snackbar.Snackbar.make(
                binding.root, getString(com.myfile.ui.R.string.media_start_server_first), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
            ).show()
            return
        }
        val link = binding.tvMediaAddress.text?.toString().orEmpty()
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MyFile Manager", link))
        com.google.android.material.snackbar.Snackbar.make(
            binding.root, getString(com.myfile.ui.R.string.media_link_copied), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
        ).show()
    }

    /** QR chứa liên kết duyệt file, để TV/điện thoại khác quét mở nhanh bằng trình duyệt. */
    private fun showMediaQrDialog() {
        if (!mediaServerRunning) {
            com.google.android.material.snackbar.Snackbar.make(
                binding.root, getString(com.myfile.ui.R.string.media_start_server_first), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
            ).show()
            return
        }
        val link = binding.tvMediaAddress.text?.toString().orEmpty()
        val dialogBinding = com.myfile.ui.databinding.DialogQrCodeBinding.inflate(layoutInflater)
        val bitmap = com.myfile.ui.util.QrCodeUtils.encode(link)
        dialogBinding.ivQrCode.setImageBitmap(bitmap)
        dialogBinding.tvQrAddress.text = link

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(com.myfile.ui.R.string.title_qr_code))
            .setView(dialogBinding.root)
            .setPositiveButton(getString(com.myfile.ui.R.string.ok), null)
            .show()
    }

    private fun setupUserList() {
        userAdapter = UserAdapter(
            onEdit = { user -> showEditUserDialog(user) },
            onDelete = { user ->
                val users = prefs.loadFtpUsers()
                users.removeAll { it.username == user.username }
                prefs.saveFtpUsers(users)
                userAdapter.submit(users)
            }
        )
        binding.rvUsers.layoutManager = LinearLayoutManager(requireContext())
        binding.rvUsers.adapter = userAdapter
        userAdapter.submit(prefs.loadFtpUsers())
        binding.rvUsers.scheduleLayoutAnimation()
    }

    private fun showAddUserDialog() {
        val dialogBinding = DialogAddUserBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(com.myfile.ui.R.string.btn_add_user))
            .setView(dialogBinding.root)
            .setPositiveButton(getString(com.myfile.ui.R.string.save)) { _, _ ->
                val username = dialogBinding.etNewUsername.text?.toString()?.trim().orEmpty()
                val password = dialogBinding.etNewPassword.text?.toString()?.trim().orEmpty()
                if (username.isNotEmpty() && password.isNotEmpty()) {
                    val users = prefs.loadFtpUsers()
                    users.removeAll { it.username == username }
                    users.add(
                        FtpUser(
                            username = username,
                            password = password,
                            homeDirectory = "",
                            writePermission = dialogBinding.switchWritePermission.isChecked
                        )
                    )
                    prefs.saveFtpUsers(users)
                    userAdapter.submit(users)
                }
            }
            .setNegativeButton(getString(com.myfile.ui.R.string.cancel), null)
            .show()
    }

    /** Sửa tên đăng nhập / mật khẩu / quyền của 1 user đã có, tiền điền sẵn dữ liệu hiện tại. */
    private fun showEditUserDialog(original: FtpUser) {
        val dialogBinding = DialogAddUserBinding.inflate(layoutInflater)
        dialogBinding.etNewUsername.setText(original.username)
        dialogBinding.etNewPassword.setText(original.password)
        dialogBinding.switchWritePermission.isChecked = original.writePermission

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(com.myfile.ui.R.string.title_edit_user))
            .setView(dialogBinding.root)
            .setPositiveButton(getString(com.myfile.ui.R.string.save)) { _, _ ->
                val username = dialogBinding.etNewUsername.text?.toString()?.trim().orEmpty()
                val password = dialogBinding.etNewPassword.text?.toString()?.trim().orEmpty()
                if (username.isNotEmpty() && password.isNotEmpty()) {
                    val users = prefs.loadFtpUsers()
                    // Xóa bản ghi cũ (theo username gốc) và bản ghi trùng username mới (nếu đổi tên
                    // trùng với user khác đã có) trước khi thêm bản ghi đã sửa vào.
                    users.removeAll { it.username == original.username || it.username == username }
                    users.add(
                        FtpUser(
                            username = username,
                            password = password,
                            homeDirectory = original.homeDirectory,
                            writePermission = dialogBinding.switchWritePermission.isChecked
                        )
                    )
                    prefs.saveFtpUsers(users)
                    userAdapter.submit(users)
                    if (serverRunning) {
                        com.google.android.material.snackbar.Snackbar.make(
                            binding.root, getString(com.myfile.ui.R.string.restart_server_hint), com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton(getString(com.myfile.ui.R.string.cancel), null)
            .show()
    }

    /** Cho chọn nhanh giữa vài thư mục gốc phổ biến, gồm cả thẻ SD card thật nếu thiết bị có. */
    private fun showChooseFolderDialog() {
        val root = android.os.Environment.getExternalStorageDirectory()
        val options = mutableListOf(
            getString(com.myfile.ui.R.string.folder_option_internal) to root.absolutePath,
            "DCIM" to File(root, "DCIM").absolutePath,
            "Download" to File(root, "Download").absolutePath,
            "Documents" to File(root, "Documents").absolutePath,
            "Pictures" to File(root, "Pictures").absolutePath
        )
        val sdPath = com.myfile.ui.util.SdCardUtils.findSdCardPath(requireContext())
        if (sdPath != null) {
            options.add(getString(com.myfile.ui.R.string.home_sdcard) to sdPath)
            // Root gộp cả 2 ổ đĩa: /storage chứa cả /storage/emulated/0 (bộ nhớ trong)
            // và /storage/XXXX-XXXX (thẻ SD) như 2 thư mục con — cho phép client FTP
            // duyệt và truy cập cả hai cùng lúc qua 1 kết nối, không cần đổi root qua lại.
            options.add(getString(com.myfile.ui.R.string.folder_option_both) to "/storage")
        }
        val labels = options.map { it.first }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(com.myfile.ui.R.string.label_root_folder))
            .setItems(labels) { _, which ->
                val path = options[which].second
                binding.tvRootFolder.text = path
                if (serverRunning) {
                    com.google.android.material.snackbar.Snackbar.make(
                        binding.root, getString(com.myfile.ui.R.string.restart_server_hint), com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton(getString(com.myfile.ui.R.string.cancel), null)
            .show()
    }

    private fun toggleServer() {
        val port = binding.etPort.text?.toString()?.toIntOrNull() ?: 2121
        prefs.serverPort = port
        prefs.rootFolderUri = binding.tvRootFolder.text?.toString()

        if (!serverRunning) {
            maybeAskBatteryOptimization()
        }

        val intent = Intent(requireContext(), FtpServerService::class.java)
        if (!serverRunning) {
            intent.action = FtpServerService.ACTION_START
            requireContext().startForegroundService(intent)
        } else {
            intent.action = FtpServerService.ACTION_STOP
            requireContext().startService(intent)
        }
        serverRunning = !serverRunning
        animateToggleButton()
        updateServerUiState()
    }

    /**
     * Hỏi xin miễn trừ tối ưu hoá pin ĐÚNG 1 LẦN trong toàn bộ vòng đời sử dụng app, ngay lúc
     * người dùng lần đầu chủ động bật 1 máy chủ (FTP/Media) — đây là thời điểm hợp lý nhất
     * vì ý định "muốn chạy nền ổn định" đã rõ ràng, không phải hỏi đột ngột lúc mở app. Không
     * hỏi lại các lần bật sau, kể cả nếu người dùng đã từ chối trước đó — tôn trọng lựa chọn.
     */
    private fun maybeAskBatteryOptimization() {
        if (prefs.hasAskedBatteryOptimization) return
        if (com.myfile.ui.util.BatteryOptimizationUtils.isIgnoringBatteryOptimizations(requireContext())) return
        prefs.hasAskedBatteryOptimization = true

        val isXiaomi = com.myfile.ui.util.BatteryOptimizationUtils.isXiaomiDevice()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.battery_opt_title))
            .setMessage(if (isXiaomi) getString(R.string.battery_opt_msg_xiaomi) else getString(R.string.battery_opt_msg_generic))
            .setPositiveButton(getString(R.string.battery_opt_btn_allow)) { _, _ ->
                com.myfile.ui.util.BatteryOptimizationUtils.requestIgnoreBatteryOptimizations(requireActivity())
                if (isXiaomi) {
                    // Mở tiếp màn Autostart riêng của Xiaomi SAU khi người dùng xử lý xong hộp
                    // thoại miễn trừ pin chuẩn — 2 bước tách biệt, không thể gộp làm 1 vì đây
                    // là 2 activity hệ thống khác nhau hoàn toàn.
                    com.myfile.ui.util.BatteryOptimizationUtils.openXiaomiAutostartSettings(requireContext())
                }
            }
            .setNegativeButton(getString(R.string.battery_opt_btn_skip), null)
            .show()
    }

    /** Hiệu ứng nảy nhẹ (bounce) khi bấm bật/tắt máy chủ, tạo cảm giác phản hồi mượt mà. */
    private fun animateToggleButton() {
        binding.btnToggleServer.animate()
            .scaleX(0.94f).scaleY(0.94f)
            .setDuration(90)
            .withEndAction {
                binding.btnToggleServer.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(140)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2.5f))
                    .start()
            }
            .start()
    }

    private fun updateServerUiState() {
        // Mờ dần rồi hiện lại cho trạng thái + địa chỉ, tránh đổi chữ đột ngột gây giật
        binding.tvStatus.animate().alpha(0f).setDuration(100).withEndAction {
            if (serverRunning) {
                binding.tvStatus.text = getString(com.myfile.ui.R.string.server_status_running)
                binding.btnToggleServer.text = getString(com.myfile.ui.R.string.btn_stop_server)
                binding.btnToggleServer.setIconResource(com.myfile.ui.R.drawable.ic_stop)
                updateAddressLabel()
            } else {
                binding.tvStatus.text = getString(com.myfile.ui.R.string.server_status_stopped)
                binding.btnToggleServer.text = getString(com.myfile.ui.R.string.btn_start_server)
                binding.btnToggleServer.setIconResource(com.myfile.ui.R.drawable.ic_play)
                binding.tvAddress.text = ""
            }
            binding.tvStatus.animate().alpha(1f).setDuration(160).start()
            updateServerPulse()
        }.start()
    }

    /** Bật/tắt hiệu ứng nhấp nháy nhẹ (pulse) trên icon máy chủ tùy trạng thái đang chạy hay không. */
    private fun updateServerPulse() {
        if (serverRunning) {
            if (pulseAnimator == null) {
                pulseAnimator = (AnimatorInflater.loadAnimator(requireContext(), com.myfile.ui.R.animator.pulse_animator) as AnimatorSet).apply {
                    setTarget(binding.ivServerIcon)
                    start()
                }
            }
        } else {
            pulseAnimator?.cancel()
            pulseAnimator = null
            binding.ivServerIcon.scaleX = 1f
            binding.ivServerIcon.scaleY = 1f
        }
    }

    private fun updateAddressLabel() {
        val ip = NetworkUtils.getLocalIpAddress(requireContext()) ?: "—"
        val port = prefs.serverPort
        binding.tvAddress.text = "ftp://$ip:$port"
    }

    /**
     * Hiện dialog chứa mã QR mã hoá địa chỉ + tài khoản đăng nhập của server đang chạy,
     * để máy khác quét bằng "Quét mã QR" ở màn hình Kết nối FTP thay vì gõ tay.
     */
    private fun showQrDialog() {
        if (!serverRunning) {
            com.google.android.material.snackbar.Snackbar.make(
                binding.root, getString(com.myfile.ui.R.string.qr_start_server_first), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
            ).show()
            return
        }
        val ip = com.myfile.ui.util.NetworkUtils.getLocalIpAddress(requireContext())
        if (ip == null) {
            com.google.android.material.snackbar.Snackbar.make(
                binding.root, getString(com.myfile.ui.R.string.error_generic), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
            ).show()
            return
        }
        // Ưu tiên tài khoản đầu tiên đã cấu hình để QR chứa sẵn user/pass; nếu chưa thêm
        // user nào thì vẫn tạo QR chỉ có địa chỉ, người quét sẽ phải tự nhập tài khoản.
        val firstUser = prefs.loadFtpUsers().firstOrNull()
        val uri = com.myfile.ui.util.QrCodeUtils.buildServerUri(
            host = ip,
            port = prefs.serverPort,
            username = firstUser?.username,
            password = firstUser?.password
        )

        val dialogBinding = com.myfile.ui.databinding.DialogQrCodeBinding.inflate(layoutInflater)
        val bitmap = com.myfile.ui.util.QrCodeUtils.encode(uri)
        dialogBinding.ivQrCode.setImageBitmap(bitmap)
        dialogBinding.tvQrAddress.text = "ftp://$ip:${prefs.serverPort}"

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(com.myfile.ui.R.string.title_qr_code))
            .setView(dialogBinding.root)
            .setPositiveButton(getString(com.myfile.ui.R.string.ok), null)
            .show()
    }

    override fun onDestroyView() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        super.onDestroyView()
        _binding = null
    }
}
