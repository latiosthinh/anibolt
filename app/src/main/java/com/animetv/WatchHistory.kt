package com.animetv

import android.content.Context
import com.animetv.model.Anime
import org.json.JSONObject

/** Last episode opened per series, stored on the device only. */
data class WatchEntry(val anime: Anime, val episode: Int, val updatedAt: Long)

object WatchHistory {
    private const val PREFS = "watch_history"
    private const val MAX_ENTRIES = 20

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(context: Context, anime: Anime, episode: Int) {
        if (anime.slug.isBlank() || episode <= 0) return
        val json = JSONObject()
            .put("title", anime.title)
            .put("slug", anime.slug)
            .put("url", anime.url)
            .put("poster", anime.posterUrl)
            .put("episode", episode)
            .put("at", System.currentTimeMillis())
        val editor = prefs(context).edit().putString(anime.slug, json.toString())
        // Keep the store small: drop the oldest series beyond the limit.
        recent(context).filter { it.anime.slug != anime.slug }
            .drop(MAX_ENTRIES - 1)
            .forEach { editor.remove(it.anime.slug) }
        editor.apply()
    }

    fun get(context: Context, slug: String): WatchEntry? = parse(prefs(context).getString(slug, null))

    fun recent(context: Context): List<WatchEntry> =
        prefs(context).all.values.mapNotNull { parse(it as? String) }.sortedByDescending { it.updatedAt }

    private fun parse(raw: String?): WatchEntry? = runCatching {
        val json = JSONObject(raw ?: return null)
        WatchEntry(
            Anime(json.getString("title"), json.getString("slug"), json.optString("url"), json.optString("poster")),
            json.getInt("episode"),
            json.optLong("at"),
        )
    }.getOrNull()
}
