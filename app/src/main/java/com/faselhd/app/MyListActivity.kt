// In file: app/src/main/java/com/faselhd/app/MyListActivity.kt

package com.faselhd.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.faselhd.app.adapters.AnimeAdapter
import com.faselhd.app.db.AppDatabase
import com.faselhd.app.models.SAnime
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MyListActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressIndicator: ProgressBar
    private lateinit var emptyTextView: TextView

    private lateinit var animeAdapter: AnimeAdapter
    private val db by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_list)

        initViews()
        setupToolbar()
        setupRecyclerView()
        loadFavorites()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        recyclerView = findViewById(R.id.my_list_recycler_view)
        progressIndicator = findViewById(R.id.progress_indicator)
        emptyTextView = findViewById(R.id.empty_text_view)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun setupRecyclerView() {
        animeAdapter = AnimeAdapter(AnimeAdapter.ViewType.GRID) { anime ->
            val intent = AnimeDetailsActivity.newIntent(this, anime)
            startActivity(intent)
        }
        recyclerView.apply {
            layoutManager = GridLayoutManager(this@MyListActivity, 2)
            adapter = animeAdapter
        }
    }

    private fun loadFavorites() {
        progressIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            db.favoriteDao().getAllFavorites().collectLatest { favoritesList ->
                progressIndicator.visibility = View.GONE

                if (favoritesList.isEmpty()) {
                    emptyTextView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyTextView.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE

                    val animeList = favoritesList.map { favorite ->
                        SAnime(
                            url = favorite.animeUrl,
                            title = favorite.title,
                            thumbnail_url = favorite.thumbnailUrl
                        )
                    }
                    animeAdapter.submitList(animeList)
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish() // Go back to the previous screen (MainActivity)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}