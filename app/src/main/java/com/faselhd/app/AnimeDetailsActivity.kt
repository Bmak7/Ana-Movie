package com.faselhd.app

import DetailsFragmentAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.unit.dp
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.faselhd.app.adapters.EpisodeAdapter
import com.faselhd.app.adapters.EpisodeDetailsAdapter
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
import com.faselhd.app.adapters.EpisodeSelectionAdapter
import com.faselhd.app.adapters.SelectableEpisode
import com.google.gson.Gson
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.view.isVisible
import com.faselhd.app.utils.PlayerDataHolder
import java.io.IOException


class AnimeDetailsActivity : AppCompatActivity() {

    // --- Views ---
    private lateinit var animeImage: ImageView
    private lateinit var animeTitle: TextView
    private lateinit var animeDescription: TextView
    //    private lateinit var animeRating: TextView
//    private lateinit var animeYear: TextView
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

    // Add these new views to your existing view declarations
    private lateinit var searchView: SearchView
    private lateinit var searchContainer: LinearLayout
    private lateinit var btnToggleSearch: ImageButton

    // --- Adapters ---
    private lateinit var horizontalEpisodeAdapter: EpisodeDetailsAdapter
    private lateinit var verticalEpisodeAdapter: EpisodeAdapter


    // Add this to track search state
    private var isSearchVisible = false
    private var originalEpisodesList: List<EpisodeWithHistory> = emptyList()

    // --- TV Navigation State ---
    private var currentEpisodePosition = 0





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
        setContentView(R.layout.activity_anime_details)

        if (!extractIntentData()) {
            finish()
            return
        }

        // Single call to initialize ALL views
        initViews()

        setupToolbar()
        setupAdapters() // Changed this from setupRecyclerView
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
//    private fun initAudioPlayerViews() {
//        audioPlayerLayout = findViewById(R.id.audio_player_layout)
//        btnPlayAudio = findViewById(R.id.btn_play_audio)
//        btnDownloadAudio = findViewById(R.id.btn_download_audio)
//        audioSeekBar = findViewById(R.id.audio_seek_bar)
//        audioTitle = findViewById(R.id.audio_title)
//        audioProgress = findViewById(R.id.audio_progress)
//        audioDuration = findViewById(R.id.audio_duration)
//    }

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
                // You can add download logic here or in the listener setup
//                showSnackbar("Download clicked for ${it.name}", false)
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

        // Handle search view close
        searchView.setOnCloseListener {
            if (isSearchVisible) {
                toggleSearchVisibility()
            }
            false
        }

        // Handle search view expansion/collapse
        searchView.setOnSearchClickListener {
            // Search view is being opened
        }
    }

    private fun toggleSearchVisibility() {
        isSearchVisible = !isSearchVisible

        if (isSearchVisible) {
            // Show search
            searchContainer.isVisible = true
            searchView.requestFocus()
            searchView.isIconified = false // Expands the search view
            btnToggleSearch.setImageResource(R.drawable.ic_close)

            // Store the current list as the original list for filtering
            val currentSeason = getCurrentSelectedSeason()
            originalEpisodesList = episodesBySeason[currentSeason] ?: emptyList()
        } else {
            // Hide search
            searchContainer.isVisible = false
            searchView.setQuery("", false) // Clear the search query
            searchView.clearFocus()
            searchView.isIconified = true // Collapse the search view
            btnToggleSearch.setImageResource(R.drawable.ic_search)

            // Restore the original, unfiltered list to the currently active adapter
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
                val numberMatch = (episode.episode_number.toInt().toString() == query) ||
                        ("Episode ${episode.episode_number.toInt()}".contains(query, ignoreCase = true))

                nameMatch || numberMatch
            }
        }

        // Submit the filtered list to the currently active adapter
        if (isEpisodeLayoutHorizontal) {
            horizontalEpisodeAdapter.submitList(filteredList)
        } else {
            verticalEpisodeAdapter.submitList(filteredList)
        }

        // Show a message if the filtered list is empty
        if (filteredList.isEmpty() && query.isNotBlank()) {
            showSnackbar("No episodes found for '$query'", false)
        }
    }

    private fun extractEpisodeNumber(episodeName: String): Int? {
        return try {
            // Try to extract number from patterns like "Episode 1", "Ep 1", "01", etc.
            val regex = Regex("""(?:Episode|Ep|E)?[\s]*(\d+)""", RegexOption.IGNORE_CASE)
            val matchResult = regex.find(episodeName)
            matchResult?.groupValues?.get(1)?.toIntOrNull()
        } catch (e: Exception) {
            null
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
                        // Resume from last watched episode
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

    private fun showDownloadBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_download, null)
        dialog.setContentView(view)

        val qualitySpinner: Spinner = view.findViewById(R.id.quality_spinner)
        val episodesRecyclerView: RecyclerView = view.findViewById(R.id.episodes_download_recycler_view)
        val btnCancel: MaterialButton = view.findViewById(R.id.btn_cancel)
        val btnConfirmDownload: MaterialButton = view.findViewById(R.id.btn_confirm_download)
//        val btnSelectAll: MaterialButton? = view.findViewById(R.id.btn_select_all)

        val selectionAdapter = EpisodeSelectionAdapter()
        episodesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@AnimeDetailsActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = selectionAdapter
        }
        selectionAdapter.submitList(allEpisodes.map { SelectableEpisode(it) })

        val qualities = arrayOf("1080","720p", "480p", "360p")
        qualitySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, qualities)

//        // Select All / Deselect All functionality
//        btnSelectAll?.setOnClickListener {
//            val allSelected = selectionAdapter.areAllSelected()
//            if (allSelected) {
//                selectionAdapter.deselectAll()
//                btnSelectAll.text = "Select All"
//            } else {
//                selectionAdapter.selectAll()
//                btnSelectAll.text = "Deselect All"
//            }
//        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirmDownload.setOnClickListener {
            val selectedEpisodes = selectionAdapter.getSelectedEpisodes()
            if (selectedEpisodes.isEmpty()) {
                showSnackbar("Please select at least one episode to download", isError = true)
            } else {
                val selectedQuality = qualitySpinner.selectedItem.toString()
                queueEpisodesForDownload(selectedEpisodes, selectedQuality)
                dialog.dismiss()
                showSnackbar("Queued ${selectedEpisodes.size} episodes for download", isError = false)
            }
        }

        dialog.show()
    }

    private fun queueEpisodesForDownload(episodes: List<SEpisode>, quality: String) {
        lifecycleScope.launch {
            var successCount = 0
            var failCount = 0

            for (episode in episodes) {
                try {

                    val videos = sourceManager.fetchVideoList(episode.url!!, specificSource)
                    val selectedVideo = videos.find { it.quality.contains(quality, ignoreCase = true) }
                        ?: videos.firstOrNull()

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
                println()
                showSnackbar("Failed to load anime details: ${e.localizedMessage}", isError = true)
            } finally {
                setLoadingState(false)

                // Handle resume episode if provided
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

        // Update button states
        btnPlay.isEnabled = !loading
        btnDownload.isEnabled = !loading
        btnBookmark.isEnabled = !loading

        // Show/hide progress indicators
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
                        // =======================================================
                        // ++ SOLUTION IMPLEMENTED HERE ++
                        // =======================================================

                        // 1. Populate the singleton holder with the large data
                        PlayerDataHolder.videos = videos
                        PlayerDataHolder.anime = currentAnime
                        PlayerDataHolder.episodeList = episodeListForPlayer

                        // 2. Create the Intent using the new, lightweight method
                        val intent = VideoPlayerActivity.newIntent(
                            context = this@AnimeDetailsActivity,
                            currentEpisodeUrl = episode.url!!, // Pass only the unique URL
                            startPosition = history?.lastWatchedPosition ?: 0L,
                            source = specificSource
                        )

                        // 3. Start the activity. The player will retrieve data from the holder.
                        startActivity(intent)

                        // =======================================================
                        // -- END OF FIX --
                        // =======================================================

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

        // Better description formatting
        val genreText = if (!anime.genre.isNullOrEmpty()) "Genre: ${anime.genre}\n\n" else ""
        animeDescription.text = genreText + (anime.description ?: "No description available")

//        animeRating.text = anime.rating ?: "N/A"
//        animeYear.text = ">  ${anime.releaseYear ?: "Unknown"}"

        // Load image with error handling
        Glide.with(this)
            .load(anime.thumbnail_url)
            .placeholder(R.drawable.placeholder_anime)
            .error(R.drawable.error_page)
            .into(animeImage)

        // Setup tags
        setupAnimeTags(anime)
        println("specificSource : $specificSource")
        // Check if this is ARABICTOONS source and has audio URL in genre
        if (specificSource == AnimeSource.ARABICTOONS && !anime.genre.isNullOrEmpty() &&
            anime.genre!!.startsWith("http")) {
            println("starting setupAudioPlayer")
            setupAudioPlayer(anime.genre!!, anime.title ?: "Opening Theme")
        } else {
            hideAudioPlayer()
        }
    }

    private fun setupAnimeTags(anime: SAnime) {
        tagsChipGroup.removeAllViews()

        // Add rating chip
        addChipToGroup("13+")

        // Add country chip if available
//        anime.country?.let { addChipToGroup(it) } ?: addChipToGroup("Japan")

        // Add subtitle chip
        addChipToGroup("Subtitle")

        // Add genre chips if available
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

                    // Submit list to the currently active adapter
                    if (isEpisodeLayoutHorizontal) {
                        horizontalEpisodeAdapter.submitList(episodes)
                    } else {
                        verticalEpisodeAdapter.submitList(episodes)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Set initial list after the listener is set
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
        // 1. Determine the list of episodes to preserve from the PREVIOUS adapter.
        //    Since `isEpisodeLayoutHorizontal` has already been flipped for the new state,
        //    we check the opposite to find the old adapter.
        val listToSubmit = if (isEpisodeLayoutHorizontal) {
            // We are switching TO horizontal, so the OLD adapter was the vertical one.
            verticalEpisodeAdapter.currentList
        } else {
            // We are switching TO vertical, so the OLD adapter was the horizontal one.
            horizontalEpisodeAdapter.currentList
        }.ifEmpty {
            // This is a fallback for the very first run when both adapter lists are empty.
            originalEpisodesList
        }

        // Reset the focus position for TV navigation
        currentEpisodePosition = 0

        // 2. Apply the new layout and submit the preserved list.
        if (isEpisodeLayoutHorizontal) {
            // --- SETUP HORIZONTAL LAYOUT ---
            episodesRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            episodesRecyclerView.adapter = horizontalEpisodeAdapter
            horizontalEpisodeAdapter.submitList(listToSubmit) // Use the preserved list

            btnToggleEpisodesLayout.setImageResource(R.drawable.ic_view_list)

            val layoutParams = episodesListContainer.layoutParams
            layoutParams.height = (140 * resources.displayMetrics.density).toInt() // 140dp in pixels
            episodesListContainer.layoutParams = layoutParams

        } else {
            // --- SETUP VERTICAL LAYOUT ---
            episodesRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
            episodesRecyclerView.adapter = verticalEpisodeAdapter
            verticalEpisodeAdapter.submitList(listToSubmit) // Use the preserved list

            // IMPORTANT: Disable nested scrolling for the vertical list to allow parent scroll
            episodesRecyclerView.isNestedScrollingEnabled = false

            btnToggleEpisodesLayout.setImageResource(R.drawable.ic_view_module)

            // Set the height to wrap_content to show all items in the vertical list
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

        // Optional: Add visual feedback with color tint
        val tintColor = if (isFavorite) {
            resources.getColor(R.color.green_see_all, null)
        } else {
            resources.getColor(android.R.color.darker_gray, null)
        }
        btnBookmark.setColorFilter(tintColor)
    }

    private fun showSnackbar(message: String, isError: Boolean) {
        val snackbar = Snackbar.make(rootLayout, message,
            if (isError) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT)

        if (isError) {
            snackbar.setBackgroundTint(resources.getColor(android.R.color.holo_red_dark, null))
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

        // Clear search if it was active
        if (isSearchVisible) {
            searchView.setQuery("", false)
        }
    }
}

//package com.faselhd.app
//
//import DetailsFragmentAdapter
//import android.content.Context
//import android.content.Intent
//import android.os.Bundle
//import android.util.Log
//import android.view.View
//import android.widget.*
//import androidx.appcompat.app.AlertDialog
//import androidx.appcompat.app.AppCompatActivity
//import androidx.lifecycle.lifecycleScope
//import androidx.recyclerview.widget.LinearLayoutManager
//import androidx.recyclerview.widget.RecyclerView
//import androidx.viewpager2.widget.ViewPager2
//import androidx.compose.material3.MaterialTheme
//import androidx.compose.material3.Text
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.platform.ComposeView
//import androidx.compose.foundation.layout.Box
//import androidx.compose.foundation.layout.padding
//import androidx.compose.foundation.layout.size
//import androidx.compose.material3.CircularProgressIndicator
//import androidx.compose.ui.unit.dp
//import androidx.work.OneTimeWorkRequestBuilder
//import androidx.work.WorkManager
//import androidx.work.workDataOf
//import com.bumptech.glide.Glide
//import com.example.myapplication.R
//import com.faselhd.app.adapters.EpisodeAdapter
//import com.faselhd.app.adapters.EpisodeDetailsAdapter
//import com.faselhd.app.db.AppDatabase
//import com.faselhd.app.models.*
//import com.faselhd.app.network.AnimeSource
//import com.faselhd.app.network.SourceManager
//import com.faselhd.app.workers.DownloadWorker
//import com.google.android.material.button.MaterialButton
//import com.google.android.material.chip.Chip
//import com.google.android.material.chip.ChipGroup
//import com.google.android.material.tabs.TabLayout
//import com.google.android.material.tabs.TabLayoutMediator
//import kotlinx.coroutines.flow.first
//import kotlinx.coroutines.launch
//import com.google.android.material.bottomsheet.BottomSheetDialog
//import android.widget.ArrayAdapter
//import com.faselhd.app.adapters.EpisodeSelectionAdapter
//import com.faselhd.app.adapters.SelectableEpisode
//import com.google.gson.Gson
//
//class AnimeDetailsActivity : AppCompatActivity() {
//
//    // --- Views ---
//    private lateinit var animeImage: ImageView
//    private lateinit var animeTitle: TextView
//    private lateinit var animeDescription: TextView
//    private lateinit var animeRating: TextView
//    private lateinit var animeYear: TextView
//    private lateinit var tagsChipGroup: ChipGroup
//    private lateinit var episodesRecyclerView: RecyclerView
//    private lateinit var seasonSpinner: Spinner
//    private lateinit var btnPlay: MaterialButton
//    private lateinit var btnDownload: MaterialButton
//    private lateinit var btnBookmark: ImageButton
//    private lateinit var btnShare: ImageButton
//    private lateinit var tabLayout: TabLayout
//    private lateinit var viewPager: ViewPager2
//    private lateinit var composeProgress: ComposeView
//    private var resumeEpisodeUrl: String? = null
//    private var allEpisodes: List<SEpisode> = emptyList()
//    private lateinit var episodesProgressBar: ProgressBar
//
//    // --- Adapters ---
//    private lateinit var episodeAdapter: EpisodeDetailsAdapter
//    private lateinit var fragmentAdapter: DetailsFragmentAdapter
//
//    // --- Utilities ---
//    private val sourceManager by lazy { SourceManager(applicationContext) }
//    private val db by lazy { AppDatabase.getDatabase(this) }
//    private var currentAnime: SAnime? = null
//    private var specificSource: AnimeSource? = null
//    private var episodesBySeason: Map<String, List<EpisodeWithHistory>> = emptyMap()
//    private var isFavorite = false
//
//    companion object {
//        private const val EXTRA_ANIME = "extra_anime"
//        private const val EXTRA_SOURCE = "extra_source"
//        private const val EXTRA_RESUME_EPISODE_URL = "extra_resume_episode_url"
//
//        fun newIntent(context: Context, anime: SAnime, source: AnimeSource?): Intent {
//            return Intent(context, AnimeDetailsActivity::class.java).apply {
//                putExtra(EXTRA_ANIME, anime)
//                putExtra(EXTRA_SOURCE, source)
//            }
//        }
//
//        fun newIntentWithResume(context: Context, anime: SAnime, resumeEpisodeUrl: String, source: AnimeSource?): Intent {
//            return Intent(context, AnimeDetailsActivity::class.java).apply {
//                putExtra(EXTRA_ANIME, anime)
//                putExtra(EXTRA_SOURCE, source)
//                putExtra(EXTRA_RESUME_EPISODE_URL, resumeEpisodeUrl)
//            }
//        }
//    }
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_anime_details)
//
//        currentAnime = intent.getParcelableExtra(EXTRA_ANIME)
//        specificSource = intent.getSerializableExtra(EXTRA_SOURCE) as? AnimeSource
//        resumeEpisodeUrl = intent.getStringExtra(EXTRA_RESUME_EPISODE_URL)
//        if (currentAnime == null) {
//            finish(); return
//        }
//
//        initViews()
//        setupToolbar()
//        setupRecyclerView()
//        setupTabsAndViewPager()
//        setupListeners()
//        loadAnimeData()
//        checkIfFavorite()
//    }
//
//    private fun initViews() {
//        animeImage = findViewById(R.id.anime_image)
//        animeTitle = findViewById(R.id.anime_title)
//        animeRating = findViewById(R.id.anime_rating)
//        animeYear = findViewById(R.id.anime_year)
//        tagsChipGroup = findViewById(R.id.anime_tags_chip_group)
//        btnPlay = findViewById(R.id.btn_play)
//        btnDownload = findViewById(R.id.btn_download)
//        animeDescription = findViewById(R.id.anime_description)
//        episodesRecyclerView = findViewById(R.id.episodes_recycler_view)
//        seasonSpinner = findViewById(R.id.season_spinner)
//        btnBookmark = findViewById(R.id.btn_bookmark)
//        btnShare = findViewById(R.id.btn_share)
//        tabLayout = findViewById(R.id.tab_layout)
//        viewPager = findViewById(R.id.view_pager)
//        composeProgress = findViewById(R.id.compose_progress)
//        episodesProgressBar = findViewById(R.id.episodes_progress_bar)
//    }
//
//    private fun setupToolbar() {
//        val toolbar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbar)
//        setSupportActionBar(toolbar)
//        supportActionBar?.setDisplayHomeAsUpEnabled(true)
//        supportActionBar?.setDisplayShowTitleEnabled(false)
//        toolbar.setNavigationOnClickListener {
//            onBackPressed()
//        }
//    }
//
//    private fun setupRecyclerView() {
//        episodeAdapter = EpisodeDetailsAdapter { episode ->
//            playEpisode(episode)
//        }
//        episodesRecyclerView.apply {
//            layoutManager = LinearLayoutManager(this@AnimeDetailsActivity, LinearLayoutManager.HORIZONTAL, false)
//            adapter = episodeAdapter
//        }
//    }
//
//    private fun setupTabsAndViewPager() {
//        fragmentAdapter = DetailsFragmentAdapter(this)
//        viewPager.adapter = fragmentAdapter
//        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
//            tab.text = when (position) {
//                0 -> "More Like This"
//                1 -> "Comments"
//                else -> null
//            }
//        }.attach()
//    }
//
//    private fun setupListeners() {
//        // UPDATED: Add click listener for the main play button
//        btnPlay.setOnClickListener {
//            handleMainPlayButtonClick()
//        }
//
//        btnBookmark.setOnClickListener { handleBookmarkClick() }
//        btnDownload.setOnClickListener {
//            if (allEpisodes.isNotEmpty()) {
//                showDownloadBottomSheet()
//            } else {
//                Toast.makeText(this, "Episodes not loaded yet.", Toast.LENGTH_SHORT).show()
//            }
//        }
//    }
//
//    // NEW: Handle main play button click
//    private fun handleMainPlayButtonClick() {
//        if (allEpisodes.isEmpty()) {
//            Toast.makeText(this, "Episodes not loaded yet. Please wait...", Toast.LENGTH_SHORT).show()
//            return
//        }
//
//        lifecycleScope.launch {
//            try {
//                // Check if there's any watch history for this anime to resume
//                val recentHistory = db.watchHistoryDao().getRecentWatchHistoryForAnime(currentAnime!!.url!!)
//
//                if (recentHistory != null && recentHistory.lastWatchedPosition > 0) {
//                    // Found recent watch history - resume from that episode
//                    val episodeToResume = allEpisodes.find { it.url == recentHistory.episodeUrl }
//                    if (episodeToResume != null) {
//                        Toast.makeText(this@AnimeDetailsActivity, "Resuming ${episodeToResume.name}", Toast.LENGTH_SHORT).show()
//                        playEpisode(episodeToResume)
//                        return@launch
//                    }
//                }
//
//                // No recent history or couldn't find episode - play first episode
//                val firstEpisode = allEpisodes.firstOrNull()
//                if (firstEpisode != null) {
//                    Toast.makeText(this@AnimeDetailsActivity, "Playing ${firstEpisode.name}", Toast.LENGTH_SHORT).show()
//                    playEpisode(firstEpisode)
//                } else {
//                    Toast.makeText(this@AnimeDetailsActivity, "No episodes available", Toast.LENGTH_SHORT).show()
//                }
//            } catch (e: Exception) {
//                Toast.makeText(this@AnimeDetailsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
//            }
//        }
//    }
//
//    private fun showDownloadBottomSheet() {
//        val dialog = BottomSheetDialog(this)
//        val view = layoutInflater.inflate(R.layout.bottom_sheet_download, null)
//        dialog.setContentView(view)
//
//        val qualitySpinner: Spinner = view.findViewById(R.id.quality_spinner)
//        val episodesRecyclerView: RecyclerView = view.findViewById(R.id.episodes_download_recycler_view)
//        val btnCancel: MaterialButton = view.findViewById(R.id.btn_cancel)
//        val btnConfirmDownload: MaterialButton = view.findViewById(R.id.btn_confirm_download)
//
//        val selectionAdapter = EpisodeSelectionAdapter()
//        episodesRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
//        episodesRecyclerView.adapter = selectionAdapter
//        selectionAdapter.submitList(allEpisodes.map { SelectableEpisode(it) })
//
//        val qualities = arrayOf("720p", "480p", "360p")
//        qualitySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, qualities)
//
//        btnCancel.setOnClickListener { dialog.dismiss() }
//
//        btnConfirmDownload.setOnClickListener {
//            val selectedEpisodes = selectionAdapter.getSelectedEpisodes()
//            if (selectedEpisodes.isEmpty()) {
//                Toast.makeText(this, "Please select at least one episode.", Toast.LENGTH_SHORT).show()
//            } else {
//                val selectedQuality = qualitySpinner.selectedItem.toString()
//                queueEpisodesForDownload(selectedEpisodes, selectedQuality)
//                dialog.dismiss()
//            }
//        }
//
//        dialog.show()
//    }
//
//    private fun queueEpisodesForDownload(episodes: List<SEpisode>, quality: String) {
//        Toast.makeText(this, "Queueing ${episodes.size} episodes for download...", Toast.LENGTH_LONG).show()
//
//        lifecycleScope.launch {
//            for (episode in episodes) {
//                try {
//                    // Fetch video list to get the actual video URL and headers
//                    val videos = sourceManager.fetchVideoList(episode.url!!, specificSource)
//                    val selectedVideo = videos.find { it.quality.contains(quality, ignoreCase = true) }
//                        ?: videos.firstOrNull()
//
//                    if (selectedVideo == null) {
//                        Log.w("Download", "No video found for episode: ${episode.name}")
//                        continue
//                    }
//
//                    // Convert headers to JSON
//                    val headersJson = selectedVideo.headers?.let { headers ->
//                        Gson().toJson(headers)
//                    }
//
//                    val workData = workDataOf(
//                        DownloadWorker.KEY_EPISODE_URL to episode.url!!,
//                        DownloadWorker.KEY_VIDEO_URL to selectedVideo.url,
//                        DownloadWorker.KEY_EPISODE_NAME to episode.name,
//                        DownloadWorker.KEY_ANIME_TITLE to currentAnime?.title,
//                        DownloadWorker.KEY_THUMBNAIL_URL to currentAnime?.thumbnail_url,
//                        DownloadWorker.KEY_HEADERS_JSON to headersJson
//                    )
//
//                    val downloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
//                        .setInputData(workData)
//                        .addTag(episode.url!!)
//                        .build()
//
//                    WorkManager.getInstance(this@AnimeDetailsActivity).enqueue(downloadWorkRequest)
//
//                    val downloadEntry = Download(
//                        episodeUrl = episode.url!!,
//                        animeTitle = currentAnime?.title ?: "",
//                        episodeName = episode.name,
//                        thumbnailUrl = currentAnime?.thumbnail_url,
//                        downloadState = DownloadState.QUEUED,
//                        mediaUri = selectedVideo.url
//                    )
//                    db.downloadDao().upsert(downloadEntry)
//
//                } catch (e: Exception) {
//                    Log.e("Download", "Failed to queue episode ${episode.name}: ${e.message}")
//                }
//            }
//        }
//    }
//
//    private fun loadAnimeData() {
//        showLoading(true)
//        episodesProgressBar.visibility = View.VISIBLE
//        episodesRecyclerView.visibility = View.INVISIBLE
//
//        lifecycleScope.launch {
//            var success = false
//            try {
//                println("current anime url ${currentAnime!!.url!!}")
//                val detailedAnime = sourceManager.fetchAnimeDetails(currentAnime!!.url!!, specificSource)
//                println("current anime details ${currentAnime!!.toString()}")
//                currentAnime = detailedAnime
//                populateUiDetails(detailedAnime)
//                println("anime source: ${specificSource}")
//
//                val episodes = sourceManager.fetchEpisodeList(currentAnime!!.url!!, specificSource)
//                allEpisodes = episodes
//                processAndDisplayEpisodes(episodes)
//
//                success = true
//
//            } catch (e: Exception) {
//                Toast.makeText(this@AnimeDetailsActivity, "Error loading details: ${e.message}", Toast.LENGTH_LONG).show()
//                println("Error loading details: ${e.message}")
//            } finally {
//                showLoading(false)
//                episodesProgressBar.visibility = View.GONE
//                episodesRecyclerView.visibility = View.VISIBLE
//
//                if (success && resumeEpisodeUrl != null) {
//                    val urlToPlay = resumeEpisodeUrl!!
//                    val episodeToPlay = allEpisodes.find { it.url == urlToPlay }
//
//                    if (episodeToPlay != null) {
//                        playEpisode(episodeToPlay)
//                    } else {
//                        Toast.makeText(this@AnimeDetailsActivity, "Could not find the episode to resume.", Toast.LENGTH_SHORT).show()
//                    }
//                    resumeEpisodeUrl = null
//                }
//            }
//        }
//    }
//
//    private fun playEpisode(episode: SEpisode) {
//        episode.url?.let { episodeUrl ->
//            showLoading(true)
//
//            lifecycleScope.launch {
//                try {
//                    val seasonName = episode.name?.substringBefore(":")?.trim() ?: "Season 1"
//                    val episodesWithHistoryForSeason = episodesBySeason[seasonName] ?: emptyList()
//                    val episodeListForPlayer = episodesWithHistoryForSeason.map { it.episode }
//
//                    val videos = sourceManager.fetchVideoList(episodeUrl, specificSource)
//                    println("videos: ${videos.toString()}")
//                    val history = db.watchHistoryDao().getWatchHistoryByEpisodeUrl(episodeUrl)
//
//                    showLoading(false)
//
//                    if (videos.isNotEmpty()) {
//                        val intent = VideoPlayerActivity.newIntent(
//                            context = this@AnimeDetailsActivity,
//                            videos = videos,
//                            anime = currentAnime!!,
//                            currentEpisode = episode,
//                            episodeListForSeason = ArrayList(episodeListForPlayer),
//                            startPosition = history?.lastWatchedPosition ?: 0L,
//                            source = specificSource
//                        )
//                        startActivity(intent)
//                    } else {
//                        Toast.makeText(this@AnimeDetailsActivity, "Could not find video link", Toast.LENGTH_SHORT).show()
//                    }
//                } catch (e: Exception) {
//                    showLoading(false)
//                    Toast.makeText(this@AnimeDetailsActivity, "Error loading video: ${e.message}", Toast.LENGTH_LONG).show()
//                    println("Error loading video: ${e.message}")
//                }
//            }
//        }
//    }
//
//    private fun populateUiDetails(anime: SAnime) {
//        animeTitle.text = anime.title
//        animeDescription.text = "Genre: ${anime.genre}\n\n${anime.description}"
//        animeRating.text = "N/A" ?: "N/A"
//        animeYear.text = ">  ${"2022" ?: "2022"}"
//
//        Glide.with(this).load(anime.thumbnail_url).into(animeImage)
//
//        tagsChipGroup.removeAllViews()
//        addChipToGroup("13+")
//        addChipToGroup("Japan")
//        addChipToGroup("Subtitle")
//    }
//
//    private fun addChipToGroup(text: String) {
//        val chip = Chip(this).apply {
//            this.text = text
//            setChipBackgroundColorResource(android.R.color.transparent)
//            setChipStrokeColorResource(R.color.green_see_all)
//            chipStrokeWidth = 3f
//            setTextColor(resources.getColor(R.color.green_see_all, null))
//        }
//        tagsChipGroup.addView(chip)
//    }
//
//    private fun startDownload(episode: SEpisode) {
//        lifecycleScope.launch {
//            try {
//                val videos = sourceManager.fetchVideoList(episode.url!!)
//                hideDownloadIndicatorFor(episode)
//                if (videos.isEmpty()) {
//                    Toast.makeText(this@AnimeDetailsActivity, "Could not find any video links.", Toast.LENGTH_SHORT).show()
//                    return@launch
//                }
//                showDownloadQualityDialog(episode, videos)
//            } catch (e: Exception) {
//                hideDownloadIndicatorFor(episode)
//                Toast.makeText(this@AnimeDetailsActivity, "Failed to get video list: ${e.message}", Toast.LENGTH_SHORT).show()
//            }
//        }
//    }
//
//    private fun hideDownloadIndicatorFor(episode: SEpisode) {
//        val position = episodeAdapter.currentList.indexOfFirst { it.episode.url == episode.url }
//        if (position != -1) {
//            val viewHolder = episodesRecyclerView.findViewHolderForAdapterPosition(position) as? EpisodeAdapter.ViewHolder
//            viewHolder?.setDownloadingState(false)
//        }
//    }
//
//    private fun showDownloadQualityDialog(episode: SEpisode, videos: List<Video>) {
//        val qualityOptions = videos.map { it.quality }.toTypedArray()
//
//        AlertDialog.Builder(this)
//            .setTitle("Select Download Quality")
//            .setItems(qualityOptions) { dialog, which ->
//                val selectedVideo = videos[which]
//                Toast.makeText(this, "Queueing download for: ${episode.name} (${selectedVideo.quality})", Toast.LENGTH_SHORT).show()
//
//                // Convert headers to JSON
//                val headersJson = selectedVideo.headers?.let { headers ->
//                    Gson().toJson(headers)
//                }
//
//                val workData = workDataOf(
//                    DownloadWorker.KEY_EPISODE_URL to episode.url!!,
//                    DownloadWorker.KEY_VIDEO_URL to selectedVideo.url,
//                    DownloadWorker.KEY_EPISODE_NAME to episode.name,
//                    DownloadWorker.KEY_ANIME_TITLE to currentAnime?.title,
//                    DownloadWorker.KEY_THUMBNAIL_URL to currentAnime?.thumbnail_url,
//                    DownloadWorker.KEY_HEADERS_JSON to headersJson
//                )
//
//                val downloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
//                    .setInputData(workData)
//                    .addTag(episode.url!!)
//                    .build()
//
//                WorkManager.getInstance(this).enqueue(downloadWorkRequest)
//
//                lifecycleScope.launch {
//                    val downloadEntry = Download(
//                        episodeUrl = episode.url!!,
//                        animeTitle = currentAnime?.title ?: "",
//                        episodeName = episode.name,
//                        thumbnailUrl = currentAnime?.thumbnail_url,
//                        downloadState = DownloadState.QUEUED,
//                        mediaUri = selectedVideo.url
//                    )
//                    db.downloadDao().upsert(downloadEntry)
//                }
//                dialog.dismiss()
//            }
//            .setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
//            .create()
//            .show()
//    }
//
//    private suspend fun processAndDisplayEpisodes(episodes: List<SEpisode>) {
//        val historyMap = db.watchHistoryDao().getAllWatchHistory().first()
//            .associateBy { it.episodeUrl }
//
//        val episodesWithHistory = episodes.map { episode ->
//            EpisodeWithHistory(
//                episode = episode,
//                history = historyMap[episode.url]
//            )
//        }
//
//        episodesBySeason = episodesWithHistory.groupBy {
//            it.episode.name?.substringBefore(":")?.trim() ?: "Season 1"
//        }
//
//        val seasonNames = episodesBySeason.keys.toList()
//
//        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, seasonNames)
//        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
//        seasonSpinner.adapter = spinnerAdapter
//
//        seasonSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
//            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
//                val selectedSeason = seasonNames[position]
//                episodeAdapter.submitList(episodesBySeason[selectedSeason])
//            }
//            override fun onNothingSelected(parent: AdapterView<*>?) {}
//        }
//
//        if (seasonNames.isNotEmpty()) {
//            episodeAdapter.submitList(episodesBySeason[seasonNames.first()])
//        }
//    }
//
//    private fun checkIfFavorite() {
//        lifecycleScope.launch {
//            val favorite = db.favoriteDao().getFavoriteByUrl(currentAnime!!.url!!)
//            isFavorite = favorite != null
//            updateBookmarkButtonUI()
//        }
//    }
//
//    private fun handleBookmarkClick() {
//        isFavorite = !isFavorite
//        lifecycleScope.launch {
//            if (isFavorite) {
//                val favorite = Favorite(
//                    animeUrl = currentAnime!!.url!!,
//                    title = currentAnime!!.title,
//                    thumbnailUrl = currentAnime!!.thumbnail_url,
//                    source = (specificSource ?: SourceManager.getSelectedSource(applicationContext)).name
//                )
//                db.favoriteDao().insert(favorite)
//                Toast.makeText(this@AnimeDetailsActivity, "Added to list", Toast.LENGTH_SHORT).show()
//            } else {
//                db.favoriteDao().delete(currentAnime!!.url!!)
//                Toast.makeText(this@AnimeDetailsActivity, "Removed from list", Toast.LENGTH_SHORT).show()
//            }
//            updateBookmarkButtonUI()
//        }
//    }
//
//    private fun updateBookmarkButtonUI() {
//        if (isFavorite) {
//            btnBookmark.setImageResource(R.drawable.bookmark_check_24px)
//        } else {
//            btnBookmark.setImageResource(R.drawable.bookmark_24px)
//        }
//    }
//
//    private fun showLoading(show: Boolean) {
//        if (show) {
//            composeProgress.visibility = View.VISIBLE
//            composeProgress.setContent {
//                MaterialTheme {
//                    Box(
//                        contentAlignment = Alignment.Center,
//                        modifier = Modifier.size(100.dp)
//                    ) {
//                        CircularProgressIndicator(
//                            modifier = Modifier.size(64.dp),
//                            color = MaterialTheme.colorScheme.primary,
//                            strokeWidth = 4.dp
//                        )
//                    }
//                }
//            }
//        } else {
//            composeProgress.visibility = View.GONE
//        }
//    }
//}