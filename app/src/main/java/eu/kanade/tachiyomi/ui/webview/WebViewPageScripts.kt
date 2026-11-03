package eu.kanade.tachiyomi.ui.webview

import androidx.core.net.toUri
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r109): scripts the app's WebView screen puts into each page.
 *
 * - [HOVER_MENUS]: desktop menus that open on mouse hover (the ▾ ones) open on a tap: the first tap on an item with a
 *   hidden sub-menu sends it the mouse-over events (and shows the sub-menu itself when the site only uses CSS :hover)
 *   instead of following a "#" / "javascript:" link; a second tap on it lets the tap through; a tap elsewhere closes
 *   it. Normal links are left alone.
 * - [PHONE_LAYOUT]: makes the page lay out 480 px wide, so a site shows its phone layout on a tablet. Turned on per
 *   site host from the WebView's ⋮ menu ([PhoneLayoutHosts]).
 */
object WebViewPageScripts {

    const val HOVER_MENUS = """
        (function () {
          try {
            if (window.__kmkHoverMenus) return;
            window.__kmkHoverMenus = true;
            var MENU_SEL = "ul,ol,div,nav,section,[role=menu],[role=listbox]";
            var opened = [];
            function isHidden(el) {
              try {
                var cs = window.getComputedStyle(el);
                if (!cs) return false;
                if (cs.display === "none" || cs.visibility === "hidden") return true;
                if (parseFloat(cs.opacity) === 0) return true;
                return false;
              } catch (e) { return false; }
            }
            function linkCount(el) {
              try {
                return el.querySelectorAll("a[href],button,[role=menuitem],[role=option]").length;
              } catch (e) { return 0; }
            }
            function hrefKind(el) {
              if (!el || el.tagName !== "A") return "none";
              var h = (el.getAttribute("href") || "").trim();
              if (h === "" || h === "#" || h.toLowerCase().indexOf("javascript:") === 0) return "dummy";
              return "real";
            }
            function inMenuBar(el) {
              try {
                return !!el.closest("nav,header,[role=navigation],[role=menubar],[role=menu]");
              } catch (e) { return false; }
            }
            // The hidden sub-menu that belongs to this trigger, or null
            function subMenuOf(el) {
              var cands = [];
              var i;
              for (i = 0; i < el.children.length; i++) cands.push(el.children[i]);
              if (el.nextElementSibling) cands.push(el.nextElementSibling);
              var li = el.parentElement && el.parentElement.tagName === "LI" ? el.parentElement : null;
              if (li) {
                for (i = 0; i < li.children.length; i++) {
                  var c = li.children[i];
                  if (c !== el && !c.contains(el)) cands.push(c);
                }
              }
              var id = el.getAttribute("aria-controls");
              if (id) {
                var byId = document.getElementById(id);
                if (byId) cands.unshift(byId);
              }
              for (i = 0; i < cands.length; i++) {
                var m = cands[i];
                if (!m || !m.matches || !m.matches(MENU_SEL)) continue;
                if (linkCount(m) < 1) continue;
                if (isHidden(m)) return m;
              }
              return null;
            }
            function strongTrigger(el) {
              var hp = (el.getAttribute("aria-haspopup") || "").toLowerCase();
              if (hp && hp !== "false") return true;
              if (el.getAttribute("aria-expanded") === "false") return true;
              return false;
            }
            // Finds the tapped element that opens a hidden sub-menu
            function triggerFor(target) {
              var el = target;
              for (var depth = 0; el && el !== document.body && depth < 5; depth++, el = el.parentElement) {
                if (el.nodeType !== 1) continue;
                var tag = el.tagName;
                var clickable = tag === "A" || tag === "BUTTON" || tag === "SUMMARY" ||
                  el.getAttribute("role") === "button" || el.getAttribute("role") === "menuitem" ||
                  el.hasAttribute("aria-haspopup") || el.hasAttribute("aria-expanded") ||
                  (el.parentElement && el.parentElement.tagName === "LI");
                if (!clickable) continue;
                var menu = subMenuOf(el);
                if (!menu) {
                  if (tag === "A" && hrefKind(el) === "real") return null;
                  continue;
                }
                var kind = hrefKind(el);
                // A normal link is only a menu trigger when the page says so or it sits in the site's menu bar
                if (kind === "real" && !strongTrigger(el) &&
                    !(el.parentElement && el.parentElement.tagName === "LI" && inMenuBar(el))) return null;
                var li = el.parentElement && el.parentElement.tagName === "LI" ? el.parentElement : null;
                return { el: el, menu: menu, li: li };
              }
              return null;
            }
            function fire(el, type, bubbles) {
              if (!el) return;
              try {
                var ev;
                if (type.indexOf("pointer") === 0 && typeof PointerEvent === "function") {
                  ev = new PointerEvent(type, {
                    bubbles: bubbles, cancelable: true, pointerType: "mouse", isPrimary: true, view: window
                  });
                } else {
                  ev = new MouseEvent(type, { bubbles: bubbles, cancelable: true, view: window });
                }
                el.dispatchEvent(ev);
              } catch (e) {}
            }
            function enter(el) {
              fire(el, "pointerover", true);
              fire(el, "pointerenter", false);
              fire(el, "mouseover", true);
              fire(el, "mouseenter", false);
            }
            function leave(el) {
              fire(el, "pointerout", true);
              fire(el, "pointerleave", false);
              fire(el, "mouseout", true);
              fire(el, "mouseleave", false);
            }
            var PROPS = ["display", "visibility", "opacity", "pointer-events"];
            function forceShow(entry) {
              var m = entry.menu;
              if (!m || !isHidden(m)) return;
              entry.saved = PROPS.map(function (p) {
                return [p, m.style.getPropertyValue(p), m.style.getPropertyPriority(p)];
              });
              var cs = window.getComputedStyle(m);
              if (cs.display === "none") m.style.setProperty("display", "block", "important");
              m.style.setProperty("visibility", "visible", "important");
              m.style.setProperty("opacity", "1", "important");
              m.style.setProperty("pointer-events", "auto", "important");
            }
            function restore(entry) {
              var m = entry.menu;
              if (!m || !entry.saved) return;
              entry.saved.forEach(function (s) {
                if (s[1]) m.style.setProperty(s[0], s[1], s[2]); else m.style.removeProperty(s[0]);
              });
              entry.saved = null;
            }
            function open(t) {
              var entry = { el: t.el, menu: t.menu, li: t.li, saved: null };
              opened.push(entry);
              enter(t.li);
              enter(t.el);
              // Menus shown by the site's CSS :hover only: show them ourselves
              setTimeout(function () { try { forceShow(entry); } catch (e) {} }, 60);
            }
            function close(entry) {
              leave(entry.el);
              leave(entry.li);
              try { restore(entry); } catch (e) {}
            }
            function holds(entry, node) {
              return (entry.li && entry.li.contains(node)) || entry.el.contains(node) ||
                (entry.menu && entry.menu.contains(node));
            }
            document.addEventListener("click", function (ev) {
              try {
                var target = ev.target;
                if (!target || target.nodeType !== 1) target = target && target.parentElement;
                if (!target) return;
                // Tap elsewhere: close what's open (outermost entries that don't hold the tap)
                var keep = [];
                for (var i = 0; i < opened.length; i++) {
                  if (holds(opened[i], target)) keep.push(opened[i]); else close(opened[i]);
                }
                opened = keep;
                // Second tap on an open trigger: let it through
                for (var j = 0; j < opened.length; j++) {
                  var o = opened[j];
                  if (o.el === target || o.el.contains(target)) {
                    if (hrefKind(o.el) !== "real") {
                      close(o);
                      opened.splice(j, 1);
                      var h = (o.el.getAttribute && o.el.getAttribute("href")) || "";
                      if (h.trim() === "#") ev.preventDefault();
                    }
                    return;
                  }
                }
                var t = triggerFor(target);
                if (!t) return;
                if (t.el.tagName === "A") ev.preventDefault();
                open(t);
              } catch (e) {}
            }, true);
          } catch (e) {}
        })();
    """

    const val PHONE_LAYOUT = """
        (function () {
          try {
            var WANT = "width=480";
            function apply() {
              try {
                var root = document.head || document.documentElement;
                if (!root) return;
                var metas = document.querySelectorAll("meta[name=viewport]");
                if (metas.length === 0) {
                  var m = document.createElement("meta");
                  m.setAttribute("name", "viewport");
                  m.setAttribute("content", WANT);
                  m.setAttribute("data-kmk", "1");
                  root.insertBefore(m, root.firstChild);
                  return;
                }
                for (var i = 0; i < metas.length; i++) {
                  if (metas[i].getAttribute("content") !== WANT) metas[i].setAttribute("content", WANT);
                }
              } catch (e) {}
            }
            apply();
            if (window.__kmkPhoneLayout) return;
            window.__kmkPhoneLayout = true;
            // The site may write its own viewport later: keep ours
            if (typeof MutationObserver === "function" && document.documentElement) {
              var watched = null;
              var headObserver = new MutationObserver(function () { apply(); });
              var watchHead = function () {
                if (document.head && watched !== document.head) {
                  watched = document.head;
                  headObserver.observe(watched, {
                    childList: true, subtree: true, attributes: true, attributeFilter: ["content", "name"]
                  });
                  apply();
                }
              };
              watchHead();
              new MutationObserver(function () { watchHead(); })
                .observe(document.documentElement, { childList: true });
            }
          } catch (e) {}
        })();
    """
}

/** KMK (r109): the site hosts whose WebView pages use the phone layout (off by default). */
object PhoneLayoutHosts {

    private val pref by lazy {
        Injekt.get<PreferenceStore>().getStringSet("webview_phone_layout_hosts", emptySet())
    }

    /** The key a page's address is remembered under: its host without "www.", or null */
    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val host = runCatching { url.toUri().host }.getOrNull()?.lowercase() ?: return null
        return host.removePrefix("www.").takeIf { it.isNotBlank() }
    }

    fun isOn(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        return host in pref.get()
    }

    /** Turns the phone layout on or off for this page's host; false when the address has no host */
    fun set(url: String?, on: Boolean): Boolean {
        val host = hostOf(url) ?: return false
        val now = pref.get()
        pref.set(if (on) now + host else now - host)
        return true
    }
}
