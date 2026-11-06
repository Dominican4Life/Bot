# 09 · Glass design memory (icons, colours, menus, sheets)

**What this is:** the "memory card" for the glass redesign (r125–r137). An AI session that touches the UI should read this first, then make new things look like they belong. The owner can also paste this file into a new conversation. When the code and this file disagree, the code wins; update this file in the same docs-only push that ships the change.

## 1. How the owner and the AI work on design

- **The owner is visual.** Every design choice is shown as a mockup before it's built. Show 2–4 options, the current look marked "Now" (dashed outline), and **the original icon next to every redrawn one** so the difference is visible. Recommend one ("✓ my pick") and say why in plain words.
- Mockups live on the design canvas artifact (claude.ai/artifact/4eAwQG3q2mVnJTJVzcs4Kh, page "glass"). Each board is a `project/X….dc.html` file plus an entry in `canvas.json` (`boards` and `order`). They use placeholder art only (generated scenes), with neutral titles. The owner's videos and screenshots may contain adult content, so nothing from them goes into a mockup.
- **Phone and tablet both.** Every item applies to both the phone and the tablet layout unless it only makes sense on one. Each list item says which (Phone + Tablet / Phone only / Tablet only). If unsure, ask the owner before building. Past misses: the phone sheet (r135/r136) and the sliding bar (r134) were done for one layout without asking about the other.
- **Collect a list, then build once.** Items are numbered (r134 1–26, r135 1–15 …). Nothing is pushed until the owner says **"go"**. Any push of non-`.md` files starts a CI build and a release. To save work without building, push to a branch outside `master` / `feature/**` (e.g. `wip/rN`).
- **Read requests carefully and ask when unsure.** Past mistakes:
  - "Can scroll up still have header" was read as "hide the header" (r134), then removed (r135, option C).
  - "Line the menu up" meant only the ⋮ menu, not turning the window into a sheet (r136).
  - "Never remove the button around arrow" meant: remove the circle behind the Suggestions →, keep the ← circle.
- **Never redraw brand logos.** AniList reuses the app's own mark (`brand_anilist` paths, `ic_glass_anilist*.xml`).
- Don't name video sites anywhere (code, strings, docs).

## 2. Icon language (how to draw a glass icon)

All glass icons are in `app/.../presentation/components/GlassIcons.kt`, drawn on a 24×24 grid with two helpers:

- `fill(d, alpha)`: a solid shape. A full-strength fill is the "body"; **`alpha ≈ 0.38` is the faint "glass" part**. Use 0.3–0.45 for faint, 0.55–0.75 for a mid tone.
- `line(d, width, alpha)`: a round-capped, round-joined stroke. Typical widths: **1.6–1.8 outlines, 2.0–2.2 main strokes, 2.4 bold accents**.

Rules that every icon follows:

1. **Start from the original Material icon and keep its idea and silhouette.** The owner rejects new metaphors. They asked for "the original resize icon, revamped" (Z2) and "the original clock for Latest".
2. **Two tones:** one solid part (what the icon *is* or *does*) plus one faint glass part (the background or context).
   - Edit Source: faint page, solid pencil.
   - Bulk Select: bold ticks, faint bars.
3. **Rounded everything:** corner radii of about 2–3.2 on frames, round caps, no sharp Material corners.
4. **On / off pairs:**
   - "on" is filled or solid;
   - "off" is faint, or an outline (`ReadOff`, `BookmarkOff`, `PopularOn`/`Popular`, `LatestOn`/`Latest`, `PreviewPanelOn`/`PreviewPanel`);
   - a slash only where the original had one (AniList off);
   - off states must still be readable: r134 raised `ReadOff` / `BookmarkOff` to fill 0.3 and line 0.7–0.85.
5. **A "+" means "add to library" only.** Popular is an empty heart when off and a solid heart when on, with no "+".
6. **Matching families look alike.**
   - Edit Source (E2: faint page with a solid pencil) and Make Editable Copy (K8a: the same, plus a full page outlined behind) are a pair.
   - The catalog tabs (Trending, Hot, Top, Calendar, New, Recent, Favourite, People, Featured, Subtitles, Movie) are one family ("Family A").
7. **Size in use:**
   - 20–22 dp in rows and buttons, 16–18 dp in chips;
   - tinted by `LocalContentColor`, never by a baked colour;
   - colour comes from the theme (see §3).
8. **Icons that aren't vectors in `GlassIcons`:**
   - incognito is an animated vector (`anim_incognito.xml`, G1 shapes, same hat-and-glasses animation);
   - AniList is a drawable (`ic_glass_anilist.xml` / `_off`).

### Icons done so far (all in `GlassIcons`)

| Area | Icons |
|---|---|
| Title page | Chapters, Preview, Suggestions, Download, Filter, InLibrary, AddToLibrary, NextUpdate(Off), Tracker, TrackerAdd, WebView, Play, Arrow |
| Chapter rows | Read / ReadOff, Unread, Bookmark / BookmarkOff / BookmarkRemove, ChapterDownload / Queued / DownloadArrow / Downloaded / DownloadError |
| Selection | SelectAll, InvertSelection, BulkSelect (B4) |
| Display | Display, LayoutComfortable / Compact / Covers / List, PanoramaFitWidth / ColorFill / Wide, ShowTitle, ShapeAuto / Page / Square / Strip, PreviewSize (Z2) |
| Library ⋮ menu | Details, UpdateLibrary, RandomTitle, ReindexDownloads, RefreshCovers, SyncLibrary, ComingUp |
| Library grouping | Category, Status, Ungrouped |
| Sorting | SortUp / Down / Shuffle / Alpha / Total / LastRead / Unread / Latest / Fetched / Added / Score / Random / Number / Upload |
| Chapter sheet | SetDefault, Reset, Scanlators |
| Source browsing | Search, Popular / PopularOn, Latest / LatestOn, SourceSettings, EditSource (E2), EditableCopy (K8a), Help, Retry, PreviewPanel / PreviewPanelOn (X6, one toggle that doesn't flip sides) |
| Catalog tabs (Family A) | TabTrending, TabHot, TabTop, TabCalendar, TabNew, TabRecent, TabFavourite, TabPeople, TabFeatured, TabSubtitles, TabMovie |
| Preview player (r134) | Pause, SkipNext / SkipPrevious, Back10 / Forward10, Close, Lock / LockOpen, Tune, Quality ("HD" in a faint frame), Fullscreen, VolumeOn / VolumeOff, DragHandle |
| Navigation bits | ChevronUp / Down / Left / Right, SwapSides, ArrowBack (mirror of Arrow), Home (HC, r137) |
| r137 | SortNewest / SortOldest (SF: rounded sort bars with the direction arrow on the left), Finished (double tick) |
| Drawables | AniList (N9: the A solid and the L faint; faint with a slash when off), incognito (G1) |

**Still Material (candidates for later):** some anime title-page and settings-screen icons, `Icons.Default.Save` in the filter sheet, and anything a source draws itself.

## 3. Colour rules

- **Accent** (`LocalGlassAccent`, in `presentation-core/.../GlassAccent.kt`):
  - on title pages and previews it's the **cover's colour**: `glassAccentFor(seed, primary, fallback)`, with the primary brightened until its luminance is ≥ 0.42;
  - everywhere else, or for a colourless cover, it's **the app colour from Settings › Appearance**: `appGlassAccent`, provided by `TachiyomiTheme`;
  - gold (`GlassGold`) is only a last-resort default; the owner didn't want yellow.
- **Text and icons on an accent fill:** `onGlassAccent(accent)`, which gives dark or white depending on the accent's brightness.
- **W rule (r135):**
  - every icon and label on glass is **white**;
  - the accent is used only for **"on" states**: Start / Resume, the unread dot, picked chips / ticks / switches, an active action button, a downloaded chapter;
  - the not-downloaded indicator is white.
- **Glass surfaces:**
  - `tintedGlass(alpha, base = 0xFF181A22, amount = 0.26)` is dark glass washed with the accent; use it for panels, tags, the description box, action buttons, See Recommendations and side panels;
  - `liquidGlass(shape, base)` (browse components) is the refractive capsule; its default base is `0xFF12141B`;
  - while browsing a source, the floating pieces use `BrowseGlassBase` (`0xFF14161C`, dark frosted) so they read over busy covers.
- **Panels:** `SlidingSidePanel(deepTint = alpha)` washes a side panel with `tintedGlass(alpha)`: 0.62 for previews, 0.86 for Filter / Source Settings (r137). Dialogs (`AdaptiveSheet`) are tinted dark glass in dark themes only.
- **No edge lines or dividers on glass** (removed in r133 / r134). Separate things with spacing or a slightly darker card (black 0.25).

## 4. Components to reuse (don't rebuild these)

| Need | Use |
|---|---|
| Slider with steps | `GlassStepSlider` / `GlassSliderRow` / `GlassColumnsSlider` (GlassControls.kt). A white capsule at rest becomes a clear lens while held or dragged, with springs and haptic ticks. Track "held" and "dragging" separately (r135 bug). Reader page slider: `GlassSliderThumb` (same feel). |
| Pick one of a few | `GlassChoiceSwitch(choices, selected, onSelect, icons, perRow)`; names wrap to 2 lines |
| On / off | `GlassSwitch`; in Android preference screens, paint the switches in `onCreateAdapter` (SourcePreferencesScreen) |
| Buttons | `GlassButton(text, primary, onClick, icon)` |
| Ticks | `GlassTick` (round, menus), `GlassTriStateBox` (checkbox rows), `GlassRoundTick`, `GlassPickRow` |
| Settings rows | `SortItem(label, desc, onClick, icon)`, `BaseSortItem`, `RadioItem(icon)`, `CheckboxItem`, `SelectItem` (SettingsItems.kt) |
| Menus | `DropdownMenu` (eu.kanade.presentation.components): glass, 196 dp, `GlassMenuStyle` (22 dp corners, `0xFF1C1E27` at 0.96, lit rim). For a wider menu, use material3 `DropdownMenu` with `GlassMenuStyle.shape / container() / rim()` (Preview Size, the player's quality / speed menus with `heightIn(max = 380.dp)`). |
| Menu lined up with its row | `TopAlignedGlassMenu` (r136): its top-right corner sits on the anchor's top-right corner. Used by the ⋮ in glass app-bar capsules (the capsule hides while it's open, via `LocalOverflowOpen`) and by the chapter sheet tabs' ⋮. |
| Filter fields | `FilledFilterField` / `FilterFieldBox`, and `filterFieldColor` (glass inside a glass panel). Every field is the same filled box, showing "Any" when empty. |
| Glass panel context | `LocalGlassPanel` (true inside glass panels and sheets), `LocalPhoneSheet` (inside the phone sheet) |

## 5. Layout decisions the owner made (keep them)

- **Title page:**
  - no dark header box (H1: the backdrop picture and its `surfaceTint` layer are hidden with glass);
  - a soft top fade, and a shadow behind the title;
  - floating glass ← and ⋮; the ⋮ capsule hides while its menu is open;
  - no description caret; no dividers around Suggestions;
  - Suggestions sit in their own tinted panel, and the → has no circle.
- **Browsing a source:**
  - nothing is solid; buttons float on dark frosted glass and the covers scroll under them;
  - **the top bar never moves (C)**; the source name capsule is 48 dp like the buttons;
  - no language line under the name, and no divider under the chips;
  - the empty-query Filter tab keeps the capsule, and search opens only from 🔍.
- **The cover being previewed** in a grid gets a thin glowing ring in the accent (S1), not a filled block.
- **Phone Filter / Source Settings (r136):**
  - a bottom sheet like the chapter sheet: full width, rounded top, handle, tinted;
  - it slides up to just under the button row;
  - it moves **only up and down** (full / half, remembered), and dragging it down closes it;
  - Save / Clear / Apply sit at the bottom.
- **Tablet:** Filter / Settings are the side panels (`SlidingSidePanel`), tinted (r137, was dark frosted). The title page has the cover blurred behind it, tinted panels, and Start / Resume inside the chapter or episode panel; manga rows have eye / bookmark / download (r137).
- **Filter content (r137, 4A):** groups are glass rows with a pill of what's picked, the header counts "N set", Save / Clear / Apply at the bottom on phone and tablet.
- **Labels are Title Case** ("3868 Chapters", "Add Anyway").
- **Bot checks:** the app **never** solves, clicks or bypasses a check. It may only notice one sooner (`isCheckPage`, `SlowLoadingScreen`) and send the user to WebView to solve it themselves.

## 6. Mockup checklist (copy this)

1. Put "Now" first, with a dashed outline and the original icons.
2. Show 2–4 options with letters (A1, B2 …), each with one line on what it is and what it trades off; mark your recommendation.
3. Use placeholder art (`scene()`), neutral titles, gold or blue standing in for the accent (say which).
4. For each icon, show it on a dark glass tile at two sizes (38 px and 20 px).
5. Publish to the canvas, then list the choices for the owner and wait for picks and **"go"**.
