package com.myfile.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.myfile.ui.adapters.TrashAdapter
import com.myfile.ui.databinding.ActivityTrashBinding
import com.myfile.ui.util.ActivityTransitions
import com.myfile.ui.util.TrashEntry
import com.myfile.ui.util.TrashManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Màn hình Thùng rác thật: hiển thị các file đã "xóa" (thực chất được di chuyển vào
 * thư mục ẩn .MyFileTrash), cho phép khôi phục về đúng vị trí gốc hoặc xóa vĩnh viễn,
 * tương đương hành vi Thùng rác của Samsung My Files.
 */
class TrashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrashBinding
    private lateinit var trashManager: TrashManager
    private lateinit var adapter: TrashAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        trashManager = TrashManager.getInstance(this)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
            ActivityTransitions.backward(this)
        }

        adapter = TrashAdapter(
            onRestore = { restore(it) },
            onDeleteForever = { confirmDeleteForever(it) }
        )
        binding.rvTrash.layoutManager = LinearLayoutManager(this)
        binding.rvTrash.adapter = adapter

        binding.btnEmptyTrash.setOnClickListener { confirmEmptyTrash() }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val entries = trashManager.listEntries()
        adapter.submit(entries)
        binding.rvTrash.scheduleLayoutAnimation()
        binding.tvEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.btnEmptyTrash.isEnabled = entries.isNotEmpty()
        binding.btnEmptyTrash.alpha = if (entries.isEmpty()) 0.4f else 1f
    }

    /**
     * restore()/deleteForever()/emptyTrash() thao tác trực tiếp trên hệ thống file (copy/xóa đệ
     * quy) — nếu vị trí gốc là THẺ NHỚ SD, renameTo() khác phân vùng luôn fail và rơi vào nhánh
     * copyRecursively() đồng bộ. Chạy trên main thread trước đây khiến app treo/ANR khi khôi phục
     * hoặc xóa vĩnh viễn thư mục/album lớn. Chuyển toàn bộ sang Dispatchers.IO.
     */
    private fun restore(entry: TrashEntry) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { trashManager.restore(entry) }
            Snackbar.make(
                binding.root,
                getString(if (ok) R.string.restored else R.string.error_generic),
                Snackbar.LENGTH_SHORT
            ).show()
            refreshList()
        }
    }

    private fun confirmDeleteForever(entry: TrashEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.btn_delete_forever))
            .setMessage(getString(R.string.confirm_delete_forever))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { trashManager.deleteForever(entry) }
                    refreshList()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun confirmEmptyTrash() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.btn_empty_trash))
            .setMessage(getString(R.string.confirm_empty_trash))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { trashManager.emptyTrash() }
                    refreshList()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
