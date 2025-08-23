//package com.faselhd.app.fragments // Or your actual fragments package
//
//import android.os.Bundle
//import android.view.LayoutInflater
//import android.view.View
//import android.view.ViewGroup
//import android.widget.LinearLayout
//import androidx.fragment.app.Fragment
//import androidx.lifecycle.lifecycleScope
//import androidx.recyclerview.widget.GridLayoutManager
//import androidx.recyclerview.widget.RecyclerView
//import com.example.myapplication.R
//import com.faselhd.app.adapters.MangaAdapter
//import com.faselhd.app.db.AppDatabase
//import com.faselhd.app.models.SManga
//import com.faselhd.app.network.MangaSource
//import com.facebook.shimmer.ShimmerFrameLayout
//import com.faselhd.app.MangaDetailsActivity
//import kotlinx.coroutines.flow.collectLatest
//import kotlinx.coroutines.launch
//
//class FavoriteMangaFragment : Fragment() {
//
//    // --- Views ---
//    private lateinit var recyclerView: RecyclerView
//    private lateinit var emptyStateLayout: LinearLayout
//    private lateinit var shimmerLayout: ShimmerFrameLayout
//
//    // --- Utilities ---
//    private lateinit var favoriteAdapter: MangaAdapter
//    private val db by lazy { AppDatabase.getDatabase(requireContext()) }
//
//    override fun onCreateView(
//        inflater: LayoutInflater,
//        container: ViewGroup?,
//        savedInstanceState: Bundle?
//    ): View? {
//        // Inflate the layout for this fragment
//        return inflater.inflate(R.layout.fragment_favorite_manga, container, false)
//    }
//
//    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
//        super.onViewCreated(view, savedInstanceState)
//
//        initViews(view)
//        setupRecyclerView()
//        observeData()
//    }
//
//    // Refresh data when the user returns to this tab
//    override fun onResume() {
//        super.onResume()
//        observeData()
//    }
//
//    private fun initViews(view: View) {
//        recyclerView = view.findViewById(R.id.favorite_manga_recycler_view)
//        emptyStateLayout = view.findViewById(R.id.empty_state_layout)
//        shimmerLayout = view.findViewById(R.id.shimmer_layout)
//    }
//
//    private fun setupRecyclerView() {
//        favoriteAdapter = MangaAdapter(MangaAdapter.ViewType.GRID) { manga ->
//            openMangaDetails(manga)
//        }
//
//        recyclerView.apply {
//            val spanCount = 3
//            // A helper for adding equal spacing to a grid. You may need to create this class.
//            // val spacing = resources.getDimensionPixelSize(R.dimen.grid_spacing)
//            layoutManager = GridLayoutManager(requireContext(), spanCount)
//            adapter = favoriteAdapter
//
//            // Prevents adding the decoration multiple times
//            if (itemDecorationCount == 0) {
//                // addItemDecoration(GridSpacingItemDecoration(spanCount, spacing, true))
//            }
//        }
//    }
//
//    private fun observeData() {
//        showLoading(true)
//        lifecycleScope.launch {
//            db.mangaFavoriteDao().getAllFavorites().collectLatest { favoritesList ->
//                showLoading(false)
//
//                if (favoritesList.isEmpty()) {
//                    emptyStateLayout.visibility = View.VISIBLE
//                    recyclerView.visibility = View.GONE
//                } else {
//                    emptyStateLayout.visibility = View.GONE
//                    recyclerView.visibility = View.VISIBLE
//
//                    // Map the database Favorite object to the UI SManga object
//                    val mangaList = favoritesList.map {
//                        SManga(
//                            url = it.mangaUrl,
//                            title = it.title,
//                            thumbnail_url = it.thumbnailUrl,
//                            source = it.source
//                        )
//                    }
//                    favoriteAdapter.submitList(mangaList)
//                }
//            }
//        }
//    }
//
//    private fun openMangaDetails(manga: SManga) {
//        val source = try {
//            manga.source?.let { MangaSource.valueOf(it) }
//        } catch (e: Exception) { null }
//
//        val intent = MangaDetailsActivity.newIntent(requireContext(), manga, source)
//        startActivity(intent)
//    }
//
//    private fun showLoading(isLoading: Boolean) {
//        if (isLoading) {
//            shimmerLayout.startShimmer()
//            shimmerLayout.visibility = View.VISIBLE
//            recyclerView.visibility = View.GONE
//            emptyStateLayout.visibility = View.GONE
//        } else {
//            shimmerLayout.stopShimmer()
//            shimmerLayout.visibility = View.GONE
//        }
//    }
//}