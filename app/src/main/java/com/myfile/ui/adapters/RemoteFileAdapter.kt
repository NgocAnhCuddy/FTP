package com.myfile.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.myfile.ui.R
import com.myfile.ui.databinding.ItemRemoteFileBinding
import com.myfile.ui.model.RemoteFile
import java.text.DecimalFormat

/**
 * Dùng ListAdapter + DiffUtil thay vì notifyDataSetChanged(): mỗi lần đổi thư mục chỉ
 * rebind đúng những dòng thực sự thay đổi, thay vì vẽ lại toàn bộ danh sách — đỡ giật/lag
 * trên chip tầm trung (MediaTek Helio, Exynos tầm trung) khi thư mục có nhiều file.
 */
class RemoteFileAdapter(
    private val onItemClick: (RemoteFile) -> Unit,
    private val onMoreClick: (RemoteFile, android.view.View) -> Unit,
    /** true = file thường (không chỉ thư mục) cũng bấm mở/tải được (CloudBrowserActivity).
     *  false = chỉ thư mục bấm được, file chỉ hiện để xem (FolderPickerActivity chọn đích). */
    private val filesClickable: Boolean = true
) : ListAdapter<RemoteFile, RemoteFileAdapter.VH>(DIFF) {

    init {
        setHasStableIds(true)
    }

    fun submit(newItems: List<RemoteFile>) {
        submitList(newItems.toList())
    }

    inner class VH(val binding: ItemRemoteFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemId(position: Int): Long = getItem(position).path.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemRemoteFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val file = getItem(position)
        holder.binding.tvFileName.text = file.name
        holder.binding.ivIcon.setImageResource(
            if (file.isDirectory) com.myfile.ui.util.FolderIcons.iconFor(file.name) else R.drawable.ic_file
        )
        holder.binding.tvFileMeta.text = if (file.isDirectory) "" else formatSize(file.size)
        // Ở CloudBrowserActivity (xem/tải file thật), file phải bấm mở được bình thường.
        // Ở FolderPickerActivity (chọn thư mục đích), file chỉ để xem, không bấm mở được —
        // điều khiển qua filesClickable để dùng chung 1 adapter cho cả 2 màn.
        val clickable = file.isDirectory || filesClickable
        holder.binding.root.alpha = if (clickable) 1f else 0.55f
        holder.binding.root.isClickable = clickable
        holder.binding.root.setOnClickListener { if (clickable) onItemClick(file) }
        holder.binding.btnMore.setOnClickListener { onMoreClick(file, it) }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val safeGroup = digitGroups.coerceIn(0, units.size - 1)
        return DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, safeGroup.toDouble())) + " " + units[safeGroup]
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RemoteFile>() {
            override fun areItemsTheSame(oldItem: RemoteFile, newItem: RemoteFile) = oldItem.path == newItem.path
            override fun areContentsTheSame(oldItem: RemoteFile, newItem: RemoteFile) = oldItem == newItem
        }
    }
}
