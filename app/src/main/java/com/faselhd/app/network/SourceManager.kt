package com.faselhd.app.network
import android.content.Context
import com.faselhd.app.models.*
enum class AnimeSource(val displayName: String) {
    FASEL_HD("FASEL HD"),
    MY_CIMA("MY CIMA"),
    ARAB_ANIME("ARAB ANIME"),
    ARAB_DRAMA("ARAB DRAMA"),
    OKANIME("OKANIME")
}
class SourceManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "source_manager_prefs"
        private const val KEY_SELECTED_SOURCE = "selected_source"
        private const val DEFAULT_SOURCE = "FASEL_HD"

        fun getSelectedSource(context: Context): AnimeSource {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val sourceName = prefs.getString(KEY_SELECTED_SOURCE, DEFAULT_SOURCE) ?: DEFAULT_SOURCE
            return try {
                AnimeSource.valueOf(sourceName)
            } catch (e: IllegalArgumentException) {
                AnimeSource.FASEL_HD
            }
        }

        // A helper function to decide which source to use



        fun setSelectedSource(context: Context, source: AnimeSource) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_SELECTED_SOURCE, source.name).apply()
        }
    }

    private val faselHDSource by lazy { FaselHDSource(context) }
    private val myCimaSource by lazy { MyCimaSource(context) }
    private val arabAnimeSource by lazy { ArabAnimeSource(context) } // Add this line
    private val okAnimeSource by lazy { OkAnimeSource(context) } // <-- ADD THIS
    private val arabDramSource by lazy { ArabDramaSource(context) } // <-- ADD THIS


    private val currentSource: AnimeSource
        get() = getSelectedSource(context)

    private fun getSource(specificSource: AnimeSource?): AnimeSource {
        return specificSource ?: currentSource
    }

    suspend fun fetchPopularSeries(page: Int): MangaPage {
        return when (currentSource) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchPopularSeries(page)
            AnimeSource.MY_CIMA -> myCimaSource.fetchPopularSeries(page)
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchPopularSeries(page) // Add this case
            AnimeSource.OKANIME -> okAnimeSource.fetchPopularSeries(page) // <-- ADD THIS
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchPopularSeries(page) // <-- ADD THIS
        }
    }

    suspend fun fetchLatestUpdates(page: Int): MangaPage {
        return when (currentSource) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchLatestUpdates(page)
            AnimeSource.MY_CIMA -> myCimaSource.fetchLatestUpdates(page)
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchLatestUpdates(page) // Add this case
            AnimeSource.OKANIME -> okAnimeSource.fetchPopularSeries(page) // <-- ADD THIS
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchPopularSeries(page) // <-- ADD THIS
        }
    }

    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList, type: String): MangaPage {
        return when (currentSource) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchSearchAnime(page, query, filters)
            AnimeSource.MY_CIMA -> myCimaSource.fetchSearchAnime(page, query, filters, type)
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchSearchAnime(page, query, filters) // Add this case
            AnimeSource.OKANIME -> okAnimeSource.fetchSearchAnime(page, query, filters) // <-- ADD THIS
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchSearchAnime(page, query, filters) // <-- ADD THIS
        }
    }

    suspend fun fetchAnimeDetails(animeUrl: String, source: AnimeSource? = null): SAnime {
        return when (getSource(source)) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchAnimeDetails(animeUrl)
            AnimeSource.MY_CIMA -> myCimaSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchAnimeDetails(animeUrl) // Add this case
            AnimeSource.OKANIME -> okAnimeSource.fetchAnimeDetails(animeUrl) // <-- ADD THIS
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchAnimeDetails(animeUrl) // <-- ADD THIS
        }
    }

    suspend fun fetchEpisodeList(animeUrl: String, source: AnimeSource? = null): List<SEpisode> {
        return when (getSource(source)) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchEpisodeList(animeUrl)
            AnimeSource.MY_CIMA -> myCimaSource.fetchEpisodeList(animeUrl)
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchEpisodeList(animeUrl) // Add this case
            AnimeSource.OKANIME -> okAnimeSource.fetchEpisodeList(animeUrl) // <-- ADD THIS
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchEpisodeList(animeUrl) // <-- ADD THIS
        }
    }

    suspend fun fetchVideoList(episodeUrl: String, source: AnimeSource? = null): List<Video> {
        return when (getSource(source)) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchVideoList(episodeUrl)
            AnimeSource.MY_CIMA -> myCimaSource.fetchVideoList(episodeUrl)
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchVideoList(episodeUrl) // Add this case
            AnimeSource.OKANIME -> okAnimeSource.fetchVideoList(episodeUrl) // <-- ADD THIS
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchVideoList(episodeUrl) // <-- ADD THIS
        }
    }

    // FaselHD specific methods
    suspend fun fetchMainSlider(): List<SAnime> {
        return when (currentSource) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchMainSlider()
            AnimeSource.MY_CIMA -> myCimaSource.fetchMainSlider() // MyCima doesn't have slider
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchMainSlider()
            AnimeSource.OKANIME -> okAnimeSource.fetchMainSlider()
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchMainSlider()
        }
    }

    suspend fun fetchHomePageLatestEpisodes(): List<SAnime> {
        return when (currentSource) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchHomePageLatestEpisodes()
            AnimeSource.MY_CIMA -> emptyList() // MyCima doesn't have this feature
            AnimeSource.ARAB_ANIME -> emptyList()
            AnimeSource.OKANIME -> okAnimeSource.fetchLatestUpdates(1)
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchLatestUpdates(1)
        }
    }

    suspend fun fetchSeasonList(animeUrl: String): List<SSeason> {
        return when (currentSource) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchSeasonList(animeUrl)
            AnimeSource.MY_CIMA -> emptyList() // MyCima handles seasons differently
            AnimeSource.ARAB_ANIME -> emptyList()
            AnimeSource.OKANIME -> emptyList() // <-- ADD THIS
            AnimeSource.ARAB_DRAMA -> emptyList()
        }
    }

    suspend fun getHlsUrlFromEpisode(episodeUrl: String): String? {
        return when (currentSource) {
            AnimeSource.FASEL_HD -> faselHDSource.getHlsUrlFromEpisode(episodeUrl)
            AnimeSource.MY_CIMA -> null // MyCima doesn't use HLS URLs directly
            AnimeSource.ARAB_ANIME -> null
            AnimeSource.OKANIME -> null // <-- ADD THIS
            AnimeSource.ARAB_DRAMA -> null
        }
    }

    fun getFilterList(): AnimeFilterList {
        return when (currentSource) {
            AnimeSource.FASEL_HD -> faselHDSource.getFilterList()
            AnimeSource.MY_CIMA -> AnimeFilterList(emptyList())
            AnimeSource.ARAB_ANIME -> arabAnimeSource.getFilterList() // Add this case
            AnimeSource.OKANIME -> okAnimeSource.getFilterList() // <-- ADD THIS
            AnimeSource.ARAB_DRAMA -> arabDramSource.getFilterList()
        }
    }

    fun getCurrentSourceName(): String {
        return currentSource.displayName
    }

    fun getAllSources(): List<AnimeSource> {
        return AnimeSource.values().toList()
    }
}