package com.animetv

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import com.animetv.model.Anime
import com.animetv.scraper.HhpandaScraper
import com.animetv.ui.LoadingView
import com.animetv.ui.NavRail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var rowsFragment: RowsSupportFragment
    private lateinit var rail: NavRail
    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter(FocusHighlight.ZOOM_FACTOR_SMALL, false).apply {
        // qfilm rows are never dimmed; only the focused card changes.
        shadowEnabled = false
        selectEffectEnabled = false
        // Leanback fades headers of unselected rows; qfilm section titles are always full white.
        headerPresenter = object : RowHeaderPresenter() {
            override fun onSelectLevelChanged(holder: ViewHolder) {
                holder.view.alpha = 1f
            }
        }
    })
    private val posterPresenter = PosterCardPresenter()
    private val updatedAdapter = ArrayObjectAdapter(ClassPresenterSelector().apply {
        addClassPresenter(Anime::class.java, WideCardPresenter())
        addClassPresenter(RowMessage::class.java, MessageCardPresenter())
    })
    private var continueRow: ListRow? = null
    private var shownHistory: List<WatchEntry> = emptyList()
    private var updatedJob: Job? = null
    private lateinit var loader: LoadingView
    private val endStartup = Runnable { finishStartup() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Cold start: cover the half-built home until the "Phim mới cập nhật" row arrives (or a time cap).
        loader = LoadingView.attachTo(this)
        if (savedInstanceState == null) {
            loader.show(getString(R.string.home_loading), takeFocus = true)
            handler.postDelayed(endStartup, STARTUP_LOADER_MAX_MS)
        }
        setupRail()
        rowsFragment = supportFragmentManager.findFragmentById(R.id.rows_fragment) as RowsSupportFragment
        setupRows()
        // Let the rows fragment create its grid before attaching the adapter.
        handler.post {
            if (isDestroyed) return@post
            rowsFragment.adapter = rowsAdapter
            rowsFragment.setSelectedPosition(0, false)
            if (!loader.isShowing) rowsFragment.view?.requestFocus()
        }
        loadUpdated()
    }

    override fun onResume() {
        super.onResume()
        refreshContinueWatching()
    }

    private fun setupRail() {
        // This screen is "Khám phá"; choosing it again returns to the rows.
        rail = NavRail(this, R.id.nav_home) { rowsFragment.view?.requestFocus() }
    }

    private fun setupRows() {
        rowsAdapter.add(ListRow(HeaderItem(ROW_UPDATED, getString(R.string.row_updated)), updatedAdapter))

        // Curated discovery shelf; do not imply live popularity or release order.
        val discoverAdapter = ArrayObjectAdapter(posterPresenter)
        CURATED.forEach { discoverAdapter.add(it) }
        rowsAdapter.add(ListRow(HeaderItem(ROW_DISCOVER, getString(R.string.row_discover)), discoverAdapter))

        val genresAdapter = ArrayObjectAdapter(GenreCardPresenter())
        CategoryActivity.GENRES.forEach { genresAdapter.add(it) }
        rowsAdapter.add(ListRow(HeaderItem(ROW_GENRES, getString(R.string.row_genres)), genresAdapter))
    }

    /** qfilm's "Phim mới cập nhật" hero row, from the provider's recently-updated page. */
    private fun loadUpdated() {
        updatedJob?.cancel()
        updatedAdapter.setItems(listOf(RowMessage(getString(R.string.row_loading))), null)
        updatedJob = lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { HhpandaScraper.fetchUpdated() }
                check(items.isNotEmpty())
                // The row ends with a way into the full, paged list.
                updatedAdapter.setItems(items.take(UPDATED_LIMIT) + RowMessage(getString(R.string.row_see_all)) { openUpdatedListing() }, null)
                finishStartup()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updatedAdapter.setItems(listOf(RowMessage(getString(R.string.row_failed)) { loadUpdated() }), null)
                // The row itself explains the failure and offers Retry.
                finishStartup()
            }
        }
    }

    private fun openUpdatedListing() {
        startActivity(Intent(this, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_LISTING, SearchActivity.LISTING_UPDATED))
    }

    /** Hands the screen over from the startup loader to the rows. */
    private fun finishStartup() {
        handler.removeCallbacks(endStartup)
        if (!loader.isShowing) return
        loader.hide()
        if (rowsFragment.adapter != null) rowsFragment.view?.requestFocus()
        // Otherwise the pending adapter setup requests focus once the rows exist.
    }

    /** Shows series the viewer opened before, most recent first, at the top. */
    private fun refreshContinueWatching() {
        val history = WatchHistory.recent(this)
        if (history == shownHistory) return
        shownHistory = history
        val existing = continueRow
        when {
            history.isEmpty() && existing != null -> {
                rowsAdapter.remove(existing)
                continueRow = null
            }
            history.isNotEmpty() && existing != null -> {
                // Update in place so the selected row index does not shift.
                (existing.adapter as ArrayObjectAdapter).setItems(history, null)
            }
            history.isNotEmpty() -> {
                val adapter = ArrayObjectAdapter(posterPresenter).apply { addAll(0, history) }
                continueRow = ListRow(HeaderItem(ROW_CONTINUE, getString(R.string.row_continue)), adapter).also { rowsAdapter.add(0, it) }
                if (rowsFragment.adapter != null) rowsFragment.setSelectedPosition(0, false)
            }
        }
    }

    // Lint flags super.dispatchKeyEvent through ComponentActivity's restricted override; this is the public Activity API.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The startup loader holds focus and swallows D-pad keys; Back still leaves the app.
        if (loader.isShowing) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = currentFocus
            when (event.keyCode) {
                // Leaving the first card of a row opens the rail, as on qfilm's always-visible sidebar.
                KeyEvent.KEYCODE_DPAD_LEFT -> if (focused != null && !rail.contains(focused) && isAtRowStart(focused)) {
                    rail.focus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (focused != null && rail.contains(focused)) {
                    rowsFragment.view?.requestFocus()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isAtRowStart(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current is HorizontalGridView) return current.selectedPosition <= 0
            current = current.parent as? View
        }
        return true
    }

    @Deprecated("Legacy activity back dispatch")
    override fun onBackPressed() {
        // First Back returns to the menu; a second Back from the menu leaves the app.
        val focused = currentFocus
        if (!loader.isShowing && focused != null && !rail.contains(focused)) rail.focus() else super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        rail.release()
        super.onDestroy()
    }

    companion object {
        private const val ROW_UPDATED = 0L
        private const val ROW_CONTINUE = 1L
        private const val ROW_DISCOVER = 2L
        private const val ROW_GENRES = 3L
        private const val UPDATED_LIMIT = 16
        /** Never hold the home screen longer than this; the row keeps its own loading card after. */
        private const val STARTUP_LOADER_MAX_MS = 8_000L

        // Titles match the provider's own Vietnamese names shown on the details screen.
        private val CURATED = listOf(
            Anime("Tiên Nghịch", "tien-nghich", "https://hhpanda.st/tien-nghich", "https://hhpanda.st/wp-content/uploads/2024/10/tien-nghich-5-300x450.webp", originalTitle = "Xian Ni"),
            Anime("Đấu Phá Thương Khung Phần 5", "dau-pha-thuong-khung-phan-5", "https://hhpanda.st/dau-pha-thuong-khung-phan-5", "https://hhpanda.st/wp-content/uploads/2024/10/Dau-Pha-Thuong-Khung-Phan-5-1-300x450.webp", originalTitle = "Fights Break Sphere"),
            Anime("Mục Thần Ký", "muc-than-ky", "https://hhpanda.st/muc-than-ky", "https://hhpanda.st/wp-content/uploads/2024/10/Muc-Than-Ky-12-hhkungfu-300x450.webp"),
            Anime("Thế Giới Hoàn Mỹ", "the-gioi-hoan-my", "https://hhpanda.st/the-gioi-hoan-my", "https://hhpanda.st/wp-content/uploads/2024/10/the-gioi-hoan-my-256-300x450.webp"),
            Anime("Phàm Nhân Tu Tiên", "pham-nhan-tu-tien", "https://hhpanda.st/pham-nhan-tu-tien", "https://hhpanda.st/wp-content/uploads/2024/10/Pham-Nhan-Tu-Tien-300x450.webp"),
            Anime("Già Thiên", "gia-thien", "https://hhpanda.st/gia-thien", "https://hhpanda.st/wp-content/uploads/2024/10/gia-thien-292-300x450.webp"),
            Anime("Thôn Phệ Tinh Không Phần 2", "thon-phe-tinh-khong-phan-2", "https://hhpanda.st/thon-phe-tinh-khong-phan-2", "https://hhpanda.st/wp-content/uploads/2024/10/thon-phe-tinh-khong-phan-2-295-300x450.webp"),
            Anime("Đấu La Đại Lục 2: Tuyệt Thế Đường Môn", "dau-la-dai-luc-2-tuyet-the-duong-mon", "https://hhpanda.st/dau-la-dai-luc-2-tuyet-the-duong-mon", "https://hhpanda.st/wp-content/uploads/2024/10/dau-la-dai-luc-2-tuyet-the-duong-mon-210.jpg"),
        )
    }
}
