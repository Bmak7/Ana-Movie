package com.faselhd.app.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.example.myapplication.R
import com.faselhd.app.models.SAnime

class AnimeResultsAdapter(
    private val animeList: List<SAnime>,
    private val onAnimeClick: (SAnime) -> Unit
) : RecyclerView.Adapter<AnimeResultsAdapter.AnimeViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnimeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_anime_horizontal, parent, false)
        return AnimeViewHolder(view)
    }

    override fun onBindViewHolder(holder: AnimeViewHolder, position: Int) {
        holder.bind(animeList[position], onAnimeClick)
    }

    override fun getItemCount(): Int = animeList.size

    class AnimeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val animeImage: ImageView = itemView.findViewById(R.id.anime_image)
        private val animeTitle: TextView = itemView.findViewById(R.id.anime_title)
//        private val animeYear: TextView = itemView.findViewById(R.id.anime_year)
        private val animeRating: TextView = itemView.findViewById(R.id.anime_rating)

        fun bind(anime: SAnime, onAnimeClick: (SAnime) -> Unit) {
            animeTitle.text = anime.title
            Glide.with(itemView.context)
                .load(anime.thumbnail_url)
                .apply(
                    RequestOptions()
                        .placeholder(R.drawable.placeholder_anime)
                        .error(R.drawable.placeholder_anime)
                        .centerCrop()
                )
                .into(animeImage)

            itemView.setOnClickListener { onAnimeClick(anime) }
        }
    }
}