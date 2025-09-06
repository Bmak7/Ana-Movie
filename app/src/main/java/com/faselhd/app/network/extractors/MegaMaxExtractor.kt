

package com.faselhd.app.network.extractors


import com.faselhd.app.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class MegaMaxExtractor(
    private val client: OkHttpClient,
    // Pass instances of all other required extractors to the constructor
    private val doodExtractor: DoodExtractor,
    private val voeExtractor: VoeExtractor,
    private val mixDropExtractor: MixDropExtractor,
    private val streamWishExtractor: StreamWishExtractor,
    private val streamTapeExtractor: StreamTapeExtractor,
    private val mp4uploadExtractor: Mp4uploadExtractor,
    private val vidTubeExtractor: VidTubeExtractor,
    private val mivalyoExtractor: MivalyoExtractor
    // Add other extractors here as you create them...
    // private val filemoonExtractor: FilemoonExtractor,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    //region Data Models for JSON Parsing
    @Serializable
    private data class MegaMaxMirror(
        @SerialName("link") val link: String,
        @SerialName("driver") val driver: String // Useful for identifying the host
    )

    @Serializable
    private data class MegaMaxQualityLevel(
        @SerialName("label") val label: String, // e.g., "1080p", "720p"
        @SerialName("mirrors") val mirrors: List<MegaMaxMirror>
    )

    @Serializable
    private data class MegaMaxStreams(
        @SerialName("data") val data: List<MegaMaxQualityLevel>
    )

    @Serializable
    private data class MegaMaxProps(
        @SerialName("streams") val streams: MegaMaxStreams
    )

    @Serializable
    private data class MegaMaxResponse(
        @SerialName("props") val props: MegaMaxProps
    )
    //endregion

    /**
     * Main function to extract final video files from a MegaMax URL.
     * It first gets server links from MegaMax, then uses other extractors
     * to get the final .mp4 or .m3u8 links from those servers.
     *
     * @param url The URL of the MegaMax iframe page.
     * @return A flat list of all resolvable Video objects.
     */
    fun videosFromUrl(url: String): List<Video> {
        // 1. Get the list of server pages from MegaMax
        val serverPages = getServerPages(url)
        if (serverPages.isEmpty()) {
            println("MegaMaxExtractor: No server pages found.")
            return emptyList()
        }

        println("MegaMaxExtractor: Found ${serverPages.size} server pages. Now extracting final links...")

        // 2. Concurrently run the appropriate extractor for each server page
        return runBlocking(Dispatchers.IO) {
            serverPages.map { server ->
                async {
                    extractFromServer(server.url, server.quality, server.host)
                }
            }.awaitAll().flatten()
        }
    }

    /**
     * Delegates extraction to the correct specialized extractor based on the host.
     */
    private fun extractFromServer(url: String, quality: String, host: String): List<Video> {
        println("MegaMaxExtractor: Delegating to extractor for host '$host' with URL: $url")
        return when {
            host.contains("doo") || host.contains("d-s") || host.contains("vide0") -> doodExtractor.videosFromUrl(url, quality)
            host.contains("voe") -> voeExtractor.videosFromUrl(url)
            host.contains("mixdrop") -> mixDropExtractor.videosFromUrl(url, quality)
            host.contains("streamwish") || host.contains("wish") -> streamWishExtractor.videosFromUrl(url, quality)
            host.contains("streamtape") -> streamTapeExtractor.videosFromUrl(url, quality)
            host.contains("mp4upload") -> mp4uploadExtractor.videosFromUrl(url, quality)
            host.contains("vidtube")  -> vidTubeExtractor.videosFromUrl(url)
            host.contains("mivalyo") || host.contains("vidhide") -> {
                println("mivalyo mivalyo url: $url")
                mivalyoExtractor.videosFromUrl(url)
            }
            // Add cases for other extractors here
            // host.contains("filemoon") -> filemoonExtractor.videosFromUrl(url, quality)

            else -> {
                println("MegaMaxExtractor: No extractor available for host: $host")
                emptyList()
            }
        }
    }

    private data class ServerPage(val url: String, val quality: String, val host: String)

    /**
     * Fetches the initial list of server pages from MegaMax.
     */
    private fun getServerPages(url: String): List<ServerPage> {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("X-Inertia", "true")
                .addHeader("X-Inertia-Version", "073aceb6c2dab1e478df72b19687c856")
                .addHeader("X-Inertia-Partial-Data", "streams")
                .addHeader("X-Inertia-Partial-Component", "files/mirror/video")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val responseBody = response.body?.string() ?: return emptyList()

                val megaMaxResponse = json.decodeFromString<MegaMaxResponse>(responseBody)
                val streamsData = megaMaxResponse.props.streams.data

                return streamsData.flatMap { qualityLevel ->
                    qualityLevel.mirrors.map { mirror ->
                        val fullUrl = if (mirror.link.startsWith("//")) "https:${mirror.link}" else mirror.link
                        ServerPage(
                            url = fullUrl,
                            quality = "${mirror.driver.capitalize()} - ${qualityLevel.label}",
                            host = mirror.driver.toLowerCase()
                        )
                    }
                }
            }
        } catch (e: IOException) {
            println("MegaMaxExtractor: Error fetching server pages: ${e.message}")
            return emptyList()
        } catch (e: Exception) {
            println("MegaMaxExtractor: An unexpected error occurred: ${e.message}")
            return emptyList()
        }
    }
}
