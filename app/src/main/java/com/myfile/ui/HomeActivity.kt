package com.myfile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.myfile.ui.R
import com.myfile.ui.databinding.ActivityHomeBinding
import com.myfile.ui.databinding.ItemCategoryBinding
import com.myfile.ui.util.ActivityTransitions
import java.text.DecimalFormat
import kotlin.math.roundToInt

/**
 * Màn hình chính của app, phỏng theo giao diện Samsung "My Files":
 * tìm kiếm, file gần đây, lưới thể loại, danh sách lưu trữ (bao gồm "Lưu trữ mạng"
 * dẫn vào FTP Server/Client/Cloud của app), và tiện ích (thùng rác).
 * Tự chuyển sáng/tối theo theme hệ thống nhờ Theme.Material3.DayNight.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    // Chờ người dùng chọn 1 file bất kỳ trong máy để mở bằng Trình soạn thảo mã.
    private val pickCodeFileLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { openPickedFile(it, forEditor = true) }
    }

    // Chờ người dùng chọn 1 file .html để chạy trực tiếp trong app.
    private val pickHtmlFileLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { openPickedFile(it, forEditor = false) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestRuntimePermissions()
        setupCategoryGrid()
        setupStorageInfo()
        setupClickRows()
        setupFabScrollBehavior()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    override fun onResume() {
        super.onResume()
        // Cập nhật lại dung lượng mỗi khi quay lại màn hình — vd sau khi copy/xóa file ở
        // màn khác rồi back về đây, số liệu và thanh dung lượng phải phản ánh đúng ngay,
        // không đợi mở lại app từ đầu. Trước đây setupSdCardRow() chỉ chạy 1 lần lúc
        // onCreate() nên thanh Thẻ nhớ SD bị đứng yên dù đã dùng thêm/xóa bớt dung lượng.
        setupStorageInfo()
        setupSdCardRow()
    }

    private fun setupCategoryGrid() {
        bindCategory(ItemCategoryBinding.bind(binding.catPhoto.root), R.drawable.ic_cat_photo, R.drawable.bg_cat_circle_photo, getString(R.string.home_category_photo))
        bindCategory(ItemCategoryBinding.bind(binding.catVideo.root), R.drawable.ic_cat_video, R.drawable.bg_cat_circle_video, getString(R.string.home_category_video))
        bindCategory(ItemCategoryBinding.bind(binding.catAudio.root), R.drawable.ic_cat_audio, R.drawable.bg_cat_circle_audio, getString(R.string.home_category_audio))
        bindCategory(ItemCategoryBinding.bind(binding.catDoc.root), R.drawable.ic_cat_doc, R.drawable.bg_cat_circle_doc, getString(R.string.home_category_doc))
        bindCategory(ItemCategoryBinding.bind(binding.catDownload.root), R.drawable.ic_cat_download, R.drawable.bg_cat_circle_download, getString(R.string.home_category_download))
        bindCategory(ItemCategoryBinding.bind(binding.catApk.root), R.drawable.ic_cat_apk, R.drawable.bg_cat_circle_apk, getString(R.string.home_category_apk))

        binding.catPhoto.root.setOnClickListener { openCategoryBrowser(CategoryType.IMAGE) }
        binding.catVideo.root.setOnClickListener { openCategoryBrowser(CategoryType.VIDEO) }
        binding.catAudio.root.setOnClickListener { openCategoryBrowser(CategoryType.AUDIO) }
        binding.catDoc.root.setOnClickListener { openCategoryBrowser(CategoryType.DOCUMENT) }
        binding.catDownload.root.setOnClickListener { openCategoryBrowser(CategoryType.DOWNLOAD) }
        binding.catApk.root.setOnClickListener { openCategoryBrowser(CategoryType.APK) }
    }

    private fun bindCategory(itemBinding: ItemCategoryBinding, iconRes: Int, badgeRes: Int, name: String) {
        itemBinding.ivCatIcon.setImageResource(iconRes)
        itemBinding.iconBadge.setBackgroundResource(badgeRes)
        itemBinding.tvCatName.text = name
    }

    /** Mở màn hình duyệt file nội bộ đã lọc sẵn theo thể loại, giống hành vi của Samsung My Files. */
    private fun openCategoryBrowser(type: CategoryType) {
        val intent = Intent(this, CategoryFilesActivity::class.java).apply {
            putExtra(CategoryFilesActivity.EXTRA_CATEGORY, type.name)
        }
        ActivityTransitions.startForward(this, intent)
    }

    /**
     * Thu gọn FAB "Lưu trữ mạng" thành hình tròn chỉ icon khi cuộn xuống (giống hành vi
     * FAB của One UI), để không che nội dung và vẫn nằm gọn trong tầm ngón cái.
     */
    private fun setupFabScrollBehavior() {
        var extended = true
        binding.scrollHome.setOnScrollChangeListener(
            androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                if (scrollY > oldScrollY && extended) {
                    binding.fabQuickConnect.shrink()
                    extended = false
                } else if (scrollY < oldScrollY && !extended) {
                    binding.fabQuickConnect.extend()
                    extended = true
                }
            }
        )
    }

    private fun setupStorageInfo() {
        try {
            val stat = StatFs(android.os.Environment.getDataDirectory().path)
            val totalBytes = stat.totalBytes
            val availableBytes = stat.availableBytes
            val usedBytes = totalBytes - availableBytes
            // Pill giờ tự vẽ CẢ chữ lẫn 2 màu tỉ lệ trong 1 view duy nhất — không còn TextView
            // (tvStorageDetail) riêng đè lên nữa, tránh lỗi đo layout vòng lặp trước đó khiến
            // pill tràn full-width che kín màn hình.
            binding.storagePillInternalBg.setUsage(
                usedBytes, totalBytes,
                getString(R.string.home_storage_detail, formatBytes(usedBytes), formatBytes(totalBytes))
            )
        } catch (e: Exception) {
            binding.storagePillInternalBg.setUsage(0, 0, "")
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val safeGroup = digitGroups.coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, safeGroup.toDouble())
        return DecimalFormat("#,##0.#").format(value) + " " + units[safeGroup]
    }

    /** Hiện hàng "Thẻ nhớ SD" chỉ khi thiết bị thực sự có gắn thẻ SD tháo rời được. */
    private fun setupSdCardRow() {
        val sdPath = com.myfile.ui.util.SdCardUtils.findSdCardPath(this)
        if (sdPath != null) {
            binding.rowSdcard.visibility = android.view.View.VISIBLE
            binding.dividerSdcard.visibility = android.view.View.VISIBLE
            binding.rowSdcard.setOnClickListener { openCategoryBrowser(CategoryType.SDCARD) }
            try {
                val stat = StatFs(sdPath)
                val totalBytes = stat.totalBytes
                val usedBytes = totalBytes - stat.availableBytes
                binding.storagePillSdBg.setUsage(
                    usedBytes, totalBytes,
                    getString(R.string.home_storage_detail, formatBytes(usedBytes), formatBytes(totalBytes))
                )
                binding.storagePillSdBg.visibility = android.view.View.VISIBLE
            } catch (e: Exception) {
                binding.storagePillSdBg.visibility = android.view.View.GONE
            }
        } else {
            binding.rowSdcard.visibility = android.view.View.GONE
            binding.dividerSdcard.visibility = android.view.View.GONE
        }
    }

    private fun setupClickRows() {
        binding.rowNetworkStorage.setOnClickListener {
            ActivityTransitions.startForward(this, Intent(this, MainActivity::class.java))
        }
        // FAB kết nối nhanh: cùng đích đến Lưu trữ mạng, nhưng nằm trong tầm ngón cái khi thao tác 1 tay
        binding.fabQuickConnect.setOnClickListener {
            ActivityTransitions.startForward(this, Intent(this, MainActivity::class.java))
        }
        binding.rowInternalStorage.setOnClickListener {
            openCategoryBrowser(CategoryType.INTERNAL)
        }
        setupSdCardRow()
        binding.rowRecent.setOnClickListener {
            openCategoryBrowser(CategoryType.RECENT)
        }
        binding.rowBookmarks.setOnClickListener {
            openCategoryBrowser(CategoryType.BOOKMARKS)
        }
        binding.rowTrash.setOnClickListener {
            ActivityTransitions.startForward(this, Intent(this, TrashActivity::class.java))
        }
        binding.rowStorageManager.setOnClickListener {
            ActivityTransitions.startForward(this, Intent(this, StorageManagerActivity::class.java))
        }
        binding.btnSearch.setOnClickListener {
            ActivityTransitions.startForward(this, Intent(this, SearchActivity::class.java))
        }
        binding.btnMore.setOnClickListener { showHomeMenu(it) }
    }

    /** Menu 3 chấm ở Home: Thông tin bộ nhớ (giống Samsung Storage Manager), Cài đặt, Giới thiệu. */
    private fun showHomeMenu(anchor: android.view.View) {
        val popup = android.widget.PopupMenu(
            android.view.ContextThemeWrapper(this, R.style.ThemeOverlay_App_PopupMenu),
            anchor
        )
        popup.menu.add(getString(R.string.menu_code_editor_home))
        popup.menu.add(getString(R.string.menu_html_viewer_home))
        popup.menu.add(getString(R.string.menu_settings))
        popup.menu.add(getString(R.string.menu_about))
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                getString(R.string.menu_code_editor_home) ->
                    pickCodeFileLauncher.launch(arrayOf("text/*", "application/json", "application/xml", "application/javascript", "text/html"))
                getString(R.string.menu_html_viewer_home) ->
                    pickHtmlFileLauncher.launch(arrayOf("text/html"))
                getString(R.string.menu_settings) ->
                    ActivityTransitions.startForward(this, Intent(this, MainActivity::class.java))
                getString(R.string.menu_about) -> showAboutDialog()
            }
            true
        }
        popup.show()
    }

    /**
     * SAF (Storage Access Framework) trả về content:// chứ không phải đường dẫn file thật,
     * và CodeEditorActivity/HtmlViewerActivity cần 1 File thật để đọc/ghi trực tiếp — nên
     * copy nội dung sang thư mục nội bộ của app trước khi mở.
     */
    private fun openPickedFile(uri: android.net.Uri, forEditor: Boolean) {
        val name = queryDisplayName(uri) ?: "file_${System.currentTimeMillis()}"
        val target = java.io.File(filesDir, name)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            com.myfile.ui.util.LogBus.error("Không thể mở file đã chọn: $name", source = "APP", throwable = e)
            return
        }
        val intent = if (forEditor) {
            Intent(this, CodeEditorActivity::class.java).putExtra(CodeEditorActivity.EXTRA_FILE_PATH, target.absolutePath)
        } else {
            Intent(this, HtmlViewerActivity::class.java).putExtra(HtmlViewerActivity.EXTRA_FILE_PATH, target.absolutePath)
        }
        ActivityTransitions.startForward(this, intent)
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }

    private fun showAboutDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(getString(R.string.app_version_info))
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), REQ_PERMISSIONS)
        }

        // MANAGE_EXTERNAL_STORAGE (Android 11+) cần xin qua Settings riêng, không qua runtime permission thường.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = android.net.Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }
    }

    companion object {
        private const val REQ_PERMISSIONS = 200
    }
}
