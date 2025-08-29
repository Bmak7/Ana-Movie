package com.faselhd.app.network
import android.content.Context
import com.arabictoons.app.network.sources.ArabicToonsSource
import com.faselhd.app.models.*
import com.faselhd.app.network.sources.*

enum class AnimeSource(val displayName: String) {
    FASEL_HD("FASEL HD"),
    MY_CIMA("MY CIMA"),
    Top_Cinema("Top Cinema"),
    ARAB_ANIME("ARAB ANIME"),
    ARAB_DRAMA("ARAB DRAMA"),
    OKANIME("OKANIME"),
    NETFLIX_MIRROR("NETFLIX MIRROR"),
    PRIME_VIDEO_MIRROR("PRIME VIDEO MIRROR"),
    ASIA2TV("ASIA2TV"),
    ANIMEIAT("ANIMEIAT"),
    EGYDEAD("EGYDEAD"),
    ANIME3RB("ANIME3RB"),
    ARABICTOONS("ARABIC TOONS"),
    HUHU("Huhu TV"),
    DADDY_LIVE("DaddyLive TV"),
    FREE_TV("Free TV"),
    INTERNET_ARCHIVE("Internet Archive"),
    ANIMERCO("ANIMERCO"),
    UHDMOVIES("UHD Movies"),
    WITANIME("WITANIME"),
    DRAMADRIP("DRAMADRIP"),
    ISQ("ISQ"),
    ANIME4UP("ANIME4UP"),


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

        fun setSelectedSource(context: Context, source: AnimeSource) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_SELECTED_SOURCE, source.name).apply()
        }
    }

    private val faselHDSource by lazy { FaselHDSource(context) }
    private val myCimaSource by lazy { MyCimaSource(context) }
    private val arabAnimeSource by lazy { ArabAnimeSource(context) }
    private val okAnimeSource by lazy { OkAnimeSource(context) }
    private val arabDramSource by lazy { ArabDramaSource(context) }
    private val netflixMirrorSource by lazy { NetflixMirrorSource(context) }
    private val primeVideoMirrorSource by lazy { PrimeVideoMirrorSource(context) }
    private val asia2TvSource by lazy { Asia2TvSource(context) }
    private val animeiatSource by lazy { AnimeiatSource(context) }
    private val egyDeadSource by lazy { EgyDeadSource(context) }
    private val anime3rbSource by lazy { Anime3rbSource(context) }
    private val arabicToonsSource by lazy { ArabicToonsSource(context) }
    private val huhuSource by lazy { HuhuSource(context) }
    private val daddyLiveSource by lazy { DaddyLiveSource(context) }
    private val freeTVSource by lazy { FreeTVSource(context) }
    private val internetArchiveSource by lazy { InternetArchiveSource(context) }
    private val animercoSource by lazy { AnimercoSource(context) }
    private val witAnimeSource by lazy { WitAnimeSource(context) }
    private val uhdMoviesSource by lazy { UHDMoviesSource(context) }
    private val dramaDripSource by lazy { DramaDripSource(context) }
    private val isqSource by lazy { IsqSource(context) }
    private val anime4upSource by lazy { Anime4upSource(context) }
    private val topCinemaSource by lazy { TopCinemaSource(context) }
    private val currentSource: AnimeSource
        get() = getSelectedSource(context)

    private fun getSource(specificSource: AnimeSource?): AnimeSource {
        return specificSource ?: currentSource
    }

    suspend fun fetchPopularSeries(page: Int): MangaPage {
        return when (currentSource) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchPopularSeries(page)
            AnimeSource.MY_CIMA -> myCimaSource.fetchPopularSeries(page)
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchPopularSeries(page)
            AnimeSource.OKANIME -> okAnimeSource.fetchPopularSeries(page)
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchPopularSeries(page)
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchPopularSeries(page)
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchPopularSeries(page)
            AnimeSource.ASIA2TV -> asia2TvSource.fetchPopularSeries(page)
            AnimeSource.ANIMEIAT -> animeiatSource.fetchPopularSeries(page)
            AnimeSource.EGYDEAD -> egyDeadSource.fetchPopularSeries(page)
            AnimeSource.ANIME3RB -> MangaPage(emptyList(),false)
            AnimeSource.ARABICTOONS -> arabicToonsSource.fetchPopularSeries(page)
            AnimeSource.INTERNET_ARCHIVE -> internetArchiveSource.fetchPopularSeries(page)
            AnimeSource.WITANIME -> witAnimeSource.fetchLatestUpdates(page)
            AnimeSource.ANIMERCO -> animercoSource.fetchPopularSeries(page)
            AnimeSource.UHDMOVIES -> uhdMoviesSource.fetchPopularSeries(page)
            AnimeSource.DRAMADRIP -> dramaDripSource.fetchPopularSeries(page)
            AnimeSource.ISQ -> isqSource.fetchPopularSeries(page)
            AnimeSource.ANIME4UP -> anime4upSource.fetchPopularSeries(page)
            AnimeSource.Top_Cinema -> topCinemaSource.fetchPopularSeries(page)

            AnimeSource.HUHU -> {
                // Convert live TV channels to manga format for main screen
                val channels = huhuSource.fetchAllChannelsByCountry()
                val allChannels = channels.values.flatten().take(20) // Get first 20 channels
                val mangaList = allChannels.map { channel ->
                    SAnime().apply {
                        title = channel.title
                        url = channel.url
                        thumbnail_url = channel.posterUrl
                        description = "Live TV - ${channel.country}"
                        source = AnimeSource.HUHU.name
                    }
                }
                MangaPage(mangaList, false)
            }

            AnimeSource.DADDY_LIVE -> {
                // Convert DaddyLive channels to manga format for main screen
                val channels = daddyLiveSource.fetchAllChannelsByCountry()
                val allChannels = channels.values.flatten().take(20) // Get first 20 channels
                val mangaList = allChannels.map { channel ->
                    SAnime().apply {
                        title = channel.title
                        url = channel.url
                        thumbnail_url = channel.posterUrl
                        description = "Live TV - ${channel.country}"
                        source = AnimeSource.DADDY_LIVE.name
                    }
                }
                MangaPage(mangaList, false)
            }

            AnimeSource.FREE_TV -> {
                // Convert Free TV channels to manga format for main screen
                val channels = freeTVSource.fetchAllChannelsByCountry()
                val allChannels = channels.values.flatten().take(20) // Get first 20 channels
                val mangaList = allChannels.map { channel ->
                    SAnime().apply {
                        title = channel.title
                        url = channel.url
                        thumbnail_url = channel.posterUrl
                        description = "Live TV - ${channel.country}"
                        source = AnimeSource.FREE_TV.name
                    }
                }
                MangaPage(mangaList, false)
            }
        }
    }

    suspend fun fetchLatestUpdates(page: Int): MangaPage {
        return when (currentSource) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchLatestUpdates(page)
            AnimeSource.MY_CIMA -> myCimaSource.fetchLatestUpdates(page)
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchLatestUpdates(page)
            AnimeSource.OKANIME -> okAnimeSource.fetchPopularSeries(page)
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchPopularSeries(page)
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchPopularSeries(page)
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchPopularSeries(page)
            AnimeSource.ASIA2TV -> asia2TvSource.fetchPopularSeries(page)
            AnimeSource.ANIMEIAT -> animeiatSource.fetchLatestUpdates(page)
            AnimeSource.EGYDEAD -> egyDeadSource.fetchPopularSeries(page)
            AnimeSource.ANIME3RB -> anime3rbSource.fetchLatestUpdates(1)
            AnimeSource.ARABICTOONS -> arabicToonsSource.fetchLatestUpdates(page)
            AnimeSource.WITANIME -> witAnimeSource.fetchLatestUpdates(page)
            AnimeSource.UHDMOVIES -> uhdMoviesSource.fetchLatestUpdates(page)
//            AnimeSource.ANIME3RB -> anime3rbSource.fetchPopularSeries(page)
            AnimeSource.INTERNET_ARCHIVE -> MangaPage(emptyList(), false)
            AnimeSource.ANIMERCO -> animercoSource.fetchLatestUpdates(page)
            AnimeSource.DRAMADRIP -> dramaDripSource.fetchLatestUpdates(page)
            AnimeSource.ANIME4UP -> anime4upSource.fetchPopularSeries(page)
            AnimeSource.ISQ -> isqSource.fetchPopularSeries(page)
            AnimeSource.Top_Cinema -> topCinemaSource.fetchPopularSeries(page)
            AnimeSource.HUHU -> {
                // For latest updates, show all channels grouped by country
                val channels = huhuSource.fetchAllChannelsByCountry()
                val allChannels = channels.values.flatten().take(30) // Get more for latest updates
                val mangaList = allChannels.map { channel ->
                    SAnime().apply {
                        title = channel.title
                        url = channel.url
                        thumbnail_url = channel.posterUrl
                        description = "Live TV - ${channel.country}"
                        source = AnimeSource.HUHU.name
                    }
                }
                MangaPage(mangaList, false)
            }

            AnimeSource.DADDY_LIVE -> {
                // For latest updates, show all DaddyLive channels grouped by country
                val channels = daddyLiveSource.fetchAllChannelsByCountry()
                val allChannels = channels.values.flatten().take(30) // Get more for latest updates
                val mangaList = allChannels.map { channel ->
                    SAnime().apply {
                        title = channel.title
                        url = channel.url
                        thumbnail_url = channel.posterUrl
                        description = "Live TV - ${channel.country}"
                        source = AnimeSource.DADDY_LIVE.name
                    }
                }
                MangaPage(mangaList, false)
            }

            AnimeSource.FREE_TV -> {
                // For latest updates, show all Free TV channels grouped by country
                val channels = freeTVSource.fetchAllChannelsByCountry()
                val allChannels = channels.values.flatten().take(30) // Get more for latest updates
                val mangaList = allChannels.map { channel ->
                    SAnime().apply {
                        title = channel.title
                        url = channel.url
                        thumbnail_url = channel.posterUrl
                        description = "Live TV - ${channel.country}"
                        source = AnimeSource.FREE_TV.name
                    }
                }
                MangaPage(mangaList, false)
            }
        }
    }

    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList, type: String, sourcee: AnimeSource? = null): MangaPage {
        return when (getSource(sourcee)) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchSearchAnime(page, query, filters)
            AnimeSource.MY_CIMA -> myCimaSource.fetchSearchAnime(page, query, filters, type)
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchSearchAnime(page, query, filters)
            AnimeSource.OKANIME -> okAnimeSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchSearchAnime(page, query, filters)
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchSearchAnime(page, query, filters)
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ASIA2TV -> asia2TvSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ANIMEIAT -> animeiatSource.fetchSearchAnime(page, query, filters)
            AnimeSource.EGYDEAD -> egyDeadSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ANIME3RB -> anime3rbSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ARABICTOONS -> arabicToonsSource.fetchSearchAnime(page, query, filters)
            AnimeSource.INTERNET_ARCHIVE -> internetArchiveSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ANIMERCO -> animercoSource.fetchSearchAnime(page, query, filters)
            AnimeSource.WITANIME -> witAnimeSource.fetchSearchAnime(page, query, filters)
            AnimeSource.UHDMOVIES -> uhdMoviesSource.fetchSearchAnime(page, query, filters)
            AnimeSource.DRAMADRIP -> dramaDripSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ISQ -> isqSource.fetchSearchAnime(page, query, filters)
            AnimeSource.Top_Cinema -> topCinemaSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ANIME4UP -> anime4upSource.fetchSearchAnime(page, query, filters)
            AnimeSource.HUHU -> {
                // Search within live TV channels
                val searchResults = huhuSource.search(query)
                val mangaList = searchResults.map { channel ->
                    SAnime().apply {
                        title = channel.title
                        url = channel.url
                        thumbnail_url = channel.posterUrl
                        description = "Live TV - ${channel.country}"
                        source = AnimeSource.HUHU.name
                    }
                }
                MangaPage(mangaList, false)
            }

            AnimeSource.DADDY_LIVE -> {
                // Search within DaddyLive channels
                val searchResults = daddyLiveSource.search(query)
                val mangaList = searchResults.map { channel ->
                    SAnime().apply {
                        title = channel.title
                        url = channel.url
                        thumbnail_url = channel.posterUrl
                        description = "Live TV - ${channel.country}"
                        source = AnimeSource.DADDY_LIVE.name
                    }
                }
                MangaPage(mangaList, false)
            }

            AnimeSource.FREE_TV -> {
                // Search within Free TV channels
                val searchResults = freeTVSource.search(query)
                val mangaList = searchResults.map { channel ->
                    SAnime().apply {
                        title = channel.title
                        url = channel.url
                        thumbnail_url = channel.posterUrl
                        description = "Live TV - ${channel.country}"
                        source = AnimeSource.FREE_TV.name
                    }
                }
                MangaPage(mangaList, false)
            }
        }
    }

    suspend fun fetchAnimeDetails(animeUrl: String, sourcee: AnimeSource? = null): SAnime {
        return when (getSource(sourcee)) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchAnimeDetails(animeUrl)
            AnimeSource.MY_CIMA -> myCimaSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchAnimeDetails(animeUrl)
            AnimeSource.OKANIME -> okAnimeSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchAnimeDetails(animeUrl)
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchAnimeDetails(animeUrl)!!
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchAnimeDetails(animeUrl)!!
            AnimeSource.ASIA2TV -> asia2TvSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ANIMEIAT -> animeiatSource.fetchAnimeDetails(animeUrl)
            AnimeSource.EGYDEAD -> egyDeadSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ANIME3RB -> anime3rbSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ARABICTOONS -> arabicToonsSource.fetchAnimeDetails(animeUrl)
            AnimeSource.INTERNET_ARCHIVE -> internetArchiveSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ANIMERCO -> animercoSource.fetchAnimeDetails(animeUrl)
            AnimeSource.WITANIME -> witAnimeSource.fetchAnimeDetails(animeUrl)
            AnimeSource.UHDMOVIES -> uhdMoviesSource.fetchAnimeDetails(animeUrl)
            AnimeSource.DRAMADRIP -> dramaDripSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ISQ -> isqSource.fetchAnimeDetails(animeUrl)
            AnimeSource.Top_Cinema -> topCinemaSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ANIME4UP -> anime4upSource.fetchAnimeDetails(animeUrl)
            AnimeSource.HUHU -> {
                // Create anime details from live TV channel data
                try {
                    val channelData = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString<com.faselhd.app.models.HuhuChannel>(animeUrl)
                    SAnime().apply {
                        title = channelData.name
                        url = animeUrl
                        thumbnail_url = "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/master/Huhu/tv.png"
                        description = "Live TV Channel from ${channelData.country}\nLanguage: ${channelData.p ?: "Unknown"}"
                        source = AnimeSource.HUHU.name
                    }
                } catch (e: Exception) {
                    SAnime().apply {
                        title = "Live TV Channel"
                        url = animeUrl
                        thumbnail_url = "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/master/Huhu/tv.png"
                        description = "Live TV Channel"
                        source = AnimeSource.HUHU.name
                    }
                }
            }

            AnimeSource.DADDY_LIVE -> {
                // Create anime details from DaddyLive channel data
                try {
                    val channelData = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString<com.faselhd.app.models.DaddyLiveChannel>(animeUrl)
                    SAnime().apply {
                        title = channelData.name
                        url = animeUrl
                        thumbnail_url = "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/refs/heads/master/DaddyLive/daddylive.jpg"
                        description = "Live TV Channel from ${channelData.country}"
                        source = AnimeSource.DADDY_LIVE.name
                    }
                } catch (e: Exception) {
                    SAnime().apply {
                        title = "Live TV Channel"
                        url = animeUrl
                        thumbnail_url = "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/refs/heads/master/DaddyLive/daddylive.jpg"
                        description = "Live TV Channel"
                        source = AnimeSource.DADDY_LIVE.name
                    }
                }
            }

            AnimeSource.FREE_TV -> {
                // Create anime details from Free TV channel data
                try {
                    val channelData = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString<com.faselhd.app.network.sources.FreeTVSource.FreeTVChannel>(animeUrl)
                    SAnime().apply {
                        title = channelData.title
                        url = animeUrl
                        thumbnail_url = if (channelData.logo.isNotEmpty()) channelData.logo else "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/master/FreeTV/freetv.png"
                        description = "Free TV Channel from ${channelData.country}\nID: ${channelData.tvgId}"
                        source = AnimeSource.FREE_TV.name
                    }
                } catch (e: Exception) {
                    SAnime().apply {
                        title = "Free TV Channel"
                        url = animeUrl
                        thumbnail_url = "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/master/FreeTV/freetv.png"
                        description = "Free TV Channel"
                        source = AnimeSource.FREE_TV.name
                    }
                }
            }
        }
    }

    suspend fun fetchEpisodeList(animeUrl: String, source: AnimeSource? = null): List<SEpisode> {
        return when (getSource(source)) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchEpisodeList(animeUrl)
            AnimeSource.MY_CIMA -> myCimaSource.fetchEpisodeList(animeUrl)
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchEpisodeList(animeUrl)
            AnimeSource.OKANIME -> okAnimeSource.fetchEpisodeList(animeUrl)
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchEpisodeList(animeUrl)
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchEpisodeList(animeUrl)
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchEpisodeList(animeUrl)
            AnimeSource.ASIA2TV -> asia2TvSource.fetchEpisodeList(animeUrl)
            AnimeSource.ANIMEIAT -> animeiatSource.fetchEpisodeList(animeUrl)
            AnimeSource.EGYDEAD -> egyDeadSource.fetchEpisodeList(animeUrl)
            AnimeSource.ANIME3RB -> anime3rbSource.fetchEpisodeList(animeUrl)
            AnimeSource.ARABICTOONS -> arabicToonsSource.fetchEpisodeList(animeUrl)
            AnimeSource.WITANIME -> witAnimeSource.fetchEpisodeList(animeUrl)
            AnimeSource.INTERNET_ARCHIVE -> internetArchiveSource.fetchEpisodeList(animeUrl)
            AnimeSource.ANIMERCO -> animercoSource.fetchEpisodeList(animeUrl)
            AnimeSource.UHDMOVIES -> uhdMoviesSource.fetchEpisodeList(animeUrl)
            AnimeSource.DRAMADRIP -> dramaDripSource.fetchEpisodeList(animeUrl)
            AnimeSource.ANIME4UP -> anime4upSource.fetchEpisodeList(animeUrl)
            AnimeSource.Top_Cinema -> topCinemaSource.fetchEpisodeList(animeUrl)
            AnimeSource.ISQ -> isqSource.fetchEpisodeList(animeUrl)
            AnimeSource.HUHU -> {
                // For live TV, create a single "episode" that represents the live stream
                listOf(
                    SEpisode().apply {
                        name = "Live Stream"
                        url = animeUrl // Use the channel data as the episode URL
                        episode_number = 1f
                    }
                )
            }

            AnimeSource.DADDY_LIVE -> {
                // For DaddyLive TV, create a single "episode" that represents the live stream
                listOf(
                    SEpisode().apply {
                        name = "Live Stream"
                        url = animeUrl // Use the channel data as the episode URL
                        episode_number = 1f
                    }
                )
            }

            AnimeSource.FREE_TV -> {
                // For Free TV, create a single "episode" that represents the live stream
                listOf(
                    SEpisode().apply {
                        name = "Live Stream"
                        url = animeUrl // Use the channel data as the episode URL
                        episode_number = 1f
                    }
                )
            }
        }
    }

    suspend fun fetchVideoList(episodeUrl: String, source: AnimeSource? = null): List<Video> {
        return when (getSource(source)) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchVideoList(episodeUrl)
            AnimeSource.MY_CIMA -> myCimaSource.fetchVideoList(episodeUrl)
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchVideoList(episodeUrl)
            AnimeSource.OKANIME -> okAnimeSource.fetchVideoList(episodeUrl)
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchVideoList(episodeUrl)
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchVideoList(episodeUrl)
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchVideoList(episodeUrl)
            AnimeSource.ASIA2TV -> asia2TvSource.fetchVideoList(episodeUrl)
            AnimeSource.ANIMEIAT -> animeiatSource.fetchVideoList(episodeUrl)
            AnimeSource.EGYDEAD -> egyDeadSource.fetchVideoList(episodeUrl)
            AnimeSource.ANIME3RB -> anime3rbSource.fetchVideoList(episodeUrl)
            AnimeSource.ARABICTOONS -> arabicToonsSource.fetchVideoList(episodeUrl)
            AnimeSource.INTERNET_ARCHIVE -> internetArchiveSource.fetchVideoList(episodeUrl)
            AnimeSource.ANIMERCO -> animercoSource.fetchVideoList(episodeUrl)
            AnimeSource.WITANIME -> witAnimeSource.fetchVideoList(episodeUrl)
            AnimeSource.UHDMOVIES -> uhdMoviesSource.fetchVideoList(episodeUrl)
            AnimeSource.DRAMADRIP -> dramaDripSource.fetchVideoList(episodeUrl)
            AnimeSource.ISQ -> isqSource.fetchVideoList(episodeUrl)
            AnimeSource.Top_Cinema -> topCinemaSource.fetchVideoList(episodeUrl)
            AnimeSource.ANIME4UP -> anime4upSource.fetchVideoList(episodeUrl)
            AnimeSource.HUHU -> {
                // Get live stream link
                val video = huhuSource.fetchLiveStreamLink(episodeUrl)
                if (video != null) listOf(video) else emptyList()
            }

            AnimeSource.DADDY_LIVE -> {
                // Get DaddyLive stream link
                val video = daddyLiveSource.fetchLiveStreamLink(episodeUrl)
                if (video != null) listOf(video) else emptyList()
            }

            AnimeSource.FREE_TV -> {
                // Get Free TV stream link
                val video = freeTVSource.fetchLiveStreamLink(episodeUrl)
                if (video != null) listOf(video) else emptyList()
            }
        }
    }

    suspend fun fetchMainSlider(): List<SAnime> {
        return when (currentSource) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchMainSlider()
            AnimeSource.MY_CIMA -> myCimaSource.fetchMainSlider()
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchMainSlider()
            AnimeSource.OKANIME -> okAnimeSource.fetchMainSlider()
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchMainSlider()
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchMainSlider()
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchMainSlider()
            AnimeSource.ASIA2TV -> asia2TvSource.fetchMainSlider()
            AnimeSource.ANIMEIAT -> animeiatSource.fetchMainSlider()
            AnimeSource.EGYDEAD -> egyDeadSource.fetchMainSlider()
            AnimeSource.ANIME3RB -> anime3rbSource.fetchMainSlider()
            AnimeSource.ARABICTOONS -> arabicToonsSource.fetchLatestUpdatess(1)
            AnimeSource.INTERNET_ARCHIVE -> internetArchiveSource.fetchMainSlider()
            AnimeSource.ANIMERCO -> animercoSource.fetchLatestUpdates(1).manga
            AnimeSource.WITANIME -> witAnimeSource.fetchMainSlider()
            AnimeSource.UHDMOVIES -> uhdMoviesSource.fetchMainSlider()
            AnimeSource.ISQ -> isqSource.fetchMainSlider()
            AnimeSource.Top_Cinema -> topCinemaSource.fetchMainSlider()
            AnimeSource.ANIME4UP -> anime4upSource.fetchLatestUpdates(1).manga
            AnimeSource.DRAMADRIP -> dramaDripSource.fetchMainSlider()
            AnimeSource.HUHU -> {
                // Create slider from featured live TV channels
                val channels = huhuSource.fetchAllChannelsByCountry()
                val featuredChannels = channels.values.flatten()
                    .shuffled() // Randomize for variety
                    .take(5) // Take first 5 for slider

                featuredChannels.map { channel ->
                    SAnime().apply {
                        title = channel.title
                        url = channel.url
                        thumbnail_url = channel.posterUrl
                        description = "Live TV - ${channel.country}"
                        source = AnimeSource.HUHU.name
                    }
                }
            }

            AnimeSource.DADDY_LIVE -> {
                // Create slider from featured DaddyLive channels
                val channels = daddyLiveSource.fetchAllChannelsByCountry()
                val featuredChannels = channels.values.flatten()
                    .shuffled() // Randomize for variety
                    .take(5) // Take first 5 for slider

                featuredChannels.map { channel ->
                    SAnime().apply {
                        title = channel.title
                        url = channel.url
                        thumbnail_url = channel.posterUrl
                        description = "Live TV - ${channel.country}"
                        source = AnimeSource.DADDY_LIVE.name
                    }
                }
            }

            AnimeSource.FREE_TV -> {
                // Create slider from featured Free TV channels
                val channels = freeTVSource.fetchAllChannelsByCountry()
                val featuredChannels = channels.values.flatten()
                    .shuffled() // Randomize for variety
                    .take(5) // Take first 5 for slider

                featuredChannels.map { channel ->
                    SAnime().apply {
                        title = channel.title
                        url = channel.url
                        thumbnail_url = channel.posterUrl
                        description = "Live TV - ${channel.country}"
                        source = AnimeSource.FREE_TV.name
                    }
                }
            }
        }
    }

    suspend fun fetchHomePageLatestEpisodes(): List<SAnime> {
        return when (currentSource) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchHomePageLatestEpisodes()
            AnimeSource.MY_CIMA -> emptyList()
            AnimeSource.ARAB_ANIME -> emptyList()
            AnimeSource.OKANIME -> okAnimeSource.fetchLatestUpdates(1)
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchLatestUpdates(1)
            AnimeSource.NETFLIX_MIRROR -> emptyList()
            AnimeSource.PRIME_VIDEO_MIRROR -> emptyList()
            AnimeSource.ASIA2TV -> emptyList()
            AnimeSource.ANIMEIAT -> animeiatSource.fetchLatestUpdatess(1)
            AnimeSource.EGYDEAD -> emptyList()
            AnimeSource.ANIME3RB -> anime3rbSource.fetchHomePageLatestAnimes()
            AnimeSource.ARABICTOONS -> arabicToonsSource.fetchLatestUpdatess(1)
            AnimeSource.INTERNET_ARCHIVE -> emptyList()
            AnimeSource.ANIMERCO -> emptyList()
            AnimeSource.WITANIME -> emptyList()
            AnimeSource.UHDMOVIES -> emptyList()
            AnimeSource.Top_Cinema -> topCinemaSource.fetchHomePageLatestEpisodes()
            AnimeSource.DRAMADRIP -> dramaDripSource.fetchLatestUpdates(1).manga
            AnimeSource.ISQ -> isqSource.fetchPopularSeries(1).manga
            AnimeSource.ANIME4UP -> anime4upSource.fetchLatestUpdates(1).manga
            AnimeSource.HUHU -> {
                // Show some popular live TV channels for "latest episodes"
                val channels = huhuSource.fetchAllChannelsByCountry()
                val popularChannels = channels.values.flatten()
                    .take(10) // Take first 10 channels

                popularChannels.map { channel ->
                    SAnime().apply {
                        title = channel.title
                        url = channel.url
                        thumbnail_url = channel.posterUrl
                        description = "Live TV - ${channel.country}"
                        source = AnimeSource.HUHU.name
                    }
                }
            }

            AnimeSource.DADDY_LIVE -> {
                // Show some popular DaddyLive channels for "latest episodes"
                val channels = daddyLiveSource.fetchAllChannelsByCountry()
                val popularChannels = channels.values.flatten()
                    .take(10) // Take first 10 channels

                popularChannels.map { channel ->
                    SAnime().apply {
                        title = channel.title
                        url = channel.url
                        thumbnail_url = channel.posterUrl
                        description = "Live TV - ${channel.country}"
                        source = AnimeSource.DADDY_LIVE.name
                    }
                }
            }

            AnimeSource.FREE_TV -> {
                // Show some popular Free TV channels for "latest episodes"
                val channels = freeTVSource.fetchAllChannelsByCountry()
                val popularChannels = channels.values.flatten()
                    .take(10) // Take first 10 channels

                popularChannels.map { channel ->
                    SAnime().apply {
                        title = channel.title
                        url = channel.url
                        thumbnail_url = channel.posterUrl
                        description = "Live TV - ${channel.country}"
                        source = AnimeSource.FREE_TV.name
                    }
                }
            }
        }
    }

    fun getFilterList(): AnimeFilterList {
        return when (currentSource) {
            AnimeSource.FASEL_HD -> faselHDSource.getFilterList()
            AnimeSource.MY_CIMA -> AnimeFilterList(emptyList())
            AnimeSource.ARAB_ANIME -> arabAnimeSource.getFilterList()
            AnimeSource.OKANIME -> okAnimeSource.getFilterList()
            AnimeSource.ARAB_DRAMA -> arabDramSource.getFilterList()
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.getFilterList()
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.getFilterList()
            AnimeSource.ASIA2TV -> asia2TvSource.getFilterList()
            AnimeSource.ANIMEIAT -> animeiatSource.getFilterList()
            AnimeSource.EGYDEAD -> egyDeadSource.getFilterList()
            AnimeSource.ANIME3RB -> anime3rbSource.getFilterList()
            AnimeSource.HUHU -> AnimeFilterList(emptyList()) // Live TV doesn't need complex filters
            AnimeSource.DADDY_LIVE -> AnimeFilterList(emptyList())
            AnimeSource.FREE_TV -> AnimeFilterList(emptyList()) // Live TV doesn't need complex filters
            AnimeSource.ARABICTOONS -> AnimeFilterList(emptyList())
            AnimeSource.INTERNET_ARCHIVE -> internetArchiveSource.getFilterList()
            AnimeSource.ANIMERCO -> animercoSource.getFilterList()
            AnimeSource.WITANIME -> witAnimeSource.getFilterList()
            AnimeSource.DRAMADRIP -> dramaDripSource.getFilterList()
            AnimeSource.UHDMOVIES -> uhdMoviesSource.getFilterList()
            AnimeSource.ISQ -> isqSource.getFilterList()
            AnimeSource.Top_Cinema -> topCinemaSource.getFilterList()
            AnimeSource.ANIME4UP ->AnimeFilterList(emptyList())
        }
    }

    suspend fun fetchAllLiveChannels(): Map<String, List<SLiveTv>> {
        return when (currentSource) {
            AnimeSource.HUHU -> huhuSource.fetchAllChannelsByCountry()
            AnimeSource.DADDY_LIVE -> daddyLiveSource.fetchAllChannelsByCountry()
            AnimeSource.FREE_TV -> freeTVSource.fetchAllChannelsByCountry()
            else -> emptyMap()
        }
    }

    suspend fun fetchLiveStream(channel: SLiveTv): Video? {
        return when (channel.source?.let { AnimeSource.valueOf(it) }) {
            AnimeSource.HUHU -> huhuSource.fetchLiveStreamLink(channel.url)
            AnimeSource.DADDY_LIVE -> daddyLiveSource.fetchLiveStreamLink(channel.url)
            AnimeSource.FREE_TV -> freeTVSource.fetchLiveStreamLink(channel.url)
            else -> null
        }
    }

    fun getCurrentSourceName(): String {
        return currentSource.displayName
    }

    fun getAllSources(): List<AnimeSource> {
        return AnimeSource.values().toList()
    }
}