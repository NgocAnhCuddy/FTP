package com.myfile.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.myfile.ui.R
import com.myfile.ui.databinding.ItemRemoteDidlBinding
import com.myfile.ui.dlna.RemoteDidlItem

/** Danh sách thư mục/file trong 1 máy chủ DLNA từ xa đang duyệt — bấm thư mục để vào sâu hơn, bấm file để phát. */
class RemoteDidlAdapter(
    private val onClick: (RemoteDidlItem) -> Unit
) : RecyclerView.Adapter<RemoteDidlAdapter.VH>() {

    private val entries = mutableListOf<RemoteDidlItem>()

    fun submitList(newEntries: List<RemoteDidlItem>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemRemoteDidlBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = entries[position]
        holder.binding.tvTitle.text = entry.title
        val icon = when {
            entry.isContainer -> R.drawable.ic_folder
            entry.mimeType?.startsWith("video") == true -> R.drawable.ic_cat_video
            entry.mimeType?.startsWith("audio") == true -> R.drawable.ic_cat_audio
            entry.mimeType?.startsWith("image") == true -> R.drawable.ic_cat_photo
            else -> R.drawable.ic_cat_doc
        }
        holder.binding.ivIcon.setImageResource(icon)
        holder.itemView.setOnClickListener { onClick(entry) }
    }

    override fun getItemCount(): Int = entries.size

    class VH(val binding: ItemRemoteDidlBinding) : RecyclerView.ViewHolder(binding.root)
}
