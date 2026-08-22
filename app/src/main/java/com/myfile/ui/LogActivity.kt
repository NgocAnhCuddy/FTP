package com.myfile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.myfile.ui.adapters.LogAdapter
import com.myfile.ui.databinding.ActivityLogBinding
import com.myfile.ui.model.LogEntry
import com.myfile.ui.model.LogLevel
import com.myfile.ui.util.ActivityTransitions
import com.myfile.ui.util.LogBus
import kotlinx.coroutines.launch

/**
 * Bảng điều khiển gỡ lỗi: nhật ký hoạt động của toàn app (máy chủ FTP, kết nối client
 * FTP/SFTP/SMB, trình xem HTML...) theo thời gian thực, có thể lọc theo mức độ và tìm kiếm
 * theo nội dung — để nhanh chóng xác định lỗi xảy ra ở đâu, nguồn nào, lúc nào.
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private lateinit var adapter: LogAdapter

    private var allLogs: List<LogEntry> = emptyList()
    private var searchQuery: String = ""
    private var activeFilter: LogLevel? = null // null = "Tất cả"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            finish()
            ActivityTransitions.backward(this)
        }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_clear_logs -> confirmClearLogs()
                R.id.action_copy_logs -> copyLogsToClipboard()
            }
            true
        }

        adapter = LogAdapter()
        binding.rvLogs.layoutManager = LinearLayoutManager(this)
        binding.rvLogs.adapter = adapter

        setupSearch()
        setupFilterChips()

        lifecycleScope.launch {
            LogBus.logs.collect { logs ->
                allLogs = logs
                applyFiltersAndRender()
            }
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                applyFiltersAndRender()
            }
        })
    }

    private fun setupFilterChips() {
        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: binding.chipFilterAll.id
            activeFilter = when (id) {
                binding.chipFilterInfo.id -> LogLevel.INFO
                binding.chipFilterSuccess.id -> LogLevel.SUCCESS
                binding.chipFilterWarning.id -> LogLevel.WARNING
                binding.chipFilterError.id -> LogLevel.ERROR
                else -> null
            }
            applyFiltersAndRender()
        }
    }

    private fun applyFiltersAndRender() {
        var filtered = allLogs
        activeFilter?.let { level -> filtered = filtered.filter { it.level == level } }
        if (searchQuery.isNotBlank()) {
            filtered = filtered.filter {
                it.message.contains(searchQuery, ignoreCase = true) ||
                    it.source.contains(searchQuery, ignoreCase = true) ||
                    it.detail?.contains(searchQuery, ignoreCase = true) == true
            }
        }
        adapter.submit(filtered)
        binding.rvLogs.scheduleLayoutAnimation()
        binding.tvEmptyLogs.visibility =
            if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmptyLogs.text = if (allLogs.isEmpty()) {
            getString(R.string.empty_logs)
        } else {
            getString(R.string.debug_no_results)
        }
    }

    private fun confirmClearLogs() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.btn_clear_logs))
            .setPositiveButton(getString(R.string.ok)) { _, _ -> LogBus.clear() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun copyLogsToClipboard() {
        val text = adapter.currentItemsAsText()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("logs", text))
        Snackbar.make(binding.root, getString(R.string.logs_copied), Snackbar.LENGTH_SHORT).show()
    }
}
