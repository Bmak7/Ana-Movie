package com.faselhd.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.faselhd.app.models.WatchHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(watchHistory: WatchHistory)

    @Query("SELECT * FROM watch_history ORDER BY timestamp DESC")
    fun getWatchHistory(): Flow<List<WatchHistory>>

    @Query("SELECT * FROM watch_history WHERE episodeUrl = :episodeUrl")
    suspend fun getWatchHistoryByEpisodeUrl(episodeUrl: String): WatchHistory?


    @Query("DELETE FROM watch_history WHERE episodeUrl = :episodeUrl")
    suspend fun delete(episodeUrl: String)

    // MODIFY THIS QUERY
    @Query("""
    SELECT * FROM watch_history 
    WHERE timestamp IN (
        SELECT MAX(timestamp) 
        FROM watch_history 
        WHERE isFinished = 0 
        GROUP BY animeUrl
    )
    ORDER BY timestamp DESC
""")
    fun getContinueWatchingHistory(): Flow<List<WatchHistory>> // <-- Renamed for clarity


    // We still need a way to get ALL history for the details screen
    @Query("SELECT * FROM watch_history")
    fun getAllWatchHistory(): Flow<List<WatchHistory>> // <-- Added this




}