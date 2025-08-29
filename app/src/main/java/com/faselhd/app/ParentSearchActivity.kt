package com.faselhd.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.faselhd.app.adapters.SearchParentAdapter
import com.faselhd.app.adapters.SourceSearchResult
import com.faselhd.app.models.AnimeFilterList
import com.faselhd.app.models.SAnime
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.SourceManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ParentSearchActivity : AppCompatActivity(), SourceResultsBottomSheet.OnAnimeSelectedListener {

    private lateinit var searchView: SearchView
    private lateinit var parentRecyclerView: RecyclerView
    private lateinit var progressIndicator: ProgressBar
    private lateinit var emptyTextView: TextView
    private lateinit var filterChipGroup: ChipGroup
    private lateinit var parentAdapter: SearchParentAdapter

    private val sourceManager by lazy { SourceManager(applicationContext) }
    private var searchJob: Job? = null

    // For thread-safe updates to the results list
    private val resultsMutex = Mutex()
    private val currentResults = mutableListOf<SourceSearchResult>()
    private var searchInProgress = false
    private var completedSources = 0
    private var totalSources = 0

    companion object {
        private const val TAG = "ParentSearchActivity"
        private const val SEARCH_TIMEOUT_MS = 30000L // 30 seconds per source

        fun newIntent(context: Context): Intent {
            return Intent(context, ParentSearchActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent_search)

        initViews()
        setupRecyclerView()
        setupSearchView()
        setupFilterChips()
    }

    private fun initViews() {
        searchView = findViewById(R.id.search_view)
        parentRecyclerView = findViewById(R.id.parent_recycler_view)
        progressIndicator = findViewById(R.id.progress_indicator)
        emptyTextView = findViewById(R.id.empty_text_view)
        filterChipGroup = findViewById(R.id.filter_chip_group)
    }

    private fun setupRecyclerView() {
        parentAdapter = SearchParentAdapter(
            onAnimeClick = { anime ->
                val intent = AnimeDetailsActivity.newIntent(this, anime, anime.source?.let { AnimeSource.valueOf(it) })
                startActivity(intent)
            },
            onSeeAllClick = { sourceResult ->
                val bottomSheet = SourceResultsBottomSheet.newInstance(
                    sourceResult.source.displayName,
                    sourceResult.results
                )
                bottomSheet.show(supportFragmentManager, "SourceResultsBottomSheet")
            }
        )
        parentRecyclerView.layoutManager = LinearLayoutManager(this)
        parentRecyclerView.adapter = parentAdapter
    }

    private fun setupSearchView() {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let {
                    if (it.isNotBlank()) {
                        performIncrementalSearch(it.trim())
                    }
                }
                searchView.clearFocus()
                return true
            }
            override fun onQueryTextChange(newText: String?) = false
        })
    }

    private fun setupFilterChips() {
        val filterTypes = listOf("Movies", "TV Series", "Anime", "Asian")
        filterTypes.forEach { filterName ->
            val chip = Chip(this).apply {
                text = filterName
                isCheckable = true
                isChecked = true
            }
            filterChipGroup.addView(chip)
        }
    }

    override fun onAnimeSelected(anime: SAnime) {
        val intent = AnimeDetailsActivity.newIntent(this, anime, anime.source?.let { AnimeSource.valueOf(it) })
        startActivity(intent)
    }

    private fun getSelectedFilterType(): String {
        val checkedChips = mutableListOf<String>()
        for (i in 0 until filterChipGroup.childCount) {
            val chip = filterChipGroup.getChildAt(i) as? Chip
            if (chip?.isChecked == true) {
                checkedChips.add(chip.text.toString().lowercase())
            }
        }

        // Return appropriate type based on selection
        return when {
            checkedChips.contains("movies") -> "movie"
            checkedChips.contains("tv series") -> "tv"
            checkedChips.contains("anime") -> "anime"
            else -> "movie" // Default
        }
    }

    private fun performIncrementalSearch(query: String) {
        searchJob?.cancel()

        // Reset state
        searchInProgress = true
        completedSources = 0
        currentResults.clear()

        progressIndicator.visibility = View.VISIBLE
        emptyTextView.visibility = View.GONE
        parentAdapter.updateData(emptyList())

        searchJob = lifecycleScope.launch {
            try {
                Log.d(TAG, "Starting incremental search for query: $query")

                val sourcesToSearch = sourceManager.getAllSources()
                totalSources = sourcesToSearch.size
                val filterType = getSelectedFilterType()
                val emptyFilterList = AnimeFilterList(emptyList())

                Log.d(TAG, "Searching ${sourcesToSearch.size} sources with filter type: $filterType")

                // Launch all searches concurrently, but update UI as each completes
                val searchJobs = sourcesToSearch.map { source ->
                    async {
                        val result = searchSingleSource(source, query, filterType, emptyFilterList)

                        // Update UI immediately when this source completes
                        if (result != null && result.results.isNotEmpty()) {
                            updateUIWithNewResult(result)
                        }

                        // Always increment completed count
                        incrementCompletedSources()

                        result
                    }
                }

                // Wait for all to complete (this doesn't block UI updates)
                searchJobs.forEach { it.await() }

                // Final cleanup
                searchInProgress = false
                progressIndicator.visibility = View.GONE

                Log.d(TAG, "Search completed. Total results: ${currentResults.size}/$totalSources sources")

                // Show empty message if no results found
                if (currentResults.isEmpty()) {
                    emptyTextView.text = "No results found for '$query'"
                    emptyTextView.visibility = View.VISIBLE
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in incremental search", e)
                searchInProgress = false
                progressIndicator.visibility = View.GONE
                emptyTextView.text = "An error occurred during search: ${e.message}"
                emptyTextView.visibility = View.VISIBLE
            }
        }
    }

    private suspend fun updateUIWithNewResult(newResult: SourceSearchResult) {
        resultsMutex.withLock {
            // Add the new result to our list
            currentResults.add(newResult)

            // Update UI on main thread
            lifecycleScope.launch {
                Log.d(TAG, "Adding results from ${newResult.source.displayName}: ${newResult.results.size} items")

                // Sort results by source name for consistent display
                val sortedResults = currentResults.sortedBy { it.source.displayName }
                parentAdapter.updateData(sortedResults)

                // Hide empty text if we have results
                if (emptyTextView.visibility == View.VISIBLE) {
                    emptyTextView.visibility = View.GONE
                }
            }
        }
    }

    private suspend fun incrementCompletedSources() {
        resultsMutex.withLock {
            completedSources++

            // Update progress indicator if still searching
            lifecycleScope.launch {
                if (searchInProgress) {
                    // You could update a progress text or progress bar here
                    Log.d(TAG, "Progress: $completedSources/$totalSources sources completed")
                }

                // Hide progress when all sources are done
                if (completedSources >= totalSources) {
                    progressIndicator.visibility = View.GONE
                }
            }
        }
    }

    private suspend fun searchSingleSource(
        source: AnimeSource,
        query: String,
        filterType: String,
        filterList: AnimeFilterList
    ): SourceSearchResult? {
        return try {
            Log.d(TAG, "Searching source: ${source.displayName}")

            val result = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                val page = sourceManager.fetchSearchAnime(1, query, filterList, filterType, source)

                // Assign source to each anime result
                val animeListWithSource = page.manga.map { anime ->
                    // Create a copy with the source assigned
                    anime.apply {
                        this.source = source.name
                    }
                }

                SourceSearchResult(source, animeListWithSource)
            }

            if (result == null) {
                Log.w(TAG, "Search timeout for source: ${source.displayName}")
                return null
            }

            Log.d(TAG, "Source ${source.displayName} returned ${result.results.size} results")
            result

        } catch (e: Exception) {
            Log.e(TAG, "Error searching source ${source.displayName}", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        searchJob?.cancel()
    }
}