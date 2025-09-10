package com.faselhd.app.adapters

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.myapplication.R
import com.faselhd.app.models.SAnime

class AnimeAdapter(
    private val viewType: ViewType,
    private val onItemClick: (SAnime) -> Unit
) : ListAdapter<SAnime, AnimeAdapter.AnimeViewHolder>(AnimeDiffCallback()) {

    enum class ViewType {
        HORIZONTAL,
        GRID,
        TOP_HIT,
        NEW_RELEASE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnimeViewHolder {
        val layoutId = when (this.viewType) {
            ViewType.HORIZONTAL -> R.layout.item_anime_horizontal
            ViewType.GRID -> R.layout.item_anime_grid
            ViewType.TOP_HIT -> R.layout.item_top_hit
            ViewType.NEW_RELEASE -> R.layout.item_new_release
        }

        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return AnimeViewHolder(view, this.viewType)
    }

    override fun onBindViewHolder(holder: AnimeViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class AnimeViewHolder(itemView: View, viewType: ViewType) : RecyclerView.ViewHolder(itemView) {

        private val animeImage: ImageView? = itemView.findViewById(R.id.anime_image)
        private var animeTitle: TextView? = null
        private var animeGenre: TextView? = null
        private var animeRating: TextView? = null
        private var animeRank: TextView? = null

        init {
            when (viewType) {
                ViewType.HORIZONTAL, ViewType.GRID -> {
                    animeTitle = itemView.findViewById(R.id.anime_title)
                    animeGenre = itemView.findViewById(R.id.anime_genre)
                    Log.d("AnimeAdapter", "Initialized HORIZONTAL/GRID: animeTitle=$animeTitle, animeGenre=$animeGenre")
                }
                ViewType.TOP_HIT -> {
                    animeRank = itemView.findViewById(R.id.anime_rank)
                    animeRating = itemView.findViewById(R.id.anime_rating)
                    Log.d("AnimeAdapter", "Initialized TOP_HIT: animeRank=$animeRank, animeRating=$animeRating")
                }
                ViewType.NEW_RELEASE -> {
                    animeTitle = itemView.findViewById(R.id.anime_title)
                    animeRating = itemView.findViewById(R.id.anime_rating)
                    Log.d("AnimeAdapter", "Initialized NEW_RELEASE: animeTitle=$animeTitle, animeRating=$animeRating")
                }
            }

            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }
        }

        fun bind(anime: SAnime, position: Int) {
            Log.d("AnimeAdapter", "Binding item at position $position: title=${anime.title}, thumbnail=${anime.thumbnail_url}, rating=")

            Glide.with(itemView.context)
                .load(anime.thumbnail_url)
                .placeholder(R.drawable.placeholder_anime)
                .error(R.drawable.placeholder_anime)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(animeImage!!)

            when (viewType) {
                ViewType.HORIZONTAL -> {
                    animeTitle?.text = anime.title ?: "No Title"
                    animeGenre?.text = anime.genre ?: "No Genre"
                }
                ViewType.GRID -> {
                    animeTitle?.text = anime.title ?: "No Title"
//                    animeRating?.text = anime.rating ?: "N/A"
                }
                ViewType.TOP_HIT -> {
                    animeRank?.text = (position + 1).toString()
//                    animeRating?.text = anime.rating ?: "N/A"
                }
                ViewType.NEW_RELEASE -> {
                    val titleText = anime.title ?: "No Title"
                    animeTitle?.text = titleText
//                    animeRating?.text = anime.rating ?: "N/A"
                    Log.d("AnimeAdapter", "NEW_RELEASE binding: titleText=$titleText, animeTitle view=$animeTitle")
                }
            }
        }
    }

    private class AnimeDiffCallback : DiffUtil.ItemCallback<SAnime>() {
        override fun areItemsTheSame(oldItem: SAnime, newItem: SAnime): Boolean {
            return oldItem.url == newItem.url
        }

        override fun areContentsTheSame(oldItem: SAnime, newItem: SAnime): Boolean {
            return oldItem == newItem
        }
    }
}