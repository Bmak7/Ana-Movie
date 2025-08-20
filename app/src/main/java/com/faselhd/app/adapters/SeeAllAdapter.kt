package com.faselhd.app.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.faselhd.app.models.SAnime

class SeeAllAdapter(
    private val onItemClick: (SAnime) -> Unit
) : ListAdapter<SAnime, SeeAllAdapter.SeeAllViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeeAllViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_see_all_anime, parent, false)
        return SeeAllViewHolder(view)
    }

    override fun onBindViewHolder(holder: SeeAllViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class SeeAllViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.anime_title)
        private val metadata: TextView = itemView.findViewById(R.id.anime_metadata)
        private val genre: TextView = itemView.findViewById(R.id.anime_genre)
        private val rank: TextView = itemView.findViewById(R.id.anime_rank)
        private val rating: TextView = itemView.findViewById(R.id.anime_rating)
        private val image: ImageView = itemView.findViewById(R.id.anime_image)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }
        }

        fun bind(anime: SAnime, position: Int) {
            title.text = anime.title
            genre.text = "Genre: ${anime.genre ?: "N/A"}"
            metadata.text = "${anime.status ?: "2022"} | Japan" // You might need to add year/country to your model
            rank.text = (position + 1).toString()
            rating.text = "N/A" ?: "9.8"

            Glide.with(itemView.context).load(anime.thumbnail_url).into(image)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<SAnime>() {
        override fun areItemsTheSame(oldItem: SAnime, newItem: SAnime): Boolean = oldItem.url == newItem.url
        override fun areContentsTheSame(oldItem: SAnime, newItem: SAnime): Boolean = oldItem == newItem
    }
}