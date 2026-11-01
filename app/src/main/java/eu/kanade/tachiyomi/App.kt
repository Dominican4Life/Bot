package eu.kanade.tachiyomi

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Looper
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.Configuration
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.util.DebugLogger
import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.LogLevel
import com.elvishew.xlog.XLog
import com.elvishew.xlog.printer.AndroidPrinter
import com.elvishew.xlog.printer.Printer
import com.elvishew.xlog.printer.file.backup.NeverBackupStrategy
import com.elvishew.xlog.printer.file.naming.DateFileNameGenerator
import dev.mihon.injekt.patchInjekt
import eu.kanade.domain.DomainModule
import eu.kanade.domain.KMKDomainModule
import eu.kanade.domain.SYDomainModule
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.setAppCompatDelegateThemeMode
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.crash.CrashActivity
import eu.kanade.tachiyomi.crash.GlobalExceptionHandler
import eu.kanade.tachiyomi.data.coil.BufferedSourceFetcher
import eu.kanade.tachiyomi.data.coil.LocalPageFetcher
import eu.kanade.tachiyomi.data.coil.LocalPageKeyer
import eu.kanade.tachiyomi.data.coil.MangaCoverFetcher
import eu.kanade.tachiyomi.data.coil.MangaCoverKeyer
import eu.kanade.tachiyomi.data.coil.MangaCoverMetadata
import eu.kanade.tachiyomi.data.coil.MangaKeyer
import eu.kanade.tachiyomi.data.coil.PagePreviewFetcher
import eu.kanade.tachiyomi.data.coil.PagePreviewKeyer
import eu.kanade.tachiyomi.data.coil.ReaderPageFetcher
import eu.kanade.tachiyomi.data.coil.ReaderPageKeyer
import eu.kanade.tachiyomi.data.coil.SourcePageFetcher
import eu.kanade.tachiyomi.data.coil.SourcePageKeyer
import eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.di.AppModule
import eu.kanade.tachiyomi.di.PreferenceModule
import eu.kanade.tachiyomi.di.SYPreferenceModule
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegate
import eu.kanade.tachiyomi.util.CrashLogUtil
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.GLUtil
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.isDebugBuildType
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.telemetryIncluded
import exh.log.CrashlyticsPrinter
import exh.log.EHLogLevel
import exh.log.EnhancedFilePrinter
import exh.log.XLogLogcatLogger
import exh.log.xLogD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import logcat.AndroidLogcatLogger
import logcat.LogPriority
import logcat.LogcatLogger
import mihon.core.migration.Migrator
import mihon.core.migration.migrations.migrations
import mihon.telemetry.TelemetryConfig
import org.conscrypt.Conscrypt
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.widget.WidgetManager
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.security.Security
import java.text.SimpleDateFormat
import java.util.Locale

class App : Application(), DefaultLifecycleObserver, SingletonImageLoader.Factory {

    private val basePreferences: BasePreferences by injectLazy()
    private val privacyPreferences: PrivacyPreferences by injectLazy()

    private val disableIncognitoReceiver = DisableIncognitoReceiver()

    /** KMK (r95): one tick per return to the foreground, for things re-checked then (the Updates widget) */
    private val foregroundTick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @SuppressLint("LaunchActivityFromNotification")
    override fun onCreate() {
        // KMK (r104): how long App.onCreate keeps the main thread, logged at the end
        val onCreateStart = android.os.SystemClock.elapsedRealtime()
        super<Application>.onCreate()
        patchInjekt()
        // KMK: memory use in Logs every few minutes
        eu.kanade.tachiyomi.util.system.MemoryLog.start()
        TelemetryConfig.init(
            applicationContext,
            isPreviewBuildType,
            BuildConfig.COMMIT_COUNT,
        )

        // KMK -->
        if (isDebugBuildType) Timber.plant(Timber.DebugTree())
        // KMK <--

        GlobalExceptionHandler.initialize(applicationContext, CrashActivity::class.java)

        // TLS 1.3 support for Android < 10
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }

        // Avoid potential crashes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val process = getProcessName()
            if (packageName != process) WebView.setDataDirectorySuffix(process)
        }

        Injekt.importModule(PreferenceModule(this))
        Injekt.importModule(AppModule(this))
        Injekt.importModule(DomainModule())
        // KMK -->
        Injekt.importModule(KMKDomainModule())
        // KMK <--
        // SY -->
        Injekt.importModule(SYPreferenceModule(this))
        Injekt.importModule(SYDomainModule())
        // SY <--

        // KMK: in-app log, before anything else logs
        eu.kanade.tachiyomi.util.system.AppLog.init(this)
        // KMK (r97) -->
        // Diagnostics engine: the Live/Health/Capture screens' data, watchdogs, previous-run reader
        eu.kanade.tachiyomi.diagnostics.DiagnosticsEngine.install(this)
        // KMK <--
        setupExhLogging() // EXH logging
        if (!LogcatLogger.isInstalled) {
            val minLogPriority = when {
                // KMK -->
                EHLogLevel.isExtraLogging() -> LogPriority.VERBOSE
                // KMK <--
                BuildConfig.DEBUG -> LogPriority.DEBUG
                else -> LogPriority.INFO
            }
            LogcatLogger.install()
            // KMK: the in-app Logs screen (first, so XLog can tell its copies apart)
            LogcatLogger.loggers += eu.kanade.tachiyomi.util.system.AppLog.Logger()
            LogcatLogger.loggers += XLogLogcatLogger() // SY Redirect Logcat to XLog
            LogcatLogger.loggers += AndroidLogcatLogger(minLogPriority)
        }

        setupNotificationChannels()
        // KMK: a notification when a source needs a bot check passed in WebView
        eu.kanade.tachiyomi.data.notification.BotCheckNotifier.install(this)

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        val scope = ProcessLifecycleOwner.get().lifecycleScope

        // Show notification to disable Incognito Mode when it's enabled
        basePreferences.incognitoMode().changes()
            .onEach { enabled ->
                if (enabled) {
                    disableIncognitoReceiver.register()
                    notify(
                        Notifications.ID_INCOGNITO_MODE,
                        Notifications.CHANNEL_INCOGNITO_MODE,
                    ) {
                        setContentTitle(stringResource(MR.strings.pref_incognito_mode))
                        setContentText(stringResource(MR.strings.notification_incognito_text))
                        setSmallIcon(R.drawable.ic_glasses_with_hat_24dp)
                        setColor(ContextCompat.getColor(applicationContext, R.color.ic_launcher))
                        setLargeIcon(BitmapFactory.decodeResource(applicationContext.resources, R.drawable.komikku))
                        setOngoing(true)

                        val pendingIntent = PendingIntent.getBroadcast(
                            this@App,
                            0,
                            Intent(ACTION_DISABLE_INCOGNITO_MODE).setPackage(BuildConfig.APPLICATION_ID),
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                        )
                        setContentIntent(pendingIntent)
                    }
                } else {
                    disableIncognitoReceiver.unregister()
                    cancelNotification(Notifications.ID_INCOGNITO_MODE)
                }
            }
            .launchIn(scope)

        privacyPreferences.analytics()
            .changes()
            .onEach(TelemetryConfig::setAnalyticsEnabled)
            .launchIn(scope)

        privacyPreferences.crashlytics()
            .changes()
            .onEach(TelemetryConfig::setCrashlyticsEnabled)
            .launchIn(scope)

        basePreferences.hardwareBitmapThreshold().let { preference ->
            if (!preference.isSet()) preference.set(GLUtil.DEVICE_TEXTURE_LIMIT)
        }

        basePreferences.hardwareBitmapThreshold().changes()
            .onEach { ImageUtil.hardwareBitmapThreshold = it }
            .launchIn(scope)

        setAppCompatDelegateThemeMode(Injekt.get<UiPreferences>().themeMode().get())

        // KMK -->
        MangaCoverMetadata.load()
        // KMK (r90): paired devices from an older version or a restored backup moved to the private list
        runCatching { eu.kanade.tachiyomi.pairsync.PairSyncPreferences.migrate() }
        // KMK <--

        // Updates widget update
        // KMK (r95): paused during restores and pair syncs (its Updates query re-ran for every merged title), and
        // only while a widget is placed (checked again each time the app comes to the foreground)
        WidgetManager(Injekt.get(), Injekt.get()).apply {
            init(
                scope,
                paused = eu.kanade.tachiyomi.ui.library.LibraryRefresh.restoring,
                recheck = foregroundTick,
            )
        }

        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(this, Configuration.Builder().build())
        }
        val syncPreferences: SyncPreferences = Injekt.get()
        val syncTriggerOpt = syncPreferences.getSyncTriggerOptions()
        if (syncPreferences.isSyncEnabled() && syncTriggerOpt.syncOnAppStart) {
            SyncDataJob.startNow(this@App)
        }

        initializeMigrator()

        // KMK (r104): compare with earlier runs; the logs folder lookup, the extension loading and the warm-up of
        // sources, database and downloads run off the main thread now and log their own "Start:" lines
        eu.kanade.tachiyomi.util.system.AppLog.add(
            'I',
            "app",
            "Start: main-thread work ${android.os.SystemClock.elapsedRealtime() - onCreateStart} ms in App.onCreate " +
                "(process start to onCreate ${onCreateStart - android.os.Process.getStartElapsedRealtime()} ms)",
        )
    }

    private fun initializeMigrator() {
        val preferenceStore = Injekt.get<PreferenceStore>()
        // SY -->
        val preference = preferenceStore.getInt(Preference.appStateKey("eh_last_version_code"), 0)
        // SY <--
        logcat { "Migration from ${preference.get()} to ${BuildConfig.VERSION_CODE}" }
        Migrator.initialize(
            old = preference.get(),
            new = BuildConfig.VERSION_CODE,
            migrations = migrations,
            onMigrationComplete = {
                logcat { "Updating last version to ${BuildConfig.VERSION_CODE}" }
                preference.set(BuildConfig.VERSION_CODE)
            },
        )
    }

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(this).apply {
            val callFactoryLazy = lazy { Injekt.get<NetworkHelper>().client }
            components {
                // NetworkFetcher.Factory
                add(OkHttpNetworkFetcherFactory(callFactoryLazy::value))
                // Decoder.Factory
                add(TachiyomiImageDecoder.Factory())
                // Fetcher.Factory
                add(BufferedSourceFetcher.Factory())
                add(MangaCoverFetcher.MangaCoverFactory(callFactoryLazy))
                add(MangaCoverFetcher.MangaFactory(callFactoryLazy))
                // Keyer
                add(MangaCoverKeyer())
                add(MangaKeyer())
                // SY -->
                add(PagePreviewKeyer())
                add(PagePreviewFetcher.Factory(callFactoryLazy))
                // SY <--
                // KMK -->
                add(ReaderPageKeyer())
                add(ReaderPageFetcher.Factory())
                add(SourcePageKeyer())
                add(SourcePageFetcher.Factory())
                add(LocalPageKeyer())
                add(LocalPageFetcher.Factory())
                add(eu.kanade.tachiyomi.data.coil.EpisodePreviewKeyer())
                add(eu.kanade.tachiyomi.data.coil.EpisodePreviewFetcher.Factory(callFactoryLazy))
                // KMK <--
            }

            diskCache(
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    // KMK (r103): up to 1 GB (Coil's default ceiling is 250 MB), still at most 2 % of the storage:
                    // covers outside the library and the Browse/JS preview page tiles share this cache
                    .maximumMaxSizeBytes(IMAGE_DISK_CACHE_MAX_BYTES)
                    .build()
                    .also { cache ->
                        eu.kanade.tachiyomi.util.system.AppLog.add(
                            'D',
                            "cover",
                            "Image disk cache: up to ${cache.maxSize / (1024 * 1024)} MB",
                        )
                    },
            )

            memoryCache(
                MemoryCache.Builder()
                    .maxSizePercent(context)
                    .build(),
            )

            crossfade((300 * this@App.animatorDurationScale).toInt())
            allowRgb565(DeviceUtil.isLowRamDevice(this@App))
            // KMK -->
            if (EHLogLevel.isExtraLogging()) logger(DebugLogger())
            // KMK <--

            // Coil spawns a new thread for every image load by default
            // KMK: covers that couldn't be shown, and slow page tiles, are written to Logs
            eventListenerFactory(eu.kanade.tachiyomi.data.coil.ImageEvents)
            fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(8))
            decoderCoroutineContext(Dispatchers.IO.limitedParallelism(3))
        }
            .build()
    }

    override fun onStart(owner: LifecycleOwner) {
        SecureActivityDelegate.onApplicationStart()
        foregroundTick.tryEmit(Unit)

        val syncPreferences: SyncPreferences = Injekt.get()
        val syncTriggerOpt = syncPreferences.getSyncTriggerOptions()
        if (syncPreferences.isSyncEnabled() && syncTriggerOpt.syncOnAppResume) {
            SyncDataJob.startNow(this@App)
        }

        // KMK (r93): a sync's staged data whose apply was interrupted (killed, or Stop) is applied once the app is in
        // the foreground (its service may start then); nothing happens while a sync runs or when nothing waits
        runCatching { eu.kanade.tachiyomi.pairsync.PairSync.resumePendingApply(this) }
    }

    override fun onStop(owner: LifecycleOwner) {
        SecureActivityDelegate.onApplicationStopped()
        // KMK (r95): the Logs file is written through a buffer; a backgrounded process may be killed any time
        eu.kanade.tachiyomi.util.system.AppLog.flush()
        // KMK (r97) -->
        // Diagnostics: flush the rings, stop the samplers
        (eu.kanade.tachiyomi.diagnostics.Diag.engine as? eu.kanade.tachiyomi.diagnostics.DiagnosticsEngine)
            ?.onBackground()
        // KMK <--
    }

    override fun getPackageName(): String {
        try {
            // Override the value passed as X-Requested-With in WebView requests
            val stackTrace = Looper.getMainLooper().thread.stackTrace
            // KMK -->
            val chromiumClasses = setOf("org.chromium.base.buildinfo", "org.chromium.base.apkinfo")
            val chromiumMethods = setOf("getall", "getpackagename", "<init>")
            // KMK <--
            val isChromiumCall = stackTrace.any { trace ->
                trace.className.lowercase() in chromiumClasses &&
                    trace.methodName.lowercase() in chromiumMethods
            }

            if (isChromiumCall) return WebViewUtil.spoofedPackageName(applicationContext)
        } catch (_: Exception) {
        }

        return super.getPackageName()
    }

    private fun setupNotificationChannels() {
        try {
            Notifications.createChannels(this)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to modify notification channels" }
        }
    }

    // EXH
    private fun setupExhLogging() {
        EHLogLevel.init(
            this,
            // KMK -->
            isDebugBuildType = isDebugBuildType,
            // KMK <--
        )

        val logLevel = when {
            // KMK -->
            EHLogLevel.isExtremeLogging() -> LogLevel.ALL
            EHLogLevel.isExtraLogging() -> LogLevel.DEBUG
            // KMK <--
            else -> LogLevel.WARN
        }

        val logConfig = LogConfiguration.Builder()
            .logLevel(logLevel)
            .disableStackTrace()
            .disableBorder()
            .build()

        val printers = mutableListOf<Printer>(
            AndroidPrinter(),
            // KMK: the in-app Logs screen
            eu.kanade.tachiyomi.util.system.AppLog.XLogPrinter(),
        )

        // KMK (r104): the logs folder is a storage (SAF) lookup that ran on the main thread at every start (it also
        // built StorageManager, which checks the app folder): now looked up on a background thread. XLog's file printer
        // is attached when it's found; lines printed before that are kept and written then
        val filePrinter = LateFilePrinter()
        printers += filePrinter
        Thread(
            {
                val start = android.os.SystemClock.elapsedRealtime()
                val logFolder = runCatching { Injekt.get<StorageManager>().getLogsDirectory() }.getOrNull()
                val printer = logFolder?.let { folder ->
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                    runCatching {
                        EnhancedFilePrinter
                            .Builder(folder) {
                                fileNameGenerator = object : DateFileNameGenerator() {
                                    override fun generateFileName(logLevel: Int, timestamp: Long): String {
                                        return super.generateFileName(
                                            logLevel,
                                            timestamp,
                                        ) + "-${BuildConfig.BUILD_TYPE}.txt"
                                    }
                                }
                                flattener { timeMillis, level, tag, message ->
                                    val time = dateFormat.format(timeMillis)
                                    "$time ${LogLevel.getShortLevelName(level)}/$tag: $message"
                                }
                                backupStrategy = NeverBackupStrategy()
                            }
                    }.getOrNull()
                }
                filePrinter.attach(printer)
                val none = if (printer == null) "; no logs folder (app folder not set or not reachable)" else ""
                eu.kanade.tachiyomi.util.system.AppLog.add(
                    'I',
                    "app",
                    "Start: logs folder looked up off the main thread in " +
                        "${android.os.SystemClock.elapsedRealtime() - start} ms (was on it)$none",
                )
            },
            "logs-folder",
        ).start()

        // Install Crashlytics in prod
        if (telemetryIncluded) {
            printers += CrashlyticsPrinter(LogLevel.ERROR)
        }

        XLog.init(
            logConfig,
            *printers.toTypedArray(),
        )

        xLogD("Application booting...")
        // KMK (r95): at the default WARN level XLog drops this line, so don't build it (it looked up the WebView
        // package on the main thread, and CrashLogUtil used to build the whole ExtensionManager just to exist)
        if (EHLogLevel.isExtraLogging()) xLogD(CrashLogUtil(applicationContext).getDebugInfo())
    }

    private inner class DisableIncognitoReceiver : BroadcastReceiver() {
        private var registered = false

        override fun onReceive(context: Context, intent: Intent) {
            basePreferences.incognitoMode().set(false)
        }

        fun register() {
            if (!registered) {
                ContextCompat.registerReceiver(
                    this@App,
                    this,
                    IntentFilter(ACTION_DISABLE_INCOGNITO_MODE),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                registered = true
            }
        }

        fun unregister() {
            if (registered) {
                unregisterReceiver(this)
                registered = false
            }
        }
    }
}

private const val ACTION_DISABLE_INCOGNITO_MODE = "tachi.action.DISABLE_INCOGNITO_MODE"

/**
 * KMK (r104): XLog's file printer, attached once the logs folder was looked up off the main thread. Lines printed
 * before that (XLog keeps only W/E by default) are held, up to [MAX_EARLY_XLOG_LINES], and written when it's attached;
 * with no logs folder they're dropped, as before.
 */
private class LateFilePrinter : Printer {
    private val lock = Any()
    private var attached = false
    private var target: Printer? = null
    private val early = ArrayList<Triple<Int, String, String>>()

    override fun println(logLevel: Int, tag: String, msg: String) {
        synchronized(lock) {
            if (attached) {
                // EnhancedFilePrinter only queues the line for its own writer thread
                target?.println(logLevel, tag, msg)
            } else if (early.size < MAX_EARLY_XLOG_LINES) {
                early += Triple(logLevel, tag, msg)
            }
        }
    }

    fun attach(printer: Printer?) {
        synchronized(lock) {
            target = printer
            attached = true
            if (printer != null) early.forEach { (level, tag, msg) -> printer.println(level, tag, msg) }
            early.clear()
        }
    }
}

private const val MAX_EARLY_XLOG_LINES = 500

/** KMK (r103): ceiling of Coil's image disk cache (was Coil's default, 250 MB) */
private const val IMAGE_DISK_CACHE_MAX_BYTES = 1024L * 1024 * 1024
