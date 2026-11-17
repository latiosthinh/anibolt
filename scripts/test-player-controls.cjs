const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../app/src/main/assets/tv-player.js'), 'utf8');
function player(video, frames = [], options = {}) {
  const listeners = {};
  const timers = [];
  const selectors = { video, ...(options.selectors || {}) };
  const context = {
    document: { querySelector: selector => selectors[selector] ?? null },
    frames,
    addEventListener: (name, fn) => listeners[name] = fn,
    setInterval: fn => { timers.push(fn); return timers.length; },
    clearInterval: id => { timers[id - 1] = null; },
  };
  context.window = context;
  context.parent = options.parent || context;
  context.top = options.top || context;
  if (options.jwplayer) context.jwplayer = options.jwplayer;
  vm.runInNewContext(source, context);
  const tick = (n = 1) => { for (let i = 0; i < n; i++) timers.forEach(fn => fn && fn()); };
  return { context, listeners, tick, timers };
}
(async () => {
  const video = { paused: true, currentTime: 5, duration: 60, play() { this.paused = false; return Promise.resolve(); }, pause() { this.paused = true; } };
  const { context, listeners } = player(video);
  context.animeTvControl('toggle');
  await Promise.resolve();
  assert.equal(video.paused, false);
  assert.equal(context.animeTvState, 'playing');
  context.animeTvControl('toggle');
  assert.equal(video.paused, true);
  context.animeTvControl('pause');
  assert.equal(context.animeTvState, 'paused');
  context.animeTvControl('rewind');
  assert.equal(video.currentTime, 0, 'Rewind must clamp at the start');
  assert.equal(context.animeTvState, 'pos:0', 'Seeks report the position in seconds');
  video.currentTime = 57;
  context.animeTvControl('forward');
  assert.equal(video.currentTime, 60, 'Seek must clamp at the end');
  video.duration = NaN;
  context.animeTvControl('forward');
  assert.equal(video.currentTime, 60, 'Unknown duration must not cause an invalid seek');
  assert.equal(context.animeTvState, 'buffering');
  listeners.message({ source: {}, data: { animeTvAction: 'play' } });
  assert.equal(video.paused, true, 'Unrelated frames cannot issue playback commands');
  const forwarded = [];
  const nested = player(null, [{ postMessage: (...args) => forwarded.push(args) }]);
  nested.context.animeTvControl('pause');
  assert.equal(forwarded[0][0].animeTvAction, 'pause', 'Backgrounding must pause embedded players too');
  const rejected = player({ ...video, play() { return Promise.reject(new Error('unavailable')); } });
  rejected.context.animeTvControl('play');
  await Promise.resolve(); await Promise.resolve();
  assert.equal(rejected.context.animeTvState, 'failed', 'Rejected playback must report failure');

  // Timeline slider: absolute seeks, clamped to the end, and only in the exact "seek:<seconds>" form.
  const sliderVideo = { paused: false, currentTime: 10, duration: 1440, play() { }, pause() { } };
  const sliderParent = {};
  const slider = player(sliderVideo, [], { parent: sliderParent });
  slider.context.animeTvControl('seek:600');
  assert.equal(sliderVideo.currentTime, 600, 'Slider seeks to the chosen second');
  assert.equal(slider.context.animeTvState, 'pos:600');
  slider.context.animeTvControl('seek:99999');
  assert.equal(sliderVideo.currentTime, 1440, 'Slider seeks clamp at the end');
  for (const bad of ['seek:-5', 'seek:1.5', 'seek:', 'seek:12;alert(1)', 'seek:1234567']) {
    slider.context.animeTvControl(bad);
    slider.listeners.message({ source: sliderParent, data: { animeTvAction: bad } });
  }
  assert.equal(sliderVideo.currentTime, 1440, 'Malformed seek commands are ignored');
  slider.listeners.message({ source: sliderParent, data: { animeTvAction: 'seek:30' } });
  assert.equal(sliderVideo.currentTime, 30, 'Seek reaches nested player frames by message');
  const forwardedSeek = [];
  player(null, [{ postMessage: msg => forwardedSeek.push(msg) }]).context.animeTvControl('seek:42');
  assert.equal(forwardedSeek[0]?.animeTvAction, 'seek:42', 'The page forwards seeks to the player frame');
  const liveVideo = { ...sliderVideo, currentTime: 5, duration: Infinity };
  const liveSeek = player(liveVideo);
  liveSeek.context.animeTvControl('seek:100');
  assert.equal(liveVideo.currentTime, 5, 'No seek while the duration is unknown');
  assert.equal(liveSeek.context.animeTvState, 'buffering');

  // Autoplay: the top page asks only the provider player frame to start.
  const posted = [];
  const playerFrame = { getAttribute: name => name === "src" ? "https://embed.example/v/1" : null, contentWindow: { postMessage: msg => posted.push(msg) } };
  const page = player(null, [], { selectors: { "#halim-player-wrapper iframe": playerFrame } });
  page.tick();
  assert.equal(posted[0]?.animeTvAction, "autoplay", "Top page must ask the player frame to autoplay");
  page.context.animeTvState = "playing";
  page.tick(3);
  assert.equal(posted.length, 1, "Nudging stops once playback is reported");

  // Inside the player frame: JW Player is started through its API, then playback is reported to the top page.
  const reports = [];
  const top = { postMessage: msg => reports.push(msg) };
  let jwState = "idle"; let jwPlays = 0;
  const jw = { getState: () => jwState, play: () => { jwPlays++; jwState = "buffering"; } };
  const frameVideo = { paused: true, currentTime: 0, play() { this.paused = false; return Promise.resolve(); }, pause() { this.paused = true; } };
  const embed = player(frameVideo, [], { top, parent: top, jwplayer: () => jw });
  embed.listeners.message({ source: {}, data: { animeTvAction: "autoplay" } });
  embed.tick();
  assert.equal(jwPlays, 0, "Only the parent page may request autoplay");
  embed.listeners.message({ source: top, data: { animeTvAction: "autoplay" } });
  embed.tick();
  assert.equal(jwPlays, 1, "Autoplay starts JW Player through its API");
  embed.tick();
  assert.equal(jwPlays, 1, "Buffering is not interrupted");
  jwState = "playing"; frameVideo.paused = false; frameVideo.currentTime = 1;
  embed.tick();
  assert.equal(reports.at(-1)?.animeTvState, "playing", "Playback start is reported to the top page");

  // Any explicit command (viewer pause, app backgrounding) ends autoplay for good.
  let plays = 0;
  const jw2 = { getState: () => "paused", play: () => { plays++; } };
  const stopped = player({ ...frameVideo, paused: true, currentTime: 0 }, [], { top, parent: top, jwplayer: () => jw2 });
  stopped.listeners.message({ source: top, data: { animeTvAction: "autoplay" } });
  stopped.listeners.message({ source: top, data: { animeTvAction: "pause" } });
  stopped.tick(3);
  assert.equal(plays, 0, "A pause from the viewer must not be overridden by autoplay");
  // Timeline: the frame reports position, duration and paused state; the top page only trusts the player frame.
  const progressReports = [];
  const parentPage = { postMessage: msg => progressReports.push(msg) };
  const timed = { paused: true, currentTime: 75.6, duration: 1260.4, play() {}, pause() {} };
  const timedFrame = player(timed, [], { top: parentPage, parent: parentPage });
  timedFrame.listeners.message({ source: parentPage, data: { animeTvAction: "status" } });
  assert.equal(progressReports.at(-1)?.animeTvProgress, "t:75:1260:1", "Status reports seconds, duration and paused");
  let autoplayCalls = 0;
  const statusOnly = player({ ...timed }, [], { top: parentPage, parent: parentPage, jwplayer: () => ({ getState: () => "idle", play: () => { autoplayCalls++; } }) });
  statusOnly.listeners.message({ source: parentPage, data: { animeTvAction: "autoplay" } });
  statusOnly.listeners.message({ source: parentPage, data: { animeTvAction: "status" } });
  statusOnly.tick();
  assert.equal(autoplayCalls, 1, "Reading the position must not cancel autoplay");
  const live = { paused: false, currentTime: 10, duration: Infinity, play() {}, pause() {} };
  const liveFrame = player(live, [], { top: parentPage, parent: parentPage });
  liveFrame.listeners.message({ source: parentPage, data: { animeTvAction: "status" } });
  assert.equal(progressReports.at(-1)?.animeTvProgress, "t:10:-1:0", "Unknown duration is reported as -1");
  const statusPosts = [];
  const episodeFrame = { getAttribute: () => "https://embed.example/v/1", contentWindow: { postMessage: msg => statusPosts.push(msg) } };
  const topPage = player(null, [], { selectors: { "#halim-player-wrapper iframe": episodeFrame } });
  topPage.context.animeTvStatus();
  assert.equal(statusPosts.at(-1)?.animeTvAction, "status", "Top page asks only the player frame for its position");
  topPage.listeners.message({ source: {}, data: { animeTvProgress: "t:5:100:0" } });
  assert.equal(topPage.context.animeTvStatus(), "", "Other frames (ads) cannot move the timeline");
  topPage.listeners.message({ source: episodeFrame.contentWindow, data: { animeTvProgress: "t:5:100:0" } });
  assert.equal(topPage.context.animeTvStatus(), "t:5:100:0", "The player frame's position is returned to the app");

  // Start over: JW Player seeks through its API; plain videos rewind to 0 and keep playing.
  const jwCalls = [];
  const restartJw = { getState: () => "playing", seek: t => jwCalls.push("seek:" + t), play: () => jwCalls.push("play") };
  const restartFrame = player({ paused: false, currentTime: 300, duration: 1200, play() { return Promise.resolve(); }, pause() {} }, [], { top: parentPage, parent: parentPage, jwplayer: () => restartJw });
  restartFrame.listeners.message({ source: parentPage, data: { animeTvAction: "restart" } });
  assert.deepEqual([...jwCalls], ["seek:0", "play"], "Start over seeks JW Player to 0 and plays");
  assert.equal(progressReports.at(-1)?.animeTvState, "restarted", "Start over is reported");
  const plain = { paused: true, currentTime: 300, duration: 1200, play() { this.paused = false; return Promise.resolve(); }, pause() {} };
  player(plain).context.animeTvControl("restart");
  assert.equal(plain.currentTime, 0, "Start over rewinds a plain video to 0:00");
  assert.equal(plain.paused, false, "Start over keeps playing");
  const forwardedRestart = [];
  player(null, [{ postMessage: msg => forwardedRestart.push(msg) }]).context.animeTvControl("restart");
  assert.equal(forwardedRestart[0]?.animeTvAction, "restart", "The page forwards Start over to the player frame");
  // With the provider's own buttons hidden, Play goes through the player API, not a button click.
  let apiPlays = 0;
  const apiOnly = player(null, [], { jwplayer: () => ({ getState: () => "idle", play: () => { apiPlays++; } }) });
  apiOnly.context.animeTvControl("toggle");
  assert.equal(apiPlays, 1, "Play uses the JW API when no video element exists yet");

  // no-native-controls.js: the embed's own controller is styled away, switched off via the JW API and <video controls> removed.
  {
    const hideSource = fs.readFileSync(require('node:path').join(__dirname, '../app/src/main/assets/no-native-controls.js'), 'utf8');
    const appended = [];
    const ticks = [];
    let jwControls = true;
    const nativeVideo = { controls: true };
    const ctx = {
      document: {
        head: { appendChild: el => appended.push(el) },
        getElementById: id => appended.find(el => el.id === id) || null,
        createElement: () => ({}),
        addEventListener: () => {},
        getElementsByTagName: () => [nativeVideo],
      },
      setInterval: fn => ticks.push(fn),
      jwplayer: () => ({ getControls: () => jwControls, setControls: on => { jwControls = on; } }),
    };
    ctx.window = ctx;
    vm.runInNewContext(hideSource, ctx);
    assert.equal(appended.length, 1, 'Hiding style is injected at document start');
    assert.match(appended[0].textContent, /\.jw-controls/, 'JW Player controller is hidden');
    assert.match(appended[0].textContent, /\.jw-display[,{]/, 'JW big play / pause / buffering display is hidden');
    assert.doesNotMatch(appended[0].textContent, /jw-captions|jw-error/, 'Subtitles and error messages stay visible');
    ticks.forEach(fn => fn());
    assert.equal(jwControls, false, 'JW controls are switched off through its API');
    assert.equal(nativeVideo.controls, false, 'Native <video controls> are removed');
    jwControls = true;
    ticks.forEach(fn => fn());
    assert.equal(jwControls, false, 'Controls stay off if the player turns them back on');
    vm.runInNewContext(hideSource, ctx);
    assert.equal(appended.length, 1, 'Running twice in a frame adds nothing');
  }

  console.log('PASS: toggle, pause, seek bounds, slider seek, unknown duration, frame routing, source validation, playback rejection, autoplay frame targeting, JW start, playback report, autoplay cancel, timeline status, frame-only progress, start over, API play, native controller hidden');
})();
