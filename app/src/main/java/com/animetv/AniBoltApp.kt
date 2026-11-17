package com.animetv

import android.app.Application
import android.content.ComponentCallbacks2
import com.animetv.scraper.HhpandaScraper
import com.bumptech.glide.Glide

/**
 * Keeps the app light on the TV once it is out of sight: TVs have little RAM and the launcher,
 * live TV and other apps need it more than our poster cache does.
 */
class AniBoltApp : Application() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN: no AniBolt screen is visible any more. Glide alone would only halve its cache here.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            Glide.get(this).clearMemory()
            HhpandaScraper.releaseConnections()
        }
    }
}
