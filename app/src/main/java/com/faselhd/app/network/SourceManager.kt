package com.faselhd.app.network
import android.content.Context
import com.anslayer.app.network.sources.AnslayerSource
import com.arabictoons.app.network.sources.ArabicToonsSource
import com.faselhd.app.models.*
import com.faselhd.app.network.sources.*
import com.faselhd.app.sources.FullReplaysSource
import recloudstream.DailymotionSource

enum class AnimeSource(val displayName: String, val isNsfw: Boolean = false) {
    FASEL_HD("FASEL HD"),
    MY_CIMA("MY CIMA"),
    TOP_CINEMA("Top Cinema"),
    SHED4U("Shed4u"),
    ARAB_ANIME("ARAB ANIME"),
    ANIME_PHOENIX("ANIME PHOENIX"),
    ARAB_DRAMA("ARAB DRAMA"),
    ARABDRAMA2("ARABDRAMA2"),
    ARAB_SEED("ARAB SEED"), // Add this line
    AKWAM("Akwam"),
    ANIMETAK("ANIMETAK"),
    OKANIME("OKANIME"),
    NETFLIX_MIRROR("NETFLIX MIRROR"),
    PRIME_VIDEO_MIRROR("PRIME VIDEO MIRROR"),
    ANSLAYER("ANSLAYER"),
    HIANIME("HIANIME"),
    ASIA2TV("ASIA2TV"),
    ANIMEIAT("ANIMEIAT"),
    EGYDEAD("EGYDEAD"),
    ANIME3RB("ANIME3RB"),
    ARABICTOONS("ARABICTOONS"),
    CARTOONY("Cartoony"),
    HUHU("Huhu"),
    DADDY_LIVE("Daddy Live"),
    FULLREPLAYS("FullReplays"),
    FREE_TV("Free TV"),
    YACINETV("Yacine Tv"),
    NETFLY("NETFLY"),
    INTERNET_ARCHIVE("Internet Archive"),
    ANIMERCO("ANIMERCO"),
    UHDMOVIES("UHD Movies"),
    WITANIME("WITANIME"),
    ZIMABADK("ZIMABADK"),
    DRAMADRIP("DRAMADRIP"),
    E3SK("E3sk"),
    ISQ("ISQ"),
    Esk("Esk"),
    ANIME4UP("ANIME4UP"),
    FIVETV("FIVETV"),
    ANIMELEK("ANIMELEK"),
    DAILY_MOTION("Daily Motion"),
    CIMA_NOW("Cima Now"),
    CIMA_CLUB("Cima Club"),
    CIMALIGHT("CimaLight"),
    SPANKBANG("SPANKBANG", true),
    HENTAI_TIME("HENTAI TIME", true),
    XVIDEOS("XVIDEOS", true),
    NXXHENTAI("NXXHENTAI", true)
}

class SourceManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "source_manager_prefs"
        private const val KEY_SELECTED_SOURCE = "selected_source"
        private const val DEFAULT_SOURCE = "FASEL_HD"
        private const val KEY_ADULT_CONTENT_UNLOCKED = "adult_content_unlocked"


        fun isAdultContentUnlocked(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_ADULT_CONTENT_UNLOCKED, false)
        }

        fun setAdultContentUnlocked(context: Context, unlocked: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_ADULT_CONTENT_UNLOCKED, unlocked).apply()
        }

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
    private val arabDrama2Source by lazy { ArabDrama2Source(context) }

    private val okAnimeSource by lazy { OkAnimeSource(context) }
    private val arabDramSource by lazy { ArabDramaSource(context) }
    private val arabSeedSource by lazy { ArabSeedSource(context) }
    private val netflixMirrorSource by lazy { NetflixMirrorSource(context) }
    private val primeVideoMirrorSource by lazy { PrimeVideoMirrorSource(context) }
    private val asia2TvSource by lazy { Asia2TvSource(context) }
    private val animeiatSource by lazy { AnimeiatSource(context) }
    private val egyDeadSource by lazy { EgyDeadSource(context) }
    private val fullReplaysSource by lazy { FullReplaysSource(context) }
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
    private val hiAnimeSource by lazy { HiAnimeSource(context) }
    private val zimabadkSource by lazy { ZimabadkSource(context) }
    private val fiveTvSource by lazy { FiveTvSource(context) }
    private val animeLekSource by lazy { AnimeLekSource(context) }
    private val dailymotionSource by lazy { DailymotionSource(context) }
    private val spankBangSource by  lazy { SpankBangSource(context) }
    private val hentaiTimeSource by lazy { HentaiTimeSource(context) }
    private val xvideosSource by lazy { XvideosSource(context) }
    private val nxxhentaiSource by lazy { NxxhentaiSource(context) }
    private val cimaNowSource by lazy { CimaNowSource(context) }
    private val shed4uSource by lazy { Shed4uSource(context) }
    private val eskSource by lazy { EskSource(context) }
    private val e3skSource by lazy { E3skSource(context) }
    private val cartoonySource by lazy { CartoonySource(context) }
    private val yacineTvSource by lazy { YacineTvSource(context) }
    private val cimaClubSource by lazy { CimaClubSource(context) }
    private val anslyerSource by lazy { AnslayerSource(context) }
    private val netflySource by lazy { NetflySource(context) }
    private val cimaLightSource by lazy { CimaLightSource(context) }
    private val akwamSource by lazy { AkwamSource(context) }
    private val animetakSource by lazy { AnimetakSource(context) }
    private val animePhoenixSource by lazy { AnimePhoenixSource(context) }

    private val currentSource: AnimeSource
        get() = getSelectedSource(context)

    private fun getSource(specificSource: AnimeSource?): AnimeSource {
        return specificSource ?: currentSource
    }

    suspend fun fetchPopularSeries(page: Int): MangaPage {
        return when (currentSource) {
            AnimeSource.FASEL_HD -> faselHDSource.fetchPopularSeries(page)
            AnimeSource.MY_CIMA -> myCimaSource.fetchPopularSeries(page)
            AnimeSource.SHED4U -> shed4uSource.fetchLatestUpdates(1)
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchPopularSeries(page)
            AnimeSource.OKANIME -> okAnimeSource.fetchPopularSeries(page)
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchPopularSeries(page)
            AnimeSource.ARABDRAMA2 -> arabDrama2Source.fetchPopularSeries(page)
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchPopularSeries(page)
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchPopularSeries(page)
            AnimeSource.ASIA2TV -> asia2TvSource.fetchPopularSeries(page)
            AnimeSource.ANIMEIAT -> animeiatSource.fetchPopularSeries(page)
            AnimeSource.EGYDEAD -> egyDeadSource.fetchPopularSeries(page)
            AnimeSource.ANIME3RB -> MangaPage(emptyList(),false)
            AnimeSource.FULLREPLAYS -> fullReplaysSource.fetchLatestUpdates(page)
            AnimeSource.ARABICTOONS -> arabicToonsSource.fetchPopularSeries(page)
            AnimeSource.ANSLAYER -> anslyerSource.fetchLatestUpdates(page)
            AnimeSource.AKWAM -> akwamSource.fetchPopularSeries(page)
            AnimeSource.INTERNET_ARCHIVE -> internetArchiveSource.fetchPopularSeries(page)
            AnimeSource.WITANIME -> witAnimeSource.fetchLatestUpdates(page)
            AnimeSource.ANIMERCO -> animercoSource.fetchPopularSeries(page)
            AnimeSource.UHDMOVIES -> uhdMoviesSource.fetchPopularSeries(page)
            AnimeSource.DRAMADRIP -> dramaDripSource.fetchPopularSeries(page)
            AnimeSource.ISQ -> isqSource.fetchPopularSeries(page)
            AnimeSource.Esk -> eskSource.fetchLatestUpdates(page)
            AnimeSource.CIMA_CLUB -> cimaClubSource.fetchPopularSeries(page)
            AnimeSource.ANIMETAK -> animetakSource.fetchPopularSeries(page)
            AnimeSource.E3SK -> e3skSource.fetchLatestUpdates(page)
            AnimeSource.ANIME4UP -> anime4upSource.fetchPopularSeries(page)
            AnimeSource.TOP_CINEMA -> topCinemaSource.fetchPopularSeries(page)
            AnimeSource.ARAB_SEED -> arabSeedSource.fetchPopularSeries(page)
            AnimeSource.HIANIME -> hiAnimeSource.fetchTopAiring(page)
            AnimeSource.ZIMABADK -> zimabadkSource.fetchPopularSeries(page)
            AnimeSource.FIVETV -> fiveTvSource.fetchPopularSeries(page)
            AnimeSource.ANIMELEK -> animeLekSource.fetchPopularSeries(page)
            AnimeSource.ANIME_PHOENIX -> animePhoenixSource.fetchPopularSeries(page)
            AnimeSource.DAILY_MOTION -> dailymotionSource.fetchPopular(page)
            AnimeSource.YACINETV -> yacineTvSource.fetchPopularSeries(page)
            AnimeSource.CIMA_NOW ->cimaNowSource.fetchPopularSeries(1)
            AnimeSource.CIMALIGHT ->cimaLightSource.fetchPopularSeries(1)
            AnimeSource.SPANKBANG -> spankBangSource.fetchPopularSeries(page)
            AnimeSource.HENTAI_TIME -> hentaiTimeSource.fetchPopular(page)
            AnimeSource.XVIDEOS -> xvideosSource.fetchPopular(page)
            AnimeSource.NXXHENTAI -> nxxhentaiSource.fetchPopular(page)
            AnimeSource.CARTOONY -> cartoonySource.fetchPopularSeries(page)
            AnimeSource.NETFLY -> netflySource.fetchLatestUpdates(1)


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
            AnimeSource.ARABDRAMA2 -> arabDrama2Source.fetchPopularSeries(page)
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchPopularSeries(page)
            AnimeSource.SHED4U -> shed4uSource.fetchLatestUpdates(1)
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchPopularSeries(page)
            AnimeSource.ASIA2TV -> asia2TvSource.fetchPopularSeries(page)
            AnimeSource.ARAB_SEED -> arabSeedSource.fetchPopularSeries(page)
            AnimeSource.ANIMEIAT -> animeiatSource.fetchLatestUpdates(page)
            AnimeSource.HIANIME -> hiAnimeSource.fetchRecentlyUpdated(page)
            AnimeSource.EGYDEAD -> egyDeadSource.fetchPopularSeries(page)
            AnimeSource.ANIME3RB -> anime3rbSource.fetchLatestUpdates(1)
            AnimeSource.NETFLY -> netflySource.fetchLatestUpdates(1)
            AnimeSource.ARABICTOONS -> arabicToonsSource.fetchLatestUpdates(page)
            AnimeSource.ANIME_PHOENIX -> animePhoenixSource.fetchLatestUpdates(page)
            AnimeSource.FULLREPLAYS -> fullReplaysSource.fetchLatestUpdates(page)
            AnimeSource.CIMA_CLUB -> cimaClubSource.fetchLatestUpdates(page)
            AnimeSource.WITANIME -> witAnimeSource.fetchLatestUpdates(page)
            AnimeSource.UHDMOVIES -> uhdMoviesSource.fetchLatestUpdates(page)
            AnimeSource.CARTOONY -> cartoonySource.fetchPopularSeries(page)
//            AnimeSource.ANIME3RB -> anime3rbSource.fetchPopularSeries(page)
            AnimeSource.INTERNET_ARCHIVE -> MangaPage(emptyList(), false)
            AnimeSource.ANIMERCO -> animercoSource.fetchLatestUpdates(page)
            AnimeSource.DRAMADRIP -> dramaDripSource.fetchLatestUpdates(page)
            AnimeSource.ANIME4UP -> anime4upSource.fetchPopularSeries(page)
            AnimeSource.ISQ -> isqSource.fetchPopularSeries(page)
            AnimeSource.Esk -> eskSource.fetchLatestUpdates(page)
            AnimeSource.E3SK -> e3skSource.fetchLatestUpdates(page)
            AnimeSource.TOP_CINEMA -> topCinemaSource.fetchPopularSeries(page)
            AnimeSource.ANSLAYER -> anslyerSource.fetchLatestUpdates(page)
            AnimeSource.ZIMABADK -> zimabadkSource.fetchLatestUpdates(page)
            AnimeSource.CIMALIGHT ->cimaLightSource.fetchLatestUpdates(1)
            AnimeSource.AKWAM -> akwamSource.fetchLatestUpdates(page)
            AnimeSource.FIVETV -> fiveTvSource.fetchLatestUpdates(page)
            AnimeSource.ANIMELEK -> animeLekSource.fetchLatestUpdates(page)
            AnimeSource.DAILY_MOTION -> dailymotionSource.fetchPopular(page)
            AnimeSource.YACINETV -> yacineTvSource.fetchPopularSeries(page)
            AnimeSource.ANIMETAK -> animetakSource.fetchLatestUpdates(page)
            AnimeSource.CIMA_NOW ->cimaNowSource.fetchMainSlider(1)
            AnimeSource.SPANKBANG -> spankBangSource.fetchLatestUpdates(page)
            AnimeSource.HENTAI_TIME -> hentaiTimeSource.fetchLatestUpdates(page)
            AnimeSource.XVIDEOS -> xvideosSource.fetchLatestUpdates(page)
            AnimeSource.NXXHENTAI -> nxxhentaiSource.fetchLatestUpdates(page)
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
            AnimeSource.MY_CIMA -> myCimaSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchSearchAnime(page, query, filters)
            AnimeSource.OKANIME -> okAnimeSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ARABDRAMA2 -> arabDrama2Source.fetchSearchAnime(page, query, filters)
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchSearchAnime(page, query, filters)
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ASIA2TV -> asia2TvSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ANIMEIAT -> animeiatSource.fetchSearchAnime(page, query, filters)
            AnimeSource.HIANIME -> hiAnimeSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ANIME_PHOENIX -> animePhoenixSource.fetchSearchAnime(page, query, filters)
            AnimeSource.YACINETV -> yacineTvSource.fetchSearchAnime(page, query, filters)
            AnimeSource.EGYDEAD -> egyDeadSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ANIME3RB -> anime3rbSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ARABICTOONS -> arabicToonsSource.fetchSearchAnime(page, query, filters)
            AnimeSource.INTERNET_ARCHIVE -> internetArchiveSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ANIMERCO -> animercoSource.fetchSearchAnime(page, query, filters)
            AnimeSource.FULLREPLAYS -> fullReplaysSource.fetchSearch(query)
            AnimeSource.WITANIME -> witAnimeSource.fetchSearchAnime(page, query, filters)
            AnimeSource.UHDMOVIES -> uhdMoviesSource.fetchSearchAnime(page, query, filters)
            AnimeSource.DRAMADRIP -> dramaDripSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ISQ -> isqSource.fetchSearchAnime(page, query, filters)
            AnimeSource.Esk -> eskSource.fetchSearchAnime(page,query)
            AnimeSource.ANSLAYER -> anslyerSource.fetchSearchAnime(page, query)
            AnimeSource.CIMA_CLUB -> cimaClubSource.fetchSearchAnime(page, query, filters)
            AnimeSource.E3SK -> e3skSource.fetchSearchAnime(page, query, filters)
            AnimeSource.NETFLY -> netflySource.fetchSearchAnime(1,query)
            AnimeSource.SHED4U -> shed4uSource.fetchSearchAnime(page, query, filters)
            AnimeSource.AKWAM -> akwamSource.fetchSearchAnime(page, query, filters)
            AnimeSource.CIMALIGHT ->cimaLightSource.fetchSearchAnime(page,query, filters)
            AnimeSource.TOP_CINEMA -> topCinemaSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ARAB_SEED -> arabSeedSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ANIME4UP -> anime4upSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ZIMABADK -> zimabadkSource.fetchSearchAnime(page, query, filters)
            AnimeSource.FIVETV -> fiveTvSource.fetchSearchAnime(page, query, filters)
            AnimeSource.ANIMELEK -> animeLekSource.fetchSearchAnime(page, query, filters)
            AnimeSource.DAILY_MOTION -> dailymotionSource.fetchSearchAnime(page, query)
            AnimeSource.ANIMETAK -> animetakSource.fetchSearchAnime(page,query,filters)
            AnimeSource.CIMA_NOW ->cimaNowSource.fetchSearch(query)
            AnimeSource.CARTOONY -> cartoonySource.fetchSearchAnime(page, query, filters)
            AnimeSource.SPANKBANG -> spankBangSource.fetchSearch(page, query, filters)
            AnimeSource.HENTAI_TIME -> hentaiTimeSource.fetchSearchAnime(page, query, filters)
            AnimeSource.XVIDEOS -> xvideosSource.fetchSearchAnime(page, query, filters)
            AnimeSource.NXXHENTAI -> nxxhentaiSource.fetchSearchAnime(page, query, filters)
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
            AnimeSource.ARABDRAMA2 -> arabDrama2Source.fetchAnimeDetails(animeUrl)
            AnimeSource.ANIME_PHOENIX -> animePhoenixSource.fetchAnimeDetails(animeUrl)
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchAnimeDetails(animeUrl)!!
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchAnimeDetails(animeUrl)!!
            AnimeSource.ASIA2TV -> asia2TvSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ANIMEIAT -> animeiatSource.fetchAnimeDetails(animeUrl)
            AnimeSource.NETFLY -> netflySource.fetchAnimeDetails(animeUrl)
            AnimeSource.CIMA_CLUB -> cimaClubSource.fetchAnimeDetails(animeUrl)
            AnimeSource.EGYDEAD -> egyDeadSource.fetchAnimeDetails(animeUrl)
            AnimeSource.CIMALIGHT ->cimaLightSource.fetchAnimeDetails(animeUrl)
            AnimeSource.YACINETV -> yacineTvSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ANIME3RB -> anime3rbSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ANSLAYER -> anslyerSource.fetchAnimeDetails(animeUrl)
            AnimeSource.Esk -> eskSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ARABICTOONS -> arabicToonsSource.fetchAnimeDetails(animeUrl)
            AnimeSource.INTERNET_ARCHIVE -> internetArchiveSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ANIMERCO -> animercoSource.fetchAnimeDetails(animeUrl)
            AnimeSource.HIANIME -> hiAnimeSource.fetchAnimeDetails(animeUrl)!!
            AnimeSource.FULLREPLAYS -> fullReplaysSource.fetchAnimeDetails(animeUrl)
            AnimeSource.WITANIME -> witAnimeSource.fetchAnimeDetails(animeUrl)
            AnimeSource.CARTOONY -> cartoonySource.fetchAnimeDetails(animeUrl)
            AnimeSource.UHDMOVIES -> uhdMoviesSource.fetchAnimeDetails(animeUrl)
            AnimeSource.DRAMADRIP -> dramaDripSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ISQ -> isqSource.fetchAnimeDetails(animeUrl)
            AnimeSource.E3SK -> e3skSource.fetchAnimeDetails(animeUrl)
            AnimeSource.AKWAM -> akwamSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ARAB_SEED -> arabSeedSource.fetchAnimeDetails(animeUrl)
            AnimeSource.TOP_CINEMA -> topCinemaSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ANIME4UP -> anime4upSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ZIMABADK -> zimabadkSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ANIMETAK -> animetakSource.fetchAnimeDetails(animeUrl)
            AnimeSource.FIVETV -> fiveTvSource.fetchAnimeDetails(animeUrl)
            AnimeSource.SHED4U -> shed4uSource.fetchAnimeDetails(animeUrl)
            AnimeSource.ANIMELEK -> animeLekSource.fetchAnimeDetails(animeUrl)
            AnimeSource.DAILY_MOTION -> dailymotionSource.fetchAnimeDetails(animeUrl)
            AnimeSource.CIMA_NOW ->cimaNowSource.fetchAnimeDetails(animeUrl)
            AnimeSource.HENTAI_TIME -> hentaiTimeSource.fetchAnimeDetails(animeUrl)
            AnimeSource.NXXHENTAI -> nxxhentaiSource.fetchAnimeDetails(animeUrl)
            AnimeSource.XVIDEOS -> xvideosSource.fetchVideoDetails(SAnime().apply { url = animeUrl })
            AnimeSource.SPANKBANG -> spankBangSource.fetchVideoDetails(SAnime().apply { url = animeUrl })

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
                        thumbnail_url = "https://seo-michael.co.uk/content/images/2025/04/dlslogo.png"
                        description = "Live TV Channel from ${channelData.name}"
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
            AnimeSource.ARABDRAMA2 -> arabDrama2Source.fetchEpisodeList(animeUrl)
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchEpisodeList(animeUrl)
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchEpisodeList(animeUrl)
            AnimeSource.ASIA2TV -> asia2TvSource.fetchEpisodeList(animeUrl)
            AnimeSource.ANIMEIAT -> animeiatSource.fetchEpisodeList(animeUrl)
            AnimeSource.ANIME_PHOENIX -> animePhoenixSource.fetchEpisodeList(animeUrl)
            AnimeSource.EGYDEAD -> egyDeadSource.fetchEpisodeList(animeUrl)
            AnimeSource.NETFLY -> netflySource.fetchEpisodeList(animeUrl)
            AnimeSource.HIANIME -> hiAnimeSource.fetchEpisodeList(animeUrl)
            AnimeSource.ANIME3RB -> anime3rbSource.fetchEpisodeList(animeUrl)
            AnimeSource.YACINETV -> yacineTvSource.fetchEpisodeList(animeUrl)
            AnimeSource.ARABICTOONS -> arabicToonsSource.fetchEpisodeList(animeUrl)
            AnimeSource.WITANIME -> witAnimeSource.fetchEpisodeList(animeUrl)
            AnimeSource.ANIMETAK -> animetakSource.fetchEpisodeList(animeUrl)
            AnimeSource.CIMA_CLUB -> cimaClubSource.fetchEpisodeList(animeUrl)
            AnimeSource.INTERNET_ARCHIVE -> internetArchiveSource.fetchEpisodeList(animeUrl)
            AnimeSource.ANIMERCO -> animercoSource.fetchEpisodeList(animeUrl)
            AnimeSource.CIMALIGHT ->cimaLightSource.fetchEpisodeList(animeUrl)
            AnimeSource.UHDMOVIES -> uhdMoviesSource.fetchEpisodeList(animeUrl)
            AnimeSource.FULLREPLAYS -> fullReplaysSource.fetchEpisodeList(animeUrl)
            AnimeSource.CARTOONY -> cartoonySource.fetchEpisodeList(animeUrl)
            AnimeSource.DRAMADRIP -> dramaDripSource.fetchEpisodeList(animeUrl)
            AnimeSource.ANIME4UP -> anime4upSource.fetchEpisodeList(animeUrl)
            AnimeSource.TOP_CINEMA -> topCinemaSource.fetchEpisodeList(animeUrl)
            AnimeSource.ARAB_SEED -> arabSeedSource.fetchEpisodeList(animeUrl)
            AnimeSource.ISQ -> isqSource.fetchEpisodeList(animeUrl).reversed()
            AnimeSource.Esk -> eskSource.fetchEpisodeList(animeUrl)
            AnimeSource.E3SK -> e3skSource.fetchEpisodeList(animeUrl)
            AnimeSource.ANSLAYER -> anslyerSource.fetchEpisodeList(animeUrl)
            AnimeSource.SHED4U -> shed4uSource.fetchEpisodeList(animeUrl)
            AnimeSource.ZIMABADK -> zimabadkSource.fetchEpisodeList(animeUrl)
            AnimeSource.FIVETV -> fiveTvSource.fetchEpisodeList(animeUrl)
            AnimeSource.CIMA_NOW ->cimaNowSource.fetchEpisodeList(animeUrl)
            AnimeSource.ANIMELEK -> animeLekSource.fetchEpisodeList(animeUrl)
            AnimeSource.AKWAM -> akwamSource.fetchEpisodeList(animeUrl)
            AnimeSource.DAILY_MOTION -> dailymotionSource.fetchEpisodeList(animeUrl)
            AnimeSource.HENTAI_TIME -> hentaiTimeSource.fetchEpisodeList(animeUrl)
            AnimeSource.NXXHENTAI -> nxxhentaiSource.fetchEpisodeList(animeUrl)
            AnimeSource.SPANKBANG -> spankBangSource.fetchEpisodeList(SAnime().apply { url = animeUrl })
            AnimeSource.XVIDEOS -> xvideosSource.fetchEpisodeList(SAnime().apply { url = animeUrl })
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
            AnimeSource.SHED4U -> shed4uSource.fetchVideoList(episodeUrl)
            AnimeSource.ARAB_ANIME -> arabAnimeSource.fetchVideoList(episodeUrl)
            AnimeSource.OKANIME -> okAnimeSource.fetchVideoList(episodeUrl)
            AnimeSource.ARAB_DRAMA -> arabDramSource.fetchVideoList(episodeUrl)
            AnimeSource.ARABDRAMA2 -> arabDrama2Source.fetchVideoList(episodeUrl)
            AnimeSource.HIANIME -> hiAnimeSource.fetchVideoList(episodeUrl)
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchVideoList(episodeUrl)
            AnimeSource.AKWAM -> akwamSource.fetchVideoList(episodeUrl)
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchVideoList(episodeUrl)
            AnimeSource.ASIA2TV -> asia2TvSource.fetchVideoList(episodeUrl)
            AnimeSource.ANIMEIAT -> animeiatSource.fetchVideoList(episodeUrl)
            AnimeSource.EGYDEAD -> egyDeadSource.fetchVideoList(episodeUrl)
            AnimeSource.ANIME3RB -> anime3rbSource.fetchVideoList(episodeUrl)
            AnimeSource.CARTOONY -> cartoonySource.fetchVideoList(episodeUrl)
            AnimeSource.ARABICTOONS -> arabicToonsSource.fetchVideoList(episodeUrl)
            AnimeSource.INTERNET_ARCHIVE -> internetArchiveSource.fetchVideoList(episodeUrl)
            AnimeSource.ANIMERCO -> animercoSource.fetchVideoList(episodeUrl)
            AnimeSource.WITANIME -> witAnimeSource.fetchVideoList(episodeUrl)
            AnimeSource.CIMA_CLUB -> cimaClubSource.fetchVideoList(episodeUrl)
            AnimeSource.UHDMOVIES -> uhdMoviesSource.fetchVideoList(episodeUrl)
            AnimeSource.ANIME_PHOENIX -> animePhoenixSource.fetchVideoList(episodeUrl)
            AnimeSource.DRAMADRIP -> dramaDripSource.fetchVideoList(episodeUrl)
            AnimeSource.NETFLY -> netflySource.fetchVideoList(episodeUrl)
            AnimeSource.FULLREPLAYS -> fullReplaysSource.fetchVideoList(episodeUrl)
            AnimeSource.ISQ -> isqSource.fetchVideoList(episodeUrl)
            AnimeSource.E3SK -> e3skSource.fetchVideoList(episodeUrl)
            AnimeSource.Esk -> eskSource.fetchVideoList(episodeUrl)
            AnimeSource.TOP_CINEMA -> topCinemaSource.fetchVideoList(episodeUrl)
            AnimeSource.ARAB_SEED -> arabSeedSource.fetchVideoList(episodeUrl)
            AnimeSource.ANIMETAK -> animetakSource.fetchVideoList(episodeUrl)
            AnimeSource.ANIME4UP -> anime4upSource.fetchVideoList(episodeUrl)
            AnimeSource.CIMALIGHT ->cimaLightSource.fetchVideoList(episodeUrl)
            AnimeSource.ZIMABADK -> zimabadkSource.fetchVideoList(episodeUrl)
            AnimeSource.FIVETV -> fiveTvSource.fetchVideoList(episodeUrl)
            AnimeSource.ANIMELEK -> animeLekSource.fetchVideoList(episodeUrl)
            AnimeSource.DAILY_MOTION -> dailymotionSource.fetchVideoList(episodeUrl)
            AnimeSource.YACINETV -> yacineTvSource.fetchVideoList(episodeUrl)
            AnimeSource.ANSLAYER -> anslyerSource.fetchVideoList(episodeUrl)
            AnimeSource.CIMA_NOW ->cimaNowSource.fetchVideoList(episodeUrl)
            AnimeSource.HENTAI_TIME -> hentaiTimeSource.fetchVideoList(episodeUrl)
            AnimeSource.NXXHENTAI -> nxxhentaiSource.fetchVideoList(episodeUrl)
            AnimeSource.SPANKBANG -> spankBangSource.fetchVideoList(SEpisode().apply { url = episodeUrl })
            AnimeSource.XVIDEOS -> xvideosSource.fetchVideoList(SEpisode().apply { url = episodeUrl })
            AnimeSource.HUHU -> {
                // Get live stream link
                val video = huhuSource.fetchLiveStreamLink(episodeUrl)
                if (video != null) listOf(video) else emptyList()
            }

            AnimeSource.DADDY_LIVE -> daddyLiveSource.fetchLiveStreamLink(episodeUrl)!!
//                val video = daddyLiveSource.fetchLiveStreamLink(episodeUrl)
//                if (video != null) listOf(video) else emptyList()
//            }


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
            AnimeSource.ARABDRAMA2 -> arabDrama2Source.fetchMainSlider()
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.fetchMainSlider()
            AnimeSource.HIANIME -> hiAnimeSource.fetchPopularSeries(1).manga
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.fetchMainSlider()
            AnimeSource.ANIME_PHOENIX -> animePhoenixSource.fetchPopularSeries(1).manga
            AnimeSource.ASIA2TV -> asia2TvSource.fetchMainSlider()
            AnimeSource.ANIMEIAT -> animeiatSource.fetchMainSlider()
            AnimeSource.EGYDEAD -> egyDeadSource.fetchMainSlider()
            AnimeSource.ANIME3RB -> anime3rbSource.fetchMainSlider()
            AnimeSource.CARTOONY -> cartoonySource.fetchLatestUpdates(1).manga
            AnimeSource.Esk -> eskSource.fetchLatestUpdates(1).manga
            AnimeSource.E3SK -> e3skSource.fetchLatestUpdates(1).manga
            AnimeSource.NETFLY -> netflySource.fetchLatestUpdates(1).manga
            AnimeSource.ARABICTOONS -> arabicToonsSource.fetchLatestUpdates(1).manga
            AnimeSource.INTERNET_ARCHIVE -> internetArchiveSource.fetchMainSlider()
            AnimeSource.ANIMERCO -> animercoSource.fetchLatestUpdates(1).manga
            AnimeSource.WITANIME -> witAnimeSource.fetchMainSlider()
            AnimeSource.ANSLAYER -> anslyerSource.fetchLatestUpdates(1).manga
            AnimeSource.FULLREPLAYS -> fullReplaysSource.fetchLatestUpdates(1).manga
            AnimeSource.UHDMOVIES -> uhdMoviesSource.fetchMainSlider()
            AnimeSource.ANIMETAK -> animetakSource.fetchPopularSeries(1).manga
            AnimeSource.CIMALIGHT ->cimaLightSource.fetchMainSlider()
            AnimeSource.ISQ -> isqSource.fetchMainSlider()
            AnimeSource.AKWAM -> akwamSource.fetchMainSlider()
            AnimeSource.TOP_CINEMA -> topCinemaSource.fetchMainSlider()
            AnimeSource.ARAB_SEED -> arabSeedSource.fetchMainSlider()
            AnimeSource.ANIME4UP -> anime4upSource.fetchLatestUpdates(1).manga
            AnimeSource.DRAMADRIP -> dramaDripSource.fetchMainSlider()
            AnimeSource.ZIMABADK -> zimabadkSource.fetchLatestUpdates(1).manga
            AnimeSource.FIVETV -> fiveTvSource.fetchMainSlider()
            AnimeSource.YACINETV -> yacineTvSource.fetchPopularSeries(1).manga
            AnimeSource.SHED4U -> shed4uSource.fetchMainSlider()
            AnimeSource.CIMA_CLUB -> cimaClubSource.fetchMainSlider()
            AnimeSource.DAILY_MOTION -> dailymotionSource.fetchPopular(1).manga
            AnimeSource.CIMA_NOW ->cimaNowSource.fetchPopularSeries(1).manga
            AnimeSource.ANIMELEK -> animeLekSource.fetchPopularSeries(1).manga
            AnimeSource.SPANKBANG -> spankBangSource.fetchPopularSeries(1).manga
            AnimeSource.HENTAI_TIME -> hentaiTimeSource.fetchPopular(1).manga
            AnimeSource.NXXHENTAI -> nxxhentaiSource.fetchLatestUpdates(1).manga
            AnimeSource.XVIDEOS -> xvideosSource.fetchPopular(1).manga
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
            AnimeSource.ARABDRAMA2 -> arabDrama2Source.fetchHomePageLatestEpisodes()
            AnimeSource.NETFLIX_MIRROR -> emptyList()
            AnimeSource.PRIME_VIDEO_MIRROR -> emptyList()
            AnimeSource.CARTOONY -> cartoonySource.fetchLatestUpdates(1).manga
            AnimeSource.ASIA2TV -> emptyList()
            AnimeSource.ANSLAYER -> emptyList()
            AnimeSource.ANIME_PHOENIX -> animePhoenixSource.fetchLatestUpdates(1).manga
            AnimeSource.ANIMEIAT -> animeiatSource.fetchLatestUpdatess(1)
            AnimeSource.EGYDEAD -> emptyList()
            AnimeSource.YACINETV -> emptyList()
            AnimeSource.ANIME3RB -> anime3rbSource.fetchHomePageLatestAnimes()
            AnimeSource.ARABICTOONS -> arabicToonsSource.fetchLatestUpdates(1).manga
            AnimeSource.INTERNET_ARCHIVE -> emptyList()
            AnimeSource.CIMALIGHT ->cimaLightSource.fetchPopularSeries(1).manga
            AnimeSource.ANIMERCO -> emptyList()
            AnimeSource.WITANIME -> emptyList()
            AnimeSource.UHDMOVIES -> emptyList()
            AnimeSource.CIMA_CLUB -> emptyList()
            AnimeSource.FULLREPLAYS -> fullReplaysSource.fetchLatestUpdates(1).manga
            AnimeSource.FIVETV -> fiveTvSource.fetchHomePageLatestEpisodes()
            AnimeSource.AKWAM -> akwamSource.fetchPopularSeries(1).manga
            AnimeSource.ARAB_SEED -> arabSeedSource.fetchHomePageLatestEpisodes()
            AnimeSource.DAILY_MOTION -> dailymotionSource.fetchPopular(1).manga
            AnimeSource.HIANIME -> hiAnimeSource.fetchRecentlyUpdated(1).manga
            AnimeSource.TOP_CINEMA -> topCinemaSource.fetchLatestUpdates(1).manga
            AnimeSource.DRAMADRIP -> dramaDripSource.fetchLatestUpdates(1).manga
            AnimeSource.ISQ -> isqSource.fetchPopularSeries(1).manga
            AnimeSource.SHED4U -> shed4uSource.fetchLatestUpdates(1).manga
            AnimeSource.ANIMETAK -> animetakSource.fetchPopularSeries(1).manga
            AnimeSource.ANIME4UP -> anime4upSource.fetchLatestUpdates(1).manga
            AnimeSource.ZIMABADK -> zimabadkSource.fetchPopularSeries(1).manga
            AnimeSource.ANIMELEK -> animeLekSource.fetchPopularSeries(1).manga
            AnimeSource.SPANKBANG -> spankBangSource.fetchLatestUpdates(1).manga
            AnimeSource.Esk -> eskSource.fetchLatestUpdates(1).manga
            AnimeSource.NETFLY -> netflySource.fetchLatestUpdates(1).manga
            AnimeSource.E3SK -> e3skSource.fetchLatestUpdates(1).manga
            AnimeSource.HENTAI_TIME -> hentaiTimeSource.fetchLatestUpdates(1).manga
            AnimeSource.CIMA_NOW ->cimaNowSource.fetchMainSlider(1).manga
            AnimeSource.XVIDEOS -> xvideosSource.fetchLatestUpdates(1).manga
            AnimeSource.NXXHENTAI -> nxxhentaiSource.fetchPopular(1).manga
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
            AnimeSource.SHED4U -> shed4uSource.getFilterList()
            AnimeSource.ARAB_ANIME -> arabAnimeSource.getFilterList()
            AnimeSource.OKANIME -> okAnimeSource.getFilterList()
            AnimeSource.YACINETV -> yacineTvSource.getFilterList()
            AnimeSource.ARAB_DRAMA -> arabDramSource.getFilterList()
            AnimeSource.ANIME_PHOENIX -> animePhoenixSource.getFilterList()
            AnimeSource.NETFLIX_MIRROR -> netflixMirrorSource.getFilterList()
            AnimeSource.PRIME_VIDEO_MIRROR -> primeVideoMirrorSource.getFilterList()
            AnimeSource.ASIA2TV -> asia2TvSource.getFilterList()
            AnimeSource.CIMA_CLUB -> cimaClubSource.getFilterList()
            AnimeSource.NETFLY ->AnimeFilterList(emptyList())
            AnimeSource.ANIMEIAT -> animeiatSource.getFilterList()
            AnimeSource.FIVETV -> fiveTvSource.getFilterList()
            AnimeSource.EGYDEAD -> egyDeadSource.getFilterList()
            AnimeSource.ANIME3RB -> anime3rbSource.getFilterList()
            AnimeSource.HUHU -> AnimeFilterList(emptyList()) // Live TV doesn't need complex filters
            AnimeSource.DADDY_LIVE -> AnimeFilterList(emptyList())
            AnimeSource.FREE_TV -> AnimeFilterList(emptyList()) // Live TV doesn't need complex filters
            AnimeSource.ARABICTOONS -> AnimeFilterList(emptyList())
            AnimeSource.INTERNET_ARCHIVE -> internetArchiveSource.getFilterList()
            AnimeSource.ANIMERCO -> animercoSource.getFilterList()
            AnimeSource.ANSLAYER -> AnimeFilterList(emptyList())
            AnimeSource.CIMALIGHT ->cimaLightSource.getFilterList()
            AnimeSource.FULLREPLAYS -> AnimeFilterList(emptyList())
            AnimeSource.WITANIME -> witAnimeSource.getFilterList()
            AnimeSource.ANIMELEK -> animeLekSource.getFilterList()
            AnimeSource.E3SK -> AnimeFilterList(emptyList())
            AnimeSource.DRAMADRIP -> dramaDripSource.getFilterList()
            AnimeSource.DAILY_MOTION ->  AnimeFilterList(emptyList())
            AnimeSource.UHDMOVIES -> uhdMoviesSource.getFilterList()
            AnimeSource.HIANIME ->AnimeFilterList(emptyList())
            AnimeSource.AKWAM -> akwamSource.getFilterList()
            AnimeSource.Esk -> AnimeFilterList(emptyList())
            AnimeSource.ZIMABADK -> zimabadkSource.getFilterList()
            AnimeSource.ANIMETAK -> animetakSource.getFilterList()
            AnimeSource.ISQ -> isqSource.getFilterList()
            AnimeSource.TOP_CINEMA -> topCinemaSource.getFilterList()
            AnimeSource.SPANKBANG -> spankBangSource.getFilterList()
            AnimeSource.ANIME4UP ->AnimeFilterList(emptyList())
            AnimeSource.CARTOONY -> AnimeFilterList(emptyList())
            AnimeSource.CIMA_NOW ->AnimeFilterList(emptyList())
            AnimeSource.ARABDRAMA2 -> arabDrama2Source.getFilterList()
            AnimeSource.ARAB_SEED -> arabSeedSource.getFilterList()
            AnimeSource.HENTAI_TIME -> hentaiTimeSource.getFilterList()
            AnimeSource.NXXHENTAI -> nxxhentaiSource.getFilterList()
            AnimeSource.XVIDEOS -> xvideosSource.getFilterList()
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

//    suspend fun fetchLiveStream(channel: SLiveTv): Video? {
//        return when (channel.source?.let { AnimeSource.valueOf(it) }) {
//            AnimeSource.HUHU -> huhuSource.fetchLiveStreamLink(channel.url)
//            AnimeSource.DADDY_LIVE -> daddyLiveSource.fetchLiveStreamLink(channel.url)
//            AnimeSource.FREE_TV -> freeTVSource.fetchLiveStreamLink(channel.url)
//            else -> null
//        }
//    }

    fun getCurrentSourceName(): String {
        return currentSource.displayName
    }

    fun getAllSources(): List<AnimeSource> {
        val adultContentUnlocked = isAdultContentUnlocked(context)
        return AnimeSource.values().filter { !it.isNsfw || adultContentUnlocked }
    }
}