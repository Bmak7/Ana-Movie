package com.faselhd.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.faselhd.app.adapters.EpisodeAdapter
import com.faselhd.app.adapters.SeasonAdapter
import com.faselhd.app.db.AppDatabase
import com.faselhd.app.models.*
import com.faselhd.app.network.FaselHDSource
import com.faselhd.app.services.VideoDownloadService
import com.faselhd.app.workers.DownloadWorker
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AnimeDetailsActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var animeImage: ImageView
    private lateinit var animeTitle: TextView
    private lateinit var animeDescription: TextView
    private lateinit var animeGenre: TextView
    private lateinit var animeStatus: TextView
    private lateinit var episodesRecyclerView: RecyclerView
    private lateinit var watchButton: MaterialButton
    private lateinit var composeProgress: ComposeView
    private lateinit var seasonsRecyclerView: RecyclerView

    private lateinit var episodeAdapter: EpisodeAdapter
    private lateinit var seasonAdapter: SeasonAdapter

    private val faselHDSource by lazy { FaselHDSource(applicationContext) }
    private val db by lazy { AppDatabase.getDatabase(this) }

    private var currentAnime: SAnime? = null
    private var allEpisodes: List<SEpisode> = emptyList()
    private var episodesBySeason: Map<String, List<EpisodeWithHistory>> = emptyMap()
    private var resumeEpisodeUrl: String? = null

    companion object {
        private const val EXTRA_ANIME = "extra_anime"
        private const val EXTRA_RESUME_EPISODE_URL = "extra_resume_episode_url"

        fun newIntent(context: Context, anime: SAnime): Intent {
            return Intent(context, AnimeDetailsActivity::class.java).apply {
                putExtra(EXTRA_ANIME, anime)
            }
        }

        fun newIntentWithResume(context: Context, anime: SAnime, resumeEpisodeUrl: String): Intent {
            return Intent(context, AnimeDetailsActivity::class.java).apply {
                putExtra(EXTRA_ANIME, anime)
                putExtra(EXTRA_RESUME_EPISODE_URL, resumeEpisodeUrl)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_anime_details)

        currentAnime = intent.getParcelableExtra(EXTRA_ANIME)
        resumeEpisodeUrl = intent.getStringExtra(EXTRA_RESUME_EPISODE_URL)

        if (currentAnime == null) {
            finish()
            return
        }

        initViews()
        setupToolbar()
        setupRecyclerViews()
        displayAnimeInfo()
        loadEpisodes()
    }

    // *** THIS IS THE CRITICAL FIX ***
    // This function is called every time the activity comes back to the foreground.
    override fun onResume() {
        super.onResume()

        // If we have already fetched the episodes from the network...
        if (allEpisodes.isNotEmpty()) {
            // ...then we just need to re-process them with the latest data from the database.
            // This is very fast and doesn't require a network call.
            lifecycleScope.launch {
                processAndDisplayEpisodes(allEpisodes)
            }
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        animeImage = findViewById(R.id.anime_image)
        animeTitle = findViewById(R.id.anime_title)
        animeDescription = findViewById(R.id.anime_description)
        animeGenre = findViewById(R.id.anime_genre)
        animeStatus = findViewById(R.id.anime_status)
        episodesRecyclerView = findViewById(R.id.episodes_recycler_view)
        watchButton = findViewById(R.id.watch_button)
        composeProgress = findViewById(R.id.compose_progress)
        seasonsRecyclerView = findViewById(R.id.seasons_recycler_view)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressed() }
    }

    private fun setupRecyclerViews() {
        seasonAdapter = SeasonAdapter { seasonName ->
            val episodesForSeason = episodesBySeason[seasonName] ?: emptyList()
            episodeAdapter.submitList(episodesForSeason)
        }
        seasonsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@AnimeDetailsActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = seasonAdapter
        }

        // *** THIS IS THE CORRECTED INITIALIZATION ***
        episodeAdapter = EpisodeAdapter(
            onClick = { episode ->
                playEpisode(episode)
            },
            // We now need the full EpisodeWithHistory object to find its position
            onDownloadClick = { episode ->
                // The loading state is already set by the ViewHolder's click listener.
                // We just need to start the download process.
                startDownload(episode)
            }
        )

        episodesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@AnimeDetailsActivity)
            adapter = episodeAdapter
            isNestedScrollingEnabled = false
        }
    }

    // Modify the startDownload function
    private fun startDownload(episode: SEpisode) {
        lifecycleScope.launch {
            try {
                val videos = faselHDSource.fetchVideoList(episode.url!!)
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
                        downloadState = DownloadState.QUEUED
                    )
                    db.downloadDao().upsert(downloadEntry)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
            .create()
            .show()
    }
    private fun displayAnimeInfo() {
        currentAnime?.let { anime ->
            animeTitle.text = anime.title
            animeDescription.text = anime.description ?: "No description available"
            animeGenre.text = "Genre: ${anime.genre ?: "Not specified"}"
            animeStatus.text = "Status: ${getStatusText(anime.status)}"

            Glide.with(this)
                .load(anime.thumbnail_url)
                .placeholder(R.drawable.placeholder_anime)
                .error(R.drawable.placeholder_anime)
                .into(animeImage)

            watchButton.setOnClickListener {
                // Find the first episode that is NOT finished to play.
                // If all are finished, it will play the very first episode again.
                val firstUnwatched = episodeAdapter.currentList.firstOrNull { it.history?.isFinished == false }
                val episodeToPlay = firstUnwatched?.episode ?: allEpisodes.firstOrNull()

                if (episodeToPlay != null) {
                    playEpisode(episodeToPlay)
                } else {
                    Toast.makeText(this, "No episodes available to watch", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadEpisodes() {
        currentAnime?.url?.let { url ->
            if (allEpisodes.isEmpty()) {
                showLoading(true)
                lifecycleScope.launch {
                    try {
                        val episodesFromNetwork = faselHDSource.fetchEpisodeList(url)
                        allEpisodes = episodesFromNetwork
                        showLoading(false)

                        if (allEpisodes.isNotEmpty()) {
                            processAndDisplayEpisodes(allEpisodes)
                            resumeEpisodeUrl?.let { urlToPlay ->
                                val episodeToPlay = allEpisodes.find { it.url == urlToPlay }
                                episodeToPlay?.let { playEpisode(it) }
                                resumeEpisodeUrl = null
                            }
                        } else {
                            Toast.makeText(this@AnimeDetailsActivity, "No episodes available", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        showLoading(false)
                        Toast.makeText(this@AnimeDetailsActivity, "Error loading episodes: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private suspend fun processAndDisplayEpisodes(allEpisodes: List<SEpisode>) {
        val historyMap = db.watchHistoryDao().getAllWatchHistory().first()
            .associateBy { it.episodeUrl }

        val episodesWithHistory = allEpisodes.map { episode ->
            EpisodeWithHistory(
                episode = episode,
                history = historyMap[episode.url]
            )
        }

        episodesBySeason = episodesWithHistory.groupBy {
            it.episode.name?.substringBefore(" : ")?.trim() ?: "Season 1"
        }

        val seasonNames = episodesBySeason.keys.toList()
        seasonAdapter.submitList(seasonNames)

        // Find which season is currently selected to maintain the state after refresh
        val currentlySelectedSeasonName = seasonAdapter.getSelectedSeason() ?: seasonNames.firstOrNull()
        if (currentlySelectedSeasonName != null) {
            seasonAdapter.setSelectedSeason(currentlySelectedSeasonName)
            episodeAdapter.submitList(episodesBySeason[currentlySelectedSeasonName])
        }
    }

    private fun playEpisode(episode: SEpisode) {
        episode.url?.let { episodeUrl ->
            showLoading(true)
            lifecycleScope.launch {
                try {
                    val seasonName = episode.name?.substringBefore(" : ")?.trim() ?: "Season 1"
                    val seasonEpisodeList = episodesBySeason[seasonName]?.map { it.episode } ?: emptyList()
                    val videos = faselHDSource.fetchVideoList(episodeUrl)
                    val history = db.watchHistoryDao().getWatchHistoryByEpisodeUrl(episodeUrl)
                    showLoading(false)

                    if (videos.isNotEmpty()) {
                        val intent = VideoPlayerActivity.newIntent(
                            context = this@AnimeDetailsActivity,
                            videos = videos,
                            anime = currentAnime!!,
                            currentEpisode = episode,
                            episodeListForSeason = ArrayList(seasonEpisodeList),
                            startPosition = history?.lastWatchedPosition ?: 0L
                        )
                        startActivity(intent)
                    } else {
                        Toast.makeText(this@AnimeDetailsActivity, "Could not find video link", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    showLoading(false)
                    Toast.makeText(this@AnimeDetailsActivity, "Error loading video: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getStatusText(status: Int): String {
        return when (status) {
            SAnime.ONGOING -> getString(R.string.status_ongoing)
            SAnime.COMPLETED -> getString(R.string.status_completed)
            else -> getString(R.string.status_unknown)
        }
    }

    private fun showLoading(show: Boolean) {
        if (show) {
            composeProgress.visibility = View.VISIBLE
            composeProgress.setContent {
                MaterialTheme {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(100.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(64.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 4.dp,
                        )
                    }
                }
            }
        } else {
            composeProgress.visibility = View.GONE
        }
    }
}

//package com.faselhd.app
//
//import android.content.Context
//import android.content.Intent
//import android.net.Uri
//import android.os.Bundle
//import android.view.View
//import android.widget.ImageView
//import android.widget.TextView
//import android.widget.Toast
//import androidx.annotation.OptIn
//import androidx.appcompat.app.AlertDialog
//import androidx.appcompat.app.AppCompatActivity
//import androidx.compose.foundation.layout.Box
//import androidx.compose.foundation.layout.padding
//import androidx.compose.foundation.layout.size
//import androidx.compose.material3.CircularProgressIndicator
//import androidx.compose.material3.MaterialTheme
//import androidx.compose.material3.Text
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.platform.ComposeView
//import androidx.compose.ui.unit.dp
//import androidx.lifecycle.lifecycleScope
//import androidx.recyclerview.widget.LinearLayoutManager
//import androidx.recyclerview.widget.RecyclerView
//import com.bumptech.glide.Glide
//import com.example.myapplication.R
//import com.faselhd.app.adapters.EpisodeAdapter
//import com.faselhd.app.adapters.SeasonAdapter
//import com.faselhd.app.db.AppDatabase
//import com.faselhd.app.network.FaselHDSource
//import com.google.android.material.appbar.MaterialToolbar
//import com.google.android.material.button.MaterialButton
//import kotlinx.coroutines.flow.first
//import kotlinx.coroutines.launch
//
//import androidx.work.OneTimeWorkRequestBuilder
//import androidx.work.WorkManager
//import androidx.work.workDataOf
//import com.faselhd.app.models.*
//import com.faselhd.app.workers.DownloadWorker
//import androidx.media3.common.MediaItem
//import androidx.media3.common.util.Log
//import androidx.media3.common.util.UnstableApi
//import androidx.media3.exoplayer.offline.DownloadRequest
//import androidx.media3.exoplayer.offline.DownloadService
//import com.faselhd.app.services.VideoDownloadService // Import the new service
//import com.google.common.util.concurrent.Futures
//import com.google.common.util.concurrent.MoreExecutors
//
//
//class AnimeDetailsActivity : AppCompatActivity() {
//
//    private lateinit var toolbar: MaterialToolbar
//    private lateinit var animeImage: ImageView
//    private lateinit var animeTitle: TextView
//    private lateinit var animeDescription: TextView
//    private lateinit var animeGenre: TextView
//    private lateinit var animeStatus: TextView
//    private lateinit var episodesRecyclerView: RecyclerView
//    private lateinit var watchButton: MaterialButton
//    private lateinit var composeProgress: ComposeView
//    private lateinit var seasonsRecyclerView: RecyclerView
//
//    private lateinit var episodeAdapter: EpisodeAdapter
//    private lateinit var seasonAdapter: SeasonAdapter
//    private lateinit var downloadSeasonButton: MaterialButton
//    private val faselHDSource by lazy { FaselHDSource(applicationContext) }
//    private val db by lazy { AppDatabase.getDatabase(this) }
//
//    private var currentAnime: SAnime? = null
//    private var allEpisodes: List<SEpisode> = emptyList()
//    private var episodesBySeason: Map<String, List<EpisodeWithHistory>> = emptyMap()
//    private var resumeEpisodeUrl: String? = null
//
//    companion object {
//        private const val EXTRA_ANIME = "extra_anime"
//        private const val EXTRA_RESUME_EPISODE_URL = "extra_resume_episode_url"
//
//        fun newIntent(context: Context, anime: SAnime): Intent {
//            return Intent(context, AnimeDetailsActivity::class.java).apply {
//                putExtra(EXTRA_ANIME, anime)
//            }
//        }
//
//        fun newIntentWithResume(context: Context, anime: SAnime, resumeEpisodeUrl: String): Intent {
//            return Intent(context, AnimeDetailsActivity::class.java).apply {
//                putExtra(EXTRA_ANIME, anime)
//                putExtra(EXTRA_RESUME_EPISODE_URL, resumeEpisodeUrl)
//            }
//        }
//    }
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_anime_details)
//
//        // Get the PARTIAL anime object from the intent
//        currentAnime = intent.getParcelableExtra(EXTRA_ANIME)
//        resumeEpisodeUrl = intent.getStringExtra(EXTRA_RESUME_EPISODE_URL)
//        downloadSeasonButton = findViewById(R.id.download_season_button)
//
//        if (currentAnime == null || currentAnime?.url == null) {
//            Toast.makeText(this, "Error: Anime data not found.", Toast.LENGTH_SHORT).show()
//            finish()
//            return
//        }
//
//        initViews()
//        setupToolbar()
//        setupRecyclerViews()
//
//        // Display initial data immediately (like title and image)
//        displayAnimeInfo()
//
//        // *** THIS IS THE NEW LOGIC ***
//        // Now, fetch the full details and episodes from the network
//        loadAnimeDetails() // Fetches description, genre, etc.
//        loadEpisodes()     // Fetches the episode lis
//
//        downloadSeasonButton.setOnClickListener {
//            // Download the currently selected season
//            val currentSeasonName = seasonAdapter.getSelectedSeason()
//            if(currentSeasonName != null) {
//                val episodesToDownload = episodesBySeason[currentSeasonName]?.map { it.episode }
//                if (!episodesToDownload.isNullOrEmpty()) {
//                    Toast.makeText(this, "Queueing ${episodesToDownload.size} episodes for download", Toast.LENGTH_SHORT).show()
//                    episodesToDownload.forEach { startDownload(it) }
//                }
//            }
//        }
//    }
//
//    // *** NEW FUNCTION TO FETCH COMPLETE ANIME DETAILS ***
//    @OptIn(UnstableApi::class)
//    private fun loadAnimeDetails() {
//        showLoading(true)
//        lifecycleScope.launch {
//            try {
//                // Use the URL from the partial anime object to get full details
//                val completeAnime = faselHDSource.fetchAnimeDetails(currentAnime!!.url!!)
//
//                // CRITICAL: Replace the partial anime object with the complete one
//                currentAnime = completeAnime
//
//                // Re-run displayAnimeInfo to populate the description and genre fields
//                displayAnimeInfo()
//
//            } catch (e: Exception) {
//                Log.e("AnimeDetails", "Failed to fetch anime details", e)
//                Toast.makeText(this@AnimeDetailsActivity, "Could not load anime details.", Toast.LENGTH_SHORT).show()
//            } finally {
//                // Ensure loading is hidden, even on failure
//                // We don't hide it here because loadEpisodes() will hide it.
//            }
//        }
//    }
//
//
//    // New function to start a download
//    @OptIn(UnstableApi::class)
//    // Replace the existing startDownload function
//    private fun startDownload(episode: SEpisode) {
//        lifecycleScope.launch {
//            try {
//                val videos = faselHDSource.fetchVideoList(episode.url!!)
//                if (videos.isEmpty()) {
//                    Toast.makeText(this@AnimeDetailsActivity, "Could not find any video links.", Toast.LENGTH_SHORT).show()
//                    return@launch
//                }
//                showDownloadQualityDialog(episode, videos)
//            } catch (e: Exception) {
//                Toast.makeText(this@AnimeDetailsActivity, "Failed to get video list: ${e.message}", Toast.LENGTH_SHORT).show()
//            }
//        }
//    }
//
//    // Add this function back
//    private fun showDownloadQualityDialog(episode: SEpisode, videos: List<Video>) {
//        val qualityOptions = videos.map { it.quality }.toTypedArray()
//
//        AlertDialog.Builder(this)
//            .setTitle("Select Download Quality")
//            .setItems(qualityOptions) { dialog, which ->
//                val selectedVideo = videos[which]
//                Toast.makeText(this, "Queueing download for: ${episode.name} (${selectedVideo.quality})", Toast.LENGTH_SHORT).show()
//
//                val workData = workDataOf(
//                    DownloadWorker.KEY_EPISODE_URL to episode.url!!,
//                    DownloadWorker.KEY_VIDEO_URL to selectedVideo.url, // The specific quality HLS URL
//                    DownloadWorker.KEY_EPISODE_NAME to episode.name,
//                    DownloadWorker.KEY_ANIME_TITLE to currentAnime?.title,
//                    DownloadWorker.KEY_THUMBNAIL_URL to currentAnime?.thumbnail_url
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
//                        downloadState = DownloadState.QUEUED
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
//    // Add this new helper function to the class
//
//
//    // *** THIS IS THE CRITICAL FIX ***
//    // This function is called every time the activity comes back to the foreground.
//    override fun onResume() {
//        super.onResume()
//
//        super.onResume()
//        if (allEpisodes.isNotEmpty()) {
//            lifecycleScope.launch {
//                processAndDisplayEpisodes(allEpisodes)
//            }
//        }
//    }
//
//    private fun initViews() {
//        toolbar = findViewById(R.id.toolbar)
//        animeImage = findViewById(R.id.anime_image)
//        animeTitle = findViewById(R.id.anime_title)
//        animeDescription = findViewById(R.id.anime_description)
//        animeGenre = findViewById(R.id.anime_genre)
//        animeStatus = findViewById(R.id.anime_status)
//        episodesRecyclerView = findViewById(R.id.episodes_recycler_view)
//        watchButton = findViewById(R.id.watch_button)
//        composeProgress = findViewById(R.id.compose_progress)
//        seasonsRecyclerView = findViewById(R.id.seasons_recycler_view)
//    }
//
//    private fun setupToolbar() {
//        setSupportActionBar(toolbar)
//        supportActionBar?.setDisplayHomeAsUpEnabled(true)
//        toolbar.setNavigationOnClickListener { onBackPressed() }
//    }
//
//    private fun setupRecyclerViews() {
//        seasonAdapter = SeasonAdapter { seasonName ->
//            val episodesForSeason = episodesBySeason[seasonName] ?: emptyList()
//            episodeAdapter.submitList(episodesForSeason)
//        }
//        seasonsRecyclerView.apply {
//            layoutManager = LinearLayoutManager(this@AnimeDetailsActivity, LinearLayoutManager.HORIZONTAL, false)
//            adapter = seasonAdapter
//        }
//
//        episodeAdapter = EpisodeAdapter(
//            // Action for clicking the whole item:
//            onClick = { episode ->
//                playEpisode(episode)
//            },
//            // Action for clicking the download icon:
//            onDownloadClick = { episode ->
//                // Give immediate feedback to the user
//                Toast.makeText(this, "Queueing download for: ${episode.name}", Toast.LENGTH_SHORT).show()
//                // Call the download logic function you already created
//                startDownload(episode)
//            }
//        )
//        episodesRecyclerView.apply {
//            layoutManager = LinearLayoutManager(this@AnimeDetailsActivity)
//            adapter = episodeAdapter
//            isNestedScrollingEnabled = false
//        }
//    }
//
//    // The displayAnimeInfo function now works correctly because `currentAnime`
//    // will be updated with the complete data after the network call.
//    private fun displayAnimeInfo() {
//        currentAnime?.let { anime ->
//            // These will be filled immediately
//            animeTitle.text = anime.title
//            Glide.with(this)
//                .load(anime.thumbnail_url)
//                .placeholder(R.drawable.placeholder_anime)
//                .error(R.drawable.placeholder_anime)
//                .into(animeImage)
//
//            // These will be empty at first, then populated after loadAnimeDetails() completes
//            animeDescription.text = anime.description
//            animeGenre.text = "Genre: ${anime.genre}"
//            animeStatus.text = "Status: ${getStatusText(anime.status)}"
//
//            watchButton.setOnClickListener {
//                // Find the first episode that is NOT finished to play.
//                // If all are finished, it will play the very first episode again.
//                val firstUnwatched = episodeAdapter.currentList.firstOrNull { it.history?.isFinished == false }
//                val episodeToPlay = firstUnwatched?.episode ?: allEpisodes.firstOrNull()
//
//                if (episodeToPlay != null) {
//                    playEpisode(episodeToPlay)
//                } else {
//                    Toast.makeText(this, "No episodes available to watch", Toast.LENGTH_SHORT).show()
//                }
//            }
//        }
//    }
//
//    private fun loadEpisodes() {
//        currentAnime?.url?.let { url ->
//            if (allEpisodes.isEmpty()) {
//                showLoading(true)
//                lifecycleScope.launch {
//                    try {
//                        val episodesFromNetwork = faselHDSource.fetchEpisodeList(url)
//                        allEpisodes = episodesFromNetwork
//                        showLoading(false)
//
//                        if (allEpisodes.isNotEmpty()) {
//                            processAndDisplayEpisodes(allEpisodes)
//                            resumeEpisodeUrl?.let { urlToPlay ->
//                                val episodeToPlay = allEpisodes.find { it.url == urlToPlay }
//                                episodeToPlay?.let { playEpisode(it) }
//                                resumeEpisodeUrl = null
//                            }
//                        } else {
//                            Toast.makeText(this@AnimeDetailsActivity, "No episodes available", Toast.LENGTH_SHORT).show()
//                        }
//                    } catch (e: Exception) {
//                        showLoading(false)
//                        Toast.makeText(this@AnimeDetailsActivity, "Error loading episodes: ${e.message}", Toast.LENGTH_LONG).show()
//                    }
//                }
//            }
//        }
//    }
//
//    private suspend fun processAndDisplayEpisodes(allEpisodes: List<SEpisode>) {
//        val historyMap = db.watchHistoryDao().getAllWatchHistory().first()
//            .associateBy { it.episodeUrl }
//
//        val episodesWithHistory = allEpisodes.map { episode ->
//            EpisodeWithHistory(
//                episode = episode,
//                history = historyMap[episode.url]
//            )
//        }
//
//        episodesBySeason = episodesWithHistory.groupBy {
//            it.episode.name?.substringBefore(" : ")?.trim() ?: "Season 1"
//        }
//
//        val seasonNames = episodesBySeason.keys.toList()
//        seasonAdapter.submitList(seasonNames)
//
//        // Find which season is currently selected to maintain the state after refresh
//        val currentlySelectedSeasonName = seasonAdapter.getSelectedSeason() ?: seasonNames.firstOrNull()
//        if (currentlySelectedSeasonName != null) {
//            seasonAdapter.setSelectedSeason(currentlySelectedSeasonName)
//            episodeAdapter.submitList(episodesBySeason[currentlySelectedSeasonName])
//        }
//    }
//
//    private fun playEpisode(episode: SEpisode) {
//        episode.url?.let { episodeUrl ->
//            showLoading(true)
//            lifecycleScope.launch {
//                try {
//                    val seasonName = episode.name?.substringBefore(" : ")?.trim() ?: "Season 1"
//                    val seasonEpisodeList = episodesBySeason[seasonName]?.map { it.episode } ?: emptyList()
//                    val videos = faselHDSource.fetchVideoList(episodeUrl)
//                    val history = db.watchHistoryDao().getWatchHistoryByEpisodeUrl(episodeUrl)
//                    showLoading(false)
//
//                    if (videos.isNotEmpty()) {
//                        val intent = VideoPlayerActivity.newIntent(
//                            context = this@AnimeDetailsActivity,
//                            videos = videos,
//                            anime = currentAnime!!,
//                            currentEpisode = episode,
//                            episodeListForSeason = ArrayList(seasonEpisodeList),
//                            startPosition = history?.lastWatchedPosition ?: 0L
//                        )
//                        startActivity(intent)
//                    } else {
//                        Toast.makeText(this@AnimeDetailsActivity, "Could not find video link", Toast.LENGTH_SHORT).show()
//                    }
//                } catch (e: Exception) {
//                    showLoading(false)
//                    Toast.makeText(this@AnimeDetailsActivity, "Error loading video: ${e.message}", Toast.LENGTH_LONG).show()
//                }
//            }
//        }
//    }
//
//    private fun getStatusText(status: Int): String {
//        return when (status) {
//            SAnime.ONGOING -> getString(R.string.status_ongoing)
//            SAnime.COMPLETED -> getString(R.string.status_completed)
//            else -> getString(R.string.status_unknown)
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
//                            strokeWidth = 4.dp,
//                        )
//                    }
//                }
//            }
//        } else {
//            composeProgress.visibility = View.GONE
//        }
//    }
//}
//
//
//
////package com.faselhd.app
////
////import android.content.Context
////import android.content.Intent
////import android.os.Bundle
////import android.view.View
////import android.widget.ImageView
////import android.widget.TextView
////import android.widget.Toast
////import androidx.appcompat.app.AppCompatActivity
////import androidx.compose.foundation.layout.width
////import androidx.compose.material3.MaterialTheme
////import androidx.compose.ui.Alignment
////import androidx.compose.ui.Modifier
////import androidx.compose.ui.platform.ComposeView
////import androidx.compose.ui.unit.dp
////import androidx.lifecycle.lifecycleScope
////import androidx.recyclerview.widget.LinearLayoutManager
////import androidx.recyclerview.widget.RecyclerView
////import com.bumptech.glide.Glide
////import com.faselhd.app.adapters.EpisodeAdapter
////import com.faselhd.app.models.SAnime
////import com.faselhd.app.models.SEpisode
////import com.example.myapplication.R
////import com.faselhd.app.adapters.SeasonAdapter
////import com.faselhd.app.network.FaselHDSource
////import com.google.android.material.appbar.MaterialToolbar
////import com.google.android.material.button.MaterialButton
////import com.google.android.material.progressindicator.CircularProgressIndicator
////import kotlinx.coroutines.launch
////import androidx.compose.foundation.layout.Box
////import androidx.compose.foundation.layout.padding
////import androidx.compose.foundation.layout.size
////import androidx.compose.material3.CircularProgressIndicator
////import androidx.compose.material3.Text
////import com.faselhd.app.db.AppDatabase
////import com.faselhd.app.models.EpisodeWithHistory
////import kotlinx.coroutines.flow.first
////
////class AnimeDetailsActivity : AppCompatActivity() {
////
////    private lateinit var toolbar: MaterialToolbar
////    private lateinit var animeImage: ImageView
////    private lateinit var animeTitle: TextView
////    private lateinit var animeDescription: TextView
////    private lateinit var animeGenre: TextView
////    private lateinit var animeStatus: TextView
////    private lateinit var episodesRecyclerView: RecyclerView
////    private lateinit var watchButton: MaterialButton
////    private lateinit var progressIndicator: CircularProgressIndicator
////    private lateinit var composeProgress: ComposeView // Add this
////
////    private lateinit var episodeAdapter: EpisodeAdapter
////    private val faselHDSource by lazy { FaselHDSource(applicationContext) }
////
////    private var currentAnime: SAnime? = null
//////    private var episodes: List<SEpisode> = emptyList()
////    private var allEpisodes: List<SEpisode> = emptyList()
//////    private var episodesBySeason: Map<String, List<EpisodeWithHistory>> = emptyMap()
////
////
////    // ++ NEW PROPERTIES ++
////    private lateinit var seasonsRecyclerView: RecyclerView
////    private lateinit var seasonAdapter: SeasonAdapter
////    private var episodesBySeason: Map<String, List<EpisodeWithHistory>> = emptyMap()
////
////
////    // database
////    private val db by lazy { AppDatabase.getDatabase(this) }
////    private var resumeEpisodeUrl: String? = null // Property to hold the resume signal
////
////    companion object {
////        private const val EXTRA_ANIME = "extra_anime"
////        private const val EXTRA_RESUME_EPISODE_URL = "extra_resume_episode_url"
////
////        // Standard intent
////        fun newIntent(context: Context, anime: SAnime): Intent {
////            return Intent(context, AnimeDetailsActivity::class.java).apply {
////                putExtra(EXTRA_ANIME, anime)
////            }
////        }
////
////        // NEW intent for auto-resuming playback
////        fun newIntentWithResume(context: Context, anime: SAnime, resumeEpisodeUrl: String): Intent {
////            return Intent(context, AnimeDetailsActivity::class.java).apply {
////                putExtra(EXTRA_ANIME, anime)
////                putExtra(EXTRA_RESUME_EPISODE_URL, resumeEpisodeUrl)
////            }
////        }
////    }
////
////
////
////
////    override fun onCreate(savedInstanceState: Bundle?) {
////        super.onCreate(savedInstanceState)
////        setContentView(R.layout.activity_anime_details)
////
////        currentAnime = intent.getParcelableExtra(EXTRA_ANIME)
////        // Get the resume URL from the intent, if it exists.
////        resumeEpisodeUrl = intent.getStringExtra(EXTRA_RESUME_EPISODE_URL)
////        if (currentAnime == null) {
////            finish()
////            return
////        }
////
////        initViews()
////        setupToolbar()
////        setupRecyclerViews()
////        displayAnimeInfo()
////        loadEpisodes()
////
////    }
////
////    private fun initViews() {
////        toolbar = findViewById(R.id.toolbar)
////        animeImage = findViewById(R.id.anime_image)
////        animeTitle = findViewById(R.id.anime_title)
////        animeDescription = findViewById(R.id.anime_description)
////        animeGenre = findViewById(R.id.anime_genre)
////        animeStatus = findViewById(R.id.anime_status)
////        episodesRecyclerView = findViewById(R.id.episodes_recycler_view)
////        watchButton = findViewById(R.id.watch_button)
////        composeProgress = findViewById(R.id.compose_progress)
////        seasonsRecyclerView = findViewById(R.id.seasons_recycler_view)
////    }
////
////
////
////    private fun setupToolbar() {
////        setSupportActionBar(toolbar)
////        supportActionBar?.setDisplayHomeAsUpEnabled(true)
////        toolbar.setNavigationOnClickListener { onBackPressed() }
////    }
////
////    private fun setupRecyclerViews() {
////        // --- Setup for Seasons ---
////        seasonAdapter = SeasonAdapter { seasonName ->
////            // This is the click listener. When a season is clicked...
////            // ...get the corresponding episodes from our map...
////            val episodesForSeason = episodesBySeason[seasonName] ?: emptyList()
////            // ...and submit them to the episode adapter.
////            episodeAdapter.submitList(episodesForSeason)
////
////        }
////        seasonsRecyclerView.apply {
////            layoutManager = LinearLayoutManager(this@AnimeDetailsActivity, LinearLayoutManager.HORIZONTAL, false)
////            adapter = seasonAdapter
////        }
////
////        // --- Setup for Episodes (no change here) ---
////        episodeAdapter = EpisodeAdapter { episode ->
////            playEpisode(episode)
////        }
////        episodesRecyclerView.apply {
////            layoutManager = LinearLayoutManager(this@AnimeDetailsActivity)
////            adapter = episodeAdapter
////            isNestedScrollingEnabled = false
////        }
////    }
////
////
////
////
////    private fun displayAnimeInfo() {
////        currentAnime?.let { anime ->
////            animeTitle.text = anime.title
////            animeDescription.text = anime.description ?: "لا يوجد وصف متاح"
////            animeGenre.text = "النوع: ${anime.genre ?: "غير محدد"}"
////            animeStatus.text = "الحالة: ${getStatusText(anime.status)}"
////
////            // Load anime image
////            Glide.with(this)
////                .load(anime.thumbnail_url)
////                .placeholder(R.drawable.placeholder_anime)
////                .error(R.drawable.placeholder_anime)
////                .into(animeImage)
////
////            // Setup watch button
////            watchButton.setOnClickListener {
////                // Use the new allEpisodes list
////                if (allEpisodes.isNotEmpty()) {
////                    playEpisode(allEpisodes.first())
////                } else {
////                    Toast.makeText(this, "No episodes available to watch", Toast.LENGTH_SHORT).show()
////                }
////            }
////        }
////    }
////
////
////
////    private fun loadEpisodes() {
////        currentAnime?.url?.let { url ->
////            // Only fetch from network if the list is empty
////            if (allEpisodes.isEmpty()) {
////                showLoading(true)
////                lifecycleScope.launch {
////                    try {
////                        // Store the result in our class-level variable
////                        val episodesFromNetwork = faselHDSource.fetchEpisodeList(url)
////                        allEpisodes = episodesFromNetwork
////                        showLoading(false)
////
////                        if (allEpisodes.isEmpty()) {
////                            Toast.makeText(this@AnimeDetailsActivity, "No episodes available", Toast.LENGTH_SHORT).show()
////                        } else {
////                            // Process and display for the first time
////                            processAndDisplayEpisodes(allEpisodes)
////                            resumeEpisodeUrl?.let { urlToPlay ->
////                                val episodeToPlay = allEpisodes.find { it.url == urlToPlay }
////                                episodeToPlay?.let {
////                                    playEpisode(it)
////                                }
////                                // Clear the resume URL so it doesn't trigger again on rotation
////                                resumeEpisodeUrl = null
////                            }
////                        }
////                    } catch (e: Exception) {
////                        showLoading(false)
////                        Toast.makeText(this@AnimeDetailsActivity, "Error loading episodes: ${e.message}", Toast.LENGTH_LONG).show()
////                    }
////                }
////            }
////        }
////    }
////
////    // ++ NEW HELPER FUNCTION TO ORGANIZE AND DISPLAY EPISODES ++
////    private suspend fun processAndDisplayEpisodes(allEpisodes: List<SEpisode>) {
////        // Step 1: Get the watch history from the database just once.
////        // USE THE NEW QUERY THAT GETS EVERYTHING
////        val historyMap = db.watchHistoryDao().getAllWatchHistory().first()
////            .associateBy { it.episodeUrl }
////
////        // Step 2: Combine the episode list with its corresponding history.
////        val episodesWithHistory = allEpisodes.map { episode ->
////            EpisodeWithHistory(
////                episode = episode,
////                history = historyMap[episode.url]
////            )
////        }
////
////        // Step 3: NOW, group the combined list by season name. This populates the map.
////        episodesBySeason = episodesWithHistory.groupBy {
////            it.episode.name?.substringBefore(" : ")?.trim() ?: "Season 1" // Added fallback
////        }
////
////        // Step 4: Get the list of season names from the now-populated map.
////        val seasonNames = episodesBySeason.keys.toList()
////
////        // Step 5: Populate the season selector RecyclerView.
////        seasonAdapter.submitList(seasonNames)
////
////        // Step 6: Display the episodes for the VERY FIRST season by default.
////        episodesBySeason.values.firstOrNull()?.let { firstSeasonEpisodes ->
////            episodeAdapter.submitList(firstSeasonEpisodes)
////        }
////    }
////
//////    private fun processAndDisplayEpisodes(allEpisodes: List<SEpisode>) {
//////        // Group the flat list by season name.
//////        // We parse the season name from the episode title (e.g., "الموسم 1 : الحلقة 5")
//////        episodesBySeason = allEpisodes.groupBy { it.name?.substringBefore(" : ").toString().trim() }
//////
//////        // Get the list of season names (e.g., ["الموسم 1", "الموسم 2"])
//////        val seasonNames = episodesBySeason.keys.toList()
//////
//////        // Populate the season selector
//////        seasonAdapter.submitList(seasonNames)
//////
//////        // Display the episodes for the first season by default
//////        episodesBySeason.values.firstOrNull()?.let { firstSeasonEpisodes ->
//////            episodeAdapter.submitList(firstSeasonEpisodes)
//////        }
//////    }
////
////    // In AnimeDetailsActivity.kt
////
////    private fun playEpisode(episode: SEpisode) {
////        episode.url?.let { episodeUrl ->
////            showLoading(true)
////            lifecycleScope.launch {
////                try {
////                    // Find the season this episode belongs to
////                    val seasonName = episode.name?.substringBefore(" : ")?.trim() ?: "Season 1"
////                    // Get the list of all episodes for that specific season
////                    val seasonEpisodeList = episodesBySeason[seasonName]?.map { it.episode } ?: emptyList()
////
////                    val videos = faselHDSource.fetchVideoList(episodeUrl)
////                    val history = db.watchHistoryDao().getWatchHistoryByEpisodeUrl(episodeUrl)
////                    showLoading(false)
////
////                    if (videos.isNotEmpty()) {
////                        val intent = VideoPlayerActivity.newIntent(
////                            context = this@AnimeDetailsActivity,
////                            videos = videos,
////                            anime = currentAnime!!,
////                            currentEpisode = episode,
////                            episodeListForSeason = ArrayList(seasonEpisodeList), // <-- PASS THE LIST
////                            startPosition = history?.lastWatchedPosition ?: 0L
////                        )
////                        startActivity(intent)
////                    } else {
////                        Toast.makeText(this@AnimeDetailsActivity, "Could not find video link", Toast.LENGTH_SHORT).show()
////                    }
////                } catch (e: Exception) {
////                    showLoading(false)
////                    Toast.makeText(this@AnimeDetailsActivity, "Error loading video: ${e.message}", Toast.LENGTH_LONG).show()
////                }
////            }
////        }
////    }
////
////
////
////    private fun getStatusText(status: Int): String {
////        return when (status) {
////            SAnime.ONGOING -> getString(R.string.status_ongoing)
////            SAnime.COMPLETED -> getString(R.string.status_completed)
////            else -> getString(R.string.status_unknown)
////        }
////    }
////
////    private fun showLoading(show: Boolean) {
////        if (show) {
////            composeProgress.visibility = View.VISIBLE
////            composeProgress.setContent {
////                MaterialTheme {
////                    Box(
////                        contentAlignment = Alignment.Center,
////                        modifier = Modifier.size(100.dp)
////                    ) {
////                        CircularProgressIndicator(
////                            modifier = Modifier.size(64.dp),
////                            color = MaterialTheme.colorScheme.primary,
////                            strokeWidth = 4.dp,
////                        )
////
////                        // Optional: Add loading text
////                        Text(
////                            text = "Loading...",
////                            color = MaterialTheme.colorScheme.onPrimary,
////                            modifier = Modifier.padding(top = 80.dp)
////                        )
////                    }
////                }
////            }
////        } else {
////            composeProgress.visibility = View.GONE
////        }
////    }
//////    private fun showLoading(show: Boolean) {
//////        progressIndicator.visibility = if (show) View.VISIBLE else View.GONE
//////    }
////}
////
////
//////package com.faselhd.app
//////
//////import android.content.Context
//////import android.content.Intent
//////import android.os.Bundle
//////import android.view.View
//////import android.widget.ImageView
//////import android.widget.TextView
//////import android.widget.Toast
//////import androidx.appcompat.app.AppCompatActivity
//////import androidx.lifecycle.lifecycleScope
//////import androidx.recyclerview.widget.LinearLayoutManager
//////import androidx.recyclerview.widget.RecyclerView
//////import com.bumptech.glide.Glide
//////import com.example.myapplication.R
//////import com.faselhd.app.adapters.EpisodeAdapter
//////import com.faselhd.app.models.SAnime
//////import com.faselhd.app.models.SEpisode
//////import com.faselhd.app.network.FaselHDSource
//////import com.google.android.material.appbar.MaterialToolbar
//////import com.google.android.material.button.MaterialButton
//////import com.google.android.material.progressindicator.CircularProgressIndicator
//////import kotlinx.coroutines.launch
//////
//////class AnimeDetailsActivity : AppCompatActivity() {
//////
//////    private lateinit var toolbar: MaterialToolbar
//////    private lateinit var animeImage: ImageView
//////    private lateinit var animeTitle: TextView
//////    private lateinit var animeDescription: TextView
//////    private lateinit var animeGenre: TextView
//////    private lateinit var animeStatus: TextView
//////    private lateinit var episodesRecyclerView: RecyclerView
//////    private lateinit var watchButton: MaterialButton
//////    private lateinit var progressIndicator: CircularProgressIndicator
//////
//////    private lateinit var episodeAdapter: EpisodeAdapter
//////    private val faselHDSource by lazy { FaselHDSource(applicationContext) }
//////
//////
//////    private var currentAnime: SAnime? = null
//////    private var episodes: List<SEpisode> = emptyList()
//////
//////    companion object {
//////        private const val EXTRA_ANIME = "extra_anime"
//////
//////        fun newIntent(context: Context, anime: SAnime): Intent {
//////            return Intent(context, AnimeDetailsActivity::class.java).apply {
//////                putExtra(EXTRA_ANIME, anime)
//////            }
//////        }
//////    }
//////
//////    override fun onCreate(savedInstanceState: Bundle?) {
//////        super.onCreate(savedInstanceState)
//////        setContentView(R.layout.activity_anime_details)
//////
//////        currentAnime = intent.getParcelableExtra(EXTRA_ANIME)
//////        if (currentAnime == null) {
//////            finish()
//////            return
//////        }
//////
//////        initViews()
//////        setupToolbar()
//////        setupRecyclerView()
//////        displayAnimeInfo()
//////        loadEpisodes()
//////    }
//////
//////    fun SearchActivity.Companion.navigate(context: Context, block: () -> Unit = {}) {
//////        val intent = Intent(context, SearchActivity::class.java)
//////        context.startActivity(intent)
//////        block()
//////    }
//////
//////    fun AnimeDetailsActivity.Companion.navigate(context: Context, anime: SAnime, block: () -> Unit = {}) {
//////        val intent = Intent(context, AnimeDetailsActivity::class.java).apply {
//////            putExtra(EXTRA_ANIME, anime)
//////        }
//////        context.startActivity(intent)
//////        block()
//////    }
//////
//////    // Create this in a new file like ActivityExtensions.kt
//////
//////    private fun initViews() {
//////        toolbar = findViewById(R.id.toolbar)
//////        animeImage = findViewById(R.id.anime_image)
//////        animeTitle = findViewById(R.id.anime_title)
//////        animeDescription = findViewById(R.id.anime_description)
//////        animeGenre = findViewById(R.id.anime_genre)
//////        animeStatus = findViewById(R.id.anime_status)
//////        episodesRecyclerView = findViewById(R.id.episodes_recycler_view)
//////        watchButton = findViewById(R.id.watch_button)
//////        progressIndicator = findViewById(R.id.progress_indicator)
//////    }
//////
//////    private fun setupToolbar() {
//////        setSupportActionBar(toolbar)
//////        supportActionBar?.setDisplayHomeAsUpEnabled(true)
//////        toolbar.setNavigationOnClickListener { onBackPressed() }
//////    }
//////
//////    private fun setupRecyclerView() {
//////        episodeAdapter = EpisodeAdapter { episode ->
//////            playEpisode(episode)
//////        }
//////        episodesRecyclerView.apply {
//////            layoutManager = LinearLayoutManager(this@AnimeDetailsActivity)
//////            adapter = episodeAdapter
//////        }
//////    }
//////
//////    private fun displayAnimeInfo() {
//////        currentAnime?.let { anime ->
//////            animeTitle.text = anime.title
//////            animeDescription.text = anime.description ?: "لا يوجد وصف متاح"
//////            animeGenre.text = "النوع: ${anime.genre ?: "غير محدد"}"
//////            animeStatus.text = "الحالة: ${getStatusText(anime.status)}"
//////
//////            // Load anime image
//////            Glide.with(this)
//////                .load(anime.thumbnail_url)
//////                .placeholder(R.drawable.placeholder_anime)
//////                .error(R.drawable.placeholder_anime)
//////                .into(animeImage)
//////
//////            // Setup watch button
//////            watchButton.setOnClickListener {
//////                if (episodes.isNotEmpty()) {
//////                    playEpisode(episodes.first())
//////                } else {
//////                    Toast.makeText(this, "لا توجد حلقات متاحة", Toast.LENGTH_SHORT).show()
//////                }
//////            }
//////        }
//////    }
//////
//////    private fun loadEpisodes() {
//////        currentAnime?.url?.let { url ->
//////            showLoading(true)
//////
//////            lifecycleScope.launch {
//////                try {
//////                    episodes = faselHDSource.fetchEpisodeList(url)
//////                    episodeAdapter.submitList(episodes)
//////                    showLoading(false)
//////
//////                    if (episodes.isEmpty()) {
//////                        Toast.makeText(this@AnimeDetailsActivity, "لا توجد حلقات متاحة", Toast.LENGTH_SHORT).show()
//////                    }
//////                } catch (e: Exception) {
//////                    showLoading(false)
//////                    Toast.makeText(this@AnimeDetailsActivity, "خطأ في تحميل الحلقات: ${e.message}", Toast.LENGTH_LONG).show()
//////                }
//////            }
//////        }
//////    }
//////
//////    private fun playEpisode(episode: SEpisode) {
//////        episode.url?.let { episodeUrl ->
//////            showLoading(true)
//////
//////            lifecycleScope.launch {
//////                try {
//////                    val videos = faselHDSource.fetchVideoList(episodeUrl)
//////                    println("videos323344dc : ${videos.toString()}")
//////                    showLoading(false)
//////
//////                    if (videos.isNotEmpty()) {
//////                        val videoUrl = videos.first().url
//////                        // The episode page URL is the perfect referer
//////                        val referer = episode.url ?: ""
//////                        val intent = VideoPlayerActivity.newIntent(
//////                            this@AnimeDetailsActivity,
//////                            videoUrl,
//////                            episode.name ?: "حلقة",
//////                            referer // <-- Pass the referer here
//////                        )
//////                        startActivity(intent)
//////                    }  else {
//////                        Toast.makeText(this@AnimeDetailsActivity, "لا يمكن العثور على رابط الفيديو", Toast.LENGTH_SHORT).show()
//////                    }
//////                } catch (e: Exception) {
//////                    showLoading(false)
//////                    Toast.makeText(this@AnimeDetailsActivity, "خطأ في تحميل الفيديو: ${e.message}", Toast.LENGTH_LONG).show()
//////                }
//////            }
//////        }
//////    }
////////private fun playEpisode(episode: SEpisode) {
////////    episode.url?.let { episodeUrl ->
////////        showLoading(true)
////////
////////        lifecycleScope.launch {
////////            try {
////////                val videos = faselHDSource.fetchVideoList(episodeUrl)
////////                println("videos323344dc : ${videos.toString()}")
////////                showLoading(false)
////////
////////                if (videos.isNotEmpty()) {
////////                    val videoUrl = videos.first().url
////////                    // The episode page URL is the perfect referer
////////                    val referer = episode.url ?: ""
////////
////////                    // Use the new VideoPlayerHelper to start the player
////////                    VideoPlayerHelper.startVideoPlayer(
////////                        context = this@AnimeDetailsActivity,
////////                        videoUrl = videoUrl,
////////                        videoTitle = episode.name ?: "حلقة",
////////                        // Pass the referer if you have modified VideoPlayerHelper
////////                        // to accept it, as discussed in the integration guide.
////////                        // If not, you might need to adapt VideoPlayerHelper or
////////                        // handle the referer within your data source factory.
////////                        // For now, assuming VideoPlayerHelper is updated to accept referer:
////////                        // referer = referer
////////                    )
////////                }  else {
////////                    Toast.makeText(this@AnimeDetailsActivity, "لا يمكن العثور على رابط الفيديو", Toast.LENGTH_SHORT).show()
////////                }
////////            } catch (e: Exception) {
////////                showLoading(false)
////////                Toast.makeText(this@AnimeDetailsActivity, "خطأ في تحميل الفيديو: ${e.message}", Toast.LENGTH_LONG).show()
////////            }
////////        }
////////    }
////////}
//////    private fun getStatusText(status: Int): String {
//////        return when (status) {
//////            SAnime.ONGOING -> getString(R.string.status_ongoing)
//////            SAnime.COMPLETED -> getString(R.string.status_completed)
//////            else -> getString(R.string.status_unknown)
//////        }
//////    }
//////
//////    private fun showLoading(show: Boolean) {
//////        progressIndicator.visibility = if (show) View.VISIBLE else View.GONE
//////    }
//////}
//////
