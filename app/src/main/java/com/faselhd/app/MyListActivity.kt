package com.faselhd.app

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.faselhd.app.adapters.AnimeAdapter
import com.faselhd.app.db.AppDatabase
import com.faselhd.app.models.SAnime
import com.faselhd.app.network.AnimeSource
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MyListActivity : AppCompatActivity() {

    // --- Views ---
    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateLayout: LinearLayout
    private lateinit var shimmerLayout: ShimmerFrameLayout
    private lateinit var bottomNavigationView: BottomNavigationView

    // --- Utilities ---
    private lateinit var animeAdapter: AnimeAdapter
    private val db by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_list)

        initViews()
        setupToolbar()
        setupRecyclerView()
        setupBottomNavigation()
        observeFavorites()
    }

    // onResume is important to refresh the list if the user removes an item
    // and then comes back to this screen.
    override fun onResume() {
        super.onResume()
        observeFavorites()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        recyclerView = findViewById(R.id.my_list_recycler_view)
        emptyStateLayout = findViewById(R.id.empty_state_layout)
        shimmerLayout = findViewById(R.id.shimmer_my_list)
        bottomNavigationView = findViewById(R.id.bottom_navigation)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        // The back button is handled by onOptionsItemSelected
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.my_list_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_search -> {
                startActivity(SearchActivity.newIntent(this))
                true
            }
            // The default back button action
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerView() {
        animeAdapter = AnimeAdapter(AnimeAdapter.ViewType.GRID) { anime ->
            val source = try {
                anime.source?.let { AnimeSource.valueOf(it) }
            } catch (e: Exception) { null }
            val intent = AnimeDetailsActivity.newIntent(this, anime, source)
            startActivity(intent)
        }
        recyclerView.apply {
            layoutManager = GridLayoutManager(this@MyListActivity, 2)
            adapter = animeAdapter
        }
    }

    private fun setupBottomNavigation() {
        // Set the "My List" item as selected
        bottomNavigationView.selectedItemId = R.id.nav_my_list

        // Handle navigation to other screens
        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    // Go back to MainActivity
                    startActivity(Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                    true
                }
                R.id.nav_my_list -> true // Already here
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

    private fun observeFavorites() {
        showLoading(true)
        lifecycleScope.launch {
            db.favoriteDao().getAllFavorites().collectLatest { favoritesList ->
                showLoading(false)

                if (favoritesList.isEmpty()) {
                    emptyStateLayout.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyStateLayout.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE

                    val animeList = favoritesList.map { SAnime(
                        url = it.animeUrl, title = it.title,
                        thumbnail_url = it.thumbnailUrl, source = it.source
                    )}
                    animeAdapter.submitList(animeList)
                }
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        if (isLoading) {
            shimmerLayout.startShimmer()
            shimmerLayout.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            emptyStateLayout.visibility = View.GONE
        } else {
            shimmerLayout.stopShimmer()
            shimmerLayout.visibility = View.GONE
        }
    }
}