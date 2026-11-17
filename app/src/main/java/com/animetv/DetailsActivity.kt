package com.animetv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TableLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.animetv.model.Anime
import com.animetv.model.AnimeDetail
import com.animetv.model.Episode
import com.animetv.scraper.HhpandaScraper
import com.animetv.ui.LoadingView
import com.animetv.ui.SelectedFocusRecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.*

class DetailsActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var epAdapter: EpisodeAdapter
    private lateinit var resume: Button
    private lateinit var watch: Button
    private lateinit var retry: Button
    private lateinit var order: Button
    private lateinit var count: TextView
    private var anime: Anime? = null
    private var episodes = emptyList<Episode>()
    private var newestFirst = true
    private var loadJob: Job? = null
    private lateinit var loader: LoadingView
    private lateinit var grid: RecyclerView
    private lateinit var pages: SelectedFocusRecyclerView
    private val pageAdapter = EpisodePageAdapter { selectPage(it) }
    private var pageList = emptyList<EpisodePage>()
    /** First episode number of the range on screen; kept across order changes and recreation. */
    private var selectedPageStart: Int? = null
    private var primaryButtonId = R.id.watch_latest

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details_native)
        loader = LoadingView.attachTo(this)
        newestFirst = savedInstanceState?.getBoolean("newestFirst", true) ?: true
        selectedPageStart = savedInstanceState?.getInt("pageStart", -1)?.takeIf { it > 0 }
        resume = findViewById(R.id.resume_episode)
        watch = findViewById(R.id.watch_latest)
        retry = findViewById(R.id.detail_retry)
        order = findViewById(R.id.episode_order)
        count = findViewById(R.id.ep_count)
        findViewById<TextView>(R.id.detail_title).text = intent.getStringExtra("title") ?: getString(R.string.details_fallback_title)
        showPoster(intent.getStringExtra("poster"))
        grid = findViewById(R.id.episode_grid)
        grid.layoutManager = GridLayoutManager(this, COLUMNS)
        pages = findViewById(R.id.episode_pages)
        pages.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        pages.adapter = pageAdapter
        // Rebind selection in place; change animations would swap the focused tab view.
        pages.itemAnimator = null
        epAdapter = EpisodeAdapter { playEpisode(it) }
        grid.adapter = epAdapter
        retry.setOnClickListener { loadDetail() }
        order.setOnClickListener {
            newestFirst = !newestFirst
            // A new order starts from its first range: newest episodes, or episode 1.
            selectedPageStart = null
            showEpisodes(preferHistory = false)
            grid.scrollToPosition(0)
        }
        loadDetail()
    }

    override fun onResume() {
        super.onResume()
        // Returning from the player: reflect the episode that was just opened.
        if (episodes.isNotEmpty()) showHistory(moveFocus = false)
    }

    private fun loadDetail() {
        loadJob?.cancel()
        retry.visibility = View.GONE
        count.setText(R.string.episodes_loading)
        // Cover the half-filled page; the title the viewer picked keeps the wait oriented.
        loader.show(getString(R.string.details_loading), intent.getStringExtra("title"), takeFocus = true)
        loadJob = scope.launch {
            try {
                val slug = intent.getStringExtra("slug").orEmpty()
                require(slug.isNotBlank())
                val detail = withContext(Dispatchers.IO) { HhpandaScraper.fetchDetail(slug) }
                check(detail.anime.title.isNotBlank())
                updateUI(detail)
                loader.hide()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                resume.visibility = View.GONE
                watch.setText(R.string.action_unavailable)
                watch.isEnabled = false
                count.setText(R.string.episodes_failed)
                retry.visibility = View.VISIBLE
                retry.requestFocus()
                loader.hide()
            }
        }
    }

    private fun updateUI(detail: AnimeDetail) {
        // Prefer the card's artwork when the page omits it, so history keeps a poster.
        val show = detail.anime.copy(posterUrl = detail.anime.posterUrl.ifBlank { intent.getStringExtra("poster").orEmpty() })
        anime = show
        findViewById<TextView>(R.id.detail_title).text = show.title
        // qfilm puts the quality tag beside the title; the provider's equivalent is the [4K]-style suffix.
        val quality = Regex("\\[([^\\]]+)]").find(detail.latestEpisode)?.groupValues?.get(1).orEmpty()
        findViewById<TextView>(R.id.detail_quality).apply {
            text = quality
            visibility = if (quality.isBlank()) View.GONE else View.VISIBLE
        }
        // qfilm's dimmed meta line: other name • latest episode • status • rating • views.
        val meta = listOf(
            show.originalTitle,
            detail.latestEpisode.replace(Regex("\\s*\\[[^\\]]+]"), ""),
            detail.status,
            show.rating.takeIf { it.isNotBlank() }?.let { getString(R.string.rating_value, it) }.orEmpty(),
            detail.views,
        ).filter { it.isNotBlank() }.joinToString("  •  ")
        findViewById<TextView>(R.id.detail_meta).apply {
            text = meta
            visibility = if (meta.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.detail_descr).text = detail.description
        val table = findViewById<TableLayout>(R.id.detail_table)
        table.removeAllViews()
        if (detail.genres.isNotEmpty()) addInfoRow(table, R.string.info_genres, detail.genres.joinToString(", "))
        showPoster(show.posterUrl)
        episodes = detail.episodes.filter { it.url.startsWith("https://") || it.url.startsWith("http://") }.distinctBy { it.number }
        showEpisodes()
        val latest = episodes.maxByOrNull { it.number }
        if (latest != null) {
            watch.isEnabled = true
            watch.text = getString(R.string.action_latest, latest.number)
            watch.setOnClickListener { playEpisode(latest) }
            showHistory(moveFocus = true)
        } else {
            resume.visibility = View.GONE
            watch.setText(R.string.action_unavailable)
            watch.isEnabled = false
            retry.visibility = View.VISIBLE
            retry.requestFocus()
        }
    }

    private fun addInfoRow(table: TableLayout, label: Int, value: String) {
        val row = LayoutInflater.from(this).inflate(R.layout.detail_info_row, table, false)
        row.findViewById<TextView>(R.id.info_label).setText(label)
        row.findViewById<TextView>(R.id.info_value).text = value
        table.addView(row)
    }

    /** Offers the last opened episode first; returning viewers rarely want the newest one. */
    private fun showHistory(moveFocus: Boolean) {
        val slug = anime?.slug ?: return
        val latest = episodes.maxByOrNull { it.number } ?: return
        val last = WatchHistory.get(this, slug)?.episode
        val lastEpisode = episodes.firstOrNull { it.number == last }
        epAdapter.lastWatched = lastEpisode?.number
        val wasOnButton = resume.hasFocus() || watch.hasFocus()
        if (lastEpisode != null && lastEpisode.number != latest.number) {
            resume.text = getString(R.string.action_resume, lastEpisode.number)
            resume.setOnClickListener { playEpisode(lastEpisode) }
            resume.visibility = View.VISIBLE
            watch.nextFocusLeftId = resume.id
        } else {
            resume.visibility = View.GONE
            watch.nextFocusLeftId = watch.id
        }
        val primary = if (resume.visibility == View.VISIBLE) resume else watch
        primaryButtonId = primary.id
        updateFocusLinks()
        if (moveFocus || wasOnButton && !resume.hasFocus() && !watch.hasFocus()) primary.requestFocus()
    }

    /**
     * Long series (more than [PAGINATE_OVER] episodes) are split into ranges of [PAGE_SIZE] (1–10, 11–20, …)
     * so a viewer can jump to any part of the series instead of walking the whole grid.
     */
    private fun showEpisodes(preferHistory: Boolean = true) {
        val ordered = if (newestFirst) episodes.sortedByDescending { it.number } else episodes.sortedBy { it.number }
        order.setText(if (newestFirst) R.string.order_newest else R.string.order_oldest)
        order.visibility = if (episodes.isEmpty()) View.GONE else View.VISIBLE
        count.text = if (episodes.isEmpty()) getString(R.string.episodes_none) else getString(R.string.episodes_count, episodes.size)
        if (episodes.size > PAGINATE_OVER) {
            pageList = ordered.groupBy { (maxOf(it.number, 1) - 1) / PAGE_SIZE }.map { (bucket, eps) ->
                EpisodePage(bucket * PAGE_SIZE + 1, eps.maxOf { it.number }.coerceAtLeast(bucket * PAGE_SIZE + 1), eps)
            }
            val index = pageList.indexOfFirst { it.first == selectedPageStart }.takeIf { it >= 0 }
                ?: defaultPageIndex(preferHistory)
            pages.visibility = View.VISIBLE
            pageAdapter.submit(pageList, index)
            pages.selectedPosition = index
            pages.scrollToPosition(index)
            showPage(index)
        } else {
            pageList = emptyList()
            pages.visibility = View.GONE
            epAdapter.submitList(ordered)
        }
        updateFocusLinks()
    }

    /** The range holding the last opened episode, otherwise the first range in the current order. */
    private fun defaultPageIndex(preferHistory: Boolean): Int {
        val last = if (preferHistory) anime?.slug?.let { WatchHistory.get(this, it)?.episode } else null
        return pageList.indexOfFirst { page -> page.episodes.any { it.number == last } }.takeIf { it >= 0 } ?: 0
    }

    private fun selectPage(index: Int) {
        if (index !in pageList.indices || index == pageAdapter.selected) return
        pageAdapter.select(index)
        pages.selectedPosition = index
        showPage(index)
    }

    private fun showPage(index: Int) {
        selectedPageStart = pageList[index].first
        epAdapter.submitList(pageList[index].episodes)
    }

    /** Buttons → ranges → episodes when ranges are shown; buttons → episodes otherwise. */
    private fun updateFocusLinks() {
        val paged = pages.visibility == View.VISIBLE
        val below = if (paged) R.id.episode_pages else R.id.episode_grid
        resume.nextFocusDownId = below
        watch.nextFocusDownId = below
        order.nextFocusDownId = below
        pageAdapter.upId = primaryButtonId
        epAdapter.topFocusId = if (paged) R.id.episode_pages else primaryButtonId
    }

    private fun showPoster(url: String?) {
        val poster = findViewById<ImageView>(R.id.detail_poster)
        Glide.with(poster).load(url).centerCrop().placeholder(R.drawable.poster_placeholder).error(R.drawable.poster_placeholder).into(poster)
    }

    /** Hands the whole ordered series to the player so it can offer the next episode. */
    private fun playEpisode(episode: Episode) {
        val series = episodes.sortedBy { it.number }
        val show = anime
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putStringArrayListExtra(PlayerActivity.EXTRA_URLS, ArrayList(series.map { it.url }))
            putExtra(PlayerActivity.EXTRA_NUMBERS, series.map { it.number }.toIntArray())
            putExtra(PlayerActivity.EXTRA_INDEX, series.indexOf(episode))
            if (show != null) {
                putExtra(PlayerActivity.EXTRA_SLUG, show.slug)
                putExtra(PlayerActivity.EXTRA_TITLE, show.title)
                putExtra(PlayerActivity.EXTRA_SERIES_URL, show.url)
                putExtra(PlayerActivity.EXTRA_POSTER, show.posterUrl)
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("newestFirst", newestFirst)
        selectedPageStart?.let { outState.putInt("pageStart", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val COLUMNS = 10
        private const val PAGE_SIZE = 10
        private const val PAGINATE_OVER = 20
    }
}

/** qfilm's compact episode buttons: number only; the last opened one carries a small marker. */
class EpisodeAdapter(private val onPlay: (Episode) -> Unit) : RecyclerView.Adapter<EpisodeAdapter.VH>() {
    private var items: List<Episode> = emptyList()
    var topFocusId: Int = R.id.watch_latest
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, minOf(DetailsActivity.COLUMNS, items.size))
        }
    var lastWatched: Int? = null
        set(value) {
            if (field == value) return
            val old = field
            field = value
            items.indexOfFirst { it.number == old }.takeIf { it >= 0 }?.let(::notifyItemChanged)
            items.indexOfFirst { it.number == value }.takeIf { it >= 0 }?.let(::notifyItemChanged)
        }

    fun submitList(list: List<Episode>) { items = list; notifyDataSetChanged() }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.tile_episode, parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val ep = items[position]
        val res = holder.itemView.resources
        val label = ep.number.toString()
        if (ep.number == lastWatched) {
            val marker = res.getString(R.string.episode_last_marker)
            holder.title.text = SpannableStringBuilder("$label\n$marker").apply {
                val start = label.length + 1
                setSpan(StyleSpan(Typeface.BOLD), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(AbsoluteSizeSpan(11, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(ContextCompat.getColor(holder.itemView.context, R.color.tv_accent_amber)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            holder.itemView.contentDescription = res.getString(R.string.episode_play_last, ep.number)
        } else {
            holder.title.text = label
            holder.itemView.contentDescription = res.getString(R.string.episode_play, ep.number)
        }
        holder.itemView.nextFocusUpId = if (position < DetailsActivity.COLUMNS) topFocusId else View.NO_ID
        holder.itemView.setOnClickListener { onPlay(ep) }
    }
    class VH(view: View) : RecyclerView.ViewHolder(view) { val title: TextView = view.findViewById(R.id.ep_num) }
}

/** A range of episodes shown together, e.g. 11–20. */
data class EpisodePage(val first: Int, val last: Int, val episodes: List<Episode>)

/** Range tabs above the episode grid; focusing a tab selects it, like TV tab rows. */
class EpisodePageAdapter(private val onSelect: (Int) -> Unit) : RecyclerView.Adapter<EpisodePageAdapter.VH>() {
    private var pages: List<EpisodePage> = emptyList()
    var selected = 0
        private set
    var upId: Int = R.id.watch_latest
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, pages.size)
        }

    @SuppressLint("NotifyDataSetChanged") // The whole range list is replaced (new series or order).
    fun submit(list: List<EpisodePage>, selectedIndex: Int) {
        pages = list
        selected = selectedIndex
        notifyDataSetChanged()
    }

    fun select(index: Int) {
        val old = selected
        selected = index
        notifyItemChanged(old)
        notifyItemChanged(index)
    }

    override fun getItemCount() = pages.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.tile_page, parent, false) as TextView)

    override fun onBindViewHolder(holder: VH, position: Int) {
        val page = pages[position]
        val res = holder.label.resources
        val single = page.first == page.last
        holder.label.text = if (single) page.first.toString() else res.getString(R.string.episode_page, page.first, page.last)
        holder.label.contentDescription = if (single) res.getString(R.string.episode_badge, page.first)
            else res.getString(R.string.episode_page_description, page.first, page.last)
        holder.label.isSelected = position == selected
        holder.label.nextFocusUpId = upId
        holder.label.nextFocusDownId = R.id.episode_grid
        holder.label.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) holder.adapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(onSelect) }
        holder.label.setOnClickListener { holder.adapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(onSelect) }
    }

    class VH(val label: TextView) : RecyclerView.ViewHolder(label)
}
