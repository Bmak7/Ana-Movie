package com.faselhd.app.models

import kotlinx.serialization.Serializable

@Serializable
data class DaddyLiveChannel(
    val name: String,
    val url: String,
    val country: String
)