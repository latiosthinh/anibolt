# AnimeTV UI/UX audit and fixes

Verified on the existing Android TV emulator (`emulator-5554`, Android 14 / API 34, 1920 × 1080, density 320). Screenshots were captured from the running app and inspected during implementation; they are not mockups.

## Findings and changes

| Finding | Impact | Fix |
| --- | --- | --- |
| Home used a FrameLayout as if it were a fragment; early adapter initialization also left the rows fragment absent | Startup crash / empty home; Down could crash | Real fragment declaration and adapter setup after the fragment lifecycle initializes its children |
| Small titles, inconsistent colors, weak or missing focus styling | Difficult to read and track selection from the sofa | Shared dark blue-gray palette, 17–28sp primary text, rounded surfaces, 3dp focus outline, TV margins, concise remote hints |
| Search had no clear loading, empty, or failure state; concurrent searches could race | Unclear progress and stale results | Native four-column results grid, explicit Search/Retry, cancellable jobs with request IDs, visible status and input/result focus routes |
| Genre extras were ignored | Genre selection did not browse that genre | Actual genre endpoint and updated provider markup selectors; all twelve existing genres exposed from home |
| Fake episode fallback created 166 identical links | Viewer could select a nonexistent episode | Removed fabricated episodes and data-only links; real links only, explicit unavailable state and Retry |
| Latest action opened the show landing page | Extra navigation before playback | Select the highest numbered real episode and open its URL |
| Ten narrow episode columns and an oversized information block | Dense targets and too many navigation steps | Six-column episode grid, synopsis beside poster, newest/oldest toggle, explicit Up/Right focus paths |
| Detail poster was never populated; empty ratings displayed | Incomplete or misleading details | Load poster, hide absent metadata, remove website suffix from title |
| Loopback proxy was mandatory and corrupted Vietnamese text | Failed on standalone TVs; unreadable titles | Fetch HTTPS content directly; also correct UTF-8 in the optional proxy script |
| Home duplicated and shuffled the same catalog under live-sounding labels | Misleading categories and unstable order | One stable, honestly labeled curated discovery shelf plus genres |
| Playback exposed website navigation; fullscreen callbacks did nothing | Remote playback was difficult and fullscreen was unreliable | Keep the provider embed intact, expand it to the viewport, add native focusable controls and fullscreen lifecycle handling |
| Native JavaScript could not reach cross-origin player frames | Play/pause/seek did nothing for the actual provider | Feature-gated document-start media commands inside frames, using fixed command names and postMessage; no native JavaScript bridge or arbitrary code evaluation |
| WebView was not paused or destroyed | Playback/resource leakage after leaving | Pause embedded videos on backgrounding, invalidate pending feedback, stop loading and destroy on exit |

## CineBOLT redesign (2026-11-15)

> **Renamed to AniBolt (2026-11-15).** The app name, launcher label and rail wordmark ("Ani**Bolt**") now read AniBolt; the logo, palette and layout are unchanged. The launcher banner shows the logo with the "Ani**Bolt**" wordmark, centred as a group ([banner](ux-audit/anibolt-banner.png)). Sections below keep the CineBOLT name as recorded at the time.

The interface now follows [qfilm-client](https://github.com/latiosthinh/qfilm-client) (CineBOLT), adapted for a 10-foot screen and D-pad. Decisions: CineBOLT name and logo, Vietnamese interface, left navigation rail.

| qfilm-client | TV adaptation |
| --- | --- |
| Palette: `#090909` page, zinc-900 `#18181B` panels, `#FAFAFA` text at 70% for meta, orange `#F15A24`, amber `#FFAA00` | Same tokens in `colors.xml`; focus is an orange ring (cards) or a solid orange fill (buttons, episode tiles, genres), matching qfilm's hover states |
| Sticky 250px sidebar: logo, "Cine**BOLT**", active item orange with a 3px right border | Rail collapses to 80dp icons and expands to 232dp with labels when focused. Khám phá / Tìm kiếm / Thể loại. Left from a row's first card opens it, Right returns, the first Back goes to the rail |
| `card-md`: 5:6 poster, orange episode badge top-left, dimmed meta line, title (orange on hover) | `card_poster.xml`: same structure, badge from the provider listing (`Tập 219/219 [4K]`), title turns orange on focus, play mark over dimmed art |
| `card-big` hero "Phim mới cập nhật": 16:9, top-anchored art, chips + bold title on black band | `card_wide.xml` row fed by the provider's *Mới cập nhật* page; top crop (`TopCrop`), quality chip, retryable loading/failure card |
| Movie page: poster column, title + quality badge, meta line, **TỔNG QUAN** + info table, **XEM PHIM** episode buttons | Same order. Quality from the `[4K]` tag, meta = other name • latest episode • status • ★ rating • views, genres table, 10-column episode buttons |
| Player modal with orange theme | Player keeps the provider embed; control bar gets the near-black panel with an orange top rule, Vietnamese labels |
| Logo SVG (orange→pink play, amber→yellow bolt) | Vector `ic_logo` (gradients on API 24+, solid colors on 21–23) and a rendered 320×180 launcher banner |

Supporting fixes found while mapping provider data: genres were silently empty (the old `.movie-detail-genre` selector no longer exists), and rating and status used selectors that no longer match. Details now read `.list_cate`, `.org_title`, `.hh3d-new-ep`, `.hh3d-info` and the star-rating payload. All interface text moved to `strings.xml`. The player script reports state codes, which Kotlin localizes. Unused classes and layouts were removed: the Leanback details fragment, the old presenters and the old card layouts.

Lint: 0 errors, 19 warnings, down from 64. The remaining warnings are dependency updates, `minSdk` attribute notices, WebView JavaScript, and `notifyDataSetChanged`. Verified on the emulator: [home](ux-audit/cinebolt-home.png). The D-pad suite (`scripts/test-tv-remote.ps1`) is rewritten for the new navigation and Vietnamese text, but **this run has not been recorded yet**.

### Follow-ups (2026-11-15)

| Request | Change |
| --- | --- |
| Search suggestions while typing | From 3 characters, a debounced (300 ms) request to the provider's own live search (`admin-ajax.php`, `dox_ajax_live_search`) fills a dropdown under the field: up to 6 rows with thumbnail, title (typed part in bold, accent-insensitive, so "tien" matches "Tiên") and original name. OK opens that title's details directly; Down from the field enters the list, Up from the first row and Back return to the field; Search/Enter still runs the full result grid and closes the dropdown. Answers are cached per query; failures hide the dropdown silently because Search reports connection errors |
| Launcher banner without the wordmark | Banner is the centred CineBOLT mark only ([banner](ux-audit/cinebolt-banner.png)); the in-app rail still shows "CineBOLT" when expanded |
| Provider site visible for ~2 s before the video | `assets/player-only.js` is injected at document start in the top frame: it hides every element except `#halim-player-wrapper`, sized full screen, before the first paint, so the viewer sees black plus the native "loading" bar, then the video. If no player appears within 5 s of page load, the style is removed so the page is never left blank. WebViews without document-start scripts keep the WebView invisible until the player has been sized |

| Plain text or blank screens while moving between screens | `ui/LoadingView`: the CineBOLT mark breathing above an amber-to-orange bar sweeping across a zinc track, with the title and a caption. Details cover the page with it (holding remote focus so hidden buttons cannot be pressed) until the show has loaded; search and genre results show it in the results area; the player shows it on black with "Series · Tập N". Screen changes use a 220–260 ms fade with a slight depth change (`RouteAnimation`) instead of the default slide |
| Choosing an episode did not start the video | The provider page loads a JW Player embed (streamfree.vip) that waits for Play. `tv-player.js` in the top page now asks only the `#halim-player-wrapper` frame (never ad frames) to autoplay; inside it, JW Player is started through its API (`jwplayer().play()`), with `video.play()` and a single start-button press as fallbacks. Playback start is reported back; the loader then fades and a 2.5 s label names the episode, with no control bar over the video. Any explicit command (viewer pause, backgrounding) ends autoplay for good. If nothing plays within 30 s (at least 15 s after the page loads), the controls open with "Video chưa tự phát…" and focus on Play/Pause. While loading, OK and Left/Right are ignored so they cannot cancel autoplay; Up/Down still open the controls, Back exits |

| Home showed half-built rows on launch | Cold start covers home with the loader ("Đang tải phim mới cập nhật…") until the *Phim mới cập nhật* row has loaded or failed, at most 8 s; then focus goes to the rows. While it is up, D-pad keys are swallowed and Back leaves the app |
| Long series meant walking through every episode tile | Series with more than 20 episodes get a strip of range tabs (1–10, 11–20, …; the last range shows its real end, e.g. 161–166). Focusing a tab shows that range's episodes (one 10-wide row). Down: Play → selected range → its episodes; Up returns the same way (`SelectedFocusRecyclerView` always focuses the selected tab). Opens on the range with the last-watched episode, else the first range in the current order; switching newest/oldest starts at that order's first range. The selected range survives recreation |

| Player controls were a row of text buttons on a solid panel | `layout/player_controls.xml`: a fade-to-black scrim instead of a panel; title and status; an orange timeline with elapsed/total time; round icon buttons — Exit on the left, Rewind 10 s / Play-Pause (larger) / Forward 10 s / Next in the centre, Reload / Hide on the right. Focus is a solid orange circle and the focused button's name is shown underneath (icons carry content descriptions for screen readers and tests). The play button shows Pause while playing and Play while paused. While the bar is open, the app asks `tv-player.js` once a second for `t:<pos>:<duration>:<paused>`; only the `#halim-player-wrapper` frame's answer is accepted, and reading the position never cancels autoplay. Unknown durations (live/HLS without length) hide the timeline |

| No way to start an episode over | "Xem lại từ đầu" button (replay icon, left of Rewind) and the remote's Previous media key: JW Player is seeked to 0 through its API and keeps playing (plain `<video>`: `currentTime = 0` + play). Reported as "Đã quay về đầu tập" |
| The embed's own JW Player controller could appear (on load, pause, buffering, clicks) on top of the app's controls | `assets/no-native-controls.js`, injected at document start into every frame: CSS hides JW's control bar, big play/pause/buffering display, dock, logo, title, settings, tooltips, next-up/related overlays and right-click menu (plus video.js, Plyr, ArtPlayer, DPlayer, Flowplayer equivalents and the browser's native `<video>` controls); every second it switches JW's controller off via `setControls(false)` (which also disables its click-to-pause and keyboard shortcuts) and clears `video.controls`, so it stays off if the player rebuilds its UI. Captions and error messages are deliberately left visible. Play/Pause now goes through the JW API rather than clicking JW's (now hidden) start button. Limits: WebViews without document-start scripts cannot reach the cross-origin frame, and any ad UI drawn inside the JW controller (e.g. a Skip button) is hidden with it |

| Text over the episode loading screen | The player's loader is icon-only: the breathing logo and the sweep bar on black, no title or caption. Its content description still announces "Series · Tập N. Đang tải tập phim…" for screen readers, and it has the id `player_loader` for the remote suite |

Lint: 0 errors, 19 warnings (unchanged). `node scripts/test-player-controls.cjs` covers the autoplay targeting, JW start, the playback report and cancellation. **Not yet verified on the emulator**: remote key input is blocked for the assistant, so the suggestion dropdown, the loaders, autoplay and the episode ranges need a run of `scripts/test-tv-remote.ps1` (updated for all three) or a manual check. Autoplay with sound relies on the WebView's `mediaPlaybackRequiresUserGesture = false`; it has not been observed on a physical TV.

### Performance on the TV (2026-11-15)

Measured on the Sony BRAVIA before the change: the debug build's process stayed alive after leaving the app, at about 74 MB (41 MB native heap, mostly decoded posters; 12 MB of unoptimized dex) with 4 idle TLS sockets open.

| Finding | Impact | Fix |
| --- | --- | --- |
| The TV ran the debug build | Debuggable apps run without full compiler optimization, with a larger dex and no code shrinking | `release` build: R8 minify + resource shrinking, not debuggable, signed with the debug key so it installs over the existing app (watch history kept). APK 6.6 MB → 1.9 MB. Installed on the TV and the emulator |
| Poster cache and sockets kept after leaving the app | ~40 MB held in the background that the launcher and other apps could use | `AniBoltApp.onTrimMemory(UI_HIDDEN)` clears Glide's memory cache completely (Glide alone only halves it) and closes OkHttp's idle connections. Posters reload from the disk cache on return |
| The hidden provider page still loads its ads, analytics and images | CPU, network and memory spent on content nobody sees, during video playback | `PlayerRequestFilter`: sub-resource requests to analytics/ad hosts (Tag Manager, Analytics, Ahrefs, JW analytics, DoubleClick, AdSense, pop-under networks…) get an empty 204. `blockNetworkImage = true`: no images are fetched in the player (the video is not an image) |
| Player left with Home or a screensaver | The page's scripts and timers kept running in the background | `onStop` → `pauseTimers()` (after the pause command), `onStart` → `resumeTimers()`; `onDestroy` resumes them before destroying the WebView |
| WebView renderer could be killed for memory | Without a handler, the whole app is killed with it | `setRendererPriorityPolicy(IMPORTANT, waivedWhenNotVisible = true)` lets the system reclaim it in the background; `onRenderProcessGone` drops the dead WebView and reopens the same episode if the player is on screen, or closes the player otherwise |
| Loader animations under a covered screen | Infinite animators kept ticking | `LoadingView.onWindowVisibilityChanged` stops them while hidden and restarts them on return |

### Seek slider (2026-11-15)

| Request | Fix |
| --- | --- |
| The player had no slider, only ±10 s buttons | The timeline bar is now a focusable `SeekBar`. Press Up from the buttons to focus it: the handle grows into an orange knob and the label reads "◀ ▶ để tua (giữ để tua nhanh) · OK để xem từ đây". Left/Right move the target 10 s per press. Holding the key speeds up to 30 s and then 1 min per step, which covers the requested 1 min / 5 min jumps without extra buttons. The target time shows in orange. The video jumps there after 0.8 s without a key press, on OK, or when focus leaves the slider. Down returns to Play. A new `seek:<seconds>` command in `tv-player.js` (exact digits only, clamped to the length, ignored while the length is unknown) does the jump; `node scripts/test-player-controls.cjs` covers it. The remote suite checks focus, the hint, the seek report and the way back to Play |

### Paged listings (2026-11-17)

| Request | Fix |
| --- | --- |
| A listing page with pagination (search and genres showed only the provider's first 20 results) | New rail item **Mới cập nhật** (under Home) opens the provider's full update list: 25 pages of 20 titles. Search and genre results are paged too. A numbered bar sits under the grid, "‹ Trước  1 … 4 5 [6] 7 8 … 25  Sau ›": the first and last pages are always shown, plus two either side of the current one, which is outlined. Down from the last row of cards lands on the current page. OK on any page loads it, scrolls the grid to the top and focuses its first card. Left/Right stay inside the bar. CH+ / CH− (also Page Down/Up and Next/Previous) change page from anywhere except the search field. The status line reads "Trang 6 / 25 · Số phim: 20". The page count comes from the provider's own pagination links (`/…/page/N`, `/page/N?s=…`). A page past the end shows an explicit empty message. The page and query survive activity recreation |
| The home row shows only the newest 16 | The "Phim mới cập nhật" row ends with a **Xem tất cả › Toàn bộ phim mới cập nhật** card (the row's existing message-card style, orange on focus) that opens the same paged listing as the rail item |
| Layout not good: a page's 20 titles needed scrolling, and the number bar sat below the fold | **One screen = one page, no scrolling.** 20 posters can't fit a 960×540 dp TV screen at a readable size, so each provider page is shown as two screens of **10 titles (5 columns × 2 rows)**. That is the most that fits with ~130 dp posters and a readable title. The header is a single row (title + field or Reload) and the footer a single line ("Trang 3 / 50 · Số phim: 10" on the left, keys on the right). Cards size themselves to the grid's height and drop the meta line, so two rows always fit. The number bar is gone. **Right past the last card of a row** turns to the next screen on the same row, **Left past the first card** turns back, and CH+ / CH− still work. A held key turns only one page. Each provider page is fetched once and kept (last 4), so the second half is instant. The screen count is 2 per provider page, or 1 for a short last page |

| Spacing/padding off: posters centred in wide columns, so their edges matched neither the title, the Reload button nor the footer | The listing screens (search, genre, Mới cập nhật) now have **the home screen's left menu** (`layout/nav_rail.xml` + `ui/NavRail.kt`, shared with home). The menu is drawn over the content so widening it doesn't resize the grid. The footer is gone: the page count sits right-aligned in the header next to Reload. The grid has no padding. Cards are as wide as fits two rows (minimum 24 dp between columns). The first column is flush with the title, the last with the Reload button, and the gaps between are equal. Each card is shifted inside its grid cell. Left from the first column of screen 1 opens the menu, and Right returns. Back goes to the menu first, then home. Choosing another menu item replaces the listing, so Back always leads home |
| "Tải lại" should be an icon | On listings it is a square 56 dp button showing the player's reload icon, with the same focus style as before. "Tải lại" / "Thử lại" moved to its content description. Search keeps its "Tìm" text |
| Down should hide the player controls | With the bar up, Down on any button hides it (on release, so that key's release does not reopen it). On the seek slider, Down still moves to Play. Down or Up while hidden brings the bar back, so Down toggles it. The ready hint reads "Xuống để ẩn thanh điều khiển · …". Which of the two Down does is decided when the key goes down (focus has already moved to Play by its release), so one press on the slider only moves to the buttons and a second press hides. Buttons are now 46 dp (Play 58 dp, previously 56 / 72) with 16 dp group spacing |

The remote suite walks the rail to Mới cập nhật and checks 10 cards are on screen at once. It flips with CH+ and CH−, Right past the 5th card and Left past the first. Its rail path to Search gained one Down.

The remote suite now resets with `pm clear` (works on the release build; `run-as` needs a debug build). Lint: 0 errors, 19 warnings (unchanged); JS tests pass. **Not yet measured after the change**: the app could not be launched from here, so the new memory figures, playback with the blocklist, and the R8 build at runtime still need the remote suite run (`! powershell -File scripts\test-tv-remote.ps1 -Serial 192.168.1.148:5555`) or a manual session.

## Second pass (2026-11-14)

| Finding | Impact | Fix |
| --- | --- | --- |
| Player control bar stayed up until "Hide" was chosen | Covered the bottom ~20% of the video, where Vietsub subtitles are burned in | Bar auto-hides after 6 s of remote inactivity once the page loads (never in the error state); Up/Down/Menu bring it back; any key while visible resets the timer |
| Seeking/pausing with the bar hidden gave no feedback | Viewer could not tell whether ±10 s or pause worked | Small top-right on-screen readout shows the player state for 2.5 s |
| Hidden-mode Down was passed to the web page | Could scroll the provider page behind the video | Down now shows the controls |
| Player never said what was playing | No orientation after leaving details | Heading "Series · Episode N" above the status line |
| No way to continue to the next episode | Back → find tile → OK for every episode | "Next · Ep N" button (and the media Next key) loads the next real episode in place; hidden on the last episode |
| Home shelf and genres used unaccented names ("Tien Nghich", "Tu Tien") while details/search showed "Tiên Nghịch" | Inconsistent, harder to read for Vietnamese viewers | Names copied from the provider's own titles/genre menu |
| No memory of what was watched | Returning viewers had to find their place in 100+ episodes | On-device history (last episode per series, max 20): "Continue watching" row on home, "Resume · Ep N" on details (focused by default when it differs from the latest), "Last watched" marker on the episode tile |
| Launcher icon and banner were flat colored rectangles | App looked broken and unnamed on the TV home screen | Rendered 320×180 banner with play mark and "AnimeTV" name, and a round icon |
| Home hint opened with filler copy | Extra reading for no action | Hint is only the remote actions |

Lint after this pass: 0 errors, 64 warnings. The 9 additional warnings are all the existing hardcoded-string category (`SetTextI18n`) plus a density-folder notice for the banner.

## Screenshot review

- [Home](ux-audit/home.png): visible focus ring, readable titles, stable discovery shelf and remote guidance.
- [Search](ux-audit/search.png): correctly rendered Vietnamese, four readable columns, clear query and result count.
- [Genre navigation](ux-audit/genres.png) and [genre results](ux-audit/genre-results.png): same focus treatment and a reachable Refresh action.
- [Details](ux-audit/details.png): synopsis next to artwork, prominent Play action, room for more episode rows.
- [Oldest first](ux-audit/episodes-oldest.png): episode one is reachable without scrolling through the whole series.
- [Empty search](ux-audit/empty.png): a clear explanation with Search still available.
- [Player](ux-audit/player.png): actual video playback with native controls; [controls hidden](ux-audit/video.png) shows the provider video filling the viewport.

The screenshot pass caught and corrected a lifecycle-related empty home, broken Vietnamese decoding, excessive blank space in details, and the provider's requirement to remain inside its original iframe. Long card titles intentionally ellipsize to two lines; opening details shows the title. In scrolling rows/grids, a partially visible next card or row indicates more content.

## Validation

- `assembleDebug` and `lintDebug` pass. Lint reports zero errors and 55 warnings (including hardcoded strings, legacy layout issues and dependency recommendations); this is not a warning-free build.
- `node scripts/test-player-controls.cjs` passes: toggle, explicit pause, seek limits, unknown duration, nested-frame forwarding, command-source validation, and rejected playback feedback.
- `scripts/test-tv-remote.ps1` exercises cold launch, focus transitions, details, ordering, focus restoration, live search, empty search and genre browsing using D-pad/OK/Back input. It captures screenshots and fails on missing expected UI states. See [recorded output](ux-audit/remote-test-results.txt). Second pass: the suite now also covers the player heading, Next episode, controls auto-hiding and returning, Resume on details, the last-watched marker and the Continue watching row. It clears the app's watch history first. **This extended run has not been recorded yet**; the second-pass APK is built and installed on the emulator.
- Manual emulator verification: actual episode video plays; media pause reports Paused; fast-forward updates playback position; Hide removes native controls; Up restores them; Back returns to the episode screen with its selected item retained.
- APK: `app/build/outputs/apk/debug/app-debug.apk`. Installed on the running emulator.

## Remaining boundaries

- Playback still uses the external provider's web player. Provider availability, markup, ads, stream quality and subtitle options remain provider-controlled. Playback was tested on this emulator/provider combination, not every TV or WebView version. Older WebViews without document-start script support have only top-document control fallback.
- Discovery is a curated catalog; search/genres load the provider's first result page. History records the last episode *opened*, not a playback position or completion; "Resume" reopens that episode from the provider's start. Pagination and a fully native media player are separate product features.
- Complete localization, large-font configurations, physical-TV overscan, screen reader behavior and network outage recovery have not been exhaustively tested. Loading/error/retry paths are implemented; the automated runtime suite covers the no-results state, not simulated outages.

## Design and API references

- [Android TV focus system](https://developer.android.com/design/ui/tv/guides/styles/focus-system)
- [Android TV navigation](https://developer.android.com/training/tv/get-started/navigation)
- [WebViewCompat document-start scripts](https://developer.android.com/reference/androidx/webkit/WebViewCompat#addDocumentStartJavaScript(android.webkit.WebView,java.lang.String,java.util.Set))
