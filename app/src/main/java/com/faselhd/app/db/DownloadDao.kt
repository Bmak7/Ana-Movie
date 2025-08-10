package com.faselhd.app.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.faselhd.app.models.Download
import kotlinx.coroutines.flow.Flow

// In DownloadDao.kt

@Dao
interface DownloadDao {
    @Upsert
    suspend fun upsert(download: Download)

    @Query("SELECT * FROM downloads WHERE episodeUrl = :episodeUrl")
    fun getDownloadFlow(episodeUrl: String): Flow<Download?>

    @Query("SELECT * FROM downloads WHERE episodeUrl = :episodeUrl")
    suspend fun getDownload(episodeUrl: String): Download?

    @Query("DELETE FROM downloads WHERE episodeUrl = :episodeUrl")
    suspend fun delete(episodeUrl: String)

    // *** ADD THIS REQUIRED FUNCTION ***
    @Query("SELECT * FROM downloads ORDER BY animeTitle, episodeName")
    fun getAllDownloadsFlow(): Flow<List<Download>>
}