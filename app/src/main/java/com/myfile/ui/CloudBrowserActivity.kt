package com.myfile.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.myfile.ui.R
import com.myfile.ui.cloud.CloudFileService
import com.myfile.ui.cloud.CloudServiceFactory
import com.myfile.ui.databinding.ActivityFileBrowserBinding
import com.myfile.ui.model.CloudProvider
import com.myfile.ui.model.RemoteFile
import com.myfile.ui.adapters.RemoteFileAdapter
import com.myfile.ui.util.ActivityTransitions
import com.myfile.ui.util.ArchiveUtils
import com.myfile.ui.widget.StoragePillView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Duyệt, tải lên/xuống, xóa, tạo thư mục trên tài khoản đám mây đã liên kết
 * (Google Drive / Dropbox / Box). Dùng chung layout với FileBrowserActivity.
 */
class CloudBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileBrowserBinding
    private lateinit var service: CloudFileService
    private lateinit var adapter: RemoteFileAdapter
    private lateinit var provider: CloudProvider

    // Ngăn xếp id thư mục cha để hỗ trợ nút back giữa các cấp thư mục cloud
    private val folderStack = ArrayDeque<Pair<String, String>>() // (folderId, tên hiển thị)
    private var currentFolderId = ""

    // Giống FileBrowserActivity: lưu danh sách gốc, search/sort áp cục bộ không gọi lại API.
    private var rawFiles: List<RemoteFile> = emptyList()
    private var searchQuery: String = ""
    private var sortMode = CloudSortMode.NAME
    private var sortAscending = true

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadFromUri(it) }
    }

    // Màn hình cấp quyền Google Drive (UserRecoverableAuthIOException.intent) — sau khi người
    // dùng bấm "Cho phép" ở đây, tự động thử tải lại thư mục hiện tại thay vì bắt bấm lại nút.
    private val driveConsentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            com.myfile.ui.util.LogBus.success("Đã cấp quyền Google Drive, đang tải lại", source = "CLOUD")
            loadCurrentFolder()
        } else {
            com.myfile.ui.util.LogBus.warning("Người dùng từ chối cấp quyền Google Drive", source = "CLOUD")
            showError(getString(R.string.error_generic))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val providerName = intent.getStringExtra(EXTRA_PROVIDER)
        provider = try {
            CloudProvider.valueOf(providerName ?: "")
        } catch (e: Exception) {
            finish()
            return
        }
        service = CloudServiceFactory.get(this, provider)
        binding.toolbar.title = providerDisplayName(provider)
        binding.toolbar.setNavigationOnClickListener { handleBack() }

        adapter = RemoteFileAdapter(
            onItemClick = { file -> onFileClick(file) },
            onMoreClick = { file, view -> showFileMenu(file, view) }
        )
        binding.rvFiles.layoutManager = LinearLayoutManager(this)
        binding.rvFiles.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadCurrentFolder() }
        binding.btnUpload.setOnClickListener { filePickerLauncher.launch("*/*") }
        binding.btnNewFolder.setOnClickListener { showNewFolderDialog() }

        setupSearchAndSort()

        loadCurrentFolder()
        loadQuota()
    }

    /** Gắn sự kiện cho thanh tìm kiếm (ẩn/hiện, gõ để lọc) và thanh sắp xếp (đổi tiêu chí, đổi chiều) — giống hệt FileBrowserActivity. */
    private fun setupSearchAndSort() {
        binding.btnSearch.setOnClickListener {
            binding.searchBar.visibility = View.VISIBLE
            binding.etSearch.requestFocus()
        }
        binding.btnCloseSearch.setOnClickListener {
            binding.searchBar.visibility = View.GONE
            binding.etSearch.setText("")
            searchQuery = ""
            applyFilterAndSort()
        }
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                binding.btnClearSearch.visibility = if (searchQuery.isEmpty()) View.GONE else View.VISIBLE
                applyFilterAndSort()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        binding.btnClearSearch.setOnClickListener { binding.etSearch.setText("") }

        binding.btnSortBy.setOnClickListener { showSortMenu() }
        binding.btnSortDirection.setOnClickListener {
            sortAscending = !sortAscending
            updateSortDirectionIcon()
            applyFilterAndSort()
        }
        updateSortDirectionIcon()
    }

    private fun showSortMenu() {
        val popup = android.widget.PopupMenu(this, binding.btnSortBy)
        popup.menu.add(0, 0, 0, getString(R.string.sort_by_name))
        popup.menu.add(0, 1, 1, getString(R.string.sort_by_size))
        popup.menu.add(0, 2, 2, getString(R.string.sort_by_date))
        popup.setOnMenuItemClickListener { item ->
            sortMode = when (item.itemId) {
                1 -> CloudSortMode.SIZE
                2 -> CloudSortMode.DATE
                else -> CloudSortMode.NAME
            }
            binding.tvSortBy.text = item.title
            applyFilterAndSort()
            true
        }
        popup.show()
    }

    private fun updateSortDirectionIcon() {
        binding.btnSortDirection.rotation = if (sortAscending) 0f else 180f
    }

    /** Lọc theo [searchQuery] rồi sắp xếp theo [sortMode]/[sortAscending] — thư mục luôn đứng trước file. */
    private fun applyFilterAndSort() {
        var result = rawFiles
        if (searchQuery.isNotBlank()) {
            result = result.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
        val comparator = when (sortMode) {
            CloudSortMode.NAME -> compareBy<RemoteFile> { it.name.lowercase() }
            CloudSortMode.SIZE -> compareBy { it.size }
            CloudSortMode.DATE -> compareBy { it.modifiedTime }
        }
        val directionalComparator = if (sortAscending) comparator else comparator.reversed()
        result = result.sortedWith(compareByDescending<RemoteFile> { it.isDirectory }.then(directionalComparator))
        adapter.submit(result)
        binding.tvEmpty.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Hiển thị pill mini dung lượng đã dùng/tổng của tài khoản cloud (StoragePillView, đồng bộ
     *  kiểu hiển thị "xanh = đã dùng, xám = trống" với pill Bộ nhớ trong/Thẻ nhớ SD ở trang chính). */
    private fun loadQuota() {
        lifecycleScope.launch {
            val result = service.getStorageQuota()
            if (result.isSuccess) {
                val quota = result.getOrNull() ?: return@launch
                if (quota.totalBytes <= 0) return@launch // provider không cung cấp tổng dung lượng (vd: "không giới hạn")
                binding.quotaBarContainer.visibility = View.VISIBLE
                binding.quotaPillBg.setUsage(
                    quota.usedBytes, quota.totalBytes,
                    getString(R.string.home_storage_detail, formatSize(quota.usedBytes), formatSize(quota.totalBytes))
                )
            }
            // Thất bại thì im lặng bỏ qua — không phải provider nào cũng cho phép đọc quota
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val safeGroup = digitGroups.coerceIn(0, units.size - 1)
        return java.text.DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, safeGroup.toDouble())) + " " + units[safeGroup]
    }

    private fun providerDisplayName(p: CloudProvider): String = when (p) {
        CloudProvider.GOOGLE_DRIVE -> getString(R.string.cloud_google_drive)
        CloudProvider.DROPBOX -> getString(R.string.cloud_dropbox)
        CloudProvider.BOX -> getString(R.string.cloud_box)
    }

    private fun loadCurrentFolder() {
        binding.tvCurrentPath.text = if (folderStack.isEmpty()) "/" else "/" + folderStack.joinToString("/") { pair -> pair.second }
        showLoading(true)
        lifecycleScope.launch {
            val result = service.listFiles(currentFolderId)
            binding.swipeRefresh.isRefreshing = false
            showLoading(false)
            if (result.isSuccess) {
                val files = result.getOrDefault(emptyList())
                rawFiles = files
                applyFilterAndSort()
                binding.rvFiles.scheduleLayoutAnimation()
            } else {
                val ex = result.exceptionOrNull()
                // Google Drive scope drive.file luôn cần 1 lần consent riêng ở lệnh gọi API đầu
                // tiên (khác với bước "Đăng nhập bằng Google" ban đầu) — trước đây exception này
                // rơi vào nhánh lỗi chung, hiện "key error" khó hiểu và không có cách nào để
                // người dùng tự cấp quyền tiếp; giờ bung đúng màn hình cấp quyền của Google.
                if (ex is com.myfile.ui.cloud.GoogleDriveService.NeedsUserConsentException) {
                    driveConsentLauncher.launch(ex.intent)
                } else {
                    showError(ex?.message ?: getString(R.string.error_generic))
                }
            }
        }
    }

    /**
     * Chạm 1 lần vào file trên Cloud: trước đây LUÔN tải file về máy (destDir) dù là loại xem
     * được ngay - giờ ưu tiên XEM TRƯỚC trong app cho pdf/docx/xlsx/zip/rar/7z, dùng CHUNG các
     * màn hình viewer đang phục vụ Bộ nhớ trong (PdfViewerActivity/DocxViewerActivity/
     * XlsxViewerActivity/ArchivePreviewActivity) để giao diện xem giống hệt nhau. Các loại khác
     * (ảnh/video/audio/còn lại) vẫn tải về như cũ vì chưa có viewer riêng.
     */
    private fun onFileClick(file: RemoteFile) {
        if (file.isDirectory) {
            folderStack.addLast(currentFolderId to file.name)
            currentFolderId = file.cloudFileId ?: file.path
            clearSearchOnNavigate()
            loadCurrentFolder()
            return
        }
        when {
            file.name.substringAfterLast('.', "").lowercase() == "pdf" ->
                previewCloudFile(file, PdfViewerActivity::class.java, PdfViewerActivity.EXTRA_FILE_PATH)
            file.name.substringAfterLast('.', "").lowercase() == "docx" ->
                previewCloudFile(file, DocxViewerActivity::class.java, DocxViewerActivity.EXTRA_FILE_PATH)
            file.name.substringAfterLast('.', "").lowercase() == "xlsx" ->
                previewCloudFile(file, XlsxViewerActivity::class.java, XlsxViewerActivity.EXTRA_FILE_PATH)
            ArchiveUtils.isArchive(file.name) ->
                previewCloudFile(file, ArchivePreviewActivity::class.java, ArchivePreviewActivity.EXTRA_ARCHIVE_PATH)
            else -> downloadFile(file)
        }
    }

    /** Tải file cloud về 1 bản tạm trong cache rồi mở thẳng bằng viewer trong app - không lưu vào bộ nhớ máy. */
    private fun previewCloudFile(file: RemoteFile, target: Class<*>, extraKey: String) {
        val cloudId = file.cloudFileId ?: file.path
        val tempFile = File(cacheDir, "preview_${System.currentTimeMillis()}_${file.name}")
        showLoading(true)
        lifecycleScope.launch {
            val result = service.downloadFile(cloudId, tempFile)
            showLoading(false)
            if (result.isFailure) {
                showError(result.exceptionOrNull()?.message ?: getString(R.string.error_generic))
                return@launch
            }
            val intent = Intent(this@CloudBrowserActivity, target).apply {
                putExtra(extraKey, tempFile.path)
            }
            startActivity(intent)
            ActivityTransitions.forward(this@CloudBrowserActivity)
        }
    }

    private fun handleBack() {
        if (folderStack.isNotEmpty()) {
            val (parentId, _) = folderStack.removeLast()
            currentFolderId = parentId
            clearSearchOnNavigate()
            loadCurrentFolder()
        } else {
            finish()
            ActivityTransitions.backward(this)
        }
    }

    /** Đổi thư mục thì xoá ô tìm kiếm của thư mục cũ — tránh hiểu lầm đang lọc nhầm thư mục mới. */
    private fun clearSearchOnNavigate() {
        if (searchQuery.isNotEmpty()) {
            binding.etSearch.setText("")
            searchQuery = ""
        }
    }

    private fun showFileMenu(file: RemoteFile, anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(getString(R.string.btn_download))
        if (!file.isDirectory) {
            if (ArchiveUtils.isArchive(file.name)) {
                popup.menu.add(getString(R.string.btn_extract))
            } else {
                popup.menu.add(getString(R.string.btn_compress))
            }
        }
        popup.menu.add(getString(R.string.btn_delete))
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                getString(R.string.btn_download) -> downloadFile(file)
                getString(R.string.btn_extract) -> extractCloudArchive(file)
                getString(R.string.btn_compress) -> compressCloudFile(file)
                getString(R.string.btn_delete) -> confirmDelete(file)
            }
            true
        }
        popup.show()
    }

    /** Tải file .zip/.7z về, giải nén cục bộ, rồi upload từng file/thư mục con lên thư mục hiện tại trên cloud. */
    private fun extractCloudArchive(file: RemoteFile) {
        val cloudId = file.cloudFileId ?: file.path
        showLoading(true)
        lifecycleScope.launch {
            val tempArchive = File(cacheDir, file.name)
            val downloadResult = service.downloadFile(cloudId, tempArchive)
            if (downloadResult.isFailure) {
                showLoading(false)
                showError(getString(R.string.extract_failed))
                return@launch
            }
            val extractDir = File(cacheDir, "extract_${System.currentTimeMillis()}")
            val extractResult = withContext(Dispatchers.IO) {
                when {
                    ArchiveUtils.isZip(file.name) -> ArchiveUtils.unzip(tempArchive, extractDir)
                    ArchiveUtils.isRar(file.name) -> ArchiveUtils.unrar(tempArchive, extractDir)
                    else -> ArchiveUtils.un7z(tempArchive, extractDir)
                }
            }
            if (extractResult.isFailure) {
                showLoading(false)
                tempArchive.delete()
                showError(getString(R.string.extract_failed))
                return@launch
            }
            val ok = uploadDirectoryRecursive(extractDir, currentFolderId)
            showLoading(false)
            tempArchive.delete()
            extractDir.deleteRecursively()
            if (ok) {
                com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.extract_success), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                loadCurrentFolder()
            } else {
                showError(getString(R.string.extract_failed))
            }
        }
    }

    /**
     * Giới hạn độ sâu đệ quy: nếu file .zip/.7z tải về có cấu trúc thư mục lồng quá sâu (dữ liệu
     * lỗi, hoặc archive được tạo ra với ý đồ xấu kiểu "zip bomb" dạng thư mục lồng hàng nghìn
     * cấp), đệ quy không giới hạn trước đây có thể gây StackOverflowError -> crash. 64 cấp là dư
     * sức cho mọi cấu trúc thư mục thực tế của người dùng.
     */
    private suspend fun uploadDirectoryRecursive(localDir: File, parentId: String, depth: Int = 0): Boolean {
        if (depth > 64) return false
        val children = localDir.listFiles() ?: return true
        for (child in children) {
            if (child.isDirectory) {
                val createResult = service.createFolder(child.name, parentId)
                if (createResult.isFailure) return false
                // Cần id thư mục vừa tạo để đệ quy tiếp — với giới hạn interface hiện tại,
                // ta liệt kê lại thư mục cha để tìm id thư mục con vừa tạo theo tên.
                val listing = service.listFiles(parentId).getOrDefault(emptyList())
                val createdFolder = listing.firstOrNull { it.isDirectory && it.name == child.name }
                val childId = createdFolder?.cloudFileId ?: parentId
                if (!uploadDirectoryRecursive(child, childId, depth + 1)) return false
            } else {
                val result = service.uploadFile(child, parentId)
                if (result.isFailure) return false
            }
        }
        return true
    }

    /** Nén 1 file từ cloud: tải về, nén cục bộ, upload file .zip kết quả lên cùng thư mục. */
    private fun compressCloudFile(file: RemoteFile) {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.hint_archive_name)
            setText(file.name.substringBeforeLast('.'))
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.btn_compress))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val zipName = input.text.toString().trim().ifBlank { "archive" }.let { if (it.endsWith(".zip")) it else "$it.zip" }
                val cloudId = file.cloudFileId ?: file.path
                showLoading(true)
                lifecycleScope.launch {
                    val tempLocal = File(cacheDir, file.name)
                    val downloadResult = service.downloadFile(cloudId, tempLocal)
                    if (downloadResult.isFailure) {
                        showLoading(false)
                        showError(getString(R.string.compress_failed))
                        return@launch
                    }
                    val tempZip = File(cacheDir, zipName)
                    val zipResult = withContext(Dispatchers.IO) { ArchiveUtils.zip(listOf(tempLocal), tempZip) }
                    if (zipResult.isFailure) {
                        showLoading(false)
                        tempLocal.delete()
                        showError(getString(R.string.compress_failed))
                        return@launch
                    }
                    val uploadResult = service.uploadFile(tempZip, currentFolderId)
                    showLoading(false)
                    tempLocal.delete()
                    tempZip.delete()
                    if (uploadResult.isSuccess) {
                        com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.compress_success), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                        loadCurrentFolder()
                    } else {
                        showError(getString(R.string.compress_failed))
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun downloadFile(file: RemoteFile) {
        val cloudId = file.cloudFileId ?: file.path
        val destDir = getExternalFilesDir(null) ?: filesDir
        val destFile = File(destDir, file.name)
        showLoading(true)
        lifecycleScope.launch {
            val result = service.downloadFile(cloudId, destFile)
            showLoading(false)
            if (result.isFailure) {
                showError(result.exceptionOrNull()?.message ?: getString(R.string.error_generic))
            }
        }
    }

    /**
     * Bug giống hệt đã sửa ở FileBrowserActivity.uploadFromUri(): đọc file người dùng chọn (từ
     * SAF picker) đồng bộ trên main thread TRƯỚC KHI vào lifecycleScope.launch — với file lớn
     * (video, ảnh RAW...) treo UI hoặc ANR. Bị bỏ sót lúc sửa lần trước vì đây là bản sao riêng
     * cho luồng Cloud (Google Drive/Dropbox/Box), không dùng chung code với FileBrowserActivity.
     */
    private fun uploadFromUri(uri: Uri) {
        val name = queryFileName(uri) ?: "upload_${System.currentTimeMillis()}"
        val tempFile = File(cacheDir, name)
        showLoading(true)
        lifecycleScope.launch {
            val copyOk = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (!copyOk) {
                showLoading(false)
                showError(getString(R.string.error_generic))
                return@launch
            }
            val result = service.uploadFile(tempFile, currentFolderId)
            showLoading(false)
            tempFile.delete()
            if (result.isSuccess) {
                loadCurrentFolder()
            } else {
                showError(result.exceptionOrNull()?.message ?: getString(R.string.error_generic))
            }
        }
    }

    private fun queryFileName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx)
            }
        }
        return null
    }

    private fun confirmDelete(file: RemoteFile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.btn_delete))
            .setMessage(file.name)
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                val cloudId = file.cloudFileId ?: file.path
                lifecycleScope.launch {
                    val result = service.deleteFile(cloudId)
                    if (result.isSuccess) loadCurrentFolder()
                    else showError(result.exceptionOrNull()?.message ?: getString(R.string.error_generic))
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showNewFolderDialog() {
        val input = android.widget.EditText(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.btn_new_folder))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        val result = service.createFolder(name, currentFolderId)
                        if (result.isSuccess) loadCurrentFolder()
                        else showError(result.exceptionOrNull()?.message ?: getString(R.string.error_generic))
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        com.google.android.material.snackbar.Snackbar.make(binding.root, message, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_PROVIDER = "extra_cloud_provider"
    }
}

private enum class CloudSortMode { NAME, SIZE, DATE }
