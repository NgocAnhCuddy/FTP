package com.myfile.ui

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.MimeTypeMap
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.myfile.ui.adapters.LocalFileAdapter
import com.myfile.ui.adapters.LocalGridAdapter
import com.myfile.ui.cloud.CloudServiceFactory
import com.myfile.ui.databinding.ActivityCategoryFilesBinding
import com.myfile.ui.model.CloudProvider
import com.myfile.ui.model.LocalFile
import com.myfile.ui.util.ActivityTransitions
import com.myfile.ui.util.ArchiveUtils
import com.myfile.ui.util.ProgressDialogHelper
import com.myfile.ui.util.TrashManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class CategoryType { IMAGE, VIDEO, AUDIO, DOCUMENT, DOWNLOAD, APK, INTERNAL, SDCARD, RECENT, BOOKMARKS }

/**
 * Duyệt file cục bộ theo thể loại (Ảnh/Video/Audio/Tài liệu/Download/APK) hoặc toàn bộ
 * Bộ nhớ trong với điều hướng thư mục lồng nhau, tương đương hành vi Samsung My Files.
 *
 * - Ảnh/Video: hiển thị dạng LƯỚI Ô VUÔNG với thumbnail (như chế độ xem lưới của My Files).
 * - Bộ nhớ trong: hiển thị dạng danh sách có thể mở thư mục con, nút "lên thư mục cha".
 * - Xóa: chuyển vào Thùng rác thật (TrashManager) thay vì xóa vĩnh viễn ngay lập tức.
 */
class CategoryFilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCategoryFilesBinding
    private lateinit var categoryType: CategoryType
    private lateinit var trashManager: TrashManager

    // Dùng cho danh sách (thư mục/tài liệu/download/apk/audio)
    private var listAdapter: LocalFileAdapter? = null
    // Dùng cho lưới ảnh/video
    private var gridAdapter: LocalGridAdapter? = null
    private var currentFileList: List<LocalFile> = emptyList()

    // Tên file/thư mục đang chờ tạo qua SAF (sau khi người dùng vừa cấp quyền thư mục SD)
    private var pendingSafCreateName: String? = null
    private var pendingSafCreateIsFolder: Boolean = false

    // Danh sách file đang chờ Sao chép/Di chuyển, chờ kết quả từ màn "Chọn thư mục"
    private var pendingCopyMoveFiles: List<LocalFile>? = null
    private var pendingCopyMoveIsMove: Boolean = false
    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val destPath = data?.getStringExtra(FolderPickerActivity.EXTRA_RESULT_DEST_PATH)
        val cloudProviderName = data?.getStringExtra(FolderPickerActivity.EXTRA_RESULT_DEST_CLOUD_PROVIDER)
        if (result.resultCode != RESULT_OK) {
            pendingCopyMoveFiles = null
        } else if (destPath != null) {
            performCopyOrMove(destPath)
        } else if (cloudProviderName != null) {
            val cloudFolderId = data.getStringExtra(FolderPickerActivity.EXTRA_RESULT_DEST_CLOUD_FOLDER_ID) ?: ""
            val provider = try { CloudProvider.valueOf(cloudProviderName) } catch (e: Exception) { null }
            if (provider != null) performUploadToCloud(provider, cloudFolderId) else pendingCopyMoveFiles = null
        } else {
            pendingCopyMoveFiles = null
        }
    }

    // Mở màn hình xem trước file nén; khi người dùng giải nén xong bên đó, load lại danh sách ở đây
    private val archivePreviewLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) loadFiles()
    }

    private val safTreeLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.error_generic), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        com.myfile.ui.util.SdCardUtils.saveTreeUri(this, uri)
        val name = pendingSafCreateName
        if (name != null) {
            val ok = createViaSaf(name, pendingSafCreateIsFolder)
            pendingSafCreateName = null
            if (ok) {
                com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.created_success), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                loadFiles()
            } else {
                com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.error_generic), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private var isGridMode = false
    private var isInternalBrowseMode = false
    private var rootDir: File = Environment.getExternalStorageDirectory()
    private var currentDir: File = Environment.getExternalStorageDirectory()

    // Lọc + sắp xếp danh sách dạng list (Tất cả/Thư mục/Tệp tin, Tên/Ngày/Dung lượng/Loại, tăng/giảm)
    private enum class FilterType { ALL, FOLDERS, FILES }
    private enum class SortField { NAME, DATE, SIZE, TYPE }
    private var filterType = FilterType.ALL
    private var sortField = SortField.NAME
    private var sortAscending = true
    private var searchQuery: String = ""

    private val mediaPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.any { it }) {
            binding.layoutPermissionNeeded.visibility = View.GONE
            loadFiles()
        } else {
            // Người dùng từ chối: hiện rõ thông báo thay vì để lưới trống/đen không lời giải thích
            binding.layoutPermissionNeeded.visibility = View.VISIBLE
            binding.rvFiles.visibility = View.GONE
        }
    }

    /** Quyền runtime cần cho từng loại category — null nếu category không cần quyền media đặc biệt. */
    private fun requiredPermissionsFor(type: CategoryType): Array<String>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return when (type) {
                CategoryType.IMAGE -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                CategoryType.VIDEO -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
                CategoryType.AUDIO -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
                CategoryType.RECENT -> arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                )
                else -> null
            }
        }
        // Android 12 trở xuống dùng chung 1 quyền đọc bộ nhớ ngoài cho mọi loại media/file
        return when (type) {
            CategoryType.IMAGE, CategoryType.VIDEO, CategoryType.AUDIO, CategoryType.DOCUMENT,
            CategoryType.DOWNLOAD, CategoryType.APK, CategoryType.INTERNAL, CategoryType.SDCARD, CategoryType.RECENT ->
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> null
        }
    }

    private fun hasRequiredPermission(): Boolean {
        val required = requiredPermissionsFor(categoryType) ?: return true
        return required.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    /**
     * Kiểm tra quyền trước khi tải danh sách. Nếu thiếu, xin quyền ngay tại màn hình này
     * (không chỉ dựa vào lần xin quyền lúc mở app ở Home — người dùng có thể đã từ chối lúc
     * đó, và trước đây app sẽ âm thầm hiện màn hình trống/đen mà không rõ lý do).
     */
    private fun ensurePermissionThenLoad() {
        val required = requiredPermissionsFor(categoryType)
        if (required == null || hasRequiredPermission()) {
            binding.layoutPermissionNeeded.visibility = View.GONE
            loadFiles()
        } else {
            binding.swipeRefresh.isRefreshing = false
            binding.layoutPermissionNeeded.visibility = View.VISIBLE
            binding.rvFiles.visibility = View.GONE
            binding.btnGrantPermission.setOnClickListener { mediaPermissionLauncher.launch(required) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoryFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        trashManager = TrashManager.getInstance(this)

        // Thanh hành động chọn nhiều (selection_bar) nằm cố định sát đáy màn hình — thêm padding
        // ĐỘNG bằng đúng chiều cao gesture bar/navigation bar thật của thiết bị, tránh bị OneUI
        // (Samsung) hoặc HyperOS (Xiaomi) đè/che mất phần nút cuối khi dùng điều hướng cử chỉ.
        com.myfile.ui.util.WindowInsetsUtils.applyBottomInsetPadding(binding.selectionBar)

        categoryType = CategoryType.valueOf(
            intent.getStringExtra(EXTRA_CATEGORY) ?: CategoryType.DOWNLOAD.name
        )
        isGridMode = categoryType == CategoryType.IMAGE || categoryType == CategoryType.VIDEO
        isInternalBrowseMode = categoryType == CategoryType.INTERNAL || categoryType == CategoryType.SDCARD
        if (categoryType == CategoryType.BOOKMARKS) {
            binding.tvEmpty.text = getString(R.string.empty_bookmarks)
        }

        if (categoryType == CategoryType.SDCARD) {
            val sdPath = com.myfile.ui.util.SdCardUtils.findSdCardPath(this)
            if (sdPath == null || !File(sdPath).exists()) {
                com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.sdcard_not_found), com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
                finish()
                return
            }
            rootDir = File(sdPath)
            currentDir = rootDir
        }

        // Khởi chạy từ lối tắt "Thêm vào Màn hình chờ" đã ghim: mở thẳng tới thư mục đã lưu
        // nếu thư mục vẫn còn tồn tại (có thể đã bị xóa/di chuyển từ lúc ghim shortcut).
        if (intent.action == ACTION_OPEN_FOLDER_SHORTCUT && isInternalBrowseMode) {
            val shortcutPath = intent.getStringExtra(EXTRA_SHORTCUT_PATH)
            val shortcutDir = shortcutPath?.let { File(it) }
            if (shortcutDir != null && shortcutDir.exists() && shortcutDir.isDirectory) {
                currentDir = shortcutDir
            } else if (shortcutPath != null) {
                com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.error_generic), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
            }
        }

        binding.toolbar.title = titleFor(categoryType)
        binding.toolbar.setNavigationOnClickListener { handleBackPress() }
        onBackPressedDispatcher.addCallback(this) { handleBackPress() }

        setupPathBar()
        setupViewToggle()
        setupAdapters()
        setupSelectionBar()
        setupCreateFab()
        setupMoreOptionsMenu()
        setupFilterSortBar()
        setupSearchBar()

        binding.swipeRefresh.setOnRefreshListener { ensurePermissionThenLoad() }
        ensurePermissionThenLoad()
    }

    override fun onResume() {
        super.onResume()
        // Người dùng có thể vừa cấp quyền qua Cài đặt hệ thống rồi quay lại màn này
        if (::categoryType.isInitialized && hasRequiredPermission() && binding.layoutPermissionNeeded.visibility == View.VISIBLE) {
            ensurePermissionThenLoad()
        }
    }

    // ---------- Tạo file/thư mục mới (chỉ khi duyệt Bộ nhớ trong) ----------

    private fun setupCreateFab() {
        if (!isInternalBrowseMode) {
            binding.fabCreate.visibility = View.GONE
            return
        }
        binding.fabCreate.visibility = View.VISIBLE
        binding.fabCreate.setOnClickListener { showCreateMenu() }
    }

    /**
     * Menu 3 chấm ở toolbar: Chọn / Xem / Tạo thư mục / (ngăn cách) / Thùng rác / Cài đặt,
     * giống overflow menu của Samsung My Files trong ảnh mẫu người dùng gửi.
     */
    private fun setupMoreOptionsMenu() {
        binding.btnMoreOptions.setOnClickListener { showMoreOptionsMenu() }
    }

    private fun showMoreOptionsMenu() {
        val popup = android.widget.PopupMenu(this, binding.btnMoreOptions)
        popup.menu.add(0, 1, 0, getString(R.string.menu_select))
        popup.menu.add(0, 2, 1, getString(R.string.menu_view))
        if (isInternalBrowseMode) {
            popup.menu.add(0, 3, 2, getString(R.string.menu_create_folder))
        }
        popup.menu.add(0, 4, 3, getString(R.string.menu_trash))
        popup.menu.add(0, 5, 4, getString(R.string.tab_settings))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> enterSelectionMode()
                2 -> showViewOptionsMenu()
                3 -> showCreateDialog(isFolder = true, customName = false)
                4 -> startActivity(Intent(this, TrashActivity::class.java))
                5 -> openAppSettings()
            }
            true
        }
        popup.show()
    }

    /** Bật chế độ chọn nhiều mục ngay từ menu, không cần nhấn giữ 1 mục trước. */
    private fun enterSelectionMode() {
        if (isGridMode) gridAdapter?.enterSelectionModeEmpty() else listAdapter?.enterSelectionModeEmpty()
        updateSelectionBarVisibility()
    }

    /** "Xem": chuyển nhanh giữa dạng lưới/danh sách — chỉ áp dụng cho Ảnh/Video (nơi có 2 chế độ). */
    private fun showViewOptionsMenu() {
        if (categoryType != CategoryType.IMAGE && categoryType != CategoryType.VIDEO) {
            com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.view_list), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
            return
        }
        val popup = android.widget.PopupMenu(this, binding.btnMoreOptions)
        popup.menu.add(getString(R.string.view_grid))
        popup.menu.add(getString(R.string.view_list))
        popup.setOnMenuItemClickListener { item ->
            val wantGrid = item.title == getString(R.string.view_grid)
            if (wantGrid != isGridMode) {
                isGridMode = wantGrid
                updateToggleIcon()
                setupAdapters()
                setupFilterSortBar()
                loadFiles()
            }
            true
        }
        popup.show()
    }

    /** Mở màn hình thông tin ứng dụng của hệ thống — chưa có màn "Cài đặt" riêng cho trình duyệt file. */
    private fun openAppSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.error_generic), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun showCreateMenu() {
        val popup = android.widget.PopupMenu(this, binding.fabCreate)
        popup.menu.add(getString(R.string.create_new_folder))
        popup.menu.add(getString(R.string.create_new_textfile))
        popup.menu.add(getString(R.string.create_new_file))
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                getString(R.string.create_new_folder) -> showCreateDialog(isFolder = true, customName = false)
                getString(R.string.create_new_textfile) -> showCreateDialog(isFolder = false, customName = false)
                getString(R.string.create_new_file) -> showCreateDialog(isFolder = false, customName = true)
            }
            true
        }
        popup.show()
    }

    /**
     * Tạo thư mục mới, file .txt nhanh, hoặc file với tên/đuôi bất kỳ do người dùng tự nhập
     * (customName = true): ví dụ "ghichu.md", "data.json", "script.py" — không ép về .txt.
     */
    private fun showCreateDialog(isFolder: Boolean, customName: Boolean) {
        val input = android.widget.EditText(this).apply {
            hint = getString(if (customName) R.string.hint_file_name_custom else R.string.hint_file_name)
            setPadding(48, 32, 48, 32)
        }
        val title = when {
            isFolder -> R.string.create_new_folder
            customName -> R.string.create_new_file
            else -> R.string.create_new_textfile
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(title))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                val finalName = when {
                    isFolder -> name
                    customName -> name // tôn trọng đúng đuôi người dùng gõ, kể cả không có đuôi
                    else -> if (name.endsWith(".txt")) name else "$name.txt"
                }
                val target = File(currentDir, finalName)
                if (target.exists()) {
                    com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.error_file_exists), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                var success = try {
                    if (isFolder) target.mkdirs() else target.createNewFile()
                } catch (e: Exception) {
                    false
                }
                // Ghi trực tiếp qua java.io.File có thể bị hệ thống chặn trên thẻ SD thật
                // (ngoài thư mục riêng của app) từ Android 10+. Nếu vậy, thử lại qua SAF
                // bằng quyền thư mục gốc SD người dùng đã cấp trước đó (nếu có).
                if (!success && categoryType == CategoryType.SDCARD) {
                    success = createViaSaf(finalName, isFolder)
                }
                if (success) {
                    com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.created_success), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                    loadFiles()
                } else if (categoryType == CategoryType.SDCARD && !com.myfile.ui.util.SdCardUtils.hasSavedTreeUri(this)) {
                    pendingSafCreateName = finalName
                    pendingSafCreateIsFolder = isFolder
                    promptSafPermissionThenRetry()
                } else {
                    com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.error_generic), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** Tạo file/thư mục qua DocumentFile bằng quyền SAF đã lưu, tìm đúng thư mục con hiện tại theo tên đường dẫn tương đối. */
    private fun createViaSaf(name: String, isFolder: Boolean): Boolean {
        val rootDoc = com.myfile.ui.util.SdCardUtils.getRootDocumentFile(this) ?: return false
        val relPath = currentDir.absolutePath.removePrefix(rootDir.absolutePath).trim('/')
        var target = rootDoc
        if (relPath.isNotEmpty()) {
            for (segment in relPath.split('/')) {
                target = target.findFile(segment) ?: return false
            }
        }
        return try {
            if (isFolder) {
                target.createDirectory(name) != null
            } else {
                val mime = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                    ?: "application/octet-stream"
                target.createFile(mime, name) != null
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Chưa có quyền SAF trên thư mục SD: giải thích ngắn gọn rồi mở picker hệ thống để người dùng cấp quyền 1 lần. */
    private fun promptSafPermissionThenRetry() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sdcard_permission_title))
            .setMessage(getString(R.string.sdcard_permission_message))
            .setPositiveButton(getString(R.string.sdcard_grant_access)) { _, _ ->
                safTreeLauncher.launch(null)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingSafCreateName = null }
            .show()
    }

    private fun titleFor(type: CategoryType): String = when (type) {
        CategoryType.IMAGE -> getString(R.string.home_category_photo)
        CategoryType.VIDEO -> getString(R.string.home_category_video)
        CategoryType.AUDIO -> getString(R.string.home_category_audio)
        CategoryType.DOCUMENT -> getString(R.string.home_category_doc)
        CategoryType.DOWNLOAD -> getString(R.string.home_category_download)
        CategoryType.APK -> getString(R.string.home_category_apk)
        CategoryType.INTERNAL -> getString(R.string.home_internal_storage)
        CategoryType.SDCARD -> getString(R.string.home_sdcard)
        CategoryType.RECENT -> getString(R.string.home_recent_files)
        CategoryType.BOOKMARKS -> getString(R.string.category_bookmarks)
    }

    private fun iconFor(type: CategoryType): Int = when (type) {
        CategoryType.IMAGE -> R.drawable.ic_cat_photo
        CategoryType.VIDEO -> R.drawable.ic_cat_video
        CategoryType.AUDIO -> R.drawable.ic_cat_audio
        CategoryType.DOCUMENT -> R.drawable.ic_cat_doc
        CategoryType.DOWNLOAD -> R.drawable.ic_cat_download
        CategoryType.APK -> R.drawable.ic_cat_apk
        CategoryType.INTERNAL -> R.drawable.ic_file
        CategoryType.SDCARD -> R.drawable.ic_network_storage
        CategoryType.RECENT -> R.drawable.ic_recent
        CategoryType.BOOKMARKS -> R.drawable.ic_bookmark
    }

    // ---------- Thanh lọc "Tất cả" + sắp xếp (ẩn ở chế độ lưới Ảnh/Video) ----------

    private fun setupFilterSortBar() {
        if (isGridMode) {
            binding.filterSortBar.visibility = View.GONE
            return
        }
        binding.filterSortBar.visibility = View.VISIBLE
        updateFilterSortLabels()
        binding.btnFilterType.setOnClickListener { showFilterTypeMenu() }
        binding.btnSortBy.setOnClickListener { showSortFieldMenu() }
        binding.btnSortDirection.setOnClickListener {
            sortAscending = !sortAscending
            updateFilterSortLabels()
            loadFiles()
        }
    }

    private fun updateFilterSortLabels() {
        binding.tvFilterType.text = getString(
            when (filterType) {
                FilterType.ALL -> R.string.filter_all
                FilterType.FOLDERS -> R.string.filter_folders
                FilterType.FILES -> R.string.filter_files
            }
        )
        binding.tvSortBy.text = getString(
            when (sortField) {
                SortField.NAME -> R.string.sort_name
                SortField.DATE -> R.string.sort_date
                SortField.SIZE -> R.string.sort_size
                SortField.TYPE -> R.string.sort_type
            }
        )
        binding.btnSortDirection.rotation = if (sortAscending) 0f else 180f
    }

    private fun showFilterTypeMenu() {
        val popup = android.widget.PopupMenu(this, binding.btnFilterType)
        popup.menu.add(0, 0, 0, getString(R.string.filter_all))
        popup.menu.add(0, 1, 1, getString(R.string.filter_folders))
        popup.menu.add(0, 2, 2, getString(R.string.filter_files))
        popup.setOnMenuItemClickListener { item ->
            filterType = when (item.itemId) {
                1 -> FilterType.FOLDERS
                2 -> FilterType.FILES
                else -> FilterType.ALL
            }
            updateFilterSortLabels()
            loadFiles()
            true
        }
        popup.show()
    }

    private fun showSortFieldMenu() {
        val popup = android.widget.PopupMenu(this, binding.btnSortBy)
        popup.menu.add(0, 0, 0, getString(R.string.sort_name))
        popup.menu.add(0, 1, 1, getString(R.string.sort_date))
        popup.menu.add(0, 2, 2, getString(R.string.sort_size))
        popup.menu.add(0, 3, 3, getString(R.string.sort_type))
        popup.setOnMenuItemClickListener { item ->
            sortField = when (item.itemId) {
                1 -> SortField.DATE
                2 -> SortField.SIZE
                3 -> SortField.TYPE
                else -> SortField.NAME
            }
            updateFilterSortLabels()
            loadFiles()
            true
        }
        popup.show()
    }

    // ---------- Thanh tìm kiếm theo tên (bấm icon kính lúp ở toolbar) ----------

    private var searchDebounceJob: kotlinx.coroutines.Job? = null

    private fun setupSearchBar() {
        binding.btnSearch.setOnClickListener { openSearchBar() }
        binding.btnCloseSearch.setOnClickListener { closeSearchBar() }
        binding.btnClearSearch.setOnClickListener { binding.etSearch.setText("") }
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString().orEmpty()
                binding.btnClearSearch.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                searchDebounceJob?.cancel()
                searchDebounceJob = lifecycleScope.launch {
                    kotlinx.coroutines.delay(250)
                    searchQuery = query
                    loadFiles()
                }
            }
        })
    }

    private fun openSearchBar() {
        binding.searchBar.visibility = View.VISIBLE
        binding.etSearch.requestFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearchBar() {
        binding.etSearch.setText("")
        searchQuery = ""
        binding.searchBar.visibility = View.GONE
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        loadFiles()
    }


    private fun setupPathBar() {
        if (!isInternalBrowseMode) {
            binding.pathBar.visibility = View.GONE
            binding.storageHeader.visibility = View.GONE
            return
        }
        binding.pathBar.visibility = View.VISIBLE
        binding.btnGoUp.setOnClickListener { navigateToParent() }
        updatePathBar()
    }

    private fun updatePathBar() {
        if (!isInternalBrowseMode) return
        val root = rootDir.absolutePath
        val display = currentDir.absolutePath.removePrefix(root).ifEmpty { "/" }
        binding.tvCurrentPath.text = display
        val atRoot = currentDir.absolutePath == root
        binding.btnGoUp.isEnabled = !atRoot
        binding.btnGoUp.alpha = if (atRoot) 0.35f else 1f
        updateStorageHeader(atRoot)
    }

    /** Header dung lượng lớn ("Bộ nhớ trong" + "x GB trống") — chỉ hiện ở thư mục gốc, giống Samsung My Files. */
    private fun updateStorageHeader(atRoot: Boolean) {
        if (!atRoot) {
            binding.storageHeader.visibility = View.GONE
            return
        }
        binding.storageHeader.visibility = View.VISIBLE
        binding.tvStorageTitle.text = titleFor(categoryType)
        try {
            val stat = android.os.StatFs(rootDir.absolutePath)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            binding.tvStorageFree.text = getString(R.string.storage_free_format, formatSizeDetail(freeBytes))
        } catch (e: Exception) {
            binding.tvStorageFree.visibility = View.GONE
        }
    }

    private fun navigateToParent() {
        val root = rootDir.absolutePath
        if (currentDir.absolutePath == root) return
        currentDir = currentDir.parentFile ?: rootDir
        updatePathBar()
        loadFiles()
    }

    private fun handleBackPress() {
        val inSelectionMode = (gridAdapter?.selectionMode ?: false) || (listAdapter?.selectionMode ?: false)
        if (binding.searchBar.visibility == View.VISIBLE) {
            closeSearchBar()
        } else if (inSelectionMode) {
            // Thoát hẳn chế độ chọn dù đang chọn 0 hay nhiều mục — giống nút "Thoát" của Samsung.
            clearAllSelections()
        } else if (isInternalBrowseMode && currentDir.absolutePath != rootDir.absolutePath) {
            navigateToParent()
        } else {
            finish()
            ActivityTransitions.backward(this)
        }
    }

    // ---------- Chuyển đổi chế độ lưới / danh sách (chỉ cho Ảnh/Video) ----------

    private fun setupViewToggle() {
        if (!isGridMode) {
            binding.btnToggleView.visibility = View.GONE
            return
        }
        binding.btnToggleView.visibility = View.VISIBLE
        updateToggleIcon()
        binding.btnToggleView.setOnClickListener {
            isGridMode = !isGridMode
            updateToggleIcon()
            setupAdapters()
            setupFilterSortBar()
            loadFiles()
        }
    }

    private fun updateToggleIcon() {
        binding.btnToggleView.setImageResource(if (isGridMode) R.drawable.ic_view_list else R.drawable.ic_view_grid)
        binding.btnToggleView.contentDescription = getString(if (isGridMode) R.string.view_list else R.string.view_grid)
    }

    // ---------- Thiết lập adapter theo chế độ hiện tại ----------

    private fun setupAdapters() {
        if (isGridMode) {
            val adapter = LocalGridAdapter(
                isVideo = categoryType == CategoryType.VIDEO,
                onItemClick = { openFile(it) },
                onItemLongClick = { toggleGridSelection(it) }
            )
            gridAdapter = adapter
            binding.rvFiles.layoutManager = GridLayoutManager(this, GRID_SPAN_COUNT)
            binding.rvFiles.adapter = adapter
            binding.rvFiles.setHasFixedSize(true)
            // Bộ nhớ đệm view lớn hơn mặc định (2) — cuộn lưới ảnh/video qua lại không phải
            // tái tạo ViewHolder liên tục, đỡ giật khi lướt tới lướt lui trong cùng thư mục.
            binding.rvFiles.setItemViewCacheSize(12)
        } else {
            val adapter = LocalFileAdapter(
                iconRes = iconFor(categoryType),
                onItemClick = { onLocalFileClick(it) },
                onMoreClick = { file, view -> showFileMenu(file, view) },
                onSelectionChanged = { updateSelectionBarVisibility() }
            )
            listAdapter = adapter
            binding.rvFiles.layoutManager = LinearLayoutManager(this)
            binding.rvFiles.adapter = adapter
            binding.rvFiles.setHasFixedSize(true)
        }
    }

    private fun onLocalFileClick(file: LocalFile) {
        if (categoryType == CategoryType.BOOKMARKS && file.isDirectory) {
            // Màn "Thư mục đã ghim" chỉ là 1 DANH SÁCH các đường dẫn — không tự nó hỗ trợ đi
            // sâu vào cấu trúc thư mục con (isInternalBrowseMode luôn false ở category này).
            // Mở 1 Activity MỚI ở chế độ duyệt Bộ nhớ trong thật (INTERNAL), khởi đầu đúng tại
            // thư mục đã ghim — tái dùng nguyên vẹn toàn bộ hạ tầng duyệt cây thư mục đã có,
            // đúng cơ chế đang dùng cho lối tắt "Thêm vào Màn hình chờ" (ACTION_OPEN_FOLDER_
            // SHORTCUT + EXTRA_SHORTCUT_PATH), thay vì viết logic điều hướng riêng trùng lặp.
            val intent = Intent(this, CategoryFilesActivity::class.java).apply {
                action = ACTION_OPEN_FOLDER_SHORTCUT
                putExtra(EXTRA_CATEGORY, CategoryType.INTERNAL.name)
                putExtra(EXTRA_SHORTCUT_PATH, file.path)
            }
            startActivity(intent)
            return
        }
        if (file.isDirectory) {
            currentDir = File(file.path)
            updatePathBar()
            loadFiles()
        } else {
            openFile(file)
        }
    }

    // ---------- Chọn nhiều mục (chế độ lưới Ảnh/Video và chế độ danh sách) ----------

    private fun setupSelectionBar() {
        binding.btnShareSelected.setOnClickListener { shareSelected() }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }
        binding.btnCompressSelected.setOnClickListener { compressSelected() }
        binding.btnBatchRenameSelected.setOnClickListener { showBatchRenameDialog() }
        binding.btnCopySelected.setOnClickListener { startCopyOrMove(getSelectedFiles(), isMove = false) }
        binding.btnMoveSelected.setOnClickListener { startCopyOrMove(getSelectedFiles(), isMove = true) }
        binding.btnBookmarkSelected.setOnClickListener { bookmarkSelected() }
        binding.btnExitSelection.setOnClickListener { clearAllSelections() }
    }

    /** Lấy danh sách mục đang được chọn, bất kể đang ở chế độ lưới hay danh sách. */
    private fun getSelectedFiles(): List<LocalFile> =
        gridAdapter?.getSelectedItems() ?: listAdapter?.getSelectedItems() ?: emptyList()

    private fun clearAllSelections() {
        gridAdapter?.exitSelectionMode()
        listAdapter?.exitSelectionMode()
        updateSelectionBarVisibility()
    }

    /**
     * Hiện/ẩn thanh nút Nén/Chia sẻ/Xóa dưới cùng theo đúng hành vi Samsung My Files:
     * thanh vẫn hiện khi đang ở chế độ chọn dù chưa chọn mục nào (count = 0) — chỉ MỜ các
     * nút hành động lại (disable) thay vì ẩn hẳn thanh, người dùng chỉ thoát bằng nút "Thoát".
     */
    private fun updateSelectionBarVisibility() {
        val inSelectionMode = (gridAdapter?.selectionMode ?: false) || (listAdapter?.selectionMode ?: false)
        val count = gridAdapter?.selectedCount() ?: listAdapter?.selectedCount() ?: 0
        binding.selectionBar.visibility = if (inSelectionMode) View.VISIBLE else View.GONE
        val hasSelection = count > 0
        binding.btnCompressSelected.isEnabled = hasSelection
        binding.btnBatchRenameSelected.isEnabled = hasSelection
        binding.btnShareSelected.isEnabled = hasSelection
        binding.btnDeleteSelected.isEnabled = hasSelection
        binding.btnCopySelected.isEnabled = hasSelection
        binding.btnMoveSelected.isEnabled = hasSelection
        binding.btnCompressSelected.alpha = if (hasSelection) 1f else 0.4f
        binding.btnBatchRenameSelected.alpha = if (hasSelection) 1f else 0.4f
        binding.btnShareSelected.alpha = if (hasSelection) 1f else 0.4f
        binding.btnDeleteSelected.alpha = if (hasSelection) 1f else 0.4f
        binding.btnCopySelected.alpha = if (hasSelection) 1f else 0.4f
        binding.btnMoveSelected.alpha = if (hasSelection) 1f else 0.4f

        // Nút Ghim chỉ có ý nghĩa với THƯ MỤC (không ghim được 1 file lẻ) — enable riêng theo
        // điều kiện có ít nhất 1 thư mục trong lựa chọn, khác các nút còn lại chỉ cần hasSelection.
        // Đồng thời đổi nhãn "Ghim thư mục" <-> "Bỏ ghim" tùy trạng thái của các mục đang chọn,
        // giống hệt cách menu 3 chấm của 1 mục đơn lẻ đã làm.
        val selectedDirs = if (hasSelection) getSelectedFiles().filter { it.isDirectory && !it.path.startsWith("content://") } else emptyList()
        val hasBookmarkableSelection = selectedDirs.isNotEmpty()
        binding.btnBookmarkSelected.isEnabled = hasBookmarkableSelection
        binding.btnBookmarkSelected.alpha = if (hasBookmarkableSelection) 1f else 0.4f
        if (hasBookmarkableSelection) {
            val prefs = com.myfile.ui.util.SecurePrefs.getInstance(this)
            val allBookmarked = selectedDirs.all { prefs.isBookmarked(it.path) }
            binding.btnBookmarkSelected.text = getString(if (allBookmarked) R.string.btn_remove_bookmark else R.string.btn_add_bookmark)
        } else {
            binding.btnBookmarkSelected.text = getString(R.string.btn_add_bookmark)
        }

        // Tiêu đề + nút back trên toolbar cũng đổi theo, giống Samsung My Files:
        // "Đã chọn N" khi có mục chọn, "Chọn mục" khi mode bật nhưng chưa chọn gì,
        // và ngoài selection mode thì quay lại tiêu đề danh mục bình thường.
        if (inSelectionMode) {
            binding.toolbar.title = if (hasSelection) {
                getString(R.string.selected_count, count)
            } else {
                getString(R.string.menu_select_items)
            }
            binding.toolbar.navigationIcon = null
            binding.btnMoreOptions.visibility = View.GONE
            binding.btnSearch.visibility = View.GONE
            binding.btnExitSelection.visibility = View.VISIBLE
        } else {
            binding.toolbar.title = titleFor(categoryType)
            binding.toolbar.setNavigationIcon(R.drawable.ic_back)
            binding.btnMoreOptions.visibility = View.VISIBLE
            binding.btnSearch.visibility = View.VISIBLE
            binding.btnExitSelection.visibility = View.GONE
        }
    }

    private fun compressSelected() {
        val files = getSelectedFiles()
        if (files.isEmpty()) return
        compressFiles(files)
        clearAllSelections()
    }

    private fun toggleGridSelection(file: LocalFile) {
        val adapter = gridAdapter ?: return
        if (!adapter.selectionMode) adapter.enterSelectionModeEmpty()
        adapter.toggleSelection(file)
        updateSelectionBarVisibility()
    }

    private fun shareSelected() {
        val files = getSelectedFiles()
        if (files.isEmpty()) return
        try {
            val uris = ArrayList<Uri>()
            for (f in files) {
                val uri = if (f.path.startsWith("content://")) Uri.parse(f.path)
                    else FileProvider.getUriForFile(this, "$packageName.fileprovider", File(f.path))
                uris.add(uri)
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, null))
        } catch (e: Exception) {
            com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.error_generic), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
        }
    }

    /** Xóa nhiều mục cùng lúc — chạy trên Dispatchers.IO, xem giải thích chi tiết ở confirmDelete(). */
    private fun confirmDeleteSelected() {
        val files = getSelectedFiles()
        if (files.isEmpty()) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.btn_delete))
            .setMessage(getString(R.string.items_count, files.size))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        for (f in files) {
                            if (!f.path.startsWith("content://")) trashManager.moveToTrash(File(f.path))
                        }
                    }
                    clearAllSelections()
                    com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.moved_to_trash), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                    loadFiles()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ---------- Tải danh sách file ----------

    private fun loadFiles() {
        showLoading(true)
        lifecycleScope.launch {
            val rawFiles = withContext(Dispatchers.IO) {
                when {
                    isInternalBrowseMode -> listDirectoryWithFolders(currentDir)
                    categoryType == CategoryType.IMAGE -> queryMediaStore(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                    categoryType == CategoryType.VIDEO -> queryMediaStore(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    categoryType == CategoryType.AUDIO -> queryMediaStore(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                    categoryType == CategoryType.DOWNLOAD -> listDirectory(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
                    categoryType == CategoryType.DOCUMENT -> listDirectory(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
                        .ifEmpty { listDocumentsAcrossStorage() }
                    categoryType == CategoryType.APK -> listApkFiles()
                    categoryType == CategoryType.RECENT -> listRecentFiles()
                    categoryType == CategoryType.BOOKMARKS -> listBookmarkedFolders()
                    else -> emptyList()
                }
            }
            val files = if (isGridMode) {
                if (searchQuery.isBlank()) rawFiles else rawFiles.filter { it.name.contains(searchQuery, ignoreCase = true) }
            } else {
                withContext(Dispatchers.Default) { applyFilterAndSort(rawFiles) }
            }
            showLoading(false)
            binding.swipeRefresh.isRefreshing = false
            binding.rvFiles.visibility = View.VISIBLE
            currentFileList = files
            if (isGridMode) gridAdapter?.submit(files) else listAdapter?.submit(files)
            binding.rvFiles.scheduleLayoutAnimation()
            binding.tvEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** Áp dụng bộ lọc (Tất cả/Thư mục/Tệp tin) và sắp xếp theo lựa chọn hiện tại của thanh filter/sort. */
    private fun applyFilterAndSort(source: List<LocalFile>): List<LocalFile> {
        val searched = if (searchQuery.isBlank()) source
            else source.filter { it.name.contains(searchQuery, ignoreCase = true) }
        val filtered = when (filterType) {
            FilterType.ALL -> searched
            FilterType.FOLDERS -> searched.filter { it.isDirectory }
            FilterType.FILES -> searched.filter { !it.isDirectory }
        }
        val comparator: Comparator<LocalFile> = when (sortField) {
            SortField.NAME -> compareBy { it.name.lowercase() }
            SortField.DATE -> compareBy { it.modifiedTime }
            SortField.SIZE -> compareBy { it.size }
            SortField.TYPE -> compareBy { it.name.substringAfterLast('.', "").lowercase() }
        }
        val sorted = filtered.sortedWith(comparator)
        val directed = if (sortAscending) sorted else sorted.reversed()
        // Thư mục luôn đứng trước file, trừ khi người dùng đang lọc riêng 1 loại.
        return if (filterType == FilterType.ALL) {
            directed.filter { it.isDirectory } + directed.filter { !it.isDirectory }
        } else directed
    }

    /**
     * Quyết định 1 file/thư mục có tên bắt đầu bằng dấu "." có nên bị BỎ QUA khi liệt kê hay
     * không. Luôn bỏ qua các thư mục HỆ THỐNG NỘI BỘ của chính app (thùng rác .MyFileTrash...)
     * bất kể setting "Hiện file ẩn" — đây không phải nội dung người dùng chủ động ẩn, hiện
     * chúng ra sẽ gây nhầm lẫn và có thể khiến người dùng vô tình xoá/sửa dữ liệu nội bộ của
     * app. Với các dotfile CÒN LẠI (do người dùng tự ẩn qua nút "Ẩn"), chỉ bỏ qua khi setting
     * "Hiện file ẩn" đang TẮT.
     */
    private fun shouldSkipDotFile(name: String): Boolean {
        if (name == ".MyFileTrash") return true
        return !com.myfile.ui.util.SecurePrefs.getInstance(this).showHiddenFiles
    }

    /**
     * Liệt kê các thư mục đã ghim. Tự động dọn khỏi danh sách (và lưu lại) những bookmark trỏ
     * tới thư mục KHÔNG CÒN TỒN TẠI (đã bị xoá/di chuyển từ nơi khác, ngoài tầm kiểm soát của
     * app) — tránh danh sách ghim tích luỹ rác theo thời gian và tránh crash khi người dùng bấm
     * vào 1 bookmark trỏ tới đường dẫn không còn tồn tại.
     */
    private fun listBookmarkedFolders(): List<LocalFile> {
        val prefs = com.myfile.ui.util.SecurePrefs.getInstance(this)
        val bookmarks = prefs.getBookmarks()
        val valid = mutableListOf<LocalFile>()
        val stalePaths = mutableListOf<String>()
        for (bm in bookmarks) {
            val dir = File(bm.path)
            if (dir.exists() && dir.isDirectory) {
                val count = dir.listFiles()?.size ?: 0
                valid.add(LocalFile(name = bm.name, path = bm.path, size = 0L, modifiedTime = bm.addedAt, isDirectory = true, itemCount = count))
            } else {
                stalePaths.add(bm.path)
            }
        }
        stalePaths.forEach { prefs.removeBookmark(it) }
        return valid.sortedByDescending { it.modifiedTime }
    }


    private fun listDirectoryWithFolders(dir: File): List<LocalFile> {
        val children = dir.listFiles() ?: return emptyList()
        val folders = mutableListOf<LocalFile>()
        val files = mutableListOf<LocalFile>()
        for (f in children) {
            if (f.name.startsWith(".") && shouldSkipDotFile(f.name)) continue
            if (f.isDirectory) {
                val count = f.listFiles()?.size ?: 0
                folders.add(LocalFile(name = f.name, path = f.absolutePath, size = 0L, modifiedTime = f.lastModified(), isDirectory = true, itemCount = count))
            } else {
                files.add(LocalFile(name = f.name, path = f.absolutePath, size = f.length(), modifiedTime = f.lastModified()))
            }
        }
        folders.sortBy { it.name.lowercase() }
        files.sortByDescending { it.modifiedTime }
        return folders + files
    }

    private fun queryMediaStore(collection: Uri): List<LocalFile> {
        val result = mutableListOf<LocalFile>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.MIME_TYPE
        )
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        try {
            val cursor: Cursor? = contentResolver.query(collection, projection, null, null, sortOrder)
            cursor?.use {
                val idIdx = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIdx = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIdx = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateIdx = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val dataIdx = it.getColumnIndex(MediaStore.MediaColumns.DATA)
                val mimeIdx = it.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                while (it.moveToNext()) {
                    val id = it.getLong(idIdx)
                    val name = it.getString(nameIdx) ?: continue
                    val size = it.getLong(sizeIdx)
                    val modified = it.getLong(dateIdx) * 1000L
                    val path = if (dataIdx >= 0) it.getString(dataIdx) ?: "" else ""
                    val mime = if (mimeIdx >= 0) it.getString(mimeIdx) else null
                    val uri = ContentUris.withAppendedId(collection, id)
                    result.add(
                        LocalFile(
                            name = name,
                            path = path.ifBlank { uri.toString() },
                            size = size,
                            modifiedTime = modified,
                            mimeType = mime
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            // Thiếu quyền runtime — ensurePermissionThenLoad() ở trên xử lý việc xin quyền;
            // ở đây chỉ cần không để app crash, trả về rỗng là đúng.
            android.util.Log.w("CategoryFilesActivity", "Thiếu quyền đọc MediaStore: ${e.message}")
        } catch (e: Exception) {
            android.util.Log.w("CategoryFilesActivity", "Lỗi truy vấn MediaStore: ${e.message}")
        }
        return result
    }

    private fun listDirectory(dir: File?): List<LocalFile> {
        if (dir == null || !dir.exists() || !dir.isDirectory) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isFile }
            .sortedByDescending { it.lastModified() }
            .map { LocalFile(name = it.name, path = it.absolutePath, size = it.length(), modifiedTime = it.lastModified()) }
    }

    private fun listDocumentsAcrossStorage(): List<LocalFile> {
        val exts = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv")
        val root = Environment.getExternalStorageDirectory() ?: return emptyList()
        val result = mutableListOf<LocalFile>()
        fun scan(dir: File, depth: Int) {
            if (depth > 3) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (f.isDirectory) {
                    if (!f.name.startsWith(".") || !shouldSkipDotFile(f.name)) scan(f, depth + 1)
                } else if (f.extension.lowercase() in exts) {
                    result.add(LocalFile(name = f.name, path = f.absolutePath, size = f.length(), modifiedTime = f.lastModified()))
                }
            }
        }
        try {
            scan(root, 0)
        } catch (e: Exception) {
            // bỏ qua thư mục không đọc được
        }
        return result.sortedByDescending { it.modifiedTime }.take(200)
    }

    private fun listApkFiles(): List<LocalFile> {
        val root = Environment.getExternalStorageDirectory() ?: return emptyList()
        val result = mutableListOf<LocalFile>()
        fun scan(dir: File, depth: Int) {
            if (depth > 4) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (f.isDirectory) {
                    if (!f.name.startsWith(".") || !shouldSkipDotFile(f.name)) scan(f, depth + 1)
                } else if (f.extension.equals("apk", ignoreCase = true)) {
                    result.add(LocalFile(name = f.name, path = f.absolutePath, size = f.length(), modifiedTime = f.lastModified()))
                }
            }
        }
        try {
            scan(root, 0)
        } catch (e: Exception) {
            // bỏ qua thư mục không đọc được
        }
        return result.sortedByDescending { it.modifiedTime }.take(200)
    }

    /**
     * "File gần đây": gộp ảnh/video/audio mới nhất từ MediaStore với các file khác (tài liệu,
     * apk, nén, ...) quét trực tiếp trên bộ nhớ, rồi sắp xếp chung theo thời gian sửa đổi mới nhất.
     * Trước đây mục này mở picker hệ thống (ACTION_GET_CONTENT) thay vì hiển thị danh sách thật.
     */
    private fun listRecentFiles(): List<LocalFile> {
        val combined = mutableListOf<LocalFile>()
        combined += queryMediaStore(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        combined += queryMediaStore(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        combined += queryMediaStore(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        combined += scanRecentOtherFiles()
        return combined.distinctBy { it.path }
            .sortedByDescending { it.modifiedTime }
            .take(MAX_RECENT_RESULTS)
    }

    /** Quét file KHÔNG thuộc ảnh/video/audio (đã lấy qua MediaStore ở trên) để tránh trùng lặp. */
    private fun scanRecentOtherFiles(): List<LocalFile> {
        val root = Environment.getExternalStorageDirectory() ?: return emptyList()
        val result = mutableListOf<LocalFile>()
        fun scan(dir: File, depth: Int) {
            if (depth > 4 || result.size >= MAX_RECENT_SCAN) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (result.size >= MAX_RECENT_SCAN) return
                if (f.name.startsWith(".") && shouldSkipDotFile(f.name)) continue
                if (f.isDirectory) {
                    scan(f, depth + 1)
                } else if (f.extension.lowercase() !in MEDIA_EXTS) {
                    result.add(LocalFile(name = f.name, path = f.absolutePath, size = f.length(), modifiedTime = f.lastModified()))
                }
            }
        }
        try {
            scan(root, 0)
        } catch (e: Exception) {
            // bỏ qua thư mục không đọc được
        }
        return result.sortedByDescending { it.modifiedTime }.take(MAX_RECENT_SCAN)
    }

    // Các phần mở rộng được coi là file mã nguồn/text, có thể mở trực tiếp bằng Trình soạn
    // thảo mã trong app (CodeEditorActivity) thay vì phải gửi ra app ngoài. Dùng chung định
    // nghĩa với FileBrowserActivity (file remote) để hành vi đồng nhất trên toàn app.
    private val editableExtensions = setOf(
        "kt", "java", "js", "ts", "jsx", "tsx", "html", "htm", "css", "json", "xml",
        "py", "c", "cpp", "h", "cs", "php", "rb", "go", "rs", "sh", "sql", "yml", "yaml",
        "gradle", "properties", "md", "txt", "log", "ini", "env"
    )

    private fun openFile(file: LocalFile) {
        val mime = file.mimeType ?: MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.name.substringAfterLast('.', "").lowercase())
        val isImage = mime?.startsWith("image/") == true
        val isVideo = mime?.startsWith("video/") == true
        val isAudio = mime?.startsWith("audio/") == true
        val ext = file.name.substringAfterLast('.', "").lowercase()

        if (isImage || isVideo) {
            openInMediaViewer(file, isVideo)
            return
        }
        if (isAudio) {
            openInAudioPlayer(file)
            return
        }
        // File text/mã nguồn cục bộ (.txt, .js, .json, .kt, ...) -> mở thẳng bằng trình soạn
        // thảo trong app để sửa trực tiếp, giống hành vi đã có sẵn với file remote FTP/SFTP.
        // Không cần tải xuống trước như bên FTP vì đây đã là file thật trên đĩa máy; content://
        // (từ SAF/thư mục ngoài) không đọc/ghi trực tiếp được bằng File API nên vẫn rơi về mở
        // app ngoài như cũ.
        if (ext in editableExtensions && !file.path.startsWith("content://")) {
            val intent = Intent(this, CodeEditorActivity::class.java)
                .putExtra(CodeEditorActivity.EXTRA_FILE_PATH, file.path)
            startActivity(intent)
            ActivityTransitions.forward(this)
            return
        }
        com.myfile.ui.util.FileOpenUtils.openDefault(this, binding.root, file.path, file.name, mime)
    }

    /** Mở AudioPlayerActivity với toàn bộ file audio cùng cấp đang hiển thị, làm hàng đợi phát liên tục. */
    private fun openInAudioPlayer(file: LocalFile) {
        val siblings = currentFileList.filter { !it.isDirectory }
        val audioSiblings = siblings.filter { sib ->
            val m = sib.mimeType ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(sib.name.substringAfterLast('.', "").lowercase())
            m?.startsWith("audio/") == true
        }.ifEmpty { listOf(file) }

        val uris = ArrayList<String>()
        val names = ArrayList<String>()
        var startIndex = 0

        audioSiblings.forEachIndexed { index, f ->
            val uriStr = if (f.path.startsWith("content://")) {
                f.path
            } else {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", File(f.path)).toString()
            }
            uris.add(uriStr)
            names.add(f.name)
            if (f.path == file.path) startIndex = index
        }

        val intent = Intent(this, AudioPlayerActivity::class.java).apply {
            putStringArrayListExtra(AudioPlayerActivity.EXTRA_URIS, uris)
            putStringArrayListExtra(AudioPlayerActivity.EXTRA_NAMES, names)
            putExtra(AudioPlayerActivity.EXTRA_START_INDEX, startIndex)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    /** Mở MediaViewerActivity với toàn bộ ảnh/video cùng cấp đang hiển thị, để vuốt qua lại được. */
    private fun openInMediaViewer(file: LocalFile, isVideo: Boolean) {
        val siblings = currentFileList.filter { !it.isDirectory }
        val mediaSiblings = siblings.filter { sib ->
            val m = sib.mimeType ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(sib.name.substringAfterLast('.', "").lowercase())
            m?.startsWith("image/") == true || m?.startsWith("video/") == true
        }.ifEmpty { listOf(file) }

        val uris = ArrayList<String>()
        val names = ArrayList<String>()
        val realPaths = ArrayList<String>()
        val videoFlags = BooleanArray(mediaSiblings.size)
        var startPosition = 0

        mediaSiblings.forEachIndexed { index, f ->
            val uriStr = try {
                if (f.path.startsWith("content://")) {
                    f.path
                } else {
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", File(f.path)).toString()
                }
            } catch (e: IllegalArgumentException) {
                // Đường dẫn lạ không nằm trong file_paths.xml (VD USB OTG mount lạ) — bỏ qua
                // mục này thay vì để crash cả app, giữ đúng nguyên tắc "lỗi 1 ảnh không sập cả màn".
                android.util.Log.w("CategoryFilesActivity", "Không tạo được URI cho ${f.path}: ${e.message}")
                null
            }
            if (uriStr == null) return@forEachIndexed
            uris.add(uriStr)
            names.add(f.name)
            // "Phát lên TV" cần đường dẫn file thật trên máy; content:// không dùng được ở đây.
            realPaths.add(if (f.path.startsWith("content://")) "" else f.path)
            val m = f.mimeType ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(f.name.substringAfterLast('.', "").lowercase())
            videoFlags[uris.size - 1] = m?.startsWith("video/") == true
            if (f.path == file.path) startPosition = uris.size - 1
        }

        if (uris.isEmpty()) {
            com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.error_generic), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, MediaViewerActivity::class.java).apply {
            putStringArrayListExtra(MediaViewerActivity.EXTRA_URIS, uris)
            putStringArrayListExtra(MediaViewerActivity.EXTRA_NAMES, names)
            putStringArrayListExtra(MediaViewerActivity.EXTRA_REAL_PATHS, realPaths)
            putExtra(MediaViewerActivity.EXTRA_IS_VIDEO, videoFlags.copyOf(uris.size))
            putExtra(MediaViewerActivity.EXTRA_START_POSITION, startPosition)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    private fun showFileMenu(file: LocalFile, anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(getString(R.string.btn_share))
        if (!file.isDirectory && ArchiveUtils.isArchive(file.name)) {
            popup.menu.add(getString(R.string.btn_preview_archive))
            popup.menu.add(getString(R.string.btn_extract))
        } else if (!file.path.startsWith("content://")) {
            popup.menu.add(getString(R.string.btn_compress))
        }
        if (!file.isDirectory) {
            popup.menu.add(getString(R.string.btn_open_with))
        }
        if (!file.path.startsWith("content://")) {
            popup.menu.add(getString(R.string.btn_rename))
        }
        if (!file.path.startsWith("content://")) {
            popup.menu.add(getString(R.string.btn_copy_to))
            popup.menu.add(getString(R.string.btn_move_to))
        }
        if (!file.isDirectory) {
            popup.menu.add(getString(R.string.btn_copy_clipboard))
        }
        popup.menu.add(getString(R.string.btn_add_home_screen))
        popup.menu.add(getString(R.string.btn_details))
        if (file.isDirectory && !file.path.startsWith("content://")) {
            val isBookmarked = com.myfile.ui.util.SecurePrefs.getInstance(this).isBookmarked(file.path)
            popup.menu.add(if (isBookmarked) getString(R.string.btn_remove_bookmark) else getString(R.string.btn_add_bookmark))
        }
        if (!file.path.startsWith("content://")) {
            val isHidden = File(file.path).name.startsWith(".")
            popup.menu.add(if (isHidden) getString(R.string.btn_unhide) else getString(R.string.btn_hide))
        }
        popup.menu.add(getString(R.string.btn_delete))
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                getString(R.string.btn_share) -> shareFile(file)
                getString(R.string.btn_preview_archive) -> previewArchive(file)
                getString(R.string.btn_extract) -> extractArchive(file)
                getString(R.string.btn_compress) -> compressFiles(listOf(file))
                getString(R.string.btn_open_with) -> openFileWithChooser(file)
                getString(R.string.btn_rename) -> showRenameDialog(file)
                getString(R.string.btn_copy_to) -> startCopyOrMove(listOf(file), isMove = false)
                getString(R.string.btn_move_to) -> startCopyOrMove(listOf(file), isMove = true)
                getString(R.string.btn_copy_clipboard) -> copyFileToClipboard(file)
                getString(R.string.btn_add_home_screen) -> addToHomeScreen(file)
                getString(R.string.btn_details) -> showFileDetails(file)
                getString(R.string.btn_add_bookmark) -> toggleBookmark(file, add = true)
                getString(R.string.btn_remove_bookmark) -> toggleBookmark(file, add = false)
                getString(R.string.btn_hide) -> toggleHideFile(file, hide = true)
                getString(R.string.btn_unhide) -> toggleHideFile(file, hide = false)
                getString(R.string.btn_delete) -> confirmDelete(file)
            }
            true
        }
        popup.show()
    }

    /**
     * Ẩn/bỏ ẩn file hoặc thư mục — dùng CHÍNH cơ chế Unix chuẩn: thêm/bỏ dấu "." ở đầu tên.
     * Đây là cách gọn nhất, tận dụng đúng cơ chế lọc "startsWith(\".\")" đã có sẵn xuyên suốt
     * toàn app (mọi nơi liệt kê file local đều tự động bỏ qua các mục bắt đầu bằng dấu chấm),
     * không cần xây thêm database/danh sách ẩn riêng dễ mất đồng bộ với hệ thống file thật.
     *
     * Với thư mục ảnh/video, còn tự động thêm/xoá file ".nomedia" bên trong — đây là quy ước
     * của chính Android MediaStore để loại thư mục đó khỏi Thư viện ảnh/Gallery hệ thống, quan
     * trọng hơn cả việc ẩn trong chính app này (người khác cầm máy vẫn thấy ảnh nếu chỉ ẩn
     * trong app mà quên .nomedia).
     */
    private fun toggleHideFile(file: LocalFile, hide: Boolean) {
        // Phòng thủ theo chiều sâu: menu chỉ hiện mục "Ẩn"/"Bỏ ẩn" khi path không phải
        // content:// (SAF), nhưng thêm guard TRỰC TIẾP ở đây để hàm này an toàn ngay cả nếu bị
        // gọi từ nơi khác trong tương lai mà quên kiểm tra điều kiện đó trước — content:// URI
        // không thể truyền thẳng vào File() (không phải đường dẫn hệ thống file thật).
        if (file.path.startsWith("content://")) return
        val src = File(file.path)
        val newName = if (hide) ".${src.name}" else src.name.removePrefix(".")
        val dest = File(src.parentFile, newName)
        if (dest.exists()) {
            com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.error_generic), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
            return
        }
        if (src.renameTo(dest)) {
            if (dest.isDirectory) {
                val nomedia = File(dest, ".nomedia")
                if (hide) {
                    try { nomedia.createNewFile() } catch (e: Exception) { /* không nghiêm trọng nếu thất bại, chỉ mất tác dụng ẩn khỏi Gallery hệ thống */ }
                } else {
                    nomedia.delete()
                }
            }
            val msgRes = if (hide) R.string.msg_file_hidden else R.string.msg_file_unhidden
            com.google.android.material.snackbar.Snackbar.make(binding.root, getString(msgRes), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
            loadFiles()
        } else {
            com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.error_generic), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
        }
    }

    /** Ghim/bỏ ghim 1 thư mục để hiển thị nhanh ở màn Home, dưới các danh mục có sẵn. */
    private fun toggleBookmark(file: LocalFile, add: Boolean) {
        val prefs = com.myfile.ui.util.SecurePrefs.getInstance(this)
        if (add) {
            prefs.addBookmark(file.path, file.name)
        } else {
            prefs.removeBookmark(file.path)
        }
        val msgRes = if (add) R.string.msg_bookmark_added else R.string.msg_bookmark_removed
        com.google.android.material.snackbar.Snackbar.make(binding.root, getString(msgRes), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
    }

    /**
     * Ghim nhiều thư mục cùng lúc từ thanh hành động khi đang ở chế độ chọn nhiều mục.
     * Chỉ ghim những mục THỰC SỰ LÀ THƯ MỤC (bỏ qua file lẻ nếu người dùng lỡ chọn chung) —
     * tính năng "Thư mục đã ghim" (giống Samsung My Files) chỉ dành cho thư mục, không có khái
     * niệm ghim 1 file đơn lẻ. Nếu mọi mục đã chọn đều đã được ghim từ trước thì bỏ ghim hết
     * (cùng logic toggle như menu 3 chấm của 1 mục — bấm lại để đảo trạng thái).
     */
    private fun bookmarkSelected() {
        val files = getSelectedFiles().filter { it.isDirectory && !it.path.startsWith("content://") }
        if (files.isEmpty()) {
            com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.error_generic), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
            return
        }
        val prefs = com.myfile.ui.util.SecurePrefs.getInstance(this)
        val allAlreadyBookmarked = files.all { prefs.isBookmarked(it.path) }
        if (allAlreadyBookmarked) {
            files.forEach { prefs.removeBookmark(it.path) }
            com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.msg_bookmark_removed), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
        } else {
            files.forEach { prefs.addBookmark(it.path, it.name) }
            com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.msg_bookmark_added), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
        }
        clearAllSelections()
    }

    /** Mở màn "Chọn thư mục" (giống Samsung My Files) để Sao chép/Di chuyển [files] tới đó. */
    private fun startCopyOrMove(files: List<LocalFile>, isMove: Boolean) {
        val validFiles = files.filter { !it.path.startsWith("content://") }
        if (validFiles.isEmpty()) return
        pendingCopyMoveFiles = validFiles
        pendingCopyMoveIsMove = isMove
        val intent = Intent(this, FolderPickerActivity::class.java).apply {
            putExtra(FolderPickerActivity.EXTRA_IS_MOVE, isMove)
        }
        folderPickerLauncher.launch(intent)
        ActivityTransitions.forward(this)
    }

    /** Thực hiện sao chép/di chuyển thật sau khi người dùng chọn xong thư mục đích, có dialog tiến trình %/thời gian. */
    private fun performCopyOrMove(destPath: String) {
        val files = pendingCopyMoveFiles ?: return
        val isMove = pendingCopyMoveIsMove
        pendingCopyMoveFiles = null
        val destDir = File(destPath)
        val progress = ProgressDialogHelper(this, if (isMove) R.string.progress_moving else R.string.progress_copying)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val onFile: (String) -> Unit = { progress.setCurrentFile(it) }
                val onProgress: (Long, Long) -> Unit = { done, total -> progress.update(done, total) }
                val sources = files.map { File(it.path) }
                if (isMove) com.myfile.ui.util.FileOpsUtils.move(sources, destDir, onFile, onProgress)
                else com.myfile.ui.util.FileOpsUtils.copy(sources, destDir, onFile, onProgress)
            }
            progress.dismiss()
            val msgRes = if (result.isSuccess) {
                if (isMove) R.string.move_success else R.string.copy_success
            } else {
                if (isMove) R.string.move_failed else R.string.copy_failed
            }
            com.google.android.material.snackbar.Snackbar.make(binding.root, getString(msgRes), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
            if (result.isSuccess) loadFiles()
        }
    }

    /**
     * Sao chép/Di chuyển khi đích là Lưu trữ mạng: không có "copy" ở tầng OS như bộ nhớ trong,
     * nên thao tác thật sự là UPLOAD từng file qua CloudFileService.uploadFile(). Thư mục con
     * được hỗ trợ bằng cách duyệt đệ quy cục bộ: tạo thư mục tương ứng bên cloud trước
     * (createFolder), rồi upload từng file bên trong vào đúng folderId vừa tạo, đệ quy xuống
     * các thư mục con — nếu tạo thư mục cloud thất bại thì bỏ qua toàn bộ nhánh đó (không upload
     * lạc file vào sai chỗ) nhưng vẫn tiếp tục các mục còn lại thay vì dừng hẳn.
     */
    private fun performUploadToCloud(provider: CloudProvider, folderId: String) {
        val files = pendingCopyMoveFiles ?: return
        val isMove = pendingCopyMoveIsMove
        pendingCopyMoveFiles = null
        if (files.isEmpty()) return

        // Đếm trước tổng số file thật sự (đệ quy vào thư mục) để hiển thị tiến trình đúng.
        // LƯU Ý: thư mục rỗng (0 file con) là hợp lệ — vẫn cần tạo thư mục đó trên cloud, không
        // được coi totalFileCount=0 là "thất bại" như trước đây (chặn cả trường hợp chỉ toàn
        // thư mục rỗng, dù về logic không có gì sai để làm).
        fun collectFiles(f: File): List<File> =
            if (f.isDirectory) (f.listFiles() ?: emptyArray()).flatMap { collectFiles(it) }
            else listOf(f)
        val totalFileCount = files.sumOf { collectFiles(File(it.path)).size }

        val service = CloudServiceFactory.get(this, provider)
        val progress = ProgressDialogHelper(this, R.string.progress_uploading)
        lifecycleScope.launch {
            var successCount = 0
            var uploadedIndex = 0
            val deletableRoots = mutableListOf<File>()
            var anyFolderCreateFailed = false

            suspend fun uploadRecursive(src: File, destFolderId: String): Boolean {
                if (src.isDirectory) {
                    val createResult = service.createFolder(src.name, destFolderId)
                    if (createResult.isFailure) {
                        anyFolderCreateFailed = true
                        return false
                    }
                    // Không gọi lại listFiles() để "tìm id vừa tạo" — với Dropbox (path = id),
                    // việc list ngay sau khi tạo có thể trả về chưa kịp cập nhật (mắt lag phía
                    // server) khiến tìm không ra và bị coi là thất bại dù folder đã tạo xong.
                    // Tự ghép path con = path cha + "/" + tên, đúng quy ước path-là-id của
                    // Dropbox/Box; Drive dùng id thật riêng nên override lại hàm này nếu cần sau.
                    val newFolderId = if (destFolderId.isBlank()) "/${src.name}" else "$destFolderId/${src.name}"
                    var allChildrenOk = true
                    (src.listFiles() ?: emptyArray()).forEach { child ->
                        if (!uploadRecursive(child, newFolderId)) allChildrenOk = false
                    }
                    return allChildrenOk
                } else {
                    uploadedIndex++
                    progress.setCurrentFile(src.name)
                    progress.update(uploadedIndex.toLong(), totalFileCount.toLong())
                    val result = service.uploadFile(src, destFolderId)
                    return if (result.isSuccess) {
                        successCount++
                        true
                    } else false
                }
            }

            withContext(Dispatchers.IO) {
                files.forEach { lf ->
                    val src = File(lf.path)
                    val ok = uploadRecursive(src, folderId)
                    if (ok && isMove) deletableRoots.add(src)
                }
                progress.update(totalFileCount.toLong(), totalFileCount.toLong())
            }
            if (isMove) deletableRoots.forEach { root -> deleteRecursiveLocal(root) }
            progress.dismiss()
            val allOk = successCount == totalFileCount && !anyFolderCreateFailed
            val msgRes = if (allOk) R.string.upload_success else R.string.upload_failed
            com.google.android.material.snackbar.Snackbar.make(binding.root, getString(msgRes), com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
            if (isMove && successCount > 0) loadFiles()
        }
    }

    private fun deleteRecursiveLocal(f: File) {
        if (f.isDirectory) (f.listFiles() ?: emptyArray()).forEach { deleteRecursiveLocal(it) }
        f.delete()
    }

    /**
     * "Sao chép vào bộ nhớ tạm": file văn bản nhỏ (txt/log/md/json/xml/csv...) thì copy thẳng
     * NỘI DUNG text để dán được vào ô nhập liệu bất kỳ. Các loại file khác thì copy content:// URI
     * kèm cấp quyền đọc, để dán được dưới dạng file trong các app hỗ trợ paste file (Files, Gmail...).
     */
    /**
     * Đọc nội dung file text để copy vào clipboard cần chạy NGOÀI main thread — file .txt/.log/
     * .json người dùng chọn có thể vài chục MB (log app khác, export dữ liệu...), readText()
     * đồng bộ trên main thread trước đây có thể treo UI vài giây hoặc bị hệ thống coi là ANR
     * (Application Not Responding) và tự kill app, đúng kiểu "crash không ổn định" chỉ xảy ra
     * với vài file cụ thể chứ không phải lúc nào cũng crash.
     */
    private fun copyFileToClipboard(file: LocalFile) {
        val mime = file.mimeType ?: MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.name.substringAfterLast('.', "").lowercase())
        val isPlainText = mime?.startsWith("text/") == true ||
            file.name.substringAfterLast('.', "").lowercase() in TEXT_CLIP_EXTS

        if (isPlainText && !file.path.startsWith("content://")) {
            lifecycleScope.launch {
                val content = try {
                    withContext(Dispatchers.IO) { File(file.path).readText() }
                } catch (e: Exception) {
                    com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.copy_clipboard_failed), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                    return@launch
                }
                try {
                    val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(file.name, content))
                    com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.copied_to_clipboard), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.copy_clipboard_failed), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                }
            }
            return
        }
        try {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            val uri = if (file.path.startsWith("content://")) Uri.parse(file.path)
                else FileProvider.getUriForFile(this, "$packageName.fileprovider", File(file.path))
            clipboard.setPrimaryClip(android.content.ClipData.newUri(contentResolver, file.name, uri))
            com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.copied_to_clipboard), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
        } catch (e: Exception) {
            com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.copy_clipboard_failed), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
        }
    }

    /**
     * "Thêm vào Màn hình chờ": ghim một shortcut mở trực tiếp file/thư mục này thông qua
     * ShortcutManagerCompat (hoạt động cả trên launcher không hỗ trợ pin, tự fallback an toàn).
     */
    private fun addToHomeScreen(file: LocalFile) {
        try {
            if (!androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
                com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.shortcut_not_supported), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                return
            }
            val uri: Uri = if (file.path.startsWith("content://")) Uri.parse(file.path)
                else FileProvider.getUriForFile(this, "$packageName.fileprovider", File(file.path))
            val mime = file.mimeType ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.name.substringAfterLast('.', "").lowercase())

            val shortcutIntent = if (file.isDirectory) {
                Intent(this, CategoryFilesActivity::class.java).apply {
                    action = ACTION_OPEN_FOLDER_SHORTCUT
                    putExtra(EXTRA_CATEGORY, categoryType.name)
                    putExtra(EXTRA_SHORTCUT_PATH, file.path)
                }
            } else {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val shortcutId = "file_shortcut_${file.path.hashCode()}"
            val icon = if (file.isDirectory) {
                androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.drawable.ic_folder)
            } else {
                androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.mipmap.ic_launcher)
            }
            val shortcutInfo = androidx.core.content.pm.ShortcutInfoCompat.Builder(this, shortcutId)
                .setShortLabel(file.name.take(25))
                .setLongLabel(file.name)
                .setIcon(icon)
                .setIntent(shortcutIntent)
                .build()
            androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(this, shortcutInfo, null)
            com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.shortcut_added), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
        } catch (e: Exception) {
            com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.shortcut_add_failed), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
        }
    }

    /** "Mở bằng": luôn hiện danh sách ứng dụng để chọn, khác với mở nhanh (openFile) có thể tự chọn app mặc định. */
    private fun openFileWithChooser(file: LocalFile) {
        com.myfile.ui.util.FileOpenUtils.openWithChooser(this, binding.root, file.path, file.name, file.mimeType)
    }

    /** Đổi tên file/thư mục ngay trong cùng thư mục cha, không di chuyển vị trí. */
    private fun showRenameDialog(file: LocalFile) {
        val input = android.widget.EditText(this).apply {
            setText(file.name)
            setSelection(0, file.name.substringBeforeLast('.', file.name).length)
            setPadding(48, 32, 48, 32)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.btn_rename))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isBlank() || newName == file.name) return@setPositiveButton
                val src = File(file.path)
                val dest = File(src.parentFile, newName)
                if (dest.exists()) {
                    com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.error_file_exists), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (src.renameTo(dest)) {
                    loadFiles()
                } else {
                    com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.error_generic), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Đổi tên nhiều file/thư mục đã chọn cùng lúc theo 1 trong 3 quy tắc (đánh số / tìm-thay /
     * tiền tố-hậu tố) — xem trước đầy đủ kết quả (kèm cảnh báo đỏ nếu có tên trùng nhau) trước
     * khi người dùng bấm Áp dụng thật sự.
     */
    private fun showBatchRenameDialog() {
        val selected = getSelectedFiles()
        if (selected.isEmpty()) return
        val files = selected.map { File(it.path) }

        val dialogBinding = com.myfile.ui.databinding.DialogBatchRenameBinding.inflate(layoutInflater)
        val previewAdapter = com.myfile.ui.adapters.BatchRenamePreviewAdapter()
        dialogBinding.rvPreview.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        dialogBinding.rvPreview.adapter = previewAdapter

        fun currentMode(): com.myfile.ui.util.BatchRenameUtils.Mode = when (dialogBinding.rgMode.checkedRadioButtonId) {
            R.id.rb_mode_find_replace -> com.myfile.ui.util.BatchRenameUtils.Mode.FIND_REPLACE
            R.id.rb_mode_prefix_suffix -> com.myfile.ui.util.BatchRenameUtils.Mode.PREFIX_SUFFIX
            else -> com.myfile.ui.util.BatchRenameUtils.Mode.NUMBERING
        }

        fun buildCurrentPlan() = com.myfile.ui.util.BatchRenameUtils.buildPlan(
            files = files,
            mode = currentMode(),
            baseName = dialogBinding.etBaseName.text?.toString().orEmpty(),
            startNumber = dialogBinding.etStartNumber.text?.toString()?.toIntOrNull() ?: 1,
            find = dialogBinding.etFind.text?.toString().orEmpty(),
            replace = dialogBinding.etReplace.text?.toString().orEmpty(),
            prefix = dialogBinding.etPrefix.text?.toString().orEmpty(),
            suffix = dialogBinding.etSuffix.text?.toString().orEmpty()
        )

        fun refreshPreview() {
            val mode = currentMode()
            dialogBinding.groupNumbering.visibility = if (mode == com.myfile.ui.util.BatchRenameUtils.Mode.NUMBERING) View.VISIBLE else View.GONE
            dialogBinding.groupFindReplace.visibility = if (mode == com.myfile.ui.util.BatchRenameUtils.Mode.FIND_REPLACE) View.VISIBLE else View.GONE
            dialogBinding.groupPrefixSuffix.visibility = if (mode == com.myfile.ui.util.BatchRenameUtils.Mode.PREFIX_SUFFIX) View.VISIBLE else View.GONE
            val plan = buildCurrentPlan()
            val duplicates = com.myfile.ui.util.BatchRenameUtils.findDuplicateNewNames(plan)
            previewAdapter.submit(plan, duplicates)
        }

        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = refreshPreview()
        }
        listOf(
            dialogBinding.etBaseName, dialogBinding.etStartNumber, dialogBinding.etFind,
            dialogBinding.etReplace, dialogBinding.etPrefix, dialogBinding.etSuffix
        ).forEach { it.addTextChangedListener(watcher) }
        dialogBinding.rgMode.setOnCheckedChangeListener { _, _ -> refreshPreview() }

        refreshPreview()

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_batch_rename, files.size))
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.save), null) // gán listener riêng bên dưới để tự kiểm soát việc đóng dialog khi lỗi
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val plan = buildCurrentPlan()
                val changedCount = plan.count { !it.isUnchanged }
                if (changedCount == 0) {
                    com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.batch_rename_no_change), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val result = com.myfile.ui.util.BatchRenameUtils.apply(plan)
                val msg = if (result.failedItems.isEmpty()) {
                    getString(R.string.batch_rename_success, result.successCount)
                } else {
                    getString(R.string.batch_rename_failed_some, result.successCount, result.failedItems.size)
                }
                com.google.android.material.snackbar.Snackbar.make(binding.root, msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
                clearAllSelections()
                loadFiles()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** Hộp thoại "Chi tiết": tên, đường dẫn, dung lượng, thời gian sửa đổi gần nhất. */
    private fun showFileDetails(file: LocalFile) {
        val sizeText = if (file.isDirectory) getString(R.string.items_count, file.itemCount) else formatSizeDetail(file.size)
        val dateText = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(file.modifiedTime))
        val pathText = if (file.path.startsWith("content://")) file.path else file.path
        val message = getString(R.string.file_details_format, file.name, pathText, sizeText, dateText)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.btn_details))
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    private fun formatSizeDetail(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val safeGroup = digitGroups.coerceIn(0, units.size - 1)
        return java.text.DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, safeGroup.toDouble())) + " " + units[safeGroup]
    }

    /**
     * "Xem trước" nội dung file .zip/.7z: liệt kê tên + dung lượng từng mục bên trong mà KHÔNG
     * giải nén ra đĩa, giống tính năng preview của Samsung My Files trong ảnh mẫu người dùng gửi.
     */
    /** Mở màn hình xem trước trực quan (breadcrumb + chọn từng mục) trước khi giải nén file .zip/.7z. */
    private fun previewArchive(file: LocalFile) {
        val intent = Intent(this, ArchivePreviewActivity::class.java).apply {
            putExtra(ArchivePreviewActivity.EXTRA_ARCHIVE_PATH, file.path)
        }
        archivePreviewLauncher.launch(intent)
        ActivityTransitions.forward(this)
    }

    /** Nén 1 hoặc nhiều mục thành file .zip cùng thư mục chứa mục đầu tiên, tuỳ chọn mật khẩu. */
    private fun compressFiles(files: List<LocalFile>) {
        if (files.isEmpty() || files.any { it.path.startsWith("content://") }) return
        val dialogBinding = com.myfile.ui.databinding.DialogCompressArchiveBinding.inflate(layoutInflater)
        dialogBinding.etArchiveName.setText(if (files.size == 1) files.first().name.substringBeforeLast('.') else "archive")
        dialogBinding.cbUsePassword.setOnCheckedChangeListener { _, checked ->
            dialogBinding.tilPassword.visibility = if (checked) View.VISIBLE else View.GONE
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.btn_compress))
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name = dialogBinding.etArchiveName.text?.toString()?.trim().orEmpty().ifBlank { "archive" }
                val password = if (dialogBinding.cbUsePassword.isChecked) {
                    dialogBinding.etArchivePassword.text?.toString().orEmpty().ifBlank { null }
                } else null
                val destDir = File(files.first().path).parentFile ?: currentDir
                val destZip = File(destDir, if (name.endsWith(".zip")) name else "$name.zip")
                val progress = com.myfile.ui.util.ProgressDialogHelper(this@CategoryFilesActivity, R.string.progress_compressing)
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        ArchiveUtils.zip(
                            files.map { File(it.path) },
                            destZip,
                            onFile = { progress.setCurrentFile(it) },
                            onProgress = { done, total -> progress.update(done, total) },
                            password = password
                        )
                    }
                    progress.dismiss()
                    val msgRes = if (result.isSuccess) R.string.compress_success else R.string.compress_failed
                    com.google.android.material.snackbar.Snackbar.make(binding.root, getString(msgRes), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                    if (result.isSuccess) loadFiles()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** Giải nén .zip, .7z hoặc .rar vào 1 thư mục con cùng tên file (không đè cấu trúc thư mục hiện tại). */
    private fun extractArchive(file: LocalFile) {
        val archiveFile = File(file.path)
        val isRar = ArchiveUtils.isRar(file.name)
        val isZip = ArchiveUtils.isZip(file.name)
        if (isRar || isZip) {
            // File .rar/.zip tải từ mạng khá hay có mật khẩu — kiểm tra TRƯỚC khi giải nén để
            // hỏi ngay từ đầu, tránh giải nén dở dang rồi mới báo lỗi (.7z tạm chưa hỗ trợ mật
            // khẩu ở phần "Nén"/"Giải nén" của app này nên không cần kiểm tra ở đây).
            lifecycleScope.launch {
                val hasPassword = withContext(Dispatchers.IO) {
                    if (isRar) ArchiveUtils.isRarPasswordProtected(archiveFile) else ArchiveUtils.isZipPasswordProtected(archiveFile)
                }
                if (hasPassword) {
                    promptArchivePassword(archiveFile, file.name)
                } else {
                    doExtractArchive(archiveFile, file.name, password = null)
                }
            }
            return
        }
        doExtractArchive(archiveFile, file.name, password = null)
    }

    /** Hộp thoại nhập mật khẩu cho file .zip/.rar có bảo vệ, thử giải nén lại nếu sai mật khẩu. */
    private fun promptArchivePassword(archiveFile: File, displayName: String) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.hint_archive_password)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_archive_password_protected))
            .setMessage(getString(R.string.msg_archive_password_protected, displayName))
            .setView(input)
            .setPositiveButton(getString(R.string.extract)) { _, _ ->
                doExtractArchive(archiveFile, displayName, password = input.text?.toString().orEmpty())
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun doExtractArchive(archiveFile: File, displayName: String, password: String?) {
        val destDir = File(archiveFile.parentFile, archiveFile.nameWithoutExtension)
        val progress = ProgressDialogHelper(this, R.string.progress_extracting)
        val isProtectedFormat = ArchiveUtils.isRar(displayName) || ArchiveUtils.isZip(displayName)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val onFile: (String) -> Unit = { progress.setCurrentFile(it) }
                val onProgress: (Long, Long) -> Unit = { done, total -> progress.update(done, total) }
                when {
                    ArchiveUtils.isZip(displayName) -> ArchiveUtils.unzip(archiveFile, destDir, onFile, onProgress, password)
                    ArchiveUtils.isRar(displayName) -> ArchiveUtils.unrar(archiveFile, destDir, password, onFile, onProgress)
                    else -> ArchiveUtils.un7z(archiveFile, destDir, onFile, onProgress)
                }
            }
            progress.dismiss()
            // Mật khẩu sai với junrar/zip4j thường lộ ra dưới dạng exception khi đọc dữ liệu
            // (không phải lỗi rõ ràng "wrong password") — nếu vừa nhập mật khẩu mà vẫn fail,
            // khả năng cao nhất là sai mật khẩu, nên mời nhập lại thay vì chỉ báo lỗi chung chung.
            if (result.isFailure && isProtectedFormat && !password.isNullOrEmpty()) {
                destDir.deleteRecursively()
                com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.error_wrong_archive_password), com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
                promptArchivePassword(archiveFile, displayName)
                return@launch
            }
            val msgRes = if (result.isSuccess) R.string.extract_success else R.string.extract_failed
            com.google.android.material.snackbar.Snackbar.make(binding.root, getString(msgRes), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
            if (result.isSuccess) loadFiles()
        }
    }

    private fun shareFile(file: LocalFile) {
        try {
            val uri = if (file.path.startsWith("content://")) Uri.parse(file.path)
                else FileProvider.getUriForFile(this, "$packageName.fileprovider", File(file.path))
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, null))
        } catch (e: Exception) {
            com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.error_generic), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
        }
    }

    /**
     * Xóa: chuyển vào Thùng rác thật (di chuyển file, không xóa vĩnh viễn ngay), trừ nội dung
     * MediaStore content:// không thể di chuyển trực tiếp.
     *
     * moveToTrash() chạy trên Dispatchers.IO vì với file/thư mục ở THẺ NHỚ SD, renameTo() giữa
     * 2 phân vùng khác nhau (SD -> .MyFileTrash ở bộ nhớ trong) LUÔN thất bại, khiến TrashManager
     * rơi vào nhánh copyRecursively() + deleteRecursively() — thao tác đồng bộ tốn thời gian.
     * Gọi thẳng trên main thread trước đây khiến app treo/ANR khi xóa thư mục/album lớn trên SD.
     */
    private fun confirmDelete(file: LocalFile) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.btn_delete))
            .setMessage(file.name)
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    val success = withContext(Dispatchers.IO) {
                        if (file.path.startsWith("content://")) {
                            try { contentResolver.delete(Uri.parse(file.path), null, null) > 0 } catch (e: Exception) { false }
                        } else {
                            trashManager.moveToTrash(File(file.path))
                        }
                    }
                    if (success) {
                        com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.moved_to_trash), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                        loadFiles()
                    } else {
                        com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.error_generic), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    companion object {
        const val EXTRA_CATEGORY = "extra_category"
        /** Đường dẫn thư mục cần mở tới ngay khi Activity được khởi chạy từ shortcut đã ghim. */
        const val EXTRA_SHORTCUT_PATH = "extra_shortcut_path"
        /** Action riêng để phân biệt Intent khởi chạy từ shortcut Màn hình chờ (mở thẳng 1 thư mục cụ thể). */
        const val ACTION_OPEN_FOLDER_SHORTCUT = "com.myfile.ui.action.OPEN_FOLDER_SHORTCUT"
        private const val GRID_SPAN_COUNT = 4
        private const val MAX_RECENT_RESULTS = 150
        private const val MAX_RECENT_SCAN = 300
        private val MEDIA_EXTS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif",
            "mp4", "mkv", "mov", "avi", "3gp", "webm", "m4v",
            "mp3", "wav", "flac", "aac", "ogg", "m4a", "wma"
        )
        /** Phần mở rộng được coi là text thuần để copy NỘI DUNG vào clipboard thay vì URI file. */
        private val TEXT_CLIP_EXTS = setOf(
            "txt", "log", "md", "json", "xml", "csv", "ini", "conf", "yaml", "yml", "java", "kt", "py", "js", "html", "css"
        )
    }
}
