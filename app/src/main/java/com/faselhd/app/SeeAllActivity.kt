package com.faselhd.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.faselhd.app.adapters.SeeAllAdapter
import com.faselhd.app.models.MangaPage // Make sure this import exists
import com.faselhd.app.models.SAnime
import com.faselhd.app.network.SourceManager
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

class SeeAllActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressIndicator: ComposeView
    private lateinit var seeAllAdapter: SeeAllAdapter
    private val sourceManager by lazy { SourceManager(applicationContext) }

    companion object {
        private const val EXTRA_LIST_TYPE = "extra_list_type"
        private const val EXTRA_TITLE = "extra_title"

        fun newIntent(context: Context, listType: String, title: String): Intent {
            return Intent(context, SeeAllActivity::class.java).apply {
                putExtra(EXTRA_LIST_TYPE, listType)
                putExtra(EXTRA_TITLE, title)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_see_all)

        val listType = intent.getStringExtra(EXTRA_LIST_TYPE)
        val title = intent.getStringExtra(EXTRA_TITLE)

        if (listType == null || title == null) {
            finish()
            return
        }

        initViews()
        setupToolbar(title)
        setupRecyclerView()
        fetchData(listType)
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        recyclerView = findViewById(R.id.see_all_recycler_view)
        progressIndicator = findViewById(R.id.compose_progress)
    }

    private fun setupToolbar(title: String) {
        toolbar.title = title
        toolbar.setNavigationOnClickListener { onBackPressed() }
    }

    private fun setupRecyclerView() {
        seeAllAdapter = SeeAllAdapter { anime ->
            // Your logic to open AnimeDetailsActivity
            val intent = AnimeDetailsActivity.newIntent(this, anime, null)
            startActivity(intent)
        }
        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@SeeAllActivity)
            adapter = seeAllAdapter
        }
    }

    private fun fetchData(listType: String) {
        showLoading(true)
        lifecycleScope.launch {
            try {
                val result: MangaPage = when (listType) {
                    "TOP_HITS" -> sourceManager.fetchPopularSeries(1)
                    "NEW_EPISODES" -> {
                        val episodesList = sourceManager.fetchHomePageLatestEpisodes()
                        MangaPage(manga = episodesList, hasNextPage = false)
                    }
                    "LATEST_UPDATES" -> sourceManager.fetchLatestUpdates(1)
                    else -> throw IllegalArgumentException("Unknown list type: $listType")
                }
                seeAllAdapter.submitList(result.manga)
            } catch (e: Exception) {
                Toast.makeText(this@SeeAllActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        progressIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
        // You can set content for your compose progress bar here
    }
}