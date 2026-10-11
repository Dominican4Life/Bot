package eu.kanade.tachiyomi.ui.library

import android.app.Application
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.core.preference.asState
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.anime.asDisplayManga
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeLibraryStats
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.data.anime.animeLibraryFilters
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.devicefiles.DeviceFilesIds
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.model.Source
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.random.Random

/**
 * KMK: The anime half of the library. It turns anime into the manga library's items, so the same grid, tabs and
 * badges show them, and applies the anime library's own filters, sorting, display settings and grouping (the
 * options Anikku's library has).
 */
class AnimeLibraryScreenModel(
    private val repository: AnimeRepository = Injekt.get(),
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val downloadManager: AnimeDownloadManager = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
    private val context: Application = Injekt.get(),
) : StateScreenModel<AnimeLibraryScreenModel.State>(State()) {

    private val searchQuery = MutableStateFlow<String?>(null)

    init {
        screenModelScope.launchIO {
            combine(
                libraryFlow(),
                libraryPreferences.animeLibraryFilters(basePreferences),
                displayFlow(),
                arrangementFlow(),
                searchQuery,
            ) { library, filters, display, arrangement, query ->
                val items = library.favorites
                    .filter { anime ->
                        filters.matches(
                            anime = anime,
                            stats = library.stats[anime.id],
                            categoryIds = library.categoryIds[anime.id].orEmpty(),
                            sourceName = sourceManager.get(anime.source)?.name,
                            downloadCount = downloadManager::countDownloads,
                        ) &&
                            (query.isNullOrBlank() || anime.title.contains(query, ignoreCase = true))
                    }
                    .map { it.toLibraryItem(library, display) }
                    .arranged(arrangement, library.favorites.associate { -it.id to it.nextEpisodeAiringAt })
                val groups = items.grouped(arrangement.group, library.categories, display.hiddenCategories)
                State(
                    isLoading = false,
                    isLibraryEmpty = library.favorites.isEmpty(),
                    hasActiveFilters = filters.isActive,
                    categories = groups.map { it.first },
                    items = groups.associate { (category, groupItems) -> category.id to groupItems },
                    itemCount = items.size,
                    userCategories = library.categories,
                    showCategoryTabs = display.categoryTabs,
                    showItemCount = display.itemCount,
                    showContinueButton = display.continueButton,
                )
            }.collectLatest { new ->
                mutableState.update { new.copy(activePage = it.activePage, dialog = it.dialog) }
            }
        }
    }

    fun search(query: String?) {
        searchQuery.value = query
    }

    fun setActivePage(page: Int) {
        mutableState.update { it.copy(activePage = page) }
    }

    fun getDisplayMode(): PreferenceMutableState<LibraryDisplayMode> =
        libraryPreferences.animeDisplayMode().asState(screenModelScope)

    fun getColumnsForOrientation(isLandscape: Boolean): PreferenceMutableState<Int> =
        (if (isLandscape) libraryPreferences.animeLandscapeColumns() else libraryPreferences.animePortraitColumns())
            .asState(screenModelScope)

    /** The episode the "continue watching" button plays: the earliest one not seen yet */
    suspend fun getNextUnseenEpisode(animeId: Long): Episode? =
        repository.getEpisodes(animeId)
            .filterNot { it.seen }
            .minWithOrNull(compareBy<Episode> { it.episodeNumber }.thenByDescending { it.sourceOrder })

    // Categories

    /** Opens the "set categories" dialog for an anime (with no categories yet, it offers to make some) */
    fun openChangeCategoryDialog(animeId: Long) {
        val categories = state.value.userCategories
        screenModelScope.launchIO {
            val current = repository.getCategoryIds(animeId)
            val selection = categories.map {
                if (it.id in current) CheckboxState.State.Checked(it) else CheckboxState.State.None(it)
            }
            mutableState.update { it.copy(dialog = Dialog.ChangeCategory(animeId, selection.toImmutableList())) }
        }
    }

    fun setCategories(animeId: Long, categoryIds: List<Long>) {
        screenModelScope.launchIO { repository.setCategories(animeId, categoryIds) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    // Building the library

    private fun libraryFlow(): Flow<Library> =
        combine(
            repository.subscribeFavorites(),
            repository.subscribeLibraryStats(),
            repository.subscribeCategories(),
            repository.subscribeLibraryCategoryIds(),
            // Emits when an episode file appears or disappears
            downloadManager.changes,
        ) { favorites, stats, categories, categoryIds, _ ->
            Library(favorites, stats, categories, categoryIds)
        }

    private fun displayFlow(): Flow<Display> =
        combine(
            listOf(
                libraryPreferences.animeDownloadBadge(),
                libraryPreferences.animeUnseenBadge(),
                libraryPreferences.animeLocalBadge(),
                libraryPreferences.animeLanguageBadge(),
                libraryPreferences.animeUseLangIcon(),
                libraryPreferences.animeSourceBadge(),
                libraryPreferences.animeCategoryTabs(),
                libraryPreferences.animeShowHiddenCategories(),
                libraryPreferences.animeCategoryNumberOfItems(),
                libraryPreferences.animeShowContinueWatchingButton(),
            ).map { it.changes() },
        ) {
            Display(
                downloadBadge = it[0],
                unseenBadge = it[1],
                localBadge = it[2],
                languageBadge = it[3],
                useLangIcon = it[4],
                sourceBadge = it[5],
                categoryTabs = it[6],
                hiddenCategories = it[7],
                itemCount = it[8],
                continueButton = it[9],
            )
        }

    private fun arrangementFlow(): Flow<Arrangement> =
        combine(
            libraryPreferences.animeSortingMode().changes(),
            libraryPreferences.animeGroupLibraryBy().changes(),
            libraryPreferences.randomSortSeed().changes(),
            libraryPreferences.sortTagsForLibrary().changes(),
        ) { sort, group, randomSeed, sortTags ->
            Arrangement(
                sort = sort,
                group = group,
                randomSeed = randomSeed,
                // Saved as "position|tag"
                sortTags = sortTags
                    .mapNotNull {
                        val parts = it.split("|")
                        (parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null) to
                            (parts.getOrNull(1) ?: return@mapNotNull null)
                    }
                    .sortedBy { it.first }
                    .map { it.second },
            )
        }

    private fun Anime.toLibraryItem(library: Library, display: Display): LibraryItem {
        val stats: AnimeLibraryStats? = library.stats[id]
        val animeSource = sourceManager.get(source)
        return LibraryItem(
            libraryManga = LibraryManga(
                manga = asDisplayManga().copy(lastUpdate = lastUpdate),
                // Anime in no category are in "Default", like manga
                categories = library.categoryIds[id].orEmpty().ifEmpty { listOf(Category.UNCATEGORIZED_ID) },
                totalChapters = stats?.total ?: 0L,
                readCount = stats?.seenCount ?: 0L,
                bookmarkCount = stats?.bookmarkCount ?: 0L,
                bookmarkReadCount = 0L,
                chapterFlags = 0L,
                latestUpload = stats?.latestUpload ?: 0L,
                chapterFetchedAt = stats?.fetchedAt ?: 0L,
                lastRead = stats?.lastSeen ?: 0L,
            ),
            downloadCount = if (display.downloadBadge) downloadManager.countDownloads(this).toLong() else 0L,
            unreadCount = if (display.unseenBadge) stats?.unseenCount ?: 0L else 0L,
            // Device files are this app's local anime source
            isLocal = display.localBadge && source == DeviceFilesIds.ANIME,
            sourceLanguage = if (display.languageBadge) animeSource?.lang.orEmpty() else "",
            useLangIcon = display.useLangIcon,
            source = if (display.sourceBadge) {
                Source(
                    id = source,
                    lang = animeSource?.lang.orEmpty(),
                    name = animeSource?.name ?: source.toString(),
                    supportsLatest = false,
                    isStub = animeSource == null,
                )
            } else {
                null
            },
        )
    }

    /** [airingAt] is when each item's next episode airs, by item id (0 when unknown) */
    private fun List<LibraryItem>.arranged(arrangement: Arrangement, airingAt: Map<Long, Long>): List<LibraryItem> {
        val sort = arrangement.sort
        if (sort.type == LibrarySort.Type.Random) return shuffled(Random(arrangement.randomSeed))

        val alphabetically = Comparator<LibraryItem> { a, b ->
            a.libraryManga.manga.title.lowercase().compareToWithCollator(b.libraryManga.manga.title.lowercase())
        }
        val byType = Comparator<LibraryItem> { a, b ->
            val anime1 = a.libraryManga
            val anime2 = b.libraryManga
            when (sort.type) {
                LibrarySort.Type.Alphabetical -> alphabetically.compare(a, b)
                LibrarySort.Type.LastRead -> anime1.lastRead.compareTo(anime2.lastRead)
                LibrarySort.Type.LastUpdate -> anime1.manga.lastUpdate.compareTo(anime2.manga.lastUpdate)
                LibrarySort.Type.UnreadCount -> when {
                    // Anime with something left to watch come first
                    anime1.unreadCount == anime2.unreadCount -> 0
                    anime1.unreadCount == 0L -> if (sort.isAscending) 1 else -1
                    anime2.unreadCount == 0L -> if (sort.isAscending) -1 else 1
                    else -> anime1.unreadCount.compareTo(anime2.unreadCount)
                }
                LibrarySort.Type.TotalChapters -> anime1.totalChapters.compareTo(anime2.totalChapters)
                LibrarySort.Type.LatestChapter -> anime1.latestUpload.compareTo(anime2.latestUpload)
                LibrarySort.Type.ChapterFetchDate -> anime1.chapterFetchedAt.compareTo(anime2.chapterFetchedAt)
                LibrarySort.Type.DateAdded -> anime1.manga.dateAdded.compareTo(anime2.manga.dateAdded)
                LibrarySort.Type.AiringTime -> {
                    // Like Anikku: by unseen count, then by airing time with unknown times last
                    val airing1 = airingAt[a.id] ?: 0L
                    val airing2 = airingAt[b.id] ?: 0L
                    when {
                        anime1.unreadCount != anime2.unreadCount -> anime1.unreadCount.compareTo(anime2.unreadCount)
                        airing1 == airing2 -> 0
                        airing1 == 0L -> if (sort.isAscending) 1 else -1
                        airing2 == 0L -> if (sort.isAscending) -1 else 1
                        else -> airing1.compareTo(airing2)
                    }
                }
                LibrarySort.Type.TagList -> {
                    val index1 = arrangement.sortTags.indexOfFirst { anime1.manga.genre?.contains(it) ?: false }
                    val index2 = arrangement.sortTags.indexOfFirst { anime2.manga.genre?.contains(it) ?: false }
                    index1.compareTo(index2)
                }
                // Anime aren't tracked yet
                LibrarySort.Type.TrackerMean, LibrarySort.Type.Random -> 0
            }
        }
        return sortedWith((if (sort.isAscending) byType else byType.reversed()).thenComparing(alphabetically))
    }

    /** The pages of the library: one per category, source, tag or status, or a single one when ungrouped */
    private fun List<LibraryItem>.grouped(
        group: Int,
        categories: List<Category>,
        showHiddenCategories: Boolean,
    ): List<Pair<Category, List<LibraryItem>>> {
        val default = groupCategory(Category.UNCATEGORIZED_ID, "", -1L)
        val groups = when (group) {
            LibraryGroup.BY_DEFAULT -> {
                val byCategory = mutableMapOf<Long, MutableList<LibraryItem>>()
                forEach { item ->
                    item.libraryManga.categories.forEach { byCategory.getOrPut(it) { mutableListOf() }.add(item) }
                }
                // "Default" only shows while something is in it, like in the manga library
                (listOf(default).filter { it.id in byCategory } + categories.filter { showHiddenCategories || !it.hidden })
                    .map { it to byCategory[it.id].orEmpty() }
            }
            LibraryGroup.BY_SOURCE -> {
                groupBy { it.libraryManga.manga.source }
                    .map { (sourceId, items) ->
                        val name = when (sourceId) {
                            DeviceFilesIds.ANIME -> context.stringResource(MR.strings.local_source)
                            else -> sourceManager.get(sourceId)?.name?.ifBlank { null } ?: sourceId.toString()
                        }
                        Triple(sourceId, name, items)
                    }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })
                    .mapIndexed { index, (sourceId, name, items) ->
                        groupCategory(sourceId, name, index.toLong()) to items
                    }
            }
            GROUP_BY_TAG -> {
                val ungrouped = context.stringResource(SYMR.strings.ungrouped)
                val byTag = mutableMapOf<String, MutableList<LibraryItem>>()
                forEach { item ->
                    item.libraryManga.manga.genre.orEmpty().distinct().forEach { tag ->
                        byTag.getOrPut(tag) { mutableListOf() }.add(item)
                    }
                }
                // Like Anikku: a tag gets its own page once more than 3 anime have it; the rest share one page
                val pages = byTag.filterValues { it.size > 3 }.toSortedMap()
                val shown = pages.values.flatten().mapTo(HashSet()) { it.id }
                val rest = filterNot { it.id in shown }
                pages.entries.mapIndexed { index, (tag, items) ->
                    // Ids start at 1: 0 is the "Default" category
                    groupCategory(index + 1L, tag, index.toLong()) to items.toList()
                } + listOfNotNull(
                    (groupCategory(Long.MAX_VALUE, ungrouped, Long.MAX_VALUE) to rest).takeIf { rest.isNotEmpty() },
                )
            }
            LibraryGroup.BY_STATUS -> {
                groupBy { it.libraryManga.manga.status }
                    .map { (status, items) ->
                        val (nameRes, order) = statusNames[status.toInt()] ?: (MR.strings.unknown to 7L)
                        groupCategory(status + 1, context.stringResource(nameRes), order) to items
                    }
                    .sortedBy { it.first.order }
            }
            else -> emptyList()
        }
        // Ungrouped, or nothing to show: one page without a tab
        return groups.ifEmpty { listOf(default to this) }
    }

    private fun groupCategory(id: Long, name: String, order: Long) =
        Category(id = id, name = name, order = order, flags = 0L, hidden = false)

    private data class Library(
        val favorites: List<Anime>,
        val stats: Map<Long, AnimeLibraryStats>,
        val categories: List<Category>,
        val categoryIds: Map<Long, List<Long>>,
    )

    private data class Display(
        val downloadBadge: Boolean,
        val unseenBadge: Boolean,
        val localBadge: Boolean,
        val languageBadge: Boolean,
        val useLangIcon: Boolean,
        val sourceBadge: Boolean,
        val categoryTabs: Boolean,
        val hiddenCategories: Boolean,
        val itemCount: Boolean,
        val continueButton: Boolean,
    )

    private data class Arrangement(
        val sort: LibrarySort,
        val group: Int,
        val randomSeed: Int,
        val sortTags: List<String>,
    )

    sealed interface Dialog {
        data class ChangeCategory(
            val animeId: Long,
            val initialSelection: ImmutableList<CheckboxState<Category>>,
        ) : Dialog
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val isLibraryEmpty: Boolean = false,
        val hasActiveFilters: Boolean = false,
        /** The pages shown: categories, or the groups of another grouping */
        val categories: List<Category> = emptyList(),
        /** Items of each page, by [Category.id] */
        val items: Map<Long, List<LibraryItem>> = emptyMap(),
        val itemCount: Int = 0,
        /** The anime categories the user made (without "Default") */
        val userCategories: List<Category> = emptyList(),
        val showCategoryTabs: Boolean = false,
        val showItemCount: Boolean = false,
        val showContinueButton: Boolean = false,
        val activePage: Int = 0,
        val dialog: Dialog? = null,
    ) {
        val coercedActivePage: Int
            get() = activePage.coerceIn(0, (categories.size - 1).coerceAtLeast(0))
    }

    companion object {
        /** Anikku's "Tags" grouping; the other groupings are [LibraryGroup]'s */
        const val GROUP_BY_TAG = 5

        private val statusNames = mapOf(
            SAnime.ONGOING to (MR.strings.ongoing to 1L),
            SAnime.COMPLETED to (MR.strings.completed to 2L),
            SAnime.LICENSED to (MR.strings.licensed to 3L),
            SAnime.PUBLISHING_FINISHED to (MR.strings.publishing_finished to 4L),
            SAnime.CANCELLED to (MR.strings.cancelled to 5L),
            SAnime.ON_HIATUS to (MR.strings.on_hiatus to 6L),
        )
    }
}
