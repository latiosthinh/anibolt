package com.animetv

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.animetv.model.Anime
import com.animetv.ui.LoadingView

/** Keeps the provider's embedded player intact, with native controls for TV remotes. */
class PlayerActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var controls: LinearLayout
    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var osd: TextView
    private lateinit var play: ImageButton
    private lateinit var next: ImageButton
    private lateinit var timeline: View
    private lateinit var progressBar: SeekBar
    /** Set when Down is pressed on a control button: its release hides the bar. */
    private var hideOnRelease = false
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView
    private lateinit var buttonLabel: TextView
    /** Last position and length reported by the page, in seconds (-1 = length unknown). */
    private var knownPosition = 0
    private var knownDuration = -1
    /** Where the slider is being moved to, in seconds; -1 while not seeking. */
    private var scrubTarget = -1
    /** Right after a seek the page may still report the old position once; don't let the slider jump back. */
    private var ignoreProgressUntil = 0L
    private val commitSeek = Runnable { commitScrub() }
    /** Refreshes the timeline and play/pause icon while the control bar is open. */
    private val pollProgress = object : Runnable {
        override fun run() {
            if (isDestroyed || controls.visibility != View.VISIBLE) return
            web.evaluateJavascript("window.animeTvStatus ? window.animeTvStatus() : ''") { result ->
                if (isDestroyed) return@evaluateJavascript
                applyProgress(runCatching { org.json.JSONArray("[$result]").optString(0) }.getOrDefault(""))
            }
            web.postDelayed(this, PROGRESS_POLL_MS)
        }
    }
    private lateinit var controlScript: String
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var failed = false
    /** False once the WebView is destroyed (renderer gone or activity ending). */
    private var webAlive = true
    private var resumed = false
    private var pageReady = false
    private var prepareAttempts = 0
    private var hidesPageNatively = false
    private var commandId = 0
    private var urls: List<String> = emptyList()
    private var numbers: IntArray = IntArray(0)
    private var index = 0
    private val preparePlayer = object : Runnable {
        override fun run() {
            if (isDestroyed || failed) return
            val giveUp = prepareAttempts >= PLAYER_SEARCH_ATTEMPTS
            web.evaluateJavascript("""
                (function(){
                    var wrapper=document.querySelector('#halim-player-wrapper');
                    var frame=wrapper && wrapper.querySelector('iframe');
                    var target=frame ? wrapper : document.querySelector('video');
                    if(target){
                        target.classList.add('animetv-target');
                        target.style.cssText='position:fixed;inset:0;width:100vw;height:100vh;max-width:none;max-height:none;background:black;z-index:2147483647';
                        if(frame) frame.style.cssText='position:absolute;inset:0;width:100%;height:100%;border:0';
                        document.body.style.overflow='hidden';
                    } else if($giveUp){
                        // No recognizable player: show the page rather than a black screen.
                        var hide=document.getElementById('animetv-player-only');
                        if(hide) hide.remove();
                    }
                })();
            """.trimIndent(), null)
            // Without document-start scripts the page was hidden natively until the player is sized.
            if (web.visibility != View.VISIBLE) web.visibility = View.VISIBLE
            if (++prepareAttempts < 10) web.postDelayed(this, 1000)
        }
    }
    // The control bar covers burned-in subtitles, so it gets out of the way when idle.
    private val autoHide = Runnable { if (!failed && pageReady && controls.visibility == View.VISIBLE) hideControls() }
    private val clearOsd = Runnable { osd.visibility = View.GONE }
    private lateinit var loader: LoadingView
    private var loadingDeadline = 0L
    /** While the loader is up, waits for tv-player.js to report that autoplay has started the video. */
    private val watchPlayback = object : Runnable {
        override fun run() {
            if (isDestroyed || failed || !loader.isShowing) return
            if (SystemClock.uptimeMillis() > loadingDeadline) {
                onAutoplayTimedOut()
                return
            }
            web.evaluateJavascript("window.animeTvState || ''") { result ->
                if (isDestroyed || !loader.isShowing) return@evaluateJavascript
                if (runCatching { org.json.JSONArray("[$result]").optString(0) }.getOrDefault("") == "playing") onPlaybackStarted()
            }
            web.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        readEpisodes()
        index = savedInstanceState?.getInt(EXTRA_INDEX, index)?.coerceIn(0, maxOf(0, urls.size - 1)) ?: index
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            // Only the video is ever shown; the hidden page's posters and banners would still be downloaded and decoded.
            settings.blockNetworkImage = true
            // Let the system reclaim the page's renderer while the player is in the background (onRenderProcessGone recovers).
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            }
        }
        controlScript = assets.open("tv-player.js").bufferedReader().use { it.readText() }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            hidesPageNatively = true
        } else {
            // Runs the same fixed media commands inside nested player frames. No native bridge or eval.
            WebViewCompat.addDocumentStartJavaScript(web, controlScript, setOf("*"))
            // Hides the provider's site chrome before first paint; only the player is ever shown.
            WebViewCompat.addDocumentStartJavaScript(web, assets.open("player-only.js").bufferedReader().use { it.readText() }, setOf("*"))
            // Every frame: the embed's own controller (JW Player etc.) never shows; the app's bar replaces it.
            WebViewCompat.addDocumentStartJavaScript(web, assets.open("no-native-controls.js").bufferedReader().use { it.readText() }, setOf("*"))
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        // Covers the page until the video is actually playing; the control bar can still open above it.
        loader = LoadingView(this).apply {
            id = R.id.player_loader
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        root.addView(loader, FrameLayout.LayoutParams(-1, -1))
        controls = layoutInflater.inflate(R.layout.player_controls, root, false) as LinearLayout
        heading = controls.findViewById(R.id.player_heading)
        status = controls.findViewById(R.id.player_status)
        timeline = controls.findViewById(R.id.player_timeline)
        progressBar = controls.findViewById(R.id.player_progress)
        timeCurrent = controls.findViewById(R.id.player_time_current)
        timeTotal = controls.findViewById(R.id.player_time_total)
        buttonLabel = controls.findViewById(R.id.player_button_label)
        progressBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) buttonLabel.setText(R.string.player_seek_hint) else commitScrub()
        }
        progressBar.setOnKeyListener { _, keyCode, event ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) scrub(if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1, event.repeatCount)
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        if (scrubTarget >= 0) commitScrub() else videoCommand("toggle")
                    }
                    true
                }
                else -> false
            }
        }
        fun button(id: Int, action: () -> Unit): ImageButton = controls.findViewById<ImageButton>(id).apply {
            setOnClickListener { action() }
            // The buttons are icons only, so the focused one is named underneath.
            setOnFocusChangeListener { view, hasFocus -> if (hasFocus) buttonLabel.text = view.contentDescription }
        }
        button(R.id.player_exit) { finish() }
        button(R.id.player_restart) { videoCommand("restart") }
        button(R.id.player_rewind) { videoCommand("rewind") }
        play = button(R.id.player_play) { videoCommand("toggle") }
        button(R.id.player_forward) { videoCommand("forward") }
        next = button(R.id.player_next) { loadEpisode(index + 1) }
        button(R.id.player_reload) {
            failed = false
            prepareAttempts = 0
            status.setText(R.string.player_loading)
            hideControls()
            startLoading()
            web.reload()
        }
        button(R.id.player_hide) { hideControls() }
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        osd = TextView(this).apply {
            textSize = 18f
            setTextColor(color(R.color.tv_text))
            setBackgroundResource(R.drawable.tile_bg)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
        }
        root.addView(osd, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(24), dp(48), 0) })
        setContentView(root)
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                failed = false
                pageReady = false
                status.setText(R.string.player_loading)
                // Older WebViews cannot hide the site before it paints, so keep the black backdrop until the player is sized.
                if (hidesPageNatively) web.visibility = View.INVISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!failed) {
                    pageReady = true
                    status.setText(R.string.player_ready)
                    // The embed starts loading only now; give autoplay time to reach the stream.
                    loadingDeadline = maxOf(loadingDeadline, SystemClock.uptimeMillis() + AUTOPLAY_AFTER_LOAD_MS)
                    web.evaluateJavascript(controlScript, null)
                    web.removeCallbacks(preparePlayer)
                    web.post(preparePlayer)
                    if (controls.visibility == View.VISIBLE) scheduleAutoHide()
                }
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                if (request?.isForMainFrame != true && PlayerRequestFilter.isBlocked(request?.url)) PlayerRequestFilter.emptyResponse() else null
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // The page's renderer crashed or was reclaimed for memory. Without handling this the whole app would be
                // killed with it. Drop the dead WebView; come back on the same episode if the viewer is watching.
                if (!webAlive) return true
                webAlive = false
                web.removeCallbacks(preparePlayer)
                web.removeCallbacks(watchPlayback)
                web.removeCallbacks(pollProgress)
                root.removeView(web)
                web.destroy()
                if (resumed) recreate() else finish()
                return true
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) showError()
            }
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true) showError()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || customView != null) { callback?.onCustomViewHidden(); return }
                customView = view
                customCallback = callback
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                controls.bringToFront()
                osd.bringToFront()
                hideControls()
            }
            override fun onHideCustomView() { closeFullscreen() }
        }
        loadEpisode(index)
    }

    /** Accepts the series list from details; falls back to the legacy single-URL extra. */
    private fun readEpisodes() {
        val list = intent.getStringArrayListExtra(EXTRA_URLS)
        val nums = intent.getIntArrayExtra(EXTRA_NUMBERS)
        if (list != null && nums != null && list.size == nums.size && list.isNotEmpty()) {
            urls = list
            numbers = nums
            index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, list.size - 1)
        } else {
            urls = listOfNotNull(intent.getStringExtra("url"))
            numbers = intArrayOf(intent.getIntExtra("episode", 0))
            index = 0
        }
    }

    private fun loadEpisode(target: Int) {
        if (target !in urls.indices) return
        index = target
        commandId++
        val number = numbers[index]
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        heading.text = when {
            title.isNotBlank() && number > 0 -> getString(R.string.player_heading, title, number)
            number > 0 -> getString(R.string.player_episode, number)
            else -> title
        }
        heading.visibility = if (heading.text.isBlank()) View.GONE else View.VISIBLE
        val hasNext = index + 1 < urls.size
        next.visibility = if (hasNext) View.VISIBLE else View.GONE
        if (hasNext) next.contentDescription = getString(R.string.player_next, numbers[index + 1])
        // A new episode starts from zero; hide the old timeline until the new one reports.
        cancelScrub()
        knownPosition = 0
        knownDuration = -1
        if (progressBar.isFocused) play.requestFocus()
        timeline.visibility = View.INVISIBLE
        setPlayIcon(paused = false)
        val url = urls[index]
        failed = false
        prepareAttempts = 0
        web.removeCallbacks(preparePlayer)
        if (url.startsWith("https://") || url.startsWith("http://")) {
            recordHistory(number)
            // Stop the previous episode and forget its "playing" state before the next page replaces it.
            web.evaluateJavascript("if(window.animeTvControl)window.animeTvControl('pause');window.animeTvState='';", null)
            hideControls()
            startLoading()
            web.loadUrl(url)
        } else showError()
    }

    private fun recordHistory(number: Int) {
        val slug = intent.getStringExtra(EXTRA_SLUG) ?: return
        val anime = Anime(
            intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { slug },
            slug,
            intent.getStringExtra(EXTRA_SERIES_URL).orEmpty(),
            intent.getStringExtra(EXTRA_POSTER).orEmpty(),
        )
        WatchHistory.record(this, anime, number)
    }

    /** Branded loader with the series and episode until the video plays (or autoplay gives up). */
    private fun startLoading() {
        // Icon only: the logo and sweep bar on black, no text over the screen; screen readers still hear what is loading.
        loader.show(caption = null, description = listOf(heading.text, getString(R.string.player_loading)).filter { it.isNotBlank() }.joinToString(". "))
        loadingDeadline = SystemClock.uptimeMillis() + AUTOPLAY_TIMEOUT_MS
        web.removeCallbacks(watchPlayback)
        web.postDelayed(watchPlayback, 400)
    }

    private fun onPlaybackStarted() {
        web.removeCallbacks(watchPlayback)
        loader.hide()
        status.setText(R.string.player_state_playing)
        // No control bar over the opening seconds; just name what is playing.
        if (controls.visibility != View.VISIBLE && heading.text.isNotBlank()) showOsd(heading.text.toString())
    }

    private fun onAutoplayTimedOut() {
        web.removeCallbacks(watchPlayback)
        loader.hide()
        showControls()
        status.setText(R.string.player_press_play)
        play.requestFocus()
    }

    private fun videoCommand(action: String) {
        // The viewer took over: show the real player state instead of the loader.
        if (loader.isShowing) {
            web.removeCallbacks(watchPlayback)
            loader.hide()
        }
        val currentCommand = ++commandId
        web.evaluateJavascript("window.animeTvState='';if(window.animeTvControl)window.animeTvControl('$action');", null)
        web.postDelayed({
            if (isDestroyed || currentCommand != commandId) return@postDelayed
            web.evaluateJavascript("window.animeTvState || ''") { result ->
                if (isDestroyed || currentCommand != commandId) return@evaluateJavascript
                val state = runCatching { org.json.JSONArray("[$result]").optString(0) }.getOrDefault("")
                val message = describeState(state)
                if (state == "paused") setPlayIcon(paused = true) else if (state == "playing") setPlayIcon(paused = false)
                status.text = message
                // Without the control bar there is otherwise no feedback for seeks and pauses.
                if (controls.visibility != View.VISIBLE) showOsd(message)
            }
        }, 700)
    }

    /** tv-player.js reports fixed state codes; the wording lives in string resources. */
    private fun describeState(state: String): String = when {
        state == "playing" -> getString(R.string.player_state_playing)
        state == "paused" -> getString(R.string.player_state_paused)
        state == "restarted" -> getString(R.string.player_state_restarted)
        state == "starting" -> getString(R.string.player_state_starting)
        state == "failed" -> getString(R.string.player_state_failed)
        state == "buffering" -> getString(R.string.player_state_buffering)
        state.startsWith("pos:") -> state.removePrefix("pos:").toIntOrNull()
            ?.let { getString(R.string.player_state_position, it / 60, it % 60) }
            ?: getString(R.string.player_not_ready)
        else -> getString(R.string.player_not_ready)
    }

    /** Applies "t:<seconds>:<duration or -1>:<1 if paused>" from tv-player.js to the timeline and play icon. */
    private fun applyProgress(value: String) {
        val match = PROGRESS.matchEntire(value) ?: return
        val position = match.groupValues[1].toInt()
        val duration = match.groupValues[2].toInt()
        setPlayIcon(paused = match.groupValues[3] == "1")
        knownDuration = duration
        if (duration <= 0) {
            // A live or not-yet-loaded stream cannot be seeked; keep focus on a visible control.
            cancelScrub()
            if (progressBar.isFocused) play.requestFocus()
            timeline.visibility = View.INVISIBLE
            return
        }
        timeline.visibility = View.VISIBLE
        if (scrubTarget >= 0 || SystemClock.uptimeMillis() < ignoreProgressUntil) return
        knownPosition = position.coerceIn(0, duration)
        showTimeline(knownPosition, duration)
    }

    private fun showTimeline(position: Int, duration: Int) {
        progressBar.progress = (position.toLong() * 1000L / duration).toInt()
        timeCurrent.text = clock(position)
        timeTotal.text = clock(duration)
    }

    /** Moves the slider's target: 10 s per press; holding the key speeds up to 30 s, then 1 min per step. */
    private fun scrub(direction: Int, repeat: Int) {
        if (knownDuration <= 0) return
        val step = when {
            repeat < SCRUB_FAST_AFTER -> 10
            repeat < SCRUB_FASTEST_AFTER -> 30
            else -> 60
        }
        val from = if (scrubTarget >= 0) scrubTarget else knownPosition
        scrubTarget = (from + direction * step).coerceIn(0, knownDuration)
        showTimeline(scrubTarget, knownDuration)
        timeCurrent.setTextColor(color(R.color.tv_accent))
        progressBar.removeCallbacks(commitSeek)
        progressBar.postDelayed(commitSeek, SCRUB_COMMIT_MS)
    }

    /** Jumps the video to the slider's target, if the viewer moved it. */
    private fun commitScrub() {
        progressBar.removeCallbacks(commitSeek)
        val target = scrubTarget
        if (target < 0) return
        scrubTarget = -1
        knownPosition = target
        timeCurrent.setTextColor(color(R.color.tv_text))
        ignoreProgressUntil = SystemClock.uptimeMillis() + SEEK_SETTLE_MS
        videoCommand("seek:$target")
    }

    private fun cancelScrub() {
        progressBar.removeCallbacks(commitSeek)
        if (scrubTarget >= 0) timeCurrent.setTextColor(color(R.color.tv_text))
        scrubTarget = -1
    }

    private fun clock(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
    }

    /** The middle button shows what pressing it will do: Play while paused, Pause while playing. */
    private fun setPlayIcon(paused: Boolean) {
        play.setImageResource(if (paused) R.drawable.ic_player_play else R.drawable.ic_player_pause)
        play.contentDescription = getString(if (paused) R.string.player_play else R.string.player_pause)
        if (play.isFocused) buttonLabel.text = play.contentDescription
    }

    private fun showOsd(message: String) {
        osd.text = message
        osd.visibility = View.VISIBLE
        osd.bringToFront()
        osd.removeCallbacks(clearOsd)
        osd.postDelayed(clearOsd, 2500)
    }

    private fun showError() {
        failed = true
        web.removeCallbacks(watchPlayback)
        loader.hide()
        root.removeCallbacks(autoHide)
        showControls()
        status.setText(R.string.player_error)
    }
    private fun scheduleAutoHide() {
        root.removeCallbacks(autoHide)
        root.postDelayed(autoHide, AUTO_HIDE_MS)
    }
    private fun showControls() {
        osd.visibility = View.GONE
        controls.visibility = View.VISIBLE
        controls.bringToFront()
        if (!controls.hasFocus()) play.requestFocus()
        scheduleAutoHide()
        web.removeCallbacks(pollProgress)
        web.post(pollProgress)
    }
    private fun hideControls() {
        commitScrub()
        root.removeCallbacks(autoHide)
        web.removeCallbacks(pollProgress)
        controls.visibility = View.GONE
        (customView ?: web).requestFocus()
    }
    private fun closeFullscreen() {
        customView?.let { root.removeView(it) }
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        showControls()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "toggle"
            KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
            KeyEvent.KEYCODE_MEDIA_REWIND -> "rewind"
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "forward"
            // "Previous" on an episode means start it over (Next already moves to the next episode).
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "restart"
            else -> null
        }
        if (action != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) videoCommand(action)
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) loadEpisode(index + 1)
            return true
        }
        if (controls.visibility == View.VISIBLE) {
            // Down on the slider moves to the buttons; Down on the buttons hides the bar. Decided when the key goes down
            // (focus has already moved by its release) and done on release, so that release does not reopen the bar.
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && customView == null) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) hideOnRelease = !progressBar.isFocused
                if (hideOnRelease) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        hideOnRelease = false
                        hideControls()
                    }
                    return true
                }
            }
            // Any remote activity keeps the bar up while the viewer is using it.
            scheduleAutoHide()
        } else {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> {
                    if (event.action == KeyEvent.ACTION_UP) showControls()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // While loading, OK would cancel autoplay; it starts playback on its own.
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !loader.isShowing) videoCommand("toggle")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !loader.isShowing) videoCommand(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) "rewind" else "forward")
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    @Deprecated("Legacy activity back dispatch")
    override fun onBackPressed() {
        if (customView != null) closeFullscreen() else super.onBackPressed()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(EXTRA_INDEX, index)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() {
        resumed = false
        cancelScrub()
        commandId++
        root.removeCallbacks(autoHide)
        if (webAlive) {
            web.evaluateJavascript("if(window.animeTvControl)window.animeTvControl('pause');", null)
            web.onPause()
        }
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        resumed = true
        if (webAlive) web.onResume()
        if (controls.visibility == View.VISIBLE) scheduleAutoHide()
    }
    override fun onStart() {
        super.onStart()
        if (webAlive) web.resumeTimers()
    }
    override fun onStop() {
        // Out of sight (Home, another app, screensaver): freeze the page's JavaScript timers, layout and parsing
        // so its scripts stop using the TV's CPU. Done here, after onPause's pause command has reached the player.
        if (webAlive) web.pauseTimers()
        super.onStop()
    }
    override fun onDestroy() {
        web.removeCallbacks(preparePlayer)
        web.removeCallbacks(watchPlayback)
        web.removeCallbacks(pollProgress)
        root.removeCallbacks(autoHide)
        osd.removeCallbacks(clearOsd)
        progressBar.removeCallbacks(commitSeek)
        customView?.let { root.removeView(it) }
        customCallback?.onCustomViewHidden()
        if (webAlive) {
            webAlive = false
            root.removeView(web)
            web.stopLoading()
            // Timers are process-wide; never leave them paused for the next WebView.
            web.resumeTimers()
            web.destroy()
        }
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = androidx.core.content.ContextCompat.getColor(this, id)

    companion object {
        /** Seconds after page load to wait for the embed before un-hiding the page as a fallback. */
        private const val PLAYER_SEARCH_ATTEMPTS = 5
        private const val PROGRESS_POLL_MS = 1000L
        private val PROGRESS = Regex("t:(\\d+):(-?\\d+):([01])")
        /** Longest the loader stays up for one episode before handing over to the controls. */
        private const val AUTOPLAY_TIMEOUT_MS = 30_000L
        /** Minimum wait after the page itself has loaded, since the embed only starts then. */
        private const val AUTOPLAY_AFTER_LOAD_MS = 15_000L
        const val EXTRA_URLS = "episode_urls"
        const val EXTRA_NUMBERS = "episode_numbers"
        const val EXTRA_INDEX = "episode_index"
        const val EXTRA_SLUG = "slug"
        const val EXTRA_TITLE = "anime_title"
        const val EXTRA_SERIES_URL = "anime_url"
        const val EXTRA_POSTER = "poster"
        private const val AUTO_HIDE_MS = 6000L
        /** The slider seeks once the viewer stops pressing for this long (or presses OK). */
        private const val SCRUB_COMMIT_MS = 800L
        private const val SEEK_SETTLE_MS = 1500L
        /** Key-repeat counts after which a held Left/Right moves 30 s, then 60 s per step. */
        private const val SCRUB_FAST_AFTER = 6
        private const val SCRUB_FASTEST_AFTER = 20
    }
}
