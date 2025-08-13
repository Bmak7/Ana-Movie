// In file: app/src/main/java/com/faselhd/app/db/DownloadDao.kt

package com.faselhd.app.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.faselhd.app.models.Download
import com.faselhd.app.models.DownloadState // Make sure this is imported
import kotlinx.coroutines.flow.Flow

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

    @Query("SELECT * FROM downloads ORDER BY animeTitle, episodeName")
    fun getAllDownloadsFlow(): Flow<List<Download>>

    // **** ADD THIS FUNCTION TO UPDATE ONLY THE STATE ****
    @Query("UPDATE downloads SET downloadState = :newState WHERE episodeUrl = :episodeUrl")
    suspend fun updateState(episodeUrl: String, newState: DownloadState)

    // **** ADD THIS FUNCTION TO UPDATE ONLY THE PROGRESS ****
    @Query("UPDATE downloads SET progress = :newProgress WHERE episodeUrl = :episodeUrl")
    suspend fun updateProgress(episodeUrl: String, newProgress: Int)

    // **** ADD THIS FUNCTION TO FINALIZE A SUCCESSFUL DOWNLOAD ****
    @Query("UPDATE downloads SET progress = 100, downloadState = 'COMPLETED', localFilePath = :filePath WHERE episodeUrl = :episodeUrl")
    suspend fun updateOnSuccess(episodeUrl: String, filePath: String)

    @Query("UPDATE downloads SET mediaUri = :mediaUri WHERE episodeUrl = :episodeUrl")
    suspend fun updateMediaUri(episodeUrl: String, mediaUri: String)
}