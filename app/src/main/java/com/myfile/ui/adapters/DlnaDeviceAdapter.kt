package com.myfile.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.myfile.ui.databinding.ItemDlnaDeviceBinding
import com.myfile.ui.dlna.DlnaDevice

/** Danh sách thiết bị DLNA (TV/loa) tìm thấy trong LAN, bấm vào 1 dòng để cast tới đó. */
class DlnaDeviceAdapter(
    private val onDeviceClick: (DlnaDevice) -> Unit
) : RecyclerView.Adapter<DlnaDeviceAdapter.VH>() {

    private val devices = mutableListOf<DlnaDevice>()

    fun submitList(newDevices: List<DlnaDevice>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDlnaDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val device = devices[position]
        holder.binding.tvDeviceName.text = device.friendlyName
        holder.itemView.setOnClickListener { onDeviceClick(device) }
    }

    override fun getItemCount(): Int = devices.size

    class VH(val binding: ItemDlnaDeviceBinding) : RecyclerView.ViewHolder(binding.root)
}
