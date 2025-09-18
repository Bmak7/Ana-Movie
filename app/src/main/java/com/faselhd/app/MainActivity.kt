package com.faselhd.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.faselhd.app.widgets.GridSpacingItemDecoration
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat.performHapticFeedback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.faselhd.app.adapters.AnimeAdapter
import com.faselhd.app.adapters.ContinueWatchingAdapter
import com.faselhd.app.adapters.SliderAdapter
import com.faselhd.app.db.AppDatabase
import com.faselhd.app.network.SourceManager
import com.example.myapplication.R
import com.facebook.shimmer.ShimmerFrameLayout
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.utils.PlayerDataHolder
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.lang.Runnable
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class MainActivity : AppCompatActivity() {
    // View Declarations
    private lateinit var mainSliderViewPager: ViewPager2
    private lateinit var bottomNavigationView: BottomNavigationView
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar
    private lateinit var featuredAnimeTitle: TextView
    private lateinit var featuredAnimeGenre: TextView
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnMyList: MaterialButton
    private lateinit var continueWatchingSection: LinearLayout
    private lateinit var continueWatchingRecyclerView: RecyclerView
    private lateinit var topHitsRecyclerView: RecyclerView
    private lateinit var newEpisodesRecyclerView: RecyclerView
    private lateinit var latestRecyclerView: RecyclerView
    private lateinit var seeAllContinueWatching: TextView
    private lateinit var seeAllTopHits: TextView
    private lateinit var seeAllNewEpisodes: TextView
    private lateinit var seeAllLatest: TextView
    private lateinit var shimmerTopHits: ShimmerFrameLayout
    private lateinit var shimmerNewEpisodes: ShimmerFrameLayout
    private lateinit var shimmerLatestUpdates: ShimmerFrameLayout
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var tvSidebarNavigation: LinearLayout
    private lateinit var mainContentContainer: LinearLayout
    private lateinit var navItemHome: LinearLayout
    private lateinit var navItemMyList: LinearLayout
    private lateinit var navItemSearch: LinearLayout
    private lateinit var navItemDownloads: LinearLayout
    private lateinit var navItemSettings: LinearLayout

    // Adapters
    private lateinit var sliderAdapter: SliderAdapter
    private lateinit var continueWatchingAdapter: ContinueWatchingAdapter
    private lateinit var topHitsAdapter: AnimeAdapter
    private lateinit var newEpisodesAdapter: AnimeAdapter
    private lateinit var latestAdapter: AnimeAdapter

    // Utilities & State
    private val sourceManager by lazy { SourceManager(applicationContext) }
    private val db by lazy { AppDatabase.getDatabase(this) }
    private val sliderHandler = Handler(Looper.getMainLooper())
    private lateinit var sliderRunnable: Runnable
    private var currentFeaturedAnime: SAnime? = null
    private var currentFeaturedSource: AnimeSource? = null
    private var isRunningOnTV = false

    // Enhanced D-pad Navigation State
    private var currentFocusedSection = FocusSection.SIDEBAR_NAVIGATION
    private var currentButtonIndex = 0
    private var currentSidebarIndex = 0
    private var searchActionView: View? = null

    // RecyclerView position tracking for better navigation
    private var currentContinueWatchingPosition = 0
    private var currentTopHitsPosition = 0
    private var currentNewEpisodesPosition = 0
    private var currentLatestGridPosition = 0
    private var currentSliderPosition = 0

    // Focusable View Lists
    private lateinit var featuredButtons: List<View>
    private lateinit var seeAllButtons: List<TextView>
    private lateinit var recyclerViews: List<RecyclerView>
    private lateinit var sidebarItems: List<LinearLayout>

    // Focus Sections Enum for TV Navigation
    private enum class FocusSection {
        TOOLBAR, SIDEBAR_NAVIGATION, FEATURED_SLIDER, FEATURED_BUTTONS,
        CONTINUE_WATCHING_SECTION, CONTINUE_WATCHING_ITEMS,
        TOP_HITS_SECTION, TOP_HITS_ITEMS,
        NEW_EPISODES_SECTION, NEW_EPISODES_ITEMS,
        LATEST_SECTION, LATEST_ITEMS
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupToolbar()
        setupRecyclerViews()
        setupBottomNavigation()
        setupSeeAllButtons()
        setupFeaturedButtons()
        setupSwipeRefresh()
        setupDpadNavigation()
        loadData()
        observeWatchHistory()
    }

    private fun initViews() {
        // Find all views by their ID
        toolbar = findViewById(R.id.toolbar)
        mainSliderViewPager = findViewById(R.id.main_slider_view_pager)
        bottomNavigationView = findViewById(R.id.bottom_navigation)
        tvSidebarNavigation = findViewById(R.id.tv_sidebar_navigation)
        mainContentContainer = findViewById(R.id.main_content_container)
        navItemHome = findViewById(R.id.nav_item_home)
        navItemMyList = findViewById(R.id.nav_item_my_list)
        navItemSearch = findViewById(R.id.nav_item_search)
        navItemDownloads = findViewById(R.id.nav_item_downloads)
        navItemSettings = findViewById(R.id.nav_item_settings)
        featuredAnimeTitle = findViewById(R.id.featured_anime_title)
        featuredAnimeGenre = findViewById(R.id.featured_anime_genre)
        btnPlay = findViewById(R.id.btn_play)
        btnMyList = findViewById(R.id.btn_my_list)
        continueWatchingSection = findViewById(R.id.continue_watching_section)
        continueWatchingRecyclerView = findViewById(R.id.continue_watching_recycler_view)
        topHitsRecyclerView = findViewById(R.id.top_hits_recycler_view)
        newEpisodesRecyclerView = findViewById(R.id.new_episodes_recycler_view)
        latestRecyclerView = findViewById(R.id.latest_recycler_view)
        seeAllContinueWatching = findViewById(R.id.see_all_continue_watching)
        seeAllTopHits = findViewById(R.id.see_all_top_hits)
        seeAllNewEpisodes = findViewById(R.id.see_all_new_episodes)
        seeAllLatest = findViewById(R.id.see_all_latest)
        shimmerTopHits = findViewById(R.id.shimmer_top_hits)
        shimmerNewEpisodes = findViewById(R.id.shimmer_new_episodes)
        shimmerLatestUpdates = findViewById(R.id.shimmer_latest_updates)
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)

        // Initialize view lists here, after findViewById calls
        featuredButtons = listOf(btnPlay, btnMyList)
        seeAllButtons = listOf(seeAllContinueWatching, seeAllTopHits, seeAllNewEpisodes, seeAllLatest)
        recyclerViews = listOf(continueWatchingRecyclerView, topHitsRecyclerView, newEpisodesRecyclerView, latestRecyclerView)
        sidebarItems = listOf(navItemHome, navItemMyList, navItemSearch, navItemDownloads, navItemSettings)

        setupTVLayout()
        setupViewFocusability()
    }



    private fun setupDpadNavigation() {
        if (!isRunningOnTV) return

        setupFocusListeners()
        setupRecyclerViewFocusHandling()
        setInitialFocus()
    }

    private fun setupFocusListeners() {
        // Featured buttons focus handling
        featuredButtons.forEachIndexed { index, button ->
            button.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    currentFocusedSection = FocusSection.FEATURED_BUTTONS
                    currentButtonIndex = index
                }
                animateButtonFocus(view, hasFocus)
            }
        }

        // Sidebar items focus handling
        sidebarItems.forEachIndexed { index, item ->
            item.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    currentSidebarIndex = index
                    currentFocusedSection = FocusSection.SIDEBAR_NAVIGATION
                }
                animateSidebarItemFocus(view, hasFocus, index)
            }
        }

        // See all buttons focus handling
        seeAllButtons.forEachIndexed { index, button ->
            button.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    when (index) {
                        0 -> currentFocusedSection = FocusSection.CONTINUE_WATCHING_SECTION
                        1 -> currentFocusedSection = FocusSection.TOP_HITS_SECTION
                        2 -> currentFocusedSection = FocusSection.NEW_EPISODES_SECTION
                        3 -> currentFocusedSection = FocusSection.LATEST_SECTION
                    }
                }
                animateTextButtonFocus(view, hasFocus)
            }
        }

        // Toolbar search focus handling
        searchActionView?.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                currentFocusedSection = FocusSection.TOOLBAR
                view.setBackgroundResource(R.color.green_play_button)
            } else {
                view.setBackgroundResource(android.R.color.transparent)
            }
        }

        // Slider focus handling
        mainSliderViewPager.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                currentFocusedSection = FocusSection.FEATURED_SLIDER
                view.elevation = 8f
            } else {
                view.elevation = 4f
            }
        }
    }

    private fun setupViewFocusability() {
        if (!isRunningOnTV) return

        // Set all relevant views to be focusable for TV
        btnPlay.isFocusable = true
        btnMyList.isFocusable = true
        mainSliderViewPager.isFocusable = true
        recyclerViews.forEach { it.isFocusable = true; it.descendantFocusability = RecyclerView.FOCUS_AFTER_DESCENDANTS }
        seeAllButtons.forEach { it.isFocusable = true }
        sidebarItems.forEach { it.isFocusable = true }
    }

    private fun animateButtonFocus(view: View, hasFocus: Boolean) {
        val scale = if (hasFocus) 1.1f else 1.0f
        val elevation = if (hasFocus) 8f else 2f

        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(200)
            .start()

        view.elevation = elevation
    }

    private fun animateTextButtonFocus(view: View, hasFocus: Boolean) {
        val backgroundRes = if (hasFocus) R.drawable.rounded_background_focused else android.R.color.transparent
        view.setBackgroundResource(backgroundRes)

        val scale = if (hasFocus) 1.05f else 1.0f
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(150)
            .start()
    }

    private fun animateSidebarItemFocus(view: View, hasFocus: Boolean, index: Int) {
        val backgroundRes = if (hasFocus) R.color.green_play_button else android.R.color.transparent
        view.setBackgroundResource(backgroundRes)

        val scale = if (hasFocus) 1.02f else 1.0f
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(100)
            .start()
    }

    private fun handleRecyclerViewFocus(recyclerView: RecyclerView, hasFocus: Boolean) {
        if (!hasFocus) {
            removeHighlightFromRecyclerView(recyclerView)
            return
        }

        when (recyclerView) {
            continueWatchingRecyclerView -> {
                currentFocusedSection = FocusSection.CONTINUE_WATCHING_ITEMS
                highlightRecyclerViewItem(recyclerView, currentContinueWatchingPosition)
            }
            topHitsRecyclerView -> {
                currentFocusedSection = FocusSection.TOP_HITS_ITEMS
                highlightRecyclerViewItem(recyclerView, currentTopHitsPosition)
            }
            newEpisodesRecyclerView -> {
                currentFocusedSection = FocusSection.NEW_EPISODES_ITEMS
                highlightRecyclerViewItem(recyclerView, currentNewEpisodesPosition)
            }
            latestRecyclerView -> {
                currentFocusedSection = FocusSection.LATEST_ITEMS
                highlightRecyclerViewItem(recyclerView, currentLatestGridPosition)
            }
        }
    }

    @SuppressLint("WrongConstant")
    private fun highlightRecyclerViewItem(recyclerView: RecyclerView, position: Int) {
        recyclerView.post {
            val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
            viewHolder?.itemView?.let { itemView ->
                // Enhanced visual feedback for TV navigation
                itemView.animate()
                    .scaleX(1.05f)
                    .scaleY(1.05f)
                    .setDuration(200)
                    .start()

                itemView.elevation = 8f

                // Show focus indicator
                val focusIndicator = itemView.findViewById<View>(R.id.focus_indicator)
                focusIndicator?.visibility = View.VISIBLE
                focusIndicator?.animate()?.alpha(1.0f)?.setDuration(150)?.start()
            }
            ensureItemVisible(recyclerView, position)
        }
    }

    @SuppressLint("WrongConstant")
    private fun removeHighlightFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.post {
            for (i in 0 until recyclerView.childCount) {
                val childView = recyclerView.getChildAt(i)
                childView.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(200)
                    .start()

                childView.elevation = 4f

                // Hide focus indicator
                val focusIndicator = childView.findViewById<View>(R.id.focus_indicator)
                focusIndicator?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction {
                    focusIndicator.visibility = View.GONE

                }?.start()
            }
        }
    }

    // Enhanced grid layout for better TV/Phone experience
    private fun setupRecyclerViews() {
        // Continue watching with improved layout
        continueWatchingAdapter = ContinueWatchingAdapter { watchHistory ->
            openContinueWatchingItem(watchHistory)
        }
        continueWatchingRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = continueWatchingAdapter
            isNestedScrollingEnabled = false

            // Better spacing for TV
            if (isRunningOnTV) {
                val spacing = resources.getDimensionPixelSize(R.dimen.tv_item_spacing)
                addItemDecoration(HorizontalSpaceItemDecoration(spacing))
            }
        }

        // Top hits with improved layout
        topHitsAdapter = AnimeAdapter(AnimeAdapter.ViewType.TOP_HIT) { anime ->
            openAnimeDetails(anime)
        }
        topHitsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = topHitsAdapter
            isNestedScrollingEnabled = false

            if (isRunningOnTV) {
                val spacing = resources.getDimensionPixelSize(R.dimen.tv_item_spacing)
                addItemDecoration(HorizontalSpaceItemDecoration(spacing))
            }
        }

        // New episodes with improved layout
        newEpisodesAdapter = AnimeAdapter(AnimeAdapter.ViewType.NEW_RELEASE) { anime ->
            openAnimeDetails(anime)
        }
        newEpisodesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = newEpisodesAdapter
            isNestedScrollingEnabled = false

            if (isRunningOnTV) {
                val spacing = resources.getDimensionPixelSize(R.dimen.tv_item_spacing)
                addItemDecoration(HorizontalSpaceItemDecoration(spacing))
            }
        }

        // Latest updates with responsive grid
        latestAdapter = AnimeAdapter(AnimeAdapter.ViewType.GRID) { anime ->
            openAnimeDetails(anime)
        }
        latestRecyclerView.apply {
            val spanCount = 3
            layoutManager = GridLayoutManager(this@MainActivity, spanCount)
            adapter = latestAdapter
            isNestedScrollingEnabled = false
            if (itemDecorationCount == 0) {
                addItemDecoration(GridSpacingItemDecoration(spanCount, resources.getDimensionPixelSize(R.dimen.grid_spacing), true))
            }
        }
    }

    // Custom ItemDecoration for horizontal spacing
    class HorizontalSpaceItemDecoration(private val horizontalSpaceHeight: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            outRect.right = horizontalSpaceHeight

            // Add left margin only for first item
            if (parent.getChildAdapterPosition(view) == 0) {
                outRect.left = horizontalSpaceHeight
            }
        }
    }

    // Enhanced TV layout setup
    @SuppressLint("WrongConstant")
    private fun setupTVLayout() {
        isRunningOnTV = packageManager.hasSystemFeature("android.software.leanback") ||
                packageManager.hasSystemFeature("android.software.leanback_only")

        if (isRunningOnTV) {
            // Show TV sidebar and adjust layout
            tvSidebarNavigation.visibility = View.VISIBLE
            bottomNavigationView.visibility = View.GONE

            val sidebarWidth = resources.getDimensionPixelSize(R.dimen.tv_sidebar_width)
            val params = mainContentContainer.layoutParams as CoordinatorLayout.LayoutParams
            params.marginStart = sidebarWidth
            mainContentContainer.layoutParams = params

            // Disable swipe refresh on TV
            swipeRefreshLayout.isEnabled = false

            // Setup TV-specific styling
            setupTVSidebarNavigation()
            applyTVStyling()
        } else {
            // Phone/tablet layout
            tvSidebarNavigation.visibility = View.GONE
            bottomNavigationView.visibility = View.VISIBLE

            val params = mainContentContainer.layoutParams as CoordinatorLayout.LayoutParams
            params.marginStart = 0
            mainContentContainer.layoutParams = params

            swipeRefreshLayout.isEnabled = true
        }
    }

    private fun applyTVStyling() {
        // Increase text sizes for TV
        featuredAnimeTitle.textSize = 28f
        featuredAnimeGenre.textSize = 16f

        // Adjust button sizes for TV
        val buttonParams = btnPlay.layoutParams
        buttonParams.height = resources.getDimensionPixelSize(R.dimen.tv_button_height)
        btnPlay.layoutParams = buttonParams
        btnMyList.layoutParams = buttonParams

        // Increase section title sizes
        findViewById<TextView>(R.id.see_all_top_hits)?.textSize = 18f
        findViewById<TextView>(R.id.see_all_new_episodes)?.textSize = 18f
        findViewById<TextView>(R.id.see_all_latest)?.textSize = 18f
    }

    // Enhanced keyboard navigation with better visual feedback
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isRunningOnTV) return super.onKeyDown(keyCode, event)

        // Add haptic feedback for better user experience
        window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> handleDpadUp()
            KeyEvent.KEYCODE_DPAD_DOWN -> handleDpadDown()
            KeyEvent.KEYCODE_DPAD_LEFT -> handleDpadLeft()
            KeyEvent.KEYCODE_DPAD_RIGHT -> handleDpadRight()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> handleDpadCenter()
            KeyEvent.KEYCODE_BACK -> handleBackKey()
            KeyEvent.KEYCODE_MENU -> showTVHelp()
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun showTVHelp(): Boolean {
        val helpDialog = AlertDialog.Builder(this)
            .setTitle("TV Navigation Help")
            .setMessage("""
            🎮 D-pad Navigation:
            ↑↓ - Navigate sections vertically
            ←→ - Navigate items horizontally  
            CENTER/ENTER - Select item
            BACK - Go back to previous section
            MENU - Show this help
            
            📱 Remote Tips:
            • Use sidebar for main navigation
            • Focus follows your movements
            • Items highlight when selected
        """.trimIndent())
            .setPositiveButton("Got it!") { dialog, _ -> dialog.dismiss() }
            .create()

        helpDialog.show()
        return true
    }

    private fun setupRecyclerViewFocusHandling() {
        recyclerViews.forEach { recyclerView ->
            recyclerView.setOnFocusChangeListener { _, hasFocus ->
                handleRecyclerViewFocus(recyclerView, hasFocus)
            }
        }
    }

    private fun setInitialFocus() {
        if (isRunningOnTV) {
            Handler(Looper.getMainLooper()).postDelayed({
                sidebarItems.firstOrNull()?.requestFocus()
            }, 100)
        }
    }



    private fun handleBackKey(): Boolean {
        // Custom back button behavior for TV
        when (currentFocusedSection) {
            FocusSection.CONTINUE_WATCHING_ITEMS,
            FocusSection.TOP_HITS_ITEMS,
            FocusSection.NEW_EPISODES_ITEMS,
            FocusSection.LATEST_ITEMS -> {
                // Go back to section header
                focusOnSection(getSectionForItems(currentFocusedSection))
                return true // Consume the event
            }
            FocusSection.FEATURED_SLIDER,
            FocusSection.FEATURED_BUTTONS,
            FocusSection.CONTINUE_WATCHING_SECTION,
            FocusSection.TOP_HITS_SECTION,
            FocusSection.NEW_EPISODES_SECTION,
            FocusSection.LATEST_SECTION -> {
                focusOnSection(FocusSection.SIDEBAR_NAVIGATION)
                return true // Consume the event
            }
            else -> {
                super.onBackPressed() // performs the default behavior
                return true           // consume the event
            }//Labeled return is deprecated - result of pop back stack is now available through FragmentManager.OnBackStackChangedListener
        }
    }


    private fun getSectionForItems(itemsSection: FocusSection): FocusSection {
        return when (itemsSection) {
            FocusSection.CONTINUE_WATCHING_ITEMS -> FocusSection.CONTINUE_WATCHING_SECTION
            FocusSection.TOP_HITS_ITEMS -> FocusSection.TOP_HITS_SECTION
            FocusSection.NEW_EPISODES_ITEMS -> FocusSection.NEW_EPISODES_SECTION
            FocusSection.LATEST_ITEMS -> FocusSection.LATEST_SECTION
            else -> itemsSection
        }
    }

    private fun handleDpadUp(): Boolean {
        when (currentFocusedSection) {
            FocusSection.SIDEBAR_NAVIGATION -> if (currentSidebarIndex > 0) sidebarItems[--currentSidebarIndex].requestFocus()
            FocusSection.TOOLBAR -> focusOnSection(FocusSection.SIDEBAR_NAVIGATION)
            FocusSection.FEATURED_SLIDER -> focusOnSection(FocusSection.TOOLBAR)
            FocusSection.FEATURED_BUTTONS -> focusOnSection(FocusSection.FEATURED_SLIDER)
            FocusSection.CONTINUE_WATCHING_SECTION -> focusOnSection(FocusSection.FEATURED_BUTTONS)
            FocusSection.CONTINUE_WATCHING_ITEMS -> focusOnSection(FocusSection.CONTINUE_WATCHING_SECTION)
            FocusSection.TOP_HITS_SECTION -> {
                if (continueWatchingSection.visibility == View.VISIBLE) {
                    focusOnSection(FocusSection.CONTINUE_WATCHING_SECTION)
                } else {
                    focusOnSection(FocusSection.FEATURED_BUTTONS)
                }
            }
            FocusSection.TOP_HITS_ITEMS -> focusOnSection(FocusSection.TOP_HITS_SECTION)
            FocusSection.NEW_EPISODES_SECTION -> focusOnSection(FocusSection.TOP_HITS_SECTION)
            FocusSection.NEW_EPISODES_ITEMS -> focusOnSection(FocusSection.NEW_EPISODES_SECTION)
            FocusSection.LATEST_SECTION -> focusOnSection(FocusSection.NEW_EPISODES_SECTION)
            FocusSection.LATEST_ITEMS -> {
                val layoutManager = latestRecyclerView.layoutManager as? GridLayoutManager
                val spanCount = layoutManager?.spanCount ?: 3
                val newPosition = currentLatestGridPosition - spanCount
                if (newPosition >= 0) {
                    currentLatestGridPosition = newPosition
                    scrollToGridPosition(latestRecyclerView, currentLatestGridPosition)
                } else {
                    focusOnSection(FocusSection.LATEST_SECTION)
                }
            }
        }
        return true
    }

    private fun handleDpadDown(): Boolean {
        when (currentFocusedSection) {
            FocusSection.SIDEBAR_NAVIGATION -> if (currentSidebarIndex < sidebarItems.size - 1) sidebarItems[++currentSidebarIndex].requestFocus()
            FocusSection.TOOLBAR -> focusOnSection(FocusSection.FEATURED_SLIDER)
            FocusSection.FEATURED_SLIDER -> focusOnSection(FocusSection.FEATURED_BUTTONS)
            FocusSection.FEATURED_BUTTONS -> {
                if (continueWatchingSection.visibility == View.VISIBLE) {
                    focusOnSection(FocusSection.CONTINUE_WATCHING_SECTION)
                } else {
                    focusOnSection(FocusSection.TOP_HITS_SECTION)
                }
            }
            FocusSection.CONTINUE_WATCHING_SECTION -> focusOnSection(FocusSection.CONTINUE_WATCHING_ITEMS)
            FocusSection.CONTINUE_WATCHING_ITEMS -> focusOnSection(FocusSection.TOP_HITS_SECTION)
            FocusSection.TOP_HITS_SECTION -> focusOnSection(FocusSection.TOP_HITS_ITEMS)
            FocusSection.TOP_HITS_ITEMS -> focusOnSection(FocusSection.NEW_EPISODES_SECTION)
            FocusSection.NEW_EPISODES_SECTION -> focusOnSection(FocusSection.NEW_EPISODES_ITEMS)
            FocusSection.NEW_EPISODES_ITEMS -> focusOnSection(FocusSection.LATEST_SECTION)
            FocusSection.LATEST_SECTION -> focusOnSection(FocusSection.LATEST_ITEMS)
            FocusSection.LATEST_ITEMS -> {
                val layoutManager = latestRecyclerView.layoutManager as? GridLayoutManager
                val adapter = latestRecyclerView.adapter
                val spanCount = layoutManager?.spanCount ?: 3
                val maxPosition = (adapter?.itemCount ?: 0) - 1
                val newPosition = currentLatestGridPosition + spanCount
                if (newPosition <= maxPosition) {
                    currentLatestGridPosition = newPosition
                    scrollToGridPosition(latestRecyclerView, currentLatestGridPosition)
                }
            }
        }
        return true
    }

    private fun handleDpadLeft(): Boolean {
        when (currentFocusedSection) {
            FocusSection.FEATURED_BUTTONS -> {
                if (currentButtonIndex > 0) {
                    featuredButtons[--currentButtonIndex].requestFocus()
                } else {
                    focusOnSection(FocusSection.SIDEBAR_NAVIGATION)
                }
            }
            FocusSection.CONTINUE_WATCHING_ITEMS -> {
                if (currentContinueWatchingPosition > 0) {
                    navigateHorizontalRecyclerView(continueWatchingRecyclerView, -1) { currentContinueWatchingPosition = it }
                } else {
                    focusOnSection(FocusSection.SIDEBAR_NAVIGATION)
                }
            }
            FocusSection.TOP_HITS_ITEMS -> {
                if (currentTopHitsPosition > 0) {
                    navigateHorizontalRecyclerView(topHitsRecyclerView, -1) { currentTopHitsPosition = it }
                } else {
                    focusOnSection(FocusSection.SIDEBAR_NAVIGATION)
                }
            }
            FocusSection.NEW_EPISODES_ITEMS -> {
                if (currentNewEpisodesPosition > 0) {
                    navigateHorizontalRecyclerView(newEpisodesRecyclerView, -1) { currentNewEpisodesPosition = it }
                } else {
                    focusOnSection(FocusSection.SIDEBAR_NAVIGATION)
                }
            }
            FocusSection.LATEST_ITEMS -> {
                val layoutManager = latestRecyclerView.layoutManager as? GridLayoutManager
                val spanCount = layoutManager?.spanCount ?: 3
                val currentCol = currentLatestGridPosition % spanCount
                if (currentCol > 0) {
                    currentLatestGridPosition--
                    scrollToGridPosition(latestRecyclerView, currentLatestGridPosition)
                } else {
                    focusOnSection(FocusSection.SIDEBAR_NAVIGATION)
                }
            }
            FocusSection.SIDEBAR_NAVIGATION -> { /* Stay in sidebar */ }
            else -> focusOnSection(FocusSection.SIDEBAR_NAVIGATION)
        }
        return true
    }


    private fun handleDpadRight(): Boolean {
        when (currentFocusedSection) {
            FocusSection.SIDEBAR_NAVIGATION -> focusOnSection(FocusSection.FEATURED_SLIDER)
            FocusSection.FEATURED_SLIDER -> {
                val adapter = mainSliderViewPager.adapter
                if (adapter != null && adapter.itemCount > 0) {
                    val nextItem = (mainSliderViewPager.currentItem + 1) % adapter.itemCount
                    mainSliderViewPager.setCurrentItem(nextItem, true)
                }
            }
            FocusSection.FEATURED_BUTTONS -> {
                if (currentButtonIndex < featuredButtons.size - 1) {
                    featuredButtons[++currentButtonIndex].requestFocus()
                }
            }
            FocusSection.CONTINUE_WATCHING_ITEMS -> navigateHorizontalRecyclerView(continueWatchingRecyclerView, 1) { currentContinueWatchingPosition = it }
            FocusSection.TOP_HITS_ITEMS -> navigateHorizontalRecyclerView(topHitsRecyclerView, 1) { currentTopHitsPosition = it }
            FocusSection.NEW_EPISODES_ITEMS -> navigateHorizontalRecyclerView(newEpisodesRecyclerView, 1) { currentNewEpisodesPosition = it }
            FocusSection.LATEST_ITEMS -> {
                val adapter = latestRecyclerView.adapter
                val layoutManager = latestRecyclerView.layoutManager as? GridLayoutManager
                if (adapter != null && layoutManager != null) {
                    val spanCount = layoutManager.spanCount
                    val currentCol = currentLatestGridPosition % spanCount
                    if (currentCol < spanCount - 1) {
                        val newPosition = currentLatestGridPosition + 1
                        if (newPosition < adapter.itemCount) {
                            currentLatestGridPosition = newPosition
                            scrollToGridPosition(latestRecyclerView, currentLatestGridPosition)
                        }
                    }
                }
            }
            else -> {
                // For section headers, move focus to the items
                when (currentFocusedSection) {
                    FocusSection.CONTINUE_WATCHING_SECTION -> focusOnSection(FocusSection.CONTINUE_WATCHING_ITEMS)
                    FocusSection.TOP_HITS_SECTION -> focusOnSection(FocusSection.TOP_HITS_ITEMS)
                    FocusSection.NEW_EPISODES_SECTION -> focusOnSection(FocusSection.NEW_EPISODES_ITEMS)
                    FocusSection.LATEST_SECTION -> focusOnSection(FocusSection.LATEST_ITEMS)
                    else -> { /* No specific action */ }
                }
            }
        }
        return true
    }

    private fun handleDpadCenter(): Boolean {
        when (currentFocusedSection) {
            FocusSection.TOOLBAR -> searchActionView?.performClick()
            FocusSection.SIDEBAR_NAVIGATION -> sidebarItems[currentSidebarIndex].performClick()
            FocusSection.FEATURED_SLIDER -> focusOnSection(FocusSection.FEATURED_BUTTONS)
            FocusSection.FEATURED_BUTTONS -> featuredButtons[currentButtonIndex].performClick()
            FocusSection.CONTINUE_WATCHING_SECTION -> seeAllContinueWatching.performClick()
            FocusSection.TOP_HITS_SECTION -> seeAllTopHits.performClick()
            FocusSection.NEW_EPISODES_SECTION -> seeAllNewEpisodes.performClick()
            FocusSection.LATEST_SECTION -> seeAllLatest.performClick()
            FocusSection.CONTINUE_WATCHING_ITEMS -> clickRecyclerViewItemAtPosition(continueWatchingRecyclerView, currentContinueWatchingPosition)
            FocusSection.TOP_HITS_ITEMS -> clickRecyclerViewItemAtPosition(topHitsRecyclerView, currentTopHitsPosition)
            FocusSection.NEW_EPISODES_ITEMS -> clickRecyclerViewItemAtPosition(newEpisodesRecyclerView, currentNewEpisodesPosition)
            FocusSection.LATEST_ITEMS -> clickRecyclerViewItemAtPosition(latestRecyclerView, currentLatestGridPosition)
        }
        return true
    }

    private fun navigateHorizontalRecyclerView(recyclerView: RecyclerView, direction: Int, updatePosition: (Int) -> Unit) {
        val adapter = recyclerView.adapter ?: return
        val currentPos = when (recyclerView) {
            continueWatchingRecyclerView -> currentContinueWatchingPosition
            topHitsRecyclerView -> currentTopHitsPosition
            newEpisodesRecyclerView -> currentNewEpisodesPosition
            else -> return
        }

        val newPosition = (currentPos + direction).coerceIn(0, adapter.itemCount - 1)
        if (newPosition != currentPos) {
            updatePosition(newPosition)
            recyclerView.smoothScrollToPosition(newPosition)
            removeHighlightFromRecyclerView(recyclerView)
            highlightRecyclerViewItem(recyclerView, newPosition)
        }
    }

    private fun scrollToGridPosition(recyclerView: RecyclerView, position: Int) {
        recyclerView.smoothScrollToPosition(position)
        removeHighlightFromRecyclerView(recyclerView)
        highlightRecyclerViewItem(recyclerView, position)
    }

    private fun clickRecyclerViewItemAtPosition(recyclerView: RecyclerView, position: Int) {
        recyclerView.post {
            val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
            viewHolder?.itemView?.performClick()
        }
    }

    private fun focusOnSection(section: FocusSection) {
        // Remove highlights from previous section's items
        when (currentFocusedSection) {
            FocusSection.CONTINUE_WATCHING_ITEMS -> removeHighlightFromRecyclerView(continueWatchingRecyclerView)
            FocusSection.TOP_HITS_ITEMS -> removeHighlightFromRecyclerView(topHitsRecyclerView)
            FocusSection.NEW_EPISODES_ITEMS -> removeHighlightFromRecyclerView(newEpisodesRecyclerView)
            FocusSection.LATEST_ITEMS -> removeHighlightFromRecyclerView(latestRecyclerView)
            else -> { /* No cleanup needed for non-item sections */ }
        }

        currentFocusedSection = section
        val viewToFocus: View? = when (section) {
            FocusSection.TOOLBAR -> searchActionView
            FocusSection.SIDEBAR_NAVIGATION -> sidebarItems.getOrNull(currentSidebarIndex)
            FocusSection.FEATURED_SLIDER -> mainSliderViewPager
            FocusSection.FEATURED_BUTTONS -> featuredButtons.getOrNull(currentButtonIndex)
            FocusSection.CONTINUE_WATCHING_SECTION -> seeAllContinueWatching.takeIf { it.visibility == View.VISIBLE }
            FocusSection.CONTINUE_WATCHING_ITEMS -> continueWatchingRecyclerView.takeIf { it.visibility == View.VISIBLE }
            FocusSection.TOP_HITS_SECTION -> seeAllTopHits
            FocusSection.TOP_HITS_ITEMS -> topHitsRecyclerView
            FocusSection.NEW_EPISODES_SECTION -> seeAllNewEpisodes
            FocusSection.NEW_EPISODES_ITEMS -> newEpisodesRecyclerView
            FocusSection.LATEST_SECTION -> seeAllLatest
            FocusSection.LATEST_ITEMS -> latestRecyclerView
        }

        viewToFocus?.requestFocus()

        // If the target view is a RecyclerView, highlight its current item
        if (viewToFocus is RecyclerView) {
            handleRecyclerViewFocus(viewToFocus, true)
        }

        // Handle case where a section is not visible (e.g., Continue Watching)
        if (viewToFocus == null && section == FocusSection.CONTINUE_WATCHING_SECTION) {
            focusOnSection(FocusSection.TOP_HITS_SECTION) // Skip to the next section
        }
    }

    private fun setupTVSidebarNavigation() {
        sidebarItems.forEach { it.isFocusable = true }

        // Click listeners
        navItemHome.setOnClickListener { updateSidebarSelection(0) }
        navItemMyList.setOnClickListener {
            startActivity(Intent(this, MyListActivity::class.java))
            updateSidebarSelection(1)
        }
        navItemSearch.setOnClickListener {
            val intent = ParentSearchActivity.newIntent(this)
            startActivity(intent)
            updateSidebarSelection(2)
        }
        navItemDownloads.setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
            updateSidebarSelection(3)
        }
        navItemSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            updateSidebarSelection(4)
        }
    }

    private fun updateSidebarSelection(selectedIndex: Int) {
        sidebarItems.forEachIndexed { index, item ->
            item.setBackgroundResource(
                if (index == selectedIndex) R.color.green_play_button
                else android.R.color.transparent
            )
        }
        currentSidebarIndex = selectedIndex
    }

    // --- Additional helper methods for better TV navigation ---

    private fun ensureItemVisible(recyclerView: RecyclerView, position: Int) {
        val layoutManager = recyclerView.layoutManager
        when (layoutManager) {
            is LinearLayoutManager -> {
                val firstVisible = layoutManager.findFirstCompletelyVisibleItemPosition()
                val lastVisible = layoutManager.findLastCompletelyVisibleItemPosition()
                if (position < firstVisible || position > lastVisible) {
                    recyclerView.smoothScrollToPosition(position)
                }
            }
            is GridLayoutManager -> {
                val firstVisible = layoutManager.findFirstCompletelyVisibleItemPosition()
                val lastVisible = layoutManager.findLastCompletelyVisibleItemPosition()
                if (position < firstVisible || position > lastVisible) {
                    recyclerView.smoothScrollToPosition(position)
                }
            }
        }
    }

    private fun resetNavigationPositions() {
        currentContinueWatchingPosition = 0
        currentTopHitsPosition = 0
        currentNewEpisodesPosition = 0
        currentLatestGridPosition = 0
        currentSliderPosition = 0
        currentButtonIndex = 0
        currentSidebarIndex = 0
    }

    // Override onResume to ensure proper focus restoration
    override fun onResume() {
        super.onResume()
        if (::sliderRunnable.isInitialized) {
            sliderHandler.postDelayed(sliderRunnable, 3000)
        }
        if (isRunningOnTV) {
            // Restore focus to the current section
            Handler(Looper.getMainLooper()).postDelayed({
                focusOnSection(currentFocusedSection)
            }, 200)
        }
    }

    // --- Rest of the original code (unchanged methods) ---

    private fun setupFeaturedButtons() {
        btnPlay.setOnClickListener {
            currentFeaturedAnime?.let { anime ->
                handlePlayButtonClick(anime)
            } ?: run {
                Toast.makeText(this, "No anime selected", Toast.LENGTH_SHORT).show()
            }
        }

        btnMyList.setOnClickListener {
            currentFeaturedAnime?.let { anime ->
                addToMyList(anime)
            } ?: run {
                Toast.makeText(this, "No anime selected", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun handlePlayButtonClick(anime: SAnime) {
        lifecycleScope.launch {
            if (!isNetworkAvailable()) {
                Toast.makeText(this@MainActivity, "No internet connection.", Toast.LENGTH_LONG).show()
                return@launch
            }

            showMainLoading(true)

            try {
                // Perform database and network operations on a background thread
                val result = withContext(Dispatchers.IO) {
                    val recentHistory = db.watchHistoryDao().getRecentWatchHistoryForAnime(anime.url!!)
                    if (recentHistory != null && recentHistory.lastWatchedPosition > 0) {
                        // Scenario 1: Resume - This part is fine.
                        Triple(recentHistory.episodeUrl, null, null)
                    } else {
                        // Scenario 2: Start from the first episode
                        val episodes = sourceManager.fetchEpisodeList(anime.url!!, currentFeaturedSource)
                        if (episodes.isNotEmpty()) {
                            val firstEpisode = episodes.first()
                            val videos = sourceManager.fetchVideoList(firstEpisode.url!!, currentFeaturedSource)
                            // Pass all necessary data back to the main thread
                            Triple(null, videos, episodes)
                        } else {
                            // No episodes found
                            Triple(null, emptyList<Video>(), emptyList<SEpisode>())
                        }
                    }
                }

                // Update UI on the main thread based on the result
                val (resumeUrl, videos, episodes) = result

                if (resumeUrl != null) {
                    // Resume logic remains unchanged
                    Toast.makeText(this@MainActivity, "Resuming...", Toast.LENGTH_SHORT).show()
                    val intent = AnimeDetailsActivity.newIntentWithResume(
                        context = this@MainActivity,
                        anime = anime,
                        resumeEpisodeUrl = resumeUrl,
                        source = currentFeaturedSource
                    )
                    startActivity(intent)
                    overridePendingTransition(R.anim.scale_in, R.anim.fade_out)
                } else if (!videos.isNullOrEmpty() && !episodes.isNullOrEmpty()) {
                    // This block is now fixed and more efficient
                    Toast.makeText(this@MainActivity, "Starting Episode 1...", Toast.LENGTH_SHORT).show()
                    val firstEpisode = episodes.first()

                    // =======================================================
                    // ++ SOLUTION IMPLEMENTED HERE ++
                    // =======================================================

                    // 1. Populate the singleton holder
                    PlayerDataHolder.videos = videos
                    PlayerDataHolder.anime = anime
                    PlayerDataHolder.episodeList = episodes

                    // 2. Create the lightweight intent
                    val intent = VideoPlayerActivity.newIntent(
                        context = this@MainActivity,
                        currentEpisodeUrl = firstEpisode.url!!,
                        startPosition = 0L,
                        source = currentFeaturedSource
                    )

                    // 3. Start the activity
                    startActivity(intent)

                    // =======================================================
                    // -- END OF FIX --
                    // =======================================================

                } else {
                    Toast.makeText(this@MainActivity, "No episodes or video sources found.", Toast.LENGTH_SHORT).show()
                    openAnimeDetailsAsFallback(anime)
                }
            } catch (e: Exception) {
                handlePlaybackError(e, anime)
            } finally {
                showMainLoading(false)
            }
        }
    }

    private fun loadData() {
        showLoading(true)
        lifecycleScope.launch {
            try {
                if (!isNetworkAvailable()) {
                    showError("No internet connection.")
                    showLoading(false)
                    showOfflineState()
                    return@launch
                }

                // Launch all network requests concurrently on the IO dispatcher
                val sliderJob = async(Dispatchers.IO) { sourceManager.fetchMainSlider() }
                val popularJob = async(Dispatchers.IO) { sourceManager.fetchPopularSeries(1) }
                val latestEpisodesJob = async(Dispatchers.IO) { sourceManager.fetchHomePageLatestEpisodes() }
                val latestUpdatesJob = async(Dispatchers.IO) { sourceManager.fetchLatestUpdates(1) }

                // Await for all jobs to complete
                val sliderItems = sliderJob.await()
                val popularSeries = popularJob.await()
                val latestEpisodes = latestEpisodesJob.await()
                val latestUpdates = latestUpdatesJob.await()


                if (!isActive) return@launch

                // --- Update the UI on the Main thread ---
                if (sliderItems.isNotEmpty()) {
                    sliderAdapter = SliderAdapter(sliderItems) { anime -> openAnimeDetails(anime) }
                    mainSliderViewPager.adapter = sliderAdapter
                    setupAutoSwipe(sliderAdapter)
                    updateFeaturedAnime(sliderItems[0], null)
                } else {
                    handleEmptySlider()
                }

                topHitsAdapter.submitList(popularSeries.manga.take(10))
                newEpisodesAdapter.submitList(latestEpisodes.take(10))
                latestAdapter.submitList(latestUpdates.manga.take(20))

                showLoading(false)

                if (sliderItems.isEmpty() && popularSeries.manga.isEmpty() && latestEpisodes.isEmpty() && latestUpdates.manga.isEmpty()) {
                    showError("Unable to load content. Try again.")
                } else if (sliderItems.isEmpty() || popularSeries.manga.isEmpty() || latestEpisodes.isEmpty() || latestUpdates.manga.isEmpty()) {
                    showWarning("Some content could not be loaded.")
                }

                if (isRunningOnTV) {
                    resetNavigationPositions()
                }

            } catch (e: Exception) {
                if (isActive) {
                    val message = when (e) {
                        is UnknownHostException, is ConnectException -> "No internet connection."
                        is SocketTimeoutException -> "Connection timed out."
                        else -> "Error loading data: ${e.localizedMessage ?: "Unknown error"}"
                    }
                    showError(message)
                    showLoading(false)
                    showOfflineState()
                }
            }
        }
    }

    private fun addToMyList(anime: SAnime) {
        lifecycleScope.launch {
            try {
                // Perform database operation on a background thread
                val isNowFavorite = withContext(Dispatchers.IO) {
                    val existingFavorite = db.favoriteDao().getFavoriteByUrl(anime.url!!)
                    if (existingFavorite != null) {
                        db.favoriteDao().delete(existingFavorite.animeUrl)
                        false // Was a favorite, now it's not
                    } else {
                        val favorite = Favorite(
                            animeUrl = anime.url!!,
                            title = anime.title,
                            thumbnailUrl = anime.thumbnail_url ?: "",
                            source = SourceManager.getSelectedSource(applicationContext).name,
                            timestamp = System.currentTimeMillis()
                        )
                        db.favoriteDao().insert(favorite)
                        true // Was not a favorite, now it is
                    }
                }

                // Update UI on the Main thread
                val message = if (isNowFavorite) "Added to My List" else "Removed from My List"
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                updateMyListButtonState(isNowFavorite)

            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error updating My List", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateFeaturedAnime(anime: SAnime, source: AnimeSource?) {
        currentFeaturedAnime = anime
        currentFeaturedSource = source
        featuredAnimeTitle.text = anime.title
        featuredAnimeGenre.text = anime.description ?: "Action, Adventure"
        btnPlay.isEnabled = true
        btnMyList.isEnabled = true
        lifecycleScope.launch {
            // Check favorite status on a background thread
            val isFavorite = withContext(Dispatchers.IO) {
                db.favoriteDao().getFavoriteByUrl(anime.url!!) != null
            }
            // Update the button UI on the main thread
            updateMyListButtonState(isFavorite)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun handlePlaybackError(e: Exception, anime: SAnime) {
        val errorMessage = when (e) {
            is UnknownHostException -> "No internet connection"
            is SocketTimeoutException -> "Connection timeout."
            is ConnectException -> "Unable to connect to server"
            else -> "Error loading episode: ${e.localizedMessage ?: "Unknown error"}"
        }
        Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_SHORT).show()
        openAnimeDetailsAsFallback(anime)
    }

    private fun openAnimeDetailsAsFallback(anime: SAnime) {
        currentFeaturedSource = SourceManager.getSelectedSource(applicationContext)
        try {
            val intent = AnimeDetailsActivity.newIntent(this@MainActivity, anime, currentFeaturedSource)
            startActivity(intent)
            overridePendingTransition(R.anim.scale_in, R.anim.fade_out)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open anime details", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleEmptySlider() {
        featuredAnimeTitle.text = "Welcome to Anime App"
        featuredAnimeGenre.text = "Discover amazing anime"
        btnPlay.isEnabled = false
        btnMyList.isEnabled = false
    }

    private fun showOfflineState() {
        featuredAnimeTitle.text = "No Connection"
        featuredAnimeGenre.text = "Please check your internet"
        btnPlay.isEnabled = false
        btnMyList.isEnabled = false
    }

    private fun showWarning(message: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showMainLoading(show: Boolean) {
        btnPlay.isEnabled = !show
        btnPlay.text = if (show) "Loading..." else "Play"
    }

//    private fun addToMyList(anime: SAnime) {
//        lifecycleScope.launch {
//            try {
//                val existingFavorite = db.favoriteDao().getFavoriteByUrl(anime.url!!)
//                if (existingFavorite != null) {
//                    db.favoriteDao().delete(existingFavorite.animeUrl)
//                    Toast.makeText(this@MainActivity, "Removed from My List", Toast.LENGTH_SHORT).show()
//                    updateMyListButtonState(false)
//                } else {
//                    val favorite = Favorite(
//                        animeUrl = anime.url!!,
//                        title = anime.title,
//                        thumbnailUrl = anime.thumbnail_url ?: "",
//                        source = SourceManager.getSelectedSource(applicationContext).name,
//                        timestamp = System.currentTimeMillis()
//                    )
//                    db.favoriteDao().insert(favorite)
//                    Toast.makeText(this@MainActivity, "Added to My List", Toast.LENGTH_SHORT).show()
//                    updateMyListButtonState(true)
//                }
//            } catch (e: Exception) {
//                Toast.makeText(this@MainActivity, "Error updating My List", Toast.LENGTH_SHORT).show()
//            }
//        }
//    }

    private fun updateMyListButtonState(isInMyList: Boolean) {
        if (isInMyList) {
            btnMyList.text = "My List"
            btnMyList.setIconResource(R.drawable.done_all_24px)
        } else {
            btnMyList.text = "My List"
            btnMyList.setIconResource(R.drawable.add_24px)
        }
    }

    private fun setupSeeAllButtons() {
        seeAllTopHits.setOnClickListener {
            startActivity(SeeAllActivity.newIntent(this, "TOP_HITS", "Top Hits Anime"))
        }
        seeAllNewEpisodes.setOnClickListener {
            startActivity(SeeAllActivity.newIntent(this, "NEW_EPISODES", "New Episode Releases"))
        }
        seeAllLatest.setOnClickListener {
            startActivity(SeeAllActivity.newIntent(this, "LATEST_UPDATES", "Latest Updates"))
        }
//        seeAllContinueWatching.setOnClickListener {
//            startActivity(Intent(this, ContinueWatchingActivity::class.java))
//        }
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_home_menu, menu)
        val searchItem = menu?.findItem(R.id.action_search)
        if (isRunningOnTV) {
            searchItem?.setActionView(R.layout.action_search_focusable)
            searchActionView = searchItem?.actionView
            searchActionView?.setOnClickListener { onOptionsItemSelected(searchItem!!) }
            setupFocusListeners()
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_search -> {
                startActivity(SearchActivity.newIntent(this))
                true
            }
            R.id.action_notifications -> {
                Toast.makeText(this, "Notifications clicked!", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }



    private fun setupBottomNavigation() {
        bottomNavigationView.setOnNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_my_list -> {
                    startActivity(Intent(this, MyListActivity::class.java)); true
                }
                R.id.nav_search -> {
                    startActivity(ParentSearchActivity.newIntent(this)); true
                }
                R.id.nav_download -> {
                    startActivity(Intent(this, DownloadsActivity::class.java)); true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java)); true
                }
                else -> false
            }
        }
    }

    @SuppressLint("WrongConstant")
    private fun observeWatchHistory() {
        lifecycleScope.launch {
            db.watchHistoryDao().getContinueWatchingHistory().collectLatest { historyList ->
                val visibility = if (historyList.isEmpty()) View.GONE else View.VISIBLE
                continueWatchingSection.visibility = visibility
                continueWatchingRecyclerView.visibility = visibility
                continueWatchingAdapter.submitList(historyList)
            }
        }
    }

//    private fun updateFeaturedAnime(anime: SAnime, source: AnimeSource?) {
//        currentFeaturedAnime = anime
//        currentFeaturedSource = source
//        featuredAnimeTitle.text = anime.title
//        featuredAnimeGenre.text = anime.description ?: "Action, Adventure"
//        btnPlay.isEnabled = true
//        btnMyList.isEnabled = true
//        lifecycleScope.launch {
//            val existingFavorite = db.favoriteDao().getFavoriteByUrl(anime.url!!)
//            updateMyListButtonState(existingFavorite != null)
//        }
//    }

    private fun openContinueWatchingItem(item: WatchHistory) {
        val anime = SAnime(url = item.animeUrl, title = item.animeTitle, thumbnail_url = item.animeThumbnailUrl)

        val source = try { AnimeSource.valueOf(item.source.replace(" ", "_").uppercase()) } catch (e: Exception) { null }
        println("sssssource : ${AnimeSource.valueOf(item.source.replace(" ", "_").uppercase())}")
        println("contin je watchanime: ${anime.toString()} item : ${item.source} sourcess : $source")
        val intent = AnimeDetailsActivity.newIntentWithResume(context = this, anime = anime, resumeEpisodeUrl = item.episodeUrl, source = source)
        startActivity(intent)
    }

    private fun setupAutoSwipe(sliderAdapter: SliderAdapter) {
        sliderRunnable = Runnable {
            val currentItem = mainSliderViewPager.currentItem
            val itemCount = sliderAdapter.itemCount
            if (itemCount > 0) {
                val nextItem = (currentItem + 1) % itemCount
                mainSliderViewPager.setCurrentItem(nextItem, true)
            }
        }
        mainSliderViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                sliderHandler.removeCallbacks(sliderRunnable)
                sliderHandler.postDelayed(sliderRunnable, 3000)
                val sliderItems = sliderAdapter.getItems()
                if (sliderItems.isNotEmpty() && position < sliderItems.size) {
                    updateFeaturedAnime(sliderItems[position], null)
                }
                currentSliderPosition = position
            }
        })
        sliderHandler.postDelayed(sliderRunnable, 3000)
    }

    private fun openAnimeDetails(anime: SAnime) {
        try {
            val source = SourceManager.getSelectedSource(applicationContext)
            val intent = AnimeDetailsActivity.newIntent(this, anime, source)
            startActivity(intent)
            overridePendingTransition(R.anim.scale_in, R.anim.fade_out)
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening anime details", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("WrongConstant")
    private fun showLoading(isLoading: Boolean) {
        swipeRefreshLayout.isRefreshing = isLoading
        val shimmerVisibility = if (isLoading) View.VISIBLE else View.GONE
        val recyclerVisibility = if (isLoading) View.GONE else View.VISIBLE

        shimmerTopHits.visibility = shimmerVisibility
        shimmerNewEpisodes.visibility = shimmerVisibility
        shimmerLatestUpdates.visibility = shimmerVisibility
        if (isLoading) {
            shimmerTopHits.startShimmer()
            shimmerNewEpisodes.startShimmer()
            shimmerLatestUpdates.startShimmer()
        } else {
            shimmerTopHits.stopShimmer()
            shimmerNewEpisodes.stopShimmer()
            shimmerLatestUpdates.stopShimmer()
        }

        topHitsRecyclerView.visibility = recyclerVisibility
        newEpisodesRecyclerView.visibility = recyclerVisibility
        latestRecyclerView.visibility = recyclerVisibility
    }

    private fun showError(message: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onPause() {
        super.onPause()
        if (::sliderRunnable.isInitialized) sliderHandler.removeCallbacks(sliderRunnable)
    }

    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener { loadData() }
        swipeRefreshLayout.setColorSchemeResources(R.color.green_play_button, android.R.color.holo_blue_bright, android.R.color.holo_green_light, android.R.color.holo_orange_light)
    }

    override fun onStart() {
        super.onStart()
        if (isRunningOnTV) {
            swipeRefreshLayout.isEnabled = false
        }
    }
}