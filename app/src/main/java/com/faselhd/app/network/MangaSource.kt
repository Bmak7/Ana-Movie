//package com.faselhd.app.network
//
//import android.content.Context
//import com.faselhd.app.models.*
//import com.faselhd.app.network.manga.sources.*
//
//enum class MangaSource(val displayName: String) {
//    MANGA_CLASH("MANGA CLASH"),
////    MANGA_READ("MANGA READ"),
////    MANGA_KAKALOT("MANGA KAKALOT"),
////    MANGA_NELO("MANGA NELO"),
////    READ_MANGA("READ MANGA"),
////    MANGA_PARK("MANGA PARK")
//}
//
//class MangaSourceManager(private val context: Context) {
//
//    companion object {
//        private const val PREFS_NAME = "manga_source_manager_prefs"
//        private const val KEY_SELECTED_SOURCE = "selected_manga_source"
//        private const val DEFAULT_SOURCE = "MANGA_CLASH"
//
//        fun getSelectedSource(context: Context): MangaSource {
//            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
//            val sourceName = prefs.getString(KEY_SELECTED_SOURCE, DEFAULT_SOURCE) ?: DEFAULT_SOURCE
//            return try {
//                MangaSource.valueOf(sourceName)
//            } catch (e: IllegalArgumentException) {
//                MangaSource.MANGA_CLASH
//            }
//        }
//
//        fun setSelectedSource(context: Context, source: MangaSource) {
//            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
//            prefs.edit().putString(KEY_SELECTED_SOURCE, source.name).apply()
//        }
//    }
//
//    // Initialize source implementations
//    private val mangaClashSource by lazy { MangaClashSource(context) }
////    private val mangaReadSource by lazy { MangaReadSource(context) }
////    private val mangaKakalotSource by lazy { MangaKakalotSource(context) }
////    private val mangaNeloSource by lazy { MangaNeloSource(context) }
////    private val readMangaSource by lazy { ReadMangaSource(context) }
////    private val mangaParkSource by lazy { MangaParkSource(context) }
//
//    private val currentSource: MangaSource
//        get() = getSelectedSource(context)
//
//    private fun getSource(specificSource: MangaSource?): MangaSource {
//        return specificSource ?: currentSource
//    }
//
//    suspend fun fetchPopularManga(page: Int): MangaPagee{
//        return when (currentSource) {
//            MangaSource.MANGA_CLASH -> mangaClashSource.fetchPopularManga(page)
////            MangaSource.MANGA_READ -> mangaReadSource.fetchPopularManga(page)
////            MangaSource.MANGA_KAKALOT -> mangaKakalotSource.fetchPopularManga(page)
////            MangaSource.MANGA_NELO -> mangaNeloSource.fetchPopularManga(page)
////            MangaSource.READ_MANGA -> readMangaSource.fetchPopularManga(page)
////            MangaSource.MANGA_PARK -> mangaParkSource.fetchPopularManga(page)
//        }
//    }
//
//    suspend fun fetchLatestUpdates(page: Int): MangaPagee {
//        return when (currentSource) {
//            MangaSource.MANGA_CLASH -> mangaClashSource.fetchLatestUpdates(page)
////            MangaSource.MANGA_READ -> mangaReadSource.fetchLatestUpdates(page)
////            MangaSource.MANGA_KAKALOT -> mangaKakalotSource.fetchLatestUpdates(page)
////            MangaSource.MANGA_NELO -> mangaNeloSource.fetchLatestUpdates(page)
////            MangaSource.READ_MANGA -> readMangaSource.fetchLatestUpdates(page)
////            MangaSource.MANGA_PARK -> mangaParkSource.fetchLatestUpdates(page)
//        }
//    }
//
//    suspend fun fetchSearchManga(
//        page: Int,
//        query: String,
//        filters: MangaFilterList,
//        source: MangaSource? = null
//    ): MangaPagee {
//        return when (getSource(source)) {
//            MangaSource.MANGA_CLASH -> mangaClashSource.fetchSearchManga(page, query, filters)
////            MangaSource.MANGA_READ -> mangaReadSource.fetchSearchManga(page, query, filters)
////            MangaSource.MANGA_KAKALOT -> mangaKakalotSource.fetchSearchManga(page, query, filters)
////            MangaSource.MANGA_NELO -> mangaNeloSource.fetchSearchManga(page, query, filters)
////            MangaSource.READ_MANGA -> readMangaSource.fetchSearchManga(page, query, filters)
////            MangaSource.MANGA_PARK -> mangaParkSource.fetchSearchManga(page, query, filters)
//        }
//    }
//
//    suspend fun fetchMangaDetails(mangaUrl: String, source: MangaSource? = null): SManga {
//        return when (getSource(source)) {
//            MangaSource.MANGA_CLASH -> mangaClashSource.fetchMangaDetails(mangaUrl)
////            MangaSource.MANGA_READ -> mangaReadSource.fetchMangaDetails(mangaUrl)
////            MangaSource.MANGA_KAKALOT -> mangaKakalotSource.fetchMangaDetails(mangaUrl)
////            MangaSource.MANGA_NELO -> mangaNeloSource.fetchMangaDetails(mangaUrl)
////            MangaSource.READ_MANGA -> readMangaSource.fetchMangaDetails(mangaUrl)
////            MangaSource.MANGA_PARK -> mangaParkSource.fetchMangaDetails(mangaUrl)
//        }
//    }
//
//    suspend fun fetchChapterList(mangaUrl: String, source: MangaSource? = null): List<SChapter> {
//        return when (getSource(source)) {
//            MangaSource.MANGA_CLASH -> mangaClashSource.fetchChapterList(mangaUrl)
////            MangaSource.MANGA_READ -> mangaReadSource.fetchChapterList(mangaUrl)
////            MangaSource.MANGA_KAKALOT -> mangaKakalotSource.fetchChapterList(mangaUrl)
////            MangaSource.MANGA_NELO -> mangaNeloSource.fetchChapterList(mangaUrl)
////            MangaSource.READ_MANGA -> readMangaSource.fetchChapterList(mangaUrl)
////            MangaSource.MANGA_PARK -> mangaParkSource.fetchChapterList(mangaUrl)
//        }
//    }
//
//    suspend fun fetchPageList(chapterUrl: String, source: MangaSource? = null): List<SPage> {
//        return when (getSource(source)) {
//            MangaSource.MANGA_CLASH -> mangaClashSource.fetchPageList(chapterUrl)
////            MangaSource.MANGA_READ -> mangaReadSource.fetchPageList(chapterUrl)
////            MangaSource.MANGA_KAKALOT -> mangaKakalotSource.fetchPageList(chapterUrl)
////            MangaSource.MANGA_NELO -> mangaNeloSource.fetchPageList(chapterUrl)
////            MangaSource.READ_MANGA -> readMangaSource.fetchPageList(chapterUrl)
////            MangaSource.MANGA_PARK -> mangaParkSource.fetchPageList(chapterUrl)
//        }
//    }
//
//    // Manga-specific methods
//    suspend fun fetchFeaturedManga(): List<SManga> {
//        return when (currentSource) {
//            MangaSource.MANGA_CLASH -> mangaClashSource.fetchFeaturedManga()
////            MangaSource.MANGA_READ -> mangaReadSource.fetchFeaturedManga()
////            MangaSource.MANGA_KAKALOT -> mangaKakalotSource.fetchFeaturedManga()
////            MangaSource.MANGA_NELO -> mangaNeloSource.fetchFeaturedManga()
////            MangaSource.READ_MANGA -> readMangaSource.fetchFeaturedManga()
////            MangaSource.MANGA_PARK -> mangaParkSource.fetchFeaturedManga()
//        }
//    }
//
//    suspend fun fetchRecentlyUpdated(): List<SManga> {
//        return when (currentSource) {
//            MangaSource.MANGA_CLASH -> mangaClashSource.fetchRecentlyUpdated()
////            MangaSource.MANGA_READ -> mangaReadSource.fetchRecentlyUpdated()
////            MangaSource.MANGA_KAKALOT -> mangaKakalotSource.fetchRecentlyUpdated()
////            MangaSource.MANGA_NELO -> mangaNeloSource.fetchRecentlyUpdated()
////            MangaSource.READ_MANGA -> readMangaSource.fetchRecentlyUpdated()
////            MangaSource.MANGA_PARK -> mangaParkSource.fetchRecentlyUpdated()
//        }
//    }
//
//    fun getFilterList(): MangaFilterList {
//        return when (currentSource) {
//            MangaSource.MANGA_CLASH -> mangaClashSource.getFilterList()
////            MangaSource.MANGA_READ -> mangaReadSource.getFilterList()
////            MangaSource.MANGA_KAKALOT -> mangaKakalotSource.getFilterList()
////            MangaSource.MANGA_NELO -> mangaNeloSource.getFilterList()
////            MangaSource.READ_MANGA -> readMangaSource.getFilterList()
////            MangaSource.MANGA_PARK -> mangaParkSource.getFilterList()
//        }
//    }
//
//    fun getCurrentSourceName(): String {
//        return currentSource.displayName
//    }
//
//    fun getAllSources(): List<MangaSource> {
//        return MangaSource.values().toList()
//    }
//}
