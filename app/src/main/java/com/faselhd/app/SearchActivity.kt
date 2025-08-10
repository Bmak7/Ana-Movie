package com.faselhd.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.KeyEvent // <-- Make sure this import is present
import com.faselhd.app.adapters.AnimeAdapter
import com.faselhd.app.models.AnimeFilterList
import com.faselhd.app.models.SAnime
import com.faselhd.app.network.FaselHDSource
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.launch
import com.example.myapplication.R


class SearchActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var searchView: SearchView
    private lateinit var searchRecyclerView: RecyclerView
    private lateinit var progressIndicator: CircularProgressIndicator
    private lateinit var composeProgress: ComposeView // Add this

    private lateinit var searchAdapter: AnimeAdapter
    private val faselHDSource by lazy { FaselHDSource(applicationContext) }

    private var currentQuery = ""
    private var currentPage = 1
    private var isLoading = false
    private var hasNextPage = true

    companion object {
        fun newIntent(context: Context): Intent {
            return Intent(context, SearchActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        initViews()
        setupToolbar()
        setupRecyclerView()
        setupSearchView()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        searchView = findViewById(R.id.search_view)
        searchRecyclerView = findViewById(R.id.search_recycler_view)
        composeProgress = findViewById(R.id.compose_progress) // Make sure this exists in XM
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "البحث"
        toolbar.setNavigationOnClickListener { onBackPressed() }
    }

    private fun setupRecyclerView() {
        searchAdapter = AnimeAdapter(AnimeAdapter.ViewType.GRID) { anime ->
            openAnimeDetails(anime)
        }

        searchRecyclerView.apply {
            layoutManager = GridLayoutManager(this@SearchActivity, 2)
            adapter = searchAdapter

            // Add scroll listener for pagination
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    val layoutManager = recyclerView.layoutManager as GridLayoutManager
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                    if (!isLoading && hasNextPage) {
                        if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount
                            && firstVisibleItemPosition >= 0) {
                            loadMoreResults()
                        }
                    }
                }
            })
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
                searchView.clearFocus() // This helps hide the keyboard
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                // Optional: Implement real-time search with debouncing
                return false
            }
        })

        // Auto-focus search view
        searchView.requestFocus()

        // *** THIS IS THE NEW CODE TO FIX TV NAVIGATION ***
        searchView.setOnKeyListener { _, keyCode, event ->
            // Check if the key is "D-pad Down" and it's a key press event
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                // IMPORTANT: Only move focus if there are results to focus on.
                if (searchAdapter.itemCount > 0) {
                    // Manually request focus for the search results RecyclerView
                    searchRecyclerView.requestFocus()
                    // Return true because we've handled this event
                    return@setOnKeyListener true
                }
            }
            // For any other key, return false to let the system handle it
            return@setOnKeyListener false
        }
    }

    private fun performSearch(query: String) {
        currentQuery = query
        currentPage = 1
        hasNextPage = true

        showLoading(true)
        searchAdapter.submitList(emptyList()) // Clear previous results

        lifecycleScope.launch {
            try {
                val searchResults = faselHDSource.fetchSearchAnime(
                    page = currentPage,
                    query = query,
                    filters = AnimeFilterList(emptyList())
                )

                searchAdapter.submitList(searchResults.manga)
                hasNextPage = searchResults.hasNextPage
                showLoading(false)

                if (searchResults.manga.isEmpty()) {
                    showError("لا توجد نتائج للبحث عن: $query")
                }

            } catch (e: Exception) {
                showLoading(false)
                showError("خطأ في البحث: ${e.message}")
            }
        }
    }

    private fun loadMoreResults() {
        if (currentQuery.isBlank()) return

        isLoading = true
        currentPage++

        lifecycleScope.launch {
            try {
                val searchResults = faselHDSource.fetchSearchAnime(
                    page = currentPage,
                    query = currentQuery,
                    filters = AnimeFilterList(emptyList())
                )

                val currentList = searchAdapter.currentList.toMutableList()
                currentList.addAll(searchResults.manga)
                searchAdapter.submitList(currentList)

                hasNextPage = searchResults.hasNextPage
                isLoading = false

            } catch (e: Exception) {
                isLoading = false
                currentPage-- // Revert page increment on error
                showError("خطأ في تحميل المزيد: ${e.message}")
            }
        }
    }

    private fun openAnimeDetails(anime: SAnime) {
        val intent = AnimeDetailsActivity.newIntent(this, anime)
        startActivity(intent)
    }

//    private fun showLoading(show: Boolean) {
//        progressIndicator.visibility = if (show) View.VISIBLE else View.GONE
//    }
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

//package com.faselhd.app
//
//import android.content.Context
//import android.content.Intent
//import android.os.Bundle
//import android.view.View
//import android.widget.TextView
//import android.widget.Toast
//import androidx.appcompat.app.AppCompatActivity
//import androidx.lifecycle.lifecycleScope
//import androidx.recyclerview.widget.GridLayoutManager
//import androidx.recyclerview.widget.RecyclerView
//import com.example.myapplication.R
//import com.faselhd.app.adapters.AnimeAdapter
//import com.faselhd.app.models.AnimeFilterList
//import com.faselhd.app.models.SAnime
//import com.faselhd.app.network.FaselHDSource
//import com.google.android.material.appbar.MaterialToolbar
//import com.google.android.material.progressindicator.CircularProgressIndicator
//import kotlinx.coroutines.launch
//
//class SearchActivity : AppCompatActivity() {
//
//    private lateinit var toolbar: MaterialToolbar
//    private lateinit var searchResultsRecyclerView: RecyclerView
//    private lateinit var progressIndicator: CircularProgressIndicator
//    private lateinit var emptyTextView: TextView
//
//    private lateinit var searchAdapter: AnimeAdapter
//    private val faselHDSource by lazy { FaselHDSource(applicationContext) }
//
//    companion object {
//        private const val EXTRA_QUERY = "extra_query"
//
//        fun newIntent(context: Context, query: String): Intent {
//            return Intent(context, SearchActivity::class.java).apply {
//                putExtra(EXTRA_QUERY, query)
//            }
//        }
//    }
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_search)
//
//        val query = intent.getStringExtra(EXTRA_QUERY)
//        if (query.isNullOrBlank()) {
//            finish()
//            return
//        }
//
//        initViews()
//        setupToolbar(query)
//        setupRecyclerView()
//        performSearch(query)
//    }
//
//    private fun initViews() {
//        toolbar = findViewById(R.id.toolbar)
//        searchResultsRecyclerView = findViewById(R.id.search_results_recycler_view)
//        progressIndicator = findViewById(R.id.progress_indicator)
//        emptyTextView = findViewById(R.id.empty_text_view)
//    }
//
//    private fun setupToolbar(query: String) {
//        toolbar.title = "نتائج البحث عن: '$query'"
//        toolbar.setNavigationOnClickListener { onBackPressed() }
//    }
//
//    private fun setupRecyclerView() {
//        searchAdapter = AnimeAdapter(AnimeAdapter.ViewType.GRID) { anime ->
//            openAnimeDetails(anime)
//        }
//        searchResultsRecyclerView.apply {
//            layoutManager = GridLayoutManager(this@SearchActivity, 2)
//            adapter = searchAdapter
//        }
//    }
//
//    private fun performSearch(query: String) {
//        showLoading(true)
//        lifecycleScope.launch {
//            try {
//                // We pass an empty filter list for a simple query-based search
//                val searchResultPage = faselHDSource.fetchSearchAnime(1, query, AnimeFilterList(emptyList()))
//
//                if (searchResultPage.manga.isEmpty()) {
//                    showEmptyView(true)
//                } else {
//                    searchAdapter.submitList(searchResultPage.manga)
//                    showEmptyView(false)
//                }
//
//                showLoading(false)
//            } catch (e: Exception) {
//                showLoading(false)
//                Toast.makeText(this@SearchActivity, "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
//            }
//        }
//    }
//
//    private fun openAnimeDetails(anime: SAnime) {
//        val intent = AnimeDetailsActivity.newIntent(this, anime)
//        startActivity(intent)
//    }
//
//    private fun showLoading(isLoading: Boolean) {
//        progressIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
//    }
//
//    private fun showEmptyView(show: Boolean) {
//        emptyTextView.visibility = if (show) View.VISIBLE else View.GONE
//    }
//}