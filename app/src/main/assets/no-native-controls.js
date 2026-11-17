// Injected at document start into every frame of the episode page. The app draws its own controls,
// so the embedded web player's controller must never show: not on load, pause, buffering, seeking,
// mouse/remote activity, or after the player re-creates its UI.
// Subtitles/captions and error messages are left visible on purpose.
(function () {
    if (window.__animeTvNoNativeControls) return;
    window.__animeTvNoNativeControls = true;

    var hidden = [
        // JW Player (the provider's embed): control bar, big play/pause/buffering display, dock, logo,
        // title, settings, tooltips, next-up and related overlays, right-click menu.
        '.jw-controls', '.jw-controlbar', '.jw-display', '.jw-display-container', '.jw-display-icon-container',
        '.jw-dock', '.jw-logo', '.jw-title', '.jw-settings-menu', '.jw-tooltip', '.jw-nextup-container',
        '.jw-related-shelf-container', '.jw-rightclick', '.jw-preview .jw-icon',
        // Other common web players, in case the provider switches.
        '.vjs-control-bar', '.vjs-big-play-button', '.vjs-menu',
        '.plyr__controls', '.plyr__control--overlaid', '.plyr__menu',
        '.art-bottom', '.art-mask', '.art-controls', '.art-settings',
        '.dplayer-controller', '.dplayer-bezel', '.dplayer-setting',
        '.fp-ui', '.fp-controls'
    ];
    var css = hidden.join(',') + '{display:none!important;opacity:0!important;pointer-events:none!important}' +
        // Browser-native <video controls> UI.
        'video::-webkit-media-controls,video::-webkit-media-controls-enclosure,video::-webkit-media-controls-panel,' +
        'video::-webkit-media-controls-start-playback-button,video::-webkit-media-controls-overlay-play-button{display:none!important}' +
        'video,.jwplayer,.jw-media{cursor:none!important}';

    function addStyle() {
        if (document.getElementById('animetv-no-native-controls')) return;
        var parent = document.head || document.documentElement;
        if (!parent) return;
        var style = document.createElement('style');
        style.id = 'animetv-no-native-controls';
        style.textContent = css;
        parent.appendChild(style);
    }
    addStyle();
    // Some pages rebuild <head>; re-add the style once the DOM exists.
    document.addEventListener('DOMContentLoaded', addStyle);

    // Beyond CSS: turn the controller off where the player exposes it, and keep it off.
    function disable() {
        addStyle();
        try {
            var jw = typeof window.jwplayer === 'function' ? window.jwplayer() : null;
            // JW's own switch also disables its click-to-pause and keyboard shortcuts.
            if (jw && typeof jw.getControls === 'function' && jw.getControls()) jw.setControls(false);
        } catch (_) { }
        var videos = document.getElementsByTagName('video');
        for (var i = 0; i < videos.length; i++) {
            if (videos[i].controls) videos[i].controls = false;
        }
    }
    setInterval(disable, 1000);
})();
