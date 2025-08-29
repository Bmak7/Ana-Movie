//package com.faselhd.app.network.sources
//
//import com.faselhd.app.models.AnimeFilter
//
//// A generic filter class for this source
//private open class AnimercoSelectFilter(
//    displayName: String,
//    val vals: Array<Pair<String, String>>,
//) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
//    fun toUriPart() = vals[state].second
//}
//
//// Specific filter implementations
//internal class GenreFilter(values: Array<Pair<String, String>>) : AnimercoSelectFilter("التصنيفات", values)
