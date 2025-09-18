package com.faselhd.app.network.sources // Or your actual package name

import android.content.Context
import android.util.Log
import com.faselhd.app.models.*
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.net.URLEncoder
import java.util.Base64
import kotlin.text.Charsets
import kotlin.text.Regex

class CimaNowSource(private val context: Context) {
    companion object {
        const val name = "CimaNow"
        const val BASE_URL = "https://cimanow.cc"
        const val lang = "ar"
        const val supportsLatest = true
    }

    // --- OKHTTP CLIENT SETUP ---
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .ignoreAllSSLErrors()
            .cache(Cache(File(context.cacheDir, "http_cache"), 50L * 1024L * 1024L))
            .build()
    }
    private val app = Requests(client)
    // ============================== Main Slider ==============================
    suspend fun fetchMainSlider(page: Int): MangaPage = withContext(Dispatchers.IO) {
        // The slider is only on the first page.
        if (page > 1) {
            return@withContext MangaPage(emptyList(), false)
        }
        val document = app.get(BASE_URL).document
        // Selector for a typical slider section. Adjust if CimaNow's structure is different.
        val sliderItems = document.select("div.slick-track div.post-box").mapNotNull {
            toSAnime(it)
        }
        MangaPage(sliderItems, false)
    }

    // ============================== Popular Series ==============================
    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        // Using "Latest Additions" as the source for popular/trending content.
        val url = "$BASE_URL/الأحدث/page/$page/"
        val document = app.get(url).document
        val decodedDoc = decodeHtml(document)
        val items = decodedDoc.select("section article").mapNotNull { toSAnime(it) }
        // Assume there's a next page since it's a paginated category.
        MangaPage(items, items.isNotEmpty())
    }

    suspend fun fetchSearch(query: String): MangaPage = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE_URL/?s=$encodedQuery"
        val document = app.get(url).document
        val decodedDoc = decodeHtml(document)
        val items = decodedDoc.select("section article").mapNotNull { toSAnime(it) }
        MangaPage(items, false) // Search page doesn't have pagination
    }

    // ============================== Details & Episodes ==============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val document = app.get(animeUrl).document
        val decodedDoc = decodeHtml(document)

        return@withContext SAnime().apply {
            url = animeUrl
            title = decodedDoc.selectFirst("div.Single-title h1")?.text()
                ?.replace(Regex("""الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي|Cima Now|-|سيما ناو|ج\d+\|\s*"""), "")
                ?.trim()
            thumbnail_url = decodedDoc.selectFirst("figure img")?.attr("src")
            description = decodedDoc.selectFirst("li[aria-label=story] p")?.text()
            genre = decodedDoc.select("ul li a[href*='/genre/']").joinToString(", ") { it.text() }
            status = if (decodedDoc.title().contains("فيلم")) SAnime.COMPLETED else SAnime.ONGOING
        }
    }


    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val episodeList = mutableListOf<SEpisode>()
        val mainDocument = app.get(animeUrl).document
        val decodedMainDoc = decodeHtml(mainDocument)

        // Find all season tabs. If none, the current page is treated as the only season.
        val seasonElements = decodedMainDoc.select("section[aria-label=seasons] ul li a")

        if (seasonElements.isNotEmpty()) {
            // Multi-season show
            for (seasonElement in seasonElements) {
                val seasonUrl = seasonElement.attr("href")
                try {
                    val seasonDoc = decodeHtml(app.get(seasonUrl).document)
                    val seasonName = seasonElement.text()
                    val episodesInSeason = seasonDoc.select("section[aria-label=details] ul#eps li")

                    episodesInSeason.reversed().forEachIndexed { index, ep ->
                        episodeList.add(
                            SEpisode().apply {
                                url = ep.selectFirst("a")?.attr("href") // Use the direct link
                                name = "$seasonName: ${ep.selectFirst("a")?.text() ?: "الحلقة ${index + 1}"}"
                                episode_number = (index + 1).toFloat()
                            }
                        )
                    }
                } catch (e: Exception) {
                    Log.e("CimaNow", "Failed to fetch or parse season: $seasonUrl", e)
                }
            }
        } else {
            // Single-season show or movie
            val episodesOnPage = decodedMainDoc.select("section[aria-label=details] ul#eps li")
            if (episodesOnPage.isNotEmpty()) {
                // It's a series with one season
                episodesOnPage.reversed().forEachIndexed { index, ep ->
                    episodeList.add(
                        SEpisode().apply {
                            url = ep.selectFirst("a")?.attr("href")
                            name = ep.selectFirst("a")?.text() ?: "الحلقة ${index + 1}"
                            episode_number = (index + 1).toFloat()
                        }
                    )
                }
            } else {
                // It's a movie, create a single "episode" for the watch button
                episodeList.add(
                    SEpisode().apply {
                        url = animeUrl // The movie URL itself leads to the watch links
                        name = "مشاهدة الفيلم"
                        episode_number = 1f
                    }
                )
            }
        }
        return@withContext episodeList
    }



    // ============================== Video Links ==============================
    // ============================== Video Links ==============================
    // ============================== Video Links ==============================
    suspend fun fetchVideoList(episodeId: String): List<Video> = withContext(Dispatchers.IO) {
        val videoList = mutableListOf<Video>()

        // The URL from fetchEpisodeList is now the correct ID for the AJAX call
        val ajaxUrl = "$BASE_URL/wp-content/themes/Cima%20Now%20New/core.php?action=switch&id=$episodeId"

        try {
            val document = app.get(ajaxUrl).document

            // This should now contain the iframe from the AJAX response
            val iframeSrc = document.selectFirst("iframe")?.attr("src")

            if (iframeSrc.isNullOrBlank()) {
                Log.w("CimaNow", "AJAX response did not contain an iframe for episode ID: $episodeId")
                return@withContext emptyList()
            }

            Log.d("CimaNow", "Found iframe: $iframeSrc")
            videoList.addAll(extractVideosFromUrl(iframeSrc, "CimaNow Server"))

        } catch (e: Exception) {
            Log.e("CimaNow", "AJAX call failed for episode ID: $episodeId", e)
        }

        return@withContext videoList.distinctBy { it.url }
    }
    private suspend fun extractVideosFromUrl(url: String, name: String): List<Video> {
        println("DEBUG: extractVideosFromUrl called with url: $url, name: $name")

        val videos = mutableListOf<Video>()
        try {
            when {
                "vidguard" in url || "vid-guard" in url -> {
                    println("DEBUG: Detected vidguard/vid-guard URL")
                    val doc = app.get(url).document
                    val scriptData = doc.select("script:containsData(eval)").firstOrNull()?.data() ?: ""

                    println("DEBUG: Script data length: ${scriptData.length}")
                    if (scriptData.isNotEmpty()) {
                        println("DEBUG: Found eval script, but JS engine not implemented")
                    }

                    Log.w("CimaNow", "Vidguard extractor needs a JS engine, which is not implemented here.")
                    println("WARN: Vidguard extractor needs JS engine - skipping")
                }
                // Many servers use the same packed JS logic
                "vadbam" in url || "viidshare" in url || "vidpro" in url || "govid" in url || "vidlook" in url -> {
                    println("DEBUG: Detected known video host: $url")
                    val doc = app.get(url).document
                    val script = doc.select("script:containsData(eval)").html()

                    println("DEBUG: Found script with eval data, length: ${script.length}")

                    val unpacked = JsUnpacker.unpack(script)
                    if (unpacked != null) {
                        println("DEBUG: Successfully unpacked JS, length: ${unpacked.length}")

                        val regex = Regex("""file:"(https://[^"]*)"""")
                        val matches = regex.findAll(unpacked).toList()

                        println("DEBUG: Found ${matches.size} video URL matches in unpacked JS")

                        matches.forEach { match ->
                            val videoUrl = match.groupValues[1]
                            println("DEBUG: Adding video from regex match: $videoUrl")
                            videos.add(Video(videoUrl, name, videoUrl))
                        }
                    } else {
                        println("DEBUG: JS unpacking failed or returned null")
                    }
                }
                // Handle direct sources if available
                else -> {
                    println("DEBUG: Handling as direct source URL")
                    val doc = app.get(url).document
                    val sourceElements = doc.select("source")

                    println("DEBUG: Found ${sourceElements.size} source elements")

                    sourceElements.forEach { source ->
                        val videoUrl = source.attr("src")
                        val quality = source.attr("size") + "p"
                        println("DEBUG: Adding direct source - URL: $videoUrl, Quality: $quality")
                        videos.add(Video(videoUrl, quality, videoUrl))
                    }

                    if (sourceElements.isEmpty()) {
                        println("DEBUG: No source elements found in document")
                    }
                }
            }
        } catch (e: Exception) {
            println("ERROR: Exception in extractVideosFromUrl: ${e.javaClass.simpleName} - ${e.message}")
            Log.e("CimaNow", "Error extracting from $url", e)
            e.printStackTrace()
        }

        println("DEBUG: Returning ${videos.size} videos from extractVideosFromUrl")
        return videos
    }


    // ============================== Helper Functions ==============================
    private fun toSAnime(element: Element): SAnime? {
        val linkElement = element.selectFirst("a") ?: return null
        val title = element.selectFirst("li[aria-label=title]")?.text() ?: return null
        if (element.select("a").text().contains("الكل")) return null

        return SAnime().apply {
            this.title = Regex("""موسم \d+|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي|\|""").replace(title, "").trim()
            this.url = linkElement.attr("href")
            this.thumbnail_url = element.selectFirst("img.lazy")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }
            this.status = if (title.contains("فيلم")) SAnime.COMPLETED else SAnime.ONGOING
        }
    }

    private fun decodeHtml(doc: Document): Document {
        if (!doc.toString().contains("hide_my_HTML_")) return doc

        try {
            val scriptData = doc.selectFirst("script")?.data() ?: return doc
            val obfuscatedContent = scriptData.substringAfter("= '").substringBefore("';")
            val lastNumberMatch = Regex("-(\\d+)").findAll(scriptData).lastOrNull()
            val lastNumber = lastNumberMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val decodedHtml = decodeObfuscatedString(obfuscatedContent.replace(Regex("['+\\n\" ]"), ""), -lastNumber)
            return Jsoup.parse(decodedHtml)
        } catch (e: Exception) {
            return doc
        }
    }

    private fun decodeObfuscatedString(concatenated: String, lastNumber: Int): String {
        val output = StringBuilder()
        concatenated.split('.').forEach { segment ->
            if (segment.isNotEmpty()) {
                try {
                    val decodedSegment = String(Base64.getDecoder().decode(segment), Charsets.UTF_8)
                    val numbersOnly = decodedSegment.filter { it.isDigit() }
                    if (numbersOnly.isNotEmpty()) {
                        val charCode = numbersOnly.toInt() + lastNumber
                        output.append(charCode.toChar())
                    }
                } catch (e: Exception) {
                    // Ignore segments that fail to decode
                }
            }
        }
        return output.toString()
    }
}

// A simple JS unpacker utility is required for some sources.
// This is a basic implementation; a more robust one might be needed.
object JsUnpacker {
    fun unpack(packedJs: String): String? {
        val regex = Regex("""eval\(function\(p,a,c,k,e,d\)\{(.+)\}\((.+)\)\)""")
        val match = regex.find(packedJs) ?: return null

        val payload = match.groupValues[1]
        val args = match.groupValues[2]

        // This is a simplified version. A full implementation would need to
        // properly parse and execute the JS logic. For many common packers,
        // simply extracting and splitting the arguments works.
        try {
            val p = args.substringBefore(".split('|')").trim('\'')
            val a = args.substringAfter(",").substringBefore(",").toIntOrNull() ?: 0
            val c = args.substringAfter(",$a,").substringBefore(",").toIntOrNull() ?: 0
            val k = args.substringAfter(",$c,").substringBefore(".").trim('\'').split('|')

            // This is a placeholder for the actual de-obfuscation logic
            // which is too complex to replicate without a full JS engine.
            // However, often the useful links are visible in the payload itself.
            return payload + k.joinToString("|")
        } catch (e: Exception) {
            return null
        }
    }
}