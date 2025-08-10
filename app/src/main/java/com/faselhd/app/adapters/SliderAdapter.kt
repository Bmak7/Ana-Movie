package com.faselhd.app.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.faselhd.app.models.SAnime

class SliderAdapter(
    private val items: List<SAnime>,
    private val onClick: (SAnime) -> Unit
) : RecyclerView.Adapter<SliderAdapter.SliderViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SliderViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_slider, parent, false)
        return SliderViewHolder(view)
    }

    override fun onBindViewHolder(holder: SliderViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class SliderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.slider_image)
        private val titleView: TextView = itemView.findViewById(R.id.slider_title)

        init {
            itemView.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onClick(items[adapterPosition])
                }
            }
        }

        fun bind(item: SAnime) {
            titleView.text = item.title
            Glide.with(itemView.context)
                .load(item.thumbnail_url)
                .placeholder(R.drawable.placeholder_anime)
                .into(imageView)
        }
    }
}