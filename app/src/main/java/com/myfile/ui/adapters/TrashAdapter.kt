package com.myfile.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.myfile.ui.R
import com.myfile.ui.databinding.ItemTrashFileBinding
import com.myfile.ui.util.TrashEntry
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Hiển thị danh sách các mục trong Thùng rác, mỗi dòng có nút Khôi phục và Xóa vĩnh viễn. */
class TrashAdapter(
    private val onRestore: (TrashEntry) -> Unit,
    private val onDeleteForever: (TrashEntry) -> Unit
) : ListAdapter<TrashEntry, TrashAdapter.VH>(DIFF) {

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    fun submit(newItems: List<TrashEntry>) {
        submitList(newItems.toList())
    }

    inner class VH(val binding: ItemTrashFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTrashFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = getItem(position)
        holder.binding.tvFileName.text = entry.name
        holder.binding.ivIcon.setImageResource(R.drawable.ic_file)
        val sizeText = formatSize(entry.size)
        holder.binding.tvFileMeta.text = "$sizeText • ${dateFormat.format(Date(entry.deletedAt))}"
        holder.binding.btnRestore.setOnClickListener { onRestore(entry) }
        holder.binding.btnDeleteForever.setOnClickListener { onDeleteForever(entry) }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val safeGroup = digitGroups.coerceIn(0, units.size - 1)
        return DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, safeGroup.toDouble())) + " " + units[safeGroup]
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TrashEntry>() {
            override fun areItemsTheSame(oldItem: TrashEntry, newItem: TrashEntry) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: TrashEntry, newItem: TrashEntry) = oldItem == newItem
        }
    }
}
