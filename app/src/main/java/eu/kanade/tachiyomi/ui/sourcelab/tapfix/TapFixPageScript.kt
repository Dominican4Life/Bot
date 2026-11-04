package eu.kanade.tachiyomi.ui.sourcelab.tapfix

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * KMK (r118): the script Tap to fix puts on the site's page. Its own (the builder's picker.js is tied to the builder's
 * model and labels), with the builder's lessons kept: listeners on `window` in the capture phase with
 * `passive: false`; in Tap mode a click never reaches the page (no navigation, no menu), `touchstart` is prevented
 * only on controls (picked on `touchend`) so scrolling still works; `touchmove` is never prevented; invisible
 * overlays are skipped; outlines are inline `!important` styles put back as they were.
 *
 * A tap highlights the element and works out a short CSS selector for it, the way PickHealer's candidates do
 * (stable classes, attributes, the parent, the nearest id / class anchor; the cheapest that finds the same set wins),
 * inside the nearest ancestor matching the Item selector when the place is read inside each Item. The page reports
 * `{type: "ready" | "measure" | "pick" | "log", …}` through `TapFixBridge.report(json)`, or
 * `console.log("TAPFIX:<token>:" + json)` when the bridge isn't there.
 *
 * Bot checks: [botCheck] only *reads* the page (title, the check's own elements) to say so; nothing here clicks,
 * solves or hides one, and Tap mode is switched off on such a page by the app.
 *
 * No `$` anywhere in [SCRIPT] (a Kotlin raw string would take it as a template).
 */
internal object TapFixPageScript {

    const val BRIDGE = "TapFixBridge"
    const val CONSOLE_PREFIX = "TAPFIX:"

    /** The script with this screen's console token, in one evaluation (the script takes the token off `window`) */
    fun install(token: String): String = "window.__tapFixToken = ${JsonPrimitive(token)};\n$SCRIPT"

    fun mode(tap: Boolean): String = "window.__tapFix && window.__tapFix.setMode(${JsonPrimitive(if (tap) "tap" else "browse")})"

    /** What the page needs for [place]: the old selector to measure, the Item it is read in, what a tap should be */
    fun context(place: TapFixScanner.Place, item: String?, oldAlt: String?, itemAlt: String?): String = buildJsonObject {
        put("id", place.id)
        put("label", place.label)
        put("kind", place.kind.name.lowercase())
        put("single", place.single)
        put("old", place.selector)
        if (oldAlt != null) put("oldAlt", oldAlt)
        if (item != null) put("item", item)
        if (itemAlt != null) put("itemAlt", itemAlt)
    }.toString()

    /** Sets the context (and measures the old selector) only when the page doesn't have it yet: a page load */
    fun ensure(contextJson: String): String = "window.__tapFix && window.__tapFix.ensure($contextJson)"

    /** Sets the context now (another place was picked): the old pick is cleared */
    fun setContext(contextJson: String): String = "window.__tapFix && window.__tapFix.setContext($contextJson)"

    const val CLEAR = "window.__tapFix && window.__tapFix.clear()"
    const val UP = "window.__tapFix && window.__tapFix.up()"
    const val DOWN = "window.__tapFix && window.__tapFix.down()"
    const val HELLO = "window.__tapFix && window.__tapFix.hello()"

    val SCRIPT: String = """
(function () {
  var token = window.__tapFixToken || '';
  try { delete window.__tapFixToken; } catch (e) { window.__tapFixToken = undefined; }
  if (window.__tapFix) { window.__tapFix.hello(); return; }

  var BADGE = '__tapfix_badge';
  var INTERACTIVE = 'button, select, option, textarea, input, label, summary, [role=button], [role=option], [role=menuitem], [role=tab], [aria-haspopup]';
  var STATE = ['active', 'selected', 'current', 'show', 'shown', 'hidden', 'hide', 'open', 'hover', 'focus', 'visible', 'lazy',
    'lazyload', 'lazyloaded', 'lazyloading', 'loaded', 'loading', 'fade', 'in', 'odd', 'even', 'first', 'last'];
  var PLAIN = ['src', 'href', 'alt', 'title', 'width', 'height', 'loading', 'decoding', 'srcset', 'sizes', 'target', 'rel',
    'type', 'tabindex', 'draggable', 'class', 'id', 'style'];
  var VALUE_ATTRS = ['itemprop', 'rel', 'property', 'role', 'data-type', 'data-role'];
  var NEVER = ['html', 'head', 'body', 'script', 'style', 'meta', 'link', 'title', 'template', 'br', 'hr'];
  var PICTURE_ATTRS = ['data-src', 'data-lazy-src', 'data-original', 'src', 'srcset', 'data-srcset'];
  var GENERATED = /^(css|sc|jsx|svelte|emotion|chakra|styled|tw)-/i;

  var mode = 'browse';
  var ctx = { id: null, item: null, kind: 'text', single: false, label: '' };
  var current = null;
  var painted = [];
  var lastPickAt = 0;
  var touchTarget = null, touchX = 0, touchY = 0, moved = false;

  function send(type, obj) {
    obj = obj || {};
    obj.type = type;
    var json;
    try { json = JSON.stringify(obj); } catch (e) { return; }
    try {
      var b = window.TapFixBridge;
      if (b && typeof b.report === 'function') { b.report(json); return; }
    } catch (e) {}
    console.log('TAPFIX:' + token + ':' + json);
  }
  function log(text) { send('log', { text: String(text) }); }

  // ---------- names ----------
  function nameOk(s) {
    if (!s) return false;
    var m = /^-?[A-Za-z_][A-Za-z0-9_-]*/.exec(s);
    return !!m && m[0].length === s.length;
  }
  function switches(s) {
    var n = 0, prev = 0;
    for (var i = 0; i < s.length; i++) {
      var ch = s.charAt(i);
      var kind = /[0-9]/.test(ch) ? 1 : (/[A-Za-z]/.test(ch) ? 2 : 0);
      if (kind && prev && kind !== prev) n++;
      if (kind) prev = kind;
    }
    return n;
  }
  function digits(s) { var m = s.match(/[0-9]/g); return m ? m.length : 0; }
  function stableClass(c) {
    return nameOk(c) && !GENERATED.test(c) && STATE.indexOf(c.toLowerCase()) < 0 && switches(c) < 3 && digits(c) < 4;
  }
  function stableId(id) { return !!id && nameOk(id) && switches(id) < 3 && digits(id) < 3; }
  function tagOf(el) { return el && el.tagName ? el.tagName.toLowerCase() : ''; }
  function classesOf(el) {
    var out = [];
    if (!el || !el.classList) return out;
    for (var i = 0; i < el.classList.length; i++) {
      var c = el.classList[i];
      if (stableClass(c) && out.indexOf(c) < 0) out.push(c);
    }
    return out;
  }
  function attrNames(el) {
    var out = [];
    if (!el || !el.attributes) return out;
    for (var i = 0; i < el.attributes.length; i++) {
      var a = el.attributes[i].name.toLowerCase();
      if (!nameOk(a) || PLAIN.indexOf(a) >= 0 || a.indexOf('on') === 0 || a.indexOf('data-v-') === 0 || a.indexOf('_ng') === 0) continue;
      if (out.indexOf(a) < 0) out.push(a);
    }
    return out.sort().slice(0, 16);
  }
  function indexOfType(el) {
    var i = 0, n = el.previousElementSibling, t = tagOf(el);
    while (n) { if (tagOf(n) === t) i++; n = n.previousElementSibling; }
    return i;
  }

  // ---------- queries ----------
  function qsa(sel, root) { try { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); } catch (e) { return null; } }
  function qs(sel, root) { try { return (root || document).querySelector(sel); } catch (e) { return undefined; } }
  function matches(el, sel) { try { return !!el.matches && el.matches(sel); } catch (e) { return false; } }

  // ---------- what was tapped ----------
  function visibleText(el) { return ((el && (el.innerText || el.textContent)) || '').replace(/\s+/g, ' ').trim(); }
  function ownText(el) {
    for (var i = 0; i < el.childNodes.length; i++) {
      var n = el.childNodes[i];
      if (n.nodeType === 3 && n.nodeValue.trim()) return true;
    }
    return false;
  }
  function hasPicture(el) {
    var t = tagOf(el);
    return t === 'img' || t === 'picture' || t === 'video' || t === 'svg' || t === 'canvas' || t === 'iframe' ||
      !!(el.querySelector && el.querySelector('img, picture, video, iframe, canvas'));
  }
  function opacity(el) {
    var o = 1, n = el;
    while (n && n.nodeType === 1) {
      var v = parseFloat(getComputedStyle(n).opacity);
      if (!isNaN(v)) o *= v;
      n = n.parentElement;
    }
    return o;
  }
  function skip(el) {
    var st = getComputedStyle(el);
    if (st.visibility === 'hidden') return 'hidden';
    if (opacity(el) < 0.05) return 'transparent';
    var r = el.getBoundingClientRect();
    var area = Math.max(1, window.innerWidth * window.innerHeight);
    var t = tagOf(el);
    if ((t === 'a' || t === 'div') && (r.width * r.height) / area >= 0.5 && !ownText(el) && !hasPicture(el)) return 'covers the page';
    if ((st.position === 'fixed' || st.position === 'absolute') && !visibleText(el) && !hasPicture(el)) return 'empty overlay';
    return null;
  }
  function resolveAt(x, y, fallback) {
    var list = document.elementsFromPoint ? document.elementsFromPoint(x, y) : [];
    var kept = [], skipped = [];
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (el === document.documentElement || el === document.body || el.id === BADGE) continue;
      var why = skip(el);
      if (why) { skipped.push(tagOf(el) + ' (' + why + ')'); continue; }
      kept.push(el);
    }
    if (skipped.length) log('tap skipped ' + skipped.slice(0, 4).join(', '));
    for (var j = 0; j < kept.length; j++) if (visibleText(kept[j]) || hasPicture(kept[j])) return kept[j];
    return kept[0] || fallback || null;
  }
  function depthTo(el, anc) { var d = 0, n = el; while (n && n !== anc) { n = n.parentElement; d++; } return n ? d : 99; }
  /** A tap made into what the place reads: the link around a text, the one picture in a wrapper, the frame */
  function adjust(el) {
    if (!el) return el;
    var t = tagOf(el);
    if (ctx.kind === 'link') {
      var a = el.closest ? el.closest('a') : null;
      if (a && depthTo(el, a) <= 4) return a;
    } else if (ctx.kind === 'picture') {
      if (t === 'picture' || t === 'source') { var pi = (el.closest('picture') || el).querySelector('img'); if (pi) return pi; }
      if (t !== 'img') { var imgs = el.querySelectorAll ? el.querySelectorAll('img') : []; if (imgs.length === 1) return imgs[0]; }
    } else if (ctx.kind === 'frame') {
      if (['iframe', 'video', 'source', 'embed', 'object'].indexOf(t) < 0) {
        var f = el.querySelector ? el.querySelector('iframe, video, embed, object') : null;
        if (f) return f;
      }
    } else if (ctx.kind === 'item') {
      // the card: the nearest ancestor-or-self that repeats among its siblings and holds a link
      var n = el;
      for (var k = 0; k < 8 && n && n !== document.body; k++) {
        var p = n.parentElement;
        if (p) {
          var same = 0;
          for (var c = 0; c < p.children.length; c++) if (shape(p.children[c]) === shape(n)) same++;
          if (same >= 2 && (tagOf(n) === 'a' || (n.querySelector && n.querySelector('a[href]')))) return n;
        }
        n = p;
      }
    }
    return el;
  }

  // ---------- look-alikes ----------
  function shape(el) { return tagOf(el) + '|' + classesOf(el).slice().sort().join('.'); }
  function sameShape(a, b) {
    return shape(a) === shape(b) && !!a.parentElement && !!b.parentElement && shape(a.parentElement) === shape(b.parentElement);
  }
  function docLike(el) {
    var all = qsa(tagOf(el)) || [el];
    return all.filter(function (e) { return e === el || sameShape(e, el); });
  }
  function cardOf(el, item) {
    var n = el.parentElement;
    while (n && n !== document.documentElement) { if (matches(n, item)) return n; n = n.parentElement; }
    return null;
  }
  function chain(el, card) { var out = [], n = el; while (n && n !== card) { out.push(shape(n)); n = n.parentElement; } return out.join('<'); }
  function targetIn(card, el, ch) {
    var list = qsa(tagOf(el), card) || [];
    for (var i = 0; i < list.length; i++) if (chain(list[i], card) === ch) return list[i];
    return null;
  }

  // ---------- selectors (PickHealer's candidates and costs) ----------
  function anchor(el) {
    var n = el.parentElement, level = 0;
    while (n && level < 6) {
      var t = tagOf(n);
      if (t === 'body' || t === 'html') return null;
      if (stableId(n.id)) return '#' + n.id;
      var c = classesOf(n)[0];
      if (c) return '.' + c;
      n = n.parentElement; level++;
    }
    return null;
  }
  function candidates(el, relative) {
    var out = [];
    function add(s) { if (s && out.indexOf(s) < 0) out.push(s); }
    var t = tagOf(el), tagOk = nameOk(t), tt = tagOk ? t : '';
    var classes = classesOf(el).slice(0, 6);
    var attrs = attrNames(el).slice(0, 6);
    if (!relative && stableId(el.id)) { add('#' + el.id); if (tagOk) add(t + '#' + el.id); }
    classes.forEach(function (c) { add('.' + c); if (tagOk) add(t + '.' + c); });
    for (var i = 0; i < classes.length; i++) {
      for (var j = i + 1; j < classes.length; j++) {
        add('.' + classes[i] + '.' + classes[j]);
        if (tagOk) add(t + '.' + classes[i] + '.' + classes[j]);
      }
    }
    if (tagOk) add(t);
    VALUE_ATTRS.forEach(function (a) {
      var v = el.getAttribute && el.getAttribute(a);
      if (v && v.length <= 40 && /^[A-Za-z0-9 _:.\/-]+/.test(v) && /^[A-Za-z0-9 _:.\/-]*/.exec(v)[0].length === v.length && switches(v) < 3) {
        add(tt + '[' + a + "='" + v + "']");
      }
    });
    attrs.forEach(function (a) {
      if (tagOk) add(t + '[' + a + ']');
      add('[' + a + ']');
      if (classes[0]) add(tt + '.' + classes[0] + '[' + a + ']');
    });
    var p = el.parentElement;
    if (p && p !== document.body && p !== document.documentElement) {
      var pt = tagOf(p), ptOk = nameOk(pt);
      var pc = classesOf(p).slice(0, 3);
      var parentForms = [];
      pc.forEach(function (c) { parentForms.push('.' + c); if (ptOk) parentForms.push(pt + '.' + c); });
      if (ptOk && NEVER.indexOf(pt) < 0) parentForms.push(pt);
      var selfForms = [];
      if (tagOk) selfForms.push(t);
      classes.slice(0, 2).forEach(function (c) { selfForms.push('.' + c); });
      if (tagOk && classes[0]) selfForms.push(t + '.' + classes[0]);
      parentForms.forEach(function (pf) { selfForms.forEach(function (sf) { add(pf + ' > ' + sf); add(pf + ' ' + sf); }); });
      if (tagOk) {
        var idx = indexOfType(el) + 1;
        parentForms.forEach(function (pf) { add(pf + ' > ' + t + ':nth-of-type(' + idx + ')'); });
        if (relative) add(t + ':nth-of-type(' + idx + ')');
      }
    }
    if (!relative) {
      var a = anchor(el);
      if (a) {
        if (tagOk) add(a + ' ' + t);
        classes.slice(0, 2).forEach(function (c) { add(a + ' .' + c); if (tagOk) add(a + ' ' + t + '.' + c); });
      }
    }
    return out;
  }
  function baseCost(sel, relative) {
    var plain = sel.indexOf('.') < 0 && sel.indexOf('[') < 0 && sel.indexOf('#') < 0;
    return sel.length + (plain ? (relative ? 8 : 16) : 0) + (sel.charAt(0) === '[' ? 8 : 0) +
      3 * (sel.split(' ').length - 1) + (sel.indexOf(':nth-of-type') >= 0 ? 40 : 0);
  }
  function f1(p, r) { return p + r === 0 ? 0 : 2 * p * r / (p + r); }
  function cost(sel, p, r, relative) {
    var base = baseCost(sel, relative);
    if (p >= 0.9 && r >= 0.9) return base + (2 - p - r) * 20;
    if (p >= 0.75 && r >= 0.75) return base + 60 + (1 - f1(p, r)) * 100;
    return null;
  }
  function rateDoc(sel, el, likeSet, likeCount, single) {
    var m = qsa(sel);
    if (!m || !m.length || m.indexOf(el) < 0) return null;
    if (single) {
      if (m[0] !== el) return null;
      return { cost: baseCost(sel, false) + (m.length > 1 ? 6 + Math.min(m.length, 50) : 0), count: m.length, list: m };
    }
    var inter = 0;
    m.forEach(function (e) { if (likeSet.has(e)) inter++; });
    var c = cost(sel, inter / m.length, inter / likeCount, false);
    return c === null ? null : { cost: c, count: m.length, list: m };
  }
  function rateRel(sel, el, cards, card, targets) {
    if (qs(sel, card) !== el) return null;
    var hits = 0, found = 0, withTarget = 0, firsts = [];
    for (var i = 0; i < cards.length; i++) {
      var f = qs(sel, cards[i]);
      if (f === undefined) return null;
      if (targets[i]) withTarget++;
      if (f) { found++; firsts.push(f); if (f === targets[i]) hits++; }
    }
    if (!found || !withTarget) return null;
    var c = cost(sel, hits / found, hits / withTarget, true);
    return c === null ? null : { cost: c, count: found, list: firsts };
  }
  /** The plain path down to [el] from [stop] (a card) or the page: the last resort, marked weak */
  function pathTo(el, stop) {
    var segs = [], n = el;
    while (n && n !== stop && n !== document.body && n !== document.documentElement && segs.length < 8) {
      if (stableId(n.id) && n !== el) { segs.unshift('#' + n.id); break; }
      var t = tagOf(n), cs = classesOf(n).slice(0, 2);
      var seg = t + (cs.length ? '.' + cs.join('.') : '');
      if (!cs.length && n.parentElement) {
        var same = 0;
        for (var i = 0; i < n.parentElement.children.length; i++) if (tagOf(n.parentElement.children[i]) === t) same++;
        if (same > 1) seg += ':nth-of-type(' + (indexOfType(n) + 1) + ')';
      }
      segs.unshift(seg);
      var s = segs.join(' > ');
      if (stop ? qs(s, stop) === el : (qsa(s) || []).indexOf(el) === 0) break;
      n = n.parentElement;
    }
    if (!stop && n === document.body) segs.unshift('body');
    return segs.join(' > ');
  }
  function describe(el) { var c = classesOf(el)[0]; return tagOf(el) + (c ? '.' + c : (el.id ? '#' + el.id : '')); }
  function snippet(el) {
    var text = visibleText(el);
    if (!text) text = (el.getAttribute && (el.getAttribute('alt') || el.getAttribute('title') || el.getAttribute('aria-label'))) || '';
    if (!text && tagOf(el) !== 'img') { var img = el.querySelector && el.querySelector('img[alt]'); if (img) text = img.getAttribute('alt') || ''; }
    return text.slice(0, 60);
  }
  function sampleOf(e) {
    if (!e) return '';
    var t = tagOf(e);
    if (ctx.kind === 'picture' || t === 'img') {
      var img = t === 'img' ? e : e.querySelector('img');
      if (img) {
        for (var i = 0; i < PICTURE_ATTRS.length; i++) {
          var v = img.getAttribute(PICTURE_ATTRS[i]);
          if (v && v.trim()) return v.trim().split(' ')[0].slice(0, 160);
        }
      }
    }
    if (ctx.kind === 'frame') {
      var f = ['iframe', 'video', 'source', 'embed'].indexOf(t) >= 0 ? e : e.querySelector('iframe, video, source, embed');
      var s = f && (f.getAttribute('src') || f.getAttribute('data-src'));
      if (s) return s.slice(0, 160);
    }
    var text = visibleText(e);
    if (t === 'meta') text = e.getAttribute('content') || '';
    if (ctx.kind === 'link' || !text) {
      var a = t === 'a' ? e : ((e.closest && e.closest('a')) || (e.querySelector && e.querySelector('a[href]')));
      var h = a && a.getAttribute('href');
      if (h) return (text ? text.slice(0, 50) + ' · ' : '') + h.slice(0, 120);
    }
    return text.slice(0, 80);
  }
  function samples(list) {
    var out = [];
    for (var i = 0; i < list.length && out.length < 3; i++) { var s = sampleOf(list[i]); if (s) out.push(s); }
    return out;
  }

  /** The Item selector the place is read in, as the browser can run it ([itemAlt]: Jsoup-only parts left out) */
  function itemCards() {
    if (!ctx.item || ctx.kind === 'item') return { cards: null, item: null };
    var cards = qsa(ctx.item);
    if (cards) return { cards: cards, item: ctx.item };
    if (ctx.itemAlt) { cards = qsa(ctx.itemAlt); if (cards) return { cards: cards, item: ctx.itemAlt }; }
    return { cards: null, item: null, error: true };
  }

  function compute(el) {
    var ic = itemCards();
    var rel = !!ic.cards, card = null, note = null;
    if (ic.error) note = 'The Item selector (' + ctx.item + ") can't run in the browser: worked out on the whole page";
    if (rel) {
      card = cardOf(el, ic.item);
      if (!card) {
        note = 'Not inside an Item (' + ic.item + ' ×' + ic.cards.length + '): worked out on the whole page';
        rel = false;
      }
    }
    var best = null, bestSel = null, weak = false;
    if (rel) {
      var ch = chain(el, card);
      var targets = ic.cards.map(function (c) { return targetIn(c, el, ch); });
      candidates(el, true).forEach(function (s) {
        var r = rateRel(s, el, ic.cards, card, targets);
        if (r && (!best || r.cost < best.cost)) { best = r; bestSel = s; }
      });
      if (!best) {
        bestSel = pathTo(el, card); weak = true;
        var firsts = [];
        ic.cards.forEach(function (c) { var f = qs(bestSel, c); if (f) firsts.push(f); });
        best = { count: firsts.length, list: firsts };
      }
    } else {
      var single = ctx.single;
      var like = single ? [el] : docLike(el);
      var likeSet = new Set(like);
      candidates(el, false).forEach(function (s) {
        var r = rateDoc(s, el, likeSet, like.length, single);
        if (r && (!best || r.cost < best.cost)) { best = r; bestSel = s; }
      });
      if (!best) {
        bestSel = pathTo(el, null); weak = true;
        var m = qsa(bestSel) || [el];
        best = { count: m.length, list: m };
      }
    }
    return {
      selector: bestSel, count: best.count, cards: rel ? ic.cards.length : -1, relative: rel, list: best.list,
      samples: samples(best.list), weak: weak, note: note, tag: describe(el), snippet: snippet(el),
    };
  }

  function measure(sel, alt) {
    var r = measureOne(sel);
    if (r.ok || !alt) return r;
    var r2 = measureOne(alt);
    if (r2.ok) { r2.approx = true; return r2; }
    return r;
  }
  function measureOne(sel) {
    if (!sel || !sel.trim()) return { ok: true, empty: true, count: 0, cards: -1, samples: [] };
    if (qsa(sel) === null) return { ok: false, error: "can't run in the browser", count: 0, cards: -1, samples: [] };
    var ic = itemCards();
    if (ic.cards) {
      var firsts = [];
      ic.cards.forEach(function (c) { var f = qs(sel, c); if (f) firsts.push(f); });
      return { ok: true, count: firsts.length, cards: ic.cards.length, samples: samples(firsts) };
    }
    var m = qsa(sel);
    return { ok: true, count: m.length, cards: -1, samples: samples(m), itemError: !!ic.error };
  }

  // ---------- outlines ----------
  function keep(s, name) { return [s.getPropertyValue(name), s.getPropertyPriority(name)]; }
  function put(s, name, saved) { if (saved[0]) s.setProperty(name, saved[0], saved[1]); else s.removeProperty(name); }
  function paintOne(e, picked) {
    var s = e.style;
    if (!s) return;
    painted.push({ e: e, o: keep(s, 'outline'), f: keep(s, 'outline-offset'), b: keep(s, 'background-color') });
    s.setProperty('outline', picked ? '3px solid #2196F3' : '2px dashed #FB8C00', 'important');
    s.setProperty('outline-offset', picked ? '-3px' : '-2px', 'important');
    if (picked) s.setProperty('background-color', 'rgba(33,150,243,0.18)', 'important');
  }
  function unpaint() {
    painted.forEach(function (p) {
      var s = p.e.style;
      put(s, 'outline', p.o); put(s, 'outline-offset', p.f); put(s, 'background-color', p.b);
    });
    painted = [];
  }
  function paint(el, list) {
    unpaint();
    var n = 0;
    for (var i = 0; i < list.length && n < 150; i++) if (list[i] !== el) { paintOne(list[i], false); n++; }
    paintOne(el, true);
  }
  function badge(text) {
    var b = document.getElementById(BADGE);
    if (!text) { if (b) b.remove(); return; }
    if (!b) {
      b = document.createElement('div');
      b.id = BADGE;
      b.setAttribute('style', 'position:fixed!important;left:8px;right:8px;top:8px;z-index:2147483647;pointer-events:none;' +
        'background:rgba(20,22,28,.88);color:#fff;font:13px/1.35 sans-serif;padding:6px 10px;border-radius:10px;' +
        'white-space:nowrap;overflow:hidden;text-overflow:ellipsis;');
      (document.body || document.documentElement).appendChild(b);
    }
    b.textContent = text;
  }
  function waitingBadge() { badge('Tap to fix: tap the ' + (ctx.label || 'thing').toLowerCase() + ' on the page'); }

  // ---------- picking ----------
  function pick(el, via) {
    if (!el || mode !== 'tap' || !ctx.id) return;
    current = el;
    var r;
    try { r = compute(el); } catch (e) { log('tap not worked out: ' + e); return; }
    paint(el, r.list);
    badge('Picked ' + r.tag + ' → ' + r.selector + ' ×' + r.count);
    send('pick', {
      id: ctx.id, via: via || 'tap', selector: r.selector, count: r.count, cards: r.cards, relative: r.relative,
      samples: r.samples, weak: r.weak, note: r.note, tag: r.tag, snippet: r.snippet,
    });
  }
  function pickAt(x, y, fallback) { pick(adjust(resolveAt(x, y, fallback)), 'tap'); }
  function interactive(t) { return !!(t && t.closest && t.closest(INTERACTIVE)); }
  function stop(e) { e.preventDefault(); e.stopImmediatePropagation(); }
  var CAPTURE = { capture: true, passive: false };

  window.addEventListener('click', function (e) {
    if (mode !== 'tap') return;
    stop(e);
    if (Date.now() - lastPickAt < 700) return;
    pickAt(e.clientX, e.clientY, e.target);
  }, CAPTURE);
  ['pointerdown', 'pointerup', 'mousedown', 'mouseup', 'dblclick', 'auxclick'].forEach(function (name) {
    window.addEventListener(name, function (e) { if (mode === 'tap' && interactive(e.target)) stop(e); }, CAPTURE);
  });
  window.addEventListener('touchstart', function (e) {
    if (mode !== 'tap') return;
    var t = e.touches && e.touches[0];
    moved = false;
    if (t) { touchX = t.clientX; touchY = t.clientY; }
    if (interactive(e.target)) { touchTarget = e.target; stop(e); } else { touchTarget = null; }
  }, CAPTURE);
  window.addEventListener('touchmove', function (e) {
    var t = e.touches && e.touches[0];
    if (t && (Math.abs(t.clientX - touchX) > 10 || Math.abs(t.clientY - touchY) > 10)) moved = true;
  }, { capture: true, passive: true });
  window.addEventListener('touchend', function (e) {
    if (mode !== 'tap' || !touchTarget) return;
    var target = touchTarget;
    touchTarget = null;
    stop(e);
    // a click that may still follow this touch is not a second tap
    if (!moved) { lastPickAt = Date.now(); pickAt(touchX, touchY, target); }
  }, CAPTURE);
  window.addEventListener('submit', function (e) { if (mode === 'tap') stop(e); }, CAPTURE);
  window.addEventListener('focusin', function (e) {
    var t = e.target;
    if (mode === 'tap' && t && /^(input|select|textarea)/i.test(t.tagName || '') && t.blur) t.blur();
  }, true);

  // ---------- bot checks: only read, never touched ----------
  function botCheck() {
    var title = (document.title || '').toLowerCase();
    var words = ['just a moment', 'attention required', 'verify you are human', 'checking your browser', 'are you a robot',
      'ddos-guard', 'please wait while we verify', 'security check'];
    for (var i = 0; i < words.length; i++) if (title.indexOf(words[i]) >= 0) return true;
    if (document.querySelector('#challenge-form, #challenge-running, #cf-challenge-running, #challenge-error-title, iframe[src*="challenges.cloudflare.com"]')) return true;
    var text = document.body ? visibleText(document.body) : '';
    if (text.length < 1500 && document.querySelector('.g-recaptcha, .h-captcha, .cf-turnstile, iframe[src*="hcaptcha.com"], iframe[src*="recaptcha"]')) return true;
    return false;
  }

  function setContext(c) {
    ctx = {
      id: c.id || null, item: c.item || null, itemAlt: c.itemAlt || null, kind: c.kind || 'text', single: !!c.single,
      label: c.label || '',
    };
    current = null;
    lastPickAt = 0;
    unpaint();
    if (mode === 'tap') waitingBadge(); else badge(null);
    var m;
    try { m = measure(c.old || '', c.oldAlt || null); } catch (e) { m = { ok: false, error: String(e), count: 0, cards: -1, samples: [] }; }
    m.id = ctx.id;
    send('measure', m);
  }

  window.__tapFix = {
    hello: function () { send('ready', { url: location.href, title: document.title || '', botCheck: botCheck() }); },
    setMode: function (m) {
      // never Tap on a bot check page: the user passes it in WebView, nothing here takes its taps
      mode = m === 'tap' && !botCheck() ? 'tap' : 'browse';
      if (mode === 'browse') { unpaint(); badge(null); current = null; } else if (!current && ctx.id) waitingBadge();
    },
    setContext: setContext,
    ensure: function (c) { if (ctx.id !== (c.id || null)) setContext(c); },
    up: function () {
      if (!current) return;
      var p = current.parentElement;
      if (!p || p === document.body || p === document.documentElement) { log('up: already at the top'); return; }
      pick(p, 'up');
    },
    down: function () {
      if (!current) return;
      var kids = Array.prototype.filter.call(current.children, function (k) {
        var r = k.getBoundingClientRect();
        return r.width > 0 && r.height > 0 && (visibleText(k) || hasPicture(k));
      });
      if (!kids.length) { log('down: ' + describe(current) + ' has no child with text or a picture'); return; }
      pick(kids[0], 'down');
    },
    clear: function () { current = null; unpaint(); if (mode === 'tap' && ctx.id) waitingBadge(); else badge(null); },
  };
  window.__tapFix.hello();
})();
"""
}
