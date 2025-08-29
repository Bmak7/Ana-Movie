package com.faselhd.app.models

import com.fasterxml.jackson.annotation.JsonProperty

// DTO for parsing the remote domains list
data class DomainsParser(
    // ========= THE FIX =========
    // This annotation tells Jackson to look for the JSON key "UHDMovies"
    // and map its value to this "uhdmovies" property.
    @JsonProperty("UHDMovies") val uhdmovies: String,
    @JsonProperty("dramadrip") val dramadrip: String,
)

// DTO for parsing the final video URL from some backup servers
data class UHDBackupUrl(
    @JsonProperty("url") val url: String? = null,
)