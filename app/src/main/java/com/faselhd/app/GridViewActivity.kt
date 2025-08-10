package com.faselhd.app
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.faselhd.app.adapters.AnimeAdapter
import com.faselhd.app.db.AppDatabase
import com.faselhd.app.models.SAnime
import com.example.myapplication.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
class GridViewActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var gridRecyclerView: RecyclerView
    private lateinit var progressIndicator: CircularProgressIndicator
    private lateinit var emptyTextView: TextView

    private lateinit var animeAdapter: AnimeAdapter

    private val db by lazy { AppDatabase.getDatabase(this) }


    companion object {
        private const val EXTRA_TITLE = "extra_title"

        // A clean way to create an Intent for this activity
        fun newIntent(context: Context, title: String): Intent {
            return Intent(context, GridViewActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_grid_view)

        initViews()
        setupToolbar()
        setupRecyclerView()
        loadContinueWatchingData()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        gridRecyclerView = findViewById(R.id.grid_recycler_view)
        progressIndicator = findViewById(R.id.progress_indicator)
        emptyTextView = findViewById(R.id.empty_text_view)
    }

    private fun setupToolbar() {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Grid View"
        toolbar.title = title
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun setupRecyclerView() {
        animeAdapter = AnimeAdapter(AnimeAdapter.ViewType.GRID) { anime ->
            // When an item in the grid is clicked, open its details page
            val intent = AnimeDetailsActivity.newIntent(this, anime)
            startActivity(intent)
        }
        gridRecyclerView.apply {
            layoutManager = GridLayoutManager(this@GridViewActivity, 2)
            adapter = animeAdapter
        }
    }

    private fun loadContinueWatchingData() {
        progressIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            db.watchHistoryDao().getContinueWatchingHistory().collectLatest { historyList ->
                progressIndicator.visibility = View.GONE

                if (historyList.isEmpty()) {
                    emptyTextView.visibility = View.VISIBLE
                    gridRecyclerView.visibility = View.GONE
                } else {
                    emptyTextView.visibility = View.GONE
                    gridRecyclerView.visibility = View.VISIBLE

                    // Convert WatchHistory objects to SAnime objects for the adapter
                    val animeList = historyList.map { history ->
                        SAnime(
                            url = history.animeUrl,
                            title = history.animeTitle,
                            thumbnail_url = history.animeThumbnailUrl
                        )
                    }
                    animeAdapter.submitList(animeList)
                }
            }
        }
    }

    // Handle the back button in the toolbar
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish() // Close this activity and go back
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}