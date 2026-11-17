package com.animetv

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.animetv.model.Anime
import com.animetv.ui.TopCrop
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

internal fun openDetails(view: View, anime: Anime) {
    val ctx = view.context
    ctx.startActivity(Intent(ctx, DetailsActivity::class.java).apply {
        putExtra("slug", anime.slug)
        putExtra("url", anime.url)
        putExtra("title", anime.title)
        putExtra("poster", anime.posterUrl)
    })
}

private fun TextView.showOrHide(value: String, keepSpace: Boolean = false) {
    text = value
    visibility = when {
        value.isNotBlank() -> View.VISIBLE
        keepSpace -> View.INVISIBLE
        else -> View.GONE
    }
}

/**
 * qfilm card-md: portrait poster with the orange episode badge, a dimmed meta line and the title.
 * Renders curated/search results ([Anime]) and history ([WatchEntry]).
 * [widthDp] fixes the card width for rows; null lets a grid size it.
 */
class PosterCardPresenter(private val widthDp: Int? = 168) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.card_poster, parent, false)
        widthDp?.let { view.layoutParams = ViewGroup.LayoutParams(parent.context.dp(it), ViewGroup.LayoutParams.WRAP_CONTENT) }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val entry = item as? WatchEntry
        val anime = entry?.anime ?: item as? Anime ?: return
        val view = viewHolder.view
        val res = view.resources
        view.findViewById<TextView>(R.id.card_title).text = anime.title
        if (entry != null) {
            view.findViewById<TextView>(R.id.card_badge).showOrHide(res.getString(R.string.episode_badge, entry.episode))
            // The row is already titled "Tiếp tục xem"; the badge carries the episode.
            view.findViewById<TextView>(R.id.card_meta).showOrHide("")
            view.contentDescription = res.getString(R.string.continue_description, anime.title, entry.episode)
        } else {
            view.findViewById<TextView>(R.id.card_badge).showOrHide(anime.badge)
            view.findViewById<TextView>(R.id.card_meta).showOrHide(anime.quality.ifBlank { anime.originalTitle }, keepSpace = true)
            view.contentDescription = anime.title
        }
        Glide.with(view)
            .load(anime.posterUrl)
            .transform(CenterCrop())
            .placeholder(R.drawable.poster_placeholder)
            .error(R.drawable.poster_placeholder)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(view.findViewById(R.id.card_poster))
        view.setOnClickListener { openDetails(view, anime) }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        Glide.with(viewHolder.view).clear(viewHolder.view.findViewById<ImageView>(R.id.card_poster))
        viewHolder.view.setOnClickListener(null)
    }
}

/** qfilm card-big for the "Phim mới cập nhật" hero row. */
class WideCardPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.card_wide, parent, false))

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val anime = item as? Anime ?: return
        val view = viewHolder.view
        view.findViewById<TextView>(R.id.card_title).text = anime.title
        view.findViewById<TextView>(R.id.card_badge).showOrHide(anime.badge)
        view.findViewById<TextView>(R.id.card_chip).showOrHide(anime.quality)
        view.contentDescription = listOf(anime.title, anime.badge).filter { it.isNotBlank() }.joinToString(", ")
        Glide.with(view)
            .load(anime.posterUrl)
            .transform(TopCrop())
            .placeholder(R.drawable.poster_placeholder)
            .error(R.drawable.poster_placeholder)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(view.findViewById(R.id.card_poster))
        view.setOnClickListener { openDetails(view, anime) }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        Glide.with(viewHolder.view).clear(viewHolder.view.findViewById<ImageView>(R.id.card_poster))
        viewHolder.view.setOnClickListener(null)
    }
}

/** Genre tile; same focus treatment as qfilm's buttons (zinc, then solid orange). */
class GenreCardPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.card_genre, parent, false))

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val genre = item as? Genre ?: return
        val view = viewHolder.view
        view.findViewById<TextView>(R.id.genre_text).text = genre.name
        view.contentDescription = view.resources.getString(R.string.browse_genre, genre.name)
        view.setOnClickListener { genre.open(view.context) }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) = viewHolder.view.setOnClickListener(null)
}

/** A focusable message card used while a row loads or after it fails. */
data class RowMessage(val text: String, val onClick: (() -> Unit)? = null)

class MessageCardPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.card_message, parent, false))

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val message = item as? RowMessage ?: return
        viewHolder.view.findViewById<TextView>(R.id.message_text).text = message.text
        viewHolder.view.contentDescription = message.text
        viewHolder.view.setOnClickListener { message.onClick?.invoke() }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) = viewHolder.view.setOnClickListener(null)
}
