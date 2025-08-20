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

    // 1. Expanded the enum to include the new view types
    enum class ViewType {
        HORIZONTAL,
        GRID,
        TOP_HIT,
        NEW_RELEASE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnimeViewHolder {
        // 2. Inflate the correct layout based on the adapter's viewType
        val layoutId = when (this.viewType) {
            ViewType.HORIZONTAL -> R.layout.item_anime_horizontal
            ViewType.GRID -> R.layout.item_anime_grid
            ViewType.TOP_HIT -> R.layout.item_top_hit
            ViewType.NEW_RELEASE -> R.layout.item_new_release
        }

        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        // Pass the viewType to the ViewHolder so it knows which views to find
        return AnimeViewHolder(view, this.viewType)
    }

    override fun onBindViewHolder(holder: AnimeViewHolder, position: Int) {
        // Pass the position to bind for ranking in TOP_HIT
        holder.bind(getItem(position), position)
    }

    // 3. The ViewHolder is now more flexible
    inner class AnimeViewHolder(itemView: View, viewType: ViewType) : RecyclerView.ViewHolder(itemView) {

        // --- Declare all possible views from all layouts as nullable ---
        // This prevents crashes if a view doesn't exist in a particular layout.
        private val animeImage: ImageView? = itemView.findViewById(R.id.anime_image)

        // Views for HORIZONTAL/GRID
        private var animeTitle: TextView? = null
        private var animeGenre: TextView? = null
        private var animeStatus: TextView? = null

        // Views for TOP_HIT/NEW_RELEASE
        private var animeRating: TextView? = null

        // View for TOP_HIT only
        private var animeRank: TextView? = null

        init {
            // --- Find views based on the layout type ---
            when (viewType) {
                ViewType.HORIZONTAL, ViewType.GRID -> {
                    animeTitle = itemView.findViewById(R.id.anime_title)
                    animeGenre = itemView.findViewById(R.id.anime_genre)
                    animeStatus = itemView.findViewById(R.id.anime_status)
                }
                ViewType.TOP_HIT -> {
                    animeRank = itemView.findViewById(R.id.anime_rank)
                    animeRating = itemView.findViewById(R.id.anime_rating)
                }
                ViewType.NEW_RELEASE -> {
                    animeRating = itemView.findViewById(R.id.anime_rating)
                }
            }

            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }
        }

        // 4. The bind method now handles the logic for all layouts
        fun bind(anime: SAnime, position: Int) {
            // Load image (common to all layouts)
            Glide.with(itemView.context)
                .load(anime.thumbnail_url)
                .placeholder(R.drawable.placeholder_anime) // Consider creating different placeholders for different aspect ratios
                .error(R.drawable.placeholder_anime)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(animeImage!!) // Assuming every layout will have an anime_image

            // Bind data based on the specific layout
            when (viewType) {
                ViewType.HORIZONTAL -> {
                    animeTitle?.text = anime.title ?: "No Title"
                    animeGenre?.text = anime.genre ?: "No Genre"
                    // ... (status logic)
                }
                ViewType.GRID -> {
                    // BIND DATA FOR GRID
                    animeTitle?.text = anime.title ?: "No Title"
                    animeRating?.text = "N/A" ?: "N/A" // Use rating from your SAnime model
                }
                ViewType.TOP_HIT -> {
                    animeRank?.text = (position + 1).toString()
                    // Note: You may need to add a `rating` field to your SAnime model
                    animeRating?.text =  "9.8" // Placeholder
                }
                ViewType.NEW_RELEASE -> {
                    // Note: You may need to add a `rating` field to your SAnime model
                    animeRating?.text =  "9.5" // Placeholder
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