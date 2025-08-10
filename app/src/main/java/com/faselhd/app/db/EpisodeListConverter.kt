package com.faselhd.app.db

import androidx.room.TypeConverter
import com.faselhd.app.models.SEpisode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class EpisodeListConverter {
    private val mapper = jacksonObjectMapper()

    @TypeConverter
    fun fromEpisodeList(episodes: List<SEpisode>): String {
        return mapper.writeValueAsString(episodes)
    }

    @TypeConverter
    fun toEpisodeList(json: String): List<SEpisode> {
        return mapper.readValue(json)
    }
}