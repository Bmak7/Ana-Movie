package com.faselhd.app.db

import androidx.room.TypeConverter
import com.faselhd.app.models.DownloadState

class TypeConverters {
    @TypeConverter
    fun fromDownloadState(value: DownloadState): String = value.name

    @TypeConverter
    fun toDownloadState(value: String): DownloadState = DownloadState.valueOf(value)
}