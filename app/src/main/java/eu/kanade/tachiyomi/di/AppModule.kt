package eu.kanade.tachiyomi.di

import android.app.Application
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.BackupRestoreStatus
import eu.kanade.tachiyomi.data.LibraryUpdateStatus
import eu.kanade.tachiyomi.data.SyncStatus
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.cache.PagePreviewCache
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveService
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.AndroidSourceManager
import eu.kanade.tachiyomi.util.system.isDebugBuildType
import exh.eh.EHentaiUpdateHelper
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.core.archive.CbzCrypto
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.storage.AndroidStorageFolderProvider
import tachiyomi.core.common.storage.UniFileTempFileManager
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addFactory
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

// SY -->
private const val LEGACY_DATABASE_NAME = "tachiyomi.db"
// SY <--

class AppModule(val app: Application) : InjektModule {
    // SY -->
    private val securityPreferences: SecurityPreferences by injectLazy()
    // SY <--

    override fun InjektRegistrar.registerInjectables() {
        addSingleton(app)

        addSingletonFactory<SqlDriver> {
            // SY -->
            if (securityPreferences.encryptDatabase().get()) {
                System.loadLibrary("sqlcipher")
            }

            // SY <--
            AndroidSqliteDriver(
                schema = Database.Schema,
                context = app,
                // SY -->
                name = if (securityPreferences.encryptDatabase().get()) {
                    CbzCrypto.DATABASE_NAME
                } else {
                    LEGACY_DATABASE_NAME
                },
                factory = if (securityPreferences.encryptDatabase().get()) {
                    SupportOpenHelperFactory(CbzCrypto.getDecryptedPasswordSql(), null, false, 25)
                } else if (isDebugBuildType && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Support database inspector in Android Studio
                    FrameworkSQLiteOpenHelperFactory()
                } else {
                    RequerySQLiteOpenHelperFactory()
                },
                // SY <--
                callback = object : AndroidSqliteDriver.Callback(Database.Schema) {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        setPragma(db, "foreign_keys = ON")
                        setPragma(db, "journal_mode = WAL")
                        setPragma(db, "synchronous = NORMAL")
                    }
                    private fun setPragma(db: SupportSQLiteDatabase, pragma: String) {
                        val cursor = db.query("PRAGMA $pragma")
                        cursor.moveToFirst()
                        cursor.close()
                    }
                },
                // KMK (r95): compiled statements kept for reuse (the default is 20; a restore, sync or library update
                // runs the same handful of queries thousands of times)
                cacheSize = 100,
            )
        }
        addSingletonFactory {
            Database(
                driver = get(),
                historyAdapter = History.Adapter(
                    last_readAdapter = DateColumnAdapter,
                ),
                mangasAdapter = Mangas.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                    memoAdapter = MemoColumnAdapter,
                ),
                chaptersAdapter = Chapters.Adapter(
                    memoAdapter = MemoColumnAdapter,
                ),
            )
        }
        addSingletonFactory<DatabaseHandler> { AndroidDatabaseHandler(get(), get()) }

        addSingletonFactory {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
        addSingletonFactory {
            XML {
                defaultPolicy {
                    ignoreUnknownChildren()
                }
                autoPolymorphic = true
                xmlDeclMode = XmlDeclMode.Charset
                indent = 2
                xmlVersion = XmlVersion.XML10
            }
        }
        addSingletonFactory<ProtoBuf> {
            ProtoBuf
        }

        addSingletonFactory { UniFileTempFileManager(app) }

        addSingletonFactory { ChapterCache(app, get(), get()) }
        addSingletonFactory { CoverCache(app) }

        addSingletonFactory { NetworkHelper(app, get(), get()) }
        addSingletonFactory { JavaScriptEngine(app) }

        // KMK: JS / JSON sources from the Code editor
        addSingletonFactory { eu.kanade.tachiyomi.jssource.JsSourceManager(app) }
        addSingletonFactory<SourceManager> { AndroidSourceManager(app, get(), get()) }
        addSingletonFactory { ExtensionManager(app) }
        // KMK: anime
        addSingletonFactory { eu.kanade.tachiyomi.animeextension.AnimeExtensionManager(app) }
        addSingletonFactory { eu.kanade.tachiyomi.animeextension.AnimeSourceManager(get()) }
        addSingletonFactory { eu.kanade.tachiyomi.data.anime.AnimeRepository(get()) }
        addSingletonFactory { eu.kanade.tachiyomi.data.anime.track.AnimeTrackRepository(get()) }
        // Anime player (from AniZen)
        addSingletonFactory { eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences(get()) }
        addSingletonFactory { eu.kanade.tachiyomi.ui.player.settings.GesturePreferences(get()) }
        addSingletonFactory { eu.kanade.tachiyomi.ui.player.settings.DecoderPreferences(get()) }
        // KMK: Anime4K shaders for the player filters panel
        addSingletonFactory { eu.kanade.tachiyomi.ui.player.utils.Anime4KManager(app) }
        addSingletonFactory { eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager(app) }
        addSingletonFactory { eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences(get()) }
        addSingletonFactory { eu.kanade.tachiyomi.ui.player.settings.AudioPreferences(get()) }
        addSingletonFactory { eu.kanade.tachiyomi.ui.player.settings.AdvancedPlayerPreferences(get()) }
        addSingletonFactory<tachiyomi.domain.custombuttons.repository.CustomButtonRepository> {
            tachiyomi.data.custombutton.CustomButtonRepositoryImpl(get())
        }
        addFactory { tachiyomi.domain.custombuttons.interactor.CreateCustomButton(get()) }
        addFactory { tachiyomi.domain.custombuttons.interactor.DeleteCustomButton(get()) }
        addFactory { tachiyomi.domain.custombuttons.interactor.GetCustomButtons(get()) }
        addFactory { tachiyomi.domain.custombuttons.interactor.UpdateCustomButton(get()) }
        addFactory { tachiyomi.domain.custombuttons.interactor.ReorderCustomButton(get()) }
        addFactory { tachiyomi.domain.custombuttons.interactor.ToggleFavoriteCustomButton(get()) }
        addFactory { eu.kanade.tachiyomi.ui.player.domain.TrackSelect(get(), get()) }
        addSingletonFactory { eu.kanade.tachiyomi.ui.player.domain.AudioManager(app) }
        addSingletonFactory { eu.kanade.tachiyomi.ui.player.domain.BrightnessManager(app) }
        addSingletonFactory { animiru.feature.mpvfiles.MpvConfig(app, get(), get(), get()) }

        addSingletonFactory { DownloadProvider(app) }
        addSingletonFactory { DownloadManager(app) }
        addSingletonFactory { DownloadCache(app) }

        addSingletonFactory { TrackerManager() }
        addSingletonFactory { DelayedTrackingStore(app) }

        addSingletonFactory { ImageSaver(app) }

        addSingletonFactory { AndroidStorageFolderProvider(app) }
        addSingletonFactory { LocalSourceFileSystem(get()) }
        addSingletonFactory { LocalCoverManager(app, get()) }
        addSingletonFactory { StorageManager(app, get()) }

        // SY -->
        addSingletonFactory { EHentaiUpdateHelper(app) }

        addSingletonFactory { PagePreviewCache(app) }
        // SY <--

        // KMK -->
        addSingletonFactory { BackupRestoreStatus() }
        addSingletonFactory { SyncStatus() }
        addSingletonFactory { LibraryUpdateStatus() }
        // KMK <--

        // AM (CONNECTIONS) -->
        addSingletonFactory { ConnectionsManager() }
        // <-- AM (CONNECTIONS)

        // Asynchronously init expensive components for a faster cold start
        // KMK (r104): this ran on the main looper (after App.onCreate), so it wasn't asynchronous at all. Only the
        // network client is still built there (its cookie jar opens WebView's CookieManager); the rest is built on a
        // background thread. Injekt's singletons are created once under a lock, so a screen that asks for one
        // meanwhile waits for it instead of building a second
        ContextCompat.getMainExecutor(app).execute {
            val mainStart = android.os.SystemClock.elapsedRealtime()
            get<NetworkHelper>()
            val mainMs = android.os.SystemClock.elapsedRealtime() - mainStart

            Thread(
                {
                    val start = android.os.SystemClock.elapsedRealtime()
                    val timings = mutableListOf<String>()
                    fun step(name: String, build: () -> Unit) {
                        val stepStart = android.os.SystemClock.elapsedRealtime()
                        runCatching { build() }.onFailure {
                            eu.kanade.tachiyomi.util.system.AppLog.add(
                                'E',
                                "app",
                                "Start: building $name failed: ${it.message}",
                            )
                        }
                        timings += "$name ${android.os.SystemClock.elapsedRealtime() - stepStart} ms"
                    }

                    step("sources") { get<SourceManager>() }

                    // KMK: anime extensions
                    step("anime sources") { get<eu.kanade.tachiyomi.animeextension.AnimeSourceManager>() }

                    step("database") { get<Database>() }

                    step("downloads") { get<DownloadManager>() }

                    // SY -->
                    step("custom info") { get<GetCustomMangaInfo>() }
                    // SY <--

                    eu.kanade.tachiyomi.util.system.AppLog.add(
                        'I',
                        "app",
                        "Start: warm-up off the main thread " +
                            "${android.os.SystemClock.elapsedRealtime() - start} ms (was on it): " +
                            "${timings.joinToString()}; on the main thread: network client $mainMs ms",
                    )
                },
                "app-warmup",
            ).start()
        }

        addSingletonFactory { GoogleDriveService(app) }
    }
}
