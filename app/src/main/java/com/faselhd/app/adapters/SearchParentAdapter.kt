package com.faselhd.app.adapters

import HorizontalSpacingItemDecoration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.faselhd.app.models.SAnime
import com.faselhd.app.network.AnimeSource

// Data class to hold the results for one source
data class SourceSearchResult(val source: AnimeSource, val results: List<SAnime>)

class SearchParentAdapter(
    private val onAnimeClick: (SAnime) -> Unit,
    private val onSeeAllClick: (SourceSearchResult) -> Unit
) : RecyclerView.Adapter<SearchParentAdapter.ViewHolder>() {

    private val list = mutableListOf<SourceSearchResult>()

    fun updateData(newList: List<SourceSearchResult>) {
        list.clear()
        list.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result_parent, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(list[position])
    }

    override fun getItemCount(): Int = list.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val sourceHeader: View = itemView.findViewById(R.id.source_header_layout)
        private val sourceTitle: TextView = itemView.findViewById(R.id.source_title)
        private val childRecyclerView: RecyclerView = itemView.findViewById(R.id.child_recycler_view)

        fun bind(sourceResult: SourceSearchResult) {
            sourceTitle.text = "${sourceResult.source.displayName} •"
            sourceHeader.setOnClickListener { onSeeAllClick(sourceResult) }

            // Setup the child RecyclerView
            val childAdapter = AnimeAdapter(AnimeAdapter.ViewType.GRID, onAnimeClick)

            childRecyclerView.layoutManager = LinearLayoutManager(itemView.context, LinearLayoutManager.HORIZONTAL, false)
            childRecyclerView.adapter = childAdapter

            // --- NEW CODE TO ADD SPACING ---

            // Get the spacing value from your dimens.xml file
            val spacing = itemView.context.resources.getDimensionPixelSize(R.dimen.grid_spacing)

            // Apply the decoration, but only if one doesn't already exist.
            if (childRecyclerView.itemDecorationCount == 0) {
                childRecyclerView.addItemDecoration(HorizontalSpacingItemDecoration(spacing))
            }
            // --- END OF NEW CODE ---

            childAdapter.submitList(sourceResult.results)
        }
    }
}