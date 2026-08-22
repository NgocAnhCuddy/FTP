package com.myfile.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.myfile.ui.R
import com.myfile.ui.databinding.ItemArchiveEntryBinding
import com.myfile.ui.model.ArchiveNode
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Adapter cho danh sách mục (file/thư mục) trong 1 cấp của cây file nén, dùng ở ArchivePreviewActivity.
 * [isSelected] tra trạng thái tick của 1 node theo entryPath (nguồn dữ liệu chọn nằm ở Activity,
 * adapter chỉ hỏi & vẽ lại, không giữ state chọn để tránh lệch khi điều hướng qua lại các cấp).
 */
class ArchiveEntryAdapter(
    private val isSelected: (ArchiveNode) -> Boolean,
    private val onToggleSelect: (ArchiveNode) -> Unit,
    private val onOpenFolder: (ArchiveNode) -> Unit,
    private val entryDate: Long
) : RecyclerView.Adapter<ArchiveEntryAdapter.VH>() {

    private val items = mutableListOf<ArchiveNode>()
    private val dateFmt = SimpleDateFormat("d 'Th'M HH:mm", Locale.getDefault())

    fun submit(newItems: List<ArchiveNode>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemArchiveEntryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemArchiveEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val node = items[position]
        val b = holder.binding
        b.tvEntryName.text = node.name
        b.ivIcon.setImageResource(if (node.isDirectory) R.drawable.ic_folder else R.drawable.ic_file)
        b.ivSelectedCheck.setImageResource(
            if (isSelected(node)) R.drawable.ic_check_circle else R.drawable.ic_check_circle_outline
        )

        if (node.isDirectory) {
            b.tvEntryMeta.text = dateFmt.format(entryDate)
            b.tvEntryExtra.text = b.root.context.getString(R.string.items_count, node.children.size)
            b.tvEntryExtra.visibility = android.view.View.VISIBLE
        } else {
            b.tvEntryMeta.text = dateFmt.format(entryDate)
            b.tvEntryExtra.text = formatSize(node.size)
            b.tvEntryExtra.visibility = android.view.View.VISIBLE
        }

        b.ivSelectedCheck.setOnClickListener { onToggleSelect(node) }
        b.root.setOnClickListener {
            if (node.isDirectory) onOpenFolder(node) else onToggleSelect(node)
        }
        b.root.setOnLongClickListener { onToggleSelect(node); true }
    }

    override fun getItemCount(): Int = items.size

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val safeGroup = digitGroups.coerceIn(0, units.size - 1)
        return DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, safeGroup.toDouble())) + " " + units[safeGroup]
    }
}
