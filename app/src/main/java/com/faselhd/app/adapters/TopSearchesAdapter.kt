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

class TopSearchesAdapter(
    private val onItemClick: (SAnime) -> Unit
) : ListAdapter<SAnime, TopSearchesAdapter.TopSearchViewHolder>(AnimeDiffCallback()) {

    /**
     * Creates and returns a ViewHolder for the item_top_search layout.
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TopSearchViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_top_search, parent, false)
        return TopSearchViewHolder(view)
    }

    /**
     * Binds the data from an SAnime object to the views in the ViewHolder.
     */
    override fun onBindViewHolder(holder: TopSearchViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * The ViewHolder that holds the views for each top search item.
     */
    inner class TopSearchViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val animeImage: ImageView = itemView.findViewById(R.id.anime_image)
        private val animeTitle: TextView = itemView.findViewById(R.id.anime_title)

        init {
            // Set a click listener on the entire item view
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }
        }

        /**
         * Populates the views with data from the SAnime object.
         */
        fun bind(anime: SAnime) {
            animeTitle.text = anime.title

            // Use Glide to load the image
            Glide.with(itemView.context)
                .load(anime.thumbnail_url)
                // It's a good practice to have a placeholder with the correct aspect ratio
                .placeholder(R.drawable.placeholder_anime) // You can create a landscape version if you like
                .error(R.drawable.placeholder_anime)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(animeImage)
        }
    }

    /**
     * DiffUtil callback to efficiently update the list.
     */
    private class AnimeDiffCallback : DiffUtil.ItemCallback<SAnime>() {
        override fun areItemsTheSame(oldItem: SAnime, newItem: SAnime): Boolean {
            return oldItem.url == newItem.url
        }

        override fun areContentsTheSame(oldItem: SAnime, newItem: SAnime): Boolean {
            return oldItem == newItem
        }
    }
}