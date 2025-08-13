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
import com.faselhd.app.models.Favorite
import com.google.android.material.button.MaterialButton // Ensure this is the correct import
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

    private lateinit var addToListButton: MaterialButton // Add this
    private var isFavorite = false // Add this to track state

    private lateinit var downloadSeasonButton: MaterialButton // Add this

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
        setupDownloadSeasonButton()
        displayAnimeInfo()
        checkIfFavorite()
        setupFavoriteButtonListener()
        loadEpisodes()
    }


    private fun setupDownloadSeasonButton() {
        downloadSeasonButton.setOnClickListener {
            // Get the name of the currently selected season from the adapter
            val selectedSeasonName = seasonAdapter.getSelectedSeason()
            if (selectedSeasonName == null) {
                Toast.makeText(this, "Please select a season first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Get the list of episodes for that season
            val episodesForSeason = episodesBySeason[selectedSeasonName]
            if (episodesForSeason.isNullOrEmpty()) {
                Toast.makeText(this, "No episodes found for this season", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Show a confirmation dialog to the user
            AlertDialog.Builder(this)
                .setTitle("Download Season")
                .setMessage("Are you sure you want to download all ${episodesForSeason.size} episodes for '$selectedSeasonName'?")
                .setPositiveButton("Download") { dialog, _ ->
                    queueSeasonForDownload(episodesForSeason.map { it.episode })
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun queueSeasonForDownload(episodes: List<SEpisode>) {
        lifecycleScope.launch {
            Toast.makeText(this@AnimeDetailsActivity, "Queueing season for download...", Toast.LENGTH_LONG).show()

            for (episode in episodes) {
                val existingDownload = db.downloadDao().getDownload(episode.url!!)
                if (existingDownload != null && existingDownload.downloadState != DownloadState.FAILED) {
                    continue
                }

                val workData = workDataOf(
                    DownloadWorker.KEY_EPISODE_URL to episode.url!!,
                    DownloadWorker.KEY_VIDEO_URL to null,
                    DownloadWorker.KEY_EPISODE_NAME to episode.name,
                    DownloadWorker.KEY_ANIME_TITLE to currentAnime?.title,
                    DownloadWorker.KEY_THUMBNAIL_URL to currentAnime?.thumbnail_url
                )

                val downloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setInputData(workData)
                    .addTag(episode.url!!)
                    .build()

                WorkManager.getInstance(this@AnimeDetailsActivity).enqueue(downloadWorkRequest)

                // **** THIS IS THE FIX ****
                // Create the database entry with a null mediaUri for now.
                val downloadEntry = Download(
                    episodeUrl = episode.url!!,
                    animeTitle = currentAnime?.title ?: "",
                    episodeName = episode.name,
                    thumbnailUrl = currentAnime?.thumbnail_url,
                    downloadState = DownloadState.QUEUED,
                    mediaUri = null // It's now nullable, so this is valid.
                )
                db.downloadDao().upsert(downloadEntry)
            }
        }
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
        addToListButton = findViewById(R.id.btn_add_to_list) // Initialize the new button
        downloadSeasonButton = findViewById(R.id.download_season_button) // Add this line
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressed() }
    }

    private fun checkIfFavorite() {
        lifecycleScope.launch {
            val favorite = db.favoriteDao().getFavoriteByUrl(currentAnime!!.url!!)
            isFavorite = favorite != null
            updateFavoriteButtonUI()
        }
    }

    private fun updateFavoriteButtonUI() {
        if (isFavorite) {
            addToListButton.text = "إزالة من قائمتي"
             addToListButton.setIconResource(R.drawable.done_all_24px) // Example icon change
        } else {
            addToListButton.text = "أضف إلى قائمتي"
             addToListButton.setIconResource(R.drawable.add_24px) // Example icon change
        }
    }

    private fun setupFavoriteButtonListener() {
        addToListButton.setOnClickListener {
            lifecycleScope.launch {
                val anime = currentAnime!!
                if (isFavorite) {
                    // It's a favorite, so delete it
                    db.favoriteDao().delete(anime.url!!)
                    Toast.makeText(this@AnimeDetailsActivity, "تمت إزالته من قائمتي", Toast.LENGTH_SHORT).show()
                } else {
                    // It's not a favorite, so add it
                    val newFavorite = Favorite(
                        animeUrl = anime.url!!,
                        title = anime.title,
                        thumbnailUrl = anime.thumbnail_url
                    )
                    db.favoriteDao().insert(newFavorite)
                    Toast.makeText(this@AnimeDetailsActivity, "تمت إضافته إلى قائمتي", Toast.LENGTH_SHORT).show()
                }
                // Toggle the state and update the UI
                isFavorite = !isFavorite
                updateFavoriteButtonUI()
            }
        }
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

