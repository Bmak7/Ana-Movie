//// Add these to your adapters package
//
//package com.faselhd.app.adapters
//
//import android.view.LayoutInflater
//import android.view.View
//import android.view.ViewGroup
//import android.widget.ImageView
//import android.widget.TextView
//import androidx.recyclerview.widget.DiffUtil
//import androidx.recyclerview.widget.ListAdapter
//import androidx.recyclerview.widget.RecyclerView
//import com.bumptech.glide.Glide
//import com.example.myapplication.R
//import com.faselhd.app.models.*
//
//// Manga Grid/List Adapter (similar to AnimeAdapter)
//class MangaAdapter(
//    private val viewType: ViewType,
//    private val onMangaClick: (SManga) -> Unit
//) : ListAdapter<SManga, MangaAdapter.ViewHolder>(MangaDiffCallback()) {
//
//    enum class ViewType {
//        GRID, LIST, POPULAR, RECENT
//    }
//
//    override fun getItemViewType(position: Int): Int = viewType.ordinal
//
//    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
//        val layoutId = when (ViewType.values()[viewType]) {
//            ViewType.GRID -> R.layout.item_manga_grid
//            ViewType.LIST -> R.layout.item_manga_list
//            ViewType.POPULAR -> R.layout.item_manga_popular
//            ViewType.RECENT -> R.layout.item_manga_recent
//        }
//        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
//        return ViewHolder(view, onMangaClick)
//    }
//
//    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
//        holder.bind(getItem(position))
//    }
//
//    class ViewHolder(
//        itemView: View,
//        private val onMangaClick: (SManga) -> Unit
//    ) : RecyclerView.ViewHolder(itemView) {
//
//        private val image: ImageView = itemView.findViewById(R.id.manga_image)
//        private val title: TextView = itemView.findViewById(R.id.manga_title)
//        private val author: TextView? = itemView.findViewById(R.id.manga_author)
//        private val status: TextView? = itemView.findViewById(R.id.manga_status)
//        private val genre: TextView? = itemView.findViewById(R.id.manga_genre)
//
//        fun bind(manga: SManga) {
//            title.text = manga.title
//            author?.text = manga.author
//            status?.text = manga.status
//            genre?.text = manga.genre
//
//            Glide.with(itemView.context)
//                .load(manga.thumbnail_url)
//                .placeholder(R.drawable.placeholder_anime)
//                .error(R.drawable.placeholder_anime)
//                .into(image)
//
//            itemView.setOnClickListener { onMangaClick(manga) }
//        }
//    }
//
//    private class MangaDiffCallback : DiffUtil.ItemCallback<SManga>() {
//        override fun areItemsTheSame(oldItem: SManga, newItem: SManga): Boolean {
//            return oldItem.url == newItem.url
//        }
//
//        override fun areContentsTheSame(oldItem: SManga, newItem: SManga): Boolean {
//            return oldItem == newItem
//        }
//    }
//}
//
//// Chapter List Adapter
//class ChapterAdapter(
//    private val onChapterClick: (SChapter) -> Unit,
//    private val onChapterDownload: (SChapter) -> Unit
//) : ListAdapter<ChapterWithHistory, ChapterAdapter.ViewHolder>(ChapterDiffCallback()) {
//
//    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
//        val view = LayoutInflater.from(parent.context)
//            .inflate(R.layout.item_chapter, parent, false)
//        return ViewHolder(view, onChapterClick, onChapterDownload)
//    }
//
//    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
//        holder.bind(getItem(position))
//    }
//
//    class ViewHolder(
//        itemView: View,
//        private val onChapterClick: (SChapter) -> Unit,
//        private val onChapterDownload: (SChapter) -> Unit
//    ) : RecyclerView.ViewHolder(itemView) {
//
//        private val title: TextView = itemView.findViewById(R.id.chapter_title)
//        private val date: TextView = itemView.findViewById(R.id.chapter_date)
//        private val scanlator: TextView = itemView.findViewById(R.id.chapter_scanlator)
//        private val downloadButton: ImageView = itemView.findViewById(R.id.btn_download_chapter)
//        private val readProgress: View = itemView.findViewById(R.id.read_progress_indicator)
//
//        fun bind(chapterWithHistory: ChapterWithHistory) {
//            val chapter = chapterWithHistory.chapter
//            val history = chapterWithHistory.history
//
//            title.text = chapter.name
//
//            if (chapter.date_upload > 0) {
//                date.text = android.text.format.DateUtils.getRelativeTimeSpanString(
//                    chapter.date_upload,
//                    System.currentTimeMillis(),
//                    android.text.format.DateUtils.DAY_IN_MILLIS
//                )
//                date.visibility = View.VISIBLE
//            } else {
//                date.visibility = View.GONE
//            }
//
//            scanlator.text = chapter.scanlator
//            scanlator.visibility = if (chapter.scanlator.isNullOrEmpty()) View.GONE else View.VISIBLE
//
//            // Show read progress
//            readProgress.visibility = if (history != null) View.VISIBLE else View.GONE
//
//            // Set alpha based on read status
//            itemView.alpha = if (history?.isFinished == true) 0.6f else 1.0f
//
//            itemView.setOnClickListener { onChapterClick(chapter) }
//            downloadButton.setOnClickListener { onChapterDownload(chapter) }
//        }
//    }
//
//    private class ChapterDiffCallback : DiffUtil.ItemCallback<ChapterWithHistory>() {
//        override fun areItemsTheSame(oldItem: ChapterWithHistory, newItem: ChapterWithHistory): Boolean {
//            return oldItem.chapter.url == newItem.chapter.url
//        }
//
//        override fun areContentsTheSame(oldItem: ChapterWithHistory, newItem: ChapterWithHistory): Boolean {
//            return oldItem == newItem
//        }
//    }
//}
//
//// Manga Page Adapters for Reader
//class MangaPageAdapter(
//    private val onPageClick: () -> Unit
//) : ListAdapter<SPage, MangaPageAdapter.ViewHolder>(PageDiffCallback()) {
//
//    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
//        val view = LayoutInflater.from(parent.context)
//            .inflate(R.layout.item_manga_page_horizontal, parent, false)
//        return ViewHolder(view, onPageClick)
//    }
//
//    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
//        holder.bind(getItem(position))
//    }
//
//    class ViewHolder(
//        itemView: View,
//        private val onPageClick: () -> Unit
//    ) : RecyclerView.ViewHolder(itemView) {
//
//        private val pageImage: ImageView = itemView.findViewById(R.id.page_image)
//
//        fun bind(page: SPage) {
//            Glide.with(itemView.context)
//                .load(page.imageUrl)
//                .placeholder(R.drawable.placeholder_page)
//                .error(R.drawable.error_page)
//                .into(pageImage)
//
//            itemView.setOnClickListener { onPageClick() }
//        }
//    }
//
//    private class PageDiffCallback : DiffUtil.ItemCallback<SPage>() {
//        override fun areItemsTheSame(oldItem: SPage, newItem: SPage): Boolean {
//            return oldItem.index == newItem.index
//        }
//
//        override fun areContentsTheSame(oldItem: SPage, newItem: SPage): Boolean {
//            return oldItem == newItem
//        }
//    }
//}
//
//class MangaVerticalAdapter(
//    private val onPageClick: () -> Unit
//) : ListAdapter<SPage, MangaVerticalAdapter.ViewHolder>(PageDiffCallback()) {
//
//    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
//        val view = LayoutInflater.from(parent.context)
//            .inflate(R.layout.item_manga_page_vertical, parent, false)
//        return ViewHolder(view, onPageClick)
//    }
//
//    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
//        holder.bind(getItem(position))
//    }
//
//    class ViewHolder(
//        itemView: View,
//        private val onPageClick: () -> Unit
//    ) : RecyclerView.ViewHolder(itemView) {
//
//        private val pageImage: ImageView = itemView.findViewById(R.id.page_image)
//
//        fun bind(page: SPage) {
//            Glide.with(itemView.context)
//                .load(page.imageUrl)
//                .placeholder(R.drawable.placeholder_page)
//                .error(R.drawable.error_page)
//                .into(pageImage)
//
//            itemView.setOnClickListener { onPageClick() }
//        }
//    }
//
//    private class PageDiffCallback : DiffUtil.ItemCallback<SPage>() {
//        override fun areItemsTheSame(oldItem: SPage, newItem: SPage): Boolean {
//            return oldItem.index == newItem.index
//        }
//
//        override fun areContentsTheSame(oldItem: SPage, newItem: SPage): Boolean {
//            return oldItem == newItem
//        }
//    }
//}
//
//// Continue Reading Adapter (for homepage)
//class ContinueMangaAdapter(
//    private val onMangaClick: (MangaReadHistory) -> Unit
//) : ListAdapter<MangaReadHistory, ContinueMangaAdapter.ViewHolder>(ReadHistoryDiffCallback()) {
//
//    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
//        val view = LayoutInflater.from(parent.context)
//            .inflate(R.layout.item_continue_reading, parent, false)
//        return ViewHolder(view, onMangaClick)
//    }
//
//    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
//        holder.bind(getItem(position))
//    }
//
//    class ViewHolder(
//        itemView: View,
//        private val onMangaClick: (MangaReadHistory) -> Unit
//    ) : RecyclerView.ViewHolder(itemView) {
//
//        private val thumbnail: ImageView = itemView.findViewById(R.id.manga_thumbnail)
//        private val title: TextView = itemView.findViewById(R.id.manga_title)
//        private val chapterName: TextView = itemView.findViewById(R.id.chapter_name)
//        private val progress: TextView = itemView.findViewById(R.id.reading_progress)
//
//        fun bind(history: MangaReadHistory) {
//            title.text = history.mangaTitle
//            chapterName.text = history.chapterName
//
//            val progressText = if (history.totalPages > 0) {
//                "${history.lastReadPage + 1}/${history.totalPages}"
//            } else {
//                "Page ${history.lastReadPage + 1}"
//            }
//            progress.text = progressText
//
//            Glide.with(itemView.context)
//                .load(history.mangaThumbnailUrl)
//                .placeholder(R.drawable.placeholder_anime)
//                .error(R.drawable.placeholder_anime)
//                .into(thumbnail)
//
//            itemView.setOnClickListener { onMangaClick(history) }
//        }
//    }
//
//    private class ReadHistoryDiffCallback : DiffUtil.ItemCallback<MangaReadHistory>() {
//        override fun areItemsTheSame(oldItem: MangaReadHistory, newItem: MangaReadHistory): Boolean {
//            return oldItem.chapterUrl == newItem.chapterUrl
//        }
//
//        override fun areContentsTheSame(oldItem: MangaReadHistory, newItem: MangaReadHistory): Boolean {
//            return oldItem == newItem
//        }
//    }
//}
