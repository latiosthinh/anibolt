package com.animetv

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * The provider's page is hidden behind the player, but its analytics and ad scripts would still
 * download, run and keep timers going on the TV's small CPU. Only hosts that never serve the
 * page, the embed or the video stream are listed.
 */
object PlayerRequestFilter {
    private val BLOCKED_HOSTS = listOf(
        // Analytics and tag managers
        "googletagmanager.com", "google-analytics.com", "analytics.ahrefs.com", "clarity.ms",
        "hotjar.com", "mc.yandex.ru", "histats.com", "scorecardresearch.com", "statcounter.com",
        "jwpltx.com", // JW Player's playback analytics pings
        // Ad networks
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "connect.facebook.net", "popads.net", "popcash.net", "propellerads.com", "adsterra.com",
        "exoclick.com", "onclickads.net", "adnxs.com", "taboola.com", "outbrain.com",
    )

    fun isBlocked(url: Uri?): Boolean {
        val host = url?.host?.lowercase() ?: return false
        return BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** An empty 204 so the page's loaders finish at once instead of retrying or waiting on a timeout. */
    fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
