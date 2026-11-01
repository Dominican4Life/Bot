// KMK (r102, Source Lab): the page-side half of "Learn this list" in Build by tapping. Injected by the app after
// picker.js on every page load. A list picked for a filter whose entries are buttons without links (a "Year: All ▾"
// dropdown of year buttons, a sort menu that changes the address by script) can't tell the Lab where each entry goes.
// The app (BuilderExtrasModel) learns it one entry at a time: it loads the list page, calls
//   window.__labLearn.press({ opener, entry: { path, text } })
// and waits for the address to change. press() makes sure nothing is armed in the picker (so the site sees the
// click), finds the entry (by `path`, a css selector, whose element has the entry's text; else by the exact text
// anywhere on the page), and when it isn't visible, opens its menu first: `opener` is the css path of the menu's
// button recorded when the list was picked; without one, the nearest preceding / enclosing element with
// aria-haspopup / aria-expanded or a dropdown / toggle / select class. The menu is opened by a click, then by
// hovering, then by a pointer press, 300 ms (+400 ms) apart, until the entry shows; then the entry is clicked.
// Idempotent: a second injection on the same window keeps the first. Logs go through LabPicker.log when the bridge
// is there ("learn: …"), else console.info.
(function () {
  if (window.__labLearn) return;

  function log(text) {
    var line = "learn: " + text;
    try {
      if (typeof LabPicker !== "undefined" && LabPicker && typeof LabPicker.log === "function") {
        LabPicker.log(line);
        return;
      }
    } catch (e) { /* falls through */ }
    try {
      // r102: the picker's own fallback carries this screen's token, so the app still hears the answer
      if (window.__labPicker && typeof window.__labPicker.log === "function") { window.__labPicker.log(line); return; }
    } catch (e3) { /* falls through */ }
    try { console.info(line); } catch (e2) { /* nothing */ }
  }

  function norm(s) { return String(s || "").replace(/\s+/g, " ").trim(); }

  function queryAll(selector) {
    if (!selector) return [];
    try { return Array.prototype.slice.call(document.querySelectorAll(selector)); } catch (e) { return []; }
  }

  function textOf(el) {
    var t = norm(el.textContent);
    if (!t && el.getAttribute) t = norm(el.getAttribute("aria-label") || el.getAttribute("title") || el.getAttribute("value") || "");
    return t;
  }

  // Shown on screen: a size, and no hidden / see-through ancestor (a menu that fades in counts once it is opaque)
  function visible(el) {
    if (!el || !el.isConnected) return false;
    if (el.tagName && el.tagName.toLowerCase() === "option") {
      var sel = el.closest("select");
      return !!sel && visible(sel);
    }
    var r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return false;
    for (var n = el; n && n.nodeType === 1; n = n.parentElement) {
      var cs = window.getComputedStyle(n);
      if (cs.display === "none" || cs.visibility === "hidden" || cs.visibility === "collapse") return false;
      if (parseFloat(cs.opacity) === 0) return false;
    }
    return true;
  }

  var ACTIVE = "a[href], button, option, label, summary, [role=option], [role=menuitem], [role=menuitemradio], " +
    "[role=radio], [role=tab], [role=button], [data-value], li";
  var CANDIDATES = ACTIVE + ", span, div, p, td";

  function own(el) {
    return !el.closest || !el.closest("#__lab-badge");
  }

  // The element of [pool] whose text is [text] (exact, else any case): the innermost one, lifted to the control
  // around it when that control says the same; visible ones first, then controls
  function pick(pool, text) {
    var want = norm(text);
    if (!want) return null;
    var lower = want.toLowerCase();
    var exact = [];
    var loose = [];
    for (var i = 0; i < pool.length; i++) {
      var el = pool[i];
      if (!own(el)) continue;
      var t = textOf(el);
      if (t === want) exact.push(el);
      else if (t.toLowerCase() === lower) loose.push(el);
    }
    var list = exact.length ? exact : loose;
    if (!list.length) return null;
    list = list.filter(function (el) {
      for (var k = 0; k < list.length; k++) if (list[k] !== el && el.contains(list[k])) return false;
      return true;
    });
    list = list.map(function (el) {
      var c = el.closest ? el.closest(ACTIVE) : null;
      return c && textOf(c).toLowerCase() === textOf(el).toLowerCase() ? c : el;
    });
    function score(el) {
      return (visible(el) ? 2 : 0) + (el.matches && el.matches(ACTIVE) ? 1 : 0);
    }
    list.sort(function (a, b) { return score(b) - score(a); });
    return list[0];
  }

  // By its path first; else by its text anywhere, leaving out the text of a menu's own button ("Year: <span>All")
  function findEntry(entry, opener) {
    var hit = pick(queryAll(entry.path), entry.text);
    if (hit) return hit;
    var buttons = queryAll(opener).concat(queryAll("[aria-haspopup], [aria-expanded]"));
    var pool = queryAll(CANDIDATES).filter(function (el) {
      for (var i = 0; i < buttons.length; i++) if (buttons[i] !== el && buttons[i].contains(el)) return false;
      return buttons.indexOf(el) < 0 || !el.hasAttribute("aria-expanded");
    });
    return pick(pool, entry.text);
  }

  var TOGGLE_CLASS = /(dropdown|toggle|select(?!ed)|picker|combo)/i;

  function sameKind(a, b) {
    return !!a && !!b && a.tagName === b.tagName && (a.getAttribute("class") || "") === (b.getAttribute("class") || "");
  }

  function toggleLike(el, entryEl) {
    if (!el || el.nodeType !== 1 || !own(el)) return false;
    if (entryEl && (el === entryEl || sameKind(el, entryEl) || el.contains(entryEl))) return false;
    if (el.hasAttribute("aria-haspopup") || el.hasAttribute("aria-expanded")) return true;
    var cls = String(el.getAttribute("class") || "");
    return TOGGLE_CLASS.test(cls) && /^(button|a|div|span|summary|label)$/i.test(el.tagName) && textOf(el).length <= 40;
  }

  // How many ancestors [a] and [b] share (the nearer an opener sits to the entry, the more)
  function shared(a, b) {
    var chain = [];
    for (var n = a; n; n = n.parentElement) chain.push(n);
    for (var m = b; m; m = m.parentElement) {
      var at = chain.indexOf(m);
      if (at >= 0) return chain.length - at;
    }
    return 0;
  }

  function findOpener(opener, entryEl) {
    var els = queryAll(opener).filter(own);
    if (els.length === 1) return els[0];
    if (els.length > 1) {
      if (entryEl) els.sort(function (a, b) { return shared(b, entryEl) - shared(a, entryEl); });
      return els[0];
    }
    if (!entryEl) return null;
    // An enclosing toggle (aria), or one just before the entry or one of its parents
    for (var pass = 0; pass < 2; pass++) {
      for (var n = entryEl; n && n.tagName && !/^(body|html)$/i.test(n.tagName); n = n.parentElement) {
        if (n !== entryEl && pass === 0 && (n.hasAttribute("aria-haspopup") || n.hasAttribute("aria-expanded"))) return n;
        for (var p = n.previousElementSibling, hop = 0; p && hop < 3; p = p.previousElementSibling, hop++) {
          var cands = [p].concat(queryAllIn(p, "[aria-haspopup], [aria-expanded], button, [class]"));
          for (var c = 0; c < cands.length; c++) {
            var el = cands[c];
            var aria = el.hasAttribute && (el.hasAttribute("aria-haspopup") || el.hasAttribute("aria-expanded"));
            if (pass === 0 ? aria && toggleLike(el, entryEl) : toggleLike(el, entryEl)) return el;
          }
        }
      }
    }
    return null;
  }

  function queryAllIn(root, selector) {
    try { return Array.prototype.slice.call(root.querySelectorAll(selector)); } catch (e) { return []; }
  }

  function fire(el, type, Kind) {
    try {
      var r = el.getBoundingClientRect();
      var init = { bubbles: true, cancelable: true, view: window, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
      var Ctor = Kind && window[Kind] ? window[Kind] : MouseEvent;
      el.dispatchEvent(new Ctor(type, init));
    } catch (e) { /* an old engine without the constructor */ }
  }

  function press(el) {
    var tag = el.tagName.toLowerCase();
    if (tag === "option") {
      var sel = el.closest("select");
      if (sel) {
        sel.value = el.value;
        sel.dispatchEvent(new Event("input", { bubbles: true }));
        sel.dispatchEvent(new Event("change", { bubbles: true }));
        return;
      }
    }
    try { el.scrollIntoView({ block: "nearest" }); } catch (e) { /* nothing */ }
    el.click();
  }

  // Ways to open a menu, tried in order until the entry shows
  var OPENERS = [
    function (op) { op.click(); },
    function (op) {
      fire(op, "pointerover", "PointerEvent");
      fire(op, "pointerenter", "PointerEvent");
      fire(op, "mouseover");
      fire(op, "mouseenter");
    },
    function (op) {
      fire(op, "pointerdown", "PointerEvent");
      fire(op, "mousedown");
      fire(op, "pointerup", "PointerEvent");
      fire(op, "mouseup");
    },
  ];

  // Nothing armed: the picker must let the site see these clicks
  function disarm() {
    var p = window.__labPicker;
    if (!p) return;
    try {
      if (typeof p.arm === "function") p.arm(null);
      else if (typeof p.setMode === "function" && p.mode === "pick") p.setMode("browse");
      // Still taking taps with nothing armed: a group pick ("List for a filter") is waiting; it would eat the clicks
      var root = document.documentElement;
      if (root && root.classList.contains("__lab-picking") && typeof p.groupMode === "function") p.groupMode(false);
      // r103: "Pick a menu on the site" waiting for its tap would eat them too
      if (root && root.classList.contains("__lab-picking") && typeof p.menuMode === "function") p.menuMode(false);
    } catch (e) { /* nothing */ }
  }

  var last = { state: "idle" };

  // r102 fix: the outcome goes back to the app ("learn: pressed #<id> …" / "learn: missing #<id> …"), so an entry
  // that wasn't found is marked missing there instead of "no change"
  var current = null;
  function finish(state, detail) {
    last = { state: state, detail: detail || "", at: Date.now(), id: current };
    log(state + (current !== null ? " #" + current : "") + (detail ? " " + detail : ""));
  }

  function run(req) {
    req = req || {};
    var entry = req.entry || {};
    current = typeof req.id === "number" || typeof req.id === "string" ? String(req.id) : null;
    var name = "\"" + norm(entry.text).slice(0, 40) + "\"";
    disarm();
    var el = findEntry(entry, req.opener);
    if (el && visible(el)) {
      finish("pressed", name);
      press(el);
      return "pressed";
    }
    var op = findOpener(req.opener, el);
    if (!op) {
      if (el) {
        finish("pressed", name + " (hidden, no menu button found)");
        press(el);
        return "pressed-hidden";
      }
      finish("missing", name + " is not on " + location.href);
      return "missing";
    }
    last = { state: "opening", detail: name, at: Date.now(), id: current };
    var mine = current;
    function attempt(k) {
      current = mine; // a later press() replaced it meanwhile: this one still reports under its own id
      OPENERS[k](op);
      function check(second) {
        current = mine;
        var e2 = findEntry(entry, req.opener);
        if (e2 && visible(e2)) {
          finish("pressed", name + " (menu opened, way " + (k + 1) + ")");
          press(e2);
          return;
        }
        if (!second) {
          setTimeout(function () { check(true); }, 400);
          return;
        }
        if (k + 1 < OPENERS.length) {
          attempt(k + 1);
        } else if (e2) {
          finish("pressed", name + " (menu didn't show it: pressed anyway)");
          press(e2);
        } else {
          finish("missing", name + " (not found after opening the menu)");
        }
      }
      setTimeout(function () { check(false); }, 300);
    }
    attempt(0);
    return "opening";
  }

  window.__labLearn = {
    version: 1,
    press: function (req) {
      try {
        return run(req);
      } catch (e) {
        finish("error", e && e.message ? e.message : String(e));
        return "error";
      }
    },
    // For checks: what press() would use, without pressing
    find: function (req) {
      req = req || {};
      var el = findEntry(req.entry || {}, req.opener);
      var op = el && visible(el) ? null : findOpener(req.opener, el);
      return { found: !!el, visible: !!el && visible(el), text: el ? textOf(el) : "", opener: op ? textOf(op) : "" };
    },
    last: function () { return last; },
  };
  log("ready on " + location.href);
})();
