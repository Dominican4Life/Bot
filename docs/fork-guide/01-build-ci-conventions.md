## Project structure, build, CI/releases, signing and working rules

This section covers how the code is laid out, how a push becomes a release `rN`, why every release so far has had a different signing key, the rules for working on the repo, and the compile errors that have cost release cycles before. Paths are relative to the repo root unless they start with `eu/kanade/...`; those are under `app/src/main/java/`.

Ground truth worth knowing first:

- **No local build is possible in the assistant's environment.** There is no Android SDK, `dl.google.com`/`maven.google.com`/Maven Central are blocked by the proxy, and there is no ktlint. **CI is the only compiler.** A green run takes 15 to 23 min (the last 15 runs). A failed run usually means one more full cycle.
- The local clone is **shallow**: `git rev-list --count HEAD` gives 1173 here, while CI (with `fetch-depth: 0`) gets about 10873. In `git blame`, the boundary commit `^ccd6032a` (AntsyLich, 2025-04-01) means "older than the shallow history", **not** "written by AntsyLich". Upstream code shows up with that hash.
- The repo is `Dominican4Life/Komikku`. The default branch is `master` and the work branch is `feature/browse-redesign`. The repo is **private** right now (the API says `private: true`), which matters for runner size and artifact quota (see below).

### Module layout: what goes where

| Module | Contents | Put here |
|---|---|---|
| `app/` | All UI (`eu.kanade.presentation.*` composables, `eu.kanade.tachiyomi.ui.*` screens/models), DI (`eu/kanade/tachiyomi/di/AppModule.kt`, `eu/kanade/domain/DomainModule.kt`, `KMKDomainModule`, `SYDomainModule`), workers, backup, downloads, the player (`ui/player/`), JS/JSON sources (`eu/kanade/tachiyomi/jssource/`, editor in `ui/codeeditor/`), `exh/`, `mihon/` | Almost all fork features. Anything that touches Android UI, Compose, Coil, mpv or QuickJS |
| `domain/` | Interactors (`tachiyomi/domain/**/interactor/`), models, repository interfaces, `*Preferences` services (e.g. `HistoryPreferences`) | Pure logic and preference definitions shared by app and data |
| `data/` | SQLDelight (`data/src/main/sqldelight/tachiyomi/data/*.sq`, `migrations/N.sqm`), `*RepositoryImpl`, `mihon/data/extension/**` (extension stores) | DB tables/queries, repo implementations, store parsing |
| `core/common` | `eu/kanade/tachiyomi/network/*` (NetworkHelper, interceptors, DoH, NetworkPreferences), preferences (`tachiyomi.core.common.preference`), `exh/log/EHLogLevel.kt`, `tachiyomi.core.common.util.system.logcat` | Networking, preference store helpers, shared utilities with no UI |
| `core/archive`, `core-metadata` | CBZ/archive reading, ComicInfo | Rarely touched |
| `source-api` | Extension API: `eu.kanade.tachiyomi.source.*` (manga) **and** `eu.kanade.tachiyomi.animesource.*` (anime, added by the fork in 39f455aa "Anime stage 1"), e.g. `animesource/model/Video.kt` | Only ABI-compatible changes. Installed extensions link against these classes |
| `source-local` | Local source | |
| `presentation-core` | Shared Compose pieces (`tachiyomi.presentation.core.*`, e.g. `components/material/Constants.kt` `MaterialTheme.padding`) | Reusable composables |
| `presentation-widget` | Glance widget | |
| `i18n` (`MR`) | Mihon strings | Never add fork strings |
| `i18n-sy` (`SYMR`) | TachiyomiSY strings | Never add fork strings |
| `i18n-kmk` (`KMR`, `tachiyomi.i18n.kmk.KMR`) | Komikku strings | **New fork strings go here** (base folder only) |
| `i18n-ank` (`AMR`, package `tachiyomi.i18n.ank`) | Strings ported from Aniyomi/AniZen. **Fork-added** in 23ba5816 (Anime stage 3) | Only strings copied from Aniyomi/AniZen code; new wording goes in KMR |
| `flagkit`, `telemetry`, `macrobenchmark` | Flags; Firebase (no-op without `-Pinclude-telemetry`); benchmarks | |
| `buildSrc/` | Convention plugins `mihon.android.application`, `mihon.code.lint` (Spotless), `mihon.buildlogic.{AndroidConfig,BuildConfig,Commands}` | |

Version catalogs: `gradle/libs.versions.toml`, `androidx.versions.toml` (**AGP `agp_version = "8.13.2"`**), `kotlinx.versions.toml` (Kotlin 2.4.0), `compose.versions.toml`, `sy.versions.toml`, and the fork's `aniyomi.versions.toml` (catalog `aniyomilibs`, added in 23ba5816). Gradle wrapper 9.3.1. CI uses JDK 21 Temurin (`.github/.java-version`) and targets JVM 17 (`AndroidConfig`).

Dependency direction is `app → domain → source-api`, and `data` implements the `domain` interfaces. `data` cannot see `app`. A data class in `data/` that gains a constructor parameter breaks every caller at compile time (see r64 below).

**DB migrations 47.sqm–57.sqm are all fork-added** (47 = anime stage 1 … 57 = r57). If upstream Komikku is ever merged, its next migration will also be `47.sqm`. That is a guaranteed conflict and must be renumbered by hand.

### Build variants and version numbers

`app/build.gradle.kts`:

- `defaultConfig`: `applicationId = "app.komikku"`, **`versionCode = 81`, `versionName = "1.14.1"`** (upstream values, never bumped by the fork). `BuildConfig.COMMIT_COUNT = git rev-list --count HEAD`, `COMMIT_SHA = git rev-parse --short HEAD`, and `BUILD_TIME` (`buildSrc/.../Commands.kt`).
- `preview` (what CI builds with `assemblePreview`) = `initWith(release)` plus `applicationIdSuffix = ".beta"` → **`app.komikku.beta`**, `versionNameSuffix = "-<commitCount>"` → e.g. `1.14.1-10873`, `signingConfig = debug.signingConfig`, and resources from `app/src/beta/res` (launcher icon colour `#c837ab`). Because it inherits from `release`, the preview is **R8-minified and resource-shrunk** (`Config.enableCodeShrink` is true unless `-Pdisable-code-shrink`). Any new library reached through JNI or reflection needs a keep rule in `app/proguard-rules.pro`. The fork added rules for `is.xyz.mpv.**`, `com.arthenica.**` (FFmpegKit), `com.dokar.quickjs.**`, `com.squareup.zstd.**`, `eu.kanade.tachiyomi.torrentutils.**` and Coil service loaders.
- `debug` (`.dev`), `release`, `releaseTest` (`.rt`), `foss` (`.foss`), `benchmark`: not built by CI.
- ABI splits: armeabi-v7a, arm64-v8a, x86, x86_64 + universal. **CI publishes only `arm64-v8a` and `universal`.**
- The `quickjs-kt` native lib is repackaged as `libquickjskt.so` by the `unpackQuickjsKt` task, wired to `preBuild`, so that it doesn't clash with extensions' `libquickjs.so`. Don't rename it.

Three different numbers describe one build. Don't mix them up:

| Number | Example | Where it comes from | Where it shows |
|---|---|---|---|
| Release tag | `r84` | CI: newest `rN` release + 1, computed **at publish time** | GitHub release, APK file name `Komikku-beta-arm64-v8a-r84.apk` |
| Commit count | `10873` | `git rev-list --count HEAD` with full history | About screen shows **"Beta r10873"** (`AboutScreen.kt:374`), versionName suffix, crash log "Build version" |
| versionCode | `81` | hard-coded | Package manager. The same for every build, so Android accepts each one as an "update" (only the signature can stop it) |

Consequences:
- The owner's About screen says `r10873`, not `r84`. To map an installed build to a release, compare the commit sha (release notes say "Built from branch … at <sha>") or the commit count.
- **Preference/app migrations (`mihon/core/migration`, run by `Migrator.initialize(new = BuildConfig.VERSION_CODE)` in `App.kt`) never re-run between fork builds**, because versionCode stays 81. For any one-time fork data move, use a "done" flag or check for the old data's existence, as `jssource/JsLegacy.kt` does (it moves old folders and prefs once and logs `js` W on failure). Don't add a `Migration` with `version = 82f`: it would never run.
- The preview "What's new" dialog (`MainActivity.kt` ~424) triggers when `COMMIT_COUNT` > the stored `preview_last_version_code`.
- Every screen change in a preview build logs an **ERROR** line `ScreenTransition: <key>` (`eu/kanade/presentation/util/Navigator.kt:101`, upstream code). These are not errors. Ignore them when you filter Logs by Problems.

### `.github/workflows/build_custom.yml`, step by step

The only workflow; the fork deleted all upstream workflows in 0e53ee05.

1. **Triggers**: `push` to `master` or `feature/**`, plus `workflow_dispatch`. `paths-ignore: '**.md'` means a push whose changed files are *all* `.md` creates no run at all. A push mixing `.md` and code does build. A commit message containing `[skip ci]` is also skipped (GitHub built-in). The fork uses that for `WIP rN:` commits (e.g. 5fb7b0f4, 9f786264).
2. **Concurrency**: group `workflow-ref`, `cancel-in-progress: true`. A new push to the same branch **cancels** the running build. For example, r83 run 40628267250 (find bar only) was cancelled when the download changes were pushed on top, and run 40628628894 built both. A push to a *different* branch (master vs feature/browse-redesign) runs in parallel.
3. `runs-on: ubuntu-24.04`. Actions are pinned by SHA (checkout v7.0.0 with `fetch-depth: 0` so the commit count is right, setup-java v5.4.0 with Java 21 Temurin, setup-gradle v6).
4. **"Use fixed debug signing key"**: `mkdir -p ~/.android; cp .github/custom-debug.keystore ~/.android/debug.keystore`. This step succeeds, but AGP does not sign with that key; see the next section.
5. **"Build app"**: `set -o pipefail`, then up to 4 tries of `./gradlew assemblePreview --stacktrace $extra 2>&1 | tee build.log`. After a failure it greps the log for `Could not resolve|Could not find|Could not GET|Read timed out` (flaky JitPack downloads). If none of these match, it exits at once (compile errors are not retried). If one matches, it sleeps 45 s and retries with `--refresh-dependencies`, because Gradle caches failed lookups (7f6db7b1, 88659f9e).
6. **"Report build errors"** (only on failure): it greps `build.log` for `^e: |^ERROR|FAILED$|\.sqm?[:(]|\.sq line|sqldelight` plus 20 lines after "What went wrong", keeps the first 120 lines (or `tail -80` if nothing matched), and emits **one `::error title=Build errors::` annotation**. What you see in practice:
   - The first ~4 lines of every annotation are a harmless `at app.cash.sqldelight.gradle.SqlDelightPlugin.setupSqlDelightTasks…` stack trace, matched by the `sqldelight` pattern. Skip them. The real errors are the `e: file:///home/runner/work/Komikku/Komikku/<path>:<line>:<col> <message>` lines.
   - **Annotations are cut at about 4 KB.** Two past annotations (r32 run 38472407337, r67 run 40223378511) were exactly 4097 bytes, so more errors existed than were shown. If the annotation is near 4 KB, assume more errors exist and re-check every file you touched, not only the ones listed.
7. **"Upload APKs"**: an artifact kept 3 days, with `continue-on-error: true`. A private repo hits the artifact storage quota. Successful runs then carry a *failure* annotation "Failed to CreateArtifact: Artifact storage quota has been hit". That one is harmless; the release is where the APKs live (9bab11cf).
8. **"Publish release"**: `last = highest rN among release tags` (via `gh release list`, which works inside Actions), `tag = r(last+1)`. It copies `app-arm64-v8a-preview*.apk` and `app-universal-preview*.apk` to `Komikku-beta-<abi>-<tag>.apk` and runs `gh release create <tag> --target <sha> --title "Komikku beta <tag>" --prerelease`.
   - Failed or cancelled runs **don't use up a number**. That is why fix commits keep the same prefix ("r81: missing import" after the r81 build failed).
   - The `rN` in a commit message is only the assistant's *prediction*.
   - Race: two branches building at once both compute the same tag, and the second `gh release create` fails.
   - History: numbering was first `custom-r(run_number+86)`, then `custom-rN`, then `rN` (24364c57, 8bd6ae9b).

Runner memory: on 2026-10-10 the repo was private and builds were killed for lack of memory. 402cc16a added a 12 GB swap file, `--max-workers=2` and in-process Kotlin compilation. 2f068409/24364c57 removed all of that again ("the repo is public again… the swap file filled the disk"). The repo is **private again now**, and builds still pass in about 20 min. If a run dies with "Gradle daemon disappeared", exit code 137 or "Killed", look at 402cc16a first.

### Signing: why every release had a different certificate (fixed in r85)

> **r85:** fixed with an explicit `signingConfigs.debug` (see README, "Changes in r85"). CI now checks the certificate after the build. The text below is the history.


**Symptom (verified).** r60, r71, r83 and r84 are each signed with a different certificate (r84 SHA-256 `6fe1b84b…`, r71 `44a7175e…`). The committed keystore `.github/custom-debug.keystore` is `2C:43:CB:4F:…`. So a new beta can't install over the previous one ("package conflicts / signatures don't match") without uninstalling, which loses app data.

**What the certificates show (verified by dumping the v2 signing block of `r71.apk`/`r84.apk`).** Both are `CN=Android Debug, O=Android, C=US`, **serial `01`, valid 30 years**, with `notBefore` a few minutes after the release commit (r84: 2026-10-28 11:12:28 UTC, commit 11:08). That is the key AGP creates itself when no debug keystore exists. The committed keystore is different: serial `fc4a147e2e0f8a15`, valid 2026-10-08 → 2054-02-23 (10000 days, `keytool` style), alias `androiddebugkey`, PKCS12, store/key password `android`. **AGP generated a fresh debug keystore on the runner during every build**, which means it did not find a file where it looked.

**How AGP finds the debug keystore.** `preview` uses `debug.signingConfig`, AGP's built-in `debug` signing config. Its `storeFile` defaults to `<Android prefs dir>/debug.keystore`. `ValidateSigning<Variant>` creates that file if it is missing. The prefs dir is `ANDROID_USER_HOME` if set (older names: `ANDROID_PREFS_ROOT`, and `ANDROID_SDK_HOME` as the *parent* of `.android`), otherwise `<user.home>/.android` (Android docs: "ANDROID_USER_HOME … Defaults to `$HOME/.android/`").

**What was ruled out:**
- *Runner env vars*: the ubuntu-24.04 image (`actions/runner-images`, `install-android-sdk.sh` and the Ubuntu2404 readme) sets only `ANDROID_HOME`, `ANDROID_SDK_ROOT` and the `ANDROID_NDK*` variables, not `ANDROID_USER_HOME`, `ANDROID_PREFS_ROOT` or `ANDROID_SDK_HOME`. So the expected path is `/home/runner/.android/debug.keystore`, exactly where the step copies it.
- *The step failing*: the job API shows step 5 "Use fixed debug signing key: success" in run 40677590024 (r84).
- *A corrupt keystore*: `keytool -list -storepass android` reads the committed file fine, and the blob hash matches the working copy.

**Still unknown.** The CI log would show it (`ls -la ~/.android` after the build, or AGP's "Creating debug keystore" style lines), but job logs redirect to Azure blob storage, which the proxy blocks here. So the exact reason AGP 8.13.2 doesn't pick up the copied file is **not confirmed**. Remaining candidates:
- (a) The Gradle daemon resolves `user.home`/the prefs dir differently from the shell's `~`. Another project hit the same symptom and blamed this (DazBot13-boopt/dazdownloader PR #20).
- (b) Some AGP 8.x behaviour around the *default* debug keystore that treats the copied file as unusable and makes a new one.

Either way, the fix is the same.

**Proper fix (not applied, by instruction).** Don't depend on where AGP looks. Give the preview an explicit signing config that points at the committed file:

```kotlin
// app/build.gradle.kts, inside android { }, before buildTypes
signingConfigs {
    // KMK: one fixed key for CI betas, so each release installs over the previous one
    create("beta") {
        storeFile = rootProject.file(".github/custom-debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
}
// in create("preview") { … }
signingConfig = signingConfigs.getByName("beta")
```

Then:
1. Remove (or keep, harmless) the copy step.
2. Add a CI check after the build so this can never silently come back: `"$(ls -d $ANDROID_HOME/build-tools/*/ | sort -V | tail -1)apksigner" verify --print-certs app/build/outputs/apk/preview/*universal*.apk | grep -qi "SHA-256 digest: 2c43cb4f1bca57c8"`, and fail the job otherwise.
3. Add `*.keystore binary` and `*.jks binary` to `.gitattributes`. Today `* text eol=lf` marks the keystore as **text**, so a future keystore containing CR LF bytes would be corrupted on commit. The current blob happens to be fine.
4. Tell the owner: the first fixed-key build needs **one uninstall** of the current beta. Back up first (Settings › Data and storage › Create backup). After that, every release updates in place.

### Release flow the assistant uses

GitHub GraphQL is blocked from these sessions ("GitHub GraphQL is not available from Claude Code sessions; use the REST API"), so `gh release …`, `gh run …` and `gh pr …` are unreliable. Use `gh api` REST calls. `gh auth status` reports an invalid token, but `gh api` still works through the proxy. Job **logs** (`/actions/jobs/<id>/logs`) can't be read (blob storage blocked); **annotations can**.

1. Check the branch: `git branch --show-current`. Never push to `master` unless the owner asks.
2. Commit with a message of the form `rN: <user-visible summary>`, a body of bullets saying what and why, and the trailer:
   ```
   Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_<id>
   ```
   Use `WIP rN: … [skip ci]` for partial work that must not build.
3. Push and find the run:
   ```bash
   git push -q origin HEAD:feature/browse-redesign
   sleep 8
   gh api "repos/Dominican4Life/Komikku/actions/runs?branch=feature/browse-redesign&per_page=1" \
     --jq '.workflow_runs[0] | "\(.id) \(.status) \(.head_sha[0:7])"'
   ```
   Check that `head_sha` equals `git rev-parse --short HEAD`. If not, the run isn't created yet, so wait and ask again.
4. Wait about 20 min. Run this in the background, because foreground `sleep` is blocked:
   ```bash
   until s=$(gh api repos/Dominican4Life/Komikku/actions/runs/$RUN --jq '.status+" "+(.conclusion//"")'); \
     [[ $s == completed* ]]; do sleep 30; done; echo $s
   ```
5. On `failure`, read the errors:
   ```bash
   JOB=$(gh api repos/Dominican4Life/Komikku/actions/runs/$RUN/jobs --jq '.jobs[0].id')
   gh api repos/Dominican4Life/Komikku/check-runs/$JOB/annotations \
     --jq '.[] | select(.title=="Build errors") | .message'
   ```
   `gh api ".../actions/jobs/$JOB" --jq '.steps[] | "\(.number) \(.name) \(.conclusion)"'` shows which step failed. "Build app" means compile; "Publish release" means a tag race or a missing APK. Fix the errors, commit as `rN: fix …` with the same N, and push.
6. On `success`, get the tag: `gh api "repos/Dominican4Life/Komikku/releases?per_page=1" --jq '.[0].tag_name'`. To download an APK: `id=$(gh api repos/Dominican4Life/Komikku/releases/tags/r84 --jq '.assets[]|select(.name|test("arm64"))|.id'); gh api -H "Accept: application/octet-stream" repos/Dominican4Life/Komikku/releases/assets/$id > r84.apk`. Note that a release's `created_at` is the *commit* time, not the build time.

### Repo rules (AGENTS.md) and where the fork deviates

From `AGENTS.md`:
- Work on a feature branch and never commit or push to `master`/`main` unprompted.
- Komikku strings go to `i18n-kmk` `base/` only (`KMR`). Never edit non-`base` locales (Weblate owns them).
- Run `spotlessApply` → `spotlessCheck` → `assembleDebug` before calling work done.
- Keep `// KMK -->`, `// SY -->` and `// EXH -->` blocks.
- Log with `logcat {}` / `xLog`, never `android.util.Log`.

Where the fork actually deviates, and what to do about it:
- **Build verification can't be done here** (no SDK, no Maven), and **CI does not run `spotlessCheck`** (only `assemblePreview`). Formatting is not gated anywhere. Keep to `.editorconfig` by hand: 4-space indent, trailing commas, no star imports, `max_line_length = 120`. Note that ktlint's `max-line-length` rule is *disabled* (`ktlint_standard_max-line-length = disabled`), so 120 is a convention the IDE shows, not a build failure. Long lines don't break the build, but keep them under 120 so a later `spotlessApply` diff stays small.
- **Strings.** Ported Aniyomi/AniZen code uses `AMR` (`i18n-ank`, fork-added). Much new fork UI uses **hard-coded English** literals, e.g. `UnifiedExtensionsScreen.kt:671` "No signing fingerprint…" and `ExtensionStoresDialogs.kt:225` "Signing key", plus the "Sync"/"Logs" labels in `HomeScreen.kt`. That is a deliberate shortcut (the owner reads English), but new strings should preferably go to `KMR`. Removing a `KMR` string without removing its uses fails the build, and so does the reverse.
- **Markers.** Fork code uses `// KMK:` one-line comments, `// KMK -->` blocks, and `// ANZ -->` for AniZen ports (e.g. `torrentClient` in `NetworkHelper`). Upstream Komikku also uses `// KMK`, so decide "fork or upstream" with `git log --author='Claude\|Dominican4Life\|OmfgItsJesus'`, not by the marker.
- **Branches.** Day-to-day pushes go to `feature/browse-redesign`. `master` exists and also builds.

### Logging conventions

- **`AppLog`** (`eu/kanade/tachiyomi/util/system/AppLog.kt`, fork-added in 4735057d) is the in-app log behind the **Logs** screen (`eu/kanade/tachiyomi/ui/logs/LogsScreen.kt`).
  - It keeps the last `MAX_LINES = 4000` lines in memory and in `filesDir/app_log/log.txt`. Lines from earlier runs show with a negative time.
  - Format: `+12.345s W tag message`. Filters: All / Info (I, W, E) / Problems (W, E), plus a text search on message and tag. Copy trims to what fits; Share writes a file.
  - `E` lines are written synchronously so they survive a crash.
  - **Entry point:** the only button is on the **tablet navigation rail** (`HomeScreen.kt:139`, bug icon at the bottom, below "Sync"). The phone bottom bar has none. On a phone, set Appearance › Tablet UI (`tablet_ui_mode`) to always on to reach Logs.
- **What reaches AppLog** (wired in `App.kt` ~159–173 and `setupExhLogging()`):
  - Everything sent through square `logcat {}` at DEBUG and up, via `AppLog.Logger`, which is installed *before* `XLogLogcatLogger` so duplicates can be dropped.
  - XLog (`xLogD/xLogE`), via `AppLog.XLogPrinter`, but XLog's level is **WARN** unless Settings › Advanced › Log level (`eh_log_level`, SY pref; default MINIMAL for preview, because only `BUILD_TYPE == "debug"` defaults to EXTRA) is EXTRA or EXTREME. So `xLogD` from fork code is invisible by default. **Use `logcat` or `AppLog.add` in fork code.**
  - Crashes, via `GlobalExceptionHandler` → `logcat(ERROR)`.
- **How to log:**
  - `AppLog.add('W', "hls", "…")` writes a line with an explicit short tag, and only to the in-app log (not Android logcat). The fork uses it for owner-facing events.
  - `logcat(LogPriority.X) { "…" }` from `tachiyomi.core.common.util.system` is an **extension on `Any`**. Its tag is the caller's class name, and its `tag =` parameter only adds a `[tag] ` *prefix to the message*. Inside a top-level `@Composable` there is no `this`, so call `context.logcat(...)` (84935397). Alternatively use square's `logcat.logcat("tag", priority) { }`, which takes a real tag (that is what the `net` line uses).
- **Tags in use**:
  - Explicit `AppLog.add` tags: `net` (D, via logcat), `hls`, `js`, `extensions`, `Sideload`, `botcheck`, `anilist`, `Memory` (`MemoryLog.kt`, including "Memory low … cleared …" W lines), `Restore`, `sync`, `reader`, `app` (the `start app.komikku.beta 1.14.1-10873 on Android … (SDK …), <maker> <model>` line at each launch).
  - JS sources log through `JsRuntime.kt:142` with their own tags.
- Mihon's `verboseLogging` preference is **commented out** in Komikku (`NetworkPreferences`). Ported Aniyomi code that calls `networkPreferences.verboseLogging()` won't compile (7d617511). Use `EHLogLevel.isExtraLogging()` instead.

### Networking basics

`core/common/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt`. It is one `NetworkHelper` from Injekt. `clientBuilder()` is a **function** (KMK), so every client gets its own builder.

- **Timeouts** (seconds): connect 30, read 30, call 120. `torrentClient` (ANZ, 1546a765): read 10 min, no call timeout. `clientWithTimeOut()` is for `downloadFileWithResume` (5 tries, Range resume, exponential backoff up to 32 s).
- **Interceptor order** (outermost first):
  1. `UncaughtExceptionInterceptor`
  2. `UserAgentInterceptor(defaultUserAgentProvider)`
  3. the **`net` request logger** (fork, 4735057d)
  4. a `HttpLoggingInterceptor(HEADERS)` network interceptor, only with EXTRA logging
  5. then, on `client` only, `BotCheckInterceptor` (fork, ba9254b9, placed *before* Cloudflare so it only sees checks the helper couldn't pass), `CloudflareInterceptor`, and the MangaDex covers header hack.

  Because the logger is outside BotCheck/Cloudflare, one `net` line covers the whole call including any Cloudflare solving: good `D net GET https://… -> 200 in 412 ms`, bad `W net GET https://… -> 403 in …` or `W net GET … failed: SocketTimeoutException: …`.
- **`nonCloudflareClient`** (fork, e14a5052) is the same client without the Cloudflare/BotCheck interceptors. Anime extensions use it.
- **Cache**: 5 MiB `network_cache` in cacheDir.
- **DoH**: `doh_provider` (default `-1`, off). Settings › Advanced › DNS over HTTPS, 12 providers in `DohProviders.kt`. It **needs an app restart**, because `client` is `by lazy`.
- **IPv4-first DNS** (fork, aa5d1710, r82): after the DoH choice, `builder.dns(...)` wraps the resolver (system or DoH) and sorts `Inet4Address` first. The reason: on networks where IPv6 doesn't get through, hosts with an AAAA record (jikan.moe) waited out the connect timeout, 5 s and then failed. Keep this wrapper *after* the DoH `when`, because it reads `builder.build().dns`.
- **User agent**: `default_user_agent`, default `Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36`, editable in Settings › Advanced.
- **`HlsRelay.bypassClient`** (`eu/kanade/tachiyomi/jssource/HlsRelay.kt`) is `network.client.newBuilder().dns(...)` with its own Cloudflare/Google DoH. It is used when a host resolves to `0.0.0.0`/loopback (ad-block DNS) and logs `W hls … is blocked by this device's DNS or ad blocker…`. It **replaces** the DNS, so it does *not* get the IPv4-first ordering.
- Other clients built from scratch, without these interceptors or DNS settings: `DiscordWebsocket`, `WebDavSyncService`, `SyncYomiSyncService`, `exh/uconfig/EHConfigurator`.

### Compile errors from fork history and the patterns behind them

Taken from the "Build errors" annotations of the 25 failed runs, plus the fix commits (`5fdcde5d`, `7d617511`, `03d3b54d`, `84935397`, `f7abcdbd`, `c80868cc`, `1c15d291`, `87b66486`, `2d7b748d`, `3370dee4`, `76a264d6`, `57ff3b63`, `a2741c97`, `67f10965`, `9dbef2ba`, `d4095f30`, `301d601a`, `154bede3`):

| Pattern | Real cases | Rule |
|---|---|---|
| **Missing import** of a Compose modifier or helper used in new code | `fillMaxSize` (r81, `AutoCompleteItem.kt:431`); `rememberScreenModel` (r67, `AnimeLibraryTab.kt`, which cascaded into "Property delegate must have getValue", "Cannot infer type T", and ambiguous `component1()`); `calculateStart/EndPadding` (03d3b54d); `getAndSet` (`tachiyomi.core.common.preference.getAndSet`, AnimeIncognito); `logcat` (AnimeTrackSheet) | For every identifier you add, check the file's import list. One missing import can produce 8+ follow-on errors; fix the first `Unresolved reference` and the rest usually go away |
| **Wrong package** in an import | `androidx.compose.foundation.layout.horizontalScroll` → `androidx.compose.foundation.horizontalScroll`; `…layout.selection.selectable` → `androidx.compose.foundation.selection.selectable` | `horizontalScroll`/`verticalScroll` live in `foundation`; `selectable`/`toggleable` in `foundation.selection`; padding/size/insets in `foundation.layout` |
| **Importing a member** as if it were top-level | `import tachiyomi.core.common.preference.getObjectFromString` (it is a `PreferenceStore` member; 1c15d291) | Members are called on the receiver, not imported |
| **Fully-qualified extension property** | `WindowInsets.statusBars` written as `androidx.compose.foundation.layout.WindowInsets.statusBars` → "Unresolved reference 'statusBars'" (r60) | Extension properties can't be reached through a qualified receiver. Import them, or use `Modifier.statusBarsPadding()` |
| **Name clash between same-named extensions** | `MigrationStart.request.collectAsState()` picked the fork's `Preference.collectAsState` (r30) and was fixed with `import androidx.compose.runtime.collectAsState as collectFlowAsState`; `AnimatedVisibility` inside a Column/Row scope picked the scoped overload (fixed with the fully-qualified `androidx.compose.animation.AnimatedVisibility`) | When a file already imports `tachiyomi.presentation.core.util.collectAsState`, collect Flows with an alias. Inside `ColumnScope`/`RowScope`, `AnimatedVisibility` resolves to the scope version |
| **`Any.logcat` without a receiver** | top-level composable (84935397) | Use `context.logcat` or `logcat.logcat("tag")` |
| **Lambda type inferred from its last expression** | `{ if (…) context.toast(a) else context.toast(b) }` became `() -> Toast` where `(() -> Unit)?` was expected (LibraryTab, d4095f30); `val openInBrowser = { … }` (c80868cc) | Declare the type (`val f: () -> Unit = { … }`) or end the lambda with `Unit` |
| **`+=` on collections** | `out += result.pagePreviews` where `out` is a `val MutableList` → "'val' cannot be reassigned / List vs MutableList" (r77); `elements += element` (c80868cc) | Use `.add()`/`.addAll()` on `val` mutable lists |
| **New enum entry, non-exhaustive `when`** | `DownloadAction.ALL_CHAPTERS` broke `AnimeScreenModel.kt:331` and `LibraryScreenModel.kt:900` (87b66486) | Before adding an enum entry, grep every `when (` over that enum in **both** manga and anime code |
| **Removed or renamed parameter, stale call sites** | `onClickRefresh` / `onClickSourceSettings` on the anime toolbar (87b66486); JS tab params (ab0aaa0a) | Grep all callers of a composable whose signature you change. Manga and anime twins often share components |
| **Constructor parameter without a default** | `discord` added to a data class: "No value passed for parameter 'discord'" in `data/…/AniyomiNetworkExtensionStore.kt` (r64) | Give new constructor parameters a default, or update every call site (including in `data/`) |
| **Duplicated declarations after an edit** | `BackupNotifier.kt` "Conflicting overloads" ×6, which cascaded into "Overload resolution ambiguity" in BackupCreateJob/BackupRestoreJob/BackupRestorer (r32; 9dbef2ba removed 72 duplicated lines) | After a large replace, check that the old body is gone |
| **Code inserted in the wrong place** | a helper inserted into the middle of a call chain: "Condition type mismatch: String vs Boolean", "Unresolved reference 'removePrefix'" (r75, `ExtensionStoreService.kt:260`, 154bede3) | Re-read the edited region in context |
| **Partial revert** | "back to r63" restored a file that used `oldCopiesCleared`, which the revert didn't bring back (r77, `AnimatedThumbnail.kt:72`) | After `git checkout <old> -- file`, check the file's references to top-level declarations in other files and in the same file |
| **Nullability or type drift** | `String?` passed as `String` (r71, `AnimatedThumbnail.kt:191`); `Map<String, Any?>` vs `RawJson` (`SumizuriRuntime.kt:370`); `chapter.mangaId` vs the SQLDelight column `manga_id` (301d601a) | Check the actual type and field names (SQLDelight-generated classes use the column names) |
| **APIs that don't exist in Komikku** in ported Aniyomi/AniZen code | `networkPreferences.verboseLogging()`, `MaterialTheme.padding.mediumSmall` (added to `presentation-core/.../Constants.kt`), `enabled =` param (7d617511) | Ported code compiles against Aniyomi; check each symbol exists here |
| **`Video.copy` overloads** (`source-api/.../animesource/model/Video.kt`) | not a past failure, but a trap | There are **three** `copy`s: (1) the 15 constructor fields, which keeps `type`, `mimeType` and `videoPageUrl` from `this`; (2) the same plus `type`, `mimeType`, `videoPageUrl`; (3) the hidden ext-lib-16 one. A call naming only common fields resolves to (1). Pass `videoPageUrl=`/`type=`/`mimeType=` to get (2). Don't add a fourth overload with the same parameter prefix, or every existing `copy(...)` call becomes ambiguous |
| **Conditional `remember`** | avoided in r83 rather than hit | `if (x) remember { … }` compiles, but shifts the composition slots when `x` changes and resets state. Call `remember` unconditionally and branch on its result |

### Settings in this area

| Setting (as shown) | Preference key | Default | What it changes | Fork-added? |
|---|---|---|---|---|
| Advanced › DNS over HTTPS | `doh_provider` | `-1` (off) | Resolver for `NetworkHelper` clients (restart needed); the IPv4-first wrapper sits on top | no (IPv4 wrapper: yes, r82) |
| Advanced › Default user agent | `default_user_agent` | Chrome 141 Android 10 string | `UserAgentInterceptor`, Cloudflare/WebView | no |
| Advanced › auto-open bot checks (`KMR.pref_auto_open_bot_checks`) | `auto_open_bot_checks` | `true` | Opens unpassable bot checks in WebView right away and retries | yes (3db8eea1) |
| Advanced › Log level (`SYMR.log_level`) | `eh_log_level` | MINIMAL (EXTRA on debug builds) | XLog level (WARN/DEBUG/ALL), `HttpLoggingInterceptor` headers, logcat min priority | no (SY) |
| Appearance › Tablet UI | `tablet_ui_mode` | AUTOMATIC | Whether the navigation rail, and with it the only **Logs** button, is shown | no |
| (internal) | `preview_last_version_code` (app-state key) | 0 | Preview "What's new" dialog when the commit count grows | no (KMK upstream) |

### Cross-feature impact map

- Changing **`versionCode`** affects app migrations (`Migrator`): bumping it to 82+ re-arms every migration with `version > old`. Leaving it alone means new `Migration`s never run.
- Changing **NetworkHelper `clientBuilder()`** affects every source, extension, cover, the JS `fetch`, HLS relay, trackers and the AniList calls, because they all derive from `network.client`/`nonCloudflareClient`. The `net` log lines and IPv4-first behaviour come from here too.
- Changing the **DNS wrapper** affects `HlsRelay.bypassClient`, which overrides DNS and silently drops the wrapper.
- Changing **interceptor order** affects bot-check notifications (`BotCheckInterceptor` must stay before `CloudflareInterceptor`) and what the `net` timing means.
- Changing **`source-api` classes** (`Video`, `Hoster`, `SManga`…) affects installed APK extensions: ABI breaks show up only at runtime as `NoSuchMethodError` in Logs, never at compile time.
- Changing **R8 keep rules or adding JNI/reflective libraries** affects only preview/release builds (minified). A missing rule passes CI and crashes on the device.
- Changing **`.github/custom-debug.keystore` or the signing config** means every installed beta needs one uninstall.
- Changing **i18n modules**: a string removed from `KMR`/`AMR` that is still referenced is a compile error in `app`.
- Changing **the workflow's error grep** affects the only way to read failures from these sessions (annotations).

### Before you push (checklist)

Each item is tied to a past failure. With no local compiler, this list is the compile check.

1. **Imports**: for every new identifier in each touched file (`fillMaxSize`, `rememberScreenModel`, `getValue`/`setValue` for `by remember`, `collectAsState`, `logcat`, `getAndSet`, padding helpers…), confirm a matching `import` is present and the package is right (`foundation.horizontalScroll`, `foundation.selection.selectable`, `foundation.layout.*`). Quick check: `git diff -U0 | grep '^+' | grep -o '\b[a-z][A-Za-z]*(' | sort -u`, then grep the file's imports for each.
2. **No fully-qualified extension properties** (`WindowInsets.statusBars` etc.) and no imports of class members.
3. **Signatures changed?** Grep every caller in `app/` *and* `data/`, in both the manga and anime twins (`ui/anime/*` vs `ui/manga/*`, `AnimeLibraryTab` vs `LibraryTab`). New constructor parameters get defaults.
4. **Enum entry added?** `grep -rn "when (.*<EnumName>\|is <EnumName>\.\|<EnumName>\.[A-Z_]* ->"` and cover every `when`.
5. **Lambdas passed as `() -> Unit`** don't end in an `if/else` or a call that returns a value (`toast`, `add`, `launch`…). Declare the type or end with `Unit`.
6. **`val` mutable collections** use `.add/.addAll`, not `+=`.
7. **After big replaces or reverts**: no duplicated function bodies, no code dropped into the middle of a call chain, and no references to declarations the revert removed. Re-read every edited hunk in context.
8. **Ported Aniyomi/AniZen code**: each referenced symbol exists in this repo (`grep -rn "fun verboseLogging\|val mediumSmall"`, etc.).
9. **`Any.logcat` inside top-level composables** has a receiver (`context.logcat`).
10. **Compose**: no `remember` inside `if`/`when`/loops, no `@Composable` calls from non-composable lambdas (run 38160810294, `SettingsAppearanceScreen.kt:261`, which followed an unresolved `DateFormats`; fixed in ab0aaa0a), and no `collectAsState` name clash.
11. **Strings**: new fork strings go in `i18n-kmk/src/commonMain/moko-resources/base/strings.xml` only, and anything removed there is no longer referenced (87b66486 removed `anime_no_source_settings` together with its use).
12. **DB**: a new table or column needs a new `data/src/main/sqldelight/tachiyomi/migrations/<next>.sqm` (next after 57) matching the `.sq` change, and Kotlin must use the generated column names (`manga_id`).
13. **New native or reflective library**: add an R8 keep rule (preview is minified).
14. **Lines ≤ 120 chars**, trailing commas, 4-space indent (not enforced, but keeps a future `spotlessApply` diff small).
15. **Commit hygiene**: message `rN: …` plus the trailer, `[skip ci]` for WIP. Push only to `feature/browse-redesign`. Pushing again cancels the build in progress, so push the *complete* change once.
16. **After pushing**: confirm the run's `head_sha`. On failure, read the annotation, skip the SqlDelight stack-trace lines, and if it is about 4 KB assume more errors exist than are shown.
