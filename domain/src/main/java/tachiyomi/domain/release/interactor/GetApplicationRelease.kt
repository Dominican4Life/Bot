package tachiyomi.domain.release.interactor

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService
import java.time.Instant
import java.time.temporal.ChronoUnit

class GetApplicationRelease(
    private val service: ReleaseService,
    private val preferenceStore: PreferenceStore,
) {

    private val lastChecked: Preference<Long> by lazy {
        preferenceStore.getLong(Preference.appStateKey("last_app_check"), 0)
    }

    suspend fun await(arguments: Arguments): Result {
        val now = Instant.now()

        // Limit checks to once every 3 days at most
        val nextCheckTime = Instant.ofEpochMilli(lastChecked.get()).plus(2, ChronoUnit.DAYS)
        if (!arguments.forceCheck && now.isBefore(nextCheckTime)) {
            return Result.NoNewUpdate
        }

        // KMK -->
        val releases = service.releaseNotes(arguments)
            .filter {
                !it.preRelease &&
                    isNewVersion(
                        arguments.isPreview,
                        arguments.commitCount,
                        arguments.versionName,
                        it.version,
                    )
            }

        val latest = releases.getLatest() ?: return Result.NoNewUpdate
        // KMK <--

        lastChecked.set(now.toEpochMilli())

        // Check if latest version is different from current version
        val isNewVersion = isNewVersion(
            isPreview = arguments.isPreview,
            commitCount = arguments.commitCount,
            versionName = arguments.versionName,
            versionTag = latest.version,
        )
        return when {
            isNewVersion -> Result.NewUpdate(latest)
            else -> Result.NoNewUpdate
        }
    }

    // KMK -->
    // KMK: [includePreReleases] for the fork's own releases, which CI publishes as pre-releases
    // KMK (r90): [sinceReleaseNumber] is this build's number (tags "rN"): older releases are left out (What's new
    // used to list every release GitHub returned, about 30); null keeps them all. At most [limit] releases. The
    // result is the newest release, its info being the combined notes of all kept releases.
    suspend fun awaitReleaseNotes(
        arguments: Arguments,
        includePreReleases: Boolean = false,
        sinceReleaseNumber: Int? = null,
        limit: Int = Int.MAX_VALUE,
    ): Result {
        val releases = service.releaseNotes(arguments)
            .filter { (includePreReleases || !it.preRelease) && !it.draft }
            .filter { release ->
                if (sinceReleaseNumber == null) return@filter true
                val number = RELEASE_TAG_REGEX.matchEntire(release.version)?.groupValues?.get(1)?.toIntOrNull()
                number != null && number >= sinceReleaseNumber
            }
            .take(limit)

        // KMK (r90): one release whose body lists every kept release, newest first (GitHub's order), each under
        // its own "## rN" heading. Joined with "\n" (not getLatest()'s "\r", which the Markdown view may not take as
        // a line break, so the later headings ran into the first release's text).
        val newest = releases.firstOrNull() ?: return Result.NoNewUpdate
        val body = releases.joinToString("\n\n---\n\n") { "## ${it.version}\n\n${it.info.trim()}" }
        return Result.NewUpdate(newest.copy(info = body))
    }
    // KMK <--

    /**
     * [isPreview] is if current version is Preview (beta) build
     *
     * [versionTag] is the version of new release
     *
     * Release (stable) version will compare with current's [versionName] ("v0.1.2")
     *
     * Preview (beta) version will compare with current's [commitCount] ("r1234")
     */
    private fun isNewVersion(
        isPreview: Boolean,
        commitCount: Int,
        versionName: String,
        versionTag: String,
    ): Boolean {
        // Removes prefixes like "r" or "v"
        val newVersion = versionTag.replace("[^\\d.]".toRegex(), "")
        return if (isPreview) {
            // Preview builds: based on releases in "komikku-app/komikku-preview" repo
            // tagged as something like "r1234"
            newVersion.toInt() > commitCount
        } else {
            // Release builds: based on releases in "komikku-app/komikku" repo
            // tagged as something like "v0.1.2"
            val oldVersion = versionName.replace("[^\\d.]".toRegex(), "")

            val newSemVer = newVersion.split(".").map { it.toInt() }
            val oldSemVer = oldVersion.split(".").map { it.toInt() }

            oldSemVer.mapIndexed { index, i ->
                if (newSemVer[index] > i) {
                    return true
                }
                if (newSemVer[index] < i) return false
            }

            false
        }
    }

    data class Arguments(
        val isFoss: Boolean,
        /** If current version is Preview (beta) build */
        val isPreview: Boolean,
        /** Commit count of current version */
        val commitCount: Int,
        /** Current version name, could be version tag (v0.1.2) or commit count (r1234) */
        val versionName: String,
        /** Repository name */
        val repository: String,
        /** Force check for new update */
        val forceCheck: Boolean = false,
    )

    sealed interface Result {
        data class NewUpdate(val release: Release) : Result
        data object NoNewUpdate : Result
        data object OsTooOld : Result
    }
}

// KMK: the fork's release tags, "r90"
private val RELEASE_TAG_REGEX = Regex("^r(\\d+)$")

// KMK --.
internal fun List<Release>.getLatest(): Release? {
    return firstOrNull()
        ?.copy(
            info = joinToString("\r-----\r") {
                "## ${it.version}\r\r" +
                    it.info
            },
        )
}
// KMK <--
