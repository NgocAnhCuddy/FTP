package com.myfile.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.myfile.ui.AppLockActivity
import com.myfile.ui.BuildConfig
import com.myfile.ui.databinding.FragmentSettingsBinding
import com.myfile.ui.util.SecurePrefs

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    // CREATE_OR_CHANGE_PIN: đang mở AppLockActivity để tạo PIN mới (bật khoá lần đầu, hoặc đổi
    // PIN). CONFIRM_TO_DISABLE: đang mở để XÁC THỰC PIN hiện tại trước khi cho phép TẮT khoá.
    private var pendingAction: PendingAction = PendingAction.NONE

    private enum class PendingAction { NONE, CREATE_OR_CHANGE_PIN, CONFIRM_TO_DISABLE }

    private lateinit var appLockLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appLockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                when (pendingAction) {
                    // AppLockActivity ở MODE_CREATE đã tự lưu PIN mới + bật appLockEnabled khi
                    // thành công — không cần làm gì thêm ở đây ngoài đồng bộ lại UI bên dưới.
                    PendingAction.CREATE_OR_CHANGE_PIN -> {}
                    PendingAction.CONFIRM_TO_DISABLE -> {
                        SecurePrefs.getInstance(requireContext()).clearAppLock()
                    }
                    PendingAction.NONE -> {}
                }
            }
            // Nếu người dùng bấm Back/thoát giữa chừng: không làm gì cả, refreshAppLockUi() bên
            // dưới sẽ tự đưa switch về đúng trạng thái thật đang lưu trong SecurePrefs.
            pendingAction = PendingAction.NONE
            refreshAppLockUi()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvVersion.text = "Phiên bản ${BuildConfig.VERSION_NAME}"

        binding.btnChangePin.setOnClickListener {
            pendingAction = PendingAction.CREATE_OR_CHANGE_PIN
            appLockLauncher.launch(
                Intent(requireContext(), AppLockActivity::class.java)
                    .putExtra(AppLockActivity.EXTRA_MODE, AppLockActivity.MODE_CREATE)
            )
        }

        binding.switchShowHiddenFiles.isChecked = SecurePrefs.getInstance(requireContext()).showHiddenFiles
        binding.switchShowHiddenFiles.setOnCheckedChangeListener { _, isChecked ->
            SecurePrefs.getInstance(requireContext()).showHiddenFiles = isChecked
        }

        refreshAppLockUi()
    }

    override fun onResume() {
        super.onResume()
        // Đồng bộ lại UI mỗi khi Fragment quay lại hiển thị, phòng trạng thái đổi từ nơi khác.
        refreshAppLockUi()
    }

    /**
     * Gắn lại toàn bộ listener của khu vực Khoá app VÀ đồng bộ giá trị hiển thị khớp đúng với
     * dữ liệu thật trong SecurePrefs. Gộp chung 2 việc (thay vì tách riêng "set giá trị" và
     * "gắn listener") để tránh vòng lặp gọi lẫn nhau: set isChecked bằng code sẽ tự trigger
     * listener nếu listener đã gắn từ trước — nên PHẢI gỡ listener (setOnCheckedChangeListener
     * (null)) ngay trước khi set giá trị, rồi mới gắn lại listener thật sau đó.
     */
    private fun refreshAppLockUi() {
        val prefs = SecurePrefs.getInstance(requireContext())

        binding.switchAppLock.setOnCheckedChangeListener(null)
        binding.switchAppLock.isChecked = prefs.appLockEnabled
        binding.groupAppLockOptions.visibility = if (prefs.appLockEnabled) View.VISIBLE else View.GONE
        binding.switchAppLock.setOnCheckedChangeListener { _, isChecked ->
            val p = SecurePrefs.getInstance(requireContext())
            if (isChecked && !p.appLockEnabled) {
                // Bật khoá: mở màn tạo PIN. CHƯA set appLockEnabled ở đây — AppLockActivity tự
                // bật cờ này SAU KHI tạo PIN thành công, tránh trường hợp bật cờ trước rồi
                // người dùng bấm Back giữa chừng, khiến app tự khoá ngay lần mở tiếp theo mà
                // chưa hề có PIN nào được lưu (tự khoá chết chính mình, không lối thoát).
                pendingAction = PendingAction.CREATE_OR_CHANGE_PIN
                appLockLauncher.launch(
                    Intent(requireContext(), AppLockActivity::class.java)
                        .putExtra(AppLockActivity.EXTRA_MODE, AppLockActivity.MODE_CREATE)
                )
            } else if (!isChecked && p.appLockEnabled) {
                // Tắt khoá: bắt buộc xác thực lại PIN hiện tại trước khi cho phép tắt — tránh
                // trường hợp ai đó cầm máy đang mở sẵn app tắt khoá hộ chỉ bằng 1 lần chạm.
                pendingAction = PendingAction.CONFIRM_TO_DISABLE
                appLockLauncher.launch(
                    Intent(requireContext(), AppLockActivity::class.java)
                        .putExtra(AppLockActivity.EXTRA_MODE, AppLockActivity.MODE_UNLOCK)
                )
            }
        }

        binding.switchAppLockBiometric.setOnCheckedChangeListener(null)
        binding.switchAppLockBiometric.isChecked = prefs.appLockBiometricEnabled
        binding.switchAppLockBiometric.setOnCheckedChangeListener { _, isChecked ->
            SecurePrefs.getInstance(requireContext()).appLockBiometricEnabled = isChecked
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
