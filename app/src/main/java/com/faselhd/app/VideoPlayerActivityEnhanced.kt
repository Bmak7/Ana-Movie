package com.faselhd.app

// REQUIRED IMPORTS
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.*
import android.provider.Settings
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
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
import com.faselhd.app.utils.GestureControlManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class VideoPlayerActivityEnhanced : AppCompatActivity() {

    companion object {
        private const val EXTRA_VIDEOS = "extra_videos"
        private const val EXTRA_ANIME = "extra_anime"
        private const val EXTRA_EPISODE = "extra_episode"
        private const val EXTRA_START_POSITION = "extra_start_position"
        private const val EXTRA_IS_OFFLINE = "extra_is_offline"

        // *** ADD THESE MISSING CONSTANTS ***
        private const val CONTROL_FADE_DURATION = 300L
        private const val SEEK_INDICATOR_DURATION = 800L
        private const val OVERLAY_ANIMATION_DURATION = 250L

        fun newIntent(
            context: Context,
            videos: List<Video>,
            anime: SAnime,
            currentEpisode: SEpisode,
            episodeListForSeason: ArrayList<SEpisode>,
            startPosition: Long = 0L,
            isOffline: Boolean = false
        ): Intent {
            return Intent(context, VideoPlayerActivityEnhanced::class.java).apply {
                putParcelableArrayListExtra(EXTRA_VIDEOS, ArrayList(videos))
                putExtra(EXTRA_ANIME, anime)
                putExtra(EXTRA_EPISODE, currentEpisode)
                putParcelableArrayListExtra("extra_episode_list", episodeListForSeason)
                putExtra(EXTRA_START_POSITION, startPosition)
                putExtra(EXTRA_IS_OFFLINE, isOffline)
            }
        }
    }


    private lateinit var vibrator: Vibrator


    private lateinit var seekOverlay: MaterialCardView



    // --- State Management ---
    private val hideControlsRunnable = Runnable { hideControlsWithAnimation() }

    // Smooth Seek State
    private var isSeeking = false
    private var seekStartPosition: Long = 0


    // Player and UI components
    private lateinit var playerView: PlayerView
    private lateinit var player: ExoPlayer
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var audioManager: AudioManager
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var mediaSession: MediaSessionCompat

    // UI Elements
    private lateinit var topOverlay: LinearLayout
    private lateinit var centerControls: LinearLayout
    private lateinit var bottomControls: LinearLayout
    private lateinit var brightnessOverlay: LinearLayout
    private lateinit var volumeOverlay: LinearLayout
    private lateinit var videoInfoOverlay: ScrollView
    private lateinit var abRepeatIndicators: LinearLayout

    // Buttons and controls
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
    private lateinit var btnQuality: ImageButton
    private lateinit var btnVideoInfo: ImageButton
    private lateinit var btnAbRepeat: ImageButton
    private lateinit var btnPlaybackSpeed: TextView
    private lateinit var btnAudioTrack: ImageButton
    private lateinit var btnPip: ImageButton
    private lateinit var btnBackgroundPlay: ImageButton

    // Text views and progress bars
    private lateinit var tvResolution: TextView
    private lateinit var tvEpisodeTitle: TextView
    private lateinit var tvSeekTime: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var tvPointA: TextView
    private lateinit var tvPointB: TextView
    private lateinit var tvVideoInfoDetails: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var brightnessProgress: ProgressBar
    private lateinit var volumeProgress: ProgressBar
    private lateinit var tvBrightnessValue: TextView
    private lateinit var tvVolumeValue: TextView
    private lateinit var ivVolumeIcon: ImageView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var speedIndicatorText: TextView
    private lateinit var lockOverlay: FrameLayout
    private lateinit var btnUnlock: ImageButton
    private lateinit var rewindIndicator: LinearLayout
    private lateinit var forwardIndicator: LinearLayout
    private lateinit var btnSkipIntro: MaterialButton
    private lateinit var btnCloseVideoInfo: Button

    // State variables
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }
    private var isControlsVisible = true
    private var isLocked = false
    private var isFullscreen = true
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
    private var currentBrightness = 0.5f
    private var currentVolume = 0
    private var maxVolume = 0
    private var seekChange: Long = 0
    private var isOnLongPressSpeedUp = false

    // A-B Repeat functionality
    private var pointA: Long = -1
    private var pointB: Long = -1
    private var isAbRepeatActive = false

    // Playback speed
    private var currentPlaybackSpeed = 1.0f
    private val playbackSpeeds = arrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    // Data variables
    private var videoList: List<Video> = emptyList()
    private val db by lazy { AppDatabase.getDatabase(this) }
    private var currentAnime: SAnime? = null
    private var currentEpisode: SEpisode? = null
    private var seasonEpisodeList: List<SEpisode> = emptyList()
    private var skipStamps: List<EpisodeSkip.SkipStamp> = emptyList()
    private var currentSkipStamp: EpisodeSkip.SkipStamp? = null

    // Background playback
    private var isBackgroundPlayEnabled = false

    private val progressUpdateHandler = Handler(Looper.getMainLooper())
    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            updateProgress() // This function will be called repeatedly
            // Schedule the next execution after 1000 milliseconds (1 second)
            progressUpdateHandler.postDelayed(this, 1000)
        }
    }

    private lateinit var gestureControlManager: GestureControlManager




    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player_enhanced)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Retrieve data
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
        setupPlayer()
//        setupGestureDetector()
        setupEnhancedTouchListener() // <-- NEW FUNCTION CALL
        setupAudioManager()
        setupClickListeners()
        setupSeekBar()
        setupMediaSession()
        hideSystemUI()

        // Set episode title
        tvEpisodeTitle.text = "${currentAnime?.title} - ${currentEpisode?.name}"

        gestureControlManager = GestureControlManager(
            context = this,
            viewWidth = playerView.width, // 👈 Pass actual view width here
            listener = object : GestureControlManager.GestureListener {
                override fun onSingleTap() {
                    if (!isLocked) toggleControlsWithAnimation()
                }

                override fun onDoubleTap(isLeftSide: Boolean) {
                    if (isLocked) return
                    if (isLeftSide) {
                        seekRelative(-10000)
                        showEnhancedSeekIndicator(rewindIndicator, -10)
                    } else {
                        seekRelative(10000)
                        showEnhancedSeekIndicator(forwardIndicator, 10)
                    }
                }

                override fun onLongPress() {
                    if (isLocked) return
                    startSpeedControl()
                }

                override fun onScrollStart(type: GestureControlManager.ScrollType) {
                    if (isLocked) return
                    when (type) {
                        GestureControlManager.ScrollType.HORIZONTAL -> {
                            seekStartPosition = player.currentPosition
                            seekOverlay.visibility = View.VISIBLE
                            seekOverlay.bringToFront()
                            hideControlsWithAnimation()
                        }
                        GestureControlManager.ScrollType.VERTICAL_LEFT -> brightnessOverlay.visibility = View.VISIBLE
                        GestureControlManager.ScrollType.VERTICAL_RIGHT -> volumeOverlay.visibility = View.VISIBLE
                    }
                }

                override fun onScroll(type: GestureControlManager.ScrollType, delta: Float) {
                    if (isLocked) return
                    when (type) {
                        GestureControlManager.ScrollType.HORIZONTAL -> {
                            val sensitivity = 1.5f
                            seekChange += (delta * (player.duration / (playerView.width.toFloat() * sensitivity))).toLong()
                            val newPosition = (seekStartPosition + seekChange).coerceIn(0, player.duration)
                            updateSeekUI(newPosition, seekChange)
                        }
                        GestureControlManager.ScrollType.VERTICAL_LEFT -> handleEnhancedBrightness(delta)
                        GestureControlManager.ScrollType.VERTICAL_RIGHT -> handleEnhancedVolume(delta)
                    }
                }

                override fun onScrollEnd() {
                    if (isLocked) return
                    if (seekChange != 0L) {
                        val finalPosition = (seekStartPosition + seekChange).coerceIn(0, player.duration)
                        player.seekTo(finalPosition)
                        seekChange = 0L
                    }
                    hideGestureOverlays()
                    brightnessOverlay.visibility = View.GONE
                    volumeOverlay.visibility = View.GONE
                }
            }
        )

        playerView.setOnTouchListener { _, event ->
            gestureControlManager.onTouchEvent(event)
        }
        if (startPosition > 0) {
            player.seekTo(startPosition)
        }

        scheduleHideControls()

        // Hide next episode button if no next episode
        val currentIndex = seasonEpisodeList.indexOf(currentEpisode)
        if (currentIndex == -1 || currentIndex == seasonEpisodeList.size - 1) {
            btnNextEpisode.visibility = View.GONE
        }
    }

    private fun initializeViews() {
        playerView = findViewById(R.id.player_view)
        topOverlay = findViewById(R.id.top_overlay)
        centerControls = findViewById(R.id.center_controls)
        bottomControls = findViewById(R.id.bottom_controls)
        lockOverlay = findViewById(R.id.lock_overlay)
        brightnessOverlay = findViewById(R.id.brightness_overlay)
        volumeOverlay = findViewById(R.id.volume_overlay)
        rewindIndicator = findViewById(R.id.rewind_indicator)
        forwardIndicator = findViewById(R.id.forward_indicator)
        seekOverlay = findViewById(R.id.seek_overlay)

        // *** ADD THIS MISSING LINE ***
        ivVolumeIcon = findViewById(R.id.iv_volume_icon)

        loadingIndicator = findViewById(R.id.loading_indicator)
        videoInfoOverlay = findViewById(R.id.video_info_overlay)
        abRepeatIndicators = findViewById(R.id.ab_repeat_indicators)

        btnBack = findViewById(R.id.btn_back)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        ivPlayPause = findViewById(R.id.iv_play_pause)
        btnLock = findViewById(R.id.btn_lock)
        btnUnlock = findViewById(R.id.btn_unlock)
        btnNextEpisode = findViewById(R.id.btn_next_episode)
        btnQuality = findViewById(R.id.btn_quality)
        btnResize = findViewById(R.id.btn_resize)
        btnSkipIntro = findViewById(R.id.btn_skip_intro)
        btnPlaybackSpeed = findViewById(R.id.btn_playback_speed)
        btnCloseVideoInfo = findViewById(R.id.btn_close_video_info)
        btnVideoInfo = findViewById(R.id.btn_video_info)
        btnAbRepeat = findViewById(R.id.btn_ab_repeat)
        btnAudioTrack = findViewById(R.id.btn_audio_track)
        btnPip = findViewById(R.id.btn_pip)
        btnBackgroundPlay = findViewById(R.id.btn_background_play)
        btnSubtitle = findViewById(R.id.btn_subtitle)

        tvEpisodeTitle = findViewById(R.id.tv_episode_title)
        tvSeekTime = findViewById(R.id.tv_seek_time)
        tvCurrentTime = findViewById(R.id.tv_current_time)
        tvTotalTime = findViewById(R.id.tv_total_time)
        seekBar = findViewById(R.id.seek_bar)
        loadingIndicator = findViewById(R.id.loading_indicator)
        speedIndicatorText = findViewById(R.id.speed_indicator_text)
        brightnessProgress = findViewById(R.id.brightness_progress)
        volumeProgress = findViewById(R.id.volume_progress)
        tvBrightnessValue = findViewById(R.id.tv_brightness_value)
        tvVolumeValue = findViewById(R.id.tv_volume_value)
        tvResolution = findViewById(R.id.tv_resolution)
        tvVideoInfoDetails = findViewById(R.id.tv_video_info_details)
        tvPointA = findViewById(R.id.tv_point_a)
        tvPointB = findViewById(R.id.tv_point_b)

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // Buttons
        btnPlayPause = findViewById(R.id.btn_play_pause)
        ivPlayPause = findViewById(R.id.iv_play_pause)
        btnRewind = findViewById(R.id.btn_rewind)
        btnFastForward = findViewById(R.id.btn_fast_forward)
        btnLock = findViewById(R.id.btn_lock)
        btnFullscreen = findViewById(R.id.btn_fullscreen)
        btnResize = findViewById(R.id.btn_resize)
        btnSubtitle = findViewById(R.id.btn_subtitle)
        btnNextEpisode = findViewById(R.id.btn_next_episode)
        btnQuality = findViewById(R.id.btn_quality)
        btnVideoInfo = findViewById(R.id.btn_video_info)
        btnAbRepeat = findViewById(R.id.btn_ab_repeat)
        btnPlaybackSpeed = findViewById(R.id.btn_playback_speed)
        btnAudioTrack = findViewById(R.id.btn_audio_track)
        btnPip = findViewById(R.id.btn_pip)
        btnBackgroundPlay = findViewById(R.id.btn_background_play)

        // Text views and other UI elements
        tvResolution = findViewById(R.id.tv_resolution)
        tvEpisodeTitle = findViewById(R.id.tv_episode_title)
        tvSeekTime = findViewById(R.id.tv_seek_time)
        tvCurrentTime = findViewById(R.id.tv_current_time)
        tvTotalTime = findViewById(R.id.tv_total_time)
        tvPointA = findViewById(R.id.tv_point_a)
        tvPointB = findViewById(R.id.tv_point_b)
        tvVideoInfoDetails = findViewById(R.id.tv_video_info_details)
        seekBar = findViewById(R.id.seek_bar)
        brightnessProgress = findViewById(R.id.brightness_progress)
        volumeProgress = findViewById(R.id.volume_progress)
        tvBrightnessValue = findViewById(R.id.tv_brightness_value)
        tvVolumeValue = findViewById(R.id.tv_volume_value)
        ivVolumeIcon = findViewById(R.id.iv_volume_icon)
        loadingIndicator = findViewById(R.id.loading_indicator)
        speedIndicatorText = findViewById(R.id.speed_indicator_text)
        lockOverlay = findViewById(R.id.lock_overlay)
        btnUnlock = findViewById(R.id.btn_unlock)
        rewindIndicator = findViewById(R.id.rewind_indicator)
        forwardIndicator = findViewById(R.id.forward_indicator)
        btnSkipIntro = findViewById(R.id.btn_skip_intro)
        btnCloseVideoInfo = findViewById(R.id.btn_close_video_info)
    }

    // In VideoPlayerActivityEnhanced.kt

    // In VideoPlayerActivityEnhanced.kt

// REMOVE the gestureDetector property. We will only use the onTouchEvent.
// private lateinit var gestureDetector: GestureDetectorCompat

    // It's better to manage this state directly
    private var isScrolling = false
    private var startX = 0f
    private var startY = 0f

    @SuppressLint("ClickableViewAccessibility")
    private fun setupEnhancedTouchListener() {
        val gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isLocked) toggleControlsWithAnimation()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked) return false
                if (e.x < playerView.width / 2) {
                    seekRelative(-10000)
                    showEnhancedSeekIndicator(rewindIndicator, -10)
                } else {
                    seekRelative(10000)
                    showEnhancedSeekIndicator(forwardIndicator, 10)
                }
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (isLocked) return
                startSpeedControl()
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (isLocked || e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (!isSeeking && (abs(dx) > 20 || abs(dy) > 20)) {
                    isSeeking = true
                    seekStartPosition = player.currentPosition
                    if (abs(dx) > abs(dy)) {
                        seekOverlay.visibility = View.VISIBLE
                        seekOverlay.bringToFront()
                        hideControlsWithAnimation()
                    }
                }
                if (isSeeking) {
                    if (abs(dx) > abs(dy)) {
                        handleEnhancedSeeking(dx)
                    } else {
                        if (e1.x < playerView.width / 2) handleEnhancedBrightness(-dy) else handleEnhancedVolume(-dy)
                    }
                }
                return true
            }
        })

        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                if (isSeeking) {
                    val finalPosition = (seekStartPosition + seekChange).coerceIn(0, player.duration)
                    player.seekTo(finalPosition)
                    hideHandler.postDelayed({ seekOverlay.visibility = View.GONE }, 500)
                }
                isSeeking = false
                if (isOnLongPressSpeedUp) stopSpeedControl()
                hideGestureOverlays()
            }
            true
        }
    }

    private fun handleEnhancedSeeking(dx: Float) {
        if (player.duration <= 0) return
        val maxSeek = 180_000L // Max 3 minutes seek per screen width
        seekChange = (dx / playerView.width * maxSeek).toLong()
        val newPosition = (seekStartPosition + seekChange).coerceIn(0, player.duration)
        updateSeekUI(newPosition, seekChange)
    }

    private fun updateSeekUI(newPosition: Long, seekChange: Long) {
        val changeSign = if (seekChange >= 0) "+" else "-"
        val changeSeconds = abs(seekChange / 1000)
        val changeMinutesPart = changeSeconds / 60
        val changeSecondsPart = changeSeconds % 60

        val formattedChange = String.format("%s%02d:%02d", changeSign, changeMinutesPart, changeSecondsPart)
        val formattedPosition = formatTime(newPosition)

        tvSeekTime.text = "$formattedChange [$formattedPosition]"
    }
    private fun handleEnhancedBrightness(dy: Float) {
        // ** THE FIX IS HERE **
        brightnessOverlay.visibility = View.VISIBLE
        volumeOverlay.visibility = View.GONE

        val delta = -dy / playerView.height // Invert delta for intuitive scrolling
        currentBrightness = (currentBrightness + delta).coerceIn(0.01f, 1.0f)
        window.attributes = window.attributes.apply { screenBrightness = currentBrightness }

        val percentage = (currentBrightness * 100).toInt()
        brightnessProgress.progress = percentage
        tvBrightnessValue.text = "$percentage%"
    }

    private fun handleEnhancedVolume(dy: Float) {
        // ** THE FIX IS HERE **
        volumeOverlay.visibility = View.VISIBLE
        brightnessOverlay.visibility = View.GONE

        val delta = -dy / playerView.height // Invert delta for intuitive scrolling
        val volumeChange = (delta * maxVolume).toInt()
        currentVolume = (currentVolume + volumeChange).coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)

        val percentage = if (maxVolume > 0) (currentVolume * 100) / maxVolume else 0
        volumeProgress.progress = percentage
        tvVolumeValue.text = "$percentage%"
        updateVolumeDisplay()
    }



    private fun toggleControlsWithAnimation() {
        if (isLocked) return
        if (isControlsVisible) {
            hideControlsWithAnimation()
        } else {
            showControlsWithAnimation()
        }
    }

    private fun showControlsWithAnimation() {
        isControlsVisible = true
        scheduleHideControls() // Restart the auto-hide timer

        // Animate visibility using alpha fade-in
        topOverlay.animate().alpha(1f).setDuration(CONTROL_FADE_DURATION).withStartAction { topOverlay.visibility = View.VISIBLE }.start()
        centerControls.animate().alpha(1f).setDuration(CONTROL_FADE_DURATION).withStartAction { centerControls.visibility = View.VISIBLE }.start()
        bottomControls.animate().alpha(1f).setDuration(CONTROL_FADE_DURATION).withStartAction { bottomControls.visibility = View.VISIBLE }.start()
    }

    private fun hideControlsWithAnimation() {
        isControlsVisible = false
        hideHandler.removeCallbacks(hideRunnable) // Stop the auto-hide timer

        // Animate visibility using alpha fade-out
        topOverlay.animate().alpha(0f).setDuration(CONTROL_FADE_DURATION).withEndAction { topOverlay.visibility = View.GONE }.start()
        centerControls.animate().alpha(0f).setDuration(CONTROL_FADE_DURATION).withEndAction { centerControls.visibility = View.GONE }.start()
        bottomControls.animate().alpha(0f).setDuration(CONTROL_FADE_DURATION).withEndAction { bottomControls.visibility = View.GONE }.start()
    }

    private fun seekRelative(milliseconds: Long) {
        if (!::player.isInitialized) return
        val newPosition = (player.currentPosition + milliseconds).coerceIn(0, player.duration)
        player.seekTo(newPosition)
    }

    private fun showEnhancedSeekIndicator(indicator: View, seconds: Int) {
        // Set the text if the indicator is one of our forward/rewind views
        if (indicator is LinearLayout) {
            val textView = indicator.getChildAt(1) as? TextView
            textView?.text = if (seconds > 0) "+$seconds seconds" else "$seconds seconds"
        }

        indicator.visibility = View.VISIBLE
        indicator.alpha = 1f // Reset alpha before starting

        // Animate fade out
        indicator.animate()
            .alpha(0f)
            .setDuration(SEEK_INDICATOR_DURATION)
            .setStartDelay(200) // Give user a moment to see it
            .withEndAction {
                indicator.visibility = View.GONE
                indicator.alpha = 1f // Reset for the next time
            }
            .start()
    }

    private fun startSpeedControl() {
        if (!::player.isInitialized) return
        isOnLongPressSpeedUp = true
        // Store the current speed to revert to, in case it was not 1.0x
        val previousSpeed = player.playbackParameters.speed
        player.setPlaybackParameters(PlaybackParameters(2f))

        speedIndicatorText.text = "Speed: 2.0x"
        speedIndicatorText.visibility = View.VISIBLE

        // Also provide haptic feedback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun stopSpeedControl() {
        if (!::player.isInitialized) return
        isOnLongPressSpeedUp = false
        // Revert to the normal speed (1.0x)
        player.setPlaybackParameters(PlaybackParameters(1f))
        speedIndicatorText.visibility = View.GONE
    }

    private fun hideGestureOverlays() {
        animateOverlayVisibility(brightnessOverlay, false)
        animateOverlayVisibility(volumeOverlay, false)
        animateOverlayVisibility(seekOverlay, false)
    }

    private fun animateOverlayVisibility(overlay: View, show: Boolean) {
        if (show) {
            overlay.visibility = View.VISIBLE
            overlay.animate().alpha(1f).setDuration(OVERLAY_ANIMATION_DURATION).start()
        } else {
            overlay.animate().alpha(0f).setDuration(OVERLAY_ANIMATION_DURATION).withEndAction {
                overlay.visibility = View.GONE
            }.start()
        }
    }

    // This is a new function to start the updates
    private fun startProgressUpdates() {
        // First, remove any old callbacks to prevent multiple loops
        progressUpdateHandler.removeCallbacks(progressUpdateRunnable)
        // Post the runnable to start the loop
        progressUpdateHandler.post(progressUpdateRunnable)
    }

    // This is a new function to stop the updates
    private fun stopProgressUpdates() {
        progressUpdateHandler.removeCallbacks(progressUpdateRunnable)
    }
    @OptIn(UnstableApi::class)
    private fun setupPlayer() {
        trackSelector = DefaultTrackSelector(this)
        player = ExoPlayer.Builder(this).setTrackSelector(trackSelector).build()
        playerView.player = player
        playerView.useController = false

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e("VideoPlayerEnhanced", "ExoPlayer Error: ", error)
                Toast.makeText(this@VideoPlayerActivityEnhanced, "Playback Error: ${error.message}", Toast.LENGTH_LONG).show()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                loadingIndicator.visibility = if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (playbackState == Player.STATE_READY) {
                    updatePlayPauseButton()
                    updateDuration()
                    fetchSkipTimes()
                    updateVideoInfo()
                } else if (playbackState == Player.STATE_ENDED) {
                    if (isAbRepeatActive && pointA != -1L && pointB != -1L) {
                        player.seekTo(pointA)
                        player.play()
                    } else {
                        playNextEpisode()
                    }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseButton()
                updateMediaSessionPlaybackState()

                // ** THE FIX IS HERE **
                if (isPlaying) {
                    startProgressUpdates()
                } else {
                    stopProgressUpdates()
                }
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                checkAbRepeat()
            }
        })

        currentPlaybackSpeed = 1.0f
        player.setPlaybackSpeed(currentPlaybackSpeed)
        btnPlaybackSpeed.text = "${currentPlaybackSpeed}x"

        val isOffline = intent.getBooleanExtra(EXTRA_IS_OFFLINE, false)
        val mediaSource: MediaSource
        if (isOffline) {
            val mediaItem = MediaItem.fromUri(currentEpisode!!.url!!)
            val dataSourceFactory = DownloadUtil.getReadOnlyCacheDataSourceFactory(this)
            mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            if (videoList.isEmpty()) { finish(); return }
            val masterPlaylistContent = createMasterPlaylist(videoList)
            val playlistUri = "data:application/x-mpegURL;base64," + android.util.Base64.encodeToString(masterPlaylistContent.toByteArray(), android.util.Base64.NO_WRAP)
            val mediaItem = MediaItem.fromUri(playlistUri)
            val dataSourceFactory = DefaultDataSource.Factory(this)
            mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }
        player.setMediaSource(mediaSource)
        player.prepare()
        player.play()
        updateProgress()
    }


    private fun setupGestureDetector() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isLocked) {
                    toggleControls()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isLocked) {
                    togglePlayPause()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!isLocked && !isOnLongPressSpeedUp) {
                    isOnLongPressSpeedUp = true
                    player.setPlaybackSpeed(2.0f)
                    speedIndicatorText.text = "Speed: 2.0x (Long Press)"
                    speedIndicatorText.visibility = View.VISIBLE
                    hideHandler.removeCallbacks(hideSpeedIndicator)
                }
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (isLocked) return false

                val screenWidth = playerView.width
                val screenHeight = playerView.height

                if (abs(distanceX) > abs(distanceY)) {
                    // Horizontal scroll - seeking
                    val seekAmount = (distanceX / screenWidth) * 60000 // 60 seconds max
                    seekChange += seekAmount.toLong()
                    val newPosition = (player.currentPosition - seekChange).coerceIn(0, player.duration)

                    tvSeekTime.text = formatTime(newPosition) + " [${if (seekChange > 0) "+" else ""}${formatTime(abs(seekChange))}]"
                    tvSeekTime.visibility = View.VISIBLE

                    hideHandler.removeCallbacks(hideSeekTime)
                    hideHandler.postDelayed(hideSeekTime, 1000)
                } else {
                    // Vertical scroll
                    if (e2.x < screenWidth / 2) {
                        // Left side - brightness
                        adjustBrightness(-distanceY / screenHeight)
                    } else {
                        // Right side - volume
                        adjustVolume(-distanceY / screenHeight)
                    }
                }
                return true
            }
        })

        playerView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                if (isOnLongPressSpeedUp) {
                    isOnLongPressSpeedUp = false
                    player.setPlaybackSpeed(currentPlaybackSpeed)
                    hideHandler.postDelayed(hideSpeedIndicator, 1000)
                }

                if (seekChange != 0L) {
                    val newPosition = (player.currentPosition - seekChange).coerceIn(0, player.duration)
                    player.seekTo(newPosition)
                    seekChange = 0
                    hideHandler.postDelayed(hideSeekTime, 500)
                }
            }
            gestureDetector.onTouchEvent(event)
        }
    }

    private fun setupClickListeners() {
        btnBack.setOnClickListener { finish() }

        btnPlayPause.setOnClickListener { togglePlayPause() }

        btnRewind.setOnClickListener {
            seekBy(-10000)
            showSeekFeedback(false)
        }

        btnFastForward.setOnClickListener {
            seekBy(10000)
            showSeekFeedback(true)
        }

        btnLock.setOnClickListener { toggleLock() }

        btnUnlock.setOnClickListener { toggleLock() }

        btnFullscreen.setOnClickListener { toggleFullscreen() }

        btnResize.setOnClickListener { cycleResizeMode() }

        btnSubtitle.setOnClickListener { showSubtitleDialog() }

        btnNextEpisode.setOnClickListener { playNextEpisode() }

        btnQuality.setOnClickListener { showQualityDialog() }

        btnVideoInfo.setOnClickListener { toggleVideoInfo() }

        btnAbRepeat.setOnClickListener { handleAbRepeat() }

        btnPlaybackSpeed.setOnClickListener { showPlaybackSpeedDialog() }

        btnAudioTrack.setOnClickListener { showAudioTrackDialog() }

        btnPip.setOnClickListener { enterPictureInPictureMode() }

        btnBackgroundPlay.setOnClickListener { toggleBackgroundPlay() }

        btnSkipIntro.setOnClickListener { skipIntro() }

        btnCloseVideoInfo.setOnClickListener { videoInfoOverlay.visibility = View.GONE }
    }

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val position = (progress * player.duration) / 100
                    tvCurrentTime.text = formatTime(position)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                hideHandler.removeCallbacks(hideRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val position = (seekBar!!.progress * player.duration) / 100
                player.seekTo(position)
                scheduleHideControls()
            }
        })
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "VideoPlayer")
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                player.play()
            }

            override fun onPause() {
                player.pause()
            }

            override fun onSeekTo(pos: Long) {
                player.seekTo(pos)
            }

            override fun onSkipToNext() {
                playNextEpisode()
            }
        })

        mediaSession.isActive = true
        updateMediaSessionMetadata()
    }

    private fun updateMediaSessionMetadata() {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentEpisode?.name ?: "Unknown")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentAnime?.title ?: "Unknown")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player.duration)
            .build()

        mediaSession.setMetadata(metadata)
    }

    private fun updateMediaSessionPlaybackState() {
        val state = if (player.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, player.currentPosition, player.playbackParameters.speed)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            )
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun showPlaybackSpeedDialog() {
        val speedOptions = playbackSpeeds.map { "${it}x" }.toTypedArray()
        val currentIndex = playbackSpeeds.indexOf(currentPlaybackSpeed)

        AlertDialog.Builder(this)
            .setTitle("Playback Speed")
            .setSingleChoiceItems(speedOptions, currentIndex) { dialog, which ->
                currentPlaybackSpeed = playbackSpeeds[which]
                player.setPlaybackSpeed(currentPlaybackSpeed)
                btnPlaybackSpeed.text = "${currentPlaybackSpeed}x"
                dialog.dismiss()
            }
            .show()
    }

    @OptIn(UnstableApi::class)
    private fun showAudioTrackDialog() {
        val trackGroups = trackSelector.currentMappedTrackInfo?.getTrackGroups(C.TRACK_TYPE_AUDIO)
        if (trackGroups == null || trackGroups.length == 0) {
            Toast.makeText(this, "No audio tracks available", Toast.LENGTH_SHORT).show()
            return
        }

        val trackNames = mutableListOf<String>()
        for (i in 0 until trackGroups.length) {
            val format = trackGroups[i].getFormat(0)
            trackNames.add(format.language ?: "Track ${i + 1}")
        }

        AlertDialog.Builder(this)
            .setTitle("Audio Track")
            .setItems(trackNames.toTypedArray()) { _, which ->
                val parametersBuilder = trackSelector.buildUponParameters()
                parametersBuilder.setRendererDisabled(C.TRACK_TYPE_AUDIO, false)
                // Set specific audio track selection logic here
                trackSelector.setParameters(parametersBuilder)
            }
            .show()
    }

    private fun showSubtitleDialog() {
        val options = arrayOf("None", "Load External Subtitle", "Subtitle Settings")

        AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> disableSubtitles()
                    1 -> loadExternalSubtitle()
                    2 -> showSubtitleSettings()
                }
            }
            .show()
    }

    private fun showSubtitleSettings() {
        // Implement subtitle customization (font size, color, position)
        val sizes = arrayOf("Small", "Medium", "Large", "Extra Large")

        AlertDialog.Builder(this)
            .setTitle("Subtitle Size")
            .setItems(sizes) { _, which ->
                // Apply subtitle size changes
                Toast.makeText(this, "Subtitle size changed to ${sizes[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun loadExternalSubtitle() {
        // Implement external subtitle loading
        Toast.makeText(this, "External subtitle loading not implemented yet", Toast.LENGTH_SHORT).show()
    }

    @OptIn(UnstableApi::class)
    private fun disableSubtitles() {
        val parametersBuilder = trackSelector.buildUponParameters()
        parametersBuilder.setRendererDisabled(C.TRACK_TYPE_TEXT, true)
        trackSelector.setParameters(parametersBuilder)
    }

    private fun handleAbRepeat() {
        val currentPosition = player.currentPosition

        when {
            pointA == -1L -> {
                // Set point A
                pointA = currentPosition
                tvPointA.text = "A: ${formatTime(pointA)}"
                abRepeatIndicators.visibility = View.VISIBLE
                Toast.makeText(this, "Point A set", Toast.LENGTH_SHORT).show()
            }
            pointB == -1L -> {
                // Set point B
                if (currentPosition > pointA) {
                    pointB = currentPosition
                    tvPointB.text = "B: ${formatTime(pointB)}"
                    isAbRepeatActive = true
                    Toast.makeText(this, "A-B Repeat enabled", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Point B must be after Point A", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {
                // Clear A-B repeat
                pointA = -1L
                pointB = -1L
                isAbRepeatActive = false
                abRepeatIndicators.visibility = View.GONE
                Toast.makeText(this, "A-B Repeat disabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkAbRepeat() {
        if (isAbRepeatActive && pointA != -1L && pointB != -1L) {
            val currentPosition = player.currentPosition
            if (currentPosition >= pointB) {
                player.seekTo(pointA)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun enterPictureInPictureMode() {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        } else {
            Toast.makeText(this, "Picture-in-Picture not supported", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleBackgroundPlay() {
        isBackgroundPlayEnabled = !isBackgroundPlayEnabled
        if (isBackgroundPlayEnabled) {
            btnBackgroundPlay.setColorFilter(resources.getColor(android.R.color.holo_blue_light))
            Toast.makeText(this, "Background play enabled", Toast.LENGTH_SHORT).show()
        } else {
            btnBackgroundPlay.clearColorFilter()
            Toast.makeText(this, "Background play disabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleVideoInfo() {
        videoInfoOverlay.visibility = if (videoInfoOverlay.visibility == View.VISIBLE) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    @OptIn(UnstableApi::class)
    private fun updateVideoInfo() {
        val format = player.videoFormat
        if (format != null) {
            val info = buildString {
                append("Resolution: ${format.width}x${format.height}\n")
                append("Codec: ${format.codecs}\n")
                append("Bitrate: ${format.bitrate / 1000} kbps\n")
                append("Frame Rate: ${format.frameRate} fps\n")

                val audioFormat = player.audioFormat
                if (audioFormat != null) {
                    append("Audio: ${audioFormat.codecs} ${audioFormat.bitrate / 1000} kbps")
                }
            }
            tvVideoInfoDetails.text = info
            tvResolution.text = "${format.height}p - ${format.width}×${format.height}"
        }
    }

    // Helper methods
    private fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    private fun updatePlayPauseButton() {
        ivPlayPause.setImageResource(
            if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow_large
        )
    }

    private fun seekBy(milliseconds: Long) {
        val newPosition = (player.currentPosition + milliseconds).coerceIn(0, player.duration)
        player.seekTo(newPosition)
    }

    private fun showSeekFeedback(isForward: Boolean) {
        val indicator = if (isForward) forwardIndicator else rewindIndicator
        indicator.visibility = View.VISIBLE
        hideHandler.removeCallbacks(hideSeekIndicator)
        hideHandler.postDelayed(hideSeekIndicator, 1000)
    }

    private fun toggleControls() {
        if (isControlsVisible) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun showControls() {
        if (!isLocked) {
            topOverlay.visibility = View.VISIBLE
            centerControls.visibility = View.VISIBLE
            bottomControls.visibility = View.VISIBLE
            isControlsVisible = true
            scheduleHideControls()
        }
    }

    private fun hideControls() {
        topOverlay.visibility = View.GONE
        centerControls.visibility = View.GONE
        bottomControls.visibility = View.GONE
        isControlsVisible = false
        hideHandler.removeCallbacks(hideRunnable)
    }

    private fun scheduleHideControls() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 3000)
    }

    private fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) {
            hideControls()
            lockOverlay.visibility = View.VISIBLE
            btnLock.setImageResource(R.drawable.ic_lock)
        } else {
            lockOverlay.visibility = View.GONE
            showControls()
            btnLock.setImageResource(R.drawable.ic_lock_open)
        }
    }

    private fun formatTime(timeMs: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(timeMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(timeMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(timeMs) % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    // Runnables for hiding UI elements
    private val hideSeekTime = Runnable { tvSeekTime.visibility = View.GONE }
    private val hideSpeedIndicator = Runnable { speedIndicatorText.visibility = View.GONE }
    private val hideSeekIndicator = Runnable {
        rewindIndicator.visibility = View.GONE
        forwardIndicator.visibility = View.GONE
    }

    // Implement remaining methods from original class...
    private fun setupAudioManager() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        updateVolumeDisplay()
    }

    private fun adjustVolume(delta: Float) {
        val volumeChange = (delta * maxVolume).toInt()
        currentVolume = (currentVolume + volumeChange).coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
        updateVolumeDisplay()
        showVolumeOverlay()
    }

    private fun adjustBrightness(delta: Float) {
        currentBrightness = (currentBrightness + delta).coerceIn(0.01f, 1.0f)
        val layoutParams = window.attributes
        layoutParams.screenBrightness = currentBrightness
        window.attributes = layoutParams
        updateBrightnessDisplay()
        showBrightnessOverlay()
    }

    private fun updateVolumeDisplay() {
        val percentage = (currentVolume * 100) / maxVolume
        volumeProgress.progress = percentage
        tvVolumeValue.text = "$percentage%"

        ivVolumeIcon.setImageResource(
            when {
                currentVolume == 0 -> R.drawable.ic_volume_off
                currentVolume < maxVolume / 3 -> R.drawable.ic_volume_down
                else -> R.drawable.ic_volume_up
            }
        )
    }

    private fun updateBrightnessDisplay() {
        val percentage = (currentBrightness * 100).toInt()
        brightnessProgress.progress = percentage
        tvBrightnessValue.text = "$percentage%"
    }

    private fun showVolumeOverlay() {
        volumeOverlay.visibility = View.VISIBLE
        hideHandler.removeCallbacks(hideVolumeOverlay)
        hideHandler.postDelayed(hideVolumeOverlay, 2000)
    }

    private fun showBrightnessOverlay() {
        brightnessOverlay.visibility = View.VISIBLE
        hideHandler.removeCallbacks(hideBrightnessOverlay)
        hideHandler.postDelayed(hideBrightnessOverlay, 2000)
    }

    private val hideVolumeOverlay = Runnable { volumeOverlay.visibility = View.GONE }
    private val hideBrightnessOverlay = Runnable { brightnessOverlay.visibility = View.GONE }

    // Additional methods from original implementation...
    private fun updateProgress() {
        if (player.duration > 0) {
            val progress = ((player.currentPosition * 100) / player.duration).toInt()
            seekBar.progress = progress
            tvCurrentTime.text = formatTime(player.currentPosition)
        }

        if (player.isPlaying) {
            hideHandler.postDelayed({ updateProgress() }, 1000)
        }
    }

    private fun updateDuration() {
        tvTotalTime.text = formatTime(player.duration)
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, playerView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // Lifecycle methods
    override fun onPause() {
        super.onPause()
        if (!isBackgroundPlayEnabled) {
            player.pause()
        }
        saveWatchProgress()
    }

    override fun onResume() {
        super.onResume()
        if (isBackgroundPlayEnabled) {
            player.play()
        }
    }

    // Also, make sure to stop it when the activity is destroyed
    override fun onDestroy() {
        super.onDestroy()
        stopProgressUpdates() // <-- ADD THIS
        if (::player.isInitialized) {
            player.release()
        }
        if (::mediaSession.isInitialized) {
            mediaSession.release()
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


    // Placeholder methods - implement based on original code
    // In VideoPlayerActivityEnhanced.kt

    private fun createMasterPlaylist(videos: List<Video>): String {
        val playlistBuilder = StringBuilder()
        playlistBuilder.append("#EXTM3U\n")

        val sortedVideos = videos.sortedByDescending {
            it.quality.filter { char -> char.isDigit() }.toIntOrNull() ?: 0
        }

        for (video in sortedVideos) {
            val resolution: String
            val bandwidth: Int
            when {
                "1080p" in video.quality -> {
                    resolution = "1920x1080"
                    bandwidth = 5000000
                }
                "720p" in video.quality -> {
                    resolution = "1280x720"
                    bandwidth = 2800000
                }
                "480p" in video.quality -> {
                    resolution = "854x480"
                    bandwidth = 1400000
                }
                "360p" in video.quality -> {
                    resolution = "640x360"
                    bandwidth = 800000
                }
                else -> {
                    resolution = ""
                    bandwidth = 500000
                }
            }

            if (resolution.isNotEmpty()) {
                playlistBuilder.append("#EXT-X-STREAM-INF:BANDWIDTH=$bandwidth,RESOLUTION=$resolution\n")
            } else {
                playlistBuilder.append("#EXT-X-STREAM-INF:BANDWIDTH=$bandwidth\n")
            }
            playlistBuilder.append("${video.url}\n")
        }

        return playlistBuilder.toString()
    }

    private fun getEnhancedVideoInfo(quality: String): Triple<String, Int, String> {
        // This function returns the Resolution, Bandwidth, and Codec info for a given quality string.
        return when {
            "2160p" in quality || "4K" in quality -> Triple("3840x2160", 15000000, "avc1.640033,mp4a.40.2")
            "1440p" in quality -> Triple("2560x1440", 9000000, "avc1.640032,mp4a.40.2")
            "1080p" in quality -> Triple("1920x1080", 5000000, "avc1.640028,mp4a.40.2")
            "720p" in quality -> Triple("1280x720", 2800000, "avc1.64001f,mp4a.40.2")
            "480p" in quality -> Triple("854x480", 1400000, "avc1.64001e,mp4a.40.2")
            "360p" in quality -> Triple("640x360", 800000, "avc1.64001e,mp4a.40.2")
            "240p" in quality -> Triple("426x240", 400000, "avc1.64000d,mp4a.40.2")
            else -> Triple("", 500000, "avc1.64001e,mp4a.40.2") // Fallback for unknown qualities
        }
    }
    // In VideoPlayerActivityEnhanced.kt

    private fun saveWatchProgress() {
        if (!::player.isInitialized) return // Safety check
        val anime = currentAnime ?: return
        val episode = currentEpisode ?: return
        val position = player.currentPosition
        val duration = player.duration
        if (duration <= 0 || episode.url.isNullOrEmpty()) return

        val progressPercentage = (position * 100) / duration
        val isEpisodeFinished = progressPercentage > 90

        val watchHistory = WatchHistory(
            episodeUrl = episode.url!!,
            animeUrl = anime.url!!,
            animeTitle = anime.title ?: "Unknown Title",
            animeThumbnailUrl = anime.thumbnail_url,
            episodeName = episode.name ?: "Unknown Episode",
            lastWatchedPosition = position,
            duration = duration,
            timestamp = System.currentTimeMillis(),
            isFinished = isEpisodeFinished,
            episodeNumber = episode.episode_number.toInt(), // <-- SAVE EPISODE NUMBER
            seasonEpisodes = seasonEpisodeList // <-- SAVE THE SEASON LIST
        )

        CoroutineScope(Dispatchers.IO).launch {
            db.watchHistoryDao().upsert(watchHistory)
        }
    }

    private fun fetchSkipTimes() {
        if (!::player.isInitialized) return
        val anime = currentAnime ?: return
        val episode = currentEpisode ?: return
        val duration = player.duration
        if (duration <= 0) return

        lifecycleScope.launch {
            skipStamps = EpisodeSkip.getStamps(anime, episode.episode_number.toInt(), duration)
        }
    }

    private fun skipIntro() {
        currentSkipStamp?.let {
            player.seekTo(it.endMs)
            btnSkipIntro.visibility = View.GONE
            currentSkipStamp = null
        }
    }

    private fun playNextEpisode() {
        val currentIndex = seasonEpisodeList.indexOf(currentEpisode)
        if (currentIndex != -1 && currentIndex < seasonEpisodeList.size - 1) {
            val nextEpisode = seasonEpisodeList[currentIndex + 1]
            val intent = AnimeDetailsActivity.newIntentWithResume(
                context = this,
                anime = currentAnime!!,
                resumeEpisodeUrl = nextEpisode.url!!
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
        } else {
            // This was the last episode, so just finish the activity.
            finish()
        }
    }

    @OptIn(UnstableApi::class)
    private fun cycleResizeMode() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        playerView.resizeMode = currentResizeMode
        Toast.makeText(this, "Resize mode changed", Toast.LENGTH_SHORT).show()
    }

    private fun toggleFullscreen() {
        requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
}

