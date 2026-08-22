package com.myfile.ui.adapters

import com.myfile.ui.R
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.myfile.ui.databinding.ItemDuplicateGroupHeaderBinding
import com.myfile.ui.databinding.ItemRemoteFileBinding
import com.myfile.ui.model.LocalFile
import java.text.DecimalFormat

/**
 * Danh sach cac nhom file trung lap: moi nhom co 1 dong tieu de (so ban sao + dung luong moi
 * file + nut "Giu 1, xoa het") va cac dong file ben duoi (checkbox chon tung file de xoa rieng).
 * File dau tien trong moi nhom duoc danh dau "ban goc" va khong the bo chon rieng le qua nut nhom.
 */
class DuplicateGroupAdapter(
    private val onToggleFile: (LocalFile) -> Unit,
    private val onKeepOneDeleteRest: (List<LocalFile>) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Phan tu hien thi: hoac la header cua 1 nhom, hoac la 1 file thuoc nhom do. */
    private sealed class Row {
        data class Header(val groupIndex: Int, val count: Int, val sizeEach: Long) : Row()
        data class FileRow(val file: LocalFile, val isFirstInGroup: Boolean) : Row()
    }

    private var groups: List<List<LocalFile>> = emptyList()
    private val selectedPaths = mutableSetOf<String>()
    private val rows = mutableListOf<Row>()

    fun submit(newGroups: List<List<LocalFile>>) {
        groups = newGroups
        selectedPaths.clear()
        rebuildRows()
        notifyDataSetChanged()
    }

    private fun rebuildRows() {
        rows.clear()
        groups.forEachIndexed { idx, group ->
            val sizeEach = group.firstOrNull()?.size ?: 0L
            rows.add(Row.Header(idx, group.size, sizeEach))
            group.forEachIndexed { fileIdx, file ->
                rows.add(Row.FileRow(file, isFirstInGroup = fileIdx == 0))
            }
        }
    }

    fun getSelectedFiles(): List<LocalFile> =
        groups.flatten().filter { selectedPaths.contains(it.path) }

    fun selectedCount(): Int = selectedPaths.size

    fun clearSelection() {
        selectedPaths.clear()
        notifyDataSetChanged()
    }

    private fun toggle(file: LocalFile) {
        if (selectedPaths.contains(file.path)) selectedPaths.remove(file.path) else selectedPaths.add(file.path)
        onToggleFile(file)
        val idx = rows.indexOfFirst { it is Row.FileRow && it.file.path == file.path }
        if (idx >= 0) notifyItemChanged(idx)
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.FileRow -> TYPE_FILE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemDuplicateGroupHeaderBinding.inflate(inflater, parent, false))
        } else {
            FileVH(ItemRemoteFileBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> {
                (holder as HeaderVH).binding.tvGroupTitle.text =
                    "${row.count} bản sao • ${formatSize(row.sizeEach)} mỗi file"
                holder.binding.tvGroupSelectAll.setOnClickListener {
                    val group = groups[row.groupIndex]
                    // "Giữ 1, xóa hết": chọn tất cả trừ file đầu tiên (bản gốc) trong nhóm.
                    val toSelect = group.drop(1)
                    toSelect.forEach { selectedPaths.add(it.path) }
                    onKeepOneDeleteRest(toSelect)
                    val groupStart = rows.indexOfFirst { it is Row.Header && it.groupIndex == row.groupIndex }
                    if (groupStart >= 0) notifyItemRangeChanged(groupStart, group.size + 1)
                }
            }
            is Row.FileRow -> {
                val file = row.file
                val fileBinding = (holder as FileVH).binding
                fileBinding.tvFileName.text = file.name
                fileBinding.tvFileMeta.text = if (row.isFirstInGroup) {
                    "Bản gốc • ${formatSize(file.size)}"
                } else formatSize(file.size)
                fileBinding.ivIcon.setImageResource(com.myfile.ui.R.drawable.ic_file)
                fileBinding.btnMore.visibility = android.view.View.GONE
                val isSelected = selectedPaths.contains(file.path)
                fileBinding.ivSelectedCheck.visibility = android.view.View.VISIBLE
                fileBinding.ivSelectedCheck.alpha = if (isSelected) 1f else 0.25f
                fileBinding.root.alpha = if (isSelected) 0.7f else 1f
                fileBinding.root.setOnClickListener { toggle(file) }
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val safeGroup = digitGroups.coerceIn(0, units.size - 1)
        return DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, safeGroup.toDouble())) + " " + units[safeGroup]
    }

    private class HeaderVH(val binding: ItemDuplicateGroupHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    private class FileVH(val binding: ItemRemoteFileBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_FILE = 1
    }
}
