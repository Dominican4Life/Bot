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
//
// KMK (r109, a directory's custom drop-downs "Sort: Most videos", "Height: Any height"): press() takes `label` too: the
// menu's button is the element reading "<label>: …" when `opener` finds nothing (and its own text is never the entry).
// items({ id, label, opener, current }) opens such a menu (the same 3 ways) and reads the entries that showed (text,
// href, value, current), then closes it; it answers "learn: items #<id> <json>" ({ ok, items, reason }).
//
// KMK (r110): press() takes `banner` too ("Learning the menus · pressing "Cup: D cup" (14 of 50)"): shown in a bar
// at the page's top (it never takes taps), and the pressed entry and the menu's button get an orange outline;
// banner(null) takes both away. count({ id, selector }) answers "learn: count #<id> <n>": how many of the directory's
// cards the page shows (0 = "Nothing found").
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
    return !el.closest || !el.closest("#__lab-badge, #__lab-learn-banner");
  }

  // r110: the bar at the page's top while the menus are learned, and the orange outline on what was pressed
  var BANNER_ID = "__lab-learn-banner";
  var MARK = "data-lab-learn-mark";
  function unmark() {
    var old = queryAll("[" + MARK + "]");
    for (var i = 0; i < old.length; i++) {
      try {
        old[i].style.outline = old[i].getAttribute(MARK + "-outline") || "";
        old[i].style.outlineOffset = old[i].getAttribute(MARK + "-offset") || "";
        old[i].removeAttribute(MARK);
        old[i].removeAttribute(MARK + "-outline");
        old[i].removeAttribute(MARK + "-offset");
      } catch (e) { /* nothing */ }
    }
  }
  function mark(el) {
    if (!el || !el.style || el.hasAttribute(MARK)) return;
    try {
      el.setAttribute(MARK + "-outline", el.style.outline || "");
      el.setAttribute(MARK + "-offset", el.style.outlineOffset || "");
      el.setAttribute(MARK, "1");
      el.style.outline = "2px solid #f59e0b";
      el.style.outlineOffset = "2px";
    } catch (e) { /* nothing */ }
  }
  // KMK (r110) fix: banner(null) (the run ended or Stop) cancels the presses still waiting for their menu to open:
  // check() reads it before outlining or pressing. A new press / begin() clears it; [gen] keeps the old ones cancelled
  var cancelled = false;
  var gen = 0;
  function banner(text) {
    var bar = document.getElementById(BANNER_ID);
    if (!text) {
      cancelled = true;
      gen++;
      if (bar && bar.parentNode) bar.parentNode.removeChild(bar);
      unmark();
      return;
    }
    if (!bar) {
      bar = document.createElement("div");
      bar.id = BANNER_ID;
      bar.setAttribute("style", "position:fixed;left:0;right:0;top:0;z-index:2147483646;pointer-events:none;" +
        "background:#f59e0b;color:#1f1300;font:600 13px/1.3 system-ui,sans-serif;padding:6px 12px;" +
        "box-shadow:0 2px 6px rgba(0,0,0,.3);white-space:nowrap;overflow:hidden;text-overflow:ellipsis");
      (document.body || document.documentElement).appendChild(bar);
    }
    bar.textContent = String(text);
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

  // r109: the menu's button by its label: the innermost element reading "<label>: …", lifted to the control around it
  // (a button, a link, [role=button], [aria-haspopup] / [aria-expanded], or one with a click handler's class); visible
  // ones first
  function openerByLabel(label) {
    var want = norm(label).toLowerCase();
    if (!want) return null;
    var found = queryAll("button, a, summary, label, span, div, p, strong, [role=button], [aria-haspopup], [aria-expanded]").filter(function (el) {
      if (!own(el)) return false;
      var t = norm(el.textContent).replace(/[\s▾▴▼▲⌄⌃∨∧˅˄›»⏷⏶▸▹►▶]+$/, "").toLowerCase();
      return t.length <= 64 && t.indexOf(want + ":") === 0;
    });
    found = found.filter(function (el) {
      for (var k = 0; k < found.length; k++) if (found[k] !== el && el.contains(found[k])) return false;
      return true;
    }).map(function (el) {
      var c = el.closest("button, a, summary, [role=button], [role=combobox], [aria-haspopup], [aria-expanded]");
      if (c) return c;
      for (var n = el, up = 0; n && up < 3; n = n.parentElement, up++) if (toggleLike(n, null)) return n;
      // a text that isn't a control (a heading reading "Name: …") is no menu's button
      return /^(button|summary|label)$/i.test(el.tagName) ? el : null;
    }).filter(function (el) { return !!el; });
    found.sort(function (a, b) { return (visible(b) ? 1 : 0) - (visible(a) ? 1 : 0); });
    return found[0] || null;
  }

  // By its path first; else by its text anywhere, leaving out the text of a menu's own button ("Year: <span>All")
  function findEntry(entry, opener, label) {
    var hit = pick(queryAll(entry.path), entry.text);
    if (hit) return hit;
    var byLabel = label ? openerByLabel(label) : null;
    var buttons = queryAll(opener).concat(queryAll("[aria-haspopup], [aria-expanded]")).concat(byLabel ? [byLabel] : []);
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

  function readsLabel(el, label) {
    return norm(el.textContent).toLowerCase().indexOf(norm(label).toLowerCase() + ":") === 0;
  }

  function findOpener(opener, entryEl, label) {
    var els = queryAll(opener).filter(own);
    // r109: a recorded path that finds nothing, or an element not reading "<label>: …" (the page changed since it was
    // read), loses to the button reading the label
    var labelled = label ? openerByLabel(label) : null;
    if (labelled && !els.some(function (e) { return e === labelled || e.contains(labelled) || labelled.contains(e) || readsLabel(e, label); })) {
      return labelled;
    }
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
    cancelled = false; // KMK (r110) fix: a new press
    var myGen = gen;
    function gone() { return cancelled || myGen !== gen; }
    var showing = !!req.banner;
    if (showing) { unmark(); banner(req.banner); }
    var label = req.label ? String(req.label) : "";
    var el = findEntry(entry, req.opener, label);
    if (el && visible(el)) {
      finish("pressed", name);
      if (showing) mark(el);
      press(el);
      return "pressed";
    }
    var op = findOpener(req.opener, el, label);
    if (op && showing) mark(op);
    if (!op) {
      if (el) {
        finish("pressed", name + " (hidden, no menu button found)");
        if (showing) mark(el);
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
      if (gone()) { finish("stopped", name + " (learning stopped)"); return; } // KMK (r110) fix
      OPENERS[k](op);
      function check(second) {
        current = mine;
        if (gone()) { finish("stopped", name + " (learning stopped)"); return; } // KMK (r110) fix
        var e2 = findEntry(entry, req.opener, label);
        if (e2 && visible(e2)) {
          finish("pressed", name + " (menu opened, way " + (k + 1) + ")");
          if (showing) mark(e2);
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

  // r109: the entries shown on screen now (innermost controls / list items), never inside [op]
  var ITEM_SEL = "a[href], button, li, [role=option], [role=menuitem], [role=menuitemradio], [role=radio], [data-value], label";
  function shownEntries(op) {
    var all = queryAll(ITEM_SEL).filter(function (el) { return own(el) && (!op || (!op.contains(el) && !el.contains(op))) && visible(el); });
    return all.filter(function (el) {
      for (var k = 0; k < all.length; k++) if (all[k] !== el && el.contains(all[k])) return false;
      return true;
    });
  }
  var ITEM_CURRENT = /(^|\s)(is-|o-)?(active|selected|current|checked|chosen)(\s|$)/i;
  function entryInfo(el, current) {
    var t = textOf(el);
    var out = { text: t.slice(0, 80) };
    if (el.tagName.toLowerCase() === "a") {
      var raw = el.getAttribute("href") || "";
      if (raw && !/^(javascript:|#)/i.test(raw)) { try { out.href = new URL(raw, location.href).href; } catch (e) { /* nothing */ } }
    }
    var v = el.getAttribute("data-value");
    if (v === null) v = el.getAttribute("value");
    if (v !== null) out.value = String(v);
    var li = el.parentElement && el.parentElement.tagName.toLowerCase() === "li" ? el.parentElement : null;
    var boxes = li ? [el, li] : [el];
    for (var i = 0; i < boxes.length; i++) {
      var b = boxes[i];
      var cur = b.getAttribute("aria-current");
      if (ITEM_CURRENT.test(String(b.getAttribute("class") || "")) || b.getAttribute("aria-selected") === "true" ||
        b.getAttribute("aria-checked") === "true" || (cur !== null && cur !== "false")) out.current = true;
    }
    if (current && t.toLowerCase() === norm(current).toLowerCase()) out.current = true;
    return out;
  }

  // r109: opens the menu of [req.label] / [req.opener], reads the entries that showed, closes it
  function items(req) {
    req = req || {};
    var id = String(req.id === undefined ? "" : req.id);
    function answer(o) { log("items #" + id + " " + JSON.stringify(o)); }
    disarm();
    var op = findOpener(req.opener, null, req.label ? String(req.label) : "");
    if (!op) { answer({ ok: false, items: [], reason: "the menu's button (" + (req.label || req.opener || "?") + ") is not on " + location.href }); return "missing"; }
    var before = shownEntries(op);
    function fresh() { return shownEntries(op).filter(function (el) { return before.indexOf(el) < 0; }); }
    function close() {
      try { document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true })); } catch (e) { /* nothing */ }
      setTimeout(function () { if (fresh().length >= 2) { try { op.click(); } catch (e2) { /* nothing */ } } }, 150);
    }
    function attempt(k) {
      OPENERS[k](op);
      function check(second) {
        var now = fresh();
        if (now.length >= 2) {
          var list = now.slice(0, 80).map(function (el) { return entryInfo(el, req.current); }).filter(function (x) { return !!x.text; });
          answer({ ok: true, items: list.slice(0, 60), way: k + 1 });
          close();
          return;
        }
        if (!second) { setTimeout(function () { check(true); }, 400); return; }
        if (k + 1 < OPENERS.length) attempt(k + 1);
        else answer({ ok: false, items: [], reason: "no entries showed after pressing " + textOf(op).slice(0, 40) });
      }
      setTimeout(function () { check(false); }, 300);
    }
    attempt(0);
    return "opening";
  }

  window.__labLearn = {
    version: 3, // r110: banner, count
    // r109: a custom drop-down's entries, opened on the page
    items: function (req) {
      try {
        return items(req);
      } catch (e) {
        log("items #" + String((req && req.id) || "") + " " + JSON.stringify({ ok: false, items: [], reason: e && e.message ? e.message : String(e) }));
        return "error";
      }
    },
    press: function (req) {
      try {
        return run(req);
      } catch (e) {
        finish("error", e && e.message ? e.message : String(e));
        return "error";
      }
    },
    // r110: the learning bar (null = gone, outlines too) and the directory's cards counted
    banner: function (text) {
      try { banner(text); } catch (e) { /* nothing */ }
    },
    // KMK (r110) fix: a new learning run starts: presses aren't cancelled any more
    begin: function () { cancelled = false; },
    count: function (req) {
      req = req || {};
      var id = String(req.id === undefined ? "" : req.id);
      var n = -1;
      try {
        // a selector the page can't read is "not known", never "nothing found"
        if (req.selector) n = Array.prototype.slice.call(document.querySelectorAll(String(req.selector))).filter(own).length;
      } catch (e) { n = -1; }
      log("count #" + id + " " + n);
      return n;
    },
    // For checks: what press() would use, without pressing
    find: function (req) {
      req = req || {};
      var el = findEntry(req.entry || {}, req.opener, req.label);
      var op = el && visible(el) ? null : findOpener(req.opener, el, req.label);
      return { found: !!el, visible: !!el && visible(el), text: el ? textOf(el) : "", opener: op ? textOf(op) : "" };
    },
    last: function () { return last; },
  };
  log("ready on " + location.href);
})();
