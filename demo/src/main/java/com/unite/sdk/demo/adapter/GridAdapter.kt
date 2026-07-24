package com.unite.sdk.demo.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.unite.sdk.demo.databinding.ItemGridBinding
import com.unite.sdk.demo.model.DemoItem

class GridAdapter(
    private val list: List<DemoItem>
) : RecyclerView.Adapter<GridAdapter.VH>() {

    inner class VH(val binding: ItemGridBinding)
        : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {

        val binding = ItemGridBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )

        return VH(binding)
    }

    override fun getItemCount() = list.size

    override fun onBindViewHolder(holder: VH, position: Int) {

        val item = list[position]

        holder.binding.gridText.text = item.title
    }
}