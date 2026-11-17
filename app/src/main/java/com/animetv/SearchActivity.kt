package com.animetv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.Presenter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.animetv.model.Anime
import com.animetv.scraper.HhpandaScraper
import com.animetv.scraper.ListingPage
import com.animetv.ui.LoadingView
import com.animetv.ui.NavRail
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import kotlinx.coroutines.*
import java.text.Normalizer

/**
 * Title search with live suggestions; genre browsing with a "genre" extra; the full "Mới cập nhật" list with
 * [EXTRA_LISTING]. Every result list is paged like the provider's site (20 titles per page) with a numbered bar.
 */
class SearchActivity : FragmentActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var searchJob: Job? = null
    private var suggestJob: Job? = null
    private var requestId = 0
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var grid: RecyclerView
    private lateinit var suggestions: RecyclerView
    private lateinit var submit: Button
    private lateinit var loading: LoadingView
    private lateinit var cards: SearchCards
    private val suggestionAdapter = SuggestionAdapter()
    /** Provider suggestions per folded query, so backspacing does not refetch. */
    private val suggestionCache = HashMap<String, List<Anime>>()
    private var genre: String? = null
    private var listing: String? = null
    /** Genre or "Mới cập nhật": a fixed list with Reload instead of a search field. */
    private val browsing get() = genre != null || listing != null
    private lateinit var rail: NavRail
    /** The query the shown pages belong to (the field may have been edited since). */
    private var activeQuery = ""
    /** Screen pages: each provider page (20 titles) is shown as two screens of [PAGE_SIZE] that fit without scrolling. */
    private var currentPage = 1
    private var totalPages = 1
    /** Provider pages already fetched for [activeQuery] / the listing, so the second screen of a page is instant. */
    private val providerPages = object : LinkedHashMap<Int, ListingPage>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ListingPage>) = size > 4
    }
    private var providerTotal = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        input = findViewById(R.id.search_input)
        status = findViewById(R.id.search_status)
        grid = findViewById(R.id.search_grid)
        suggestions = findViewById(R.id.search_suggestions)
        submit = findViewById(R.id.search_submit)
        loading = findViewById(R.id.search_loading)
        grid.layoutManager = GridLayoutManager(this, COLUMNS)
        grid.itemAnimator = null
        genre = intent.getStringExtra("genre")
        listing = intent.getStringExtra(EXTRA_LISTING)?.takeIf { it == LISTING_UPDATED }
        rail = NavRail(this, when {
            listing != null -> R.id.nav_updated
            genre != null -> R.id.nav_genres
            else -> R.id.nav_search
        }) { focusContent() }
        cards = SearchCards(
            PosterCardPresenter(widthDp = null),
            topFocusId = {
                when {
                    browsing -> R.id.search_submit
                    suggestions.visibility == View.VISIBLE -> R.id.search_suggestions
                    else -> R.id.search_input
                }
            },
        )
        grid.adapter = cards
        suggestions.layoutManager = LinearLayoutManager(this)
        suggestions.adapter = suggestionAdapter
        suggestions.itemAnimator = null
        // Browsing: reload the page on screen. Searching: a new search from page 1.
        submit.setOnClickListener {
            if (browsing) {
                providerPages.clear()
                loadPage(currentPage)
            } else search()
        }
        input.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEARCH || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                search(); true
            } else false
        }
        val savedPage = savedInstanceState?.getInt("page", 1) ?: 1
        if (browsing) {
            findViewById<TextView>(R.id.search_title).text = if (listing != null) getString(R.string.updated_title)
                else intent.getStringExtra("genre_name") ?: getString(R.string.genre_fallback_title)
            input.visibility = View.GONE
            // The page count sits in the field's place next to Reload; no status line under the header.
            status.visibility = View.GONE
            status = findViewById<TextView>(R.id.search_page_info).apply { visibility = View.VISIBLE }
            // Listings: a square reload icon instead of the "Tải lại" text.
            submit.layoutParams = submit.layoutParams.apply { width = submit.layoutParams.height }
            submit.minWidth = 0
            submit.minimumWidth = 0
            submit.setPaddingRelative((submit.layoutParams.height - dp(ICON_DP)) / 2, 0, 0, 0)
            submit.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_player_reload, 0, 0, 0)
            setSubmitLabel(R.string.search_refresh)
            submit.nextFocusLeftId = submit.id
            grid.nextFocusUpId = submit.id
            submit.requestFocus()
            loadPage(savedPage)
        } else {
            input.setText(savedInstanceState?.getString("query") ?: "")
            input.requestFocus()
            activeQuery = savedInstanceState?.getString("activeQuery").orEmpty()
            if (activeQuery.isNotBlank()) loadPage(savedPage)
            // Registered after restoring the query so a restored search does not also open suggestions.
            input.doAfterTextChanged { scheduleSuggestions(it?.toString().orEmpty()) }
        }
    }

    /** Debounced live suggestions from the provider while the viewer types. */
    private fun scheduleSuggestions(raw: String) {
        val query = raw.trim()
        suggestJob?.cancel()
        if (query.length < HhpandaScraper.SUGGEST_MIN_CHARS) {
            showSuggestions(emptyList(), query)
            return
        }
        val key = fold(query)
        suggestionCache[key]?.let {
            showSuggestions(it, query)
            return
        }
        suggestJob = scope.launch {
            delay(SUGGEST_DEBOUNCE_MS)
            val found = try {
                withContext(Dispatchers.IO) { HhpandaScraper.suggest(query) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Suggestions are a shortcut; Search still reports connection problems.
                emptyList()
            }
            suggestionCache[key] = found
            if (input.text.toString().trim() == query) showSuggestions(found, query)
        }
    }

    private fun showSuggestions(items: List<Anime>, query: String) {
        val visible = items.isNotEmpty()
        // Never strand focus on a row that is about to disappear.
        if (!visible && suggestions.hasFocus()) input.requestFocus()
        suggestionAdapter.submitList(items.take(MAX_SUGGESTIONS).map { Suggestion(it, query) })
        // The field starts after the title on the same row: line the panel up with it.
        (suggestions.layoutParams as ViewGroup.MarginLayoutParams).marginStart = input.left
        suggestions.visibility = if (visible) View.VISIBLE else View.GONE
        val below = if (visible) R.id.search_suggestions else R.id.search_grid
        input.nextFocusDownId = below
        submit.nextFocusDownId = below
    }

    /** A new search for the field's text, from its first page. */
    private fun search() {
        val query = input.text.toString().trim()
        if (query.isEmpty()) {
            status.setText(R.string.search_empty_query)
            input.requestFocus()
            return
        }
        activeQuery = query
        providerPages.clear()
        loadPage(1)
    }

    /**
     * Shows screen [page] and focuses the card at [focusAt] (clamped). Screen p is half (p - 1) % 2 of provider page
     * (p + 1) / 2; a fetched provider page is kept, so flipping to its other half needs no network.
     */
    private fun loadPage(page: Int, focusAt: Int = 0) {
        val query = activeQuery
        val providerPage = (page + 1) / 2
        val cached = providerPages[providerPage]
        if (cached != null) {
            searchJob?.cancel()
            ++requestId
            loading.hide()
            showPage(cached, page, focusAt, moveFocus = !input.hasFocus())
            return
        }
        // The full result grid replaces the suggestions until the query changes again.
        suggestJob?.cancel()
        showSuggestions(emptyList(), query)
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        submit.requestFocus()
        val currentRequest = ++requestId
        searchJob?.cancel()
        cards.submit(emptyList())
        // The loader in the results area carries the progress message.
        status.text = ""
        loading.show(getString(R.string.search_loading))
        setSubmitLabel(if (browsing) R.string.search_refresh else R.string.search_action)
        currentPage = page
        val genreSlug = genre
        searchJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    when {
                        listing != null -> HhpandaScraper.fetchUpdated(providerPage)
                        genreSlug != null -> HhpandaScraper.fetchGenre(genreSlug, providerPage)
                        else -> HhpandaScraper.search(query, providerPage)
                    }
                }
                if (currentRequest != requestId) return@launch
                loading.hide()
                providerPages[providerPage] = result
                // Do not move focus out of a field the viewer is editing during a request.
                showPage(result, page, focusAt, moveFocus = submit.hasFocus())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (currentRequest != requestId) return@launch
                loading.hide()
                status.setText(R.string.search_failed)
                setSubmitLabel(R.string.search_retry)
            }
        }
    }

    /** Puts one screen of [result] (provider page) on the grid as screen [page] and updates the page count. */
    private fun showPage(result: ListingPage, page: Int, focusAt: Int, moveFocus: Boolean) {
        providerTotal = result.totalPages
        totalPages = screenCount()
        currentPage = page
        val results = screenItems(result.items, (page - 1) % 2)
        cards.submit(results)
        grid.scrollToPosition(0)
        status.text = when {
            results.isEmpty() && page > 1 -> getString(R.string.listing_empty_page, page)
            results.isEmpty() -> getString(R.string.search_empty)
            totalPages > 1 -> getString(R.string.listing_count, page, totalPages)
            else -> getString(R.string.search_count, results.size)
        }
        if (results.isNotEmpty() && moveFocus) {
            val target = focusAt.coerceIn(0, results.size - 1)
            grid.post { (grid.layoutManager?.findViewByPosition(target) ?: grid.getChildAt(0))?.requestFocus() }
        }
    }

    /** Two screens per provider page, except a last provider page short enough for one. */
    private fun screenCount(): Int {
        val last = providerPages[providerTotal]?.takeIf { it.totalPages == providerTotal }
        val lastScreens = if (last == null) 2 else if (last.items.size <= PAGE_SIZE) 1 else 2
        return ((providerTotal - 1) * 2 + lastScreens).coerceAtLeast(1)
    }

    /** Half [half] of a provider page: the first and second ten of twenty, or everything on one screen when it fits. */
    private fun screenItems(items: List<Anime>, half: Int): List<Anime> {
        if (items.size <= PAGE_SIZE) return if (half == 0) items else emptyList()
        val cut = (items.size + 1) / 2
        return if (half == 0) items.take(cut) else items.drop(cut)
    }

    /**
     * Page turning: CH+ / Page Down / Next and CH− / Page Up / Previous anywhere outside the field, and Right past the
     * last card of a row / Left past the first one, which lands on the same row of the next / previous screen.
     */
    // Lint flags super.dispatchKeyEvent through ComponentActivity's restricted override; this is the public Activity API.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && currentFocus?.let { rail.contains(it) } == true) {
            focusContent()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && totalPages > 1 && !input.hasFocus()) {
            val step = when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> 1
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> -1
                else -> 0
            }
            if (step != 0) {
                flip(step, 0, event)
                return true
            }
            val card = currentFocus?.takeIf { it.parent === grid }
            if (card != null) {
                val position = grid.getChildAdapterPosition(card)
                val rowStart = position / COLUMNS * COLUMNS
                val atRight = position % COLUMNS == COLUMNS - 1 || position == cards.itemCount - 1
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && atRight && currentPage < totalPages) {
                    flip(1, rowStart, event)
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && position % COLUMNS == 0 && currentPage > 1) {
                    flip(-1, rowStart + COLUMNS - 1, event)
                    return true
                }
            }
        }
        // Left from the first column with no screen before it opens the menu, as on home.
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            val card = currentFocus?.takeIf { it.parent === grid }
            if (card != null && grid.getChildAdapterPosition(card) % COLUMNS == 0) {
                rail.focus()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Search shows the label on the button; listings show only the icon and give the label to screen readers. */
    private fun setSubmitLabel(label: Int) {
        if (browsing) {
            submit.text = ""
            submit.contentDescription = getString(label)
        } else {
            submit.setText(label)
            submit.contentDescription = null
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** From the menu back into the page: the first card, or the field / Reload when there are none. */
    private fun focusContent() {
        val first = grid.layoutManager?.findViewByPosition(0)
        when {
            first != null -> first.requestFocus()
            browsing -> submit.requestFocus()
            else -> input.requestFocus()
        }
    }

    /** One screen forward or back; a held key turns only one page. */
    private fun flip(step: Int, focusAt: Int, event: KeyEvent) {
        val target = currentPage + step
        if (event.repeatCount == 0 && target in 1..totalPages && !loading.isShowing) loadPage(target, focusAt)
    }

    @Deprecated("Legacy activity back dispatch")
    override fun onBackPressed() {
        // Back from a suggestion returns to the field to keep typing; from the content it goes to the menu first, then leaves.
        val focused = currentFocus
        when {
            suggestions.hasFocus() -> input.requestFocus()
            focused != null && !rail.contains(focused) -> rail.focus()
            else -> super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", input.text.toString())
        outState.putString("activeQuery", activeQuery)
        outState.putInt("page", currentPage)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        scope.cancel()
        rail.release()
        super.onDestroy()
    }

    companion object {
        const val COLUMNS = 5
        /** Rows on one screen; cards shrink to fit them without scrolling. */
        const val ROWS = 2
        /** Titles per screen: half of the provider's 20-title page. */
        const val PAGE_SIZE = COLUMNS * ROWS
        private const val ICON_DP = 24
        const val EXTRA_LISTING = "listing"
        const val LISTING_UPDATED = "updated"
        private const val MAX_SUGGESTIONS = 6
        private const val SUGGEST_DEBOUNCE_MS = 300L
    }
}

/** Lowercase and strip Vietnamese diacritics one character at a time, so indexes still match the original. */
private fun fold(text: CharSequence): String = buildString(text.length) {
    for (c in text) append(
        when (c) {
            'đ', 'Đ' -> 'd'
            else -> Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar()
        }
    )
}

/** Bold the part of the title the viewer typed ("tien" matches "Tiên"). */
private fun highlight(title: String, query: String): CharSequence {
    val needle = fold(query)
    val start = if (needle.isEmpty()) -1 else fold(title).indexOf(needle)
    if (start < 0) return title
    return SpannableString(title).apply {
        setSpan(StyleSpan(Typeface.BOLD), start, start + needle.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

private data class Suggestion(val anime: Anime, val query: String)

private class SuggestionAdapter : ListAdapter<Suggestion, SuggestionAdapter.Holder>(object : DiffUtil.ItemCallback<Suggestion>() {
    override fun areItemsTheSame(old: Suggestion, new: Suggestion) = old.anime.slug == new.anime.slug
    override fun areContentsTheSame(old: Suggestion, new: Suggestion) = old == new
}) {
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val poster: ImageView = view.findViewById(R.id.suggestion_poster)
        val title: TextView = view.findViewById(R.id.suggestion_title)
        val original: TextView = view.findViewById(R.id.suggestion_original)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_suggestion, parent, false)
        // The list is a single column: Left/Right would otherwise jump to unrelated controls.
        view.setOnKeyListener { _, keyCode, event ->
            event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val (anime, query) = getItem(position)
        val view = holder.itemView
        val res = view.resources
        holder.title.text = highlight(anime.title, query)
        holder.original.text = anime.originalTitle
        holder.original.visibility = if (anime.originalTitle.isBlank()) View.GONE else View.VISIBLE
        view.contentDescription = if (anime.originalTitle.isBlank()) res.getString(R.string.suggestion_description, anime.title)
            else res.getString(R.string.suggestion_description_original, anime.title, anime.originalTitle)
        view.nextFocusUpId = if (position == 0) R.id.search_input else View.NO_ID
        Glide.with(view)
            .load(anime.posterUrl)
            .transform(CenterCrop(), RoundedCorners((4 * res.displayMetrics.density).toInt()))
            .placeholder(R.drawable.poster_placeholder)
            .error(R.drawable.poster_placeholder)
            .into(holder.poster)
        view.setOnClickListener { openDetails(view, anime) }
    }

    override fun onViewRecycled(holder: Holder) {
        Glide.with(holder.itemView).clear(holder.poster)
        holder.itemView.setOnClickListener(null)
    }
}

private class SearchCards(
    private val presenter: PosterCardPresenter,
    private val topFocusId: () -> Int,
) : RecyclerView.Adapter<SearchCards.Holder>() {
    private var items: List<Anime> = emptyList()
    fun submit(value: List<Anime>) { items = value; notifyDataSetChanged() }
    class Holder(val card: Presenter.ViewHolder) : RecyclerView.ViewHolder(card.view)
    override fun getItemCount() = items.size
    /** Card width and the gap between columns, fitted to the grid on first use. */
    private var cardWidth = 0
    private var gap = 0
    private var gridWidth = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val card = presenter.onCreateViewHolder(parent)
        // Title only: the meta line would cost the poster ~30dp of height on a two-row screen.
        card.view.findViewById<View>(R.id.card_meta).visibility = View.GONE
        if (cardWidth == 0 || gridWidth != parent.width) fit(parent, card.view)
        card.view.layoutParams = RecyclerView.LayoutParams(
            if (cardWidth > 0) cardWidth else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        return Holder(card)
    }

    /**
     * The widest card for which [SearchActivity.ROWS] rows fit the grid's height, with at least [MIN_GAP_DP] between
     * columns; the poster's height follows its width.
     */
    private fun fit(parent: ViewGroup, sample: View) {
        val density = parent.resources.displayMetrics.density
        val columns = SearchActivity.COLUMNS
        val width = parent.width
        val rowHeight = parent.height / SearchActivity.ROWS
        if (width <= 0 || rowHeight <= 0) return
        val rowGap = (ROW_GAP_DP * density).toInt()
        var card = (width - (columns - 1) * (MIN_GAP_DP * density).toInt()) / columns
        sample.measure(View.MeasureSpec.makeMeasureSpec(card, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val excess = sample.measuredHeight + rowGap - rowHeight
        if (excess > 0) card -= (excess / POSTER_RATIO).toInt() + 1
        cardWidth = card
        gap = (width - columns * card) / (columns - 1)
        gridWidth = width
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        presenter.onBindViewHolder(holder.card, items[position])
        val view = holder.itemView
        view.findViewById<View>(R.id.card_meta).visibility = View.GONE
        view.nextFocusUpId = if (position < SearchActivity.COLUMNS) topFocusId() else View.NO_ID
        // First column flush with the title, last flush with the button, equal gaps between: the grid gives every
        // column the same cell, so each card is shifted inside its cell by column × (cell − card − gap).
        val params = view.layoutParams as? RecyclerView.LayoutParams ?: return
        if (cardWidth <= 0) return
        val column = position % SearchActivity.COLUMNS
        val cell = gridWidth / SearchActivity.COLUMNS
        val start = column * (cardWidth + gap) - column * cell
        val rowGap = (ROW_GAP_DP * view.resources.displayMetrics.density).toInt()
        params.setMargins(start, 0, cell - cardWidth - start, rowGap)
        view.layoutParams = params
    }

    private companion object {
        const val POSTER_RATIO = 1.2f
        const val MIN_GAP_DP = 24
        const val ROW_GAP_DP = 16
    }

    override fun onViewRecycled(holder: Holder) = presenter.onUnbindViewHolder(holder.card)
}
