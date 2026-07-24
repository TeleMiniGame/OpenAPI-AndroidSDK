package com.unite.sdk.demo.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.unite.sdk.demo.databinding.ItemCardBinding
import com.unite.sdk.demo.model.DemoItem

class CardAdapter(
    private val list: List<DemoItem>
) : RecyclerView.Adapter<CardAdapter.VH>() {

    inner class VH(val binding: ItemCardBinding)
        : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {

        val binding = ItemCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )

        return VH(binding)
    }

    override fun getItemCount() = list.size

    override fun onBindViewHolder(holder: VH, position: Int) {

        val item = list[position]

        holder.binding.cardText.text = item.title
    }
}