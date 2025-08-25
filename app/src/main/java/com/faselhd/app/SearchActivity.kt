package com.faselhd.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.faselhd.app.adapters.AnimeAdapter
import com.faselhd.app.adapters.TopSearchesAdapter // You will need to create this simple adapter
import com.faselhd.app.models.AnimeFilterList
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.faselhd.app.models.SAnime
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.SourceManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.faselhd.app.widgets.GridSpacingItemDecoration


class SearchActivity : AppCompatActivity() {

    // --- Views ---
    private lateinit var searchView: SearchView
    private lateinit var filterButton: View
    private lateinit var composeProgress: ComposeView

    // --- Layouts for different states ---
    private lateinit var topSearchesLayout: LinearLayout
    private lateinit var notFoundLayout: LinearLayout

    // --- RecyclerViews and Adapters ---
    private lateinit var topSearchesRecyclerView: RecyclerView
    private lateinit var searchResultsRecyclerView: RecyclerView
    private lateinit var topSearchesAdapter: TopSearchesAdapter
    private lateinit var searchResultsAdapter: AnimeAdapter

    // --- Utilities ---
    private val sourceManager by lazy { SourceManager(applicationContext) }
    private var currentSearchType = "movie" // Default search type

    companion object {
        fun newIntent(context: Context): Intent {
            return Intent(context, SearchActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        initViews()
        setupRecyclerViews()
        setupSearchView()
        setupFilterButton()
        loadTopSearches()
    }

    private fun initViews() {
        searchView = findViewById(R.id.search_view)
        filterButton = findViewById(R.id.filter_button)
        composeProgress = findViewById(R.id.compose_progress)
        topSearchesLayout = findViewById(R.id.top_searches_layout)
        notFoundLayout = findViewById(R.id.not_found_layout)
        topSearchesRecyclerView = findViewById(R.id.top_searches_recycler_view)
        searchResultsRecyclerView = findViewById(R.id.search_results_recycler_view)
    }

    private fun setupRecyclerViews() {
        // Adapter for Top Searches (vertical list)
        topSearchesAdapter = TopSearchesAdapter { anime -> openAnimeDetails(anime) }
        topSearchesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@SearchActivity)
            adapter = topSearchesAdapter
        }

        // Adapter for Search Results (grid)
        searchResultsAdapter = AnimeAdapter(AnimeAdapter.ViewType.GRID) { anime -> openAnimeDetails(anime) }
        searchResultsRecyclerView.apply {
            // 1. Define the span count and spacing for a 3-column grid.
            val spanCount = 3
            val spacing = resources.getDimensionPixelSize(R.dimen.grid_spacing) // Uses your existing 8dp dimen

            // 2. Set the GridLayoutManager with the new span count.
            layoutManager = GridLayoutManager(this@SearchActivity, spanCount)
            adapter = searchResultsAdapter

            // 3. Add the GridSpacingItemDecoration to handle spacing perfectly.
            // This check prevents adding the decorator more than once.
            if (itemDecorationCount == 0) {
                addItemDecoration(GridSpacingItemDecoration(spanCount, spacing, true))
            }
        }
    }

    private fun loadTopSearches() {
        // For demonstration, we'll load "Popular" anime as "Top Searches"
        lifecycleScope.launch {
            try {
                val popularAnime = sourceManager.fetchPopularSeries(1)
                topSearchesAdapter.submitList(popularAnime.manga)
            } catch (e: Exception) {
                showError("Could not load top searches")
            }
        }
    }

    private fun setupSearchView() {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let {
                    if (it.isNotBlank()) {
                        performSearch(it)
                    }
                }
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                // When text is cleared, go back to the top searches view
                if (newText.isNullOrEmpty()) {
                    resetToInitialState()
                }
                return true
            }
        })
        searchView.requestFocus()
    }

    private fun setupFilterButton() {
        filterButton.setOnClickListener {
            showFilterDialog()
        }
    }

    private fun showFilterDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_search_filter, null)
        val radioGroup = dialogView.findViewById<RadioGroup>(R.id.search_type_radio_group)

        // Pre-select the current filter
        when (currentSearchType) {
            "movie" -> radioGroup.check(R.id.radio_movie)
            "series" -> radioGroup.check(R.id.radio_series)
            "anime" -> radioGroup.check(R.id.radio_anime)
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Apply") { dialog, _ ->
                currentSearchType = when (radioGroup.checkedRadioButtonId) {
                    R.id.radio_movie -> "movie"
                    R.id.radio_series -> "series"
                    R.id.radio_anime -> "anime"
                    else -> "movie"
                }
                // If there's already a query, re-run the search with the new filter
                val currentQuery = searchView.query.toString()
                if (currentQuery.isNotBlank()) {
                    performSearch(currentQuery)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .create()
            .show()
    }

    private fun performSearch(query: String) {
        showLoading(true)
        // Hide initial layout and previous results/errors
        topSearchesLayout.visibility = View.GONE
        searchResultsRecyclerView.visibility = View.GONE
        notFoundLayout.visibility = View.GONE
        searchResultsAdapter.submitList(emptyList())

        lifecycleScope.launch {
            try {
                val results = sourceManager.fetchSearchAnime(1, query, AnimeFilterList(emptyList()), currentSearchType)
                showLoading(false)
                if (results.manga.isEmpty()) {
                    notFoundLayout.visibility = View.VISIBLE
                } else {
                    searchResultsRecyclerView.visibility = View.VISIBLE
                    searchResultsAdapter.submitList(results.manga)
                }
            } catch (e: Exception) {
                showLoading(false)
                notFoundLayout.visibility = View.VISIBLE
                showError("Search failed: ${e.message}")
            }
        }
    }

    private fun resetToInitialState() {
        topSearchesLayout.visibility = View.VISIBLE
        searchResultsRecyclerView.visibility = View.GONE
        notFoundLayout.visibility = View.GONE
        searchResultsAdapter.submitList(emptyList())
    }

    private fun openAnimeDetails(anime: SAnime) {
        val intent = AnimeDetailsActivity.newIntent(this, anime, SourceManager.getSelectedSource(applicationContext))
        startActivity(intent)
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

                        // Optional: Add loading text
                        Text(
                            text = "جاري التحميل...",
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(top = 80.dp)
                        )
                    }
                }
            }
        } else {
            composeProgress.visibility = View.GONE
        }
    }
    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

