package com.faselhd.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
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
import com.faselhd.app.network.SourceManager
import com.example.myapplication.R
import com.facebook.shimmer.ShimmerFrameLayout
import com.faselhd.app.network.AnimeSource
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    // --- Views ---
    private lateinit var mainSliderViewPager: ViewPager2
    private lateinit var bottomNavigationView: BottomNavigationView
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar

    // --- Recycler Views (ALL ARE HERE) ---
    private lateinit var continueWatchingSection: LinearLayout
    private lateinit var continueWatchingRecyclerView: RecyclerView
    private lateinit var topHitsRecyclerView: RecyclerView
    private lateinit var newEpisodesRecyclerView: RecyclerView
    private lateinit var latestRecyclerView: RecyclerView // Restored!

    // --- Adapters (ALL ARE HERE) ---
    private lateinit var sliderAdapter: SliderAdapter
    private lateinit var continueWatchingAdapter: ContinueWatchingAdapter
    private lateinit var topHitsAdapter: AnimeAdapter
    private lateinit var newEpisodesAdapter: AnimeAdapter
    private lateinit var latestAdapter: AnimeAdapter // Restored!

    // --- Utilities ---
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupToolbar()
        setupRecyclerViews()
        setupBottomNavigation()
        setupSeeAllButtons()
        loadData()
        observeWatchHistory() // Restored call
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        mainSliderViewPager = findViewById(R.id.main_slider_view_pager)
        bottomNavigationView = findViewById(R.id.bottom_navigation)

        // Fi
        // nd all RecyclerViews and sections
        continueWatchingSection = findViewById(R.id.continue_watching_section)
        continueWatchingRecyclerView = findViewById(R.id.continue_watching_recycler_view)
        topHitsRecyclerView = findViewById(R.id.top_hits_recycler_view)
        newEpisodesRecyclerView = findViewById(R.id.new_episodes_recycler_view)
        latestRecyclerView = findViewById(R.id.latest_recycler_view) // Restored

        seeAllContinueWatching = findViewById(R.id.see_all_continue_watching)
        seeAllTopHits = findViewById(R.id.see_all_top_hits)
        seeAllNewEpisodes = findViewById(R.id.see_all_new_episodes)
        seeAllLatest = findViewById(R.id.see_all_latest)

        shimmerTopHits = findViewById(R.id.shimmer_top_hits)
        shimmerNewEpisodes = findViewById(R.id.shimmer_new_episodes)
        shimmerLatestUpdates = findViewById(R.id.shimmer_latest_updates)
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

        // You can also add one for continue watching if you create a separate screen for it
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    // This method is now restored to add the search icon to the toolbar
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_home_menu, menu)
        return true
    }



    // This method handles clicks on the search icon
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_search -> {
                // Open your search activity
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
        // Continue Watching (Restored)
        continueWatchingAdapter = ContinueWatchingAdapter { watchHistory ->
            openContinueWatchingItem(watchHistory)
        }
        continueWatchingRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = continueWatchingAdapter
        }

        // Top Hits (New Design)
        topHitsAdapter = AnimeAdapter(AnimeAdapter.ViewType.TOP_HIT) { anime -> openAnimeDetails(anime) }
        topHitsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = topHitsAdapter
        }

        // New Episodes (New Design)
        newEpisodesAdapter = AnimeAdapter(AnimeAdapter.ViewType.NEW_RELEASE) { anime -> openAnimeDetails(anime) }
        newEpisodesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = newEpisodesAdapter
        }

        // Latest Updates Grid (Restored)
        latestAdapter = AnimeAdapter(AnimeAdapter.ViewType.GRID) { anime ->
            openAnimeDetails(anime)
        }
        latestRecyclerView.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            adapter = latestAdapter
            // Important: Disable nested scrolling for the grid to make the whole page scroll smoothly
            isNestedScrollingEnabled = false
        }
    }

    private fun setupBottomNavigation() {
        bottomNavigationView.setOnNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    // You are already on the home screen
                    true
                }
                R.id.nav_my_list -> {
                    // Launch your MyListActivity
                    startActivity(Intent(this, MyListActivity::class.java))
                    true
                }
                R.id.nav_download -> {
                    // THIS IS THE FIX: Launch DownloadsActivity using the correct ID
                    startActivity(Intent(this, DownloadsActivity::class.java))
                    true
                }
                R.id.nav_settings -> {
                    // Launch your new SettingsActivity
                     startActivity(Intent(this, SettingsActivity::class.java))
//                    Toast.makeText(this, "Settings Clicked", Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
    }

    // Restored this entire function to populate the Continue Watching section
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

    // The loadData function now fetches data for ALL sections
    private fun loadData() {
        showLoading(true) // <-- START LOADING
        lifecycleScope.launch {
            try {
                val sliderJob = async { sourceManager.fetchMainSlider() }
                val popularJob = async { sourceManager.fetchPopularSeries(1) }
                val latestEpisodesJob = async { sourceManager.fetchHomePageLatestEpisodes() }
                val latestUpdatesJob = async { sourceManager.fetchLatestUpdates(1) } // Restored fetch

                val sliderItems = sliderJob.await()
                val popularSeries = popularJob.await()
                val latestEpisodes = latestEpisodesJob.await()
                val latestUpdates = latestUpdatesJob.await() // Restored await

                if (!isActive) return@launch

                sliderAdapter = SliderAdapter(sliderItems) { anime -> openAnimeDetails(anime) }
                mainSliderViewPager.adapter = sliderAdapter
                setupAutoSwipe(sliderAdapter)

                // Populate all adapters
                topHitsAdapter.submitList(popularSeries.manga.take(10))
                newEpisodesAdapter.submitList(latestEpisodes.take(10))
                latestAdapter.submitList(latestUpdates.manga.take(20)) // Restored populate

                showLoading(false) // <-- STOP LOADING ON SUCCESS

            } catch (e: Exception) {
                if (isActive) {
                    showError("Error loading data: ${e.message}")
                    showLoading(false) // <-- STOP LOADING ON FAILURE
                }
            }
        }
    }

    private fun openContinueWatchingItem(item: WatchHistory) {
        val anime = SAnime(
            url = item.animeUrl,
            title = item.animeTitle,
            thumbnail_url = item.animeThumbnailUrl
        )

        // Convert the stored source String back to an AnimeSource enum
        val source = try {
            AnimeSource.valueOf(item.source.replace(" ", "_").uppercase())
        } catch (e: Exception) {
            null // Fallback if the source name is invalid or not stored
        }
        println("sourceee : ${source}");


        // Call the newly modified newIntentWithResume function
        val intent = AnimeDetailsActivity.newIntentWithResume(
            context = this,
            anime = anime,
            resumeEpisodeUrl = item.episodeUrl,
            source = source // <-- PASS THE ORIGINAL SOURCE
        )

        startActivity(intent)
    }

    private fun setupAutoSwipe(sliderAdapter: SliderAdapter) {
        // Define what the auto-swipe action does
        sliderRunnable = Runnable {
            val currentItem = mainSliderViewPager.currentItem
            val itemCount = sliderAdapter.itemCount
            if (itemCount > 0) {
                mainSliderViewPager.setCurrentItem((currentItem + 1) % itemCount, true)
            }
        }

        // Register a callback to automatically restart the swipe timer
        mainSliderViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                sliderHandler.removeCallbacks(sliderRunnable)
                sliderHandler.postDelayed(sliderRunnable, 3000)
            }
        })

        // Start the first swipe if not already running
        if(::sliderRunnable.isInitialized) {
            sliderHandler.postDelayed(sliderRunnable, 3000)
        }
    }

    private fun openAnimeDetails(anime: SAnime) {
        val source = try {
            // Convert the string from the database (e.g., "FASEL_HD") back to an AnimeSource enum
            anime.source?.let { AnimeSource.valueOf(it) }
        } catch (e: Exception) {
            // Fallback if the source is missing from an old database entry or is invalid
            null
        }
        // Navigate to anime details activity
        val intent = AnimeDetailsActivity.newIntent(this, anime, source)
        startActivity(intent)
        overridePendingTransition(R.anim.scale_in, R.anim.fade_out)
    }

    private fun showLoading(isLoading: Boolean) {
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
    }



    private fun showError(message: String) {
        if (isFinishing || isDestroyed) {
            return
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // Lifecycle methods for auto-swipe
    override fun onPause() {
        super.onPause()
        if(::sliderRunnable.isInitialized) sliderHandler.removeCallbacks(sliderRunnable)
    }

    override fun onResume() {
        super.onResume()
        if(::sliderRunnable.isInitialized) sliderHandler.postDelayed(sliderRunnable, 3000)
    }
}