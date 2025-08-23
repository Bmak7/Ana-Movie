package com.faselhd.app.network
import android.content.Context
import com.faselhd.app.models.*
import com.faselhd.app.network.sources.*

enum class AnimeSource(val displayName: String) {
    FASEL_HD("FASEL HD"),
    MY_CIMA("MY CIMA"),
    ARAB_ANIME("ARAB ANIME"),
    ARAB_DRAMA("ARAB DRAMA"),
    OKANIME("OKANIME"),
    NETFLIX_MIRROR("NETFLIX MIRROR"),
    PRIME_VIDEO_MIRROR("PRIME VIDEO MIRROR"),
    ASIA2TV("ASIA2TV"),
    ANIMEIAT("ANIMEIAT"), // <-- ADD THIS
    EGYDEAD("EGYDEAD"),
    ANIME3RB("ANIME3RB"),

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
    private val netflixMirrorSource by lazy { NetflixMirrorSource(context) } // <-- ADD THIS
    private val primeVideoMirrorSource by lazy { PrimeVideoMirrorSource(context) } // <-- ADD THIS
    private val asia2TvSource by lazy { Asia2TvSource(context) } //
    private val animeiatSource by lazy { AnimeiatSource(context) } // <-- ADD THIS
    private val egyDeadSource by lazy { EgyDeadSource(context) }
    private val anime3rbSource by lazy { Anime3rbSource(context) }


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
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchPopularSeries(page)
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchPopularSeries(page) // <-- ADD THIS
            AnimeSource.ASIA2TV -> asia2TvSource.fetchPopularSeries(page) // <-- ADD THIS
            AnimeSource.ANIMEIAT -> animeiatSource.fetchPopularSeries(page) // <-- ADD THIS
            AnimeSource.EGYDEAD -> egyDeadSource.fetchPopularSeries(page) // <-- ADD THIS
            AnimeSource.ANIME3RB -> anime3rbSource.fetchPopularSeries(page)
        }
    }

    suspend fun fetchLatestUpdates(page: Int): MangaPage {
        return when (currentSource) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchLatestUpdates(page)
            AnimeSource.MY_CIMA -> myCimaSource.fetchLatestUpdates(page)
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchLatestUpdates(page) // Add this case
            AnimeSource.OKANIME -> okAnimeSource.fetchPopularSeries(page) // <-- ADD THIS
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchPopularSeries(page) // <-- ADD THIS
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchPopularSeries(page)
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchPopularSeries(page) // <-- ADD THIS
            AnimeSource.ASIA2TV -> asia2TvSource.fetchPopularSeries(page) // <-- ADD THIS
            AnimeSource.ANIMEIAT -> animeiatSource.fetchLatestUpdates(page) // <-- ADD THIS
            AnimeSource.EGYDEAD -> egyDeadSource.fetchPopularSeries(page) // <-- ADD THIS
            AnimeSource.ANIME3RB -> anime3rbSource.fetchPopularSeries(page)
        }
    }

    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList, type: String, source: AnimeSource? = null): MangaPage {
        return when (getSource(source)) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchSearchAnime(page, query, filters)
            AnimeSource.MY_CIMA -> myCimaSource.fetchSearchAnime(page, query, filters, type)
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchSearchAnime(page, query, filters) // Add this case
            AnimeSource.OKANIME -> okAnimeSource.fetchSearchAnime(page, query, filters) // <-- ADD THIS
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchSearchAnime(page, query, filters) // <-- ADD THIS
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchSearchAnime(page, query, filters)
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchSearchAnime(page, query, filters) // <-- ADD THIS
            AnimeSource.ASIA2TV -> asia2TvSource.fetchSearchAnime(page, query, filters) // <-- ADD THIS
            AnimeSource.ANIMEIAT -> animeiatSource.fetchSearchAnime(page, query, filters) // <-- ADD THIS
            AnimeSource.EGYDEAD -> egyDeadSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ANIME3RB -> anime3rbSource.fetchSearchAnime(page, query, filters)
        }
    }

    suspend fun fetchAnimeDetails(animeUrl: String, source: AnimeSource? = null): SAnime {
        return when (getSource(source)) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchAnimeDetails(animeUrl)
            AnimeSource.MY_CIMA -> myCimaSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchAnimeDetails(animeUrl) // Add this case
            AnimeSource.OKANIME -> okAnimeSource.fetchAnimeDetails(animeUrl) // <-- ADD THIS
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchAnimeDetails(animeUrl) // <-- ADD THIS
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchAnimeDetails(animeUrl)!!
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchAnimeDetails(animeUrl)!!
            AnimeSource.ASIA2TV -> asia2TvSource.fetchAnimeDetails(animeUrl) // <-- ADD THIS
            AnimeSource.ANIMEIAT -> animeiatSource.fetchAnimeDetails(animeUrl) // <-- ADD THIS
            AnimeSource.EGYDEAD -> egyDeadSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ANIME3RB -> anime3rbSource.fetchAnimeDetails(animeUrl)

        }
    }

    suspend fun fetchEpisodeList(animeUrl: String, source: AnimeSource? = null): List<SEpisode> {
        return when (getSource(source)) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchEpisodeList(animeUrl)
            AnimeSource.MY_CIMA -> myCimaSource.fetchEpisodeList(animeUrl)
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchEpisodeList(animeUrl) // Add this case
            AnimeSource.OKANIME -> okAnimeSource.fetchEpisodeList(animeUrl) // <-- ADD THIS
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchEpisodeList(animeUrl) // <-- ADD THIS
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchEpisodeList(animeUrl)
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchEpisodeList(animeUrl)
            AnimeSource.ASIA2TV -> asia2TvSource.fetchEpisodeList(animeUrl) // <-- ADD THIS
            AnimeSource.ANIMEIAT -> animeiatSource.fetchEpisodeList(animeUrl) // <-- ADD THIS
            AnimeSource.EGYDEAD -> egyDeadSource.fetchEpisodeList(animeUrl)
            AnimeSource.ANIME3RB -> anime3rbSource.fetchEpisodeList(animeUrl)
        }
    }



    suspend fun fetchVideoList(episodeUrl: String, source: AnimeSource? = null): List<Video> {
        return when (getSource(source)) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchVideoList(episodeUrl)
            AnimeSource.MY_CIMA -> myCimaSource.fetchVideoList(episodeUrl)
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchVideoList(episodeUrl) // Add this case
            AnimeSource.OKANIME -> okAnimeSource.fetchVideoList(episodeUrl) // <-- ADD THIS
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchVideoList(episodeUrl) // <-- ADD THIS
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchVideoList(episodeUrl)
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchVideoList(episodeUrl)
            AnimeSource.ASIA2TV -> asia2TvSource.fetchVideoList(episodeUrl) // <-- ADD THIS
            AnimeSource.ANIMEIAT -> animeiatSource.fetchVideoList(episodeUrl) // <-- ADD THIS
            AnimeSource.EGYDEAD -> egyDeadSource.fetchVideoList(episodeUrl)
            AnimeSource.ANIME3RB -> anime3rbSource.fetchVideoList(episodeUrl)
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
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchMainSlider()
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchMainSlider()
            AnimeSource.ASIA2TV -> asia2TvSource.fetchMainSlider() // <-- ADD THIS
            AnimeSource.ANIMEIAT -> animeiatSource.fetchMainSlider() // <-- ADD THIS
            AnimeSource.EGYDEAD -> egyDeadSource.fetchMainSlider()
            AnimeSource.ANIME3RB -> anime3rbSource.fetchMainSlider()

        }
    }

    suspend fun fetchHomePageLatestEpisodes(): List<SAnime> {
        return when (currentSource) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchHomePageLatestEpisodes()
            AnimeSource.MY_CIMA -> emptyList() // MyCima doesn't have this feature
            AnimeSource.ARAB_ANIME -> emptyList()
            AnimeSource.OKANIME -> okAnimeSource.fetchLatestUpdates(1)
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchLatestUpdates(1)
            AnimeSource.NETFLIX_MIRROR -> emptyList()// <-- ADD THIS
            AnimeSource.PRIME_VIDEO_MIRROR -> emptyList()
            AnimeSource.ASIA2TV -> emptyList()// <-- ADD THIS
            AnimeSource.ANIMEIAT -> animeiatSource.fetchLatestUpdatess(1) // <-- ADD THIS
            AnimeSource.EGYDEAD -> emptyList()
            AnimeSource.ANIME3RB -> emptyList()
        }
    }



    fun getFilterList(): AnimeFilterList {
        return when (currentSource) {
            AnimeSource.FASEL_HD -> faselHDSource.getFilterList()
            AnimeSource.MY_CIMA -> AnimeFilterList(emptyList())
            AnimeSource.ARAB_ANIME -> arabAnimeSource.getFilterList() // Add this case
            AnimeSource.OKANIME -> okAnimeSource.getFilterList() // <-- ADD THIS
            AnimeSource.ARAB_DRAMA -> arabDramSource.getFilterList()
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.getFilterList() // <-- ADD THIS
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.getFilterList()
            AnimeSource.ASIA2TV -> asia2TvSource.getFilterList() // <-- ADD THIS
            AnimeSource.ANIMEIAT -> animeiatSource.getFilterList() // <-- ADD THIS
            AnimeSource.EGYDEAD -> egyDeadSource.getFilterList()
            AnimeSource.ANIME3RB -> anime3rbSource.getFilterList()
        }
    }

    fun getCurrentSourceName(): String {
        return currentSource.displayName
    }

    fun getAllSources(): List<AnimeSource> {
        return AnimeSource.values().toList()
    }
}