package com.faselhd.app.viewmodels

import android.app.Application
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
import com.faselhd.app.utils.EpisodeSkip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val sourceManager = SourceManager(application)

    // Player State
    private val _isPlaying = MutableLiveData<Boolean>(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _currentPosition = MutableLiveData<Long>(0L)
    val currentPosition: LiveData<Long> = _currentPosition

    private val _duration = MutableLiveData<Long>(0L)
    val duration: LiveData<Long> = _duration

    private val _playbackError = MutableLiveData<String?>()
    val playbackError: LiveData<String?> = _playbackError

    // Episode State
    private val _videoList = MutableLiveData<List<Video>>(emptyList())
    val videoList: LiveData<List<Video>> = _videoList

    private val _currentAnime = MutableLiveData<SAnime?>()
    val currentAnime: LiveData<SAnime?> = _currentAnime

    private val _currentEpisode = MutableLiveData<SEpisode?>()
    val currentEpisode: LiveData<SEpisode?> = _currentEpisode

    private val _episodeList = MutableLiveData<List<SEpisode>>(emptyList())
    val episodeList: LiveData<List<SEpisode>> = _episodeList

    private val _hasNextEpisode = MutableLiveData<Boolean>(false)
    val hasNextEpisode: LiveData<Boolean> = _hasNextEpisode

    // Skip State
    private val _skipStamps = MutableLiveData<List<EpisodeSkip.SkipStamp>>(emptyList())
    val skipStamps: LiveData<List<EpisodeSkip.SkipStamp>> = _skipStamps

    private val _currentSkipStamp = MutableLiveData<EpisodeSkip.SkipStamp?>()
    val currentSkipStamp: LiveData<EpisodeSkip.SkipStamp?> = _currentSkipStamp

    // UI State
    private val _episodeTitle = MutableLiveData<String>("")
    val episodeTitle: LiveData<String> = _episodeTitle

    private val _serverName = MutableLiveData<String>("")
    val serverName: LiveData<String> = _serverName

    // Internal state
    private var specificSource: AnimeSource? = null
    private var isOfflineContent = false

    fun initializePlayer(
        videos: List<Video>,
        anime: SAnime,
        episode: SEpisode,
        episodes: List<SEpisode>,
        startPosition: Long = 0L,
        source: AnimeSource? = null
    ) {
        _videoList.value = videos
        _currentAnime.value = anime
        _currentEpisode.value = episode
        _episodeList.value = episodes
        specificSource = source

        // Check if content is offline
        isOfflineContent = videos.firstOrNull()?.let { video ->
            video.url.startsWith("file://") || video.url.startsWith("content://") ||
                    video.url.startsWith("/") || java.io.File(video.url).exists()
        } ?: false

        updateEpisodeTitle()
        updateHasNextEpisode()
        updateCurrentPosition(startPosition)

        // Only fetch skip times for online content
        if (!isOfflineContent) {
            fetchSkipTimes()
        }
    }


    fun updateServerName(serverName: String) {
        _serverName.value = serverName
    }

    fun setLoading(isLoading: Boolean) {
        _isLoading.value = isLoading
    }

    fun setPlaybackError(error: String?) {
        _playbackError.value = error
    }

    fun updateCurrentPosition(position: Long) {
        _currentPosition.value = position
    }

    private fun updateEpisodeTitle() {
        val anime = _currentAnime.value
        val episode = _currentEpisode.value
        if (anime != null && episode != null) {
            _episodeTitle.value = "${anime.title} - ${episode.name}"
        }
    }

    private fun updateHasNextEpisode() {
        val episodes = _episodeList.value ?: emptyList()
        val currentEpisode = _currentEpisode.value
        val currentIndex = episodes.indexOfFirst { it.url == currentEpisode?.url }
        _hasNextEpisode.value = currentIndex != -1 && currentIndex < episodes.size - 1
    }

    private fun fetchSkipTimes() {
        val anime = _currentAnime.value ?: return
        val episode = _currentEpisode.value ?: return
        val duration = _duration.value ?: return

        if (duration <= 0) return

        viewModelScope.launch {
            try {
                val stamps = EpisodeSkip.getStamps(
                    anime,
                    episode.episode_number.toInt(),
                    duration
                )
                _skipStamps.value = stamps
            } catch (e: Exception) {
                // Handle error silently for skip times
            }
        }
    }

    private fun checkSkipButtonVisibility(currentPosition: Long) {
        val stamps = _skipStamps.value ?: emptyList()
        if (stamps.isEmpty()) return

        val activeStamp = stamps.find { currentPosition in it.startMs..it.endMs }
        _currentSkipStamp.value = activeStamp
    }

    fun skipToPosition(position: Long) {
        _currentSkipStamp.value = null
    }

    fun playNextEpisode() {
        val episodes = _episodeList.value ?: return
        val currentEpisode = _currentEpisode.value ?: return
        val currentIndex = episodes.indexOfFirst { it.url == currentEpisode.url }

        if (currentIndex != -1 && currentIndex < episodes.size - 1) {
            val nextEpisode = episodes[currentIndex + 1]
            loadEpisode(nextEpisode)
        }
    }

    private fun loadEpisode(episode: SEpisode) {
        viewModelScope.launch {
            _isLoading.value = true
            // Clear previous error messages before loading the next episode
            _playbackError.value = null
            try {
                val newVideoList = withContext(Dispatchers.IO) {
                    sourceManager.fetchVideoList(episode.url!!, specificSource)
                }

                if (newVideoList.isNotEmpty()) {
                    _currentEpisode.value = episode

                    // ++ ADDED STATE RESET
                    // Reset position and duration for the new episode
                    _currentPosition.value = 0L
                    _duration.value = 0L
                    // -- END ADDITION

                    // This is the most important line: it triggers the observer in the Activity
                    _videoList.value = newVideoList

                    updateEpisodeTitle()
                    updateHasNextEpisode()

                    // Reset skip-related state
                    _currentSkipStamp.value = null
                    _skipStamps.value = emptyList()

                    // Fetch skip times for new episode if online
                    if (!isOfflineContent) {
                        // We will fetch skip times once the duration is known
                    }
                } else {
                    _playbackError.value = "Could not find video for the next episode."
                }
            } catch (e: Exception) {
                _playbackError.value = "Error loading next episode: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Small adjustment to when skip times are fetched for better accuracy.
    fun updatePlayerState(isPlaying: Boolean, currentPosition: Long, duration: Long) {
        _isPlaying.value = isPlaying
        _currentPosition.value = currentPosition

        // Only update duration if it has changed and fetch skip times on the first valid duration
        val previousDuration = _duration.value ?: 0L
        if (duration > 0 && duration != previousDuration) {
            _duration.value = duration
            if (!isContentOffline()) {
                fetchSkipTimes() // Fetch skip times now that we have a duration
            }
        }

        // Check skip button visibility
        checkSkipButtonVisibility(currentPosition)
    }

    fun saveWatchProgress(currentPosition: Long, duration: Long) {
        val anime = _currentAnime.value ?: return
        val episode = _currentEpisode.value ?: return
        val episodes = _episodeList.value ?: emptyList()

        if (duration <= 0 || episode.url.isNullOrEmpty()) return

        val sourceToSave = specificSource?.displayName ?: sourceManager.getCurrentSourceName()
        val progressPercentage = (currentPosition * 100) / duration

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (progressPercentage > 90) {
                    // Episode is finished - set up next episode in history
                    val currentIndex = episodes.indexOfFirst { it.url == episode.url }
                    if (currentIndex != -1 && currentIndex < episodes.size - 1) {
                        val nextEpisode = episodes[currentIndex + 1]

                        val nextEpisodeHistory = WatchHistory(
                            episodeUrl = nextEpisode.url!!,
                            animeUrl = anime.url!!,
                            animeTitle = anime.title ?: "Unknown Title",
                            animeThumbnailUrl = anime.thumbnail_url,
                            episodeName = nextEpisode.name,
                            lastWatchedPosition = 0L,
                            duration = 0L,
                            timestamp = System.currentTimeMillis() + 1000,
                            isFinished = false,
                            episodeNumber = nextEpisode.episode_number.toInt(),
                            seasonEpisodes = episodes,
                            source = sourceToSave
                        )

                        db.watchHistoryDao().upsert(nextEpisodeHistory)
                    }

                    // Mark current episode as finished
                    val watchHistory = WatchHistory(
                        episodeUrl = episode.url!!,
                        animeUrl = anime.url!!,
                        animeTitle = anime.title ?: "Unknown Title",
                        animeThumbnailUrl = anime.thumbnail_url,
                        episodeName = episode.name ?: "Unknown Episode",
                        lastWatchedPosition = currentPosition,
                        duration = duration,
                        timestamp = System.currentTimeMillis(),
                        isFinished = true,
                        episodeNumber = episode.episode_number.toInt(),
                        seasonEpisodes = episodes,
                        source = sourceToSave
                    )
                    db.watchHistoryDao().upsert(watchHistory)
                } else {
                    // Episode is in progress
                    val watchHistory = WatchHistory(
                        episodeUrl = episode.url!!,
                        animeUrl = anime.url!!,
                        animeTitle = anime.title ?: "Unknown Title",
                        animeThumbnailUrl = anime.thumbnail_url,
                        episodeName = episode.name ?: "Unknown Episode",
                        lastWatchedPosition = currentPosition,
                        duration = duration,
                        timestamp = System.currentTimeMillis(),
                        isFinished = false,
                        episodeNumber = episode.episode_number.toInt(),
                        seasonEpisodes = episodes,
                        source = sourceToSave
                    )
                    db.watchHistoryDao().upsert(watchHistory)
                }
            } catch (e: Exception) {
                // Handle database error silently
            }
        }
    }

    // Add this method to your VideoPlayerViewModel class

    fun retryLoadingVideos() {
        val episode = _currentEpisode.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _playbackError.value = null // Clear any previous errors

            try {
                val newVideoList = withContext(Dispatchers.IO) {
                    sourceManager.fetchVideoList(episode.url!!, specificSource)
                }

                if (newVideoList.isNotEmpty()) {
                    _videoList.value = newVideoList
                } else {
                    _playbackError.value = "No video sources found for this episode"
                }
            } catch (e: Exception) {
                _playbackError.value = "Failed to load video sources: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Also add this helper method for better error handling
    fun refreshVideoSources() {
        val episode = _currentEpisode.value
        if (episode?.url != null) {
            retryLoadingVideos()
        } else {
            _playbackError.value = "Invalid episode data"
        }
    }

    fun clearError() {
        _playbackError.value = null
    }

    fun isContentOffline(): Boolean = isOfflineContent

    override fun onCleared() {
        super.onCleared()
        // Cleanup if needed
    }
}

