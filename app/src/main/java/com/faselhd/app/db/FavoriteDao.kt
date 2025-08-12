package com.faselhd.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.faselhd.app.models.Favorite
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: Favorite)

    @Query("DELETE FROM favorites WHERE animeUrl = :animeUrl")
    suspend fun delete(animeUrl: String)

    @Query("SELECT * FROM favorites WHERE animeUrl = :animeUrl")
    suspend fun getFavoriteByUrl(animeUrl: String): Favorite?

    @Query("SELECT * FROM favorites ORDER BY timestamp DESC")
    fun getAllFavorites(): Flow<List<Favorite>>
}