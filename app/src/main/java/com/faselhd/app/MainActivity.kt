package com.faselhd.app

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.faselhd.app.widgets.GridSpacingItemDecoration
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.faselhd.app.adapters.AnimeAdapter
import com.faselhd.app.adapters.ContinueWatchingAdapter
import com.faselhd.app.adapters.SliderAdapter
import com.faselhd.app.db.AppDatabase
import com.faselhd.app.models.SAnime
import com.faselhd.app.models.WatchHistory
import com.faselhd.app.models.Favorite
import com.faselhd.app.network.SourceManager
import com.example.myapplication.R
import com.facebook.shimmer.ShimmerFrameLayout
import com.faselhd.app.network.AnimeSource
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class MainActivity : AppCompatActivity() {

    // ... (keep all your existing view declarations as they are)
    private lateinit var mainSliderViewPager: ViewPager2
    private lateinit var bottomNavigationView: BottomNavigationView
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar

    // Featured anime views
    private lateinit var featuredAnimeTitle: TextView
    private lateinit var featuredAnimeGenre: TextView
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnMyList: MaterialButton

    // Recycler Views
    private lateinit var continueWatchingSection: LinearLayout
    private lateinit var continueWatchingRecyclerView: RecyclerView
    private lateinit var topHitsRecyclerView: RecyclerView
    private lateinit var newEpisodesRecyclerView: RecyclerView
    private lateinit var latestRecyclerView: RecyclerView

    // Adapters
    private lateinit var sliderAdapter: SliderAdapter
    private lateinit var continueWatchingAdapter: ContinueWatchingAdapter
    private lateinit var topHitsAdapter: AnimeAdapter
    private lateinit var newEpisodesAdapter: AnimeAdapter
    private lateinit var latestAdapter: AnimeAdapter

    // Utilities
    private val sourceManager by lazy { SourceManager(applicationContext) }
    private val db by lazy { AppDatabase.getDatabase(this) }
    private val sliderHandler = Handler(Looper.getMainLooper())
    private lateinit var sliderRunnable: Runnable

    private lateinit var seeAllContinueWatching: TextView
    private lateinit var seeAllTopHits: TextView
    private lateinit var seeAllNewEpisodes: TextView
    private lateinit var seeAllLatest: TextView

    private lateinit var shimmerTopHits: ShimmerFrameLayout
    private lateinit var shimmerNewEpisodes: ShimmerFrameLayout
    private lateinit var shimmerLatestUpdates: ShimmerFrameLayout
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    // Current featured anime
    private var currentFeaturedAnime: SAnime? = null
    private var currentFeaturedSource: AnimeSource? = null

    // D-pad navigation variables
    private var currentFocusedSection = FocusSection.FEATURED_BUTTONS
    private var currentButtonIndex = 0
    private var currentRecyclerPosition = 0
    private var currentBottomNavIndex = 0

    // Focusable views lists for D-pad navigation
    private lateinit var featuredButtons: List<View>
    private lateinit var seeAllButtons: List<TextView>
    private lateinit var recyclerViews: List<RecyclerView>
    private lateinit var bottomNavItems: List<View>

    // Define focus sections for D-pad navigation
    private enum class FocusSection {
        FEATURED_BUTTONS,
        CONTINUE_WATCHING,
        TOP_HITS,
        NEW_EPISODES,
        LATEST_UPDATES,
        BOTTOM_NAVIGATION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupToolbar()
        setupRecyclerViews()
        setupBottomNavigation()
        setupSeeAllButtons()
        setupFeaturedButtons()
        setupSwipeRefresh()
        setupDpadNavigation()
        loadData()
        observeWatchHistory()
    }

    // Setup D-pad navigation
    private fun setupDpadNavigation() {
        // Initialize focusable views lists
        featuredButtons = listOf(btnPlay, btnMyList)
        seeAllButtons = listOf(seeAllContinueWatching, seeAllTopHits, seeAllNewEpisodes, seeAllLatest)
        recyclerViews = listOf(continueWatchingRecyclerView, topHitsRecyclerView, newEpisodesRecyclerView, latestRecyclerView)

        // Setup focus change listeners for visual feedback
        setupFocusListeners()

        // Set initial focus
        setInitialFocus()
    }

    private fun setupFocusListeners() {
        // Featured buttons focus listeners
        featuredButtons.forEach { button ->
            button.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.scaleX = 1.1f
                    view.scaleY = 1.1f
                    currentFocusedSection = FocusSection.FEATURED_BUTTONS
                    currentButtonIndex = featuredButtons.indexOf(view)
                } else {
                    view.scaleX = 1.0f
                    view.scaleY = 1.0f
                }
            }
        }

        // See all buttons focus listeners
        seeAllButtons.forEach { button ->
            button.setOnFocusChangeListener { view, hasFocus ->
                val btn = view as Button
                if (hasFocus) {
                    view.setBackgroundColor(resources.getColor(R.color.green_play_button, theme))
                    view.setTextColor(resources.getColor(android.R.color.white, theme))
                } else {
                    view.background = null
                    view.setTextColor(resources.getColor(R.color.green_play_button, theme))
                }
            }
        }

        // RecyclerView focus listeners
        recyclerViews.forEachIndexed { index, recyclerView ->
            recyclerView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    currentFocusedSection = when (index) {
                        0 -> FocusSection.CONTINUE_WATCHING
                        1 -> FocusSection.TOP_HITS
                        2 -> FocusSection.NEW_EPISODES
                        3 -> FocusSection.LATEST_UPDATES
                        else -> FocusSection.TOP_HITS
                    }
                    highlightRecyclerView(recyclerView, hasFocus)
                }
            }
        }

        // Bottom navigation focus setup
        for (i in 0 until bottomNavigationView.menu.size()) {
            val menuItem = bottomNavigationView.menu.getItem(i)
            // You might need to get the actual view from the BottomNavigationView
            // This depends on your specific implementation
        }
    }

    private fun setInitialFocus() {
        // Set initial focus to the first featured button
        btnPlay.requestFocus()
    }

    private fun highlightRecyclerView(recyclerView: RecyclerView, hasFocus: Boolean) {
        if (hasFocus) {
            recyclerView.setBackgroundColor(resources.getColor(R.color.green_play_button, theme))
            recyclerView.alpha = 0.8f
        } else {
            recyclerView.background = null
            recyclerView.alpha = 1.0f
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                handleDpadUp()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                handleDpadDown()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                handleDpadLeft()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                handleDpadRight()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                handleDpadCenter()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                handleBackButton()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun handleDpadUp(): Boolean {
        return when (currentFocusedSection) {
            FocusSection.CONTINUE_WATCHING -> {
                focusOnSection(FocusSection.FEATURED_BUTTONS)
                true
            }
            FocusSection.TOP_HITS -> {
                if (continueWatchingSection.visibility == View.VISIBLE) {
                    focusOnSection(FocusSection.CONTINUE_WATCHING)
                } else {
                    focusOnSection(FocusSection.FEATURED_BUTTONS)
                }
                true
            }
            FocusSection.NEW_EPISODES -> {
                focusOnSection(FocusSection.TOP_HITS)
                true
            }
            FocusSection.LATEST_UPDATES -> {
                focusOnSection(FocusSection.NEW_EPISODES)
                true
            }
            FocusSection.BOTTOM_NAVIGATION -> {
                focusOnSection(FocusSection.LATEST_UPDATES)
                true
            }
            else -> false
        }
    }

    private fun handleDpadDown(): Boolean {
        return when (currentFocusedSection) {
            FocusSection.FEATURED_BUTTONS -> {
                if (continueWatchingSection.visibility == View.VISIBLE) {
                    focusOnSection(FocusSection.CONTINUE_WATCHING)
                } else {
                    focusOnSection(FocusSection.TOP_HITS)
                }
                true
            }
            FocusSection.CONTINUE_WATCHING -> {
                focusOnSection(FocusSection.TOP_HITS)
                true
            }
            FocusSection.TOP_HITS -> {
                focusOnSection(FocusSection.NEW_EPISODES)
                true
            }
            FocusSection.NEW_EPISODES -> {
                focusOnSection(FocusSection.LATEST_UPDATES)
                true
            }
            FocusSection.LATEST_UPDATES -> {
                focusOnSection(FocusSection.BOTTOM_NAVIGATION)
                true
            }
            else -> false
        }
    }

    private fun handleDpadLeft(): Boolean {
        return when (currentFocusedSection) {
            FocusSection.FEATURED_BUTTONS -> {
                if (currentButtonIndex > 0) {
                    currentButtonIndex--
                    featuredButtons[currentButtonIndex].requestFocus()
                } else {
                    // Move slider to previous item
                    val currentItem = mainSliderViewPager.currentItem
                    if (currentItem > 0) {
                        mainSliderViewPager.currentItem = currentItem - 1
                    }
                }
                true
            }
            FocusSection.CONTINUE_WATCHING,
            FocusSection.TOP_HITS,
            FocusSection.NEW_EPISODES -> {
                scrollRecyclerViewHorizontally(getCurrentRecyclerView(), -1)
                true
            }
            FocusSection.LATEST_UPDATES -> {
                navigateGridLeft()
                true
            }
            FocusSection.BOTTOM_NAVIGATION -> {
                navigateBottomNavigation(-1)
                true
            }
            else -> false
        }
    }

    private fun handleDpadRight(): Boolean {
        return when (currentFocusedSection) {
            FocusSection.FEATURED_BUTTONS -> {
                if (currentButtonIndex < featuredButtons.size - 1) {
                    currentButtonIndex++
                    featuredButtons[currentButtonIndex].requestFocus()
                } else {
                    // Move slider to next item
                    val currentItem = mainSliderViewPager.currentItem
                    val itemCount = if (::sliderAdapter.isInitialized) sliderAdapter.itemCount else 0
                    if (currentItem < itemCount - 1) {
                        mainSliderViewPager.currentItem = currentItem + 1
                    }
                }
                true
            }
            FocusSection.CONTINUE_WATCHING,
            FocusSection.TOP_HITS,
            FocusSection.NEW_EPISODES -> {
                scrollRecyclerViewHorizontally(getCurrentRecyclerView(), 1)
                true
            }
            FocusSection.LATEST_UPDATES -> {
                navigateGridRight()
                true
            }
            FocusSection.BOTTOM_NAVIGATION -> {
                navigateBottomNavigation(1)
                true
            }
            else -> false
        }
    }

    private fun handleDpadCenter(): Boolean {
        return when (currentFocusedSection) {
            FocusSection.FEATURED_BUTTONS -> {
                featuredButtons[currentButtonIndex].performClick()
                true
            }
            FocusSection.CONTINUE_WATCHING,
            FocusSection.TOP_HITS,
            FocusSection.NEW_EPISODES,
            FocusSection.LATEST_UPDATES -> {
                clickCurrentRecyclerViewItem()
                true
            }
            FocusSection.BOTTOM_NAVIGATION -> {
                selectBottomNavigationItem()
                true
            }
            else -> false
        }
    }

    private fun handleBackButton(): Boolean {
        // Handle back button press - you can customize this behavior
        finish()
        return true
    }

    private fun focusOnSection(section: FocusSection) {
        currentFocusedSection = section
        when (section) {
            FocusSection.FEATURED_BUTTONS -> {
                featuredButtons[currentButtonIndex].requestFocus()
            }
            FocusSection.CONTINUE_WATCHING -> {
                if (continueWatchingSection.visibility == View.VISIBLE) {
                    continueWatchingRecyclerView.requestFocus()
                    currentRecyclerPosition = 0
                }
            }
            FocusSection.TOP_HITS -> {
                topHitsRecyclerView.requestFocus()
                currentRecyclerPosition = 0
            }
            FocusSection.NEW_EPISODES -> {
                newEpisodesRecyclerView.requestFocus()
                currentRecyclerPosition = 0
            }
            FocusSection.LATEST_UPDATES -> {
                latestRecyclerView.requestFocus()
                currentRecyclerPosition = 0
            }
            FocusSection.BOTTOM_NAVIGATION -> {
                // Focus on bottom navigation
                bottomNavigationView.requestFocus()
            }
        }
    }

    private fun getCurrentRecyclerView(): RecyclerView {
        return when (currentFocusedSection) {
            FocusSection.CONTINUE_WATCHING -> continueWatchingRecyclerView
            FocusSection.TOP_HITS -> topHitsRecyclerView
            FocusSection.NEW_EPISODES -> newEpisodesRecyclerView
            FocusSection.LATEST_UPDATES -> latestRecyclerView
            else -> topHitsRecyclerView
        }
    }

    private fun scrollRecyclerViewHorizontally(recyclerView: RecyclerView, direction: Int) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
        layoutManager?.let {
            val currentPosition = it.findFirstVisibleItemPosition()
            val newPosition = (currentPosition + direction).coerceAtLeast(0)

            val adapter = recyclerView.adapter
            if (adapter != null && newPosition < adapter.itemCount) {
                recyclerView.smoothScrollToPosition(newPosition)
                currentRecyclerPosition = newPosition
            }
        }
    }

    private fun navigateGridLeft() {
        val layoutManager = latestRecyclerView.layoutManager as? GridLayoutManager
        layoutManager?.let {
            val spanCount = it.spanCount
            val newPosition = (currentRecyclerPosition - 1).coerceAtLeast(0)
            if (newPosition >= 0) {
                latestRecyclerView.smoothScrollToPosition(newPosition)
                currentRecyclerPosition = newPosition
            }
        }
    }

    private fun navigateGridRight() {
        val layoutManager = latestRecyclerView.layoutManager as? GridLayoutManager
        layoutManager?.let {
            val adapter = latestRecyclerView.adapter
            if (adapter != null) {
                val newPosition = (currentRecyclerPosition + 1).coerceAtMost(adapter.itemCount - 1)
                latestRecyclerView.smoothScrollToPosition(newPosition)
                currentRecyclerPosition = newPosition
            }
        }
    }

    private fun navigateBottomNavigation(direction: Int) {
        val menuSize = bottomNavigationView.menu.size()
        currentBottomNavIndex = ((currentBottomNavIndex + direction) + menuSize) % menuSize

        // Highlight the selected item (visual feedback)
        bottomNavigationView.menu.getItem(currentBottomNavIndex).isChecked = true
    }

    private fun selectBottomNavigationItem() {
        val selectedItem = bottomNavigationView.menu.getItem(currentBottomNavIndex)
        bottomNavigationView.selectedItemId = selectedItem.itemId

        // Trigger the navigation
        when (selectedItem.itemId) {
            R.id.nav_home -> {
                // Already on home
            }
            R.id.nav_my_list -> {
                startActivity(Intent(this, MyListActivity::class.java))
            }
            R.id.nav_search -> {
                val intent = ParentSearchActivity.newIntent(this)
                startActivity(intent)
            }
            R.id.nav_download -> {
                startActivity(Intent(this, DownloadsActivity::class.java))
            }
            R.id.nav_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
    }

    private fun clickCurrentRecyclerViewItem() {
        val recyclerView = getCurrentRecyclerView()
        val layoutManager = recyclerView.layoutManager

        when (layoutManager) {
            is LinearLayoutManager -> {
                val viewHolder = recyclerView.findViewHolderForAdapterPosition(currentRecyclerPosition)
                viewHolder?.itemView?.performClick()
            }
            is GridLayoutManager -> {
                val viewHolder = recyclerView.findViewHolderForAdapterPosition(currentRecyclerPosition)
                viewHolder?.itemView?.performClick()
            }
        }
    }

    // Add methods to show visual feedback for TV navigation
    private fun showNavigationHint(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // Override existing methods with TV-friendly modifications
    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        mainSliderViewPager = findViewById(R.id.main_slider_view_pager)
        bottomNavigationView = findViewById(R.id.bottom_navigation)

        featuredAnimeTitle = findViewById(R.id.featured_anime_title)
        featuredAnimeGenre = findViewById(R.id.featured_anime_genre)
        btnPlay = findViewById(R.id.btn_play)
        btnMyList = findViewById(R.id.btn_my_list)

        continueWatchingSection = findViewById(R.id.continue_watching_section)
        continueWatchingRecyclerView = findViewById(R.id.continue_watching_recycler_view)
        topHitsRecyclerView = findViewById(R.id.top_hits_recycler_view)
        newEpisodesRecyclerView = findViewById(R.id.new_episodes_recycler_view)
        latestRecyclerView = findViewById(R.id.latest_recycler_view)

        seeAllContinueWatching = findViewById(R.id.see_all_continue_watching)
        seeAllTopHits = findViewById(R.id.see_all_top_hits)
        seeAllNewEpisodes = findViewById(R.id.see_all_new_episodes)
        seeAllLatest = findViewById(R.id.see_all_latest)

        shimmerTopHits = findViewById(R.id.shimmer_top_hits)
        shimmerNewEpisodes = findViewById(R.id.shimmer_new_episodes)
        shimmerLatestUpdates = findViewById(R.id.shimmer_latest_updates)
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)

        // Make views focusable for TV navigation
        btnPlay.isFocusable = true
        btnMyList.isFocusable = true
        continueWatchingRecyclerView.isFocusable = true
        topHitsRecyclerView.isFocusable = true
        newEpisodesRecyclerView.isFocusable = true
        latestRecyclerView.isFocusable = true
        bottomNavigationView.isFocusable = true
    }

    private fun setupFeaturedButtons() {
        btnPlay.setOnClickListener {
            currentFeaturedAnime?.let { anime ->
                handlePlayButtonClick(anime)
            } ?: run {
                Toast.makeText(this, "No anime selected", Toast.LENGTH_SHORT).show()
            }
        }

        btnMyList.setOnClickListener {
            currentFeaturedAnime?.let { anime ->
                addToMyList(anime)
            } ?: run {
                Toast.makeText(this, "No anime selected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ENHANCED: Handle play button click with comprehensive error handling
    private fun handlePlayButtonClick(anime: SAnime) {
        if (currentFeaturedAnime == null) {
            Toast.makeText(this, "No anime selected", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                // Check network connectivity
                if (!isNetworkAvailable()) {
                    Toast.makeText(this@MainActivity, "No internet connection. Please check your network settings.", Toast.LENGTH_LONG).show()
                    return@launch
                }

                showMainLoading(true)

                // Check if there's recent watch history for this anime
                val recentHistory = db.watchHistoryDao().getRecentWatchHistoryForAnime(anime.url!!)

                if (recentHistory != null && recentHistory.lastWatchedPosition > 0) {
                    // There's watch history - resume from that episode
                    Toast.makeText(this@MainActivity, "Resuming from where you left off...", Toast.LENGTH_SHORT).show()
                    val intent = AnimeDetailsActivity.newIntentWithResume(
                        context = this@MainActivity,
                        anime = anime,
                        resumeEpisodeUrl = recentHistory.episodeUrl,
                        source = currentFeaturedSource
                    )
                    startActivity(intent)
                    overridePendingTransition(R.anim.scale_in, R.anim.fade_out)
                } else {
                    // No watch history - fetch episodes and play first one
                    Toast.makeText(this@MainActivity, "Starting from Episode 1...", Toast.LENGTH_SHORT).show()

                    try {
                        val episodes = sourceManager.fetchEpisodeList(anime.url!!, currentFeaturedSource)

                        if (episodes.isNotEmpty()) {
                            val firstEpisode = episodes.first()

                            // Get video links for the first episode
                            val videos = sourceManager.fetchVideoList(firstEpisode.url!!, currentFeaturedSource)

                            if (videos.isNotEmpty()) {
                                // Start video player directly
                                val intent = VideoPlayerActivity.newIntent(
                                    context = this@MainActivity,
                                    videos = videos,
                                    anime = anime,
                                    currentEpisode = firstEpisode,
                                    episodeListForSeason = ArrayList(episodes),
                                    startPosition = 0L,
                                    source = currentFeaturedSource
                                )
                                startActivity(intent)
                            } else {
                                Toast.makeText(this@MainActivity, "No video sources available for this episode", Toast.LENGTH_SHORT).show()
                                // Fallback to anime details
                                openAnimeDetailsAsFallback(anime)
                            }
                        } else {
                            Toast.makeText(this@MainActivity, "No episodes found for this anime", Toast.LENGTH_SHORT).show()
                            // Fallback to anime details
                            openAnimeDetailsAsFallback(anime)
                        }
                    } catch (e: Exception) {
                        handlePlaybackError(e, anime)
                    }
                }
            } catch (e: Exception) {
                showMainLoading(false)
                handlePlaybackError(e, anime)
            } finally {
                showMainLoading(false)
            }
        }
    }

    // ENHANCED: loadData with comprehensive error handling
    private fun loadData() {
        showLoading(true)
        lifecycleScope.launch {
            try {
                // Check network connectivity first
                if (!isNetworkAvailable()) {
                    showError("No internet connection. Please check your network settings.")
                    showLoading(false)
                    showOfflineState()
                    return@launch
                }

                val sliderJob = async {
                    try {
                        sourceManager.fetchMainSlider()
                    } catch (e: Exception) {
                        emptyList<SAnime>() // Return empty list on failure
                    }
                }

                val popularJob = async { sourceManager.fetchPopularSeries(1) }
                val latestEpisodesJob = async { sourceManager.fetchHomePageLatestEpisodes() }
                val latestUpdatesJob = async { sourceManager.fetchLatestUpdates(1) }

                val sliderItems = sliderJob.await()
                val popularSeries = popularJob.await()
                val latestEpisodes = latestEpisodesJob.await()
                val latestUpdates = latestUpdatesJob.await()

                if (!isActive) return@launch

                // Handle slider setup with error checking
                if (sliderItems.isNotEmpty()) {
                    sliderAdapter = SliderAdapter(sliderItems) { anime -> openAnimeDetails(anime) }
                    mainSliderViewPager.adapter = sliderAdapter
                    setupAutoSwipe(sliderAdapter)
                    updateFeaturedAnime(sliderItems[0], null)
                } else {
                    // Handle empty slider case
                    handleEmptySlider()
                }

                // Safely submit lists with null checks
                topHitsAdapter.submitList(popularSeries.manga.take(10))
                newEpisodesAdapter.submitList(latestEpisodes.take(10))
                latestAdapter.submitList(latestUpdates.manga.take(20))

                showLoading(false)

                // Show partial data warning if some sections are empty
                if (sliderItems.isEmpty() && popularSeries.manga.isEmpty() &&
                    latestEpisodes.isEmpty() && latestUpdates.manga.isEmpty()) {
                    showError("Unable to load content. Please check your internet connection and try again.")
                } else if (sliderItems.isEmpty() || popularSeries.manga.isEmpty() ||
                    latestEpisodes.isEmpty() || latestUpdates.manga.isEmpty()) {
                    showWarning("Some content could not be loaded. Pull to refresh to try again.")
                }

            } catch (e: Exception) {
                if (isActive) {
                    when (e) {
                        is UnknownHostException -> {
                            showError("No internet connection. Please check your network settings.")
                        }
                        is SocketTimeoutException -> {
                            showError("Connection timeout. Please try again.")
                        }
                        is ConnectException -> {
                            showError("Unable to connect to server. Please try again later.")
                        }
                        else -> {
                            showError("Error loading data: ${e.localizedMessage ?: "Unknown error occurred"}")
                        }
                    }
                    showLoading(false)
                    showOfflineState()
                }
            }
        }
    }

    // Helper methods for better error handling
    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    private fun handlePlaybackError(e: Exception, anime: SAnime) {
        val errorMessage = when (e) {
            is UnknownHostException -> "No internet connection"
            is SocketTimeoutException -> "Connection timeout. Please try again."
            is ConnectException -> "Unable to connect to server"
            else -> "Error loading episode: ${e.localizedMessage ?: "Unknown error"}"
        }

        Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_SHORT).show()

        // Always offer fallback to anime details page
        openAnimeDetailsAsFallback(anime)
    }

    private fun openAnimeDetailsAsFallback(anime: SAnime) {
        currentFeaturedSource = SourceManager.getSelectedSource(applicationContext)
        try {
            val intent = AnimeDetailsActivity.newIntent(this@MainActivity, anime, currentFeaturedSource)
            startActivity(intent)
            overridePendingTransition(R.anim.scale_in, R.anim.fade_out)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open anime details", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleEmptySlider() {
        // Create a placeholder or hide the slider section
        featuredAnimeTitle.text = "Welcome to Anime App"
        featuredAnimeGenre.text = "Discover amazing anime content"
        btnPlay.isEnabled = false
        btnMyList.isEnabled = false
    }

    private fun showOfflineState() {
        // Show offline indicators or cached content
        // You can implement offline caching here if needed
        featuredAnimeTitle.text = "No Connection"
        featuredAnimeGenre.text = "Please check your internet connection"
        btnPlay.isEnabled = false
        btnMyList.isEnabled = false
    }

    private fun showWarning(message: String) {
        if (isFinishing || isDestroyed) {
            return
        }
        // You can use a Snackbar for warnings instead of Toast for better UX
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showMainLoading(show: Boolean) {
        // You can implement a loading indicator here if needed
        // For now, just disable the play button to prevent multiple clicks
        btnPlay.isEnabled = !show
        btnPlay.text = if (show) "Loading..." else "Play"
    }

    private fun addToMyList(anime: SAnime) {
        lifecycleScope.launch {
            try {
                // Check if already in favorites
                val existingFavorite = db.favoriteDao().getFavoriteByUrl(anime.url!!)

                if (existingFavorite != null) {
                    // Remove from favorites
                    db.favoriteDao().delete(existingFavorite.animeUrl)
                    Toast.makeText(this@MainActivity, "Removed from My List", Toast.LENGTH_SHORT).show()
                    updateMyListButtonState(false)
                } else {
                    // Add to favorites
                    val favorite = Favorite(
                        animeUrl = anime.url!!,
                        title = anime.title,
                        thumbnailUrl = anime.thumbnail_url ?: "",
                        source = SourceManager.getSelectedSource(applicationContext).name,
                        timestamp = System.currentTimeMillis()
                    )
                    db.favoriteDao().insert(favorite)
                    Toast.makeText(this@MainActivity, "Added to My List", Toast.LENGTH_SHORT).show()
                    updateMyListButtonState(true)
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error updating My List: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateMyListButtonState(isInMyList: Boolean) {
        if (isInMyList) {
            btnMyList.text = "My List"
            btnMyList.setIconResource(R.drawable.done_all_24px) // You might need to add this icon
        } else {
            btnMyList.text = "My List"
            btnMyList.setIconResource(R.drawable.add_24px)
        }
    }

    private fun setupSeeAllButtons() {
        seeAllTopHits.setOnClickListener {
            val intent = SeeAllActivity.newIntent(this, "TOP_HITS", "Top Hits Anime")
            startActivity(intent)
        }

        seeAllNewEpisodes.setOnClickListener {
            val intent = SeeAllActivity.newIntent(this, "NEW_EPISODES", "New Episode Releases")
            startActivity(intent)
        }

        seeAllLatest.setOnClickListener {
            val intent = SeeAllActivity.newIntent(this, "LATEST_UPDATES", "Latest Updates")
            startActivity(intent)
        }

        // Make see all buttons focusable for TV navigation
        seeAllTopHits.isFocusable = true
        seeAllNewEpisodes.isFocusable = true
        seeAllLatest.isFocusable = true
        seeAllContinueWatching.isFocusable = true
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_home_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_search -> {
                val intent = SearchActivity.newIntent(this)
                startActivity(intent)
                true
            }
            R.id.action_notifications -> {
                Toast.makeText(this, "Notifications clicked!", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerViews() {
        // Continue Watching
        continueWatchingAdapter = ContinueWatchingAdapter { watchHistory ->
            openContinueWatchingItem(watchHistory)
        }
        continueWatchingRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = continueWatchingAdapter
            // Enable TV-friendly scrolling
            isNestedScrollingEnabled = false
        }

        // Top Hits
        topHitsAdapter = AnimeAdapter(AnimeAdapter.ViewType.TOP_HIT) { anime -> openAnimeDetails(anime) }
        topHitsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = topHitsAdapter
            isNestedScrollingEnabled = false
        }

        // New Episodes
        newEpisodesAdapter = AnimeAdapter(AnimeAdapter.ViewType.NEW_RELEASE) { anime -> openAnimeDetails(anime) }
        newEpisodesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = newEpisodesAdapter
            isNestedScrollingEnabled = false
        }

        // Latest Updates Grid
        latestAdapter = AnimeAdapter(AnimeAdapter.ViewType.GRID) { anime ->
            openAnimeDetails(anime)
        }
        latestRecyclerView.apply {
            val spanCount = 3
            layoutManager = GridLayoutManager(this@MainActivity, spanCount)
            adapter = latestAdapter
            isNestedScrollingEnabled = false

            val spacing = resources.getDimensionPixelSize(R.dimen.grid_spacing)
            val includeEdge = true

            if (itemDecorationCount == 0) {
                addItemDecoration(GridSpacingItemDecoration(spanCount, spacing, includeEdge))
            }
        }
    }

    private fun setupBottomNavigation() {
        bottomNavigationView.setOnNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_my_list -> {
                    startActivity(Intent(this, MyListActivity::class.java))
                    true
                }
                R.id.nav_search -> {
                    val intent = ParentSearchActivity.newIntent(this)
                    startActivity(intent)
                    true
                }
                R.id.nav_download -> {
                    startActivity(Intent(this, DownloadsActivity::class.java))
                    true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun observeWatchHistory() {
        lifecycleScope.launch {
            db.watchHistoryDao().getContinueWatchingHistory().collectLatest { historyList ->
                if (historyList.isEmpty()) {
                    continueWatchingSection.visibility = View.GONE
                    continueWatchingRecyclerView.visibility = View.GONE
                } else {
                    continueWatchingSection.visibility = View.VISIBLE
                    continueWatchingRecyclerView.visibility = View.VISIBLE
                    continueWatchingAdapter.submitList(historyList)
                }
            }
        }
    }

    private fun updateFeaturedAnime(anime: SAnime, source: AnimeSource?) {
        currentFeaturedAnime = anime
        currentFeaturedSource = source

        featuredAnimeTitle.text = anime.title
        // You might want to set genre text here if available in your anime model
        featuredAnimeGenre.text = anime.description ?: "Action, Adventure" // Fallback

        // Re-enable buttons
        btnPlay.isEnabled = true
        btnMyList.isEnabled = true

        // Check if anime is in favorites and update button state
        lifecycleScope.launch {
            try {
                val existingFavorite = db.favoriteDao().getFavoriteByUrl(anime.url!!)
                updateMyListButtonState(existingFavorite != null)
            } catch (e: Exception) {
                // Handle database error silently
            }
        }
    }

    private fun openContinueWatchingItem(item: WatchHistory) {
        val anime = SAnime(
            url = item.animeUrl,
            title = item.animeTitle,
            thumbnail_url = item.animeThumbnailUrl
        )

        val source = try {
            AnimeSource.valueOf(item.source.replace(" ", "_").uppercase())
        } catch (e: Exception) {
            null
        }

        val intent = AnimeDetailsActivity.newIntentWithResume(
            context = this,
            anime = anime,
            resumeEpisodeUrl = item.episodeUrl,
            source = source
        )
        startActivity(intent)
    }

    // ENHANCED: setupAutoSwipe with null checks and error handling
    private fun setupAutoSwipe(sliderAdapter: SliderAdapter) {
        sliderRunnable = Runnable {
            try {
                val currentItem = mainSliderViewPager.currentItem
                val itemCount = sliderAdapter.itemCount
                if (itemCount > 0) {
                    val nextItem = (currentItem + 1) % itemCount
                    mainSliderViewPager.setCurrentItem(nextItem, true)

                    // Update featured anime when slider changes
                    val sliderItems = sliderAdapter.getItems() // You might need to add this method to SliderAdapter
                    if (sliderItems.isNotEmpty() && nextItem < sliderItems.size) {
                        updateFeaturedAnime(sliderItems[nextItem], null)
                    }
                }
            } catch (e: Exception) {
                // Silently handle any auto-swipe errors to prevent crashes
            }
        }

        mainSliderViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                try {
                    sliderHandler.removeCallbacks(sliderRunnable)
                    sliderHandler.postDelayed(sliderRunnable, 3000)

                    // Update featured anime when user manually swipes
                    val sliderItems = sliderAdapter.getItems()
                    if (sliderItems.isNotEmpty() && position < sliderItems.size) {
                        updateFeaturedAnime(sliderItems[position], null)
                    }
                } catch (e: Exception) {
                    // Handle any errors during page selection
                }
            }
        })

        if(::sliderRunnable.isInitialized) {
            sliderHandler.postDelayed(sliderRunnable, 3000)
        }
    }

    private fun openAnimeDetails(anime: SAnime) {
        try {
            val source = SourceManager.getSelectedSource(applicationContext)
            val intent = AnimeDetailsActivity.newIntent(this, anime, source)
            startActivity(intent)
            overridePendingTransition(R.anim.scale_in, R.anim.fade_out)
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening anime details", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLoading(isLoading: Boolean) {
        try {
            // Stop refresh animation
            if (!isLoading) {
                swipeRefreshLayout.isRefreshing = false
            }

            if (isLoading) {
                shimmerTopHits.startShimmer()
                shimmerNewEpisodes.startShimmer()
                shimmerLatestUpdates.startShimmer()

                shimmerTopHits.visibility = View.VISIBLE
                shimmerNewEpisodes.visibility = View.VISIBLE
                shimmerLatestUpdates.visibility = View.VISIBLE

                topHitsRecyclerView.visibility = View.GONE
                newEpisodesRecyclerView.visibility = View.GONE
                latestRecyclerView.visibility = View.GONE
            } else {
                shimmerTopHits.stopShimmer()
                shimmerNewEpisodes.stopShimmer()
                shimmerLatestUpdates.stopShimmer()

                shimmerTopHits.visibility = View.GONE
                shimmerNewEpisodes.visibility = View.GONE
                shimmerLatestUpdates.visibility = View.GONE

                topHitsRecyclerView.visibility = View.VISIBLE
                newEpisodesRecyclerView.visibility = View.VISIBLE
                latestRecyclerView.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            // Handle shimmer animation errors silently
            swipeRefreshLayout.isRefreshing = false
        }
    }

    private fun showError(message: String) {
        if (isFinishing || isDestroyed) {
            return
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onPause() {
        super.onPause()
        try {
            if(::sliderRunnable.isInitialized) sliderHandler.removeCallbacks(sliderRunnable)
        } catch (e: Exception) {
            // Handle error silently
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            if(::sliderRunnable.isInitialized) sliderHandler.postDelayed(sliderRunnable, 3000)

            // Reset focus to featured buttons when returning to activity
            Handler(Looper.getMainLooper()).postDelayed({
                setInitialFocus()
            }, 100)
        } catch (e: Exception) {
            // Handle error silently
        }
    }

    // Add refresh functionality
    fun refreshData() {
        loadData()
    }

    // Setup pull-to-refresh functionality
    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener {
            loadData()
        }
        // Customize colors
        swipeRefreshLayout.setColorSchemeResources(
            R.color.green_play_button,
            android.R.color.holo_blue_bright,
            android.R.color.holo_green_light,
            android.R.color.holo_orange_light
        )
    }

    // Additional TV-specific helper methods

    /**
     * Shows on-screen navigation hints for TV users
     */
    private fun showTVNavigationHelp() {
        val helpText = """
            TV Navigation Help:
            ↑/↓ - Navigate between sections
            ←/→ - Navigate within sections
            CENTER/ENTER - Select item
            BACK - Go back
        """.trimIndent()

        Toast.makeText(this, helpText, Toast.LENGTH_LONG).show()
    }

    /**
     * Handle menu button press to show navigation help
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                showTVNavigationHelp()
                true
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }

    /**
     * Disable swipe refresh when using TV navigation to prevent conflicts
     */
    private fun updateSwipeRefreshForTV() {
        // Disable swipe refresh when TV navigation is active
        swipeRefreshLayout.isEnabled = false
    }

    override fun onStart() {
        super.onStart()
        // Check if we're running on TV and adjust accordingly
        if (packageManager.hasSystemFeature("android.software.leanback")) {
            updateSwipeRefreshForTV()
        }
    }
}

//package com.faselhd.app
//
//import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
//import android.content.Context
//import android.net.ConnectivityManager
//import android.net.NetworkCapabilities
//import com.faselhd.app.widgets.GridSpacingItemDecoration
//import android.content.Intent
//import android.os.Bundle
//import android.os.Handler
//import android.os.Looper
//import android.view.Menu
//import android.view.MenuItem
//import android.view.View
//import android.widget.LinearLayout
//import android.widget.TextView
//import android.widget.Toast
//import androidx.appcompat.app.AppCompatActivity
//import androidx.lifecycle.lifecycleScope
//import androidx.recyclerview.widget.GridLayoutManager
//import androidx.recyclerview.widget.LinearLayoutManager
//import androidx.recyclerview.widget.RecyclerView
//import androidx.viewpager2.widget.ViewPager2
//import com.faselhd.app.adapters.AnimeAdapter
//import com.faselhd.app.adapters.ContinueWatchingAdapter
//import com.faselhd.app.adapters.SliderAdapter
//import com.faselhd.app.db.AppDatabase
//import com.faselhd.app.models.SAnime
//import com.faselhd.app.models.WatchHistory
//import com.faselhd.app.models.Favorite
//import com.faselhd.app.network.SourceManager
//import com.example.myapplication.R
//import com.facebook.shimmer.ShimmerFrameLayout
//import com.faselhd.app.network.AnimeSource
//import com.google.android.material.bottomnavigation.BottomNavigationView
//import com.google.android.material.button.MaterialButton
//import kotlinx.coroutines.async
//import kotlinx.coroutines.flow.collectLatest
//import kotlinx.coroutines.isActive
//import kotlinx.coroutines.launch
//import java.net.ConnectException
//import java.net.SocketTimeoutException
//import java.net.UnknownHostException
//
//class MainActivity : AppCompatActivity() {
//
//    // ... (keep all your existing view declarations as they are)
//    private lateinit var mainSliderViewPager: ViewPager2
//    private lateinit var bottomNavigationView: BottomNavigationView
//    private lateinit var toolbar: androidx.appcompat.widget.Toolbar
//
//    // Featured anime views
//    private lateinit var featuredAnimeTitle: TextView
//    private lateinit var featuredAnimeGenre: TextView
//    private lateinit var btnPlay: MaterialButton
//    private lateinit var btnMyList: MaterialButton
//
//    // Recycler Views
//    private lateinit var continueWatchingSection: LinearLayout
//    private lateinit var continueWatchingRecyclerView: RecyclerView
//    private lateinit var topHitsRecyclerView: RecyclerView
//    private lateinit var newEpisodesRecyclerView: RecyclerView
//    private lateinit var latestRecyclerView: RecyclerView
//
//    // Adapters
//    private lateinit var sliderAdapter: SliderAdapter
//    private lateinit var continueWatchingAdapter: ContinueWatchingAdapter
//    private lateinit var topHitsAdapter: AnimeAdapter
//    private lateinit var newEpisodesAdapter: AnimeAdapter
//    private lateinit var latestAdapter: AnimeAdapter
//
//    // Utilities
//    private val sourceManager by lazy { SourceManager(applicationContext) }
//    private val db by lazy { AppDatabase.getDatabase(this) }
//    private val sliderHandler = Handler(Looper.getMainLooper())
//    private lateinit var sliderRunnable: Runnable
//
//    private lateinit var seeAllContinueWatching: TextView
//    private lateinit var seeAllTopHits: TextView
//    private lateinit var seeAllNewEpisodes: TextView
//    private lateinit var seeAllLatest: TextView
//
//    private lateinit var shimmerTopHits: ShimmerFrameLayout
//    private lateinit var shimmerNewEpisodes: ShimmerFrameLayout
//    private lateinit var shimmerLatestUpdates: ShimmerFrameLayout
//    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
//
//    // Current featured anime
//    private var currentFeaturedAnime: SAnime? = null
//    private var currentFeaturedSource: AnimeSource? = null
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_main)
//
//        initViews()
//        setupToolbar()
//        setupRecyclerViews()
//        setupBottomNavigation()
//        setupSeeAllButtons()
//        setupFeaturedButtons()
//        setupSwipeRefresh() // Add this line
//        loadData()
//        observeWatchHistory()
//    }
//
//    // ... (keep all your existing initialization methods as they are)
//    private fun initViews() {
//        toolbar = findViewById(R.id.toolbar)
//        mainSliderViewPager = findViewById(R.id.main_slider_view_pager)
//        bottomNavigationView = findViewById(R.id.bottom_navigation)
//
//        featuredAnimeTitle = findViewById(R.id.featured_anime_title)
//        featuredAnimeGenre = findViewById(R.id.featured_anime_genre)
//        btnPlay = findViewById(R.id.btn_play)
//        btnMyList = findViewById(R.id.btn_my_list)
//
//        continueWatchingSection = findViewById(R.id.continue_watching_section)
//        continueWatchingRecyclerView = findViewById(R.id.continue_watching_recycler_view)
//        topHitsRecyclerView = findViewById(R.id.top_hits_recycler_view)
//        newEpisodesRecyclerView = findViewById(R.id.new_episodes_recycler_view)
//        latestRecyclerView = findViewById(R.id.latest_recycler_view)
//
//        seeAllContinueWatching = findViewById(R.id.see_all_continue_watching)
//        seeAllTopHits = findViewById(R.id.see_all_top_hits)
//        seeAllNewEpisodes = findViewById(R.id.see_all_new_episodes)
//        seeAllLatest = findViewById(R.id.see_all_latest)
//
//        shimmerTopHits = findViewById(R.id.shimmer_top_hits)
//        shimmerNewEpisodes = findViewById(R.id.shimmer_new_episodes)
//        shimmerLatestUpdates = findViewById(R.id.shimmer_latest_updates)
//        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
//    }
//
//    private fun setupFeaturedButtons() {
//        btnPlay.setOnClickListener {
//            currentFeaturedAnime?.let { anime ->
//                handlePlayButtonClick(anime)
//            } ?: run {
//                Toast.makeText(this, "No anime selected", Toast.LENGTH_SHORT).show()
//            }
//        }
//
//        btnMyList.setOnClickListener {
//            currentFeaturedAnime?.let { anime ->
//                addToMyList(anime)
//            } ?: run {
//                Toast.makeText(this, "No anime selected", Toast.LENGTH_SHORT).show()
//            }
//        }
//    }
//
//    // ENHANCED: Handle play button click with comprehensive error handling
//    private fun handlePlayButtonClick(anime: SAnime) {
//        if (currentFeaturedAnime == null) {
//            Toast.makeText(this, "No anime selected", Toast.LENGTH_SHORT).show()
//            return
//        }
//
//        lifecycleScope.launch {
//            try {
//                // Check network connectivity
//                if (!isNetworkAvailable()) {
//                    Toast.makeText(this@MainActivity, "No internet connection. Please check your network settings.", Toast.LENGTH_LONG).show()
//                    return@launch
//                }
//
//                showMainLoading(true)
//
//                // Check if there's recent watch history for this anime
//                val recentHistory = db.watchHistoryDao().getRecentWatchHistoryForAnime(anime.url!!)
//
//                if (recentHistory != null && recentHistory.lastWatchedPosition > 0) {
//                    // There's watch history - resume from that episode
//                    Toast.makeText(this@MainActivity, "Resuming from where you left off...", Toast.LENGTH_SHORT).show()
//                    val intent = AnimeDetailsActivity.newIntentWithResume(
//                        context = this@MainActivity,
//                        anime = anime,
//                        resumeEpisodeUrl = recentHistory.episodeUrl,
//                        source = currentFeaturedSource
//                    )
//                    startActivity(intent)
//                    overridePendingTransition(R.anim.scale_in, R.anim.fade_out)
//                } else {
//                    // No watch history - fetch episodes and play first one
//                    Toast.makeText(this@MainActivity, "Starting from Episode 1...", Toast.LENGTH_SHORT).show()
//
//                    try {
//                        val episodes = sourceManager.fetchEpisodeList(anime.url!!, currentFeaturedSource)
//
//                        if (episodes.isNotEmpty()) {
//                            val firstEpisode = episodes.first()
//
//                            // Get video links for the first episode
//                            val videos = sourceManager.fetchVideoList(firstEpisode.url!!, currentFeaturedSource)
//
//                            if (videos.isNotEmpty()) {
//                                // Start video player directly
//                                val intent = VideoPlayerActivity.newIntent(
//                                    context = this@MainActivity,
//                                    videos = videos,
//                                    anime = anime,
//                                    currentEpisode = firstEpisode,
//                                    episodeListForSeason = ArrayList(episodes),
//                                    startPosition = 0L,
//                                    source = currentFeaturedSource
//                                )
//                                startActivity(intent)
//                            } else {
//                                Toast.makeText(this@MainActivity, "No video sources available for this episode", Toast.LENGTH_SHORT).show()
//                                // Fallback to anime details
//                                openAnimeDetailsAsFallback(anime)
//                            }
//                        } else {
//                            Toast.makeText(this@MainActivity, "No episodes found for this anime", Toast.LENGTH_SHORT).show()
//                            // Fallback to anime details
//                            openAnimeDetailsAsFallback(anime)
//                        }
//                    } catch (e: Exception) {
//                        handlePlaybackError(e, anime)
//                    }
//                }
//            } catch (e: Exception) {
//                showMainLoading(false)
//                handlePlaybackError(e, anime)
//            } finally {
//                showMainLoading(false)
//            }
//        }
//    }
//
//    // ENHANCED: loadData with comprehensive error handling
//    private fun loadData() {
//        showLoading(true)
//        lifecycleScope.launch {
//            try {
//                // Check network connectivity first
//                if (!isNetworkAvailable()) {
//                    showError("No internet connection. Please check your network settings.")
//                    showLoading(false)
//                    showOfflineState()
//                    return@launch
//                }
//
//                val sliderJob = async {
//                    try {
//                        sourceManager.fetchMainSlider()
//                    } catch (e: Exception) {
//                        emptyList<SAnime>() // Return empty list on failure
//                    }
//                }
//
//                val popularJob = async { sourceManager.fetchPopularSeries(1) }
//                val latestEpisodesJob = async { sourceManager.fetchHomePageLatestEpisodes() }
//                val latestUpdatesJob = async { sourceManager.fetchLatestUpdates(1) }
//
//                val sliderItems = sliderJob.await()
//                val popularSeries = popularJob.await()
//                val latestEpisodes = latestEpisodesJob.await()
//                val latestUpdates = latestUpdatesJob.await()
//
//                if (!isActive) return@launch
//
//                // Handle slider setup with error checking
//                if (sliderItems.isNotEmpty()) {
//                    sliderAdapter = SliderAdapter(sliderItems) { anime -> openAnimeDetails(anime) }
//                    mainSliderViewPager.adapter = sliderAdapter
//                    setupAutoSwipe(sliderAdapter)
//                    updateFeaturedAnime(sliderItems[0], null)
//                } else {
//                    // Handle empty slider case
//                    handleEmptySlider()
//                }
//
//                // Safely submit lists with null checks
//                topHitsAdapter.submitList(popularSeries.manga.take(10))
//                newEpisodesAdapter.submitList(latestEpisodes.take(10))
//                latestAdapter.submitList(latestUpdates.manga.take(20))
//
//                showLoading(false)
//
//                // Show partial data warning if some sections are empty
//                if (sliderItems.isEmpty() && popularSeries.manga.isEmpty() &&
//                    latestEpisodes.isEmpty() && latestUpdates.manga.isEmpty()) {
//                    showError("Unable to load content. Please check your internet connection and try again.")
//                } else if (sliderItems.isEmpty() || popularSeries.manga.isEmpty() ||
//                    latestEpisodes.isEmpty() || latestUpdates.manga.isEmpty()) {
//                    showWarning("Some content could not be loaded. Pull to refresh to try again.")
//                }
//
//            } catch (e: Exception) {
//                if (isActive) {
//                    when (e) {
//                        is UnknownHostException -> {
//                            showError("No internet connection. Please check your network settings.")
//                        }
//                        is SocketTimeoutException -> {
//                            showError("Connection timeout. Please try again.")
//                        }
//                        is ConnectException -> {
//                            showError("Unable to connect to server. Please try again later.")
//                        }
//                        else -> {
//                            showError("Error loading data: ${e.localizedMessage ?: "Unknown error occurred"}")
//                        }
//                    }
//                    showLoading(false)
//                    showOfflineState()
//                }
//            }
//        }
//    }
//
//    // Helper methods for better error handling
//    private fun isNetworkAvailable(): Boolean {
//        return try {
//            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
//            val network = connectivityManager.activeNetwork ?: return false
//            val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
//            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
//        } catch (e: Exception) {
//            false
//        }
//    }
//
//    private fun handlePlaybackError(e: Exception, anime: SAnime) {
//        val errorMessage = when (e) {
//            is UnknownHostException -> "No internet connection"
//            is SocketTimeoutException -> "Connection timeout. Please try again."
//            is ConnectException -> "Unable to connect to server"
//            else -> "Error loading episode: ${e.localizedMessage ?: "Unknown error"}"
//        }
//
//        Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_SHORT).show()
//
//        // Always offer fallback to anime details page
//        openAnimeDetailsAsFallback(anime)
//    }
//
//    private fun openAnimeDetailsAsFallback(anime: SAnime) {
//        currentFeaturedSource = SourceManager.getSelectedSource(applicationContext)
//        try {
//            val intent = AnimeDetailsActivity.newIntent(this@MainActivity, anime, currentFeaturedSource)
//            startActivity(intent)
//            overridePendingTransition(R.anim.scale_in, R.anim.fade_out)
//        } catch (e: Exception) {
//            Toast.makeText(this, "Unable to open anime details", Toast.LENGTH_SHORT).show()
//        }
//    }
//
//    private fun handleEmptySlider() {
//        // Create a placeholder or hide the slider section
//        featuredAnimeTitle.text = "Welcome to Anime App"
//        featuredAnimeGenre.text = "Discover amazing anime content"
//        btnPlay.isEnabled = false
//        btnMyList.isEnabled = false
//    }
//
//    private fun showOfflineState() {
//        // Show offline indicators or cached content
//        // You can implement offline caching here if needed
//        featuredAnimeTitle.text = "No Connection"
//        featuredAnimeGenre.text = "Please check your internet connection"
//        btnPlay.isEnabled = false
//        btnMyList.isEnabled = false
//    }
//
//    private fun showWarning(message: String) {
//        if (isFinishing || isDestroyed) {
//            return
//        }
//        // You can use a Snackbar for warnings instead of Toast for better UX
//        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
//    }
//
//    private fun showMainLoading(show: Boolean) {
//        // You can implement a loading indicator here if needed
//        // For now, just disable the play button to prevent multiple clicks
//        btnPlay.isEnabled = !show
//        btnPlay.text = if (show) "Loading..." else "Play"
//    }
//
//    private fun addToMyList(anime: SAnime) {
//        lifecycleScope.launch {
//            try {
//                // Check if already in favorites
//                val existingFavorite = db.favoriteDao().getFavoriteByUrl(anime.url!!)
//
//                if (existingFavorite != null) {
//                    // Remove from favorites
//                    db.favoriteDao().delete(existingFavorite.animeUrl)
//                    Toast.makeText(this@MainActivity, "Removed from My List", Toast.LENGTH_SHORT).show()
//                    updateMyListButtonState(false)
//                } else {
//                    // Add to favorites
//                    val favorite = Favorite(
//                        animeUrl = anime.url!!,
//                        title = anime.title,
//                        thumbnailUrl = anime.thumbnail_url ?: "",
//                        source = SourceManager.getSelectedSource(applicationContext).name,
//                        timestamp = System.currentTimeMillis()
//                    )
//                    db.favoriteDao().insert(favorite)
//                    Toast.makeText(this@MainActivity, "Added to My List", Toast.LENGTH_SHORT).show()
//                    updateMyListButtonState(true)
//                }
//            } catch (e: Exception) {
//                Toast.makeText(this@MainActivity, "Error updating My List: ${e.message}", Toast.LENGTH_SHORT).show()
//            }
//        }
//    }
//
//    private fun updateMyListButtonState(isInMyList: Boolean) {
//        if (isInMyList) {
//            btnMyList.text = "My List"
//            btnMyList.setIconResource(R.drawable.done_all_24px) // You might need to add this icon
//        } else {
//            btnMyList.text = "My List"
//            btnMyList.setIconResource(R.drawable.add_24px)
//        }
//    }
//
//    private fun setupSeeAllButtons() {
//        seeAllTopHits.setOnClickListener {
//            val intent = SeeAllActivity.newIntent(this, "TOP_HITS", "Top Hits Anime")
//            startActivity(intent)
//        }
//
//        seeAllNewEpisodes.setOnClickListener {
//            val intent = SeeAllActivity.newIntent(this, "NEW_EPISODES", "New Episode Releases")
//            startActivity(intent)
//        }
//
//        seeAllLatest.setOnClickListener {
//            val intent = SeeAllActivity.newIntent(this, "LATEST_UPDATES", "Latest Updates")
//            startActivity(intent)
//        }
//    }
//
//    private fun setupToolbar() {
//        setSupportActionBar(toolbar)
//        supportActionBar?.setDisplayShowTitleEnabled(false)
//    }
//
//    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
//        menuInflater.inflate(R.menu.main_home_menu, menu)
//        return true
//    }
//
//    override fun onOptionsItemSelected(item: MenuItem): Boolean {
//        return when (item.itemId) {
//            R.id.action_search -> {
//                val intent = SearchActivity.newIntent(this)
//                startActivity(intent)
//                true
//            }
//            R.id.action_notifications -> {
//                Toast.makeText(this, "Notifications clicked!", Toast.LENGTH_SHORT).show()
//                true
//            }
//            else -> super.onOptionsItemSelected(item)
//        }
//    }
//
//    private fun setupRecyclerViews() {
//        // Continue Watching
//        continueWatchingAdapter = ContinueWatchingAdapter { watchHistory ->
//            openContinueWatchingItem(watchHistory)
//        }
//        continueWatchingRecyclerView.apply {
//            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
//            adapter = continueWatchingAdapter
//        }
//
//        // Top Hits
//        topHitsAdapter = AnimeAdapter(AnimeAdapter.ViewType.TOP_HIT) { anime -> openAnimeDetails(anime) }
//        topHitsRecyclerView.apply {
//            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
//            adapter = topHitsAdapter
//        }
//
//        // New Episodes
//        newEpisodesAdapter = AnimeAdapter(AnimeAdapter.ViewType.NEW_RELEASE) { anime -> openAnimeDetails(anime) }
//        newEpisodesRecyclerView.apply {
//            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
//            adapter = newEpisodesAdapter
//        }
//
//        // Latest Updates Grid
//        latestAdapter = AnimeAdapter(AnimeAdapter.ViewType.GRID) { anime ->
//            openAnimeDetails(anime)
//        }
//        latestRecyclerView.apply {
//            val spanCount = 3
//            layoutManager = GridLayoutManager(this@MainActivity, spanCount)
//            adapter = latestAdapter
//            isNestedScrollingEnabled = false
//
//            val spacing = resources.getDimensionPixelSize(R.dimen.grid_spacing)
//            val includeEdge = true
//
//            if (itemDecorationCount == 0) {
//                addItemDecoration(GridSpacingItemDecoration(spanCount, spacing, includeEdge))
//            }
//        }
//    }
//
//    private fun setupBottomNavigation() {
//        bottomNavigationView.setOnNavigationItemSelectedListener { item ->
//            when (item.itemId) {
//                R.id.nav_home -> true
//                R.id.nav_my_list -> {
//                    startActivity(Intent(this, MyListActivity::class.java))
//                    true
//                }
//                R.id.nav_search -> {
//                    val intent = ParentSearchActivity.newIntent(this)
//                    startActivity(intent)
//                    true
//                }
//                R.id.nav_download -> {
//                    startActivity(Intent(this, DownloadsActivity::class.java))
//                    true
//                }
//                R.id.nav_settings -> {
//                    startActivity(Intent(this, SettingsActivity::class.java))
//                    true
//                }
//                else -> false
//            }
//        }
//    }
//
//    private fun observeWatchHistory() {
//        lifecycleScope.launch {
//            db.watchHistoryDao().getContinueWatchingHistory().collectLatest { historyList ->
//                if (historyList.isEmpty()) {
//                    continueWatchingSection.visibility = View.GONE
//                    continueWatchingRecyclerView.visibility = View.GONE
//                } else {
//                    continueWatchingSection.visibility = View.VISIBLE
//                    continueWatchingRecyclerView.visibility = View.VISIBLE
//                    continueWatchingAdapter.submitList(historyList)
//                }
//            }
//        }
//    }
//
//    private fun updateFeaturedAnime(anime: SAnime, source: AnimeSource?) {
//        currentFeaturedAnime = anime
//        currentFeaturedSource = source
//
//        featuredAnimeTitle.text = anime.title
//        // You might want to set genre text here if available in your anime model
//        featuredAnimeGenre.text = anime.description ?: "Action, Adventure" // Fallback
//
//        // Re-enable buttons
//        btnPlay.isEnabled = true
//        btnMyList.isEnabled = true
//
//        // Check if anime is in favorites and update button state
//        lifecycleScope.launch {
//            try {
//                val existingFavorite = db.favoriteDao().getFavoriteByUrl(anime.url!!)
//                updateMyListButtonState(existingFavorite != null)
//            } catch (e: Exception) {
//                // Handle database error silently
//            }
//        }
//    }
//
//    private fun openContinueWatchingItem(item: WatchHistory) {
//        val anime = SAnime(
//            url = item.animeUrl,
//            title = item.animeTitle,
//            thumbnail_url = item.animeThumbnailUrl
//        )
//
//        val source = try {
//            AnimeSource.valueOf(item.source.replace(" ", "_").uppercase())
//        } catch (e: Exception) {
//            null
//        }
//
//        val intent = AnimeDetailsActivity.newIntentWithResume(
//            context = this,
//            anime = anime,
//            resumeEpisodeUrl = item.episodeUrl,
//            source = source
//        )
//        startActivity(intent)
//    }
//
//    // ENHANCED: setupAutoSwipe with null checks and error handling
//    private fun setupAutoSwipe(sliderAdapter: SliderAdapter) {
//        sliderRunnable = Runnable {
//            try {
//                val currentItem = mainSliderViewPager.currentItem
//                val itemCount = sliderAdapter.itemCount
//                if (itemCount > 0) {
//                    val nextItem = (currentItem + 1) % itemCount
//                    mainSliderViewPager.setCurrentItem(nextItem, true)
//
//                    // Update featured anime when slider changes
//                    val sliderItems = sliderAdapter.getItems() // You might need to add this method to SliderAdapter
//                    if (sliderItems.isNotEmpty() && nextItem < sliderItems.size) {
//                        updateFeaturedAnime(sliderItems[nextItem], null)
//                    }
//                }
//            } catch (e: Exception) {
//                // Silently handle any auto-swipe errors to prevent crashes
//            }
//        }
//
//        mainSliderViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
//            override fun onPageSelected(position: Int) {
//                super.onPageSelected(position)
//                try {
//                    sliderHandler.removeCallbacks(sliderRunnable)
//                    sliderHandler.postDelayed(sliderRunnable, 3000)
//
//                    // Update featured anime when user manually swipes
//                    val sliderItems = sliderAdapter.getItems()
//                    if (sliderItems.isNotEmpty() && position < sliderItems.size) {
//                        updateFeaturedAnime(sliderItems[position], null)
//                    }
//                } catch (e: Exception) {
//                    // Handle any errors during page selection
//                }
//            }
//        })
//
//        if(::sliderRunnable.isInitialized) {
//            sliderHandler.postDelayed(sliderRunnable, 3000)
//        }
//    }
//
//    private fun openAnimeDetails(anime: SAnime) {
//        try {
//            val source = SourceManager.getSelectedSource(applicationContext)
//
//            val intent = AnimeDetailsActivity.newIntent(this, anime, source)
//            startActivity(intent)
//            overridePendingTransition(R.anim.scale_in, R.anim.fade_out)
//        } catch (e: Exception) {
//            Toast.makeText(this, "Error opening anime details", Toast.LENGTH_SHORT).show()
//        }
//    }
//
//    private fun showLoading(isLoading: Boolean) {
//        try {
//            // Stop refresh animation
//            if (!isLoading) {
//                swipeRefreshLayout.isRefreshing = false
//            }
//
//            if (isLoading) {
//                shimmerTopHits.startShimmer()
//                shimmerNewEpisodes.startShimmer()
//                shimmerLatestUpdates.startShimmer()
//
//                shimmerTopHits.visibility = View.VISIBLE
//                shimmerNewEpisodes.visibility = View.VISIBLE
//                shimmerLatestUpdates.visibility = View.VISIBLE
//
//                topHitsRecyclerView.visibility = View.GONE
//                newEpisodesRecyclerView.visibility = View.GONE
//                latestRecyclerView.visibility = View.GONE
//            } else {
//                shimmerTopHits.stopShimmer()
//                shimmerNewEpisodes.stopShimmer()
//                shimmerLatestUpdates.stopShimmer()
//
//                shimmerTopHits.visibility = View.GONE
//                shimmerNewEpisodes.visibility = View.GONE
//                shimmerLatestUpdates.visibility = View.GONE
//
//                topHitsRecyclerView.visibility = View.VISIBLE
//                newEpisodesRecyclerView.visibility = View.VISIBLE
//                latestRecyclerView.visibility = View.VISIBLE
//            }
//        } catch (e: Exception) {
//            // Handle shimmer animation errors silently
//            swipeRefreshLayout.isRefreshing = false
//        }
//    }
//
//    private fun showError(message: String) {
//        if (isFinishing || isDestroyed) {
//            return
//        }
//        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
//    }
//
//    override fun onPause() {
//        super.onPause()
//        try {
//            if(::sliderRunnable.isInitialized) sliderHandler.removeCallbacks(sliderRunnable)
//        } catch (e: Exception) {
//            // Handle error silently
//        }
//    }
//
//    override fun onResume() {
//        super.onResume()
//        try {
//            if(::sliderRunnable.isInitialized) sliderHandler.postDelayed(sliderRunnable, 3000)
//        } catch (e: Exception) {
//            // Handle error silently
//        }
//    }
//
//    // Add refresh functionality
//    fun refreshData() {
//        loadData()
//    }
//
//    // Setup pull-to-refresh functionality
//    private fun setupSwipeRefresh() {
//        swipeRefreshLayout.setOnRefreshListener {
//            loadData()
//        }
//        // Customize colors
//        swipeRefreshLayout.setColorSchemeResources(
//            R.color.green_play_button,
//            android.R.color.holo_blue_bright,
//            android.R.color.holo_green_light,
//            android.R.color.holo_orange_light
//        )
//    }
//}