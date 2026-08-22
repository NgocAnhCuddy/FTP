package com.myfile.ui

import android.os.Bundle
import android.os.Environment
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.myfile.ui.adapters.LocalFileAdapter
import com.myfile.ui.databinding.ActivityLargeFilesBinding
import com.myfile.ui.model.LocalFile
import com.myfile.ui.util.ActivityTransitions
import com.myfile.ui.util.TrashManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Man hinh "File lon": quet toan bo Bo nho trong, liet ke cac file co dung luong > 25 MB
 * (giong nguong hien trong anh mau "Cac file lon hon 25 MB"), sap xep giam dan theo dung
 * luong de nguoi dung thay ngay file dang don dep nhat. Cho chon nhieu file de xoa cung
 * luc, chuyen vao Thung rac that thay vi xoa vinh vien ngay.
 */
class LargeFilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLargeFilesBinding
    private lateinit var adapter: LocalFileAdapter
    private lateinit var trashManager: TrashManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLargeFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        trashManager = TrashManager.getInstance(this)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
            ActivityTransitions.backward(this)
        }

        adapter = LocalFileAdapter(
            iconRes = R.drawable.ic_file,
            onItemClick = { adapter.toggleSelection(it) },
            onMoreClick = { file, _ -> confirmDeleteSingle(file) },
            onSelectionChanged = { updateDeleteButtonState() }
        )
        binding.rvLargeFiles.layoutManager = LinearLayoutManager(this)
        binding.rvLargeFiles.adapter = adapter

        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }

        scanLargeFiles()
    }

    private fun updateDeleteButtonState() {
        val count = adapter.selectedCount()
        binding.btnDeleteSelected.isEnabled = count > 0
        binding.btnDeleteSelected.alpha = if (count > 0) 1f else 0.4f
        binding.btnDeleteSelected.text = if (count > 0) {
            getString(R.string.btn_delete_selected_count, count)
        } else getString(R.string.btn_delete_selected)
    }

    private fun scanLargeFiles() {
        binding.progress.visibility = View.VISIBLE
        binding.rvLargeFiles.visibility = View.GONE
        binding.tvEmpty.visibility = View.GONE

        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { findLargeFiles() }
            binding.progress.visibility = View.GONE
            if (files.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
                binding.rvLargeFiles.visibility = View.GONE
            } else {
                binding.rvLargeFiles.visibility = View.VISIBLE
                adapter.submit(files)
            }
            updateDeleteButtonState()
        }
    }

    private fun findLargeFiles(): List<LocalFile> {
        val root = Environment.getExternalStorageDirectory() ?: return emptyList()
        val result = mutableListOf<LocalFile>()

        fun scan(dir: File, depth: Int) {
            if (depth > MAX_DEPTH) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (f.isDirectory) {
                    if (!f.name.startsWith(".")) scan(f, depth + 1)
                } else if (f.length() > THRESHOLD_BYTES) {
                    result.add(
                        LocalFile(
                            name = f.name,
                            path = f.absolutePath,
                            size = f.length(),
                            modifiedTime = f.lastModified(),
                            isDirectory = false
                        )
                    )
                }
            }
        }
        try {
            scan(root, 0)
        } catch (e: Exception) {
            // bỏ qua thư mục không đọc được
        }
        return result.sortedByDescending { it.size }
    }

    private fun confirmDeleteSingle(file: LocalFile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.btn_delete))
            .setMessage(file.name)
            .setPositiveButton(getString(R.string.delete)) { _, _ -> deleteFiles(listOf(file)) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun confirmDeleteSelected() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.btn_delete))
            .setMessage(getString(R.string.confirm_delete_count, selected.size))
            .setPositiveButton(getString(R.string.delete)) { _, _ -> deleteFiles(selected) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun deleteFiles(files: List<LocalFile>) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                files.forEach { trashManager.moveToTrash(File(it.path)) }
            }
            adapter.clearSelection()
            Snackbar.make(binding.root, getString(R.string.moved_to_trash), Snackbar.LENGTH_SHORT).show()
            scanLargeFiles()
        }
    }

    companion object {
        private const val THRESHOLD_BYTES = 25 * 1024 * 1024L
        private const val MAX_DEPTH = 6
    }
}
