package com.faselhd.app

// CORRECT IMPORT: You need to import AspectRatioFrameLayout to access the constants.

// --- REQUIRED IMPORTS ---

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.myapplication.R // Ensure this matches your package
import com.faselhd.app.db.AppDatabase
import com.faselhd.app.models.SAnime
import com.faselhd.app.models.SEpisode
import com.faselhd.app.models.Video
import com.faselhd.app.models.WatchHistory
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.SourceManager
import com.faselhd.app.utils.EpisodeSkip
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs


class VideoPlayerActivity : AppCompatActivity() {

    // In VideoPlayerActivity.kt

    companion object {
        private const val EXTRA_VIDEOS = "extra_videos"
        private const val EXTRA_ANIME = "extra_anime"
        private const val EXTRA_EPISODE = "extra_episode"
        private const val EXTRA_EPISODE_LIST = "extra_episode_list"
        private const val EXTRA_START_POSITION = "extra_start_position"
        private const val EXTRA_SOURCE = "extra_source"

        fun newIntent(
            context: Context,
            videos: List<Video?>,
            anime: SAnime,
            currentEpisode: SEpisode,
            episodeListForSeason: ArrayList<SEpisode>,
            startPosition: Long = 0L,
            source: AnimeSource? = null
        ): Intent {
            return Intent(context, VideoPlayerActivity::class.java).apply {
                putParcelableArrayListExtra(EXTRA_VIDEOS, ArrayList(videos.filterNotNull()))
                putExtra(EXTRA_ANIME, anime)
                putExtra(EXTRA_EPISODE, currentEpisode)
                putParcelableArrayListExtra(EXTRA_EPISODE_LIST, episodeListForSeason)
                putExtra(EXTRA_START_POSITION, startPosition)
                putExtra(EXTRA_SOURCE, source)
            }
        }
    }

    // Player and UI Components
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var audioManager: AudioManager
    private lateinit var trackSelector: DefaultTrackSelector

    // UI Elements
    private lateinit var btnServer: ImageButton // Changed from btnQuality
    private lateinit var tvServerName: TextView // Changed from tvResolution
    private lateinit var topOverlay: LinearLayout
    private lateinit var centerControls: LinearLayout
    private lateinit var bottomControls: LinearLayout
    private lateinit var brightnessOverlay: LinearLayout
    private lateinit var volumeOverlay: LinearLayout
    private lateinit var btnBack: ImageButton
    private lateinit var btnPlayPause: FrameLayout
    private lateinit var ivPlayPause: ImageView
    private lateinit var btnRewind: FrameLayout
    private lateinit var btnFastForward: FrameLayout
    private lateinit var btnLock: ImageButton
    private lateinit var btnFullscreen: ImageButton // ADDED BACK
    private lateinit var btnResize: ImageButton // ADDED BACK
    private lateinit var btnSubtitle: ImageButton // ADDED BACK
    private lateinit var btnNextEpisode: ImageButton
    private lateinit var tvEpisodeTitle: TextView
    private lateinit var tvSeekTime: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var brightnessProgress: ProgressBar
    private lateinit var volumeProgress: ProgressBar
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var lockOverlay: FrameLayout
    private lateinit var btnUnlock: ImageButton
    private lateinit var rewindIndicator: LinearLayout
    private lateinit var forwardIndicator: LinearLayout
    private lateinit var speedIndicatorText: TextView
    private lateinit var btnSkipIntro: MaterialButton
    private lateinit var tvBrightnessValue: TextView
    private lateinit var tvVolumeValue: TextView
    private lateinit var ivVolumeIcon: ImageView

    // State variables
    private var isControlsVisible = true
    private var isLocked = false
    private var isFullscreen = true // ADDED BACK
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL // ADDED BACK
    private var currentBrightness = 0.5f
    private var currentVolume = 0
    private var maxVolume = 0
    private var isOnLongPressSpeedUp = false
    private var seekChange: Long = 0 // ADDED BACK

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }

    // Data from Intent
    private var videoList: List<Video> = emptyList()
    private var currentAnime: SAnime? = null
    private val sourceManager by lazy { SourceManager(applicationContext) } // <-- ADD SOURCEMANAGER
    private var currentEpisode: SEpisode? = null
    private var seasonEpisodeList: List<SEpisode> = emptyList()
    private var startPosition: Long = 0L
    private var specificSource: AnimeSource? = null

    // Database and Skip Times
    private val db by lazy { AppDatabase.getDatabase(this) }
    private var skipStamps: List<EpisodeSkip.SkipStamp> = emptyList()
    private var currentSkipStamp: EpisodeSkip.SkipStamp? = null

    private var isSeeking = false
    private var seekStartPosition = 0L


    // In VideoPlayerActivity.kt -> onCreate() method

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Retrieve data
        videoList = intent.getParcelableArrayListExtra(EXTRA_VIDEOS) ?: emptyList()
        currentAnime = intent.getParcelableExtra(EXTRA_ANIME)
        currentEpisode = intent.getParcelableExtra(EXTRA_EPISODE)
        seasonEpisodeList = intent.getParcelableArrayListExtra(EXTRA_EPISODE_LIST) ?: emptyList()
        startPosition = intent.getLongExtra(EXTRA_START_POSITION, 0L)
        specificSource = intent.getSerializableExtra(EXTRA_SOURCE) as? AnimeSource

        if (videoList.isEmpty() || currentAnime == null || currentEpisode == null) {
            Toast.makeText(this, "Video source not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initializeViews()
        setupAudioManager()
        setupGestureDetector()
        setupClickListeners()
        setupSeekBar()
        hideSystemUI()
        scheduleHideControls() // ADDED BACK

        // Show server selection dialog to start playback
        showSourceSelectionDialog()
    }


    private fun saveWatchProgress() {
        val p = player ?: return
        val anime = currentAnime ?: return
        println("current anime s ${currentAnime.toString()}")
        val episode = currentEpisode ?: return
        val position = p.currentPosition
        val duration = p.duration

        if (duration <= 0 || episode.url.isNullOrEmpty()) return

        // **** THIS IS THE KEY CHANGE ****
        // Determine the correct source to save. Prioritize the one passed via intent.
        val sourceToSave = specificSource?.displayName ?: sourceManager.getCurrentSourceName()

        val progressPercentage = (position * 100) / duration

        CoroutineScope(Dispatchers.IO).launch {
            if (progressPercentage > 90) {
                // --- EPISODE IS FINISHED ---
                val currentIndex = seasonEpisodeList.indexOfFirst { it.url == episode.url }
                if (currentIndex != -1 && currentIndex < seasonEpisodeList.size - 1) {
                    val nextEpisode = seasonEpisodeList[currentIndex + 1]

                    val nextEpisodeHistory = WatchHistory(
                        episodeUrl = nextEpisode.url!!, // URL of the next episode
                        animeUrl = anime.url!!,
                        animeTitle = anime.title ?: "Unknown Title",
                        animeThumbnailUrl = anime.thumbnail_url,
                        episodeName = nextEpisode.name,
                        lastWatchedPosition = 0L, // Start from the beginning
                        duration = 0L, // We don't know the duration yet, set to 0
                        timestamp = System.currentTimeMillis() + 1000, // Slightly later timestamp to ensure it's on top
                        isFinished = false,
                        episodeNumber = nextEpisode.episode_number.toInt(),
                        seasonEpisodes = seasonEpisodeList,
                        source = sourceToSave
                    )

                    println("next episode :${nextEpisodeHistory.toString()}")

                    db.watchHistoryDao().upsert(nextEpisodeHistory)

                    val watchHistory = WatchHistory(
                        episodeUrl = episode.url!!,
                        animeUrl = anime.url!!,
                        animeTitle = anime.title ?: "Unknown Title",
                        animeThumbnailUrl = anime.thumbnail_url,
                        episodeName = episode.name ?: "Unknown Episode",
                        lastWatchedPosition = position,
                        duration = duration,
                        timestamp = System.currentTimeMillis(),
                        isFinished = true,
                        episodeNumber = episode.episode_number.toInt(),
                        seasonEpisodes = seasonEpisodeList,
                        source = sourceToSave
                    )
                    println("next next episode :${watchHistory.toString()}")
                    db.watchHistoryDao().upsert(watchHistory)
                }
            } else {
                // --- EPISODE IS IN PROGRESS ---
                val watchHistory = WatchHistory(
                    episodeUrl = episode.url!!,
                    animeUrl = anime.url!! ,
                    animeTitle = anime.title ?: "Unknown Title",
                    animeThumbnailUrl = anime.thumbnail_url,
                    episodeName = episode.name ?: "Unknown Episode",
                    lastWatchedPosition = position,
                    duration = duration,
                    timestamp = System.currentTimeMillis(),
                    isFinished = false,
                    episodeNumber = episode.episode_number.toInt(),
                    seasonEpisodes = seasonEpisodeList,
                    source = sourceToSave
                )
                println(" episode :${watchHistory.toString()}")
                db.watchHistoryDao().upsert(watchHistory)
            }
        }
    }


    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, playerView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // --- Override dispatchKeyEvent for D-pad handling ---
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (isDpadEvent(event)) {
                scheduleHideControls()
            }

            if (isLocked) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    return super.dispatchKeyEvent(event)
                }
                return true
            }

            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (!isControlsVisible) {
                        showControls()
                        return true
                    }
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    playNextEpisode()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    rewind()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    fastForward()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        if (isControlsVisible) {
            hideControls()
        } else {
            super.onBackPressed()
        }
    }

    private fun isDpadEvent(event: KeyEvent): Boolean {
        return event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
    }


    private fun initializeViews() {
        playerView = findViewById(R.id.player_view)
        loadingIndicator = findViewById(R.id.loading_indicator)
        topOverlay = findViewById(R.id.top_overlay)
        centerControls = findViewById(R.id.center_controls)
        bottomControls = findViewById(R.id.bottom_controls)
        btnBack = findViewById(R.id.btn_back)
        tvEpisodeTitle = findViewById(R.id.tv_episode_title)
        btnServer = findViewById(R.id.btn_quality)
        tvServerName = findViewById(R.id.tv_resolution)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        ivPlayPause = findViewById(R.id.iv_play_pause)
        btnRewind = findViewById(R.id.btn_rewind)
        btnFastForward = findViewById(R.id.btn_fast_forward)
        tvCurrentTime = findViewById(R.id.tv_current_time)
        tvTotalTime = findViewById(R.id.tv_total_time)
        seekBar = findViewById(R.id.seek_bar)
        tvBrightnessValue = findViewById(R.id.tv_brightness_value)
        tvVolumeValue = findViewById(R.id.tv_volume_value)
        ivVolumeIcon = findViewById(R.id.iv_volume_icon)
        btnNextEpisode = findViewById(R.id.btn_next_episode)
        btnLock = findViewById(R.id.btn_lock)
        btnFullscreen = findViewById(R.id.btn_fullscreen) // ADDED BACK
        btnResize = findViewById(R.id.btn_resize) // ADDED BACK
        btnSubtitle = findViewById(R.id.btn_subtitle) // ADDED BACK
        lockOverlay = findViewById(R.id.lock_overlay)
        btnUnlock = findViewById(R.id.btn_unlock)
        brightnessOverlay = findViewById(R.id.brightness_overlay)
        brightnessProgress = findViewById(R.id.brightness_progress)
        volumeOverlay = findViewById(R.id.volume_overlay)
        volumeProgress = findViewById(R.id.volume_progress)
        rewindIndicator = findViewById(R.id.rewind_indicator)
        forwardIndicator = findViewById(R.id.forward_indicator)
        speedIndicatorText = findViewById(R.id.speed_indicator_text)
        btnSkipIntro = findViewById(R.id.btn_skip_intro)
        tvSeekTime = findViewById(R.id.tv_seek_time)

        tvEpisodeTitle.text = "${currentAnime?.title} - ${currentEpisode?.name}"
        val currentIndex = seasonEpisodeList.indexOf(currentEpisode)
        btnNextEpisode.visibility = if (currentIndex != -1 && currentIndex < seasonEpisodeList.size - 1) View.VISIBLE else View.GONE
    }

    private fun showSourceSelectionDialog() {
        if (videoList.size == 1) {
            initializePlayerForVideo(videoList.first())
            return
        }

        val sources = videoList.map { it.quality }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Source")
            .setItems(sources) { dialog, which ->
                val selectedVideo = videoList[which]
                initializePlayerForVideo(selectedVideo)
                dialog.dismiss()
            }
            .setOnCancelListener {
                if (player == null) {
                    finish()
                }
            }
            .show()
    }

    // ADDED BACK: Quality selection dialog for track selection
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun showQualityDialog() {
        val trackSelector = this.trackSelector
        val mappedTrackInfo = trackSelector.currentMappedTrackInfo
        if (mappedTrackInfo == null) {
            Toast.makeText(this, "No quality options available", Toast.LENGTH_SHORT).show()
            return
        }

        var videoRendererIndex = -1
        for (i in 0 until mappedTrackInfo.rendererCount) {
            if (player!!.getRendererType(i) == C.TRACK_TYPE_VIDEO) {
                videoRendererIndex = i
                break
            }
        }

        if (videoRendererIndex == -1) {
            Toast.makeText(this, "No quality options available", Toast.LENGTH_SHORT).show()
            return
        }

        val trackGroups = mappedTrackInfo.getTrackGroups(videoRendererIndex)
        if (trackGroups.isEmpty) {
            Toast.makeText(this, "No quality options available", Toast.LENGTH_SHORT).show()
            return
        }

        val qualityOptions = mutableListOf<String>()
        val trackIndices = mutableListOf<Int>()
        qualityOptions.add("Auto")

        for (i in 0 until trackGroups.length) {
            val group = trackGroups.get(i)
            for (j in 0 until group.length) {
                val format = group.getFormat(j)
                qualityOptions.add("${format.height}p")
                trackIndices.add(j)
            }
        }

        val selectionOverride = trackSelector.parameters.getSelectionOverride(videoRendererIndex, trackGroups)
        var checkedItem = 0
        if (selectionOverride != null && selectionOverride.length > 0) {
            checkedItem = trackIndices.indexOf(selectionOverride.tracks[0]) + 1
        }

        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("Select Quality")
        builder.setSingleChoiceItems(qualityOptions.toTypedArray(), checkedItem) { dialog, which ->
            val parametersBuilder = trackSelector.buildUponParameters()
            if (which == 0) {
                parametersBuilder.clearSelectionOverrides(videoRendererIndex)
            } else {
                val override = DefaultTrackSelector.SelectionOverride(
                    videoRendererIndex,
                    trackIndices[which - 1]
                )
                parametersBuilder.setSelectionOverride(
                    videoRendererIndex,
                    trackGroups,
                    override
                )
            }
            trackSelector.parameters = parametersBuilder.build()
            dialog.dismiss()
        }
        builder.create().show()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun initializePlayerForVideo(video: Video) {
        player?.release()
        player = null

        tvServerName.text = "${video.quality} (Auto)" // Show server name with Auto indicator

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36")

        video.headers?.let { headersMap ->
            httpDataSourceFactory.setDefaultRequestProperties(headersMap)
        }

        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        val mediaItem = MediaItem.fromUri(video.url)
        val mediaSource = if (video.url.endsWith(".m3u8", ignoreCase = true)) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }

        trackSelector = DefaultTrackSelector(this).apply {
            // Configure for automatic quality selection with adaptive bitrate
            setParameters(
                buildUponParameters()
                    .setAllowMultipleAdaptiveSelections(true)
                    .setAllowAudioMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoNonSeamlessAdaptiveness(true)
                    .setMaxVideoBitrate(Int.MAX_VALUE) // No bitrate limit for auto mode
                    .setForceHighestSupportedBitrate(false) // Allow adaptive selection
            )
        }

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .build().apply {
                setMediaSource(mediaSource)
                addListener(playerListener)
                playWhenReady = true

                val seekPosition = if (startPosition != -1L) startPosition else this@VideoPlayerActivity.player?.currentPosition ?: 0L
                seekTo(seekPosition)
                startPosition = -1L

                prepare()
            }

        playerView.player = player
        playerView.resizeMode = currentResizeMode
        updateProgress()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            loadingIndicator.visibility = if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (playbackState == Player.STATE_READY) {
                updatePlayPauseButton()
                updateDuration()
                fetchSkipTimes()
            } else if (playbackState == Player.STATE_ENDED) {
                playNextEpisode()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseButton()
        }

        // ADDED BACK: Track changes listener for quality updates
        override fun onTracksChanged(tracks: Tracks) {
            super.onTracksChanged(tracks)
            updateResolutionDisplay()
        }

        // ADDED BACK: Video size changed listener
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            super.onVideoSizeChanged(videoSize)
            updateResolutionDisplay()
        }

        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            Log.e("VideoPlayerActivity", "ExoPlayer Error: ", error)
            Toast.makeText(
                this@VideoPlayerActivity,
                "Player Error: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun rewind() {
        player?.let { p ->
            p.seekTo((p.currentPosition - 10000).coerceAtLeast(0))
            showSeekIndicator(rewindIndicator)
        }
    }

    private fun fastForward() {
        player?.let { p ->
            p.seekTo((p.currentPosition + 10000).coerceAtMost(p.duration))
            showSeekIndicator(forwardIndicator)
        }
    }

    private fun showSeekIndicator(view: View) {
        view.visibility = View.VISIBLE
        view.animate().alpha(0f).setDuration(800).withEndAction {
            view.visibility = View.GONE
            view.alpha = 1f
        }.start()
    }

    // ADDED BACK: Resize mode cycling
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun cycleResizeMode() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> {
                Toast.makeText(this, "Zoom", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> {
                Toast.makeText(this, "Stretch", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> {
                Toast.makeText(this, "Fit to Screen", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView.resizeMode = currentResizeMode
    }

    private fun setupClickListeners() {
        btnBack.setOnClickListener { finish() }
        btnServer.setOnClickListener { showSourceSelectionDialog(); scheduleHideControls() }
        btnPlayPause.setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
            scheduleHideControls()
        }
        btnRewind.setOnClickListener { rewind(); scheduleHideControls() }
        btnFastForward.setOnClickListener { fastForward(); scheduleHideControls() }
        btnLock.setOnClickListener { toggleLock() }
        btnNextEpisode.setOnClickListener { playNextEpisode() }
        btnUnlock.setOnClickListener { toggleLock() }

        // ADDED BACK: Missing button click listeners
        btnFullscreen.setOnClickListener {
            Toast.makeText(this, "Player is always in fullscreen mode", Toast.LENGTH_SHORT).show()
            scheduleHideControls()
        }
        btnResize.setOnClickListener {
            cycleResizeMode()
            scheduleHideControls()
        }
        btnSubtitle.setOnClickListener {
            Toast.makeText(this, "Subtitle functionality to be implemented", Toast.LENGTH_SHORT).show()
            scheduleHideControls()
        }

        btnSkipIntro.setOnClickListener {
            currentSkipStamp?.let {
                player?.seekTo(it.endMs)
                btnSkipIntro.visibility = View.GONE
                currentSkipStamp = null
            }
        }
    }

    private fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) {
            hideControls()
            lockOverlay.visibility = View.VISIBLE
            btnUnlock.requestFocus()
            hideHandler.postDelayed({ btnUnlock.visibility = View.GONE }, 2000)
        } else {
            lockOverlay.visibility = View.GONE
            showControls()
        }
    }

    private fun fetchSkipTimes() {
        val anime = currentAnime ?: return
        val episode = currentEpisode ?: return
        val duration = player?.duration ?: return

        if (duration <= 0) return

        lifecycleScope.launch {
            skipStamps = EpisodeSkip.getStamps(
                anime,
                episode.episode_number.toInt(),
                duration
            )
        }
    }

    private fun checkSkipButtonVisibility(currentPosition: Long) {
        if (skipStamps.isEmpty()) return

        val activeStamp = skipStamps.find { currentPosition in it.startMs..it.endMs }

        if (activeStamp != null) {
            if (btnSkipIntro.visibility == View.GONE) {
                currentSkipStamp = activeStamp
                btnSkipIntro.text = activeStamp.type.text
                btnSkipIntro.visibility = View.VISIBLE
            }
        } else {
            if (btnSkipIntro.visibility == View.VISIBLE) {
                btnSkipIntro.visibility = View.GONE
                currentSkipStamp = null
            }
        }
    }

    private fun playNextEpisode() {
        // 1. Find the current episode's index in the season list
        val currentIndex = seasonEpisodeList.indexOfFirst { it.url == currentEpisode?.url }

        // 2. Check if there is a next episode
        if (currentIndex != -1 && currentIndex < seasonEpisodeList.size - 1) {
            val nextEpisode = seasonEpisodeList[currentIndex + 1]

            // 3. Launch a coroutine to load the new episode's data
            lifecycleScope.launch {
                loadVideoForEpisode(nextEpisode)
            }
        } else {
            // 4. Handle the case where it's the last episode
            Toast.makeText(this, "You've finished the season!", Toast.LENGTH_SHORT).show()
            // Optional: you could finish the activity here if you want
            // finish()
        }
    }

    private suspend fun loadVideoForEpisode(episode: SEpisode) {
        // Show a loading indicator while we fetch the new data
        loadingIndicator.visibility = View.VISIBLE
        player?.pause() // Pause the current player

        try {
            // Fetch the list of video servers/qualities for the new episode
            val newVideoList = sourceManager.fetchVideoList(episode.url!!, specificSource)

            if (newVideoList.isNotEmpty()) {
                // Update the activity's state to the new episode
                currentEpisode = episode
                videoList = newVideoList
                startPosition = 0L // Always start the next episode from the beginning

                // Update UI elements
                updateEpisodeUI()

                // Re-initialize the player with the new video
                // If there's only one server, play it directly. Otherwise, show the selection dialog.
                if (videoList.size == 1) {
                    initializePlayerForVideo(videoList.first())
                } else {
                    showSourceSelectionDialog()
                }
            } else {
                Toast.makeText(this, "Could not find video for the next episode.", Toast.LENGTH_LONG).show()
                loadingIndicator.visibility = View.GONE
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading next episode: ${e.message}", Toast.LENGTH_LONG).show()
            loadingIndicator.visibility = View.GONE
        }
    }

    private fun updateEpisodeUI() {
        // Update the title at the top of the player
        tvEpisodeTitle.text = "${currentAnime?.title} - ${currentEpisode?.name}"

        // Re-check if the "next episode" button should be visible
        val currentIndex = seasonEpisodeList.indexOfFirst { it.url == currentEpisode?.url }
        btnNextEpisode.visibility = if (currentIndex != -1 && currentIndex < seasonEpisodeList.size - 1) {
            View.VISIBLE
        } else {
            View.GONE
        }

        // Clear any old skip intro buttons
        btnSkipIntro.visibility = View.GONE
        currentSkipStamp = null
        skipStamps = emptyList()

        // Reset the seek bar and time displays
        seekBar.progress = 0
        tvCurrentTime.text = formatTime(0)
        tvTotalTime.text = formatTime(0)
    }

    // ENHANCED: Gesture detector with all features from the original code
    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestureDetector() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLocked) {
                    btnUnlock.visibility = View.VISIBLE
                    hideHandler.postDelayed({ btnUnlock.visibility = View.GONE }, 2000)
                } else {
                    toggleControls()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isLocked) {
                    if (e.x < playerView.width / 2) rewind() else fastForward()
                }
                return true
            }

            // ENHANCED: Full scroll implementation with brightness, volume, and seeking
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (isLocked || e1 == null || player == null) return false

                val dx = e2.x - e1.x
                val dy = e2.y - e1.y

                // Determine if the scroll is more horizontal or vertical
                if (abs(dx) > abs(dy)) {
                    // --- START: MODIFIED HORIZONTAL SCROLL LOGIC ---
                    // HORIZONTAL SCROLL (SEEKING)
                    if (!isSeeking) {
                        // Capture the starting position at the beginning of the gesture
                        isSeeking = true
                        seekStartPosition = player!!.currentPosition
                    }

                    brightnessOverlay.visibility = View.GONE
                    volumeOverlay.visibility = View.GONE
                    tvSeekTime.visibility = View.VISIBLE

                    val duration = player!!.duration
                    val sensitivityMultiplier = 2.0
                    // Calculate the total offset from the start of the gesture
                    val seekOffset = (dx * (duration / (playerView.width.toFloat() * sensitivityMultiplier))).toLong()

                    // Calculate the new position based on the start position plus the total offset
                    val newPosition = (seekStartPosition + seekOffset).coerceIn(0, duration)
                    player!!.seekTo(newPosition) // Seek to the calculated absolute position

                    val changeSeconds = seekOffset / 1000
                    val changeSign = if (seekOffset >= 0) "+" else "-"
                    val changeMinutesPart = abs(changeSeconds) / 60
                    val changeSecondsPart = abs(changeSeconds) % 60

                    val formattedChange = String.format("%s%02d:%02d", changeSign, changeMinutesPart, changeSecondsPart)
                    val formattedPosition = formatTime(newPosition)

                    tvSeekTime.text = "$formattedChange [$formattedPosition]"
                    // --- END: MODIFIED HORIZONTAL SCROLL LOGIC ---

                } else {
                    // VERTICAL SCROLL (BRIGHTNESS/VOLUME)
                    if (e2.x < playerView.width / 2) {
                        adjustBrightness(-dy) // Invert dy for natural feel
                    } else {
                        adjustVolume(-dy) // Invert dy for natural feel
                    }
                }
                return true
            }

            // ADDED BACK: Long press for speed up
            override fun onLongPress(e: MotionEvent) {
                if (isLocked) return

                isOnLongPressSpeedUp = true
                player?.setPlaybackParameters(PlaybackParameters(2f))
                speedIndicatorText.text = "Speed: 2.0x"
                speedIndicatorText.visibility = View.VISIBLE
            }
        })

        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)

            // Handle touch release events
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                // --- ADD THIS LINE ---
                isSeeking = false // Reset seek state when the gesture is finished

                if (isOnLongPressSpeedUp) {
                    isOnLongPressSpeedUp = false
                    player?.setPlaybackParameters(PlaybackParameters(1f))
                    speedIndicatorText.visibility = View.GONE
                }

                // Hide seek time indicator after scroll
                if (tvSeekTime.visibility == View.VISIBLE) {
                    hideHandler.postDelayed({ tvSeekTime.visibility = View.GONE }, 500)
                }
            }
            true
        }
    }

    private fun setupAudioManager() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            currentBrightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Settings.SettingNotFoundException) {
            currentBrightness = 0.5f
            e.printStackTrace()
        }
        updateVolumeProgress()
        updateBrightnessProgress()
    }

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && player != null) {
                    val duration = player!!.duration
                    if (duration > 0) {
                        player!!.seekTo((progress * duration) / 100)
                        updateCurrentTime()
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { hideHandler.removeCallbacks(hideRunnable) }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { scheduleHideControls() }
        })
    }

    private fun toggleControls() {
        if (isControlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        btnLock.visibility = View.VISIBLE

        if (!isLocked) {
            topOverlay.visibility = View.VISIBLE
            bottomControls.visibility = View.VISIBLE
            centerControls.visibility = View.VISIBLE
            btnPlayPause.requestFocus()
        } else {
            btnUnlock.requestFocus()
        }

        isControlsVisible = true
        scheduleHideControls()
    }

    private fun hideControls() {
        if (isLocked) return

        topOverlay.visibility = View.GONE
        bottomControls.visibility = View.GONE
        centerControls.visibility = View.GONE
        brightnessOverlay.visibility = View.GONE
        volumeOverlay.visibility = View.GONE
        tvSeekTime.visibility = View.GONE
        btnLock.visibility = View.GONE

        playerView.clearFocus()

        isControlsVisible = false
        hideHandler.removeCallbacks(hideRunnable)
    }

    private fun scheduleHideControls() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 3000)
    }

    // ADDED BACK: Brightness and volume adjustment functions
    private fun adjustBrightness(deltaY: Float) {
        currentBrightness = (currentBrightness + (deltaY / (playerView.height * 2f))).coerceIn(0f, 1f)
        window.attributes = window.attributes.apply { screenBrightness = currentBrightness }
        updateBrightnessProgress()
        showBrightnessOverlay()
    }

    private fun adjustVolume(deltaY: Float) {
        val change = (deltaY / (playerView.height * 0.5f)) * maxVolume
        currentVolume = (currentVolume + change.toInt()).coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
        updateVolumeProgress()
        showVolumeOverlay()
    }

    private fun updateBrightnessProgress() {
        val progress = (currentBrightness * 100).toInt()
        brightnessProgress.progress = progress
        tvBrightnessValue.text = "$progress%"
    }

    private fun updateVolumeProgress() {
        val progress = if (maxVolume > 0) (currentVolume * 100) / maxVolume else 0
        volumeProgress.progress = progress
        tvVolumeValue.text = "$progress%"
        ivVolumeIcon.setImageResource(
            when {
                currentVolume == 0 -> R.drawable.ic_volume_off
                currentVolume < maxVolume / 2 -> R.drawable.ic_volume_down
                else -> R.drawable.ic_volume_up
            }
        )
    }

    private fun showBrightnessOverlay() {
        brightnessOverlay.visibility = View.VISIBLE
        hideHandler.removeCallbacks(hideBrightnessOverlay)
        hideHandler.postDelayed(hideBrightnessOverlay, 1000)
    }

    private val hideBrightnessOverlay = Runnable { brightnessOverlay.visibility = View.GONE }

    private fun showVolumeOverlay() {
        volumeOverlay.visibility = View.VISIBLE
        hideHandler.removeCallbacks(hideVolumeOverlay)
        hideHandler.postDelayed(hideVolumeOverlay, 1000)
    }

    private val hideVolumeOverlay = Runnable { volumeOverlay.visibility = View.GONE }

    // ADDED BACK: Show seek time function
    private fun showSeekTime(text: String) {
        tvSeekTime.text = text
        tvSeekTime.visibility = View.VISIBLE
        hideHandler.removeCallbacks(hideSeekTime)
        hideHandler.postDelayed(hideSeekTime, 1000)
    }

    private val hideSeekTime = Runnable { tvSeekTime.visibility = View.GONE }

    private fun updatePlayPauseButton() {
        player?.let { p ->
            ivPlayPause.setImageResource(if (p.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow_large)
        }
    }

    private fun updateProgress() {
        player?.let { p ->
            val duration = p.duration
            val position = p.currentPosition
            if (duration > 0) {
                seekBar.progress = ((position * 100) / duration).toInt()
            }
            tvCurrentTime.text = formatTime(position)
            checkSkipButtonVisibility(position)
        }
        hideHandler.postDelayed({ updateProgress() }, 500)
    }

    private fun updateCurrentTime() {
        player?.let { p ->
            tvCurrentTime.text = formatTime(p.currentPosition)
        }
    }

    private fun updateDuration() {
        player?.let { p ->
            val duration = p.duration
            if (duration > 0) {
                tvTotalTime.text = formatTime(duration)
            }
        }
    }

    // ADDED BACK: Function to update resolution display
    private fun updateResolutionDisplay() {
        player?.let { p ->
            val videoSize = p.videoSize
            if (videoSize.height > 0) {
                val currentQuality = "${videoSize.height}p"
                val selectedServer = tvServerName.text.toString().split(" ")[0] // Get server name before " (Auto)"
                tvServerName.text = "$selectedServer (Auto - $currentQuality)"
            }
        }
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        saveWatchProgress()
        player?.release()
        hideHandler.removeCallbacksAndMessages(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        saveWatchProgress()
        player?.pause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        player?.play()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}