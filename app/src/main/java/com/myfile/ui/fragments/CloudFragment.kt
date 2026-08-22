package com.myfile.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.myfile.ui.R
import com.myfile.ui.cloud.CloudServiceFactory
import com.myfile.ui.cloud.GoogleDriveService
import com.myfile.ui.cloud.OAuthManager
import com.myfile.ui.databinding.FragmentCloudBinding
import com.myfile.ui.databinding.ItemCloudProviderBinding
import com.myfile.ui.model.CloudProvider
import com.myfile.ui.CloudBrowserActivity
import com.myfile.ui.util.ActivityTransitions
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.launch

class CloudFragment : Fragment() {

    private var _binding: FragmentCloudBinding? = null
    private val binding get() = _binding!!
    private lateinit var oauthManager: OAuthManager

    private var pendingProvider: CloudProvider? = null

    private val oauthLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val provider = pendingProvider ?: return@registerForActivityResult
        val data = result.data
        if (data == null) {
            // Người dùng hủy giữa chừng (đóng tab trình duyệt) — không coi là lỗi, chỉ bỏ qua.
            return@registerForActivityResult
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = oauthManager.handleAuthResponse(data, provider)
            if (outcome.isFailure) {
                // Trước đây lỗi chỉ được ghi vào LogBus mà không hiện gì cho người dùng, nên khi
                // liên kết thất bại (ví dụ do app chưa cấu hình Client ID thật với nhà cung cấp)
                // màn hình trông như "không có gì xảy ra". Giờ báo rõ bằng Snackbar.
                showLinkError()
            }
            refreshAll()
        }
    }

    private val googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            if (account != null) {
                GoogleDriveService(requireContext()).handleSignInResult(account)
                com.myfile.ui.util.LogBus.success("Đã liên kết Google Drive: ${account.email}", source = "CLOUD")
            } else {
                com.myfile.ui.util.LogBus.warning("Đăng nhập Google trả về tài khoản null", source = "CLOUD")
                showLinkError()
            }
        } catch (e: Exception) {
            // Người dùng hủy đăng nhập, hoặc lỗi cấu hình (Client ID chưa đúng) — chỉ báo lỗi
            // thật sự, bỏ qua trường hợp người dùng tự hủy (mã lỗi 12501/16).
            val code = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
            // GHI RÕ MÃ LỖI ra LogBus thay vì chỉ hiện Snackbar chung chung — trước đây lỗi thật
            // (statusCode 10 = DEVELOPER_ERROR do SHA-1/Client ID sai cấu hình, 7 = NETWORK_ERROR,
            // v.v.) hoàn toàn bị nuốt mất, không cách nào biết được nguyên nhân thật khi liên kết
            // thất bại — Bảng điều khiển gỡ lỗi trống trơn dù người dùng vừa thử liên kết.
            com.myfile.ui.util.LogBus.warning(
                "Liên kết Google Drive thất bại (statusCode=$code): ${e.message}",
                source = "CLOUD"
            )
            if (code != com.google.android.gms.common.api.CommonStatusCodes.CANCELED) {
                showLinkError()
            }
        }
        refreshAll()
    }

    private fun showLinkError() {
        val v = _binding?.root ?: return
        com.google.android.material.snackbar.Snackbar.make(v, getString(R.string.cloud_link_failed), com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCloudBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        oauthManager = OAuthManager(requireContext())

        setupProviderCard(ItemCloudProviderBinding.bind(binding.cloudGdrive.root), CloudProvider.GOOGLE_DRIVE,
            R.drawable.ic_cloud_gdrive, getString(R.string.cloud_google_drive))
        setupProviderCard(ItemCloudProviderBinding.bind(binding.cloudDropbox.root), CloudProvider.DROPBOX,
            R.drawable.ic_cloud_dropbox, getString(R.string.cloud_dropbox))
        setupProviderCard(ItemCloudProviderBinding.bind(binding.cloudBox.root), CloudProvider.BOX,
            R.drawable.ic_cloud_box, getString(R.string.cloud_box))

        refreshAll()
    }

    private fun setupProviderCard(itemBinding: ItemCloudProviderBinding, provider: CloudProvider, iconRes: Int, name: String) {
        itemBinding.ivProviderLogo.setImageResource(iconRes)
        itemBinding.tvProviderName.text = name
        itemBinding.root.tag = provider

        itemBinding.btnLink.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val service = CloudServiceFactory.get(requireContext(), provider)
                // Guard sau suspend point: CloudServiceFactory.get()/isLinked() có thể đọc
                // token đã lưu qua network (refresh token hết hạn) — người dùng có thể rời
                // màn hình (đổi tab, back) trong lúc đang chờ, khiến _binding đã null nhưng
                // đoạn code dưới vẫn chạy tiếp và chạm requireContext()/binding -> crash.
                if (_binding == null) return@launch
                if (service.isLinked()) {
                    service.unlink()
                    refreshAll()
                } else {
                    if (provider == CloudProvider.GOOGLE_DRIVE) {
                        com.myfile.ui.util.LogBus.info("Mở màn đăng nhập Google Drive", source = "CLOUD")
                        googleSignInLauncher.launch(GoogleDriveService(requireContext()).getSignInIntent())
                    } else {
                        pendingProvider = provider
                        oauthLauncher.launch(oauthManager.buildAuthIntent(provider))
                    }
                }
            }
        }

        itemBinding.root.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val service = CloudServiceFactory.get(requireContext(), provider)
                if (_binding == null) return@launch
                if (service.isLinked()) {
                    val intent = Intent(requireContext(), CloudBrowserActivity::class.java)
                    intent.putExtra(CloudBrowserActivity.EXTRA_PROVIDER, provider.name)
                    startActivity(intent)
                    activity?.let { ActivityTransitions.forward(it) }
                }
            }
        }
    }

    private fun refreshAll() {
        viewLifecycleOwner.lifecycleScope.launch {
            // BẢO VỆ BẮT BUỘC: refreshAll() được gọi từ oauthLauncher/googleSignInLauncher SAU
            // KHI quay lại từ trình duyệt/màn đăng nhập ngoài — việc đó có thể mất vài giây.
            // Nếu trong lúc đó người dùng đã rời tab Cloud (View của Fragment này đã bị
            // onDestroyView() hủy, ví dụ chuyển sang tab khác trong BottomNav), _binding đã là
            // null nhưng coroutine theo viewLifecycleOwner.lifecycleScope không đảm bảo dừng
            // NGAY TỨC KHẮC — dòng code chạm vào `binding` (getter `_binding!!`) ngay sau đó sẽ
            // ném NullPointerException, crash app. Đây chính là kiểu crash "thỉnh thoảng, khó
            // tái hiện" vì chỉ xảy ra khi người dùng rời màn hình đúng lúc.
            if (_binding == null) return@launch
            updateCard(ItemCloudProviderBinding.bind(binding.cloudGdrive.root), CloudProvider.GOOGLE_DRIVE)
            if (_binding == null) return@launch
            updateCard(ItemCloudProviderBinding.bind(binding.cloudDropbox.root), CloudProvider.DROPBOX)
            if (_binding == null) return@launch
            updateCard(ItemCloudProviderBinding.bind(binding.cloudBox.root), CloudProvider.BOX)
        }
    }

    private suspend fun updateCard(itemBinding: ItemCloudProviderBinding, provider: CloudProvider) {
        val linked = CloudServiceFactory.get(requireContext(), provider).isLinked()
        itemBinding.tvProviderStatus.text = getString(
            if (linked) R.string.account_linked else R.string.account_not_linked
        )
        itemBinding.btnLink.text = getString(
            if (linked) R.string.btn_unlink_account else R.string.btn_link_account
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        oauthManager.dispose()
        _binding = null
    }
}
