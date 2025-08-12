package com.faselhd.app

import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.faselhd.app.adapters.AnimeAdapter
import com.faselhd.app.models.SAnime
import com.faselhd.app.network.FaselHDSource
import com.example.myapplication.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.launch
import android.view.KeyEvent // <-- Make sure this is imported

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.faselhd.app.adapters.ContinueWatchingAdapter
import com.faselhd.app.db.AppDatabase
import com.faselhd.app.models.WatchHistory
import kotlinx.coroutines.flow.collectLatest
import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import androidx.core.widget.NestedScrollView
import androidx.drawerlayout.widget.DrawerLayout.DrawerListener // Add this import
import androidx.viewpager2.widget.ViewPager2
import com.faselhd.app.adapters.SliderAdapter
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive


class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {


    // 1. Declare the permission launcher
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Permission is granted. You can now post notifications.
                Toast.makeText(this, "Notifications enabled", Toast.LENGTH_SHORT).show()
            } else {
                // Explain to the user that the feature is unavailable because the
                // feature requires a permission that the user has denied.
                Toast.makeText(this, "Notifications are disabled. Downloads will not show progress.", Toast.LENGTH_LONG).show()
            }
        }

    private val requestStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Storage permission is required for downloads", Toast.LENGTH_LONG).show()
            }
        }

    private lateinit var toolbar: MaterialToolbar
//    private lateinit var searchEditText: TextInputEditText
    private lateinit var popularRecyclerView: RecyclerView
    private lateinit var latestRecyclerView: RecyclerView
//    private lateinit var progressIndicator: CircularProgressIndicator
    private lateinit var composeProgress: ComposeView
    private lateinit var fabSearch: ExtendedFloatingActionButton

    private lateinit var popularAdapter: AnimeAdapter
    private lateinit var latestAdapter: AnimeAdapter

    private val faselHDSource by lazy { FaselHDSource(applicationContext) }

    private lateinit var continueWatchingRecyclerView: RecyclerView
    private lateinit var continueWatchingAdapter: ContinueWatchingAdapter
    private lateinit var continueWatchingSection: LinearLayout

    private lateinit var mainSliderViewPager: ViewPager2
    private lateinit var latestEpisodesRecyclerView: RecyclerView
    private lateinit var latestEpisodesAdapter: AnimeAdapter

    // --- NEW PROPERTIES FOR AUTO-SWIPE ---
    private val sliderHandler = Handler(Looper.getMainLooper())
    private lateinit var sliderRunnable: Runnable

    private lateinit var fabDownloads: ExtendedFloatingActionButton // Renamed variable

    private val db by lazy { AppDatabase.getDatabase(this) }

    private lateinit var btnSeeAllPopular: MaterialButton // Add this
    private lateinit var btnSeeAllLatest: MaterialButton // Add this

    private lateinit var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var noInternetLayout: LinearLayout
    private lateinit var btnRefresh: MaterialButton
    private lateinit var nestedScrollView: NestedScrollView

    private lateinit var btnSeeAllContinueWatching: MaterialButton // Add this


    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle




    private fun setupFab() {
        // The search functionality is now only in the top search bar
//        searchEditText.setOnEditorActionListener { _, _, _ ->
//            val query = searchEditText.text.toString().trim()
//            if (query.isNotBlank()) {
//                performQuickSearch(query) // Or open SearchActivity
//            }
//            true
//        }

        // *** THIS IS THE NEW FAB LOGIC ***
        fabDownloads.setOnClickListener {
            // Create an intent to launch DownloadsActivity
            val intent = Intent(this, DownloadsActivity::class.java)
            startActivity(intent)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupToolbar()
        setupDrawer()
        setupRecyclerViews()
        setupFab() // Call the renamed setup function
        setupSwipeToRefresh() // Add this
//        setupSearchFunctionality()
        loadData()
        observeWatchHistory() // Start observing changes
        askForNotificationPermission()
        askForStoragePermission() // <-- ADD THIS CALL


    }



    // It's good practice to start and stop the auto-swipe with the activity's lifecycle
    override fun onPause() {
        super.onPause()
        // ** THE FIX IS HERE **
        // Check if sliderRunnable has been initialized before trying to use it.
        if (::sliderRunnable.isInitialized) {
            sliderHandler.removeCallbacks(sliderRunnable)
        }
    }

    override fun onResume() {
        super.onResume()
        // Check if the runnable has been initialized before trying to start it
        if (::sliderRunnable.isInitialized) {
            sliderHandler.postDelayed(sliderRunnable, 3000) // Restart auto-swipe when the app comes back
        }
    }

    // 3. Add the function to ask for permission
    private fun askForNotificationPermission() {
        // This is only necessary for API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                // Permission is already granted
            } else {
                // Directly ask for the permission
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Pass the event to the ActionBarDrawerToggle.
        // If it returns true, it means the drawer toggle has handled the event.
        if (toggle.onOptionsItemSelected(item)) {
            return true
        }

        // Handle your other app bar items, like search
        when (item.itemId) {
            R.id.action_search -> {
                openSearchActivity()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showChangeUrlDialog() {
        // Inflate the custom layout you just created
        val dialogView = layoutInflater.inflate(R.layout.dialog_change_url, null)
        val urlInput = dialogView.findViewById<EditText>(R.id.url_input_edittext)
        val okButton = dialogView.findViewById<Button>(R.id.button_ok)
        val cancelButton = dialogView.findViewById<Button>(R.id.button_cancel)

        // Create the AlertDialog using the custom view
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        // Set the current URL in the EditText
        urlInput.setText(FaselHDSource.getBaseUrl(applicationContext))

        // Set a listener for the OK button
        okButton.setOnClickListener {
            val newUrl = urlInput.text.toString().trim()
            if (newUrl.isNotEmpty() && android.util.Patterns.WEB_URL.matcher(newUrl).matches()) {
                FaselHDSource.setBaseUrl(applicationContext, newUrl)
                Toast.makeText(this, "Base URL updated. Restarting...", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                recreate() // Restart the activity to apply changes
            } else {
                Toast.makeText(this, "Please enter a valid URL", Toast.LENGTH_SHORT).show()
            }
        }

        // Set a listener for the Cancel button
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        // This makes the "Done" button on the on-screen keyboard trigger the "OK" button
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                okButton.performClick()
                return@setOnEditorActionListener true
            }
            return@setOnEditorActionListener false
        }

        // Show the dialog
        dialog.show()

        // Explicitly request focus for the EditText so the user can start typing immediately
        // or see that it's the active element. This is crucial for TV.
        urlInput.requestFocus()
    }

    private fun askForStoragePermission() {
        // We only need to ask for this on older Android versions.
        // On Android 10+, requestLegacyExternalStorage handles it.
        // On Android 11+, Scoped Storage would be the modern way, but legacy still works.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) { // Check for Android 10 or lower
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                // Permission is not granted, so we request it.
                requestStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        // For Android 11 (R) and above, you don't need to request this specific permission
        // if using the Downloads directory, as apps have write access to it by default.
        // The `requestLegacyExternalStorage` flag helps with compatibility.
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
//        searchEditText = findViewById(R.id.search_edit_text)
        popularRecyclerView = findViewById(R.id.popular_recycler_view)
        latestRecyclerView = findViewById(R.id.latest_recycler_view)
        composeProgress = findViewById(R.id.compose_progress)
        mainSliderViewPager = findViewById(R.id.main_slider_view_pager)
        latestEpisodesRecyclerView = findViewById(R.id.latest_episodes_recycler_view)

//        fabSearch = findViewById(R.id.fab_search)
        continueWatchingRecyclerView = findViewById(R.id.continue_watching_recycler_view)
        continueWatchingSection = findViewById(R.id.continue_watching_section)
        fabDownloads = findViewById(R.id.fab_downloads)

        btnSeeAllPopular = findViewById(R.id.btn_see_all_popular) // Assuming this is the ID in your XML
        btnSeeAllLatest = findViewById(R.id.btn_see_all_latest) // Assuming this is the ID in your XML

        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
        noInternetLayout = findViewById(R.id.no_internet_layout)
        btnRefresh = findViewById(R.id.btn_refresh)
        nestedScrollView = findViewById(R.id.nested_scroll_view)
        btnSeeAllContinueWatching = findViewById(R.id.btn_see_all_continue_watching)
        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.nav_view)
    }

    private fun setupDrawer() {
        toggle = ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)

        // **** ADD THIS NEW DRAWER LISTENER ****
        drawerLayout.addDrawerListener(object : DrawerListener {
            override fun onDrawerOpened(drawerView: View) {
                // When the drawer opens, request focus on the navigation view itself.
                // This ensures the D-pad starts inside the drawer.
                navigationView.requestFocus()
            }
            override fun onDrawerClosed(drawerView: View) {
                // Optional: You could move focus back to a default main content view here if needed.
            }
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) { /* Do nothing */ }
            override fun onDrawerStateChanged(newState: Int) { /* Do nothing */ }
        })

        drawerLayout.addDrawerListener(toggle) // Your existing listener for the toggle
        toggle.syncState()

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        navigationView.setNavigationItemSelectedListener(this)
    }

    // **** ADD THIS NEW FUNCTION TO HANDLE KEY EVENTS FOR THE DRAWER ****
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // If the drawer is open, let the navigation view handle the key events
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // If user presses right, close the drawer
                drawerLayout.closeDrawer(GravityCompat.START)
                return true
            }
            // Let the system handle DPAD_UP, DPAD_DOWN, and ENTER/DPAD_CENTER
            return super.onKeyDown(keyCode, event)
        }

        // If the drawer is CLOSED, and the user presses left, open the drawer
        val mainContent: View = findViewById(R.id.main_slider_view_pager) // A reference view in your main layout
        if (mainContent.isFocused && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            drawerLayout.openDrawer(GravityCompat.START)
            return true
        }

        return super.onKeyDown(keyCode, event)
    }




    private fun setupSwipeToRefresh() {
        swipeRefreshLayout.setOnRefreshListener {
            checkInternetAndLoadData()
        }
        btnRefresh.setOnClickListener {
            checkInternetAndLoadData()
        }
    }


    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    private fun checkInternetAndLoadData() {
        if (isNetworkAvailable()) {
            nestedScrollView.visibility = View.VISIBLE
            noInternetLayout.visibility = View.GONE
            loadData()
        } else {
            nestedScrollView.visibility = View.GONE
            noInternetLayout.visibility = View.VISIBLE
            showLoadingg(false)
            swipeRefreshLayout.isRefreshing = false
        }
    }


    private fun showLoadingg(show: Boolean) {
        composeProgress.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            composeProgress.setContent {
                androidx.compose.material3.MaterialTheme {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(100.dp)
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(64.dp),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                            strokeWidth = 4.dp,
                        )

                        // Optional: Add loading text
                        androidx.compose.material3.Text(
                            text = "Loading...",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(top = 80.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun IndeterminateCircularIndicator() {
        var loading by remember { mutableStateOf(false) }

        Button(onClick = { loading = true }, enabled = !loading) {
            Text("Start loading")
        }

        if (!loading) return

        CircularProgressIndicator(
            modifier = Modifier.width(64.dp),
            color = MaterialTheme.colors.secondary,
            backgroundColor = MaterialTheme.colors.surface,
        )
    }

    // **** MODIFIED FUNCTION ****
    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        // The toolbar is now responsible for showing the navigation button.
        // The ActionBarDrawerToggle will automatically use it.
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }



    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)

        val searchItem = menu?.findItem(R.id.action_search)
        val searchView = searchItem?.actionView

        // Find the button within your custom layout
        val searchButton = searchView?.findViewById<View>(R.id.search_button)

        // The original click listener
        searchButton?.setOnClickListener {
            openSearchActivity()
        }

        // *** THIS IS THE NEW, CRITICAL CODE ***
        // Programmatically handle the D-pad navigation
        searchButton?.setOnKeyListener { view, keyCode, event ->
            // Check if the key is "D-pad Down" and if it's a "key down" event
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                // Find the target view in your main content
                val targetView = findViewById<ViewPager2>(R.id.main_slider_view_pager)
                // Manually request focus for the target view
                targetView?.requestFocus()
                // Return true to indicate that we have handled this event
                return@setOnKeyListener true
            }
            // Return false for all other keys to let the system handle them
            return@setOnKeyListener false
        }

        return true
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_my_list -> {
                val intent = Intent(this, MyListActivity::class.java)
                startActivity(intent)
            }
            R.id.nav_downloads -> {
                val intent = Intent(this, DownloadsActivity::class.java)
                startActivity(intent)
            }
            R.id.nav_change_url -> {
                showChangeUrlDialog()
            }
            R.id.nav_home -> {
                // Already on home, do nothing or refresh
            }
        }
        // Close the drawer after an item is tapped
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onBackPressed() {
        // If the drawer is open, the back button should close it
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            // Otherwise, perform the default back action
            super.onBackPressed()
        }
    }


//    override fun onOptionsItemSelected(item: MenuItem): Boolean {
//        // This will now only handle other menu items, not the search one.
//        return when (item.itemId) {
//            // R.id.action_search case is now handled above, so it can be removed from here if it exists.
//            else -> super.onOptionsItemSelected(item)
//        }
//    }
    private fun setupRecyclerViews() {
        // Popular anime horizontal list
        popularAdapter = AnimeAdapter(AnimeAdapter.ViewType.HORIZONTAL) { anime ->
            openAnimeDetails(anime)
            openAnimeDetails(anime)
        }
        popularRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = popularAdapter
        }

        // Latest anime grid
        latestAdapter = AnimeAdapter(AnimeAdapter.ViewType.GRID) { anime ->
            openAnimeDetails(anime)
        }
        latestRecyclerView.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            adapter = latestAdapter
        }

        // Setup for the new Latest Episodes slider
        latestEpisodesAdapter = AnimeAdapter(AnimeAdapter.ViewType.HORIZONTAL) { anime ->
            openAnimeDetails(anime)
        }
        latestEpisodesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = latestEpisodesAdapter
        }

        // Continue Watching horizontal list
        continueWatchingAdapter = ContinueWatchingAdapter { watchHistory ->
            openContinueWatchingItem(watchHistory)
        }
        continueWatchingRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = continueWatchingAdapter
        }

        // ** SET ONCLICK LISTENERS **
        btnSeeAllPopular.setOnClickListener {
            val intent = BrowseActivity.newIntent(this, BrowseActivity.BrowseType.POPULAR_SERIES)
            startActivity(intent)
        }

        btnSeeAllLatest.setOnClickListener {
            val intent = BrowseActivity.newIntent(this, BrowseActivity.BrowseType.LATEST_UPDATES)
            startActivity(intent)
        }

        // ** ADD THIS ONCLICK LISTENER **
        btnSeeAllContinueWatching.setOnClickListener {
            val intent = GridViewActivity.newIntent(this, "تابع المشاهدة")
            startActivity(intent)
        }
    }

    private fun observeWatchHistory() {
        lifecycleScope.launch {
            // USE THE NEW, FILTERED QUERY
            db.watchHistoryDao().getContinueWatchingHistory().collectLatest { historyList ->
                if (historyList.isEmpty()) {
                    continueWatchingSection.visibility = View.GONE
                    continueWatchingRecyclerView.visibility = View.GONE
                } else {
                    continueWatchingSection.visibility = View.VISIBLE
                    continueWatchingRecyclerView.visibility = View.VISIBLE
                    continueWatchingAdapter.submitList(historyList)
                }
            }
        }
    }

    // In MainActivity.kt

    private fun openContinueWatchingItem(item: WatchHistory) {
        // There's no need for a loading indicator here, as we are just
        // starting a new activity, which will show its own loading state.

        // Create an SAnime object from the history item so we can pass it
        // to the details activity.
        val anime = SAnime(
            url = item.animeUrl,
            title = item.animeTitle,
            thumbnail_url = item.animeThumbnailUrl
        )

        // Create an intent to launch AnimeDetailsActivity.
        // We pass the specific episode's URL as the "resume" signal.
        val intent = AnimeDetailsActivity.newIntentWithResume(
            context = this,
            anime = anime,
            resumeEpisodeUrl = item.episodeUrl
        )

        startActivity(intent)
    }

    private fun setupSearchFunctionality() {
        // Handle search from the main search bar
//        searchEditText.setOnEditorActionListener { _, _, _ ->
//            val query = searchEditText.text.toString().trim()
//            if (query.isNotBlank()) {
//                performQuickSearch(query)
//            }
//            true
//        }

        // FAB click to open full search activity
        fabSearch.setOnClickListener {
            openSearchActivity()
        }
    }

    private fun performQuickSearch(query: String) {
        showLoadingg(true)

        lifecycleScope.launch {
            try {
                val searchResults = faselHDSource.fetchSearchAnime(
                    page = 1,
                    query = query,
                    filters = com.faselhd.app.models.AnimeFilterList(emptyList())
                )

                // Replace latest updates with search results
                latestAdapter.submitList(searchResults.manga.take(20))
                showLoadingg(false)

                if (searchResults.manga.isEmpty()) {
                    showError("لا توجد نتائج للبحث عن: $query")
                }

            } catch (e: Exception) {
                showLoadingg(false)
                showError("خطأ في البحث: ${e.message}")
            }
        }
    }

    private fun openSearchActivity() {
        val intent = SearchActivity.newIntent(this)
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
    }

    private fun loadData() {
        if (!swipeRefreshLayout.isRefreshing) {
            showLoadingg(true)
        }

        lifecycleScope.launch {
            try {
                // Fetch all data in parallel
                val sliderJob = async { faselHDSource.fetchMainSlider() }
                val popularJob = async { faselHDSource.fetchPopularSeries(1) }
                val latestEpisodesJob = async { faselHDSource.fetchHomePageLatestEpisodes() }
                val latestUpdatesJob = async { faselHDSource.fetchLatestUpdates(1) }

                // Await results
                val sliderItems = sliderJob.await()
                val popularPage = popularJob.await()
                val latestEpisodes = latestEpisodesJob.await()
                val latestPage = latestUpdatesJob.await()

                // --- SAFETY CHECK ---
                // Before touching any views, check if the coroutine is still active.
                // If the user closed the app while loading, we stop here.
                if (!isActive) return@launch

                // --- SUCCESS: Update the UI ---
                // Show the main content and hide the error layout
                nestedScrollView.visibility = View.VISIBLE
                noInternetLayout.visibility = View.GONE

                // Populate Slider
                val sliderAdapter = SliderAdapter(sliderItems) { anime -> openAnimeDetails(anime) }
                mainSliderViewPager.adapter = sliderAdapter
                setupAutoSwipe(sliderAdapter) // Extracted auto-swipe logic for clarity

                // Populate other lists
                popularAdapter.submitList(popularPage.manga.take(10))
                latestEpisodesAdapter.submitList(latestEpisodes)
                latestAdapter.submitList(latestPage.manga.take(20))

            } catch (e: Exception) {
                // --- SAFETY CHECK ---
                // Also check for activity state before handling the error UI.
                if (!isActive) return@launch

                // --- FAILURE: Update the UI ---
                // Hide the main content and show the error layout
                nestedScrollView.visibility = View.GONE
                noInternetLayout.visibility = View.VISIBLE
                showError("Error loading data. Please check your connection.")

            } finally {
                // --- FINAL CLEANUP ---
                // This block will ALWAYS run, after success or failure.
                // It's the perfect place for cleanup code.
                if (isActive) { // Final safety check for the cleanup itself
                    showLoadingg(false)
                    swipeRefreshLayout.isRefreshing = false
                }
            }
        }
    }

    private fun setupAutoSwipe(sliderAdapter: SliderAdapter) {
        // Define what the auto-swipe action does
        sliderRunnable = Runnable {
            val currentItem = mainSliderViewPager.currentItem
            val itemCount = sliderAdapter.itemCount
            if (itemCount > 0) {
                mainSliderViewPager.setCurrentItem((currentItem + 1) % itemCount, true)
            }
        }

        // Register a callback to automatically restart the swipe timer
        mainSliderViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                sliderHandler.removeCallbacks(sliderRunnable)
                sliderHandler.postDelayed(sliderRunnable, 3000)
            }
        })

        // Start the first swipe if not already running
        if(::sliderRunnable.isInitialized) {
            sliderHandler.postDelayed(sliderRunnable, 3000)
        }
    }

    private fun openAnimeDetails(anime: SAnime) {
        // Navigate to anime details activity
        val intent = AnimeDetailsActivity.newIntent(this, anime)
        startActivity(intent)
        overridePendingTransition(R.anim.scale_in, R.anim.fade_out)
    }

    private fun showLoading(show: Boolean) {
//        progressIndicator.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        if (isFinishing || isDestroyed) {
            return
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}






