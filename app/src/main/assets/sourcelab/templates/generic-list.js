// @version 10
// Generic list template of the Source Lab, written for {{name}} ({{baseUrl}}) from what was tapped in Build by
// tapping (or filled by hand). Lists are pages of ITEM cards, chapters / episodes are the links of CHAPTER on a
// title page, pages are the IMAGE pictures of a chapter page, videos the VIDEO_FRAME of an episode page.
// r100 (v3): videos come from the app's video finder (host.findVideo); the tabs, filters and settings recorded in
// "Lists and options" of Build by tapping are added only when there are some (part "extras").
// r101 (v4): the title page reads the Cover / Description / Tags / Author / Status tapped on it first, keeps the
// list's cover when the page has none, skips the site-wide description and menu links; filters can change the list's
// address itself (a genre page /genres/{value}) and sort the list ("sort" filters).
// r102 (v5): the title page's rows tapped as Tags groups (Cast, Maker, Genres…) give "Cast: name" tags, which the
// app shows grouped like E-Hentai's; rows set to Description add a "Release date: …" line under the description.
// Tapping a grouped tag searches it in the source (a filter with that name and option when there is one).
// r103 (v6): filters made from the site's menus (one per menu group); a long list can be a text box with suggestions
// ("autocomplete" filters: the names typed are matched to the list's entries).
// r104 (v7): the title page's "You may like" / "Related" sections tapped as Suggestions become named groups of other
// titles on the title screen (getRelated); filters that open their own page (Trending → /en/trending?sort={value})
// combine with the other filters of that page; an only-episode title's episode gets the title's picture; the title
// page's small readers log what failed instead of hiding it.
// r105 (v8): filters have a role: "page" filters pick which list is browsed (a built-in "List" first: Popular, Latest
// and the tabs; Types → /en/censored, a directory → /en/makers/{value}; only the first one changed from its default
// applies), "extra" filters add their parameter (type=censored) on top of whichever list is chosen; filters can sit
// in named groups (Collections); suggestion covers skip placeholders; a section the site's script fills in later is
// read from the address it really comes from.
// r106 (v9): a suggestions section the site loads later can be read "with the browser" (the title page opened in the
// app's hidden browser until its cards show); a section's "Load more" button can be pressed up to 3 times: the next
// pages are asked for at the address one press uses ({page} filled), or the hidden browser presses it.
// r107 (v10): a directory (genres, makers, people, series…) can be a "collection" filter: its entries keep their parts
// apart (number, name, videos, views, likes, picture; never a Follow button), the site's own sort options and menus,
// and getDirectory reads its pages one at a time as Browse scrolls ("live"); the built-in "List" stays in getFilters
// (the app hides it; the list browsed still comes as "__from"); Suggestions read each section on its own (one that fails
// never fails the others), log each section's outcome, and give the hidden browser 35 s at most in all.

/*@part id="headers"*/
// Headers and constants: the site as Referer on every picture; the selectors the builder derived (each with
// its reason in the Explain pane) and the list paths ({page} and {q} are filled per call).
var BASE = "{{baseUrl}}";
var POPULAR_PATH = "{{popularPath}}";
var LATEST_PATH = "{{latestPath}}";
var SEARCH_PATH = "{{searchPath}}";
var ITEM = "{{itemSelector}}";
var TITLE = "{{titleSelector}}";
var COVER = "{{coverSelector}}";
var COVER_ATTR = "{{coverAttr}}";
var NEXT = "{{nextSelector}}";
var CHAPTER = "{{chapterSelector}}";
var CHAPTER_DATE = "{{dateSelector}}";
// r99: "This page is the only episode" (one video per title page, no list): the title page is episode 1, and
// CHAPTER_DATE / EPISODE_NUMBER are read on that page (EPISODE_NUMBER is used only then)
var SINGLE_EPISODE = "{{singleEpisode}}" === "true";
var EPISODE_NUMBER = "{{numberSelector}}";
var IMAGE = "{{imageSelector}}";
var IMAGE_ATTR = "{{imageAttr}}";
var VIDEO_FRAME = "{{videoFrame}}";
// r101: the title page's own fields tapped in Build by tapping (empty = not tapped: getDetails' fallbacks then)
var DETAILS_COVER = "{{detailsCover}}";
var DETAILS_COVER_ATTR = "{{detailsCoverAttr}}";
var DESCRIPTION = "{{descriptionSelector}}";
var TAGS = "{{tagsSelector}}";
var AUTHOR = "{{authorSelector}}";
var STATUS = "{{statusSelector}}";
// r102: the title page's rows tapped as Tags groups: [{ name, selector, target }], target "tags" (genres "name: value"),
// "description" (a "name: value, value" line under the description) or "both"; "[]" when none were tapped
var TAG_GROUPS = JSON.parse("{{tagGroups}}" || "[]");
// r104: the title page's sections tapped as Suggestions: [{ name, item, link, title, cover, coverAttr, late, read, wait,
// more }] ("[]" = none); getRelated (part "extras") lists each section's cards under its name (r105: "late" = the
// address a section the site's script fills in comes from; r106: read "browser" = the hidden browser shows the page
// until "wait" matches; more = { button, text, presses, request, from, step, wait }: its Load more button)
var SUGGESTION_GROUPS = JSON.parse("{{suggestionGroups}}" || "[]");
// r100: "Lists and options" of Build by tapping: the names of the Popular / Latest buttons (empty = the app's own),
// extra tabs [{ name, path }], filters [{ key, name, type, role, group, param, path, onSearch, options, on }] (r105:
// role "page" picks the list, "extra" adds param=value to it) and settings (the getSourcePreferences format). Lists are JSON text; "[]" when nothing was recorded. (Not POPULAR_NAME / LATEST_NAME:
// the app reads those globals as the names even when empty.)
var TAB_POPULAR_NAME = "{{tabPopularName}}";
var TAB_LATEST_NAME = "{{tabLatestName}}";
var TABS = JSON.parse("{{tabsList}}" || "[]");
var FILTERS = JSON.parse("{{filtersList}}" || "[]");
var SETTINGS = JSON.parse("{{settingsList}}" || "[]");
var HIDE_WORDS = SETTINGS.some(function (s) { return s && s.key === "hideWords"; });
var extension = {
  name: "{{name}}",
  lang: "{{lang}}",
  baseUrl: BASE,
  iconUrl: "{{iconUrl}}",
  popularName: TAB_POPULAR_NAME || undefined,
  latestName: TAB_LATEST_NAME || undefined,
  headers: { Referer: BASE + "/" },
/*@end*/

/*@part id="getPopular"*/
  // Popular: POPULAR_PATH with {page} filled, the ITEM cards (TITLE for the name and link, COVER for the picture).
  getPopular: async function (page) {
    return cards(await get(pathFor(POPULAR_PATH, page, "")));
  },
/*@end*/

/*@part id="getLatest"*/
  // Latest: LATEST_PATH with {page} filled, the same cards (the same path as Popular when the site has no
  // separate list).
  getLatest: async function (page) {
    return cards(await get(pathFor(LATEST_PATH, page, "")));
  },
/*@end*/

/*@part id="search"*/
  // Search and filters. FILTERS entries have a role (r105, v8): "page" filters pick the list that is browsed (their
  // option is a whole address such as /en/censored or /en/all?sort=week, or fills {value} in their path, e.g. a
  // directory's /en/makers/{value}); "extra" filters add param=value (type=censored) to whichever list is chosen. The
  // sheet starts with the built-in "List" (Popular, Latest, the tabs; Latest at first). Only one page filter applies:
  // the first one changed from its default in the filter order; with none changed the List's page is used: the list
  // Browse was showing when the filters were applied (the app sends it as "__from": "popular", "latest" or
  // "tab:<path>"), else Latest. Dropdowns and sorts have an empty first option ("Site default", "All"): an empty value
  // is no filter, every other option (the site's first one too) is sent. Extras are added only when set (a page keeps
  // its own sort otherwise). The page number follows the style of the list paths (?page=N, /page/N or /N). Words
  // typed: SEARCH_PATH with the words, plus the extras that work on the search page; the page filters and the other
  // extras are left out with a log line.
  // Older filters (no role, v7): a "path" filter is a page filter (two of one page's query parameters still combine), a
  // "param" filter an extra of the search page (with no list picked it opens the search page with no words, as in v7,
  // and the ones at their first option go along, as in v7). An extra with onLists false is only sent to the search page.
  // r102 (v5): a grouped tag tapped on a title page arrives as "Group: tag" (Genres: Drama): when a filter is named
  // like the group (or has it as key) and has an option labelled like the tag, that option is picked (no words);
  // else only the tag's words are searched. An "autocomplete" filter (a text box with suggestions) gets the names typed
  // as a list: each is matched to an option by its label or value (any case), a name not in the list is sent as a
  // slug ("Slice of Life" → slice-of-life, logged), names starting with "-" are left out; a page filter uses the first name, an
  // extra joins them with ",".
  // r107 (v10): a "collection" filter (a directory's entries) is a page filter like a page autocomplete: the picked entry's
  // value (a name typed that isn't one of its entries is sent as a slug, logged). Its sort and menus only order the
  // app's drop-down; they are never sent here.
  search: async function (query, page, filters) {
    var list = typeof FILTERS !== "undefined" && Array.isArray(FILTERS) ? FILTERS : [];
    var groups = typeof TAG_GROUPS !== "undefined" && Array.isArray(TAG_GROUPS) ? TAG_GROUPS : [];
    var plan = filterPlan(list);
    var q = String(query || "").trim();
    var tagged = /^([^:：]{1,40}?)\s*[:：]\s*(\S.*)$/.exec(q);
    if (tagged) {
      var ns = tagged[1].trim().toLowerCase();
      var tag = tagged[2].trim();
      var known = ns === "tags" || groups.some(function (g) { return g && String(g.name || "Tags").trim().toLowerCase() === ns; }) ||
        plan.some(function (f) { return String(f.name || "").trim().toLowerCase() === ns || String(f.key || "").toLowerCase() === ns; });
      if (known) {
        var hit = null;
        var typed = null;
        plan.forEach(function (f) {
          if (hit || f.builtIn || (f.type !== "select" && f.type !== "sort" && f.type !== "autocomplete" && f.type !== "collection")) return;
          if (String(f.name || "").trim().toLowerCase() !== ns && String(f.key || "").toLowerCase() !== ns) return;
          (f.options || []).forEach(function (o, index) {
            if (!hit && o && String(o.label || "").trim().toLowerCase() === tag.toLowerCase()) hit = { f: f, o: o, index: index };
          });
          if (!hit && !typed && (f.type === "autocomplete" || f.type === "collection")) typed = f;
        });
        if (!hit && typed) hit = { f: typed, o: { label: tag, value: slugOf(tag) }, index: -1 };
        if (hit) {
          filters = Object.assign({}, filters || {});
          filters[hit.f.key] = hit.f.type === "sort" ? { index: hit.index, value: hit.o.value, ascending: false } :
            hit.f.type === "autocomplete" ? [hit.o.label] : hit.f.type === "collection" && hit.index < 0 ? hit.o.label : hit.o.value;
          q = "";
          console.log("Tag " + tagged[1].trim() + ": " + tag + " → filter " + (hit.f.name || hit.f.key) + " = " + (hit.o.value || "(any)"));
        } else {
          q = tag;
          console.log("Tag " + tagged[1].trim() + ": " + tag + " → no filter has it: searching \"" + tag + "\"");
        }
      }
    }
    // r105 fix: the list Browse was showing (Popular, Latest or a tab) is the List's default
    var from = filters && typeof filters.__from === "string" ? filters.__from : "";
    // Every filter's value as sent by the app ("" = none) and whether it was changed from its default
    var chosen = [];
    plan.forEach(function (f) {
      var raw = filters ? filters[f.key] : undefined;
      var v = f.type === "autocomplete" || f.type === "collection" ? suggestedValue(f, raw) : filterValue(f, raw);
      if (f.builtIn) {
        var listDefault = f.options[f.defaultIndex].value;
        var here = f.options.some(function (o) { return o.value === from; }) ? from : listDefault;
        if (!v || v === listDefault) chosen.push({ f: f, v: here, picked: false });
        else chosen.push({ f: f, v: v, picked: true });
        return;
      }
      if (!v) return;
      if (!f.builtIn && (f.role === "page" ? f.path.indexOf("{value}") < 0 && !/^(\/|https?:)/i.test(v) : !f.param)) {
        console.log("Filter " + (f.name || f.key) + " left out: it has no address parameter or path");
        return;
      }
      chosen.push({ f: f, v: v, picked: !atDefault(f, raw, v) });
    });
    function name(c) { return c.f.name || c.f.key; }
    var url;
    var lead = null;
    var applied = 0;
    if (q) {
      url = pathFor(SEARCH_PATH, page, q);
      chosen.forEach(function (c) {
        if (c.f.role === "page") {
          if (!c.f.builtIn && c.picked) console.log("Filter " + name(c) + " picks a list: left out while searching for words");
        } else if (!c.picked && !c.f.legacy) {
          // at its default: not sent
        } else if (!c.f.onSearch) {
          console.log("Filter " + name(c) + " left out: it wasn't seen working on the search page");
        } else {
          // (a v7 filter at its first option goes along, as v7 sent it)
          url = withParam(url, c.f.param, c.v);
          if (c.picked) applied++;
        }
      });
    } else {
      var pages = chosen.filter(function (c) { return c.f.role === "page"; });
      var pickedPage = pages.filter(function (c) { return c.picked; })[0] || null;
      lead = pickedPage || pages.filter(function (c) { return c.f.builtIn; })[0] || pages[0] || null;
      // Extras seen only on the search page (filters written before r105) open the search page with no words when
      // no list was picked, as they always did
      var searchOnly = !pickedPage && chosen.some(function (c) { return c.f.role === "extra" && c.picked && c.f.onLists === false; });
      if (searchOnly) {
        url = pathFor(SEARCH_PATH, page, "");
        lead = null;
      } else {
        url = lead ? pageAddress(lead.f, lead.v, page) : pathFor(LATEST_PATH, page, "");
      }
      if (lead && !lead.f.builtIn) applied++;
      chosen.forEach(function (c) {
        if (c === lead) return;
        if (c.f.role === "page") {
          if (!c.picked || c.f.builtIn) return;
          // v7: two filters of the same page's query (/en/recent?type={value}, ?year={value}) combine
          if (lead && c.f.queryParam && lead.f.queryParam && c.f.pagePart === lead.f.pagePart) {
            url = withParam(url, c.f.queryParam, c.v);
            applied++;
          } else {
            console.log("Filter " + name(c) + " left out: " + (lead ? name(lead) + " (changed first) picks the list" : "the search page is used"));
          }
          return;
        }
        // (v7 filters at their first option go along to the search page, as v7 sent them)
        if (!c.picked && !(searchOnly && c.f.legacy)) return;
        if (searchOnly ? !c.f.onSearch : c.f.onLists === false) {
          console.log("Filter " + name(c) + " left out: it works on " + (searchOnly ? "the list pages" : "the search page") + ", not on " + url);
          return;
        }
        url = withParam(url, c.f.param, c.v);
        if (c.picked) applied++;
      });
    }
    var found = await cards(await get(url));
    // a filter search with no titles says which address was tried (Test Run's console shows it)
    if (!found.length && applied) {
      console.log("Filters gave 0 titles at " + url + ": check the filters' addresses (Learn addresses on the site's " +
        "dropdowns reads the codes the site really sends)");
    }
    return found;
  },
/*@end*/

/*@part id="getDetails"*/
  // Title page: the Cover / Description / Tags / Author / Status tapped on it first (DETAILS_COVER, DESCRIPTION,
  // TAGS, AUTHOR, STATUS). Else: the cover from og:image, a <video poster> or the player's poster, and no cover at
  // all when there is none (the list's cover stays); og:description unless it is the site's own slogan (the home
  // page's description, read once); genre / tag links outside the site's menus, without "Genres" / "Tags" words.
  // Author and status only when tapped. r102 (v5): the rows tapped as Tags groups (TAG_GROUPS) come first: each
  // value becomes the genre "Group: value" (the app groups them like E-Hentai's) and / or a "Group: a, b" line under
  // the description; the other genres are then "Tags: value". (Every r101 / r102 constant is checked, so this part
  // also runs in older sources.)
  getDetails: async function (url) {
    var doc = await host.parse(await get(url));
    var pickedCover = typeof DETAILS_COVER !== "undefined" ? DETAILS_COVER : "";
    var pickedCoverAttr = typeof DETAILS_COVER_ATTR !== "undefined" ? DETAILS_COVER_ATTR : "";
    var pickedDescription = typeof DESCRIPTION !== "undefined" ? DESCRIPTION : "";
    var pickedTags = typeof TAGS !== "undefined" ? TAGS : "";
    var pickedAuthor = typeof AUTHOR !== "undefined" ? AUTHOR : "";
    var pickedStatus = typeof STATUS !== "undefined" ? STATUS : "";
    var tagGroups = (typeof TAG_GROUPS !== "undefined" && Array.isArray(TAG_GROUPS) ? TAG_GROUPS : [])
      .filter(function (g) { return g && g.selector; });
    function clean(t) { return String(t || "").replace(/\s+/g, " ").trim(); }
    function picture(u) {
      u = String(u || "").trim().split(/[\s,]+/)[0];
      if (!u || /^data:/i.test(u)) return null;
      var full = abs(u);
      return /^https?:\/\//i.test(full || "") && !/\.svg(\?|#|$)|favicon|\/logo[^\/]*$/i.test(full) ? full : null;
    }
    // r104 (v7): a selector that fails is logged (it reads "" so the other fields still come)
    async function textOf(css) {
      if (!css) return "";
      try {
        var el = await doc.selectOne(css);
        return el ? clean(el.text) : "";
      } catch (e) {
        console.log("Title page: " + css + " wasn't read: " + (e && e.message));
        return "";
      }
    }
    async function meta(css, name) {
      try {
        return clean(await doc.attr(css, name));
      } catch (e) {
        console.log("Title page: " + name + " of " + css + " wasn't read: " + (e && e.message));
        return "";
      }
    }
    var out = { url: url, webUrl: url };
    out.title = (await doc.text("h1") || await doc.attr("meta[property='og:title']", "content") || "").trim();

    // Cover: the tapped picture, og:image, a <video poster>, the player's poster; none = left out (the list's stays)
    var cover = null;
    if (pickedCover) {
      try {
        var el = await doc.selectOne(pickedCover);
        if (el && el.tag !== "img") el = (await el.selectOne("img")) || el;
        if (el) {
          cover = picture(el.attr(pickedCoverAttr || "src")) || picture(el.attr("data-src")) || picture(el.attr("data-lazy-src")) ||
            picture(el.attr("data-original")) || picture(el.attr("srcset")) || picture(el.attr("src")) || picture(el.attr("poster"));
        }
      } catch (e) { cover = null; }
    }
    cover = cover || picture(await meta("meta[property='og:image']", "content")) ||
      picture(await meta("meta[name='og:image']", "content")) ||
      picture(await meta("video[poster]", "poster")) ||
      picture(await meta("[data-poster]", "data-poster")) ||
      picture(await meta("meta[itemprop='thumbnailUrl']", "content")) ||
      picture(await meta("link[itemprop='thumbnailUrl']", "href")) ||
      picture(await meta("meta[name='twitter:image']", "content"));
    if (cover) out.coverUrl = cover;

    // Description: the tapped text, else the page's meta description unless it is the whole site's
    var description = await textOf(pickedDescription);
    if (!description) {
      var meta1 = await meta("meta[property='og:description']", "content") || await meta("meta[name='description']", "content");
      if (meta1) {
        var g = typeof globalThis !== "undefined" ? globalThis : {};
        if (g.__labSiteDescriptions === undefined) {
          g.__labSiteDescriptions = [];
          try {
            var home = await host.parse(await get(BASE + "/"));
            var a1 = clean(await home.attr("meta[property='og:description']", "content"));
            var a2 = clean(await home.attr("meta[name='description']", "content"));
            g.__labSiteDescriptions = [a1, a2].filter(function (x) { return x; }).map(function (x) { return x.toLowerCase(); });
          } catch (e) { console.log("The home page's description wasn't read: " + e.message); }
        }
        if (g.__labSiteDescriptions.indexOf(meta1.toLowerCase()) < 0) description = meta1;
        else console.log("The page's description is the site's own slogan: left out");
      }
    }

    // Tags: the tapped groups' rows, the tapped ones, else genre / tag links outside the site's menus
    var genres = [];
    var seenTag = {};
    function plainTag(t) {
      t = clean(t);
      return !t || t.length > 40 || /^(genres?|categor(y|ies)|tags?|keywords?|more|all|\+\d+)$/i.test(t) ? "" : t;
    }
    function pushTag(t) {
      if (seenTag[t.toLowerCase()]) return;
      seenTag[t.toLowerCase()] = true;
      genres.push(t);
    }
    // Genres are joined with ", " and split again by the app: a comma inside a grouped value would cut it in two
    function noComma(t) { return t.replace(/\s*[,，]\s*/g, " ").trim(); }
    function addTag(t) {
      t = plainTag(t);
      if (t && tagGroups.length) t = noComma(t);
      if (t) pushTag(tagGroups.length ? "Tags: " + t : t);
    }
    var lines = [];
    for (var gi = 0; gi < tagGroups.length; gi++) {
      var group = tagGroups[gi];
      var groupName = noComma(clean(group.name).replace(/\s*[:：]$/, "")) || "Tags";
      var target = group.target === "description" || group.target === "both" ? group.target : "tags";
      var label = new RegExp("^" + groupName.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "\\s*[:：]\\s*", "i");
      var values = [];
      var seenValue = {};
      try {
        (await doc.select(group.selector, { html: false })).forEach(function (el) {
          var t = clean(el.text).replace(label, "");
          if (!t || seenValue[t.toLowerCase()]) return;
          seenValue[t.toLowerCase()] = true;
          values.push(t);
        });
      } catch (e) { console.log("Tags group " + groupName + " wasn't read: " + e.message); }
      if (!values.length) continue;
      if (target !== "description") values.forEach(function (t) { var v = noComma(plainTag(t)); if (v) pushTag(groupName + ": " + v); });
      if (target !== "tags") lines.push(groupName + ": " + values.join(", "));
    }
    if (lines.length) description = description ? description + "\n\n" + lines.join("\n") : lines.join("\n");
    if (description) out.description = description;
    var fromGroups = genres.length;
    if (pickedTags) {
      try { (await doc.select(pickedTags, { html: false })).forEach(function (a) { addTag(a.text); }); } catch (e) { genres = genres.slice(0, fromGroups); }
    }
    if (!genres.length) {
      var links = await doc.select("a[href*='/genre'], a[href*='/tag'], a[href*='/categor'], a[rel~=tag]", { html: false });
      var menu = {};
      (await doc.select("nav a, header a, footer a, aside a, [class*=menu] a, [class*=nav] a", { html: false })).forEach(function (a) { menu[a.id] = true; });
      links.forEach(function (a) { if (!menu[a.id]) addTag(a.text); });
    }
    if (genres.length) out.genres = genres;

    // Author / status: only when tapped ("Author: X" / "Status: Ongoing" lose their label)
    var author = (await textOf(pickedAuthor)).replace(/^(authors?|artists?|by|cast|makers?|studios?)\s*[:：]\s*/i, "");
    if (author) out.author = author;
    var status = (await textOf(pickedStatus)).replace(/^status\s*[:：]?\s*/i, "");
    if (status) out.status = status;
    return out;
  },
/*@end*/

/*@part id="getChapterList"*/
  // Chapters / episodes: the CHAPTER links of the title page, each address once, the number from the text, the
  // date from CHAPTER_DATE inside or next to the link when one was picked. With SINGLE_EPISODE the title page is
  // the one episode: its own address, the h1 as title, number 1 (or EPISODE_NUMBER's), the date from CHAPTER_DATE.
  // r104 (v7): the only episode's thumbnail is the title's picture (the Cover tapped on the title page, else og:image,
  // a video poster or twitter:image).
  getChapterList: async function (url) {
    var doc = await host.parse(await get(url));
    if (SINGLE_EPISODE) {
      var when = CHAPTER_DATE ? (await doc.select(CHAPTER_DATE))[0] : null;
      var numberEl = EPISODE_NUMBER ? (await doc.select(EPISODE_NUMBER))[0] : null;
      var name = (await doc.text("h1") || await doc.attr("meta[property='og:title']", "content") || "Episode 1").trim();
      var episode = {
        url: url,
        title: name,
        number: (numberEl ? numberOf(numberEl.text) : null) || 1,
        dateUploaded: require("date").parse(when ? (when.attr("datetime") || when.attr("title") || when.text) : ""),
      };
      var thumb = "";
      try {
        var pickedCover = typeof DETAILS_COVER !== "undefined" ? DETAILS_COVER : "";
        var coverEl = pickedCover ? await doc.selectOne(pickedCover) : null;
        if (coverEl && coverEl.tag !== "img") coverEl = (await coverEl.selectOne("img")) || coverEl;
        var attr = typeof DETAILS_COVER_ATTR !== "undefined" && DETAILS_COVER_ATTR ? DETAILS_COVER_ATTR : "src";
        if (coverEl) thumb = coverEl.attr(attr) || coverEl.attr("data-src") || coverEl.attr("src") || coverEl.attr("poster") || "";
        thumb = thumb || await doc.attr("meta[property='og:image']", "content") || await doc.attr("video[poster]", "poster") ||
          await doc.attr("meta[name='twitter:image']", "content") || "";
      } catch (e) {
        console.log("Episode thumbnail wasn't read: " + (e && e.message));
      }
      thumb = String(thumb || "").trim().split(/[\s,]+/)[0];
      if (thumb && !/^data:/i.test(thumb)) episode.thumbnailUrl = abs(thumb);
      return [episode];
    }
    if (!CHAPTER) throw new Error("No chapter link was picked in Build by tapping: open a title and tap a chapter link, or say the title page is the only episode");
    var links = await doc.select(CHAPTER);
    var out = [];
    var seen = {};
    for (var i = 0; i < links.length; i++) {
      var a = links[i].tag === "a" ? links[i] : await links[i].selectOne("a");
      if (!a || !a.attr("href") || seen[a.attr("href")]) continue;
      seen[a.attr("href")] = true;
      var date = CHAPTER_DATE ? await links[i].selectOne(CHAPTER_DATE) : null;
      out.push({
        url: abs(a.attr("href")),
        title: a.attr("title") || a.text,
        number: numberOf(a.text || a.attr("href")),
        dateUploaded: require("date").parse(date ? (date.attr("datetime") || date.attr("title") || date.text) : ""),
      });
    }
    return out;
  },
/*@end*/

/*@part id="getPageList"*/
  // Pages: the IMAGE pictures of the chapter page, the address from IMAGE_ATTR (then the lazy-load attributes
  // and src), in page order.
  getPageList: async function (url) {
    var doc = await host.parse(await get(url));
    var imgs = await doc.select(IMAGE);
    var out = [];
    for (var i = 0; i < imgs.length; i++) {
      var src = imgs[i].attr(IMAGE_ATTR) || imgs[i].attr("data-src") || imgs[i].attr("data-lazy-src") || imgs[i].attr("src") || "";
      src = String(src).trim().split(/[\s,]+/)[0];
      if (src) out.push({ index: out.length, imageUrl: abs(src) });
    }
    return out;
  },
/*@end*/

/*@part id="getVideoList"*/
  // Videos: the app's video finder (host.findVideo) opens the episode page in the hidden browser, presses Play and
  // catches the stream the player asks for (.m3u8 / .mpd / .mp4), also when the site's script fills in the
  // player's address after the page loads; then the player itself is opened with the page as Referer. {{videoNote}}
  // The "quality" and "server" settings (when the source has them) put the preferred ones first.
  getVideoList: async function (url) {
    var found = await host.findVideo(url, { frame: VIDEO_FRAME, referer: BASE + "/" });
    var hls = require("hls");
    var out = [];
    for (var i = 0; i < (found.videos || []).length; i++) {
      var v = found.videos[i];
      var video = { url: v.url, quality: v.quality, server: v.server, headers: v.headers };
      out.push.apply(out, v.kind === "hls" ? await hls.expand(video) : [video]);
    }
    if (!out.length) throw new Error("No playable video on " + url + ": " + (found.reason || "nothing caught"));
    var wantQ = String((await host.preference.get("quality")) || "");
    var wantS = String((await host.preference.get("server")) || "").toLowerCase();
    if ((wantQ && wantQ !== "Best") || wantS) {
      out = out.map(function (v, i) {
        var score = (wantQ && wantQ !== "Best" && String(v.quality).indexOf(wantQ) >= 0 ? 2 : 0)
          + (wantS && String(v.server).toLowerCase().indexOf(wantS) >= 0 ? 1 : 0);
        return { v: v, i: i, s: score };
      }).sort(function (a, b) { return b.s - a.s || a.i - b.i; }).map(function (x) { return x.v; });
    }
    return out;
  },
/*@end*/
};

/*@part id="extras"*/
// Tabs, filters and settings from "Lists and options" in Build by tapping, each added only when some were recorded
// (a source without them has exactly the methods above): TABS become tab buttons at the top of Browse (getCatalogs /
// getCatalog: the tab's path with {page} filled, the same cards), FILTERS the filter sheet (search adds their
// values), SETTINGS the source's settings (the code reads them with host.preference.get("key")).
// r103 (v6): an "autocomplete" filter is a text box with its options as suggestions (no "-" exclusions).
// r104 (v7): SUGGESTION_GROUPS give getRelated: each section's cards on the title page, as named groups
// { "You may like": [...], "Related videos": [...] } (a manga title screen shows each under its name).
// r105 (v8): the sheet starts with "List" (Popular, Latest, the tabs: the list a filter search browses); filters with
// a group name sit under that heading ({ type: "group", name, filters }); each filter says its role (page / extra).
// A suggestions section the site's script fills in after the page loads ("late": the address it comes from, with
// {seg1} … the title address's parts, {path}, {url}, {attr:name} or {attr:selector|name} filled) is read from that
// address. Covers take the first real picture address (data-src, data-lazy-src, data-original, srcset, src), never a
// placeholder (data:, blank.gif, spacer.gif, placeholder.png…, or one picture every card of a section has).
// r106 (v9): a section with read "browser" is read from the title page as the app's hidden browser shows it (host.render,
// waiting for its cards: slower, 2–4 s, but it works when no address was found); a section's "more" (its Load more
// button, pressed up to "presses" times) adds the next pages: from "request" with {page} = from, from + step… (stops at
// a page that adds nothing), else the hidden browser presses the button and the cards are read after.
// r107 (v10): getFilters keeps the built-in List first (the app hides it; search still reads "__from"). A
// "collection" filter goes to the app with its entries' parts ({ label, value, number, videos, views, likes, picture }:
// only the parts in its "fields"), the site's sort options ("sorts"), its other menus ("menus"), "live" and "total";
// getDirectory(key, page, sort, ascending, menus) reads one page of its directory ("directory": the page address with
// {page}, the entry selector and each part's selector) with the site's sort / menus as address parameters. getRelated
// reads the title page inside each section's try (a section that fails is logged, the others still come), logs each
// section's outcome and gives the hidden browser 35 s at most for all its sections.
if (TABS.length) {
  extension.getCatalogs = function () {
    return TABS.map(function (t) { return { id: t.path, name: t.name || t.path, tab: true }; });
  };
  extension.getCatalog = async function (id, page) {
    return cards(await get(pathFor(id, page, "")));
  };
}
if (FILTERS.length) {
  extension.getFilters = function () {
    var out = [];
    filterPlan(FILTERS).forEach(function (f) {
      // r107 (v10): the List stays first (saved searches keep their filter positions); the app hides it in the sheet
      var shown = { key: f.key, name: f.name || f.key, type: f.type, role: f.role };
      if (f.type === "select" || f.type === "sort") shown.options = f.options || [];
      if (f.type === "autocomplete") {
        shown.options = f.options || [];
        shown.hint = "Type a name: " + (f.name || f.key);
        shown.prefixes = [];
      }
      if (f.type === "collection") {
        shown.mode = "path";
        shown.path = f.path || "";
        shown.fields = Array.isArray(f.fields) && f.fields.length ? f.fields : ["title"];
        shown.options = f.options || [];
        shown.sorts = Array.isArray(f.sorts) ? f.sorts : [];
        shown.reverse = f.reverse === true;
        shown.menus = Array.isArray(f.menus) ? f.menus : [];
        shown.live = f.live === true && !!(f.directory && f.directory.item);
        shown.total = +f.total || 0;
      }
      if (f.defaultIndex) shown.defaultIndex = f.defaultIndex;
      if (!f.group) {
        out.push(shown);
        return;
      }
      // one heading per group name, where its first filter is
      var held = out.filter(function (x) { return x.type === "group" && x.name === f.group; })[0];
      if (held) held.filters.push(shown);
      else out.push({ type: "group", key: "group:" + f.group, name: f.group, filters: [shown] });
    });
    return out;
  };
}
if (SETTINGS.length) {
  extension.getSourcePreferences = function () {
    return SETTINGS;
  };
}
if (FILTERS.some(function (f) { return f && f.type === "collection" && f.directory && f.directory.item; })) {
  // r107 (v10): one page of a collection's directory: { items: [{ label, value, number?, videos?, views?, likes?,
  // picture? }], hasNext }. [sort] = one of its "sorts" values ("" = the site's own order), [ascending] is sent only
  // when the site offers it ("reverse"), [menus] = { menuKey: value } (empty values left out)
  extension.getDirectory = async function (key, page, sort, ascending, menus) {
    var f = FILTERS.filter(function (x) { return x && x.key === key && x.type === "collection"; })[0];
    if (!f || !f.directory || !f.directory.item) throw new Error("The filter " + key + " has no directory to read");
    var d = f.directory;
    var n = Math.max(1, Math.floor(+page || 1));
    if (n > 1 && !d.page) return { items: [], hasNext: false };
    var url = n === 1 ? pathFor(d.first || d.page, 1, "") : pathFor(d.page, n, "");
    var s = String(sort === undefined || sort === null ? "" : sort).trim();
    if (s && !d.sortParam && /^(\/|https?:)/i.test(s)) url = pathFor(withPageNumber(s, n), n, "");
    else if (s) url = withParam(url, d.sortParam || "sort", s);
    if (f.reverse === true && d.orderParam) url = withParam(url, d.orderParam, ascending ? (d.ascValue || "asc") : (d.descValue || "desc"));
    Object.keys(menus && typeof menus === "object" ? menus : {}).forEach(function (k) {
      var v = menus[k] === undefined || menus[k] === null ? "" : String(menus[k]).trim();
      if (v) url = withParam(url, k, v);
    });
    var doc = await host.parse(await get(url));
    var items = await directoryItems(f, doc);
    var next = items.length > 0 ? await hasNextPage(doc, n) : false;
    console.log("Directory " + (f.name || key) + " page " + n + ": " + items.length + " entries from " + url + (next ? "" : " (last page)"));
    return { items: items, hasNext: next };
  };
}
if (typeof SUGGESTION_GROUPS !== "undefined" && Array.isArray(SUGGESTION_GROUPS) && SUGGESTION_GROUPS.length) {
  extension.getRelated = async function (url) {
    // r107 (v10): the title page is read inside a try: when it fails, the sections read with the browser still come
    var doc = null;
    var docError = "";
    try {
      doc = await host.parse(await get(url));
    } catch (e) {
      docError = (e && e.message) || String(e);
      console.log("Suggestions: the title page wasn't read: " + docError);
    }
    var out = {};
    var started = Date.now();
    // r107 (v10): the hidden browser gets 35 s at most for all the sections it reads
    var browserBudget = 35000;
    var browserUsed = 0;
    for (var gi = 0; gi < SUGGESTION_GROUPS.length; gi++) {
      var g = SUGGESTION_GROUPS[gi] || {};
      var name = String(g.name || "").trim() || "Suggestions";
      var list = out[name] || [];
      var before = list.length;
      var pics = list.map(function () { return null; });
      var seen = {};
      var t0 = Date.now();
      var where = url;
      list.forEach(function (x) { seen[x.url] = true; });
      // the cards of [els] not listed yet; how many were added
      var addCards = async function (els) {
        var added = 0;
        for (var i = 0; i < els.length; i++) {
          var card = els[i];
          var a = card.tag === "a" ? card : (g.link ? await card.selectOne(g.link) : null) || (await card.selectOne("a[href]"));
          if (a && a.tag !== "a") a = await a.selectOne("a[href]");
          if (!a || !a.attr("href")) continue;
          var href = abs(a.attr("href"));
          if (!href || seen[href] || href === url) continue;
          var nameEl = g.title ? await card.selectOne(g.title) : null;
          var img = g.cover ? await card.selectOne(g.cover) : await card.selectOne("img");
          if (img && img.tag !== "img") img = (await img.selectOne("img")) || img;
          var title = (nameEl ? nameEl.text : "") || a.attr("title") || a.text || (img ? img.attr("alt") : "") || "";
          title = String(title).replace(/\s+/g, " ").trim();
          if (!title) continue;
          seen[href] = true;
          list.push({ url: href, title: title, coverUrl: coverOf(img, g.coverAttr) });
          pics.push({ img: img, attr: g.coverAttr });
          added++;
        }
        return added;
      };
      try {
        var more = g.more && g.more.presses > 0 ? g.more : null;
        var presses = more ? Math.min(3, Math.max(0, +more.presses || 0)) : 0;
        // r106: no address for the next pages: the hidden browser presses Load more while it shows the page
        var browserPresses = more && !more.request ? presses : 0;
        var from = doc;
        if (g.read === "browser" || browserPresses) {
          // r106: the title page as the hidden browser shows it (the section filled in by the site's script)
          var left = browserBudget - browserUsed;
          if (left < 6000) throw new Error("the browser's 35 s for suggestions were used by the sections before it");
          where = url + " (in the browser)";
          var b0 = Date.now();
          try {
            from = await renderedSection(url, g, browserPresses, left);
          } finally {
            browserUsed += Date.now() - b0;
          }
        } else if (g.late) {
          // r105: the section comes from another address the page asks for after it loads
          if (!doc && /\{attr:/.test(String(g.late))) throw new Error("its address needs the title page (" + docError + ")");
          where = await lateAddress(g.late, url, doc);
          from = await lateDoc(await get(where, { headers: { "X-Requested-With": "XMLHttpRequest" } }));
        } else if (!doc) {
          throw new Error("the title page wasn't read (" + docError + ")");
        }
        var els = g.item ? await from.select(g.item) : [];
        if (!els.length && g.late && g.read !== "browser" && !browserPresses) els = await shorterSelect(from, g.item);
        await addCards(els);
        // r106: Load more's next pages from the address one press asks for
        if (more && more.request && presses) {
          var step = Math.max(1, +more.step || 1);
          var first = +more.from || 2;
          for (var p = 0; p < presses; p++) {
            var at = await lateAddress(String(more.request).split("{page}").join(String(first + p * step)), url, doc);
            var page = await lateDoc(await get(at, { headers: { "X-Requested-With": "XMLHttpRequest" } }));
            var mels = await page.select(g.item);
            if (!mels.length) mels = await shorterSelect(page, g.item);
            var got = await addCards(mels);
            console.log("Suggestions " + name + ": Load more " + (p + 1) + " → " + got + " more from " + at);
            if (!got) break;
          }
        }
        sameCoverAgain(list, pics, "Suggestions " + name);
        console.log("Suggestions " + name + ": " + (list.length - before) + " cards from " + where + " in " + (Date.now() - t0) + " ms");
      } catch (e) {
        console.log("Suggestions " + name + " failed after " + (Date.now() - t0) + " ms: " + ((e && e.message) || String(e)));
      }
      if (list.length) out[name] = list;
    }
    console.log("Suggestions: " + Object.keys(out).length + " of " + SUGGESTION_GROUPS.length + " sections in " + (Date.now() - started) + " ms");
    return out;
  };
}
/*@end*/

/*@part id="helpers"*/
// Helpers: one request with the site as Referer, the list path with {page} / {q} filled, a parameter set in an
// address, the ITEM card parser the lists share (titles with a "Hide titles containing" word left out when the
// source has that setting), absolute addresses and the last number of a text.
// r105 (v8): the filters as search() uses them (filterPlan: the built-in List first, each filter's role), a page
// filter's address with the page number in the list paths' style, picture addresses without placeholders (coverOf),
// and the address / reply of a section that loads later. r106 (v9): a section read in the hidden browser
// (renderedSection, with its Load more pressed by pressScript). r107 (v10): a collection's directory entries
// (directoryItems: each part from its own element; entryLabel, countIn, valueOfHref, hasNextPage).
async function get(url, options) {
  var o = options || {};
  var headers = Object.assign({ Referer: BASE + "/" }, o.headers || {});
  var r = await host.fetch(url, Object.assign({}, o, { headers: headers }));
  if (r.statusCode >= 400) throw new Error("The site answered " + r.statusCode + " for " + url);
  return r.body || "";
}
function pathFor(path, page, query) {
  var p = String(path).split("{page}").join(String(page)).split("{q}").join(encodeURIComponent(query));
  if (page === 1) p = p.replace(/\/page\/1\/?$/, "/").replace(/([?&])page=1(&|$)/, "$1").replace(/[?&]$/, "");
  return /^https?:/.test(p) ? p : BASE + (p.charAt(0) === "/" ? "" : "/") + p;
}
// r101 (v4): the value a filter sends to search(): a dropdown's option value, a sort's picked option ({ index, value,
// ascending } from the app) value, a ticked checkbox's "on" value, a text's words; "" = not set
function filterValue(f, v) {
  if (v === undefined || v === null) return "";
  if (f.type === "checkbox") return v === true || v === "true" ? String(f.on || "1") : "";
  if (f.type === "sort") {
    if (typeof v === "object") {
      var opt = (f.options || [])[v.index];
      v = v.value !== undefined && v.value !== null && v.value !== "" ? v.value : (opt ? opt.value : "");
    }
    return v === undefined || v === null ? "" : String(v).trim();
  }
  if (typeof v === "object") return "";
  return String(v).trim();
}
// r103 (v6): "Slice of Life" → slice-of-life (a name typed that isn't in a filter's list)
function slugOf(t) {
  return String(t).toLowerCase().replace(/[^0-9a-zÀ-￿]+/g, "-").replace(/^-+|-+$/g, "");
}
// r103 (v6): the value an autocomplete filter sends: its names mapped to option values (a page filter: the first).
// r107 (v10): a collection's pick (its entry's value, or { value } / { label }) the same way
function suggestedValue(f, v) {
  if (v && typeof v === "object" && !Array.isArray(v)) v = v.value !== undefined && v.value !== null && v.value !== "" ? v.value : v.label;
  var names = Array.isArray(v) ? v : (v === undefined || v === null || v === "" ? [] : [v]);
  var out = [];
  names.forEach(function (n) {
    var t = String(n === null || n === undefined ? "" : n).trim();
    if (!t) return;
    if (t.charAt(0) === "-") { console.log("Filter " + (f.name || f.key) + ": \"" + t + "\" left out (exclusion isn't supported)"); return; }
    var low = t.toLowerCase();
    var hit = null;
    (f.options || []).forEach(function (o) {
      if (!hit && o && (String(o.label || "").trim().toLowerCase() === low || String(o.value || "").toLowerCase() === low)) hit = o;
    });
    // r107 fix: a collection's pick not in its stored entries (a live list's later page) is the entry's value as it is
    if (!hit && f.type === "collection") {
      if (out.indexOf(t) < 0) out.push(t);
      return;
    }
    var value = hit ? String(hit.value || "") : slugOf(t);
    if (!hit) console.log("Filter " + (f.name || f.key) + ": \"" + t + "\" isn't in its list: sent as " + value);
    if (value && out.indexOf(value) < 0) out.push(value);
  });
  if (!out.length) return "";
  return f.role === "page" || f.mode === "path" ? out[0] : out.join(",");
}
// r105 (v8): FILTERS as search() and getFilters use them: the built-in "List" first (Popular, Latest — the default —
// and the tabs), then each filter with its role ("page" / "extra"; v7 filters: "path" → page, "param" → extra that
// also works on the search page), its group name and its parameter or path
function filterPlan(list) {
  var out = [];
  if (!list || !list.length) return out;
  var tabs = typeof TABS !== "undefined" && Array.isArray(TABS) ? TABS : [];
  var popular = typeof TAB_POPULAR_NAME !== "undefined" && TAB_POPULAR_NAME ? TAB_POPULAR_NAME : "Popular";
  var latest = typeof TAB_LATEST_NAME !== "undefined" && TAB_LATEST_NAME ? TAB_LATEST_NAME : "Latest";
  var options = [{ label: popular, value: "popular" }, { label: latest, value: "latest" }];
  tabs.forEach(function (t) { if (t && t.path) options.push({ label: t.name || t.path, value: "tab:" + t.path }); });
  out.push({ key: "__list", name: "List", type: "select", role: "page", builtIn: true, options: options, defaultIndex: 1, group: "" });
  var searchPage = String(typeof SEARCH_PATH !== "undefined" ? SEARCH_PATH : "").split("?")[0].replace(/\/+$/, "").toLowerCase();
  list.forEach(function (f) {
    if (!f || !f.key) return;
    var legacy = f.role !== "page" && f.role !== "extra";
    var role = legacy ? (f.mode === "path" ? "page" : "extra") : f.role;
    var n = Object.assign({}, f, { role: role, group: String(f.group || "").trim(), defaultIndex: f.defaultIndex || 0, legacy: legacy });
    // r105 fix: a dropdown / sort has an empty option ("Site default" first when it has none), the one an untouched
    // sheet sends, so the site's own first option can be picked too (v7 filters keep their options as they were)
    if (!legacy && (f.type === "select" || f.type === "sort")) {
      var opts = Array.isArray(f.options) ? f.options.slice() : [];
      var empty = -1;
      opts.forEach(function (o, i) { if (empty < 0 && o && String(o.value === undefined || o.value === null ? "" : o.value).trim() === "") empty = i; });
      if (empty < 0) {
        opts.unshift({ label: "Site default", value: "" });
        empty = 0;
      }
      n.options = opts;
      if (!f.defaultIndex) n.defaultIndex = empty;
    }
    if (role === "page") {
      n.path = String(f.path || "");
      // v7: {value} in the query of a page's path (/en/recent?year={value}) is a parameter of that page: other
      // filters of the same page add theirs to it; the search page's own one is a search parameter
      var at = n.path.indexOf("?");
      var qp = at >= 0 && n.path.indexOf("{value}") > at ? /[?&]([^=&?]+)=\{value\}/.exec(n.path) : null;
      if (qp) {
        n.queryParam = decodeURIComponent(qp[1]);
        n.pagePart = n.path.slice(0, at).replace(/\/+$/, "").toLowerCase();
        if (legacy && n.pagePart === searchPage) {
          n.role = "extra";
          n.param = n.queryParam;
          n.onSearch = true;
          n.onLists = false;
        }
      }
    } else {
      n.param = String(f.param || f.key);
      // v7 param filters were the search page's: also on search, and with no words they open the search page
      n.onSearch = legacy ? true : f.onSearch === true;
      n.onLists = legacy ? false : f.onLists !== false;
    }
    out.push(n);
  });
  return out;
}
// A dropdown / sort left at its default option (the app always sends one) is the default, not a pick. r105 fix: a v8
// filter's default is its empty option (filterPlan), so any value is a pick; a v7 one's is its first option
function atDefault(f, raw, v) {
  if (f.type !== "select" && f.type !== "sort") return false;
  if (!f.legacy) return !v;
  var d = f.defaultIndex || 0;
  if (raw && typeof raw === "object" && typeof raw.index === "number") return raw.index === d;
  var first = (f.options || [])[d];
  return !!first && String(first.value === undefined || first.value === null ? "" : first.value).trim() === v;
}
// The page number in the list paths' style: ?page=N (any name), /page/N or /N; page 1 is the address as it is.
// r105 fix: only a page word before /{page} (page, p, pg, pagina, seite, strona, trang, halaman) is kept: in
// /recent/{page} "recent" is the list's own name, so another list gets /N; a slash after {page} is kept
function pageStyle() {
  var paths = [LATEST_PATH, POPULAR_PATH, SEARCH_PATH];
  for (var i = 0; i < paths.length; i++) {
    var p = String(paths[i] || "");
    var m = /[?&]([^=&?#]+)=\{page\}/.exec(p);
    if (m) return { kind: "query", name: m[1] };
    m = /\/([^\/?#{}]+)\/\{page\}(\/?)/.exec(p);
    if (m && /^(page|p|pg|pagina|seite|strona|trang|halaman)$/i.test(m[1])) return { kind: "pathWord", name: m[1], trail: m[2] === "/" };
    m = /\/\{page\}(\/?)/.exec(p);
    if (m) return { kind: "pathEnd", name: "", trail: m[1] === "/" };
  }
  return { kind: "query", name: "page" };
}
function withPageNumber(p, page) {
  p = String(p);
  if (p.indexOf("{page}") >= 0 || page === 1 || !page) return p;
  var st = pageStyle();
  if (st.kind === "query") return withParam(p, st.name, String(page));
  var at = p.indexOf("?");
  var path = (at < 0 ? p : p.slice(0, at)).replace(/\/+$/, "");
  return path + "/" + (st.kind === "pathWord" ? st.name + "/" : "") + page + (st.trail ? "/" : "") + (at < 0 ? "" : p.slice(at));
}
// r105 fix: a value for an address: encoded, except %XX already in it (a directory's slug read from its links)
function encodeValue(v) {
  return String(v).split(/(%[0-9A-Fa-f]{2})/).map(function (s, i) { return i % 2 ? s : encodeURIComponent(s); }).join("");
}
// The address a page filter's value opens: the built-in List's list, the value in its path's {value}, or the value
// itself when it is an address (/en/censored, /en/all?sort=week); the page number added in the lists' style
function pageAddress(f, v, page) {
  if (f.builtIn) {
    if (v === "popular") return pathFor(POPULAR_PATH, page, "");
    if (String(v).indexOf("tab:") === 0) return pathFor(withPageNumber(String(v).slice(4), page), page, "");
    return pathFor(LATEST_PATH, page, "");
  }
  var p = f.path && f.path.indexOf("{value}") >= 0 ? f.path.split("{value}").join(encodeValue(v)) : String(v);
  return pathFor(withPageNumber(p, page), page, "");
}
function withParam(url, key, value) {
  var at = url.indexOf("#");
  var hash = at >= 0 ? url.slice(at) : "";
  var base = at >= 0 ? url.slice(0, at) : url;
  var k = encodeURIComponent(key);
  var pair = k + "=" + encodeURIComponent(value);
  var re = new RegExp("([?&])" + k.replace(/[.*+?^$()|[\]\\]/g, "\\$&") + "=[^&]*");
  if (re.test(base)) return base.replace(re, function (m, sep) { return sep + pair; }) + hash;
  var joiner = base.indexOf("?") < 0 ? "?" : (/[?&]$/.test(base) ? "" : "&");
  return base + joiner + pair + hash;
}
// r105 (v8): a picture's address: the picked attribute, then the lazy-load ones, srcset and src; data: / about:blank
// addresses, placeholders by their file name (blank.gif, spacer.gif, placeholder.png, 1x1.gif…; not real covers
// named pixel-… or lazy-…) and [skip] (the picture every card has) are skipped
function coverOf(img, attr, skip) {
  if (!img) return null;
  var names = [attr, "data-src", "data-lazy-src", "data-original", "data-srcset", "srcset", "src"];
  for (var i = 0; i < names.length; i++) {
    if (!names[i] || names.indexOf(names[i]) < i) continue;
    var v = firstPicture(img.attr(names[i]), /srcset/i.test(names[i]));
    if (!v || /^(data|about|javascript):/i.test(v)) continue;
    if (/\.svg(\?|#|$)|favicon|\/logo[^\/]*$/i.test(v) || placeholderPicture(v)) continue;
    var full = abs(v);
    if (!full || (skip && full === skip)) continue;
    return full;
  }
  return null;
}
// The first address of an attribute: a srcset-style list ("a.jpg 320w, b.jpg 640w") gives its first entry; a comma
// inside an address (Cloudinary's w_200,h_300) stays
function firstPicture(raw, list) {
  var v = String(raw || "").trim();
  var d = /^(\S+)\s+\d+(?:\.\d+)?[wx]\s*(?:,|$)/.exec(v);
  if (d) return d[1];
  if (list) v = v.split(/,\s+(?=\S)/)[0];
  return v.split(/\s+/)[0];
}
// A known placeholder by its whole file name (blank.gif, spacer.gif, placeholder.png, lazy.png, 1x1.gif, no-image.jpg)
function placeholderPicture(v) {
  var file = String(v).split(/[?#]/)[0].split("/").pop().toLowerCase();
  return /^(placeholder|blank|spacer|transparent|pixel|1x1|empty|lazy|lazyload|loading|loader|grey|gray|no[-_]?(image|img|cover|thumb|poster))([-_.](min|small|\d+x\d+))?\.(gif|png|jpe?g|webp|svg)$/.test(file);
}
// r105 fix: one picture on every card (3 or more) is the site's placeholder: each card's next attribute is read
function sameCoverAgain(list, pics, what) {
  if (list.length < 3 || pics.length !== list.length) return;
  var c = list[0].coverUrl;
  if (!c || !list.every(function (x) { return x.coverUrl === c; })) return;
  console.log(what + ": every card has the picture " + c + ": a placeholder, left out");
  list.forEach(function (x, k) { x.coverUrl = pics[k] ? coverOf(pics[k].img, pics[k].attr, c) : null; });
}
// r105 (v8): a late section's address: {seg1}… = the title address's path parts (1 = the first), {path}, {url}
// (encoded) and {attr:name} (that attribute of the title page's first element having it; r105 fix:
// {attr:selector|name} = that attribute of the selector's first element)
async function lateAddress(tpl, url, doc) {
  var path = String(url).replace(/^https?:\/\/[^\/]+/i, "").split(/[?#]/)[0];
  var segs = path.split("/").filter(function (x) { return !!x; });
  var out = String(tpl).replace(/\{seg(\d+)\}/g, function (m, i) { return segs[+i - 1] || ""; })
    .split("{path}").join(path).split("{url}").join(encodeURIComponent(url));
  var attrs = out.match(/\{attr:[^{}]+\}/g) || [];
  for (var i = 0; i < attrs.length; i++) {
    var ref = attrs[i].slice(6, -1);
    var bar = ref.lastIndexOf("|");
    var n = bar >= 0 ? ref.slice(bar + 1) : ref;
    var css = bar >= 0 ? ref.slice(0, bar) : "[" + n + "]";
    var v = "";
    try { v = String((await doc.attr(css, n)) || ""); } catch (e) { console.log("Late section: " + n + " of " + css + " wasn't read: " + e.message); }
    out = out.split(attrs[i]).join(encodeURIComponent(v.trim()));
  }
  return abs(out);
}
// The reply of a late section: HTML, or JSON whose text values hold the HTML (joined)
async function lateDoc(body) {
  var t = String(body || "").trim();
  if (/^[{\[]/.test(t)) {
    var parts = [];
    var walk = function (x) {
      if (typeof x === "string") { if (x.indexOf("<") >= 0) parts.push(x); }
      else if (x && typeof x === "object") Object.keys(x).forEach(function (k) { walk(x[k]); });
    };
    try { walk(JSON.parse(t)); } catch (e) { parts = []; }
    if (parts.length) t = parts.join("\n");
  }
  return host.parse(t);
}
// A late reply holds the cards without the page around them: the item selector's last parts
async function shorterSelect(doc, css) {
  var parts = String(css || "").split(/\s+[>+~]\s+/);
  for (var k = 1; k < parts.length; k++) {
    try {
      var els = await doc.select(parts.slice(k).join(" > "));
      if (els.length) return els;
    } catch (e) { /* the next shorter one */ }
  }
  return [];
}
// r106 (v9): the title page as the app's hidden browser shows it, waiting (20 s at most) for the section's cards ("wait":
// the item selector a browser can read); with [presses], its Load more button is pressed that many times first, each
// time waiting until more cards show (5 s at most). r106 fix: a browser can't read Jsoup's :matchesOwn(…), so "wait"
// matches any section's cards: pressScript then finds the section's heading by its own text and waits (12 s at most)
// for at least one card under it, and counts only those cards for each press. r107 (v10): [budget] ms at most in all
// (35 s shared by getRelated's sections): the page's wait, the first card's wait and the presses are cut to fit it
async function renderedSection(url, g, presses, budget) {
  var wait = String(g.wait || (g.more && g.more.wait) || g.item || "").replace(/:(matchesOwn|matches|containsOwn|contains)\([^()]*\)/g, "").trim();
  var head = headTestOf(g.item);
  var total = Math.max(6000, Math.min(35000, +budget || 35000));
  var firstMs = Math.max(2000, Math.min(12000, Math.floor(total * 0.2)));
  // each press waits 5 s at most for more cards (plus 0.3 s). r107 fix: the presses are counted first (with the page's
  // 8 s at least), then the page gets what is left (8–20 s): one section keeps its 3 presses within 35 s
  var fit = Math.max(0, Math.floor((total - 8000 - firstMs) / 5300));
  if (presses > fit) {
    console.log("Suggestions " + (g.name || "") + ": Load more pressed " + fit + " times instead of " + presses + " (35 s at most in the browser)");
    presses = fit;
  }
  var load = Math.max(8000, Math.min(20000, total - firstMs - presses * 5300));
  var options = { timeout: load };
  if (wait) options.waitFor = wait;
  if (presses || head) options.script = pressScript(presses ? g.more.button : "", presses ? g.more.text : "", wait, presses, head, firstMs);
  var r = await host.render(url, options);
  if (r && r.timedOut) console.log("Suggestions " + (g.name || "") + ": the browser didn't show them within " + Math.round(load / 1000) + " s");
  if (presses) console.log("Suggestions " + (g.name || "") + ": Load more pressed " + (r && r.result) + " of " + presses + " times");
  else if (head && r && String(r.result) === "0") console.log("Suggestions " + (g.name || "") + ": the browser showed no card under \"" + (head.text || head.re) + "\"");
  return host.parse((r && r.html) || "");
}
// r106 fix: the heading test of an item selector: the first Jsoup text pseudo-class (:matchesOwn(re), :matches(re),
// :containsOwn(text), :contains(text)) and the element it is on ("h2.section__title"); null = none
function headTestOf(item) {
  var m = /([A-Za-z0-9_\-.#*]*):(matchesOwn|matches|containsOwn|contains)\(([^()]*)\)/.exec(String(item || ""));
  if (!m) return null;
  var regex = m[2] === "matchesOwn" || m[2] === "matches";
  return { css: m[1] || "*", own: /Own$/.test(m[2]), re: regex ? m[3] : "", text: regex ? "" : m[3].trim() };
}
// The script for the hidden browser (by the section's heading when [head] is given, else every match of [wait]):
// waits for a card, then presses a Load more button [presses] times (by its css path, else by its text), each time
// until more cards show. Its result: the presses done, or the cards found when there is nothing to press. r107 (v10):
// [firstMs] = how long it waits for the first card (12 s at most)
function pressScript(button, text, wait, presses, head, firstMs) {
  var firstWait = Math.max(1000, Math.min(12000, +firstMs || 12000));
  return "(function(){var sel=" + JSON.stringify(String(button || "")) + ",txt=" + JSON.stringify(String(text || "")) +
    ",wait=" + JSON.stringify(String(wait || "")) + ",want=" + (+presses || 0) + ",done=0,head=" + JSON.stringify(head || null) + ",re=null;" +
    "if(head&&head.re){try{re=new RegExp(head.re);}catch(e){head=null;}}" +
    "function all(css,root){try{return Array.prototype.slice.call((root||document).querySelectorAll(css));}catch(e){return [];}}" +
    "function own(el){var s='';for(var i=0;i<el.childNodes.length;i++){if(el.childNodes[i].nodeType===3)s+=el.childNodes[i].nodeValue;}return s.replace(/\\s+/g,' ').trim();}" +
    "function hit(el){var t=head.own?own(el):String(el.textContent||'').replace(/\\s+/g,' ').trim();" +
    "return re?re.test(t):t.toLowerCase().indexOf(String(head.text).toLowerCase())>=0;}" +
    "function count(){if(!wait)return 0;var cards=all(wait);if(!head)return cards.length;var n=0;" +
    "all(head.css).filter(hit).forEach(function(h){for(var anc=h.parentElement,up=0;anc&&up<6;anc=anc.parentElement,up++){" +
    "var rivals=all(h.tagName,anc).filter(function(r){return r!==h&&(h.compareDocumentPosition(r)&4)&&!cards.some(function(k){return k.contains(r);});});" +
    "var mine=cards.filter(function(c){return anc.contains(c)&&(h.compareDocumentPosition(c)&4)&&!rivals.some(function(r){return r.compareDocumentPosition(c)&4;});});" +
    "if(mine.length){n+=mine.length;break;}}});return n;}" +
    "function find(){var b=null;try{b=sel?document.querySelector(sel):null;}catch(e){b=null;}if(b)return b;if(!txt)return null;" +
    "var all2=document.querySelectorAll('button,a,[role=button],input[type=button]');for(var i=0;i<all2.length;i++){" +
    "var t=String(all2[i].textContent||all2[i].value||'').replace(/\\s+/g,' ').trim();if(t.indexOf(txt)===0)return all2[i];}return null;}" +
    "return new Promise(function(res){var t0=Date.now();" +
    "function step(){if(done>=want)return res(want?done:count());var b=find();if(!b)return res(done);" +
    "var before=count();try{b.click();}catch(e){return res(done);}done++;var t1=Date.now();" +
    "(function check(){if(count()>before||Date.now()-t1>5000)return setTimeout(step,300);setTimeout(check,200);})();}" +
    "(function first(){if(count()>=1||Date.now()-t0>" + firstWait + ")return step();setTimeout(first,250);})();});})()";
}
async function cards(html) {
  var doc = await host.parse(html);
  var items = await doc.select(ITEM);
  var hide = !HIDE_WORDS ? [] : String((await host.preference.get("hideWords")) || "").toLowerCase().split(",")
    .map(function (w) { return w.trim(); }).filter(function (w) { return w; });
  var out = [];
  var pics = [];
  for (var i = 0; i < items.length; i++) {
    var t = TITLE ? await items[i].selectOne(TITLE) : null;
    var a = t && t.tag === "a" ? t : (await items[i].selectOne("a[href]")) || (items[i].tag === "a" ? items[i] : null);
    if (!a || !a.attr("href")) continue;
    var img = COVER ? await items[i].selectOne(COVER) : await items[i].selectOne("img");
    if (img && img.tag !== "img") img = (await img.selectOne("img")) || img;
    var name = (t ? (t.text || t.attr("title")) : "") || a.attr("title") || a.text || (img ? img.attr("alt") : "");
    var lower = String(name || "").toLowerCase();
    if (hide.length && hide.some(function (w) { return lower.indexOf(w) >= 0; })) continue;
    out.push({
      url: abs(a.attr("href")),
      title: name,
      coverUrl: coverOf(img, COVER_ATTR),
    });
    pics.push({ img: img, attr: COVER_ATTR });
  }
  sameCoverAgain(out, pics, "List covers");
  return out;
}
// r107 (v10): a collection's entries on one page of its directory: each card ("item"), its link (the entry's value is
// the part of the link's address at {value} in the filter's path) and the parts kept in "fields", each read from its
// own element ("parts": title { sel, own, attr }; number / videos / views / likes { sel, bare }; picture { sel, attr };
// sel "" = the card itself). A Follow button is never read; the name never carries the counts.
async function directoryItems(f, doc) {
  var d = f.directory || {};
  var parts = d.parts || {};
  var fields = Array.isArray(f.fields) && f.fields.length ? f.fields : ["title"];
  var els = d.item ? await doc.select(d.item) : [];
  var out = [];
  var seen = {};
  for (var i = 0; i < els.length; i++) {
    var card = els[i];
    var a = null;
    if (d.link) { try { a = await card.selectOne(d.link); } catch (e) { a = null; } }
    if (!a) a = card.tag === "a" ? card : await card.selectOne("a[href]");
    if (a && a.tag !== "a") a = (await a.selectOne("a[href]")) || a;
    if (!a || !a.attr("href")) continue;
    var value = valueOfHref(f.path, abs(a.attr("href")));
    if (!value || seen[value]) continue;
    // r107 fix: a number read from the name's own text ("01 Slice of Life": number and title the same element) isn't part
    // of the name; the whole link's text ("FC FC2") without a logo's letters
    var name = await partText(card, parts.title, false);
    if (parts.number && parts.title && !parts.title.attr && parts.number.sel === parts.title.sel) name = name.replace(/^\s*#?\s*\d\d?\d?\d?\.?\s+(?=\S)/, "");
    var label = entryLabel(name) || entryLabel(a.attr("title")) || dropInitials(entryLabel(a.text));
    if (!label) continue;
    seen[value] = true;
    var item = { label: label, value: value };
    var kinds = ["number", "videos", "views", "likes"];
    for (var k = 0; k < kinds.length; k++) {
      var p = parts[kinds[k]];
      if (fields.indexOf(kinds[k]) < 0 || !p) continue;
      var v = countIn(kinds[k], await partText(card, p, true), p.bare === true);
      if (v) item[kinds[k]] = v;
    }
    if (fields.indexOf("picture") >= 0 && parts.picture) {
      var img = await partEl(card, parts.picture);
      if (img && img.tag !== "img") img = (await img.selectOne("img")) || img;
      var pic = coverOf(img, parts.picture.attr);
      if (pic) item.picture = pic;
    }
    out.push(item);
  }
  return out;
}
// A part's element inside a card (sel "" = the card itself), null when it has none
async function partEl(card, p) {
  if (!p) return null;
  if (!p.sel) return card;
  try { return await card.selectOne(p.sel); } catch (e) { return null; }
}
// A part's text: [whole] = all of it (a count), else the name: its attribute when it has one (alt, title), its own text
// without its children's when "own" (a name with a count inside the same element)
async function partText(card, p, whole) {
  var el = await partEl(card, p);
  if (!el) return "";
  if (!whole && p.attr) {
    var at = el.attr(p.attr);
    if (at) return String(at).replace(/\s+/g, " ").trim();
  }
  var t = String(el.text || "");
  if (!whole && p.own) {
    try {
      (await el.select("> *", { html: false })).forEach(function (c) {
        var ct = String(c.text || "").trim();
        if (ct) t = t.replace(ct, " ");
      });
    } catch (e) { /* the whole text */ }
  }
  return t.replace(/\s+/g, " ").trim();
}
// An entry's name without what a whole card adds to it: a Follow / Following button's words and trailing counts
// ("Slice of Life 1,234 videos · 2.1M views + Follow Following" → "Slice of Life")
function entryLabel(t) {
  var s = String(t || "").replace(/\s+/g, " ").trim();
  var before;
  do {
    before = s;
    s = s.replace(/\s*[+＋]?\s*(?:follow(?:ing|ed)?|unfollow|subscribed?|unsubscribe)\s*$/i, "").trim();
    s = s.replace(/\s*[·•|,]?\s*\d[\d.,]*(?:\s?[KMB](?![A-Za-z]))?\+?\s*(?:videos?|movies?|clips?|titles?|works?|items?|posts?|galleries|gallery|books?|mangas?|comics?|episodes?|views?|likes?|followers?|♥|❤)\s*$/i, "").trim();
    s = s.replace(/\s*[·•|]\s*$/, "").trim();
    // a thousands-grouped number alone at the end ("Drama 20,837"), never "Top 100" or a year
    s = s.replace(/^(.*[^\s\d.,])\s+\d\d?\d?(?:,\d\d\d)+\s*$/, "$1").trim();
  } while (s && s !== before);
  return s;
}
// r107 fix: "FC FC2" → "FC2", "S- S-Cute" → "S-Cute": a logo's 1–3 characters before the name they start (any case)
function dropInitials(t) {
  var s = String(t || "");
  var sp = s.indexOf(" ");
  if (sp <= 0) return s;
  var first = s.slice(0, sp);
  var rest = s.slice(sp + 1).trim();
  return first.length <= 3 && rest.length >= first.length && rest.toLowerCase().indexOf(first.toLowerCase()) === 0 ? rest : s;
}
// A count as the site shows it, its unit word dropped ("4,476 videos" → "4,476", "6.2M views" → "6.2M", "♥ 17.2K" →
// "17.2K"); [bare] = the part is a number alone; a "number" is an entry's place (01, 02…)
function countIn(kind, text, bare) {
  var t = String(text || "");
  var m = null;
  if (kind === "number") m = /^\s*#?\s*(\d\d?\d?\d?)\.?(?!\d)/.exec(t);
  else if (kind === "videos") m = /(\d[\d.,]*(?:\s?[KMB](?![A-Za-z]))?)\s*(?:videos?|movies?|clips?|titles?|works?|items?|posts?|galleries|gallery|books?|mangas?|comics?|episodes?)\b/i.exec(t);
  else if (kind === "views") m = /(\d[\d.,]*(?:\s?[KMB](?![A-Za-z]))?)\s*views?\b/i.exec(t);
  else if (kind === "likes") m = /(\d[\d.,]*(?:\s?[KMB](?![A-Za-z]))?)\s*(?:likes?\b|♥|❤)/i.exec(t) || /(?:♥|❤)\s*(\d[\d.,]*(?:\s?[KMB](?![A-Za-z]))?)/i.exec(t);
  if (!m && bare && kind !== "number") m = /(\d[\d.,]*(?:\s?[KMB](?![A-Za-z]))?)/i.exec(t);
  return m ? m[1].replace(/\s+/g, "").replace(/[.,]$/, "") : "";
}
// The value an entry's link gives a filter path: the part of its address at {value} (/en/makers/{value} → the
// maker's slug, as the site writes it), or the {value} parameter's value (?genre={value})
function valueOfHref(path, href) {
  var p = String(path || "");
  var h = String(href || "");
  var q = p.indexOf("?");
  var segs = (q < 0 ? p : p.slice(0, q)).split("/").filter(function (x) { return !!x; });
  var at = segs.indexOf("{value}");
  if (at >= 0) {
    var hs = h.replace(/^https?:\/\/[^\/]+/i, "").split(/[?#]/)[0].split("/").filter(function (x) { return !!x; });
    for (var i = 0; i < at; i++) {
      if (segs[i].indexOf("{") < 0 && String(hs[i] || "").toLowerCase() !== segs[i].toLowerCase()) return "";
    }
    return hs[at] || "";
  }
  var m = /[?&]([^=&?#]+)=\{value\}/.exec(p);
  if (!m) return "";
  var mm = new RegExp("[?&]" + m[1].replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "=([^&#]*)").exec(h);
  if (!mm) return "";
  try { return decodeURIComponent(mm[1].replace(/\+/g, " ")); } catch (e) { return mm[1]; }
}
// A directory page has a next one: rel=next, a "Next" / › link, or a link reading the next page's number
async function hasNextPage(doc, n) {
  try {
    if (await doc.selectOne("a[rel~=next][href], link[rel~=next][href]")) return true;
    if (await doc.selectOne("a[href]:matchesOwn((?i)^\\s*(next|next page|›|»|>|→)\\s*$)")) return true;
    return !!(await doc.selectOne("a[href]:matchesOwn(^\\s*" + (n + 1) + "\\s*$)"));
  } catch (e) {
    console.log("Directory: the next page wasn't looked for: " + (e && e.message));
    return false;
  }
}
function abs(href) {
  return href ? require("url").resolve(BASE, String(href).trim()) : null;
}
function numberOf(text) {
  var m = String(text || "").match(/(\d+(?:[.,]\d+)?)(?!.*\d)/);
  return m ? parseFloat(m[1].replace(",", ".")) : null;
}
/*@end*/
