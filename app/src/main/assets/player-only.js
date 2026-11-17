// Injected at document start in the provider's episode page (top frame only), so the site's
// header, menus and ads are never painted: only the embedded player is visible, full screen.
// PlayerActivity removes the style again if no player appears, so the page is never left blank.
(function () {
    if (window !== window.top || document.getElementById('animetv-player-only')) return;
    var style = document.createElement('style');
    style.id = 'animetv-player-only';
    style.textContent = [
        'html,body{background:#000!important;overflow:hidden!important}',
        'body *{visibility:hidden!important}',
        '#halim-player-wrapper,#halim-player-wrapper *,.animetv-target,.animetv-target *{visibility:visible!important}',
        '#halim-player-wrapper{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;' +
            'max-width:none!important;max-height:none!important;margin:0!important;padding:0!important;' +
            'background:#000!important;z-index:2147483647!important}',
        '#halim-player-wrapper iframe{position:absolute!important;inset:0!important;width:100%!important;height:100%!important;border:0!important}'
    ].join('\n');
    (document.head || document.documentElement).appendChild(style);
})();
