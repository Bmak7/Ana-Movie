package com.faselhd.app

import DetailsFragmentAdapter
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.widget.ArrayAdapter
import com.faselhd.app.adapters.EpisodeSelectionAdapter
import com.faselhd.app.adapters.SelectableEpisode


class AnimeDetailsActivity : AppCompatActivity() {

    // --- Views ---
    private lateinit var animeImage: ImageView
    private lateinit var animeTitle: TextView
    private lateinit var animeDescription: TextView
    private lateinit var animeRating: TextView
    private lateinit var animeYear: TextView
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
    private var resumeEpisodeUrl: String? = null // <-- Make sure you have this property
    private var allEpisodes: List<SEpisode> = emptyList() // <-- And this one
    private lateinit var episodesProgressBar: ProgressBar


    // --- Adapters ---
    private lateinit var episodeAdapter: EpisodeDetailsAdapter
    private lateinit var fragmentAdapter: DetailsFragmentAdapter


    // --- Utilities ---
    private val sourceManager by lazy { SourceManager(applicationContext) }
    private val db by lazy { AppDatabase.getDatabase(this) }
    private var currentAnime: SAnime? = null
    private var specificSource: AnimeSource? = null
    private var episodesBySeason: Map<String, List<EpisodeWithHistory>> = emptyMap()
    private var isFavorite = false

    companion object {
        private const val EXTRA_ANIME = "extra_anime"
        private const val EXTRA_SOURCE = "extra_source"
        private const val EXTRA_RESUME_EPISODE_URL = "extra_resume_episode_url"

        // The standard way to start this activity
        fun newIntent(context: Context, anime: SAnime, source: AnimeSource?): Intent {

            return Intent(context, AnimeDetailsActivity::class.java).apply {
                putExtra(EXTRA_ANIME, anime)
                putExtra(EXTRA_SOURCE, source)
            }
        }

        // THIS IS THE MISSING FUNCTION for "Continue Watching"
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

        currentAnime = intent.getParcelableExtra(EXTRA_ANIME)
        specificSource = intent.getSerializableExtra(EXTRA_SOURCE) as? AnimeSource
        resumeEpisodeUrl = intent.getStringExtra(EXTRA_RESUME_EPISODE_URL)
        if (currentAnime == null) {
            finish(); return
        }

        initViews()
        setupToolbar()
        setupRecyclerView()
        setupTabsAndViewPager()
        setupListeners()
        loadAnimeData()
        checkIfFavorite()
    }

    private fun initViews() {
        animeImage = findViewById(R.id.anime_image)
        animeTitle = findViewById(R.id.anime_title)
        animeRating = findViewById(R.id.anime_rating)
        animeYear = findViewById(R.id.anime_year)
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
    }

    private fun setupToolbar() {
        val toolbar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        // This tells the Toolbar that it should display a "Home" button (which we've configured as a back arrow)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        // This is the crucial part that makes the button work.
        // When the navigation icon (the back arrow) is clicked, it calls onBackPressed(),
        // which is the standard Android behavior for closing an activity.
        toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }

    private fun setupRecyclerView() {
        episodeAdapter = EpisodeDetailsAdapter { episode ->
            playEpisode(episode) // Connect the click to your existing play logic
        }
        episodesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@AnimeDetailsActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = episodeAdapter
        }
    }

    private fun setupTabsAndViewPager() {
        fragmentAdapter = DetailsFragmentAdapter(this)
        viewPager.adapter = fragmentAdapter
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "More Like This"
                1 -> "Comments" // Example
                else -> null
            }
        }.attach()
    }

    private fun setupListeners() {
        btnBookmark.setOnClickListener { handleBookmarkClick() }
        btnDownload.setOnClickListener {
            // Check if we have episodes first
            if (allEpisodes.isNotEmpty()) {
                showDownloadBottomSheet()
            } else {
                Toast.makeText(this, "Episodes not loaded yet.", Toast.LENGTH_SHORT).show()
            }
        }
        btnBookmark.setOnClickListener { handleBookmarkClick() }
        // TODO: Add listeners for play, download, share
    }

    private fun showDownloadBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_download, null)
        dialog.setContentView(view)

        val qualitySpinner: Spinner = view.findViewById(R.id.quality_spinner)
        val episodesRecyclerView: RecyclerView = view.findViewById(R.id.episodes_download_recycler_view)
        val btnCancel: MaterialButton = view.findViewById(R.id.btn_cancel)
        val btnConfirmDownload: MaterialButton = view.findViewById(R.id.btn_confirm_download)

        // Setup RecyclerView with the new adapter
        val selectionAdapter = EpisodeSelectionAdapter()
        episodesRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        episodesRecyclerView.adapter = selectionAdapter
        // Convert your list of SEpisode to a list of SelectableEpisode
        selectionAdapter.submitList(allEpisodes.map { SelectableEpisode(it) })

        // TODO: Populate qualitySpinner with actual video qualities
        // For now, a placeholder:
        val qualities = arrayOf("720p", "480p", "360p")
        qualitySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, qualities)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirmDownload.setOnClickListener {
            val selectedEpisodes = selectionAdapter.getSelectedEpisodes()
            if (selectedEpisodes.isEmpty()) {
                Toast.makeText(this, "Please select at least one episode.", Toast.LENGTH_SHORT).show()
            } else {
                // Get selected quality
                val selectedQuality = qualitySpinner.selectedItem.toString()
                // Queue the selected episodes for download
                queueEpisodesForDownload(selectedEpisodes, selectedQuality)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    // Add this new function to queue multiple episodes
    private fun queueEpisodesForDownload(episodes: List<SEpisode>, quality: String) {
        Toast.makeText(this, "Queueing ${episodes.size} episodes for download...", Toast.LENGTH_LONG).show()

        lifecycleScope.launch {
            for (episode in episodes) {
                // Your existing single-download logic can be reused here
                // We pass 'null' for the videoUrl because the worker will fetch it.
                val workData = workDataOf(
                    DownloadWorker.KEY_EPISODE_URL to episode.url!!,
                    DownloadWorker.KEY_VIDEO_URL to null, // Worker will find the best URL or use the quality hint
                    DownloadWorker.KEY_EPISODE_NAME to episode.name,
                    DownloadWorker.KEY_ANIME_TITLE to currentAnime?.title,
                    DownloadWorker.KEY_THUMBNAIL_URL to currentAnime?.thumbnail_url
                    // TODO: You could pass the selected 'quality' here too
                )

                val downloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(workData).build()
                WorkManager.getInstance(this@AnimeDetailsActivity).enqueue(downloadWorkRequest)

                // Create the initial database entry
                val downloadEntry = Download(
                    episodeUrl = episode.url!!,
                    animeTitle = currentAnime?.title ?: "",
                    episodeName = episode.name,
                    thumbnailUrl = currentAnime?.thumbnail_url,
                    downloadState = DownloadState.QUEUED,
                    mediaUri = null
                )
                db.downloadDao().upsert(downloadEntry)
            }
        }
    }

    // In AnimeDetailsActivity.kt

    private fun loadAnimeData() {
        // Show the main loading indicator for the whole screen
        showLoading(true)

        // Also show the specific progress bar for the episodes list
        episodesProgressBar.visibility = View.VISIBLE
        episodesRecyclerView.visibility = View.INVISIBLE

        lifecycleScope.launch {
            var success = false
            try {
                // --- Step 1: Fetch and display the main anime details ---
                println("current amnie url ${currentAnime!!.url!!}")
                val detailedAnime = sourceManager.fetchAnimeDetails(currentAnime!!.url!!, specificSource)
                println("current amnie details ${currentAnime!!.toString()}")
                currentAnime = detailedAnime
                populateUiDetails(detailedAnime) // Update title, description, image, etc.
                println("anime sssource: ${specificSource}")
                // --- Step 2: Fetch and display the episode list and seasons ---
                val episodes = sourceManager.fetchEpisodeList(currentAnime!!.url!!, specificSource)
                allEpisodes = episodes // Store a copy of the raw episode list
                processAndDisplayEpisodes(episodes) // This populates the spinner and RecyclerView

                // If we reached here, both network calls were successful
                success = true

            } catch (e: Exception) {
                Toast.makeText(this@AnimeDetailsActivity, "Error loading details: ${e.message}", Toast.LENGTH_LONG).show()
                println("Error loading details:  ${e.message}")
            } finally {
                // --- Step 3: This block runs after the 'try' block, regardless of success or failure ---

                // Hide all loading indicators
                showLoading(false)
                episodesProgressBar.visibility = View.GONE
                episodesRecyclerView.visibility = View.VISIBLE

                // --- Step 4: THE CRITICAL LOGIC ---
                // If the network calls were successful AND we have a resume URL...
                if (success && resumeEpisodeUrl != null) {
                    val urlToPlay = resumeEpisodeUrl!!
                    // Find the episode object that matches the URL
                    val episodeToPlay = allEpisodes.find { it.url == urlToPlay }

                    if (episodeToPlay != null) {
                        // We found it, now call the play function
                        playEpisode(episodeToPlay)
                    } else {
                        // We couldn't find the specific episode, inform the user
                        Toast.makeText(this@AnimeDetailsActivity, "Could not find the episode to resume.", Toast.LENGTH_SHORT).show()
                    }

                    // IMPORTANT: Clear the resume URL so it doesn't try to auto-play again
                    // if the user rotates the screen or comes back to the activity.
                    resumeEpisodeUrl = null
                }
            }
        }
    }

    private fun playEpisode(episode: SEpisode) {
        episode.url?.let { episodeUrl ->
            // --- SHOW LOADING INDICATOR ---
            showLoading(true)

            lifecycleScope.launch {
                try {
                    val seasonName = episode.name?.substringBefore(":")?.trim() ?: "Season 1"
                    val episodesWithHistoryForSeason = episodesBySeason[seasonName] ?: emptyList()
                    val episodeListForPlayer = episodesWithHistoryForSeason.map { it.episode }

                    val videos = sourceManager.fetchVideoList(episodeUrl, specificSource)
                    println("vidoes sss : ${videos.toString()}")
                    val history = db.watchHistoryDao().getWatchHistoryByEpisodeUrl(episodeUrl)

                    // --- HIDE LOADING (on success before starting next activity) ---
                    showLoading(false)

                    if (videos.isNotEmpty()) {
                        val intent = VideoPlayerActivity.newIntent(
                            context = this@AnimeDetailsActivity,
                            videos = videos,
                            anime = currentAnime!!,
                            currentEpisode = episode,
                            episodeListForSeason = ArrayList(episodeListForPlayer),
                            startPosition = history?.lastWatchedPosition ?: 0L,
                            source = specificSource
                        )
                        startActivity(intent)
                    } else {
                        Toast.makeText(this@AnimeDetailsActivity, "Could not find video link", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    // --- HIDE LOADING (on failure) ---
                    showLoading(false)
                    Toast.makeText(this@AnimeDetailsActivity, "Error loading video: ${e.message}", Toast.LENGTH_LONG).show()
                    println("Error loading video: ${e.message}")
                }
            }
        }
    }

    private fun populateUiDetails(anime: SAnime) {
        animeTitle.text = anime.title
        animeDescription.text = "Genre: ${anime.genre}\n\n${anime.description}"
        animeRating.text = "N/A" ?: "N/A"
        animeYear.text = ">  ${"2022" ?: "2022"}" // Assumes year exists in SAnime

        Glide.with(this).load(anime.thumbnail_url).into(animeImage)

        // Add chips programmatically
        tagsChipGroup.removeAllViews()
        addChipToGroup("13+")
        addChipToGroup("Japan")
        addChipToGroup("Subtitle")
    }

    private fun addChipToGroup(text: String) {
        val chip = Chip(this).apply {
            this.text = text
            // Style to match design
            setChipBackgroundColorResource(android.R.color.transparent)
            setChipStrokeColorResource(R.color.green_see_all) // You need this color
            chipStrokeWidth = 3f
            setTextColor(resources.getColor(R.color.green_see_all, null))
        }
        tagsChipGroup.addView(chip)
    }

    private fun startDownload(episode: SEpisode) {
                lifecycleScope.launch {
            try {
                val videos = sourceManager.fetchVideoList(episode.url!!)


                // NETWORK CALL IS DONE - HIDE LOADING AND SHOW DIALOG
                hideDownloadIndicatorFor(episode) // <-- New helper function
                if (videos.isEmpty()) {
                    Toast.makeText(this@AnimeDetailsActivity, "Could not find any video links.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                showDownloadQualityDialog(episode, videos)
            } catch (e: Exception) {
                // NETWORK CALL FAILED - HIDE LOADING AND SHOW ERROR
                hideDownloadIndicatorFor(episode) // <-- New helper function
                Toast.makeText(this@AnimeDetailsActivity, "Failed to get video list: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // *** ADD THIS NEW HELPER FUNCTION ***
    private fun hideDownloadIndicatorFor(episode: SEpisode) {
        // Find the item in the adapter's current list
        val position = episodeAdapter.currentList.indexOfFirst { it.episode.url == episode.url }
        if (position != -1) {
            // Get the ViewHolder for that position
            val viewHolder = episodesRecyclerView.findViewHolderForAdapterPosition(position) as? EpisodeAdapter.ViewHolder
            // Tell the ViewHolder to hide the loading indicator
            viewHolder?.setDownloadingState(false)
        }
    }

    // Add this function back
    private fun showDownloadQualityDialog(episode: SEpisode, videos: List<Video>) {
        val qualityOptions = videos.map { it.quality }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Download Quality")
            .setItems(qualityOptions) { dialog, which ->
                val selectedVideo = videos[which]
                Toast.makeText(this, "Queueing download for: ${episode.name} (${selectedVideo.quality})", Toast.LENGTH_SHORT).show()

                val workData = workDataOf(
                    DownloadWorker.KEY_EPISODE_URL to episode.url!!,
                    DownloadWorker.KEY_VIDEO_URL to selectedVideo.url, // The specific quality HLS URL
                    DownloadWorker.KEY_EPISODE_NAME to episode.name,
                    DownloadWorker.KEY_ANIME_TITLE to currentAnime?.title,
                    DownloadWorker.KEY_THUMBNAIL_URL to currentAnime?.thumbnail_url
                )

                val downloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setInputData(workData)
                    .addTag(episode.url!!)
                    .build()

                WorkManager.getInstance(this).enqueue(downloadWorkRequest)

                lifecycleScope.launch {
                    val downloadEntry = Download(
                        episodeUrl = episode.url!!,
                        animeTitle = currentAnime?.title ?: "",
                        episodeName = episode.name,
                        thumbnailUrl = currentAnime?.thumbnail_url,
                        downloadState = DownloadState.QUEUED,
                        mediaUri = null
                    )
                    db.downloadDao().upsert(downloadEntry)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
            .create()
            .show()
    }
    // Inside AnimeDetailsActivity.kt

    private suspend fun processAndDisplayEpisodes(episodes: List<SEpisode>) {
        // 1. Get all watch history from the database at once for efficiency
        val historyMap = db.watchHistoryDao().getAllWatchHistory().first()
            .associateBy { it.episodeUrl }

        // 2. Combine the episodes from the network with their matching history from the database.
        val episodesWithHistory = episodes.map { episode ->
            EpisodeWithHistory(
                episode = episode,
                history = historyMap[episode.url]
            )
        }

        // 3. Group the combined 'EpisodeWithHistory' objects by season.
        // This now produces the correct Map<String, List<EpisodeWithHistory>> type.
        episodesBySeason = episodesWithHistory.groupBy {
            it.episode.name?.substringBefore(":")?.trim() ?: "Season 1"
        }

        val seasonNames = episodesBySeason.keys.toList()

        // Setup Spinner with the list of season names
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, seasonNames)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        seasonSpinner.adapter = spinnerAdapter

        seasonSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedSeason = seasonNames[position]
                episodeAdapter.submitList(episodesBySeason[selectedSeason])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Default to showing the first season's episodes when the screen loads
        if (seasonNames.isNotEmpty()) {
            episodeAdapter.submitList(episodesBySeason[seasonNames.first()])
        }
    }

    private fun checkIfFavorite() {
        lifecycleScope.launch {
            val favorite = db.favoriteDao().getFavoriteByUrl(currentAnime!!.url!!)
            isFavorite = favorite != null
            updateBookmarkButtonUI()
        }
    }

    private fun handleBookmarkClick() {
        isFavorite = !isFavorite // Toggle state
        lifecycleScope.launch {
            if (isFavorite) {
                val favorite = Favorite(
                    animeUrl = currentAnime!!.url!!,
                    title = currentAnime!!.title,
                    thumbnailUrl = currentAnime!!.thumbnail_url,
                    source = (specificSource ?: SourceManager.getSelectedSource(applicationContext)).name
                )
                db.favoriteDao().insert(favorite)
                Toast.makeText(this@AnimeDetailsActivity, "Added to list", Toast.LENGTH_SHORT).show()
            } else {
                db.favoriteDao().delete(currentAnime!!.url!!)
                Toast.makeText(this@AnimeDetailsActivity, "Removed from list", Toast.LENGTH_SHORT).show()
            }
            updateBookmarkButtonUI()
        }
    }

    private fun updateBookmarkButtonUI() {
        if (isFavorite) {
            btnBookmark.setImageResource(R.drawable.bookmark_check_24px) // Filled icon
        } else {
            btnBookmark.setImageResource(R.drawable.bookmark_24px) // Border icon
        }
    }


    private fun showLoading(show: Boolean) {
        if (show) {
            composeProgress.visibility = View.VISIBLE
            composeProgress.setContent {
                // Use your app's MaterialTheme for consistent styling
                MaterialTheme {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(100.dp)
                    ) {
                        // This is the circular progress indicator
                        CircularProgressIndicator(
                            modifier = Modifier.size(64.dp),
                            // Use your theme's primary color for a consistent look
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 4.dp
                        )
                    }
                }
            }
        } else {
            composeProgress.visibility = View.GONE
        }
    }
}
