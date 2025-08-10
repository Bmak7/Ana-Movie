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
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.myapplication.R
import com.faselhd.app.models.SAnime

class AnimeAdapter(
    private val viewType: ViewType,
    private val onItemClick: (SAnime) -> Unit
) : ListAdapter<SAnime, AnimeAdapter.AnimeViewHolder>(AnimeDiffCallback()) {

    enum class ViewType {
        HORIZONTAL, GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnimeViewHolder {
        val layoutId = when (this.viewType) {
            ViewType.HORIZONTAL -> R.layout.item_anime_horizontal
            ViewType.GRID -> R.layout.item_anime_grid
        }

        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return AnimeViewHolder(view)
    }

    override fun onBindViewHolder(holder: AnimeViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AnimeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val animeImage: ImageView = itemView.findViewById(R.id.anime_image)
        private val animeTitle: TextView = itemView.findViewById(R.id.anime_title)
        private val animeGenre: TextView = itemView.findViewById(R.id.anime_genre)
        private val animeStatus: TextView = itemView.findViewById(R.id.anime_status)

        init {
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }
        }

        fun bind(anime: SAnime) {
            animeTitle.text = anime.title ?: "عنوان غير متوفر"
            animeGenre.text = anime.genre ?: "نوع غير محدد"

            // Set status
            animeStatus.text = when (anime.status) {
                SAnime.ONGOING -> itemView.context.getString(R.string.status_ongoing)
                SAnime.COMPLETED -> itemView.context.getString(R.string.status_completed)
                else -> itemView.context.getString(R.string.status_unknown)
            }

            // Load image with Glide
            Glide.with(itemView.context)
                .load(anime.thumbnail_url)
                .placeholder(R.drawable.placeholder_anime)
                .error(R.drawable.placeholder_anime)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(animeImage)
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

