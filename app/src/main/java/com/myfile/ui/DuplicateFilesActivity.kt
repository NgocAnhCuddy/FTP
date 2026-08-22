package com.myfile.ui

import android.os.Bundle
import android.os.Environment
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.myfile.ui.adapters.DuplicateGroupAdapter
import com.myfile.ui.databinding.ActivityDuplicateFilesBinding
import com.myfile.ui.model.LocalFile
import com.myfile.ui.util.ActivityTransitions
import com.myfile.ui.util.TrashManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Man hinh "File trung lap": quet Bo nho trong, nhom cac file co NOI DUNG giong het nhau
 * (so sanh theo hash MD5, chi tinh hash cho cac file co cung dung luong de tiet kiem thoi
 * gian quet). Cho phep chon tung file de xoa, hoac bam "Giu 1, xoa het" cho tung nhom —
 * xoa se chuyen vao Thung rac that (TrashManager), khong xoa vinh vien ngay.
 */
class DuplicateFilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDuplicateFilesBinding
    private lateinit var adapter: DuplicateGroupAdapter
    private lateinit var trashManager: TrashManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDuplicateFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        trashManager = TrashManager.getInstance(this)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
            ActivityTransitions.backward(this)
        }

        adapter = DuplicateGroupAdapter(
            onToggleFile = { updateDeleteButtonState() },
            onKeepOneDeleteRest = { updateDeleteButtonState() }
        )
        binding.rvDuplicates.layoutManager = LinearLayoutManager(this)
        binding.rvDuplicates.adapter = adapter

        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }

        scanForDuplicates()
    }

    private fun updateDeleteButtonState() {
        val count = adapter.selectedCount()
        binding.btnDeleteSelected.isEnabled = count > 0
        binding.btnDeleteSelected.alpha = if (count > 0) 1f else 0.4f
        binding.btnDeleteSelected.text = if (count > 0) {
            getString(R.string.btn_delete_selected_count, count)
        } else getString(R.string.btn_delete_selected)
    }

    private fun scanForDuplicates() {
        binding.layoutScanning.visibility = View.VISIBLE
        binding.rvDuplicates.visibility = View.GONE
        binding.tvEmpty.visibility = View.GONE
        binding.tvSummary.visibility = View.GONE

        lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) { findDuplicateGroups() }
            binding.layoutScanning.visibility = View.GONE

            if (groups.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
                binding.rvDuplicates.visibility = View.GONE
            } else {
                binding.rvDuplicates.visibility = View.VISIBLE
                adapter.submit(groups)
                val totalReclaimable = groups.sumOf { group -> group.drop(1).sumOf { it.size } }
                binding.tvSummary.visibility = View.VISIBLE
                binding.tvSummary.text = getString(
                    R.string.duplicate_summary_format,
                    groups.size,
                    formatSize(totalReclaimable)
                )
            }
            updateDeleteButtonState()
        }
    }

    /**
     * Buoc 1: nhom cac file theo dung luong (rat re, khong can doc noi dung).
     * Buoc 2: voi moi nhom co >= 2 file cung dung luong, tinh hash MD5 that su de xac nhan
     * trung khop noi dung (tranh false-positive khi 2 file tinh co cung size nhung khac noi dung).
     */
    private suspend fun findDuplicateGroups(): List<List<LocalFile>> {
        val root = Environment.getExternalStorageDirectory() ?: return emptyList()
        val allFiles = mutableListOf<File>()
        collectFiles(root, depth = 0, maxDepth = 6, out = allFiles)

        val bySize = allFiles.filter { it.length() > MIN_SIZE_BYTES }.groupBy { it.length() }
        val candidateGroups = bySize.values.filter { it.size >= 2 }

        val result = mutableListOf<List<LocalFile>>()
        for (candidates in candidateGroups) {
            val byHash = candidates.mapNotNull { f ->
                val hash = try { md5Of(f) } catch (e: Exception) { null }
                hash?.let { it to f }
            }.groupBy({ it.first }, { it.second })

            for ((_, files) in byHash) {
                if (files.size >= 2) {
                    result.add(
                        files.sortedBy { it.lastModified() }.map { f ->
                            LocalFile(
                                name = f.name,
                                path = f.absolutePath,
                                size = f.length(),
                                modifiedTime = f.lastModified(),
                                isDirectory = false
                            )
                        }
                    )
                }
            }
        }
        // Nhóm có nhiều bản sao nhất / dung lượng lớn nhất lên trước, giúp người dùng thấy ngay
        // các nhóm đáng dọn dẹp nhất.
        return result.sortedByDescending { it.size * (it.firstOrNull()?.size ?: 0L) }
    }

    private fun collectFiles(dir: File, depth: Int, maxDepth: Int, out: MutableList<File>) {
        if (depth > maxDepth) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.isDirectory) {
                if (!f.name.startsWith(".")) collectFiles(f, depth + 1, maxDepth, out)
            } else {
                out.add(f)
            }
        }
    }

    private fun md5Of(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun confirmDeleteSelected() {
        val selected = adapter.getSelectedFiles()
        if (selected.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.btn_delete))
            .setMessage(getString(R.string.confirm_delete_count, selected.size))
            .setPositiveButton(getString(R.string.delete)) { _, _ -> deleteSelected(selected) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun deleteSelected(files: List<LocalFile>) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                files.forEach { trashManager.moveToTrash(File(it.path)) }
            }
            Snackbar.make(binding.root, getString(R.string.moved_to_trash), Snackbar.LENGTH_SHORT).show()
            scanForDuplicates()
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val safeGroup = digitGroups.coerceIn(0, units.size - 1)
        return java.text.DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, safeGroup.toDouble())) + " " + units[safeGroup]
    }

    companion object {
        // Bỏ qua file quá nhỏ (< 4KB) để tránh hàng trăm file cấu hình/rác trùng vô nghĩa làm loãng kết quả.
        private const val MIN_SIZE_BYTES = 4 * 1024L
    }
}
