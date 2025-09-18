package com.faselhd.app.player

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.faselhd.app.db.AppDatabase
import com.faselhd.app.models.SAnime
import com.faselhd.app.models.SEpisode
import com.faselhd.app.models.Video
import com.faselhd.app.models.WatchHistory
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


@androidx.media3.common.util.UnstableApi
class VideoPlayerViewModel(application: Application) : AndroidViewModel(application) {

    // Player instance
    val player: IPlayer = ExoPlayerImplementation()

    // LiveData for UI state
    private val _uiState = MutableLiveData<PlayerUiState>()
    val uiState: LiveData<PlayerUiState> = _uiState

    private val _videoData = MutableLiveData<VideoLoadData>()
    val videoData: LiveData<VideoLoadData> = _videoData

    // Data
    private var videoList: List<Video> = emptyList()
    private var currentAnime: SAnime? = null
    private var currentEpisode: SEpisode? = null
    private var seasonEpisodeList: List<SEpisode> = emptyList()
    private var specificSource: AnimeSource? = null
    // This is a single-event LiveData to trigger video loading in the Activity.
    private val _videoLoadEvent = MutableLiveData<VideoLoadData>()
    val videoLoadEvent: LiveData<VideoLoadData> = _videoLoadEvent

    // Utils
    private val db by lazy { AppDatabase.getDatabase(application) }
    private val sourceManager by lazy { SourceManager(application) }

    fun init(
        videos: List<Video>,
        anime: SAnime,
        episode: SEpisode?,
        episodeList: List<SEpisode>,
        source: AnimeSource?
    ) {
        this.videoList = videos
        this.currentAnime = anime
        this.currentEpisode = episode
        this.seasonEpisodeList = episodeList
        this.specificSource = source

        // Initial UI state
        _uiState.value = PlayerUiState(
            title = "${anime.title} - ${episode!!.name}",
            hasNextEpisode = hasNextEpisode()
        )
    }

    fun onPlayerEvent(event: PlayerEvent) {
        // Handle events from IPlayer implementation
    }

    fun loadVideo(video: Video, startPosition: Long) {
        player.loadMedia(video, startPosition)
    }

    fun playNextEpisode() {
        saveWatchProgress() // Save progress of the current episode before switching
        val currentIndex = seasonEpisodeList.indexOfFirst { it.url == currentEpisode?.url }
        if (currentIndex != -1 && currentIndex < seasonEpisodeList.size - 1) {
            val nextEpisode = seasonEpisodeList[currentIndex + 1]
            viewModelScope.launch {
                _uiState.postValue(uiState.value?.copy(isLoading = true, error = null))
                try {
                    val newVideos = sourceManager.fetchVideoList(nextEpisode.url!!, specificSource)
                    if (newVideos.isNotEmpty()) {
                        currentEpisode = nextEpisode
                        videoList = newVideos
                        _uiState.postValue(
                            uiState.value?.copy(
                                title = "${currentAnime?.title} - ${nextEpisode.name}",
                                hasNextEpisode = hasNextEpisode(),
                                isLoading = false
                            )
                        )
                        // --- THIS LINE USES THE NEW LIVEDATA ---
                        _videoLoadEvent.postValue(VideoLoadData(newVideos, 0L))
                    } else {
                        _uiState.postValue(uiState.value?.copy(isLoading = false, error = "Could not find video for the next episode."))
                    }
                } catch (e: Exception) {
                    _uiState.postValue(uiState.value?.copy(isLoading = false, error = "Error loading next episode: ${e.message}"))
                }
            }
        } else {
            _uiState.postValue(uiState.value?.copy(error = "You've finished the season!"))
        }
    }

    fun saveWatchProgress() {
        val anime = currentAnime ?: return
        val episode = currentEpisode ?: return
        val position = player.getCurrentPosition()
        val duration = player.getDuration()

        if (duration <= 0 || episode.url.isNullOrEmpty()) return

        val sourceToSave = specificSource?.displayName ?: sourceManager.getCurrentSourceName()
        val isFinished = (position * 100) / duration > 90

        viewModelScope.launch(Dispatchers.IO) {
            val history = WatchHistory(
                episodeUrl = episode.url!!,
                animeUrl = anime.url!!,
                animeTitle = anime.title ?: "Unknown",
                animeThumbnailUrl = anime.thumbnail_url,
                episodeName = episode.name,
                lastWatchedPosition = position,
                duration = duration,
                timestamp = System.currentTimeMillis(),
                isFinished = isFinished,
                episodeNumber = episode.episode_number.toInt(),
                seasonEpisodes = seasonEpisodeList,
                source = sourceToSave
            )
            db.watchHistoryDao().upsert(history)
        }
    }

    private fun hasNextEpisode(): Boolean {
        val currentIndex = seasonEpisodeList.indexOfFirst { it.url == currentEpisode?.url }
        return currentIndex != -1 && currentIndex < seasonEpisodeList.size - 1
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}

// Data classes to hold UI state and events
data class PlayerUiState(
    val title: String = "",
    val hasNextEpisode: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)

data class VideoLoadData(
    val videos: List<Video>,
    val startPosition: Long
)