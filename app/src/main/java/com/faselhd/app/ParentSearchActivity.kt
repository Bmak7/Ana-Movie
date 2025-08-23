package com.faselhd.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

class ParentSearchActivity : AppCompatActivity(), SourceResultsBottomSheet.OnAnimeSelectedListener {

    private lateinit var searchView: SearchView
    private lateinit var parentRecyclerView: RecyclerView
    private lateinit var progressIndicator: ProgressBar
    private lateinit var emptyTextView: TextView
    private lateinit var filterChipGroup: ChipGroup
    private lateinit var parentAdapter: SearchParentAdapter

    private val sourceManager by lazy { SourceManager(applicationContext) }
    private var searchJob: Job? = null

    // Companion object to easily launch this activity
    companion object {
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
                // Your existing openAnimeDetails logic
                val intent = AnimeDetailsActivity.newIntent(this, anime, anime.source?.let { AnimeSource.valueOf(it) })
                startActivity(intent)
            },
            onSeeAllClick = { sourceResult ->
                // --- THIS IS THE NEW LOGIC ---
                // Launch the Bottom Sheet
                val bottomSheet = SourceResultsBottomSheet.newInstance(
                    sourceResult.source.displayName,
                    sourceResult.results
                )
                bottomSheet.show(supportFragmentManager, "SourceResultsBottomSheet")
                // --- END OF NEW LOGIC ---
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
                        performUnifiedSearch(it)
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
                isChecked = true // Default to all selected
            }
            filterChipGroup.addView(chip)
        }
    }

    override fun onAnimeSelected(anime: SAnime) {
        // You already have the logic! Just copy it from your `onAnimeClick` lambda.
        val intent = AnimeDetailsActivity.newIntent(this, anime, anime.source?.let { AnimeSource.valueOf(it) })
        startActivity(intent)
    }
    private fun performUnifiedSearch(query: String) {
        // Cancel any previous search job to avoid race conditions
        searchJob?.cancel()

        progressIndicator.visibility = View.VISIBLE
        emptyTextView.visibility = View.GONE
        parentAdapter.updateData(emptyList()) // Clear previous results

        searchJob = lifecycleScope.launch {
            try {
                // Get all sources you want to search
                val sourcesToSearch = sourceManager.getAllSources()

                val searchJobs = sourcesToSearch.map { source ->
                    async {
                        try {
                            val source_n = AnimeSource.valueOf(source.displayName.replace(" ", "_").uppercase())
                            // Fetch search results for a single source
                            val page = sourceManager.fetchSearchAnime(1, query, AnimeFilterList(emptyList()), "movie", source_n)

                            //
                            // --- THIS IS THE FIX ---
                            // Before creating the SourceSearchResult, loop through the manga list
                            // and assign the source's name to each individual SAnime object.
                            // We use .copy() as it's the standard practice for immutable data classes.
                            val animeListWithSource = page.manga.map { anime ->
                                anime.copy(source = source_n.name) // source_n.name gives the enum's string name
                            }
                            // --- END OF FIX ---
                            //

                            println("fff search parent (${source.displayName}): ${animeListWithSource.toString()}")
                            // Now, pass the MODIFIED list to the SourceSearchResult
                            SourceSearchResult(source, animeListWithSource)

                        } catch (e: Exception) {
                            // If a single source fails, return an empty result for it
                            SourceSearchResult(source, emptyList())
                        }
                    }
                }

                // Await all parallel searches to complete
                val results = searchJobs.awaitAll()

                // Filter out sources that returned no results
                val successfulResults = results.filter { it.results.isNotEmpty() }

                progressIndicator.visibility = View.GONE

                if (successfulResults.isEmpty()) {
                    emptyTextView.text = "No results found for '$query'"
                    emptyTextView.visibility = View.VISIBLE
                } else {
                    parentAdapter.updateData(successfulResults)
                }

            } catch (e: Exception) {
                progressIndicator.visibility = View.GONE
                emptyTextView.text = "An error occurred during search"
                emptyTextView.visibility = View.VISIBLE
            }
        }
    }


}