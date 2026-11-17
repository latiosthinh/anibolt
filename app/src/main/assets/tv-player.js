(function () {
    if (window.animeTvControl) return;
    var START_BUTTONS = '.jw-icon-display, .vjs-big-play-button, .plyr__control--overlaid';
    var COMMANDS = ['play', 'pause', 'toggle', 'rewind', 'forward', 'restart'];
    // The timeline slider jumps to an absolute position: "seek:<whole seconds>", nothing else.
    var SEEK = /^seek:\d{1,6}$/;
    function isCommand(action) { return typeof action === 'string' && (COMMANDS.indexOf(action) !== -1 || SEEK.test(action)); }
    // Autoplay stops for good as soon as the viewer (or the app, e.g. backgrounding) issues any command.
    var autoplay = { started: false, cancelled: false };
    function report(state) {
        if (window === window.top) window.animeTvState = state;
        else window.top.postMessage({ animeTvState: state }, '*');
    }
    function jwPlayer() {
        try {
            var jw = typeof window.jwplayer === 'function' ? window.jwplayer() : null;
            return jw && typeof jw.getState === 'function' ? jw : null;
        } catch (_) { return null; }
    }
    function control(action) {
        if (!isCommand(action)) return;
        autoplay.cancelled = true;
        var seekTo = SEEK.test(action) ? parseInt(action.slice(5), 10) : -1;
        var video = document.querySelector('video');
        if (action === 'restart') {
            // Start over: back to 0:00 and keep playing. JW's own seek keeps its state consistent.
            var jw = jwPlayer();
            try {
                if (jw) { jw.seek(0); jw.play(); report('restarted'); }
                else if (video) {
                    video.currentTime = 0;
                    var resumed = video.play();
                    if (resumed && resumed.catch) resumed.catch(function () { });
                    report('restarted');
                }
            } catch (_) { report('not-ready'); }
        } else if (video) {
            try {
                if (action === 'pause' || (action === 'toggle' && !video.paused)) {
                    video.pause(); report('paused');
                } else if (action === 'play' || action === 'toggle') {
                    var started = video.play();
                    if (started && started.then) started.then(function () { report('playing'); }).catch(function () { report('failed'); });
                    else report('playing');
                } else if (seekTo >= 0) {
                    if (isFinite(video.duration)) {
                        video.currentTime = Math.min(video.duration, seekTo);
                        report('pos:' + Math.floor(video.currentTime));
                    } else report('buffering');
                } else if (action === 'rewind' || action === 'forward') {
                    if (isFinite(video.duration)) {
                        video.currentTime = Math.max(0, Math.min(video.duration, video.currentTime + (action === 'forward' ? 10 : -10)));
                        report('pos:' + Math.floor(video.currentTime));
                    } else report('buffering');
                }
            } catch (_) { report('not-ready'); }
        } else if (action === 'play' || action === 'toggle') {
            // Some web players only create their video element after pressing Play. Their own buttons are
            // hidden (no-native-controls.js), so prefer the player API and press the button only as a fallback.
            var player = jwPlayer();
            if (player) { player.play(); report('starting'); }
            else {
                var start = document.querySelector(START_BUTTONS);
                if (start) { start.click(); report('starting'); }
            }
        }
        for (var i = 0; i < window.frames.length; i++) {
            window.frames[i].postMessage({ animeTvAction: action }, '*');
        }
    }
    /** Inside the provider's player frame: start playback as soon as the player can, then report it. */
    function startAutoplay() {
        if (autoplay.started || autoplay.cancelled) return;
        autoplay.started = true;
        var attempts = 0;
        var clicked = false;
        var timer = setInterval(function () {
            if (autoplay.cancelled || ++attempts > 75) { clearInterval(timer); return; }
            var video = document.querySelector('video');
            if (video && !video.paused && video.currentTime > 0) { report('playing'); clearInterval(timer); return; }
            try {
                // JW Player (the provider's embed) only fetches the stream after play().
                var jw = typeof window.jwplayer === 'function' ? window.jwplayer() : null;
                if (jw && typeof jw.getState === 'function') {
                    var state = jw.getState();
                    if (state === 'idle' || state === 'paused') jw.play();
                    return;
                }
            } catch (_) { }
            if (video && (video.currentSrc || video.src)) {
                var started = video.play();
                if (started && started.catch) started.catch(function () { });
                return;
            }
            // Clicking a start button toggles, so press it at most once.
            if (!clicked) {
                var start = document.querySelector(START_BUTTONS);
                if (start) { start.click(); clicked = true; }
            }
        }, 400);
    }
    /** Position for the native timeline: "t:<seconds>:<duration or -1>:<1 if paused>". Read-only; never cancels autoplay. */
    function progress() {
        var video = document.querySelector('video');
        if (!video) return;
        var duration = isFinite(video.duration) ? Math.floor(video.duration) : -1;
        var value = 't:' + Math.floor(video.currentTime || 0) + ':' + duration + ':' + (video.paused ? 1 : 0);
        if (window === window.top) window.animeTvProgress = value;
        else window.top.postMessage({ animeTvProgress: value }, '*');
    }
    function playerFrame() { return document.querySelector('#halim-player-wrapper iframe'); }
    window.animeTvControl = control;
    /** Called by the app about once a second while its controls are open; returns the last known position. */
    window.animeTvStatus = function () {
        progress();
        var frame = playerFrame();
        if (frame && frame.contentWindow) frame.contentWindow.postMessage({ animeTvAction: 'status' }, '*');
        return window.animeTvProgress || '';
    };
    window.addEventListener('message', function (event) {
        if (!event.data || typeof event.data !== 'object') return;
        if (event.data.animeTvAction && (event.source === window.parent || event.source === window)) {
            if (event.data.animeTvAction === 'autoplay') startAutoplay();
            else if (event.data.animeTvAction === 'status') progress();
            else if (isCommand(event.data.animeTvAction)) control(event.data.animeTvAction);
        }
        if (window === window.top && typeof event.data.animeTvState === 'string') {
            window.animeTvState = event.data.animeTvState.slice(0, 120);
        }
        // Only the episode's player frame may move the timeline (ads can carry videos too).
        if (window === window.top && typeof event.data.animeTvProgress === 'string') {
            var frame = playerFrame();
            if (frame && event.source === frame.contentWindow) window.animeTvProgress = event.data.animeTvProgress.slice(0, 40);
        }
    });
    if (window === window.top) {
        // Ask only the provider's player frame to autoplay; ad frames never receive the request.
        // Repeated because the frame is created by the page and its script loads after ours.
        var nudges = 0;
        var nudge = setInterval(function () {
            if (window.animeTvState === 'playing' || autoplay.cancelled || ++nudges > 80) { clearInterval(nudge); return; }
            var frame = document.querySelector('#halim-player-wrapper iframe');
            if (frame && frame.contentWindow && frame.getAttribute('src')) frame.contentWindow.postMessage({ animeTvAction: 'autoplay' }, '*');
        }, 500);
    }
})();
