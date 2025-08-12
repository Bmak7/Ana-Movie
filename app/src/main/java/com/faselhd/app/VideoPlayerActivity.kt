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
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import android.view.KeyEvent
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.source.MediaSource
import com.example.myapplication.R
import com.faselhd.app.models.Video
import kotlin.math.abs
import com.faselhd.app.db.AppDatabase
import com.faselhd.app.models.SAnime
import com.faselhd.app.models.SEpisode
import com.faselhd.app.models.WatchHistory
import com.faselhd.app.utils.DownloadUtil
import com.faselhd.app.utils.EpisodeSkip
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.view.WindowManager // Add this import
import kotlinx.coroutines.withContext


class VideoPlayerActivity : AppCompatActivity() {

    // In VideoPlayerActivity.kt

    companion object {
        private const val EXTRA_VIDEOS = "extra_videos"
        private const val EXTRA_ANIME = "extra_anime"
        private const val EXTRA_EPISODE = "extra_episode"
        private const val EXTRA_START_POSITION = "extra_start_position"
        private const val EXTRA_IS_OFFLINE = "extra_is_offline"
        // CLEANED UP FUNCTION SIGNATURE
        fun newIntent(
            context: Context,
            videos: List<Video>,
            anime: SAnime,
            currentEpisode: SEpisode,
            episodeListForSeason: ArrayList<SEpisode>, // <-- ADD THIS
            startPosition: Long = 0L,
            isOffline: Boolean = false // <-- ADD THIS NEW PARAMETER
        ): Intent {
            return Intent(context, VideoPlayerActivity::class.java).apply {
                putParcelableArrayListExtra(EXTRA_VIDEOS, ArrayList(videos))
                putExtra(EXTRA_ANIME, anime)
                putExtra(EXTRA_EPISODE, currentEpisode)
                putParcelableArrayListExtra("extra_episode_list", episodeListForSeason) // <-- ADD THIS
                putExtra(EXTRA_START_POSITION, startPosition)
                putExtra(EXTRA_IS_OFFLINE, isOffline) // <-- PUT THE NEW EXTRA
            }
        }
    }

    private lateinit var playerView: PlayerView
    private lateinit var player: ExoPlayer
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var audioManager: AudioManager

    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var btnQuality: ImageButton

    // UI Elements
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
    private lateinit var btnFullscreen: ImageButton
    private lateinit var btnResize: ImageButton
    private lateinit var btnSubtitle: ImageButton
    private lateinit var btnNextEpisode: ImageButton

    private lateinit var tvResolution: TextView
    private lateinit var tvEpisodeTitle: TextView
    private lateinit var tvSeekTime: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var seekBar: SeekBar
    private var seekChange: Long = 0
    private lateinit var brightnessProgress: ProgressBar
    private lateinit var volumeProgress: ProgressBar
    private lateinit var tvBrightnessValue: TextView
    private lateinit var tvVolumeValue: TextView
    private lateinit var ivVolumeIcon: ImageView
    private lateinit var loadingIndicator: ProgressBar

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }

    private var isControlsVisible = true
    private var isLocked = false
    private var isFullscreen = true
    // CORRECTED: Use AspectRatioFrameLayout for resize mode constants
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
    private var currentBrightness = 0.5f
    private var currentVolume = 0
    private var maxVolume = 0



    private var videoUrl: String = ""
    private var videoTitle: String = ""
    private var episodeNumber: String = ""

    private lateinit var speedIndicatorText: TextView
    private var isOnLongPressSpeedUp = false
    private var isAutoMode = true // Assume Auto mode by default

    private lateinit var lockOverlay: FrameLayout
    private lateinit var btnUnlock: ImageButton

    private lateinit var rewindIndicator: LinearLayout
    private lateinit var forwardIndicator: LinearLayout

    private lateinit var btnSkipIntro: MaterialButton
    // This will hold our list of video qualities
    private var videoList: List<Video> = emptyList()

    // vars for Database
    private val db by lazy { AppDatabase.getDatabase(this) }
    private var currentAnime: SAnime? = null // We need anime details
    private var currentEpisode: SEpisode? = null // and episode details
    private var seasonEpisodeList: List<SEpisode> = emptyList()

    private var skipStamps: List<EpisodeSkip.SkipStamp> = emptyList()
    private var currentSkipStamp: EpisodeSkip.SkipStamp? = null


    // In VideoPlayerActivity.kt -> onCreate() method

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Retrieve data using the new, clean keys
        videoList = intent.getParcelableArrayListExtra(EXTRA_VIDEOS) ?: emptyList()
        currentAnime = intent.getParcelableExtra(EXTRA_ANIME)
        seasonEpisodeList = intent.getParcelableArrayListExtra("extra_episode_list") ?: emptyList()
        currentEpisode = intent.getParcelableExtra(EXTRA_EPISODE)
        val startPosition = intent.getLongExtra(EXTRA_START_POSITION, 0L)

        // Check for essential data
        if (videoList.isEmpty() || currentAnime == null || currentEpisode == null) {
            Toast.makeText(this, "Video source not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initializeViews()

        // Set an initial value for the resolution text.
        tvResolution.text = "Auto"
        // Use the data we just retrieved
        tvEpisodeTitle.text = "${currentAnime?.title} - ${currentEpisode?.name}"

        setupPlayer()
        setupGestureDetector()
        setupAudioManager()
        setupClickListeners()
        setupSeekBar()
        hideSystemUI()

        if (startPosition > 0) {
            player.seekTo(startPosition)
        }

        scheduleHideControls()

        val currentIndex = seasonEpisodeList.indexOf(currentEpisode)
        if (currentIndex == -1 || currentIndex == seasonEpisodeList.size - 1) {
            btnNextEpisode.visibility = View.GONE
        }

        // --- NEW: Add the flag here to keep the screen on when activity is created ---
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun saveWatchProgress() {
        if (!::player.isInitialized) return
        val anime = currentAnime ?: return
        val episode = currentEpisode ?: return
        val position = player.currentPosition
        val duration = player.duration

        if (duration <= 0 || episode.url.isNullOrEmpty()) return

        val progressPercentage = (position * 100) / duration

        CoroutineScope(Dispatchers.IO).launch {
            if (progressPercentage > 90) {
                // --- EPISODE IS FINISHED ---
                // 1. Delete the record for the episode that was just completed.
//                db.watchHistoryDao().delete(episode.url!!)

                // 2. Find the next epnextEpisodeisode in the list.
                val currentIndex = seasonEpisodeList.indexOfFirst { it.url == episode.url }
                if (currentIndex != -1 && currentIndex < seasonEpisodeList.size - 1) {
                    val nextEpisode = seasonEpisodeList[currentIndex + 1]

                    // 3. Create a NEW "Continue Watching" entry for the NEXT episode.
                    //    Set its progress to the beginning.
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
                        seasonEpisodes = seasonEpisodeList
                    )

                    // 4. Upsert the new entry for the next episode.
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
                        seasonEpisodes = seasonEpisodeList
                    )
                    db.watchHistoryDao().upsert(watchHistory)
                }
                // If it's the last episode of the season, we do nothing, effectively removing the series
                // from the "Continue Watching" list because the last episode was deleted and there's no next one to add.

            } else {
                // --- EPISODE IS IN PROGRESS ---
                // This logic remains the same: save the current progress.
                val watchHistory = WatchHistory(
                    episodeUrl = episode.url!!,
                    animeUrl = anime.url!!,
                    animeTitle = anime.title ?: "Unknown Title",
                    animeThumbnailUrl = anime.thumbnail_url,
                    episodeName = episode.name ?: "Unknown Episode",
                    lastWatchedPosition = position,
                    duration = duration,
                    timestamp = System.currentTimeMillis(),
                    isFinished = false,
                    episodeNumber = episode.episode_number.toInt(),
                    seasonEpisodes = seasonEpisodeList
                )
                db.watchHistoryDao().upsert(watchHistory)
            }
        }
    }


    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, playerView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            // This makes the bars appear temporarily when the user swipes from the edge.
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // --- NEW: Override dispatchKeyEvent for D-pad handling ---
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Only interested in key down events
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Any D-pad interaction should reset the auto-hide timer
            if (isDpadEvent(event)) {
                scheduleHideControls()
            }

            // If locked, only the back button should work
            if (isLocked) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    // Allow back press to exit while locked
                    return super.dispatchKeyEvent(event)
                }
                // Consume all other events when locked
                return true
            }

            when (event.keyCode) {
                // If controls are hidden, DPAD_CENTER shows them
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (!isControlsVisible) {
                        showControls()
                        return true // Consume the event, don't pass it on
                    }
                }
                // Handle dedicated media keys
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (player.isPlaying) player.pause() else player.play()
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

    // --- NEW: Override onBackPressed for better TV UX ---
    override fun onBackPressed() {
        // If controls are visible, the first back press should hide them.
        if (isControlsVisible) {
            hideControls()
        } else {
            // If controls are already hidden, then exit the activity.
            super.onBackPressed()
        }
    }

    // --- NEW: Helper function to identify D-pad events ---
    private fun isDpadEvent(event: KeyEvent): Boolean {
        return event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
    }


    private fun initializeViews() {
        playerView = findViewById(R.id.player_view)
        btnQuality = findViewById(R.id.btn_quality)
        topOverlay = findViewById(R.id.top_overlay)
        centerControls = findViewById(R.id.center_controls)
        bottomControls = findViewById(R.id.bottom_controls)
        brightnessOverlay = findViewById(R.id.brightness_overlay)
        volumeOverlay = findViewById(R.id.volume_overlay)
        btnBack = findViewById(R.id.btn_back)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        ivPlayPause = findViewById(R.id.iv_play_pause)
        btnRewind = findViewById(R.id.btn_rewind)
        btnFastForward = findViewById(R.id.btn_fast_forward)
        btnLock = findViewById(R.id.btn_lock)
        btnFullscreen = findViewById(R.id.btn_fullscreen)
        btnResize = findViewById(R.id.btn_resize)
        btnSubtitle = findViewById(R.id.btn_subtitle)
        btnNextEpisode = findViewById(R.id.btn_next_episode)
        tvResolution = findViewById(R.id.tv_resolution)
        tvEpisodeTitle = findViewById(R.id.tv_episode_title)
        tvSeekTime = findViewById(R.id.tv_seek_time)
        tvCurrentTime = findViewById(R.id.tv_current_time)
        tvTotalTime = findViewById(R.id.tv_total_time)
        seekBar = findViewById(R.id.seek_bar)
        brightnessProgress = findViewById(R.id.brightness_progress)
        volumeProgress = findViewById(R.id.volume_progress)
        tvBrightnessValue = findViewById(R.id.tv_brightness_value)
        tvVolumeValue = findViewById(R.id.tv_volume_value)
        ivVolumeIcon = findViewById(R.id.iv_volume_icon)
        loadingIndicator = findViewById(R.id.loading_indicator)
        btnSkipIntro = findViewById(R.id.btn_skip_intro)
        lockOverlay = findViewById(R.id.lock_overlay)
        btnUnlock = findViewById(R.id.btn_unlock)

        rewindIndicator = findViewById(R.id.rewind_indicator)
        forwardIndicator = findViewById(R.id.forward_indicator)

        speedIndicatorText = findViewById(R.id.speed_indicator_text)
    }

    @OptIn(UnstableApi::class)
    private fun setupPlayer() {
        trackSelector = DefaultTrackSelector(this)
        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .build()
        playerView.player = player
        playerView.useController = false

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                Log.e("VideoPlayerActivity", "ExoPlayer Error: ", error)
                Toast.makeText(
                    this@VideoPlayerActivity,
                    "Player Error: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                loadingIndicator.visibility = if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (playbackState == Player.STATE_READY) {
                    updatePlayPauseButton()
                    updateDuration()
                    fetchSkipTimes()
                } else if (playbackState == Player.STATE_ENDED) {
                    // Consider playing the next episode automatically here
                    playNextEpisode()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseButton()
            }

            override fun onTracksChanged(tracks: Tracks) {
                super.onTracksChanged(tracks)
                val mappedTrackInfo = trackSelector.currentMappedTrackInfo ?: return

                var videoRendererIndex = -1
                for (i in 0 until mappedTrackInfo.rendererCount) {
                    if (player.getRendererType(i) == C.TRACK_TYPE_VIDEO) {
                        videoRendererIndex = i
                        break
                    }
                }
                if (videoRendererIndex == -1) return

                val trackGroups = mappedTrackInfo.getTrackGroups(videoRendererIndex)
                if (trackGroups.isEmpty) return

                val selectionOverride = trackSelector.parameters.getSelectionOverride(videoRendererIndex, trackGroups)

                // Update our class property
                isAutoMode = (selectionOverride == null)

                // Now, immediately try to update the full text. This will work if video size is already known.
                updateResolutionText()
            }

            // --- (Listener 2) - GET THE ACTUAL PLAYING RESOLUTION ---
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                super.onVideoSizeChanged(videoSize)

                // The video size has changed, so we can now reliably update the text.
                updateResolutionText()
            }
        })

        val isOffline = intent.getBooleanExtra("extra_is_offline", false)

        if (isOffline) {
            // --- OFFLINE PLAYBACK (This part is correct) ---
            val mediaItem = MediaItem.fromUri(currentEpisode!!.url!!)
            val dataSourceFactory = DownloadUtil.getReadOnlyCacheDataSourceFactory(this)
            val mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            player.setMediaSource(mediaSource)

        } else {
            // --- ONLINE STREAMING (This is the corrected part) ---
            val masterPlaylistUrl = createMasterPlaylist(videoList)
            val mediaItem = MediaItem.fromUri(masterPlaylistUrl)

            // 1. Get your factory that handles HTTP requests and caching.
            val httpDataSourceFactory = DownloadUtil.getCacheDataSourceFactory(this)

            // 2. Create a DefaultDataSource.Factory. This is the key change.
            //    It can handle multiple protocols (file://, asset://, and data://).
            //    We pass your http factory to it, so it knows what to use for network requests.
            val mainDataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

            // 3. Create the HlsMediaSource with this new, more capable factory.
            val mediaSource = HlsMediaSource.Factory(mainDataSourceFactory).createMediaSource(mediaItem)
            player.setMediaSource(mediaSource)
        }

        player.prepare()
        player.play()
        updateProgress()
    }

    private fun updateResolutionText() {
        // Get the current video size. If it's unknown, do nothing.
        val videoSize = player.videoSize
        if (videoSize.height == 0) return // Height is 0 if resolution is not yet determined

        // Get the playing quality from the height (e.g., "1080p")
        val playingQuality = "${videoSize.height}p"

        // Use our class property to format the text correctly
        val displayText = if (isAutoMode) {
            "Auto ($playingQuality)"
        } else {
            playingQuality
        }

        // Update the UI on the main thread
        runOnUiThread {
            tvResolution.text = displayText
        }
    }

    private fun rewind() {
        player.seekTo((player.currentPosition - 10000).coerceAtLeast(0))
        showSeekIndicator(rewindIndicator)
    }

    private fun fastForward() {
        player.seekTo((player.currentPosition + 10000).coerceAtMost(player.duration))
        showSeekIndicator(forwardIndicator)
    }

    // Add this new helper function
    private fun showSeekIndicator(view: View) {
        view.visibility = View.VISIBLE
        // You can add animations here for a nicer effect
        view.animate().alpha(0f).setDuration(800).withEndAction {
            view.visibility = View.GONE
            view.alpha = 1f // Reset alpha for next time
        }.start()
    }

    // THIS IS THE NEW HELPER FUNCTION
    // THIS IS THE CORRECTED HELPER FUNCTION
    private fun createMasterPlaylist(videos: List<Video>): String {
        val playlistBuilder = StringBuilder()
        // The master playlist must start with this tag
        playlistBuilder.append("#EXTM3U\n")

        // Sort videos by quality to ensure a logical order in the playlist
        val sortedVideos = videos.sortedByDescending {
            it.quality.filter { char -> char.isDigit() }.toIntOrNull() ?: 0
        }

        for (video in sortedVideos) {
            // --- START OF CHANGES ---

            // We need to provide BOTH resolution and an estimated bandwidth
            val resolution: String
            val bandwidth: Int

            when {
                "1080p" in video.quality -> {
                    resolution = "1920x1080"
                    bandwidth = 5000000 // 5 Mbps
                }
                "720p" in video.quality -> {
                    resolution = "1280x720"
                    bandwidth = 2800000 // 2.8 Mbps
                }
                "480p" in video.quality -> {
                    resolution = "854x480"
                    bandwidth = 1400000 // 1.4 Mbps
                }
                "360p" in video.quality -> {
                    resolution = "640x360"
                    bandwidth = 800000  // 800 kbps
                }
                else -> {
                    // Have a fallback for unknown qualities
                    resolution = ""
                    bandwidth = 500000 // 500 kbps
                }
            }

            // This is now the CORRECT format for the tag, with both attributes
            if (resolution.isNotEmpty()) {
                playlistBuilder.append("#EXT-X-STREAM-INF:BANDWIDTH=$bandwidth,RESOLUTION=$resolution\n")
            } else {
                playlistBuilder.append("#EXT-X-STREAM-INF:BANDWIDTH=$bandwidth\n")
            }

            // --- END OF CHANGES ---

            // The URL for the stream goes on the next line
            playlistBuilder.append("${video.url}\n")
        }

        // This part remains the same, encoding the playlist string for the player
        return "data:application/x-mpegURL;base64," +
                android.util.Base64.encodeToString(playlistBuilder.toString().toByteArray(), android.util.Base64.NO_WRAP)
    }
    private fun setupClickListeners() {
        btnBack.setOnClickListener { finish() }
        btnPlayPause.setOnClickListener {
            if (player.isPlaying) player.pause() else player.play()
            scheduleHideControls()
        }
        btnRewind.setOnClickListener {
            rewind()
            scheduleHideControls()
        }
        btnQuality.setOnClickListener {
            showQualityDialog()
            scheduleHideControls()
        }
        btnFastForward.setOnClickListener {
            fastForward()
            scheduleHideControls()
        }

        btnLock.setOnClickListener { toggleLock() }
//        btnFullscreen.setOnClickListener {
//            toggleFullscreen()
//            scheduleHideControls()
//        }


        // --- MODIFIED: The fullscreen button is no longer needed to enter fullscreen ---
        btnFullscreen.setOnClickListener {
            // The activity is always in fullscreen landscape, so this button may not be needed.
            // You could repurpose it for another feature or remove it.
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
        btnNextEpisode.setOnClickListener {
            playNextEpisode()
        }

        btnSkipIntro.setOnClickListener {
            currentSkipStamp?.let {
                player.seekTo(it.endMs)
                // Hide the button immediately after skipping
                btnSkipIntro.visibility = View.GONE
                currentSkipStamp = null
            }
        }

        lockOverlay = findViewById(R.id.lock_overlay)
        btnUnlock = findViewById(R.id.btn_unlock)

    }

    fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        if (isLocked) {
            // If locked, a single tap will briefly show the unlock button
            btnUnlock.visibility = View.VISIBLE
            hideHandler.postDelayed({ btnUnlock.visibility = View.GONE }, 2000)
        } else {
            toggleControls()
        }
        return true
    }

    private fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) {
            hideControls()
            lockOverlay.visibility = View.VISIBLE
            // Hide the unlock button after a delay but keep focus
            btnUnlock.requestFocus()
            hideHandler.postDelayed({ btnUnlock.visibility = View.GONE }, 2000)
        } else {
            lockOverlay.visibility = View.GONE
            // When unlocking, immediately show controls and set focus
            showControls()
        }
    }


    private fun fetchSkipTimes() {
        val anime = currentAnime ?: return
        val episode = currentEpisode ?: return
        val duration = player.duration

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

        // Find if we are currently inside any skip stamp window
        val activeStamp = skipStamps.find { currentPosition in it.startMs..it.endMs }

        if (activeStamp != null) {
            // We are inside a skip window
            if (btnSkipIntro.visibility == View.GONE) {
                // Show the button and set its text
                currentSkipStamp = activeStamp
                btnSkipIntro.text = activeStamp.type.text
                btnSkipIntro.visibility = View.VISIBLE
            }
        } else {
            // We are outside all skip windows
            if (btnSkipIntro.visibility == View.VISIBLE) {
                // Hide the button
                btnSkipIntro.visibility = View.GONE
                currentSkipStamp = null
            }
        }
    }
    private fun playNextEpisode() {
        val currentIndex = seasonEpisodeList.indexOf(currentEpisode)
        if (currentIndex != -1 && currentIndex < seasonEpisodeList.size - 1) {
            val nextEpisode = seasonEpisodeList[currentIndex + 1]

            // To play the next episode, we essentially just restart the
            // process of launching AnimeDetailsActivity with a resume signal.
            // This is the simplest and most robust way.
            val intent = AnimeDetailsActivity.newIntentWithResume(
                context = this,
                anime = currentAnime!!,
                resumeEpisodeUrl = nextEpisode.url!!
            )
            // Add flags to clear the old player from the back stack
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish() // Close the current player
        }
    }


    @OptIn(UnstableApi::class)
    private fun showQualityDialog() {
        val trackSelector = this.trackSelector
        val mappedTrackInfo = trackSelector.currentMappedTrackInfo
        if (mappedTrackInfo == null) {
            Toast.makeText(this, "No quality options available", Toast.LENGTH_SHORT).show()
            return
        }

        // Find the video renderer index
        var videoRendererIndex = -1
        for (i in 0 until mappedTrackInfo.rendererCount) {
            if (player.getRendererType(i) == C.TRACK_TYPE_VIDEO) {
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

        // Prepare dialog options
        val qualityOptions = mutableListOf<String>()
        val trackIndices = mutableListOf<Int>()
        qualityOptions.add("Auto") // "Auto" is always the first option

        for (i in 0 until trackGroups.length) {
            val group = trackGroups.get(i)
            for (j in 0 until group.length) {
                val format = group.getFormat(j)
                qualityOptions.add("${format.height}p")
                trackIndices.add(j)
            }
        }

        // Determine currently selected item
        val selectionOverride = trackSelector.parameters.getSelectionOverride(videoRendererIndex, trackGroups)
        var checkedItem = 0 // Default to "Auto"
        if (selectionOverride != null && selectionOverride.length > 0) {
            checkedItem = trackIndices.indexOf(selectionOverride.tracks[0]) + 1
        }

        // Create and show the dialog
        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("Select Quality")
        builder.setSingleChoiceItems(qualityOptions.toTypedArray(), checkedItem) { dialog, which ->
            val parametersBuilder = trackSelector.buildUponParameters()
            if (which == 0) {
                // User selected "Auto"
                parametersBuilder.clearSelectionOverrides(videoRendererIndex)
            } else {
                // User selected a specific quality
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



    // CORRECTED: Function now uses AspectRatioFrameLayout constants
    @OptIn(UnstableApi::class)
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
        // This line is correct, as setResizeMode takes the integer constant
        playerView.resizeMode = currentResizeMode
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isLocked) toggleControls()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isLocked) {
                    if (e.x < playerView.width / 2) rewind() else fastForward()
                }
                return true
            }
            // ** ADD HORIZONTAL SEEK LOGIC TO onScroll **
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (isLocked || e1 == null) return false

                val dx = e2.x - e1.x
                val dy = e2.y - e1.y

                // Determine if the scroll is more horizontal or vertical
                if (abs(dx) > abs(dy)) {
                    // HORIZONTAL SCROLL (SEEKING)
                    // Hide other overlays
                    brightnessOverlay.visibility = View.GONE
                    volumeOverlay.visibility = View.GONE

                    // Show the seek time overlay
                    tvSeekTime.visibility = View.VISIBLE

                    // Calculate the seek amount based on scroll distance
                    val seekAmount = (-distanceX * (player.duration / playerView.width.toFloat())).toLong()
                    val newPosition = (player.currentPosition + seekAmount).coerceIn(0, player.duration)

                    player.seekTo(newPosition)

                    val sensitivityMultiplier = 2.0
                    seekChange = (dx * (player.duration / (playerView.width.toFloat() * sensitivityMultiplier))).toLong()

                    // Calculate the target position
                    // Update the seek time text to show current time and change
                    val changeSeconds = seekChange / 1000
                    val changeSign = if (seekChange >= 0) "+" else "-"
                    val changeMinutesPart = abs(changeSeconds) / 60
                    val changeSecondsPart = abs(changeSeconds) % 60

                    val formattedChange = String.format("%s%02d:%02d", changeSign, changeMinutesPart, changeSecondsPart)
                    val formattedPosition = formatTime(newPosition)

                    tvSeekTime.text = "$formattedChange [$formattedPosition]"

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

            // ** IMPLEMENT onLongPress **
            override fun onLongPress(e: MotionEvent) {
                if (isLocked) return

                // Set a flag to indicate we are in "speed up" mode
                isOnLongPressSpeedUp = true

                // Set player speed to 2x
                player.setPlaybackParameters(PlaybackParameters(2f))

                // Show UI feedback
                speedIndicatorText.text = "Speed: 2.0x"
                speedIndicatorText.visibility = View.VISIBLE
            }

        })


        // ** MODIFY onTouchEvent to detect the release **
        playerView.setOnTouchListener { _, event ->
            // Pass the event to the gesture detector first
            gestureDetector.onTouchEvent(event)

            // Check for the "finger up" or "gesture cancelled" events
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                // Check if we were in the "speed up" mode
                if (isOnLongPressSpeedUp) {
                    // We are no longer in speed up mode
                    isOnLongPressSpeedUp = false

                    // Revert player speed to normal
                    player.setPlaybackParameters(PlaybackParameters(1f))

                    // Hide the UI feedback
                    speedIndicatorText.visibility = View.GONE
                }

                // Also hide the horizontal seek time indicator if it was visible
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
                if (fromUser) {
                    val duration = player.duration
                    if (duration > 0) {
                        player.seekTo((progress * duration) / 100)
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
            // Request focus on the play/pause button, the most common action.
            btnPlayPause.requestFocus()
        } else {
            // If locked, only the unlock button should be focusable
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

        // Clear focus from the controls so the D-pad doesn't interact with hidden views
        playerView.clearFocus()

        isControlsVisible = false
        hideHandler.removeCallbacks(hideRunnable)
    }

    private fun scheduleHideControls() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 3000)
    }

//    private fun toggleLock() {
//        isLocked = !isLocked
//        if (isLocked) {
//            // When locking the screen:
//            btnLock.setImageResource(R.drawable.ic_lock)
//            // Hide EVERYTHING except for the lock button itself.
//            topOverlay.visibility = View.GONE
//            bottomControls.visibility = View.GONE
//            centerControls.visibility = View.GONE
//            brightnessOverlay.visibility = View.GONE
//            volumeOverlay.visibility = View.GONE
//            tvSeekTime.visibility = View.GONE
//            isControlsVisible = false
//            hideHandler.removeCallbacks(hideRunnable) // Stop the auto-hide timer
//        } else {
//            // When unlocking the screen:
//            btnLock.setImageResource(R.drawable.ic_lock_open)
//            // Show all the controls again.
//            showControls()
//        }
//    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            btnFullscreen.setImageResource(R.drawable.mobile_rotate_24px)
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            btnFullscreen.setImageResource(R.drawable.mobile_rotate_24px)
        }
    }

//    private fun rewind() {
//        player.seekTo((player.currentPosition - 10000).coerceAtLeast(0))
//        showSeekTime("-10s")
//    }
//
//    private fun fastForward() {
//        player.seekTo((player.currentPosition + 10000).coerceAtMost(player.duration))
//        showSeekTime("+10s")
//    }

    private fun showSeekTime(text: String) {
        tvSeekTime.text = text
        tvSeekTime.visibility = View.VISIBLE
        hideHandler.removeCallbacks(hideSeekTime)
        hideHandler.postDelayed(hideSeekTime, 1000)
    }

    private val hideSeekTime = Runnable { tvSeekTime.visibility = View.GONE }

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

    private fun updatePlayPauseButton() {
        ivPlayPause.setImageResource(if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow_large)
    }

    private fun updateProgress() {
        if (::player.isInitialized) {
            val duration = player.duration
            val position = player.currentPosition

            if (duration > 0) {
                seekBar.progress = ((position * 100) / duration).toInt()
            }
            updateCurrentTime()

            // ** CHECK FOR SKIP BUTTON VISIBILITY ON EVERY UPDATE **
            checkSkipButtonVisibility(position)
        }

        // Schedule the next update
        hideHandler.postDelayed({ updateProgress() }, 500) // Check every 500ms
    }

    private fun updateCurrentTime() {
        tvCurrentTime.text = formatTime(player.currentPosition)
    }

    private fun updateDuration() {
        val duration = player.duration
        if (duration > 0) {
            tvTotalTime.text = formatTime(duration)
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
        saveWatchProgress() // Save one last time
        player.release()
        hideHandler.removeCallbacksAndMessages(null)

        // --- NEW: Remove the flag when the activity is completely destroyed ---
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    }

    override fun onPause() {
        super.onPause()
        saveWatchProgress()
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }

        // --- NEW: Remove the flag when the activity goes to the background ---
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        if (::player.isInitialized && !player.isPlaying) {
            player.play()
        }

        // --- NEW: Add the flag back when the activity comes to the foreground ---
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
