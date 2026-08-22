package com.myfile.ui

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.myfile.ui.adapters.LocalFileAdapter
import com.myfile.ui.adapters.RemoteFileAdapter
import com.myfile.ui.cloud.CloudFileService
import com.myfile.ui.cloud.CloudServiceFactory
import com.myfile.ui.databinding.ActivityFolderPickerBinding
import com.myfile.ui.model.CloudProvider
import com.myfile.ui.model.LocalFile
import com.myfile.ui.model.RemoteFile
import com.myfile.ui.util.ActivityTransitions
import com.myfile.ui.util.SdCardUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Màn "Chọn thư mục" đích cho tính năng Sao chép/Di chuyển, phỏng theo đúng bố cục của
 * Samsung My Files: 3 tab (Bộ nhớ trong / Thẻ nhớ SD / Lưu trữ mạng), điều hướng thư mục con,
 * tạo thư mục mới, và nút "Sao chép/Di chuyển vào đây" ở dưới cùng.
 *
 * Trả kết quả qua RESULT_OK. Nếu đích là bộ nhớ máy (Bộ nhớ trong/Thẻ nhớ SD), trả
 * [EXTRA_RESULT_DEST_PATH] là đường dẫn thư mục. Nếu đích là Lưu trữ mạng, trả thay vào đó
 * [EXTRA_RESULT_DEST_CLOUD_PROVIDER] (tên enum [CloudProvider]) và
 * [EXTRA_RESULT_DEST_CLOUD_FOLDER_ID] (id thư mục trên cloud, rỗng = thư mục gốc) — nơi gọi
 * (CategoryFilesActivity) sẽ upload file thật qua [CloudFileService.uploadFile] thay vì copy
 * cục bộ.
 */
class FolderPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFolderPickerBinding
    private lateinit var adapter: LocalFileAdapter
    private lateinit var cloudAdapter: RemoteFileAdapter

    private enum class Tab { INTERNAL, SDCARD, NETWORK }
    private var currentTab = Tab.INTERNAL
    private var currentDir: File = Environment.getExternalStorageDirectory()
    private var rootDir: File = Environment.getExternalStorageDirectory()
    private var sdRootPath: String? = null
    private var isMove: Boolean = false

    // Trạng thái duyệt cloud: provider đang chọn (null = chưa chọn, đang hiện danh sách provider),
    // service tương ứng, và ngăn xếp (folderId, tên hiển thị) để hỗ trợ nút back giữa các cấp.
    private var cloudProvider: CloudProvider? = null
    private var cloudService: CloudFileService? = null
    private val cloudFolderStack = ArrayDeque<Pair<String, String>>()
    private var cloudCurrentFolderId: String = ""
    private var cloudLoadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFolderPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isMove = intent.getBooleanExtra(EXTRA_IS_MOVE, false)
        binding.btnPasteHere.text = getString(if (isMove) R.string.paste_move_here else R.string.paste_copy_here)
        binding.toolbar.setNavigationOnClickListener { handleBack() }
        binding.btnExitPicker.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
            ActivityTransitions.backward(this)
        }

        adapter = LocalFileAdapter(
            iconRes = R.drawable.ic_file,
            onItemClick = { file -> if (file.isDirectory) openDir(File(file.path)) },
            onMoreClick = { _, _ -> }
        )
        cloudAdapter = RemoteFileAdapter(
            onItemClick = { file -> if (file.isDirectory) openCloudDir(file) },
            onMoreClick = { _, _ -> },
            filesClickable = false
        )
        binding.rvFolders.layoutManager = LinearLayoutManager(this)

        sdRootPath = SdCardUtils.findSdCardPath(this)
        binding.tabSdcard.visibility = if (sdRootPath != null) View.VISIBLE else View.GONE

        binding.tabInternal.setOnClickListener { selectTab(Tab.INTERNAL) }
        binding.tabSdcard.setOnClickListener { selectTab(Tab.SDCARD) }
        binding.tabNetwork.setOnClickListener { selectTab(Tab.NETWORK) }

        binding.btnNewFolder.setOnClickListener {
            if (currentTab == Tab.NETWORK) createCloudFolderDialog() else createFolderDialog()
        }
        binding.btnPasteHere.setOnClickListener { confirmDestination() }

        onBackPressedDispatcher.addCallback(this) { handleBack() }

        selectTab(Tab.INTERNAL)
    }

    /** Nút back cứng/toolbar: nếu đang duyệt sâu trong 1 tài khoản cloud thì lùi 1 cấp trước khi thoát màn. */
    private fun handleBack() {
        if (currentTab == Tab.NETWORK && cloudProvider != null) {
            if (cloudFolderStack.isEmpty()) {
                // Đang ở gốc của provider -> quay về màn chọn provider.
                cloudProvider = null
                cloudService = null
                cloudCurrentFolderId = ""
                showCloudProviderPicker()
            } else {
                val (folderId, _) = cloudFolderStack.removeLast()
                cloudCurrentFolderId = folderId
                loadCloudDir()
            }
            return
        }
        setResult(RESULT_CANCELED)
        finish()
        ActivityTransitions.backward(this)
    }

    private fun selectTab(tab: Tab) {
        currentTab = tab
        binding.tabInternal.isSelected = tab == Tab.INTERNAL
        binding.tabSdcard.isSelected = tab == Tab.SDCARD
        binding.tabNetwork.isSelected = tab == Tab.NETWORK

        when (tab) {
            Tab.INTERNAL -> {
                binding.rvFolders.adapter = adapter
                rootDir = Environment.getExternalStorageDirectory()
                currentDir = rootDir
                binding.rvFolders.visibility = View.VISIBLE
                binding.tvNetworkHint.visibility = View.GONE
                binding.btnNewFolder.visibility = View.VISIBLE
                binding.btnPasteHere.isEnabled = true
                binding.btnPasteHere.text = getString(if (isMove) R.string.paste_move_here else R.string.paste_copy_here)
                loadDirs()
            }
            Tab.SDCARD -> {
                val sdPath = sdRootPath
                if (sdPath == null) {
                    selectTab(Tab.INTERNAL)
                    return
                }
                binding.rvFolders.adapter = adapter
                rootDir = File(sdPath)
                currentDir = rootDir
                binding.rvFolders.visibility = View.VISIBLE
                binding.tvNetworkHint.visibility = View.GONE
                binding.btnNewFolder.visibility = View.VISIBLE
                binding.btnPasteHere.isEnabled = true
                binding.btnPasteHere.text = getString(if (isMove) R.string.paste_move_here else R.string.paste_copy_here)
                loadDirs()
            }
            Tab.NETWORK -> {
                binding.rvFolders.adapter = cloudAdapter
                binding.tvNetworkHint.visibility = View.GONE
                cloudProvider = null
                cloudService = null
                cloudFolderStack.clear()
                cloudCurrentFolderId = ""
                showCloudProviderPicker()
            }
        }
    }

    /** Liệt kê các provider cloud ĐÃ liên kết trong chính RecyclerView (dùng LocalFile giả để tái dùng adapter sẵn có). */
    private var providerLoadJob: Job? = null
    private fun showCloudProviderPicker() {
        providerLoadJob?.cancel()
        binding.btnNewFolder.visibility = View.GONE
        binding.btnPasteHere.isEnabled = false
        binding.tvCurrentPath.text = getString(R.string.home_network_storage)
        providerLoadJob = lifecycleScope.launch {
            val linked = withContext(Dispatchers.IO) {
                CloudProvider.values().filter { CloudServiceFactory.get(this@FolderPickerActivity, it).isLinked() }
            }
            if (linked.isEmpty()) {
                binding.rvFolders.visibility = View.GONE
                binding.tvEmpty.visibility = View.GONE
                binding.tvNetworkHint.visibility = View.VISIBLE
                binding.tvNetworkHint.text = getString(R.string.network_no_account_hint)
                return@launch
            }
            binding.tvNetworkHint.visibility = View.GONE
            binding.rvFolders.visibility = View.VISIBLE
            binding.tvEmpty.visibility = View.GONE
            // Dùng path = tên enum CloudProvider để openCloudDir() nhận diện đây là bước "chọn
            // account" (cloudProvider vẫn null) chứ không phải mở thư mục con thật.
            val asRemote = linked.map { p -> RemoteFile(name = providerDisplayName(p), path = p.name, isDirectory = true, cloudFileId = null) }
            cloudAdapter.submit(asRemote)
        }
    }

    private fun providerDisplayName(p: CloudProvider): String = when (p) {
        CloudProvider.GOOGLE_DRIVE -> getString(R.string.cloud_google_drive)
        CloudProvider.DROPBOX -> getString(R.string.cloud_dropbox)
        CloudProvider.BOX -> getString(R.string.cloud_box)
    }

    /** Click 1 mục trong RecyclerView khi tab NETWORK: nếu chưa chọn provider -> đây là bước chọn account; ngược lại là mở thư mục con thật. */
    private fun openCloudDir(file: RemoteFile) {
        val provider = cloudProvider
        if (provider == null) {
            // file.path chứa tên enum CloudProvider (gán ở showCloudProviderPicker)
            val selected = try { CloudProvider.valueOf(file.path) } catch (e: Exception) { return }
            cloudProvider = selected
            cloudService = CloudServiceFactory.get(this, selected)
            cloudFolderStack.clear()
            cloudCurrentFolderId = ""
            binding.btnNewFolder.visibility = View.VISIBLE
            binding.btnPasteHere.text = getString(
                if (isMove) R.string.paste_move_here_cloud else R.string.paste_upload_here
            )
            loadCloudDir()
            return
        }
        cloudFolderStack.addLast(cloudCurrentFolderId to file.name)
        cloudCurrentFolderId = file.cloudFileId ?: return
        loadCloudDir()
    }

    // Cùng cơ chế như CloudBrowserActivity: scope drive.file cần 1 lần consent riêng ở lệnh gọi
    // API đầu tiên, không liên quan gì tới "Đăng nhập bằng Google" ban đầu.
    private val driveConsentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            loadCloudDir()
        } else {
            Snackbar.make(binding.root, getString(R.string.error_generic), Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun loadCloudDir() {
        cloudLoadJob?.cancel()
        val service = cloudService ?: return
        val provider = cloudProvider ?: return
        binding.btnPasteHere.isEnabled = false
        cloudLoadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { service.listFiles(cloudCurrentFolderId) }
            binding.btnPasteHere.isEnabled = true
            result.onSuccess { files ->
                // Hiện đầy đủ cả file lẫn thư mục (giống mở Dropbox thật ở ảnh người dùng gửi),
                // không ẩn file nữa — chỉ để XEM, không bấm mở được (openCloudDir() đã tự bỏ qua
                // click vào file, chỉ xử lý khi isDirectory). Sắp xếp thư mục lên trước để không
                // lẫn với file khi cuộn tìm nơi cần vào.
                val sorted = files.sortedWith(compareByDescending<com.myfile.ui.model.RemoteFile> { it.isDirectory }.thenBy { it.name.lowercase() })
                cloudAdapter.submit(sorted)
                binding.tvEmpty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
                binding.rvFolders.visibility = View.VISIBLE
                val trail = cloudFolderStack.joinToString("") { " › ${it.second}" }
                binding.tvCurrentPath.text = "/ ${providerDisplayName(provider)}$trail"
            }.onFailure { ex ->
                if (ex is com.myfile.ui.cloud.GoogleDriveService.NeedsUserConsentException) {
                    driveConsentLauncher.launch(ex.intent)
                } else {
                    binding.tvEmpty.visibility = View.VISIBLE
                    Snackbar.make(binding.root, getString(R.string.error_generic), Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun createCloudFolderDialog() {
        val service = cloudService ?: return
        val input = android.widget.EditText(this).apply { setPadding(48, 32, 48, 32) }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.create_new_folder))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { service.createFolder(name, cloudCurrentFolderId) }
                    if (result.isSuccess) loadCloudDir()
                    else Snackbar.make(binding.root, getString(R.string.error_generic), Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun openDir(dir: File) {
        currentDir = dir
        loadDirs()
    }

    /**
     * NGUYÊN NHÂN GÂY ĐƠ MÀN "CHỌN THƯ MỤC": trước đây hàm này chạy hoàn toàn trên main thread,
     * và với MỖI thư mục hiển thị lại gọi thêm it.listFiles()?.size để đếm "N mục" — tức là
     * phải đọc toàn bộ nội dung của từng thư mục con (Android/, DCIM/, Download/...) chỉ để lấy
     * 1 con số rồi vứt danh sách đó đi. Với thư mục gốc bộ nhớ trong (Android/data chứa hàng
     * nghìn file cache của các app khác), việc này chặn UI ngay khi vừa mở màn hình — đúng lúc
     * người dùng bấm "Sao chép/Di chuyển đến" và thấy màn hình đơ trước khi kịp hiện danh sách.
     *
     * Sửa: liệt kê + đếm chạy trên Dispatchers.IO, không chặn main thread. Đồng thời hủy job
     * liệt kê trước đó (loadJob) khi người dùng bấm mở thư mục khác liên tiếp thật nhanh, tránh
     * 2 lần load chồng nhau submit list không đúng thứ tự vào adapter.
     */
    private var loadJob: Job? = null
    private fun loadDirs() {
        loadJob?.cancel()
        val dir = currentDir
        val tab = currentTab
        val root = rootDir
        binding.btnPasteHere.isEnabled = false
        loadJob = lifecycleScope.launch {
            val dirs = withContext(Dispatchers.IO) {
                (dir.listFiles() ?: emptyArray())
                    .filter { it.isDirectory && !it.name.startsWith(".") }
                    .sortedBy { it.name.lowercase() }
                    .map { f ->
                        // list() (chỉ tên) rẻ hơn listFiles() (tạo cả mảng File đầy đủ thuộc tính)
                        // vì đây chỉ cần đếm số lượng, không cần thông tin gì khác của mục con.
                        val count = f.list()?.size ?: 0
                        LocalFile(name = f.name, path = f.absolutePath, size = 0L, modifiedTime = f.lastModified(), isDirectory = true, itemCount = count)
                    }
            }
            adapter.submit(dirs)
            binding.tvEmpty.visibility = if (dirs.isEmpty()) View.VISIBLE else View.GONE
            binding.btnPasteHere.isEnabled = tab != Tab.NETWORK

            val relPath = dir.absolutePath.removePrefix(root.absolutePath).trim('/')
            val rootLabel = if (tab == Tab.SDCARD) getString(R.string.home_sdcard) else getString(R.string.home_internal_storage)
            binding.tvCurrentPath.text = if (relPath.isEmpty()) "/ $rootLabel" else "/ $rootLabel / ${relPath.replace('/', '›')}"
        }
    }

    private fun createFolderDialog() {
        val input = android.widget.EditText(this).apply { setPadding(48, 32, 48, 32) }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.create_new_folder))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val newDir = File(currentDir, name)
                    if (newDir.mkdirs()) loadDirs()
                    else Snackbar.make(binding.root, getString(R.string.error_generic), Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun confirmDestination() {
        val result = Intent()
        if (currentTab == Tab.NETWORK) {
            val provider = cloudProvider ?: return // đang ở màn chọn provider, chưa có đích hợp lệ
            result.putExtra(EXTRA_RESULT_DEST_CLOUD_PROVIDER, provider.name)
            result.putExtra(EXTRA_RESULT_DEST_CLOUD_FOLDER_ID, cloudCurrentFolderId)
        } else {
            result.putExtra(EXTRA_RESULT_DEST_PATH, currentDir.absolutePath)
        }
        setResult(RESULT_OK, result)
        finish()
        ActivityTransitions.backward(this)
    }

    companion object {
        const val EXTRA_IS_MOVE = "extra_is_move"
        const val EXTRA_RESULT_DEST_PATH = "extra_result_dest_path"
        const val EXTRA_RESULT_DEST_CLOUD_PROVIDER = "extra_result_dest_cloud_provider"
        const val EXTRA_RESULT_DEST_CLOUD_FOLDER_ID = "extra_result_dest_cloud_folder_id"
    }
}
