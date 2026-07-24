package com.unite.sdk.demo.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.unite.sdk.demo.databinding.ItemDeviceBinding
import com.unite.sdk.demo.model.DemoItem

class DeviceAdapter(
    private val list: List<DemoItem>
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    class VH(val binding: ItemDeviceBinding)
        : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {

        val binding = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )

        return VH(binding)
    }

    override fun getItemCount() = list.size

    override fun onBindViewHolder(holder: VH, position: Int) {

        val item = list[position]

        holder.binding.deviceText.text = item.title
    }
}