param([string]$Serial = 'emulator-5554')
# D-pad smoke suite for the AniBolt TV client. Drives the emulator with remote keys only.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$evidence = Join-Path $projectRoot 'docs/ux-audit'
New-Item -ItemType Directory -Force $evidence | Out-Null
function Invoke-Adb {
    $AdbArgs = $args
    $ErrorActionPreference = 'Continue'
    & adb -s $Serial @AdbArgs 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $AdbArgs" }
}
function Key([int]$code) { Invoke-Adb shell input keyevent $code | Out-Null; Start-Sleep -Milliseconds 150 }
function Read-Ui {
    Invoke-Adb shell uiautomator dump /sdcard/animetv-smoke.xml | Out-Null
    Invoke-Adb pull /sdcard/animetv-smoke.xml (Join-Path $evidence 'smoke-current.xml') 2>&1 | Out-Null
    return [xml](Get-Content (Join-Path $evidence 'smoke-current.xml') -Encoding utf8 -Raw)
}
function Expect([string]$xpath, [string]$label) {
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        $tree = Read-Ui
        if ($tree.SelectSingleNode($xpath)) { Write-Output "PASS: $label"; return }
    }
    throw "FAIL: $label ($xpath)"
}
function Absent([string]$xpath, [string]$label, [int]$waitSeconds) {
    Start-Sleep -Seconds $waitSeconds
    if ((Read-Ui).SelectSingleNode($xpath)) { throw "FAIL: $label ($xpath still present)" }
    Write-Output "PASS: $label"
}
function Shot([string]$name) {
    Invoke-Adb shell screencap -p /sdcard/animetv-smoke.png
    Invoke-Adb pull /sdcard/animetv-smoke.png (Join-Path $evidence "$name.png") 2>&1 | Out-Null
}
$focused = '@focused="true"'
Invoke-Adb shell am force-stop com.animetv
# Start from a clean watch history so the first rows are the provider feed and the curated shelf.
# pm clear works on the release build too (run-as needs a debug build); the app keeps nothing else worth saving.
Invoke-Adb shell pm clear com.animetv | Out-Null
Invoke-Adb shell am start -n com.animetv/.MainActivity | Out-Null

# Home: qfilm hero row, curated row, genres, left navigation rail.
# Evidence only: on a fast connection the loader can clear before a UI dump completes.
Shot 'home-loading'
Expect '//node[@text="Đề cử"]' 'Home rows render after cold start'
Expect '//node[contains(@content-desc,", Tập")]' 'Recently updated row loads provider cards with episode badges'
Absent '//node[@text="Đang tải phim mới cập nhật…"]' 'Loading screen clears once the row has loaded' 1
Shot 'home-hero'
Key 20
Expect "//node[$focused and @content-desc='Tiên Nghịch']" 'Down focuses the first curated anime'
Shot 'home'
Key 21
Expect "//node[$focused and @content-desc='Khám phá']" 'Left from the first card opens the navigation rail'
Expect '//node[@text="Tìm kiếm"]' 'Rail expands to show labels'
Shot 'nav-rail'
Key 22
Expect "//node[$focused and @content-desc='Tiên Nghịch']" 'Right returns to the same card'

# Details: qfilm movie page.
Key 23
Expect '//node[@resource-id="com.animetv:id/watch_latest" and @enabled="true"]' 'Real episode links load'
Expect '//node[@text="TỔNG QUAN"]' 'Overview section is shown'
Shot 'details'
Key 20
Expect "//node[$focused and @resource-id='com.animetv:id/episode_page_tab']" 'Long series: Down from Play reaches the episode ranges'
Key 20
Expect "//node[$focused and starts-with(@content-desc,'Phát tập')]" 'Ranges lead down to their episodes'
Key 19
Expect "//node[$focused and @resource-id='com.animetv:id/episode_page_tab']" 'Up from episodes returns to the selected range'
Key 19
Expect "//node[$focused and @resource-id='com.animetv:id/watch_latest']" 'Up from the ranges returns to Play'
Key 22
Expect "//node[$focused and @resource-id='com.animetv:id/episode_order']" 'Sort is reachable with Right'
Key 23
Expect '//node[@content-desc="Phát tập 1"]' 'Oldest-first opens the first range with episode one'
Shot 'episodes-oldest'
Key 20
Expect "//node[$focused and @content-desc='Tập 1 đến 10']" 'Down from the sort lands on the selected range'
Key 22
Expect '//node[@content-desc="Phát tập 11"]' 'Moving along the ranges switches the episodes shown'
Absent '//node[@content-desc="Phát tập 1"]' 'Only the selected range is listed' 0
Shot 'episode-ranges'
Key 4
Expect "//node[$focused and @content-desc='Tiên Nghịch']" 'Back restores the selected anime'

# Search through the rail (Home, Mới cập nhật, Tìm kiếm, Thể loại).
Key 21
Key 20
Expect "//node[$focused and @content-desc='Mới cập nhật']" 'Rail lists the full Mới cập nhật listing under Home'
Key 20
Expect "//node[$focused and @content-desc='Tìm kiếm']" 'Rail reaches Search'
Key 23
Expect "//node[$focused and @resource-id='com.animetv:id/search_input']" 'Search opens with input focus'
Invoke-Adb shell input text tien
Expect '//node[@resource-id="com.animetv:id/suggestion_title"]' 'Typing shows live title suggestions'
Expect '//node[starts-with(@content-desc,"Gợi ý: Tiên Nghịch")]' 'Suggestions match without accents'
Shot 'search-suggestions'
Key 66
Absent '//node[@resource-id="com.animetv:id/search_suggestions"]' 'Full search replaces the suggestions' 1
Expect '//node[@resource-id="com.animetv:id/card_title"]' 'Search returns live results'
Shot 'search'
Key 19
Expect "//node[$focused and @resource-id='com.animetv:id/search_input']" 'Up returns from search results to query'
Invoke-Adb shell input text zzznotananime123456
Key 66
Expect '//node[starts-with(@text,"Không tìm thấy phim nào.")]' 'No-result search has an actionable empty state'
Shot 'empty'
Key 4
Expect "//node[$focused and @content-desc='Tìm kiếm']" 'Back from search moves to the menu on the search screen'
Key 4
Expect "//node[$focused and @content-desc='Tìm kiếm']" 'Second Back returns home to the rail item'

# Full Mới cập nhật listing: one screen of 10 cards (5 × 2, no scrolling), flipped with CH+/CH− or the grid edges.
Key 19
Key 23
Expect '//node[@resource-id="com.animetv:id/search_title" and @text="Mới cập nhật"]' 'Rail opens the Mới cập nhật listing'
Expect '//node[@resource-id="com.animetv:id/card_title"]' 'Listing loads its first screen of titles'
Expect '//node[@resource-id="com.animetv:id/search_page_info" and starts-with(@text,"Trang 1 /")]' 'Status names screen 1 and the screen count'
Expect "//node[@resource-id='com.animetv:id/search_grid' and count(.//node[@resource-id='com.animetv:id/card_title'])=10]" 'A whole screen of 10 cards is on screen at once'
Expect '//node[@content-desc="Khám phá"]' 'The listing shows the left menu'
Expect '//node[@resource-id="com.animetv:id/search_submit" and @content-desc="Tải lại" and @text=""]' 'Reload is an icon button named for screen readers'
Shot 'listing-page-1'
Key 166
Expect '//node[@resource-id="com.animetv:id/search_page_info" and starts-with(@text,"Trang 2 /")]' 'CH+ turns to the next screen'
Key 167
Expect '//node[@resource-id="com.animetv:id/search_page_info" and starts-with(@text,"Trang 1 /")]' 'CH− turns back'
Key 22
Key 22
Key 22
Key 22
Key 22
Expect '//node[@resource-id="com.animetv:id/search_page_info" and starts-with(@text,"Trang 2 /")]' 'Right past the last card of a row turns the page'
Expect "//node[$focused and .//node[@resource-id='com.animetv:id/card_title']]" 'Focus lands on a card of the new screen'
Shot 'listing-page-2'
Key 21
Expect '//node[@resource-id="com.animetv:id/search_page_info" and starts-with(@text,"Trang 1 /")]' 'Left past the first card of a row turns back'
Key 21
Key 21
Key 21
Key 21
Key 21
Expect "//node[$focused and @content-desc='Mới cập nhật']" 'Left from the first card of screen 1 opens the menu'
Key 22
Expect "//node[$focused and .//node[@resource-id='com.animetv:id/card_title']]" 'Right from the menu returns to the cards'
Key 4
Expect "//node[$focused and @content-desc='Mới cập nhật']" 'Back from the cards moves to the menu'
Key 4
Expect "//node[$focused and @content-desc='Mới cập nhật']" 'Second Back returns home to the rail item'

# Genres from the home row.
Key 22
Key 20
Expect "//node[$focused and @content-desc='Duyệt thể loại Tu Tiên']" 'Genres are reachable from home with Down'
Shot 'genres'
Key 23
Expect '//node[@resource-id="com.animetv:id/card_title"]' 'Genre selection loads its own results'
Shot 'genre-results'
Key 19
Expect "//node[$focused and @resource-id='com.animetv:id/search_submit']" 'Genre grid returns to Refresh with Up'
Key 4
Expect "//node[$focused and @content-desc='Thể loại' and @selected='true']" 'Back on a genre moves to its menu item first'
Key 4

# Playback, history and Continue watching.
Key 19
Expect "//node[$focused and @content-desc='Tiên Nghịch']" 'Up from genres returns to the selected anime'
Key 23
Expect '//node[@resource-id="com.animetv:id/watch_latest" and @enabled="true"]' 'Details reload for playback flow'
# Play button -> newest range -> its first episode -> the one before it.
Key 20
Key 20
Key 22
Key 23
Expect '//node[@resource-id="com.animetv:id/player_loader" and starts-with(@content-desc,"Tiên Nghịch · Tập")]' 'Player opens on the loader, named for screen readers'
Absent '//node[@resource-id="com.animetv:id/player_loader"]//node[string-length(@text) > 0]' 'Episode loader is icon and bar only, no text' 0
Shot 'player-loading'
Absent '//node[@resource-id="com.animetv:id/player_loader"]' 'Episode starts on its own and the loader clears' 20
Absent '//node[starts-with(@text,"Video chưa tự phát")]' 'Autoplay did not fall back to the Play prompt' 0
Shot 'player-playing'
Key 20
Expect '//node[@content-desc="Thoát"]' 'Down opens the controls over the video'
Expect '//node[starts-with(@content-desc,"Tập tiếp ·")]' 'Player offers the next episode'
Expect '//node[@resource-id="com.animetv:id/player_play" and @focused="true"]' 'Controls open on the play/pause button'
Expect '//node[@resource-id="com.animetv:id/player_time_total"]' 'Timeline shows the episode length'
# Seek slider: Up focuses it, Right moves the target time, and a short pause jumps the video there.
Key 19
Expect "//node[@resource-id='com.animetv:id/player_progress' and $focused]" 'Up from the buttons focuses the seek slider'
Expect "//node[@resource-id='com.animetv:id/player_button_label' and starts-with(@text,'◀ ▶ để tua')]" 'The slider explains its keys'
Key 22
Key 22
Expect "//node[@resource-id='com.animetv:id/player_status' and starts-with(@text,'Vị trí')]" 'Right on the slider seeks the video and reports the new position'
Shot 'player-seek'
Key 20
Expect "//node[@resource-id='com.animetv:id/player_play' and $focused]" 'Down from the slider returns to Play'
Expect '//node[@content-desc="Thoát"]' 'Down on the slider keeps the controls up'
Key 21
Key 21
Expect "//node[$focused and @content-desc='Xem lại từ đầu']" 'Start over sits left of the seek buttons'
Key 23
Expect '//node[@text="Đã quay về đầu tập"]' 'Start over returns to the beginning of the episode'
Shot 'player-controls'
Absent '//node[@content-desc="Thoát"]' 'Controls auto-hide so subtitles stay visible' 14
Shot 'player-hidden'
Key 20
Expect '//node[@content-desc="Thoát"]' 'Down brings the controls back'
Key 20
Absent '//node[@content-desc="Thoát"]' 'Down on the buttons hides the controls again' 1
Key 20
Expect '//node[@content-desc="Thoát"]' 'Down shows them once more'
Key 4
Expect '//node[@resource-id="com.animetv:id/resume_episode" and starts-with(@text,"Xem tiếp · Tập")]' 'Details offers Resume for the opened episode'
Expect '//node[contains(@content-desc,"đang xem dở")]' 'Opened episode tile is marked'
Shot 'details-resume'
Key 4
Expect '//node[@text="Tiếp tục xem"]' 'Home shows a Continue watching row'
Expect "//node[$focused and starts-with(@content-desc,'Xem tiếp Tiên Nghịch')]" 'Continue watching is selected on return'
Shot 'continue-watching'
Key 4
Expect "//node[$focused and @content-desc='Khám phá']" 'Back from content moves to the rail before exiting'
Write-Output 'Remote smoke suite passed. App is on the home screen.'
