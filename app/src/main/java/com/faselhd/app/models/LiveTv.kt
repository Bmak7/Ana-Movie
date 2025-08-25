package com.faselhd.app.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

// This will be the main object used in your UI adapters
@Parcelize
data class SLiveTv(
    var title: String = "",
    var url: String = "", // Will store the JSON of the Channel object
    var posterUrl: String? = null,
    var country: String? = null,
    var source: String? = null
) : Parcelable

// DTO for parsing the JSON from the Huhu API
@Serializable
data class HuhuChannel(
    val country: String,
    val id: Long,
    val name: String,
    val p: Int
)