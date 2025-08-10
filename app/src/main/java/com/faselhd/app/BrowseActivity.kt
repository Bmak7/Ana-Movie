package com.faselhd.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.faselhd.app.adapters.AnimeAdapter
import com.faselhd.app.models.MangaPage
import com.faselhd.app.models.SAnime
import com.example.myapplication.R
import com.faselhd.app.network.FaselHDSource
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

class BrowseActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var browseAdapter: AnimeAdapter
    private lateinit var layoutManager: GridLayoutManager

    private val faselHDSource by lazy { FaselHDSource(applicationContext) }

    private var currentPage = 1
    private var isLoading = false
    private var hasNextPage = true
    private var browseType: BrowseType? = null

    // Enum to define what content to show
    enum class BrowseType {
        POPULAR_SERIES,
        LATEST_UPDATES
    }

    companion object {
        private const val EXTRA_BROWSE_TYPE = "extra_browse_type"

        fun newIntent(context: Context, browseType: BrowseType): Intent {
            return Intent(context, BrowseActivity::class.java).apply {
                putExtra(EXTRA_BROWSE_TYPE, browseType.name)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)

        val typeName = intent.getStringExtra(EXTRA_BROWSE_TYPE)
        if (typeName == null) {
            finish()
            return
        }
        browseType = BrowseType.valueOf(typeName)

        initViews()
        setupToolbar()
        setupRecyclerView()
        loadMoreItems() // Load the first page
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        recyclerView = findViewById(R.id.browse_recycler_view)
        progressBar = findViewById(R.id.progress_bar)
    }

    private fun setupToolbar() {
        toolbar.title = when (browseType) {
            BrowseType.POPULAR_SERIES -> "Popular Series"
            BrowseType.LATEST_UPDATES -> "Latest Updates"
            else -> "Browse"
        }
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressed() }
    }

    private fun setupRecyclerView() {
        browseAdapter = AnimeAdapter(AnimeAdapter.ViewType.GRID) { anime ->
            val intent = AnimeDetailsActivity.newIntent(this, anime)
            startActivity(intent)
        }
        layoutManager = GridLayoutManager(this, 2)
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = browseAdapter

        // *** PAGINATION LOGIC ***
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val visibleItemCount = layoutManager.childCount
                val totalItemCount = layoutManager.itemCount
                val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                // Check if we are at the end of the list
                if (!isLoading && hasNextPage) {
                    if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount && firstVisibleItemPosition >= 0) {
                        loadMoreItems()
                    }
                }
            }
        })
    }

    private fun loadMoreItems() {
        isLoading = true
        if (currentPage == 1) { // Show center progress bar only for the first page
            progressBar.visibility = View.VISIBLE
        } else {
            // TODO: Show a smaller loading indicator at the bottom of the list
        }

        lifecycleScope.launch {
            try {
                val resultPage: MangaPage = when (browseType) {
                    BrowseType.POPULAR_SERIES -> faselHDSource.fetchPopularSeries(currentPage)
                    BrowseType.LATEST_UPDATES -> faselHDSource.fetchLatestUpdates(currentPage)
                    else -> MangaPage(emptyList(), false)
                }

                // Append new items to the adapter
                val currentList = browseAdapter.currentList.toMutableList()
                currentList.addAll(resultPage.manga)
                browseAdapter.submitList(currentList)

                hasNextPage = resultPage.hasNextPage
                currentPage++
                isLoading = false
                progressBar.visibility = View.GONE

            } catch (e: Exception) {
                isLoading = false
                progressBar.visibility = View.GONE
                Toast.makeText(this@BrowseActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}