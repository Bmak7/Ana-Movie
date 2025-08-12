package com.faselhd.app.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class Favorite(
    @PrimaryKey val animeUrl: String, // Using the anime's URL as a unique ID
    val title: String?,
    val thumbnailUrl: String?,
    val timestamp: Long = System.currentTimeMillis() // To know when it was added
)