// Shared by the TV's web pages: the top bar (the TV's clock and the page tabs), the TV's wallpaper as
// the page background, and a toast. A page calls TV.start("message") and can listen with TV.onHome.
var TV = (function () {
  var home = null, skew = 0, listeners = [];

  function rgba(c) {
    var n = parseInt(c.slice(1), 16);
    return "rgba(" + ((n >>> 16) & 255) + "," + ((n >>> 8) & 255) + "," + (n & 255) + "," + (((n >>> 24) & 255) / 255).toFixed(3) + ")";
  }

  // A gradient wallpaper as CSS, the way MainActivity paints it.
  function gradientCss(colors, width) {
    var g = colors.map(rgba), k = (width || 1920) / 1920;
    return "radial-gradient(circle " + 1250 * k + "px at 8% -12%, " + g[3] + ", transparent)," +
      "radial-gradient(circle " + 1000 * k + "px at 100% 110%, " + g[4] + ", transparent)," +
      "linear-gradient(" + g[0] + "," + g[1] + "," + g[2] + ")";
  }

  function wallpaperCss(h, width) {
    return h.picture ? "url('/wallpaper/now?n=" + encodeURIComponent(h.picture) + "')" : gradientCss(h.gradient, width);
  }

  // The TV's time, in its time zone, whatever this device's are.
  function now() {
    if (!home) return null;
    return new Date(Date.now() + skew + home.tzOffset * 60000);
  }
  function timeText(t) {
    var h = t.getUTCHours(), m = t.getUTCMinutes();
    if (!home.h24) h = h % 12 || 12;
    return h + ":" + (m < 10 ? "0" : "") + m;
  }
  function dateText(t) {
    var lang = home.locale || "en";
    if (/^en\b/.test(lang))
      return t.toLocaleDateString("en", { weekday: "long", timeZone: "UTC" }) + ", " + t.getUTCDate() + " " +
        t.toLocaleDateString("en", { month: "long", timeZone: "UTC" });
    return t.toLocaleDateString(lang, { weekday: "long", day: "numeric", month: "long", timeZone: "UTC" });
  }

  function tick() {
    var t = now();
    if (!t) return;
    var tt = document.querySelector(".top .t"), dd = document.querySelector(".top .d");
    if (tt) tt.textContent = timeText(t);
    if (dd) dd.textContent = dateText(t);
  }

  var lastBg = "";
  function load() {
    var asked = Date.now();
    return fetch("/home.json").then(function (r) { return r.json(); }).then(function (h) {
      skew = h.time - (asked + Date.now()) / 2;
      home = h;
      var bg = document.getElementById("bg"), css = wallpaperCss(h, window.innerWidth);
      if (bg && css !== lastBg) { lastBg = css; bg.style.backgroundImage = css; }
      tick();
      listeners.forEach(function (f) { f(h); });
      return h;
    }).catch(function () {});
  }

  function start(page) {
    document.body.insertAdjacentHTML("afterbegin",
      '<div id="bg"></div><div id="bg-shade"></div>' +
      '<header class="top"><div class="clock"><span class="t"></span><span class="d"></span></div>' +
      '<nav class="tabs" aria-label="Pages">' +
      '<a href="/"' + (page === "home" ? ' class="on" aria-current="page"' : "") + '>Home</a>' +
      '<a href="/message"' + (page === "message" ? ' class="on" aria-current="page"' : "") + '>Message</a>' +
      '<a href="/wallpapers"' + (page === "wallpapers" ? ' class="on" aria-current="page"' : "") + '>Wallpapers</a>' +
      '<a href="/files"' + (page === "files" ? ' class="on" aria-current="page"' : "") + '>Files</a>' +
      '<a href="/remote"' + (page === "remote" ? ' class="on" aria-current="page"' : "") + '>Remote</a>' +
      '<a href="/call"' + (page === "call" ? ' class="on" aria-current="page"' : "") + '>Call</a>' +
      '</nav></header>');
    var on = document.querySelector(".tabs a.on");
    if (on && on.scrollIntoView) on.scrollIntoView({ block: "nearest", inline: "center" });
    document.body.insertAdjacentHTML("beforeend", '<div id="toast" role="status" aria-live="polite"></div>');
    setInterval(tick, 1000);
    setInterval(load, 10000);
    return load();
  }

  var toastTimer;
  function toast(text) {
    var t = document.getElementById("toast");
    if (!t) return;
    t.textContent = text;
    t.classList.add("on");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.classList.remove("on"); }, 2600);
  }

  return {
    start: start, load: load, toast: toast, rgba: rgba, gradientCss: gradientCss, wallpaperCss: wallpaperCss,
    onHome: function (f) { listeners.push(f); if (home) f(home); },
    home: function () { return home; }, now: now, timeText: timeText, dateText: dateText
  };
})();
