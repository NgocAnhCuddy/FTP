package com.myfile.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.myfile.ui.R
import com.myfile.ui.databinding.ItemRemoteDidlBinding
import com.myfile.ui.dlna.RemoteMediaServer

/** Danh sách máy chủ MediaServer (NAS, điện thoại khác...) tìm thấy trong LAN — bấm vào để bắt đầu duyệt. */
class RemoteServerAdapter(
    private val onClick: (RemoteMediaServer) -> Unit
) : RecyclerView.Adapter<RemoteServerAdapter.VH>() {

    private val servers = mutableListOf<RemoteMediaServer>()

    fun submitList(newServers: List<RemoteMediaServer>) {
        servers.clear()
        servers.addAll(newServers)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemRemoteDidlBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val server = servers[position]
        holder.binding.tvTitle.text = server.friendlyName
        holder.binding.ivIcon.setImageResource(R.drawable.ic_network_storage)
        holder.itemView.setOnClickListener { onClick(server) }
    }

    override fun getItemCount(): Int = servers.size

    class VH(val binding: ItemRemoteDidlBinding) : RecyclerView.ViewHolder(binding.root)
}
