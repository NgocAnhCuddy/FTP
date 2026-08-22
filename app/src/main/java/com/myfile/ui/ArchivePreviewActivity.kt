package com.myfile.ui

import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.myfile.ui.adapters.ArchiveEntryAdapter
import com.myfile.ui.databinding.ActivityArchivePreviewBinding
import com.myfile.ui.databinding.ItemBreadcrumbBinding
import com.myfile.ui.model.ArchiveNode
import com.myfile.ui.util.ActivityTransitions
import com.myfile.ui.util.ArchiveUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Xem trước nội dung file .zip/.7z TRƯỚC KHI giải nén, điều hướng theo cây thư mục bên trong
 * file nén bằng breadcrumb (giống Files/Samsung My Files khi mở 1 zip). Người dùng có thể tick
 * chọn từng file/thư mục con cụ thể — mặc định chọn hết — rồi bấm "Giải nén" để chỉ giải nén
 * đúng phần đã chọn vào 1 thư mục con cùng tên file nén.
 *
 * Nhận EXTRA_ARCHIVE_PATH (đường dẫn file nén trên đĩa). Khi giải nén xong (hoặc bấm Thoát sau
 * khi đã giải nén), trả RESULT_OK để màn hình gọi (CategoryFilesActivity...) biết mà load lại danh sách.
 */
class ArchivePreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ARCHIVE_PATH = "extra_archive_path"
    }

    private lateinit var binding: ActivityArchivePreviewBinding
    private lateinit var archiveFile: File

    // root là lateinit vì được dựng bất đồng bộ trong loadArchive() (đọc file .zip/.7z tốn thời
    // gian, không thể có ngay lúc onCreate). archiveLoaded đánh dấu khi nào truy cập root là AN
    // TOÀN — nếu file nén hỏng/mật khẩu bảo vệ/không đọc được, loadArchive() gọi finish() nhưng
    // Activity CHƯA HỦY NGAY LẬP TỨC (finish() chỉ xếp lịch huỷ) — trong khoảng thời gian đó,
    // người dùng bấm rất nhanh vào breadcrumb/nút chọn tất cả trước khi màn hình biến mất vẫn có
    // thể trigger updateSelectionHeader()/toggleSelectAllInCurrentView() chạm vào root chưa từng
    // được gán -> UninitializedPropertyAccessException -> crash. Guard bằng cờ này ở MỌI hàm có
    // đụng tới root/folderStack thay vì chỉ dựa vào Activity sắp bị finish().
    private var archiveLoaded = false
    private lateinit var root: ArchiveNode
    private var archiveModifiedTime: Long = System.currentTimeMillis()

    // Ngăn xếp thư mục hiện đang xem, dùng cho breadcrumb + nút back cứng của hệ thống
    private val folderStack = mutableListOf<ArchiveNode>()

    // Đường dẫn (entryPath) các mục đã CHỌN. Rỗng khi khởi tạo -> sẽ được fill full = "chọn tất cả" theo mặc định.
    private val selectedPaths = mutableSetOf<String>()
    private var didExtract = false

    private lateinit var adapter: ArchiveEntryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArchivePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_ARCHIVE_PATH)
        if (path.isNullOrBlank()) {
            finish()
            return
        }
        archiveFile = File(path)
        archiveModifiedTime = archiveFile.lastModified()

        adapter = ArchiveEntryAdapter(
            isSelected = { node -> isNodeSelected(node) },
            onToggleSelect = { node -> toggleSelect(node) },
            onOpenFolder = { node -> openFolder(node) },
            entryDate = archiveModifiedTime
        )
        binding.rvArchiveEntries.layoutManager = LinearLayoutManager(this)
        binding.rvArchiveEntries.adapter = adapter

        binding.btnExit.setOnClickListener { finishScreen() }
        binding.btnExtractSelected.setOnClickListener { extractSelectedEntries() }
        binding.ivSelectAll.setOnClickListener { toggleSelectAllInCurrentView() }
        binding.tvSelectAllLabel.setOnClickListener { toggleSelectAllInCurrentView() }

        onBackPressedDispatcher.addCallback(this) { finishScreen() }

        loadArchive()
    }

    private fun finishScreen() {
        if (didExtract) setResult(RESULT_OK)
        finish()
        ActivityTransitions.backward(this)
    }

    private fun loadArchive() {
        binding.progress.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { ArchiveUtils.listEntries(archiveFile) }
            binding.progress.visibility = View.GONE
            result.onSuccess { entries ->
                if (entries.isEmpty()) {
                    binding.tvEmpty.visibility = View.VISIBLE
                    return@onSuccess
                }
                root = ArchiveUtils.buildTree(entries)
                archiveLoaded = true
                // Mặc định chọn TẤT CẢ mục (giống ảnh mẫu "Đã chọn 1" khi chỉ có 1 mục ở gốc)
                selectAllRecursive(root)
                folderStack.clear()
                folderStack.add(root)
                renderCurrentFolder()
            }.onFailure {
                com.google.android.material.snackbar.Snackbar.make(
                    binding.root, getString(R.string.extract_failed), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    private fun selectAllRecursive(node: ArchiveNode) {
        if (node.entryPath.isNotEmpty()) selectedPaths.add(node.entryPath)
        node.children.forEach { selectAllRecursive(it) }
    }

    private fun deselectAllRecursive(node: ArchiveNode) {
        if (node.entryPath.isNotEmpty()) selectedPaths.remove(node.entryPath)
        node.children.forEach { deselectAllRecursive(it) }
    }

    /** 1 mục coi là "đã chọn" nếu bản thân nó (hoặc 1 tổ tiên của nó) nằm trong selectedPaths. */
    private fun isNodeSelected(node: ArchiveNode): Boolean {
        if (selectedPaths.contains(node.entryPath)) return true
        var parent = node.entryPath.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            if (selectedPaths.contains(parent)) return true
            parent = parent.substringBeforeLast('/', "")
        }
        return false
    }

    private fun toggleSelect(node: ArchiveNode) {
        if (!archiveLoaded) return
        if (isNodeSelected(node)) {
            deselectAllRecursive(node)
            // Nếu cha đang được chọn nguyên khối, gỡ nó ra và chọn lại các anh em còn lại
            val current = folderStack.last()
            if (selectedPaths.contains(current.entryPath) || current === root) {
                selectedPaths.remove(current.entryPath)
                current.children.filter { it.entryPath != node.entryPath }.forEach { selectAllRecursive(it) }
            }
        } else {
            selectAllRecursive(node)
        }
        adapter.notifyDataSetChanged()
        updateSelectionHeader()
    }

    private fun toggleSelectAllInCurrentView() {
        if (!archiveLoaded) return
        val current = folderStack.last()
        val allSelected = current.children.all { isNodeSelected(it) }
        if (allSelected) current.children.forEach { deselectAllRecursive(it) }
        else current.children.forEach { selectAllRecursive(it) }
        adapter.notifyDataSetChanged()
        updateSelectionHeader()
    }

    private fun openFolder(node: ArchiveNode) {
        if (!archiveLoaded) return
        folderStack.add(node)
        renderCurrentFolder()
    }

    private fun renderCurrentFolder() {
        val current = folderStack.last()
        adapter.submit(current.children)
        binding.tvEmpty.visibility = if (current.children.isEmpty()) View.VISIBLE else View.GONE
        rebuildBreadcrumb()
        updateSelectionHeader()
    }

    private fun rebuildBreadcrumb() {
        binding.breadcrumbContainer.removeAllViews()
        val rootLabel = ItemBreadcrumbBinding.inflate(layoutInflater, binding.breadcrumbContainer, false)
        rootLabel.tvCrumb.text = archiveFile.name
        rootLabel.tvCrumb.setOnClickListener { navigateBreadcrumbTo(0) }
        binding.breadcrumbContainer.addView(rootLabel.root)

        for (i in 1 until folderStack.size) {
            val crumb = ItemBreadcrumbBinding.inflate(layoutInflater, binding.breadcrumbContainer, false)
            crumb.tvCrumb.text = folderStack[i].name
            val index = i
            crumb.tvCrumb.setOnClickListener { navigateBreadcrumbTo(index) }
            binding.breadcrumbContainer.addView(crumb.root)
        }
        binding.root.post {
            (binding.breadcrumbContainer.parent as? android.widget.HorizontalScrollView)?.fullScroll(View.FOCUS_RIGHT)
        }
    }

    private fun navigateBreadcrumbTo(index: Int) {
        if (!archiveLoaded) return
        while (folderStack.size > index + 1) folderStack.removeAt(folderStack.size - 1)
        renderCurrentFolder()
    }

    private fun updateSelectionHeader() {
        if (!archiveLoaded) return
        val totalSelected = countSelectedLeaves(root)
        binding.tvSelectedCount.text = getString(R.string.selected_count, totalSelected)
        val current = folderStack.last()
        val allSelectedHere = current.children.isNotEmpty() && current.children.all { isNodeSelected(it) }
        binding.ivSelectAll.setImageResource(
            if (allSelectedHere) R.drawable.ic_check_circle else R.drawable.ic_check_circle_outline
        )
    }

    /** Đếm số mục CẤP GỐC (con trực tiếp của root) đang được chọn — khớp cách Files hiển thị "Đã chọn N". */
    private fun countSelectedLeaves(node: ArchiveNode): Int {
        return node.children.count { isNodeSelected(it) }
    }

    private fun collectSelectedTopLevelPaths(): Set<String> {
        // Giải nén theo đường dẫn đã chọn ở BẤT KỲ cấp nào; extractSelected() trong ArchiveUtils
        // đã tự xử lý việc 1 thư mục được chọn thì kéo theo mọi file con bên trong.
        val result = mutableSetOf<String>()
        fun walk(node: ArchiveNode) {
            if (node.entryPath.isNotEmpty() && selectedPaths.contains(node.entryPath)) {
                result.add(node.entryPath)
                return // đã chọn cả thư mục này, không cần đi sâu thêm
            }
            node.children.forEach { walk(it) }
        }
        walk(root)
        return result
    }

    private fun extractSelectedEntries() {
        if (!archiveLoaded) return
        val chosen = collectSelectedTopLevelPaths()
        if (chosen.isEmpty()) {
            com.google.android.material.snackbar.Snackbar.make(
                binding.root, getString(R.string.select_at_least_one), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
            ).show()
            return
        }
        val destDir = File(archiveFile.parentFile, archiveFile.nameWithoutExtension)
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ArchiveUtils.extractSelected(archiveFile, destDir, chosen)
            }
            binding.progress.visibility = View.GONE
            if (result.isSuccess) {
                didExtract = true
                com.google.android.material.snackbar.Snackbar.make(
                    binding.root, getString(R.string.extract_success), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                ).show()
                binding.root.postDelayed({ finishScreen() }, 600)
            } else {
                com.google.android.material.snackbar.Snackbar.make(
                    binding.root, getString(R.string.extract_failed), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }
}
