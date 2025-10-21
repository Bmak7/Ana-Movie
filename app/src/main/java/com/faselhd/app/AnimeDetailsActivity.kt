package com.faselhd.app

import DetailsFragmentAdapter
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.unit.dp
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.faselhd.app.db.AppDatabase
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.SourceManager
import com.faselhd.app.workers.DownloadWorker
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.widget.ArrayAdapter
import com.google.gson.Gson
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.view.isVisible
import com.faselhd.app.adapters.*
import com.faselhd.app.utils.PlayerDataHolder
import java.io.IOException


class AnimeDetailsActivity : AppCompatActivity() {

    // --- Views ---
    private lateinit var animeImage: ImageView
    private lateinit var animeTitle: TextView
    private lateinit var animeDescription: TextView
    private lateinit var tagsChipGroup: ChipGroup
    private lateinit var episodesRecyclerView: RecyclerView
    private lateinit var seasonSpinner: Spinner
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnDownload: MaterialButton
    private lateinit var btnBookmark: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var composeProgress: ComposeView
    private lateinit var episodesProgressBar: ProgressBar
    private lateinit var rootLayout: View
    private lateinit var btnToggleEpisodesLayout: ImageButton
    private lateinit var episodesListContainer: FrameLayout

    private var resumeEpisodeUrl: String? = null
    private var allEpisodes: List<SEpisode> = emptyList()

    // --- Adapters ---

    private lateinit var fragmentAdapter: DetailsFragmentAdapter

    // --- Utilities ---
    private val sourceManager by lazy { SourceManager(applicationContext) }
    private val db by lazy { AppDatabase.getDatabase(this) }
    private var currentAnime: SAnime? = null
    private var specificSource: AnimeSource? = null
    private var episodesBySeason: Map<String, List<EpisodeWithHistory>> = emptyMap()
    private var isFavorite = false
    private var isLoading = false
    private var isRunningOnTV = false


    // --- State ---
    private var isEpisodeLayoutHorizontal = true // Default to horizontal

    // --- Audio Player Views ---
    private lateinit var audioPlayerLayout: LinearLayout
    private lateinit var btnPlayAudio: ImageButton
    private lateinit var btnDownloadAudio: ImageButton
    private lateinit var audioSeekBar: SeekBar
    private lateinit var audioTitle: TextView
    private lateinit var audioProgress: TextView
    private lateinit var audioDuration: TextView

    // --- Audio Player State ---
    private var mediaPlayer: MediaPlayer? = null
    private var isAudioPlaying = false
    private var audioUrl: String? = null
    private var audioUpdateHandler = android.os.Handler()
    private var audioUpdateRunnable: Runnable? = null

    // --- Search Views ---
    private lateinit var searchView: SearchView
    private lateinit var searchContainer: LinearLayout
    private lateinit var btnToggleSearch: ImageButton

    // --- Adapters ---
    private lateinit var horizontalEpisodeAdapter: EpisodeDetailsAdapter
    private lateinit var verticalEpisodeAdapter: EpisodeAdapter

    // --- State Tracking ---
    private var isSearchVisible = false
    private var originalEpisodesList: List<EpisodeWithHistory> = emptyList()

    // --- TV Navigation State ---
    private var currentEpisodePosition = 0

    // --- PERMISSION HANDLING ---
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    // Store download requests temporarily while waiting for permission result
    private var pendingSingleDownload: Pair<SEpisode, Video>? = null
    private var pendingBulkDownload: Pair<List<SEpisode>, String>? = null


    companion object {
        private const val EXTRA_ANIME = "extra_anime"
        private const val EXTRA_SOURCE = "extra_source"
        private const val EXTRA_RESUME_EPISODE_URL = "extra_resume_episode_url"

        fun newIntent(context: Context, anime: SAnime, source: AnimeSource?): Intent {
            return Intent(context, AnimeDetailsActivity::class.java).apply {
                putExtra(EXTRA_ANIME, anime)
                putExtra(EXTRA_SOURCE, source)
            }
        }

        fun newIntentWithResume(context: Context, anime: SAnime, resumeEpisodeUrl: String, source: AnimeSource?): Intent {
            return Intent(context, AnimeDetailsActivity::class.java).apply {
                putExtra(EXTRA_ANIME, anime)
                putExtra(EXTRA_SOURCE, source)
                putExtra(EXTRA_RESUME_EPISODE_URL, resumeEpisodeUrl)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- INITIALIZE PERMISSION LAUNCHER ---
        // This must be done before onCreate finishes
        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                showSnackbar("Notifications disabled. You won't see download progress.", true)
            }
            // Proceed with whichever download was pending
            pendingSingleDownload?.let { (episode, video) ->
                queueDownloadForSingleVideo(episode, video)
            }
            pendingBulkDownload?.let { (episodes, quality) ->
                queueEpisodesForDownload(episodes, quality)
            }
            // Clear pending requests
            pendingSingleDownload = null
            pendingBulkDownload = null
        }

        setContentView(R.layout.activity_anime_details)

        if (!extractIntentData()) {
            finish()
            return
        }

        initViews()
        setupToolbar()
        setupAdapters()
        setupRecyclerView()
        setupTabsAndViewPager()
        setupListeners()
        loadAnimeData()
        checkIfFavorite()
        setupAudioPlayer()

        if (isRunningOnTV) {
            setupTVFocusListeners()
        }
    }

    private fun checkNotificationPermission(onPermissionGranted: () -> Unit) {
        // Permissions are only required on Android 13 (TIRAMISU) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                // Permission already granted
                onPermissionGranted()
            } else {
                // Permission not granted, launch the request
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            // No runtime permission needed for older Android versions
            onPermissionGranted()
        }
    }


    private fun initiateSingleEpisodeDownload(episode: SEpisode) {
        lifecycleScope.launch {
            try {
                showSnackbar("Fetching sources for ${episode.name}...", false)
                val videos = sourceManager.fetchVideoList(episode.url!!, specificSource)
                if (videos.isNotEmpty()) {
                    showQualitySelectionDialog(episode, videos)
                } else {
                    showSnackbar("No download sources found for this episode.", true)
                }
            } catch (e: Exception) {
                Log.e("AnimeDetailsActivity", "Failed to fetch video list for download", e)
                showSnackbar("Error fetching download links.", true)
            }
        }
    }

    private fun showQualitySelectionDialog(episode: SEpisode, videos: List<Video>) {
        val qualityOptions = videos.map { it.quality }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Quality for ${episode.name}")
            .setItems(qualityOptions) { dialog, which ->
                val selectedVideo = videos[which]
                // Store the download request
                pendingSingleDownload = Pair(episode, selectedVideo)
                // Now, check for permission. The download will be queued from the launcher's result.
                checkNotificationPermission {
                    // This block is called if permission is already granted
                    pendingSingleDownload?.let { (e, v) ->
                        queueDownloadForSingleVideo(e, v)
                        pendingSingleDownload = null // Clear after use
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .create()
            .show()
    }

    // REPLACE the old showDownloadBottomSheet function with this new one
    private fun showDownloadBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_download, null)
        dialog.setContentView(view)

        val qualitySpinner: Spinner = view.findViewById(R.id.quality_spinner)
        val episodesRecyclerView: RecyclerView = view.findViewById(R.id.episodes_download_recycler_view)
        val btnCancel: MaterialButton = view.findViewById(R.id.btn_cancel)
        val btnConfirmDownload: MaterialButton = view.findViewById(R.id.btn_confirm_download)
        val titleTextView: TextView = view.findViewById(R.id.bottom_sheet_title) // Assuming you have a title TextView

        // --- Step 1: Episode Selection ---
        titleTextView.text = "Select Episodes"
        qualitySpinner.visibility = View.GONE // Hide quality spinner initially
        val selectionAdapter = EpisodeSelectionAdapter()
        episodesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@AnimeDetailsActivity) // Vertical is better for multi-select
            adapter = selectionAdapter
        }
        selectionAdapter.submitList(allEpisodes.map { SelectableEpisode(it) })

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirmDownload.text = "Next"
        btnConfirmDownload.setOnClickListener {
            val selectedEpisodes = selectionAdapter.getSelectedEpisodes()
            if (selectedEpisodes.isEmpty()) {
                showSnackbar("Please select at least one episode.", true)
            } else {
                // --- Step 2: Source Selection ---
                showSourceSelection(dialog, view, selectedEpisodes)
            }
        }

        dialog.show()
    }

    private fun showSourceSelection(dialog: BottomSheetDialog, view: View, episodes: List<SEpisode>) {
        // Update UI for step 2
        val qualitySpinner: Spinner = view.findViewById(R.id.quality_spinner)
        val episodesRecyclerView: RecyclerView = view.findViewById(R.id.episodes_download_recycler_view)
        val btnConfirmDownload: MaterialButton = view.findViewById(R.id.btn_confirm_download)
        val titleTextView: TextView = view.findViewById(R.id.bottom_sheet_title)
        val loadingProgressBar: ProgressBar = view.findViewById(R.id.bottom_sheet_progress) // Add a ProgressBar to your layout

        titleTextView.text = "Select Source for Each Episode"
        qualitySpinner.visibility = View.GONE
        loadingProgressBar.visibility = View.VISIBLE
        episodesRecyclerView.visibility = View.INVISIBLE
        btnConfirmDownload.isEnabled = false

        val downloadSelections = mutableListOf<EpisodeDownloadSelection>()
        val adapter = EpisodeSourceSelectionAdapter(this, downloadSelections)
        episodesRecyclerView.adapter = adapter
        episodesRecyclerView.layoutManager = LinearLayoutManager(this)


        // Fetch sources for all selected episodes
        lifecycleScope.launch {
            episodes.forEach { episode ->
                try {
                    val sources = sourceManager.fetchVideoList(episode.url!!, specificSource)
                    if (sources.isNotEmpty()) {
                        downloadSelections.add(EpisodeDownloadSelection(episode, sources))
                    }
                } catch (e: Exception) {
                    Log.e("SourceFetch", "Failed to get sources for ${episode.name}", e)
                }
            }

            // Update UI after fetching is complete
            loadingProgressBar.visibility = View.GONE
            if (downloadSelections.isEmpty()) {
                titleTextView.text = "Could not find any download sources."
                btnConfirmDownload.text = "Close"
                btnConfirmDownload.isEnabled = true
                btnConfirmDownload.setOnClickListener { dialog.dismiss() }
            } else {
                episodesRecyclerView.visibility = View.VISIBLE
                btnConfirmDownload.isEnabled = true
                adapter.notifyDataSetChanged()

                btnConfirmDownload.text = "Download"
                btnConfirmDownload.setOnClickListener {
                    val finalSelections = (episodesRecyclerView.adapter as EpisodeSourceSelectionAdapter).getFinalSelections()

                    // Use the existing permission check flow
                    // For simplicity, we create a temporary list to pass to the permission handler
                    val bulkRequest = finalSelections.map { Pair(it.first, it.second) }

                    // This is a simplified approach. A more robust solution might involve a dedicated variable.
                    checkNotificationPermission {
                        bulkRequest.forEach { (ep, video) ->
                            queueDownloadForSingleVideo(ep, video)
                        }
                    }
                    dialog.dismiss()
                }
            }
        }
    }


    // --- Other Methods (No significant changes needed below this line, provided for context) ---

    private fun setupTVFocusListeners() {
        val focusables = listOf(btnPlay, btnDownload, btnBookmark, btnShare, seasonSpinner, btnToggleSearch, btnToggleEpisodesLayout, episodesRecyclerView)
        focusables.forEach {
            it.setOnFocusChangeListener { view, hasFocus ->
                val scale = if (hasFocus) 1.1f else 1.0f
                view.animate().scaleX(scale).scaleY(scale).setDuration(200).start()
            }
        }

        for (i in 0 until tabLayout.tabCount) {
            tabLayout.getTabAt(i)?.view?.setOnFocusChangeListener { view, hasFocus ->
                val scale = if (hasFocus) 1.1f else 1.0f
                view.animate().scaleX(scale).scaleY(scale).setDuration(200).start()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isRunningOnTV) return super.onKeyDown(keyCode, event)

        if (handleDpadNavigation(keyCode)) {
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun handleDpadNavigation(keyCode: Int): Boolean {
        val currentFocus = currentFocus ?: return false

        // Handle navigation within RecyclerView first
        if (currentFocus == episodesRecyclerView && episodesRecyclerView.adapter is EpisodeDetailsAdapter) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return navigateHorizontalRecyclerView(keyCode)
            }
        }

        return false // Let the system handle focus change for other views
    }

    private fun navigateHorizontalRecyclerView(keyCode: Int): Boolean {
        val adapter = episodesRecyclerView.adapter ?: return false
        val newPosition = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            (currentEpisodePosition + 1).coerceAtMost(adapter.itemCount - 1)
        } else {
            (currentEpisodePosition - 1).coerceAtLeast(0)
        }

        if (newPosition != currentEpisodePosition) {
            currentEpisodePosition = newPosition
            episodesRecyclerView.smoothScrollToPosition(currentEpisodePosition)
            highlightRecyclerViewItem(episodesRecyclerView, currentEpisodePosition)
            return true
        }
        return false
    }

    @SuppressLint("WrongConstant")
    private fun highlightRecyclerViewItem(recyclerView: RecyclerView, position: Int) {
        Handler(Looper.getMainLooper()).postDelayed({
            removeHighlightFromRecyclerView(recyclerView) // Clear previous highlights
            val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
            viewHolder?.itemView?.let { itemView ->
                val focusIndicator = itemView.findViewById<View>(R.id.focus_indicator)
                focusIndicator?.visibility = View.VISIBLE
                focusIndicator?.animate()?.alpha(1.0f)?.setDuration(150)?.start()
            }
        }, 50) // Delay to ensure view is bound
    }

    @SuppressLint("WrongConstant")
    private fun removeHighlightFromRecyclerView(recyclerView: RecyclerView) {
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val focusIndicator = child.findViewById<View>(R.id.focus_indicator)
            focusIndicator?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction {
                focusIndicator.visibility = View.GONE
            }?.start()
        }
    }

    private fun handleEpisodeDownloadClick(episode: SEpisode) {
        lifecycleScope.launch {
            try {
                val videos = sourceManager.fetchVideoList(episode.url!!, specificSource)
                if (videos.isEmpty()) {
                    showSnackbar("No video links found for this episode", isError = true)
                    return@launch
                }
                // For simplicity on TV, let's just pick the first available quality
                val videoToDownload = videos.first()
                queueEpisodesForDownload(listOf(episode), videoToDownload.quality)
            } catch (e: Exception) {
                showSnackbar("Failed to get download links: ${e.message}", isError = true)
            } finally {
                // Find the item in adapter and reset its download indicator state
                val position = verticalEpisodeAdapter.currentList.indexOfFirst { it.episode.url == episode.url }
                if (position != -1) {
                    verticalEpisodeAdapter.currentList[position].isFetchingDownload = false
                    val viewHolder = episodesRecyclerView.findViewHolderForAdapterPosition(position) as? EpisodeAdapter.ViewHolder
                    viewHolder?.setDownloadingState(false)
                }
            }
        }
    }
    private fun setupAudioPlayer() {
        btnPlayAudio.setOnClickListener {
            toggleAudioPlayback()
        }

        btnDownloadAudio.setOnClickListener {
            downloadAudio()
        }

        audioSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && mediaPlayer != null) {
                    mediaPlayer?.seekTo(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }



    @SuppressLint("WrongConstant")
    private fun setupAudioPlayer(url: String, title: String) {
        audioUrl = url
        audioTitle.text = title
        audioPlayerLayout.visibility = View.VISIBLE

        // Initialize media player
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(url)
                setOnPreparedListener {
                    audioSeekBar.max = it.duration
                    audioDuration.text = formatDuration(it.duration)
                    btnPlayAudio.isEnabled = true
                }
                setOnCompletionListener {
                    stopAudioPlayback()
                }
                prepareAsync() // Prepare asynchronously
            } catch (e: IOException) {
                Log.e("AudioPlayer", "Failed to set data source", e)
                showSnackbar("Cannot play audio theme.", true)
                hideAudioPlayer()
            }
        }

        btnPlayAudio.isEnabled = false
    }

    @SuppressLint("WrongConstant")
    private fun hideAudioPlayer() {
        audioPlayerLayout.visibility = View.GONE
        releaseMediaPlayer()
    }

    private fun toggleAudioPlayback() {
        if (isAudioPlaying) {
            pauseAudio()
        } else {
            playAudio()
        }
    }

    private fun playAudio() {
        mediaPlayer?.let { mp ->
            if (!mp.isPlaying) {
                mp.start()
                isAudioPlaying = true
                btnPlayAudio.setImageResource(R.drawable.ic_pause)
                startAudioProgressUpdate()
            }
        }
    }

    private fun pauseAudio() {
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                mp.pause()
                isAudioPlaying = false
                btnPlayAudio.setImageResource(R.drawable.ic_play_arrow)
                stopAudioProgressUpdate()
            }
        }
    }

    private fun stopAudioPlayback() {
        mediaPlayer?.let { mp ->
            mp.seekTo(0)
            isAudioPlaying = false
            btnPlayAudio.setImageResource(R.drawable.ic_play_arrow)
            audioSeekBar.progress = 0
            audioProgress.text = "0:00"
            stopAudioProgressUpdate()
        }
    }

    private fun startAudioProgressUpdate() {
        audioUpdateRunnable = object : Runnable {
            override fun run() {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        val currentPosition = mp.currentPosition
                        audioSeekBar.progress = currentPosition
                        audioProgress.text = formatDuration(currentPosition)
                        audioUpdateHandler.postDelayed(this, 1000)
                    }
                }
            }
        }
        audioUpdateHandler.post(audioUpdateRunnable!!)
    }

    private fun stopAudioProgressUpdate() {
        audioUpdateRunnable?.let {
            audioUpdateHandler.removeCallbacks(it)
        }
    }

    private fun formatDuration(milliseconds: Int): String {
        val seconds = (milliseconds / 1000) % 60
        val minutes = (milliseconds / (1000 * 60)) % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    private fun downloadAudio() {
        audioUrl?.let { url ->
            val workData = workDataOf(
                DownloadWorker.KEY_EPISODE_URL to url,
                DownloadWorker.KEY_VIDEO_URL to url,
                DownloadWorker.KEY_EPISODE_NAME to audioTitle.text.toString(),
                DownloadWorker.KEY_ANIME_TITLE to currentAnime?.title,
                DownloadWorker.KEY_THUMBNAIL_URL to currentAnime?.thumbnail_url,
                DownloadWorker.KEY_IS_AUDIO to true
            )

            val downloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workData)
                .addTag(url)
                .build()

            WorkManager.getInstance(this).enqueue(downloadWorkRequest)

            showSnackbar("Audio download queued", false)
        }
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                mp.stop()
            }
            mp.release()
        }
        mediaPlayer = null
        stopAudioProgressUpdate()
    }

    override fun onPause() {
        super.onPause()
        pauseAudio()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaPlayer()
        audioUpdateHandler.removeCallbacksAndMessages(null)
    }

    private fun extractIntentData(): Boolean {
        currentAnime = intent.getParcelableExtra(EXTRA_ANIME)
        specificSource = (intent.getSerializableExtra(EXTRA_SOURCE) as? AnimeSource)
        resumeEpisodeUrl = intent.getStringExtra(EXTRA_RESUME_EPISODE_URL)

        if (currentAnime == null) {
            Toast.makeText(this, "Error: No anime data provided", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun initViews() {
        // --- Standard Views ---
        rootLayout = findViewById(android.R.id.content)
        animeImage = findViewById(R.id.anime_image)
        animeTitle = findViewById(R.id.anime_title)
        tagsChipGroup = findViewById(R.id.anime_tags_chip_group)
        btnPlay = findViewById(R.id.btn_play)
        btnDownload = findViewById(R.id.btn_download)
        animeDescription = findViewById(R.id.anime_description)
        episodesRecyclerView = findViewById(R.id.episodes_recycler_view)
        seasonSpinner = findViewById(R.id.season_spinner)
        btnBookmark = findViewById(R.id.btn_bookmark)
        btnShare = findViewById(R.id.btn_share)
        tabLayout = findViewById(R.id.tab_layout)
        viewPager = findViewById(R.id.view_pager)
        composeProgress = findViewById(R.id.compose_progress)
        episodesProgressBar = findViewById(R.id.episodes_progress_bar)
        btnToggleEpisodesLayout = findViewById(R.id.btn_toggle_episodes_layout)
        episodesListContainer = findViewById(R.id.episodes_list_container)

        // --- Search Views ---
        searchView = findViewById(R.id.search_view)
        searchContainer = findViewById(R.id.search_container)
        btnToggleSearch = findViewById(R.id.btn_toggle_search)

        // --- Audio Player Views (MERGED HERE) ---
        audioPlayerLayout = findViewById(R.id.audio_player_layout)
        btnPlayAudio = findViewById(R.id.btn_play_audio)
        btnDownloadAudio = findViewById(R.id.btn_download_audio)
        audioSeekBar = findViewById(R.id.audio_seek_bar)
        audioTitle = findViewById(R.id.audio_title)
        audioProgress = findViewById(R.id.audio_progress)
        audioDuration = findViewById(R.id.audio_duration)
    }

    private fun setupToolbar() {
        val toolbar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(false)
        }
        toolbar.setNavigationOnClickListener { onBackPressed() }
    }

    private fun setupAdapters() {
        horizontalEpisodeAdapter = EpisodeDetailsAdapter { episode ->
            if (!isLoading) playEpisode(episode)
        }

        verticalEpisodeAdapter = EpisodeAdapter(
            onClick = { episode -> if (!isLoading) playEpisode(episode) },
            onDownloadClick = { episode ->
                initiateSingleEpisodeDownload(episode)
            }
        )
    }

    private fun setupRecyclerView() {
        updateEpisodesLayout() // Set initial layout
    }


    private fun setupTabsAndViewPager() {
        fragmentAdapter = DetailsFragmentAdapter(this)
        viewPager.adapter = fragmentAdapter
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "More Like This"
                1 -> "Comments"
                else -> null
            }
        }.attach()
    }

    private fun setupListeners() {
        btnPlay.setOnClickListener {
            if (!isLoading) {
                handleMainPlayButtonClick()
            }
        }

        btnBookmark.setOnClickListener {
            if (!isLoading) {
                handleBookmarkClick()
            }
        }

        btnDownload.setOnClickListener {
            if (!isLoading) {
                handleDownloadClick()
            }
        }

        btnShare.setOnClickListener {
            shareAnime()
        }

        btnToggleEpisodesLayout.setOnClickListener {
            isEpisodeLayoutHorizontal = !isEpisodeLayoutHorizontal
            updateEpisodesLayout()
        }

        // Add search toggle functionality
        btnToggleSearch.setOnClickListener {
            toggleSearchVisibility()
        }

        // Setup search functionality
        setupSearchView()
    }

    private fun setupSearchView() {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                filterEpisodes(newText ?: "")
                return true
            }
        })

        searchView.setOnCloseListener {
            if (isSearchVisible) {
                toggleSearchVisibility()
            }
            false
        }
    }

    private fun toggleSearchVisibility() {
        isSearchVisible = !isSearchVisible

        if (isSearchVisible) {
            searchContainer.isVisible = true
            searchView.requestFocus()
            searchView.isIconified = false
            btnToggleSearch.setImageResource(R.drawable.ic_close)
            val currentSeason = getCurrentSelectedSeason()
            originalEpisodesList = episodesBySeason[currentSeason] ?: emptyList()
        } else {
            searchContainer.isVisible = false
            searchView.setQuery("", false)
            searchView.clearFocus()
            searchView.isIconified = true
            btnToggleSearch.setImageResource(R.drawable.ic_search)
            if (isEpisodeLayoutHorizontal) {
                horizontalEpisodeAdapter.submitList(originalEpisodesList)
            } else {
                verticalEpisodeAdapter.submitList(originalEpisodesList)
            }
        }
    }

    private fun filterEpisodes(query: String) {
        if (!isSearchVisible || originalEpisodesList.isEmpty()) return

        val filteredList = if (query.isBlank()) {
            originalEpisodesList
        } else {
            originalEpisodesList.filter { episodeWithHistory ->
                val episode = episodeWithHistory.episode
                val nameMatch = episode.name?.contains(query, ignoreCase = true) == true
                val numberMatch = (episode.episode_number.toInt()?.toString() == query) ||
                        ("Episode ${episode.episode_number.toInt()}".contains(query, ignoreCase = true))

                nameMatch || numberMatch
            }
        }

        if (isEpisodeLayoutHorizontal) {
            horizontalEpisodeAdapter.submitList(filteredList)
        } else {
            verticalEpisodeAdapter.submitList(filteredList)
        }

        if (filteredList.isEmpty() && query.isNotBlank()) {
            showSnackbar("No episodes found for '$query'", false)
        }
    }

    private fun getCurrentSelectedSeason(): String {
        val seasonNames = episodesBySeason.keys.toList().sorted()
        val selectedPosition = seasonSpinner.selectedItemPosition
        return if (selectedPosition >= 0 && selectedPosition < seasonNames.size) {
            seasonNames[selectedPosition]
        } else {
            seasonNames.firstOrNull() ?: "Season 1"
        }
    }

    private fun handleMainPlayButtonClick() {
        if (allEpisodes.isEmpty()) {
            showSnackbar("Episodes are still loading, please wait...", isError = false)
            return
        }

        lifecycleScope.launch {
            try {
                val recentHistory = db.watchHistoryDao().getRecentWatchHistoryForAnime(currentAnime!!.url!!)

                val episodeToPlay = when {
                    recentHistory != null && recentHistory.lastWatchedPosition > 0 -> {
                        val episodeToResume = allEpisodes.find { it.url == recentHistory.episodeUrl }
                        if (episodeToResume != null) {
                            showSnackbar("Resuming: ${episodeToResume.name}", isError = false)
                            episodeToResume
                        } else {
                            allEpisodes.firstOrNull()
                        }
                    }
                    else -> allEpisodes.firstOrNull()
                }

                episodeToPlay?.let { episode ->
                    if (recentHistory == null || recentHistory.lastWatchedPosition == 0L) {
                        showSnackbar("Playing: ${episode.name}", isError = false)
                    }
                    playEpisode(episode)
                } ?: run {
                    showSnackbar("No episodes available to play", isError = true)
                }
            } catch (e: Exception) {
                Log.e("AnimeDetails", "Error finding episode to play", e)
                showSnackbar("Error loading episode: ${e.localizedMessage}", isError = true)
            }
        }
    }

    private fun handleDownloadClick() {
        if (allEpisodes.isEmpty()) {
            showSnackbar("Episodes are still loading, please wait...", isError = false)
        } else {
            showDownloadBottomSheet()
        }
    }

    private fun shareAnime() {
        currentAnime?.let { anime ->
            val shareText = "Check out this anime: ${anime.title}\n${anime.url}"
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, shareText)
                type = "text/plain"
            }
            startActivity(Intent.createChooser(shareIntent, "Share ${anime.title}"))
        }
    }

    private fun queueDownloadForSingleVideo(episode: SEpisode, video: Video) {
        // Make sure currentAnime is not null before proceeding
        val anime = currentAnime ?: return // Or handle the error appropriately

        val inputData = workDataOf(
            DownloadWorker.KEY_EPISODE_URL to episode.url,
            DownloadWorker.KEY_VIDEO_URL to video.url,
            DownloadWorker.KEY_EPISODE_NAME to episode.name,
            // CORRECTED: Pass the title string from the anime object
            DownloadWorker.KEY_ANIME_TITLE to anime.title,
            // CORRECTED: Pass the thumbnail URL string from the anime object
            DownloadWorker.KEY_THUMBNAIL_URL to anime.thumbnail_url,
            DownloadWorker.KEY_HEADERS_JSON to if (video.headers != null) {
                Gson().toJson(video.headers)
            } else null
        )

        val downloadRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .addTag("download_${episode.url}")
            .build()

        WorkManager.getInstance(this).enqueue(downloadRequest)
        showSnackbar("Download queued: ${episode.name}", false)
    }


    private fun queueEpisodesForDownload(episodes: List<SEpisode>, quality: String) {
        lifecycleScope.launch {
            var successCount = 0
            var failCount = 0

            for (episode in episodes) {
                try {

                    val videos = sourceManager.fetchVideoList(episode.url!!, specificSource)
                    // Find the best match for the selected quality
                    val selectedVideo = videos.find { it.quality.contains(quality, ignoreCase = true) }
                        ?: videos.firstOrNull() // Fallback to the first available

                    if (selectedVideo == null) {
                        Log.w("Download", "No video found for episode: ${episode.name}")
                        failCount++
                        continue
                    }

                    val headersJson = selectedVideo.headers?.let { headers ->
                        Gson().toJson(headers)
                    }

                    val workData = workDataOf(
                        DownloadWorker.KEY_EPISODE_URL to episode.url!!,
                        DownloadWorker.KEY_VIDEO_URL to selectedVideo.url,
                        DownloadWorker.KEY_EPISODE_NAME to episode.name,
                        DownloadWorker.KEY_ANIME_TITLE to currentAnime?.title,
                        DownloadWorker.KEY_THUMBNAIL_URL to currentAnime?.thumbnail_url,
                        DownloadWorker.KEY_HEADERS_JSON to headersJson
                    )

                    val downloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                        .setInputData(workData)
                        .addTag(episode.url!!)
                        .build()

                    WorkManager.getInstance(this@AnimeDetailsActivity).enqueue(downloadWorkRequest)

                    val downloadEntry = Download(
                        episodeUrl = episode.url!!,
                        animeTitle = currentAnime?.title ?: "",
                        episodeName = episode.name,
                        thumbnailUrl = currentAnime?.thumbnail_url,
                        downloadState = DownloadState.QUEUED,
                        mediaUri = selectedVideo.url
                    )
                    db.downloadDao().upsert(downloadEntry)
                    successCount++

                } catch (e: Exception) {
                    Log.e("Download", "Failed to queue episode ${episode.name}", e)
                    failCount++
                }
            }

            val message = when {
                failCount == 0 -> "Successfully queued $successCount episodes"
                successCount == 0 -> "Failed to queue any episodes"
                else -> "Queued $successCount episodes, $failCount failed"
            }
            showSnackbar(message, failCount > 0)
        }
    }

    private fun loadAnimeData() {
        if (isLoading) return

        setLoadingState(true)

        lifecycleScope.launch {
            var success = false
            try {
                Log.d("AnimeDetails", "Loading anime: ${currentAnime!!.url}")
                Log.d("AnimeDetails", "specificSource: ${specificSource}")
                val detailedAnime = sourceManager.fetchAnimeDetails(currentAnime!!.url!!, specificSource)
                currentAnime = detailedAnime
                populateUiDetails(detailedAnime)

                val episodes = sourceManager.fetchEpisodeList(currentAnime!!.url!!, specificSource)
                allEpisodes = episodes
                processAndDisplayEpisodes(episodes)

                success = true
                showSnackbar("Loaded ${episodes.size} episodes", isError = false)

            } catch (e: Exception) {
                Log.e("AnimeDetails", "Error loading anime data", e)
                showSnackbar("Failed to load anime details: ${e.localizedMessage}", isError = true)
            } finally {
                setLoadingState(false)

                if (success && resumeEpisodeUrl != null) {
                    handleResumeEpisode()
                }
            }
        }
    }

    private fun handleResumeEpisode() {
        val urlToPlay = resumeEpisodeUrl!!
        val episodeToPlay = allEpisodes.find { it.url == urlToPlay }

        if (episodeToPlay != null) {
            showSnackbar("Resuming: ${episodeToPlay.name}", isError = false)
            playEpisode(episodeToPlay)
        } else {
            showSnackbar("Could not find the episode to resume", isError = true)
        }
        resumeEpisodeUrl = null
    }

    @SuppressLint("WrongConstant")
    private fun setLoadingState(loading: Boolean) {
        isLoading = loading

        btnPlay.isEnabled = !loading
        btnDownload.isEnabled = !loading
        btnBookmark.isEnabled = !loading

        if (loading) {
            episodesProgressBar.visibility = View.VISIBLE
            episodesRecyclerView.visibility = View.INVISIBLE
            showLoading(true)
        } else {
            episodesProgressBar.visibility = View.GONE
            episodesRecyclerView.visibility = View.VISIBLE
            showLoading(false)
        }
    }

    private fun playEpisode(episode: SEpisode) {
        episode.url?.let { episodeUrl ->
            setLoadingState(true)

            lifecycleScope.launch {
                try {
                    val seasonName = episode.name?.substringBefore(":")?.trim() ?: "Season 1"
                    val episodesWithHistoryForSeason = episodesBySeason[seasonName] ?: emptyList()
                    val episodeListForPlayer = episodesWithHistoryForSeason.map { it.episode }

                    val videos = sourceManager.fetchVideoList(episodeUrl, specificSource)
                    println("videos: ${videos.toString()}")
                    val history = db.watchHistoryDao().getWatchHistoryByEpisodeUrl(episodeUrl)

                    setLoadingState(false)

                    if (videos.isNotEmpty()) {
                        PlayerDataHolder.videos = videos
                        PlayerDataHolder.anime = currentAnime
                        PlayerDataHolder.episodeList = episodeListForPlayer

                        val intent = VideoPlayerActivity.newIntent(
                            context = this@AnimeDetailsActivity,
                            currentEpisodeUrl = episode.url!!,
                            startPosition = history?.lastWatchedPosition ?: 0L,
                            source = specificSource
                        )
                        startActivity(intent)

                    } else {
                        showSnackbar("No video links found for this episode", isError = true)
                    }
                } catch (e: Exception) {
                    setLoadingState(false)
                    Log.e("AnimeDetails", "Error loading video", e)
                    showSnackbar("Error loading video: ${e.localizedMessage}", isError = true)
                }
            }
        }
    }

    private fun populateUiDetails(anime: SAnime) {
        animeTitle.text = anime.title

        val genreText = if (!anime.genre.isNullOrEmpty()) "Genre: ${anime.genre}\n\n" else ""
        animeDescription.text = genreText + (anime.description ?: "No description available")

        Glide.with(this)
            .load(anime.thumbnail_url)
            .placeholder(R.drawable.placeholder_anime)
            .error(R.drawable.error_page)
            .into(animeImage)

        setupAnimeTags(anime)

        if (specificSource == AnimeSource.ARABICTOONS && !anime.genre.isNullOrEmpty() &&
            (anime.genre!!.startsWith("http") || anime.genre!!.startsWith("https"))) {
            setupAudioPlayer(anime.genre!!, anime.title ?: "Opening Theme")
        } else {
            hideAudioPlayer()
        }
    }

    private fun setupAnimeTags(anime: SAnime) {
        tagsChipGroup.removeAllViews()
        addChipToGroup("13+")
        addChipToGroup("Subtitle")
        anime.genre?.split(",")?.take(3)?.forEach { genre ->
            addChipToGroup(genre.trim())
        }
    }

    private fun addChipToGroup(text: String) {
        val chip = Chip(this).apply {
            this.text = text
            setChipBackgroundColorResource(android.R.color.transparent)
            setChipStrokeColorResource(R.color.green_see_all)
            chipStrokeWidth = 3f
            setTextColor(resources.getColor(R.color.green_see_all, null))
            isClickable = false
        }
        tagsChipGroup.addView(chip)
    }

    private suspend fun processAndDisplayEpisodes(episodes: List<SEpisode>) {
        val historyMap = db.watchHistoryDao().getAllWatchHistory().first()
            .associateBy { it.episodeUrl }

        val episodesWithHistory = episodes.map { episode ->
            EpisodeWithHistory(
                episode = episode,
                history = historyMap[episode.url]
            )
        }

        episodesBySeason = episodesWithHistory.groupBy {
            it.episode.name?.substringBefore(":")?.trim() ?: "Season 1"
        }

        setupSeasonSpinner()
    }

    private fun setupSeasonSpinner() {
        val seasonNames = episodesBySeason.keys.toList().sorted()
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, seasonNames)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        seasonSpinner.adapter = spinnerAdapter

        seasonSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < seasonNames.size) {
                    val selectedSeason = seasonNames[position]
                    val episodes = episodesBySeason[selectedSeason] ?: emptyList()
                    originalEpisodesList = episodes

                    if (isEpisodeLayoutHorizontal) {
                        horizontalEpisodeAdapter.submitList(episodes)
                    } else {
                        verticalEpisodeAdapter.submitList(episodes)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        if (seasonNames.isNotEmpty()) {
            val initialEpisodes = episodesBySeason[seasonNames.first()] ?: emptyList()
            originalEpisodesList = initialEpisodes
            if (isEpisodeLayoutHorizontal) {
                horizontalEpisodeAdapter.submitList(initialEpisodes)
            } else {
                verticalEpisodeAdapter.submitList(initialEpisodes)
            }
        }
    }

    private fun updateEpisodesLayout() {
        val listToSubmit = if (isEpisodeLayoutHorizontal) {
            verticalEpisodeAdapter.currentList
        } else {
            horizontalEpisodeAdapter.currentList
        }.ifEmpty {
            originalEpisodesList
        }

        currentEpisodePosition = 0

        if (isEpisodeLayoutHorizontal) {
            episodesRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            episodesRecyclerView.adapter = horizontalEpisodeAdapter
            horizontalEpisodeAdapter.submitList(listToSubmit)

            btnToggleEpisodesLayout.setImageResource(R.drawable.ic_view_list)

            val layoutParams = episodesListContainer.layoutParams
            layoutParams.height = (140 * resources.displayMetrics.density).toInt()
            episodesListContainer.layoutParams = layoutParams

        } else {
            episodesRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
            episodesRecyclerView.adapter = verticalEpisodeAdapter
            verticalEpisodeAdapter.submitList(listToSubmit)

            episodesRecyclerView.isNestedScrollingEnabled = false

            btnToggleEpisodesLayout.setImageResource(R.drawable.ic_view_module)

            val layoutParams = episodesListContainer.layoutParams
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            episodesListContainer.layoutParams = layoutParams
        }
    }

    private fun checkIfFavorite() {
        lifecycleScope.launch {
            try {
                val favorite = db.favoriteDao().getFavoriteByUrl(currentAnime!!.url!!)
                isFavorite = favorite != null
                updateBookmarkButtonUI()
            } catch (e: Exception) {
                Log.e("AnimeDetails", "Error checking favorite status", e)
            }
        }
    }

    private fun handleBookmarkClick() {
        lifecycleScope.launch {
            try {
                isFavorite = !isFavorite

                if (isFavorite) {
                    val favorite = Favorite(
                        animeUrl = currentAnime!!.url!!,
                        title = currentAnime!!.title,
                        thumbnailUrl = currentAnime!!.thumbnail_url,
                        source = (specificSource ?: SourceManager.getSelectedSource(applicationContext)).name
                    )
                    db.favoriteDao().insert(favorite)
                    showSnackbar("Added to favorites", isError = false)
                } else {
                    db.favoriteDao().delete(currentAnime!!.url!!)
                    showSnackbar("Removed from favorites", isError = false)
                }
                updateBookmarkButtonUI()
            } catch (e: Exception) {
                Log.e("AnimeDetails", "Error updating favorite", e)
                isFavorite = !isFavorite // Revert state
                showSnackbar("Error updating favorites", isError = true)
                updateBookmarkButtonUI()
            }
        }
    }

    private fun updateBookmarkButtonUI() {
        val iconRes = if (isFavorite) R.drawable.bookmark_check_24px else R.drawable.bookmark_24px
        btnBookmark.setImageResource(iconRes)

        val tintColor = if (isFavorite) {
            getColor(R.color.green_see_all)
        } else {
            getColor(android.R.color.darker_gray)
        }
        btnBookmark.setColorFilter(tintColor)
    }

    private fun showSnackbar(message: String, isError: Boolean) {
        val snackbar = Snackbar.make(rootLayout, message,
            if (isError) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT)

        if (isError) {
            snackbar.setBackgroundTint(getColor(android.R.color.holo_red_dark))
        }

        snackbar.show()
    }

    private fun showLoading(show: Boolean) {
        composeProgress.visibility = if (show) View.VISIBLE else View.GONE

        if (show) {
            composeProgress.setContent {
                MaterialTheme {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(100.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(64.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 4.dp
                        )
                    }
                }
            }
        }
    }

    override fun onBackPressed() {
        if (isSearchVisible) {
            toggleSearchVisibility()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        checkIfFavorite()
        if (allEpisodes.isNotEmpty()) {
            lifecycleScope.launch {
                processAndDisplayEpisodes(allEpisodes)
            }
        }

        if (isSearchVisible) {
            searchView.setQuery("", false)
        }
    }
}