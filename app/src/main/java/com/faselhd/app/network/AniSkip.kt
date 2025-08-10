package com.faselhd.app.network

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object AniSkip {
    private val client = OkHttpClient()
    private val mapper = jacksonObjectMapper()

    suspend fun getResult(
        malId: Int,
        episodeNumber: Int,
        episodeLength: Long
    ): Pair<Long, List<Stamp>>? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://api.aniskip.com/v2/skip-times/$malId/$episodeNumber?types[]=ed&types[]=mixed-ed&types[]=mixed-op&types[]=op&types[]=recap&episodeLength=${episodeLength / 1000L}"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) return@withContext null

                val responseBody = response.body?.string() ?: return@withContext null
                val res = mapper.readValue<AniSkipResponse>(responseBody)

                if (res.found && !res.results.isNullOrEmpty()) {
                    (res.results.first().episodeLength * 1000).toLong() to res.results
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    data class AniSkipResponse(
        @JsonProperty("found") val found: Boolean,
        @JsonProperty("results") val results: List<Stamp>?,
        @JsonProperty("message") val message: String?,
        @JsonProperty("statusCode") val statusCode: Int
    )

    data class Stamp(
        @JsonProperty("interval") val interval: AniSkipInterval,
        @JsonProperty("skipType") val skipType: String,
        @JsonProperty("skipId") val skipId: String,
        @JsonProperty("episodeLength") val episodeLength: Double
    )

    data class AniSkipInterval(
        @JsonProperty("startTime") val startTime: Double,
        @JsonProperty("endTime") val endTime: Double
    )
}