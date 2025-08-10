//package com.faselhd.app.models
//
//import android.os.Parcelable
//import kotlinx.parcelize.Parcelize
//import okhttp3.Headers
//
//@Parcelize
//data class SAnime(
//    var url: String? = null,
//    var title: String? = null,
//    var thumbnail_url: String? = null,
//    var description: String? = null,
//    var genre: String? = null,
//    var status: Int = UNKNOWN
//) : Parcelable {
//    companion object {
//        const val UNKNOWN = 0
//        const val ONGOING = 1
//        const val COMPLETED = 2
//        const val LICENSED = 3
//        const val PUBLISHING_FINISHED = 4
//        const val CANCELLED = 5
//        const val ON_HIATUS = 6
//    }
//
//    fun setUrlWithoutDomain(url: String) {
//        this.url = url
//    }
//}
//
//// A simple data class for subtitles or audio tracks.
//data class Track(
//    val url: String,
//    val lang: String
//)
//data class SEpisode(
//    var url: String? = null,
//    var name: String? = null,
//    var episode_number: Float = -1f,
//    var date_upload: Long = 0
//) {
//    fun setUrlWithoutDomain(url: String) {
//        this.url = url
//    }
//}
//
//data class Video(
//    var url: String,
//    var quality: String,
//    var videoUrl: String,
//    var headers: Headers? = null,
//    var subtitleTracks: List<Track> = emptyList(),
//    var audioTracks: List<Track> = emptyList()
//)
//
//data class MangaPage(
//    val manga: List<SAnime>,
//    val hasNextPage: Boolean
//)
//
//// Filter classes
//abstract class AnimeFilter(val name: String) {
//    class Header(name: String) : AnimeFilter(name)
//    class Separator : AnimeFilter("---")
//
//    abstract class Select<T>(name: String, val values: Array<T>, var state: Int = 0) : AnimeFilter(name)
//}
//
//class AnimeFilterList(val filters: List<AnimeFilter>) {
//    val isEmpty: Boolean get() = filters.isEmpty()
//
//    inline fun <reified T : AnimeFilter> find(): T? {
//        return filters.filterIsInstance<T>().firstOrNull()
//    }
//}
//
//class SectionFilter : AnimeFilter.Select<Pair<String, String>>(
//    "اقسام الموقع",
//    arrayOf(
//        Pair("اختر", "none"),
//        Pair("جميع الافلام", "all-movies"),
//        Pair("افلام اجنبي", "movies"),
//        Pair("افلام مدبلجة", "dubbed-movies"),
//        Pair("افلام هندي", "hindi"),
//        Pair("افلام اسيوي", "asian-movies"),
//        Pair("افلام انمي", "anime-movies"),
//        Pair("الافلام الاعلي تصويتا", "movies_top_votes"),
//        Pair("الافلام الاعلي مشاهدة", "movies_top_views"),
//        Pair("الافلام الاعلي تقييما IMDB", "movies_top_imdb"),
//        Pair("جميع المسلسلات", "series"),
//        Pair("مسلسلات الأنمي", "anime"),
//        Pair("المسلسلات الاعلي تقييما IMDB", "series_top_imdb"),
//        Pair("المسلسلات القصيرة", "short_series"),
//        Pair("المسلسلات الاسيوية", "asian-series"),
//        Pair("المسلسلات الاعلي مشاهدة", "series_top_views"),
//        Pair("المسلسلات الاسيوية الاعلي مشاهدة", "asian_top_views"),
//        Pair("الانمي الاعلي مشاهدة", "anime_top_views"),
//        Pair("البرامج التليفزيونية", "tvshows"),
//        Pair("البرامج التليفزيونية الاعلي مشاهدة", "tvshows_top_views")
//    )
//) {
//    fun toUriPart() = values[state].second
//}
//
//class CategoryFilter : AnimeFilter.Select<Pair<String, String>>(
//    "النوع",
//    arrayOf(
//        Pair("اختر", "none"),
//        Pair("افلام", "movies-cats"),
//        Pair("مسلسلات", "series_genres"),
//        Pair("انمى", "anime-cats")
//    )
//) {
//    fun toUriPart() = values[state].second
//}
//
//class GenreFilter : AnimeFilter.Select<String>(
//    "التصنيف",
//    arrayOf(
//        "Action", "Adventure", "Animation", "Western", "Sport", "Short",
//        "Documentary", "Fantasy", "Sci-fi", "Romance", "Comedy", "Family",
//        "Drama", "Thriller", "Crime", "Horror", "Biography"
//    ).sortedArray()
//) {
//    fun toUriPart() = values[state]
//}
//

package com.faselhd.app.models

import android.os.Parcelable
import androidx.media3.common.Tracks
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import kotlinx.parcelize.Parcelize

import okhttp3.Headers

@Parcelize
data class SAnime(
    var url: String? = null,
    var title: String? = null,
    var thumbnail_url: String? = null,
    var description: String? = null,
    var genre: String? = null,
    var status: Int = UNKNOWN
) : Parcelable {
    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6
    }

    fun setUrlWithoutDomain(url: String) {
        this.url = url
    }
}

data class Track(
    val url: String,
    val lang: String
)

@Parcelize
@JsonIgnoreProperties(ignoreUnknown = true)
data class SEpisode(
    var url: String? = null,
    var name: String? = null,
    var episode_number: Float = -1f,
    var date_upload: Long = 0
): Parcelable {
    fun setUrlWithoutDomain(url: String) {
        this.url = url
    }
}

data class VideoTrack(
    val label: String,
    val trackGroup: Tracks.Group,
    val trackIndex: Int
)

@Parcelize // Add this annotation
data class Video(
    var url: String,
    var quality: String,
    var videoUrl: String,
    val resolution: String
//    var headers: Headers? = null,
//    var subtitleTracks: List<Track> = emptyList(),
//    var audioTracks: List<Track> = emptyList()
): Parcelable

data class MangaPage(
    val manga: List<SAnime>,
    val hasNextPage: Boolean
)

// Filter classes
abstract class AnimeFilter(val name: String) {
    class Header(name: String) : AnimeFilter(name)
    class Separator : AnimeFilter("---")

    abstract class Select<T>(name: String, val values: Array<T>, var state: Int = 0) : AnimeFilter(name)
}

class AnimeFilterList(val filters: List<AnimeFilter>) {
    val isEmpty: Boolean get() = filters.isEmpty()

    inline fun <reified T : AnimeFilter> find(): T? {
        return filters.filterIsInstance<T>().firstOrNull()
    }
}

class SectionFilter : AnimeFilter.Select<Pair<String, String>>(
    "اقسام الموقع",
    arrayOf(
        Pair("اختر", "none"),
        Pair("جميع الافلام", "all-movies"),
        Pair("افلام اجنبي", "movies"),
        Pair("افلام مدبلجة", "dubbed-movies"),
        Pair("افلام هندي", "hindi"),
        Pair("افلام اسيوي", "asian-movies"),
        Pair("افلام انمي", "anime-movies"),
        Pair("الافلام الاعلي تصويتا", "movies_top_votes"),
        Pair("الافلام الاعلي مشاهدة", "movies_top_views"),
        Pair("الافلام الاعلي تقييما IMDB", "movies_top_imdb"),
        Pair("جميع المسلسلات", "series"),
        Pair("مسلسلات الأنمي", "anime"),
        Pair("المسلسلات الاعلي تقييما IMDB", "series_top_imdb"),
        Pair("المسلسلات القصيرة", "short_series"),
        Pair("المسلسلات الاسيوية", "asian-series"),
        Pair("المسلسلات الاعلي مشاهدة", "series_top_views"),
        Pair("المسلسلات الاسيوية الاعلي مشاهدة", "asian_top_views"),
        Pair("الانمي الاعلي مشاهدة", "anime_top_views"),
        Pair("البرامج التليفزيونية", "tvshows"),
        Pair("البرامج التليفزيونية الاعلي مشاهدة", "tvshows_top_views")
    )
) {
    fun toUriPart() = values[state].second
}

class CategoryFilter : AnimeFilter.Select<Pair<String, String>>(
    "النوع",
    arrayOf(
        Pair("اختر", "none"),
        Pair("افلام", "movies-cats"),
        Pair("مسلسلات", "series_genres"),
        Pair("انمى", "anime-cats")
    )
) {
    fun toUriPart() = values[state].second
}

class GenreFilter : AnimeFilter.Select<String>(
    "التصنيف",
    arrayOf(
        "Action", "Adventure", "Animation", "Western", "Sport", "Short",
        "Documentary", "Fantasy", "Sci-fi", "Romance", "Comedy", "Family",
        "Drama", "Thriller", "Crime", "Horror", "Biography"
    ).sortedArray()
) {
    fun toUriPart() = values[state]
}




