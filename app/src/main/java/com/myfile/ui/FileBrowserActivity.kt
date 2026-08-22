package com.myfile.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.myfile.ui.R
import com.myfile.ui.client.RemoteClient
import com.myfile.ui.databinding.ActivityFileBrowserBinding
import com.myfile.ui.model.FtpConnectionProfile
import com.myfile.ui.model.RemoteFile
import com.myfile.ui.adapters.RemoteFileAdapter
import com.myfile.ui.util.ActivityTransitions
import com.myfile.ui.util.ArchiveUtils
import com.myfile.ui.util.SecurePrefs
import com.myfile.ui.widget.StoragePillView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Duyệt, tải lên/xuống, xóa, tạo thư mục trên máy chủ FTP đã kết nối. */
class FileBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileBrowserBinding
    private lateinit var client: RemoteClient
    private lateinit var adapter: RemoteFileAdapter
    private lateinit var profile: FtpConnectionProfile

    private var currentPath = "/"

    // Danh sách gốc của thư mục hiện tại (chưa lọc/sắp xếp) — search và sort đều áp trên bản
    // này rồi mới đẩy kết quả vào adapter, tránh phải gọi lại server mỗi khi gõ tìm kiếm.
    private var rawFiles: List<RemoteFile> = emptyList()
    private var searchQuery: String = ""
    private var sortMode = SortMode.NAME
    private var sortAscending = true

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val connId = intent.getStringExtra(EXTRA_CONNECTION_ID)
        val prefs = SecurePrefs.getInstance(this)
        val found = prefs.loadConnections().firstOrNull { it.id == connId }
        if (found == null) {
            finish()
            return
        }
        profile = found
        binding.toolbar.title = profile.name

        binding.toolbar.setNavigationOnClickListener {
            handleBackNavigation()
        }
        binding.btnGoUp.setOnClickListener { navigateToParent() }

        onBackPressedDispatcher.addCallback(this) {
            handleBackNavigation()
        }

        adapter = RemoteFileAdapter(
            onItemClick = { file -> onFileClick(file) },
            onMoreClick = { file, view -> showFileMenu(file, view) }
        )
        binding.rvFiles.layoutManager = LinearLayoutManager(this)
        binding.rvFiles.setHasFixedSize(true)
        binding.rvFiles.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadCurrentFolder() }
        binding.btnUpload.setOnClickListener { filePickerLauncher.launch("*/*") }
        binding.btnNewFolder.setOnClickListener { showCreateMenu() }

        setupSearchAndSort()

        client = RemoteClient.forProfile(profile)
        connectAndLoad()
    }

    /** Gắn sự kiện cho thanh tìm kiếm (ẩn/hiện, gõ để lọc) và thanh sắp xếp (đổi tiêu chí, đổi chiều). */
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
                1 -> SortMode.SIZE
                2 -> SortMode.DATE
                else -> SortMode.NAME
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

    /** Lọc theo [searchQuery] (không phân biệt hoa/thường) rồi sắp xếp theo [sortMode]/[sortAscending] — thư mục luôn đứng trước file, giống Bộ nhớ trong. */
    private fun applyFilterAndSort() {
        var result = rawFiles
        if (searchQuery.isNotBlank()) {
            result = result.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
        val comparator = when (sortMode) {
            SortMode.NAME -> compareBy<RemoteFile> { it.name.lowercase() }
            SortMode.SIZE -> compareBy { it.size }
            SortMode.DATE -> compareBy { it.modifiedTime }
        }
        val directionalComparator = if (sortAscending) comparator else comparator.reversed()
        result = result.sortedWith(compareByDescending<RemoteFile> { it.isDirectory }.then(directionalComparator))
        adapter.submit(result)
        binding.tvEmpty.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun connectAndLoad() {
        showLoading(true)
        lifecycleScope.launch {
            val result = client.connect(profile)
            if (result.isSuccess) {
                loadCurrentFolder()
                loadQuotaEstimate()
            } else {
                showLoading(false)
                showError(result.exceptionOrNull()?.message ?: getString(R.string.connect_failed))
            }
        }
    }

    /** Hiển thị pill mini ước tính dung lượng đã dùng trên server FTP (FTP chuẩn không có tổng
     *  dung lượng thật, nên dùng used*2 làm mẫu số minh hoạ tỉ lệ — chỉ để có hình ảnh trực quan,
     *  không phải số liệu chính xác tuyệt đối). */
    private fun loadQuotaEstimate() {
        lifecycleScope.launch {
            val result = client.estimateUsedSpace()
            if (result.isSuccess) {
                val used = result.getOrDefault(0L)
                val estimatedTotal = (used * 2).coerceAtLeast(1L)
                binding.quotaBarContainer.visibility = View.VISIBLE
                binding.quotaPillBg.setUsage(used, estimatedTotal, getString(R.string.ftp_used_estimate, formatSize(used)))
            }
            // Nếu thất bại thì im lặng bỏ qua — không phải mọi server FTP đều hỗ trợ duyệt đủ quyền để ước tính
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val safeGroup = digitGroups.coerceIn(0, units.size - 1)
        return java.text.DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, safeGroup.toDouble())) + " " + units[safeGroup]
    }

    /** Điều hướng lên thư mục cha của [currentPath], nếu chưa ở gốc "/". */
    private fun navigateToParent() {
        if (currentPath == "/" || currentPath.isEmpty()) return
        val trimmed = currentPath.trimEnd('/')
        val parent = trimmed.substringBeforeLast('/', "")
        currentPath = if (parent.isEmpty()) "/" else parent
        clearSearchOnNavigate()
        showLoading(true)
        loadCurrentFolder()
    }

    /** Nút back của toolbar/hệ thống: nếu đang ở thư mục con thì lên thư mục cha, nếu đã ở gốc thì thoát màn hình. */
    private fun handleBackNavigation() {
        if (currentPath != "/" && currentPath.isNotEmpty()) {
            navigateToParent()
        } else {
            finish()
            ActivityTransitions.backward(this)
        }
    }

    private fun loadCurrentFolder() {
        binding.tvCurrentPath.text = currentPath
        binding.btnGoUp.isEnabled = currentPath != "/" && currentPath.isNotEmpty()
        binding.btnGoUp.alpha = if (binding.btnGoUp.isEnabled) 1f else 0.35f
        lifecycleScope.launch {
            val result = client.listFiles(currentPath)
            binding.swipeRefresh.isRefreshing = false
            showLoading(false)
            if (result.isSuccess) {
                val files = result.getOrDefault(emptyList())
                rawFiles = files
                applyFilterAndSort()
                // Hiệu ứng rơi-xuống chỉ đẹp mắt và nhẹ nhàng với danh sách ngắn/vừa;
                // với thư mục nhiều file, bỏ qua để tránh giật khung hình trên chip yếu.
                if (files.size <= MAX_ITEMS_FOR_LAYOUT_ANIMATION) {
                    binding.rvFiles.scheduleLayoutAnimation()
                }
            } else {
                showError(result.exceptionOrNull()?.message ?: getString(R.string.error_generic))
            }
        }
    }

    // Các phần mở rộng được coi là file mã nguồn/text, có thể mở bằng Trình soạn thảo mã.
    private val editableExtensions = setOf(
        "kt", "java", "js", "ts", "jsx", "tsx", "html", "htm", "css", "json", "xml",
        "py", "c", "cpp", "h", "cs", "php", "rb", "go", "rs", "sh", "sql", "yml", "yaml",
        "gradle", "properties", "md", "txt", "log", "ini", "env"
    )

    private fun onFileClick(file: RemoteFile) {
        if (file.isDirectory) {
            currentPath = file.path
            clearSearchOnNavigate()
            showLoading(true)
            loadCurrentFolder()
        } else {
            downloadFile(file)
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
            val ext = file.name.substringAfterLast('.', "").lowercase()
            if (ext in setOf("html", "htm")) {
                popup.menu.add(getString(R.string.menu_open_html))
            }
            if (ext in editableExtensions) {
                popup.menu.add(getString(R.string.menu_open_in_editor))
            }
            if (ArchiveUtils.isArchive(file.name)) {
                popup.menu.add(getString(R.string.btn_extract))
            } else {
                popup.menu.add(getString(R.string.btn_compress))
            }
        }
        popup.menu.add(getString(R.string.btn_delete))
        popup.menu.add(getString(R.string.btn_rename))
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                getString(R.string.btn_download) -> downloadFile(file)
                getString(R.string.menu_open_html) -> downloadAndOpen(file, openInHtmlViewer = true)
                getString(R.string.menu_open_in_editor) -> downloadAndOpen(file, openInHtmlViewer = false)
                getString(R.string.btn_extract) -> extractRemoteArchive(file)
                getString(R.string.btn_compress) -> compressRemoteFile(file)
                getString(R.string.btn_delete) -> confirmDelete(file)
                getString(R.string.btn_rename) -> showRenameDialog(file)
            }
            true
        }
        popup.show()
    }

    /** Tải file về bộ nhớ trong của app rồi mở bằng HtmlViewerActivity hoặc CodeEditorActivity. */
    private fun downloadAndOpen(file: RemoteFile, openInHtmlViewer: Boolean) {
        val destFile = File(filesDir, file.name)
        showLoading(true)
        lifecycleScope.launch {
            val result = client.downloadFile(file.path, destFile)
            showLoading(false)
            if (result.isFailure) {
                showError(result.exceptionOrNull()?.message ?: getString(R.string.error_generic))
                return@launch
            }
            val intent = if (openInHtmlViewer) {
                Intent(this@FileBrowserActivity, HtmlViewerActivity::class.java)
                    .putExtra(HtmlViewerActivity.EXTRA_FILE_PATH, destFile.absolutePath)
            } else {
                Intent(this@FileBrowserActivity, CodeEditorActivity::class.java)
                    .putExtra(CodeEditorActivity.EXTRA_FILE_PATH, destFile.absolutePath)
            }
            startActivity(intent)
            ActivityTransitions.forward(this@FileBrowserActivity)
        }
    }

    /** Tải file .zip/.7z về bộ nhớ đệm, giải nén cục bộ, rồi upload từng file/thư mục con lên đúng vị trí trên server FTP. */
    private fun extractRemoteArchive(file: RemoteFile) {
        showLoading(true)
        lifecycleScope.launch {
            val tempArchive = File(cacheDir, file.name)
            val downloadResult = client.downloadFile(file.path, tempArchive)
            if (downloadResult.isFailure) {
                showLoading(false)
                showError(downloadResult.exceptionOrNull()?.message ?: getString(R.string.extract_failed))
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
            // Tải toàn bộ nội dung đã giải nén lên cùng thư mục hiện tại trên server
            val ok = uploadDirectoryRecursive(extractDir, currentPath)
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

    private suspend fun uploadDirectoryRecursive(localDir: File, remoteBasePath: String): Boolean {
        val children = localDir.listFiles() ?: return true
        for (child in children) {
            val remotePath = if (remoteBasePath.endsWith("/")) "$remoteBasePath${child.name}" else "$remoteBasePath/${child.name}"
            if (child.isDirectory) {
                client.makeDirectory(remotePath)
                if (!uploadDirectoryRecursive(child, remotePath)) return false
            } else {
                val result = client.uploadFile(child, remotePath)
                if (result.isFailure) return false
            }
        }
        return true
    }

    /** Nén 1 file/thư mục từ server: tải về, nén cục bộ, upload file .zip kết quả lên server. */
    private fun compressRemoteFile(file: RemoteFile) {
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
                showLoading(true)
                lifecycleScope.launch {
                    val tempLocal = File(cacheDir, file.name)
                    val downloadResult = client.downloadFile(file.path, tempLocal)
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
                    val remoteZipPath = if (currentPath.endsWith("/")) "$currentPath$zipName" else "$currentPath/$zipName"
                    val uploadResult = client.uploadFile(tempZip, remoteZipPath)
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
        val destDir = getExternalFilesDir(null) ?: filesDir
        val destFile = File(destDir, file.name)
        showLoading(true)
        lifecycleScope.launch {
            val result = client.downloadFile(file.path, destFile)
            showLoading(false)
            if (result.isFailure) {
                showError(result.exceptionOrNull()?.message ?: getString(R.string.error_generic))
            }
        }
    }

    /**
     * Copy nội dung file người dùng chọn (từ SAF, content://) vào cache trước khi upload.
     * File này có thể là video/apk vài trăm MB — đọc/ghi đồng bộ trên main thread trước đây
     * treo UI hoặc gây ANR tùy kích thước, một dạng "crash không ổn định" chỉ xảy ra với
     * file lớn. Toàn bộ copy + upload giờ chạy trong 1 coroutine trên Dispatchers.IO.
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
            val remotePath = if (currentPath.endsWith("/")) "$currentPath$name" else "$currentPath/$name"
            val result = client.uploadFile(tempFile, remotePath)
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
                lifecycleScope.launch {
                    val result = if (file.isDirectory) client.deleteDirectory(file.path) else client.deleteFile(file.path)
                    if (result.isSuccess) loadCurrentFolder()
                    else showError(result.exceptionOrNull()?.message ?: getString(R.string.error_generic))
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showRenameDialog(file: RemoteFile) {
        val input = android.widget.EditText(this)
        input.setText(file.name)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.btn_rename))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val parentPath = file.path.substringBeforeLast("/", currentPath)
                    val newPath = if (parentPath.endsWith("/")) "$parentPath$newName" else "$parentPath/$newName"
                    lifecycleScope.launch {
                        val result = client.rename(file.path, newPath)
                        if (result.isSuccess) loadCurrentFolder()
                        else showError(result.exceptionOrNull()?.message ?: getString(R.string.error_generic))
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** Menu chọn giữa tạo thư mục mới hoặc file mới với tên/đuôi tùy ý trên máy chủ. */
    private fun showCreateMenu() {
        val popup = android.widget.PopupMenu(this, binding.btnNewFolder)
        popup.menu.add(getString(R.string.create_new_folder))
        popup.menu.add(getString(R.string.create_new_file))
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                getString(R.string.create_new_folder) -> showNewFolderDialog()
                getString(R.string.create_new_file) -> showNewRemoteFileDialog()
            }
            true
        }
        popup.show()
    }

    private fun showNewFolderDialog() {
        val input = android.widget.EditText(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.btn_new_folder))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val newPath = if (currentPath.endsWith("/")) "$currentPath$name" else "$currentPath/$name"
                    lifecycleScope.launch {
                        val result = client.makeDirectory(newPath)
                        if (result.isSuccess) loadCurrentFolder()
                        else showError(result.exceptionOrNull()?.message ?: getString(R.string.error_generic))
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** Tạo file mới trống trên máy chủ với tên do người dùng gõ (bất kỳ đuôi nào, không ép .txt). */
    private fun showNewRemoteFileDialog() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.hint_file_name_custom)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.create_new_file))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val remotePath = if (currentPath.endsWith("/")) "$currentPath$name" else "$currentPath/$name"
                    showLoading(true)
                    lifecycleScope.launch {
                        val emptyLocal = File(cacheDir, name)
                        emptyLocal.createNewFile()
                        val result = client.uploadFile(emptyLocal, remotePath)
                        emptyLocal.delete()
                        showLoading(false)
                        if (result.isSuccess) loadCurrentFolder()
                        else showError(result.exceptionOrNull()?.message ?: getString(R.string.error_generic))
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * lifecycleScope bị hủy NGAY khi onDestroy() bắt đầu chạy (nó gắn theo Lifecycle.Event.DESTROY),
     * nên launch bằng lifecycleScope ở đây trước đây gần như chắc chắn không bao giờ thực thi —
     * disconnect() không chạy, socket FTP/SFTP/SMB bị rò rỉ (không đóng), có thể khiến lần kết
     * nối sau tới cùng server chậm hoặc bị treo nếu server giới hạn số kết nối đồng thời.
     * Dùng 1 CoroutineScope độc lập (không gắn Activity/Fragment) chạy trên Dispatchers.IO,
     * không chờ kết quả — đóng kết nối "cố gắng hết sức" ở nền, không giữ Activity destroy lại.
     */
    override fun onDestroy() {
        super.onDestroy()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try { client.disconnect() } catch (e: Exception) { /* app đang đóng, bỏ qua lỗi đóng kết nối */ }
        }
    }

    private fun showLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        com.google.android.material.snackbar.Snackbar.make(binding.root, message, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_CONNECTION_ID = "extra_connection_id"
        private const val MAX_ITEMS_FOR_LAYOUT_ANIMATION = 60
    }
}

private enum class SortMode { NAME, SIZE, DATE }
