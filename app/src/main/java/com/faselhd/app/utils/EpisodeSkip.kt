package com.faselhd.app.utils

import com.example.myapplication.R // Your app's R file
import com.faselhd.app.models.SAnime
import com.faselhd.app.network.AniSkip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.util.Log



object EpisodeSkip {
    // We can simplify this for your use case
    enum class SkipType(val text: String) {
        OPENING("Skip Opening"),
        ENDING("Skip Ending"),
        RECAP("Skip Recap"),
        MIXED_OPENING("Skip Intro"),
        MIXED_ENDING("Skip Outro")
    }

    data class SkipStamp(
        val type: SkipType,
        val startMs: Long,
        val endMs: Long,
    )
    private val client = OkHttpClient()


    // A simple in-memory cache
    private val cachedStamps = mutableMapOf<String, List<SkipStamp>>()

    suspend fun getStamps(
        anime: SAnime,
        episodeNumber: Int,
        episodeDurationMs: Long,
    ): List<SkipStamp> {
        // Use a unique key for the cache
        val cacheKey = "${anime.url}_$episodeNumber"
        cachedStamps[cacheKey]?.let { return it }

        // We need a MAL ID. We can try to get it from the anime title.
        // This is a very simplified approach. A more robust app would store the MAL ID.
        val malId = anime.title?.let { getMalIdFromName(it) } ?: return emptyList()

        return withContext(Dispatchers.IO) {
            val results = AniSkip.getResult(malId, episodeNumber, episodeDurationMs)
            val stamps = results?.second?.mapNotNull { stamp ->
                val skipType = when (stamp.skipType) {
                    "op" -> SkipType.OPENING
                    "ed" -> SkipType.ENDING
                    "recap" -> SkipType.RECAP
                    "mixed-ed" -> SkipType.MIXED_ENDING
                    "mixed-op" -> SkipType.MIXED_OPENING
                    else -> null
                } ?: return@mapNotNull null

                SkipStamp(
                    type = skipType,
                    startMs = (stamp.interval.startTime * 1000).toLong(),
                    endMs = (stamp.interval.endTime * 1000).toLong()
                )
            } ?: emptyList()

            cachedStamps[cacheKey] = stamps
            stamps
        }
    }

    // A very basic placeholder for getting a MAL ID.
    // In a real app, you would get this from a more reliable source.
//    private suspend fun getMalIdFromName(name: String): Int? {
//        // This is a placeholder. For a real implementation, you would need
//        // to either scrape the faselhd page for a MAL link or use an API
//        // like Kitsu/AniList to search for the anime by name.
//        // For testing, you can hardcode some values.
//        return when {
//            name.contains("One Piece", ignoreCase = true) -> 21
//            name.contains("Attack on Titan", ignoreCase = true) -> 16498
//            name.contains("Shingeki no", ignoreCase = true) -> 16498
//            // Add more known mappings for your tests
//            else -> null
//        }
//    }

    private const val TAG = "AniListMALFetcher"
    private fun cleanAnimeName(rawName: String): String {
        return rawName
            // 1. Remove common Arabic words for anime/episode/season + Arabic ordinals
            .replace(
                "(?i)\\b(انمي|أنمي|الحلقة|حلقة|الموسم|موسم|الأول|الاول|الثاني|الثالث|الرابع|الخامس|السادس|السابع|الثامن|التاسع|العاشر)\\b"
                    .toRegex(), " "
            )
            // 2. Remove English words for episode/season/part
            .replace("(?i)\\b(season|episode|ep|part)\\b".toRegex(), " ")
            // 3. Remove numbers (Arabic and English)
            .replace("[٠-٩0-9]+".toRegex(), " ")
            // 4. Remove dash separators often used in titles from sites
            .replace("[–—-]".toRegex(), " ")
            // 5. Remove parentheses/brackets but keep inside text if relevant
            .replace("[\\(\\)\\[\\]{}]".toRegex(), " ")
            // 6. Remove extra punctuation except ! and ?
            .replace("[,:;\"'`~_^|<>]".toRegex(), " ")
            // 7. Replace multiple spaces with a single space
            .replace("\\s{2,}".toRegex(), " ")
            // 8. Trim spaces
            .trim()
    }


    suspend fun getMalIdFromName(name: String): Int? = withContext(Dispatchers.IO) {
        val cleanName = cleanAnimeName(name)
        Log.i(TAG, "Searching MAL ID for: \"$cleanName\"")

        try {
            // GraphQL query to get MAL ID from AniList
            val query = """
            query {
              Media(search: "$cleanName", type: ANIME) {
                idMal
                title {
                  romaji
                  english
                }
              }
            }
        """.trimIndent()

            val body = JSONObject().apply {
                put("query", query)
            }

            Log.d(TAG, "Sending query to AniList API...")

            val request = Request.Builder()
                .url("https://graphql.anilist.co")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val rawJson = response.body?.string()
                Log.d(TAG, "HTTP Status: ${response.code}")
                Log.v(TAG, "Raw Response: $rawJson")

                if (!response.isSuccessful || rawJson.isNullOrBlank()) {
                    Log.w(TAG, "AniList request failed or empty response.")
                    return@withContext null
                }

                val json = JSONObject(rawJson)
                val media = json.optJSONObject("data")?.optJSONObject("Media")
                if (media == null) {
                    Log.w(TAG, "No 'Media' object found in response.")
                    return@withContext null
                }

                val idMal = media.optInt("idMal", 0)
                val titleRomaji = media.optJSONObject("title")?.optString("romaji")
                val titleEnglish = media.optJSONObject("title")?.optString("english")

                Log.i(TAG, "Matched anime: $titleRomaji / $titleEnglish → MAL ID: $idMal")

                return@withContext if (idMal != 0) idMal else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching MAL ID: ${e.message}", e)
            null
        }
    }
}