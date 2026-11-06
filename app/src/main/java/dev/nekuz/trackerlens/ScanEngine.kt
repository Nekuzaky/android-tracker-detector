package dev.nekuz.trackerlens

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

object ScanEngine {
    data class Snapshot(
        val apps: List<AppReport> = emptyList(),
        val scanned: Int = 0,
        val total: Int = 0,
        val running: Boolean = false,
        val loaded: Boolean = false,
        val failed: Boolean = false,
    )

    private const val AD_ID_PERMISSION = "com.google.android.gms.permission.AD_ID"
    private const val CACHE_FILE = "scans.tsv"
    private const val PUBLISH_INTERVAL_MS = 200L
    private const val SAVE_EVERY = 10
    private const val RETRY_INTERVAL_MS = 15 * 60 * 1000L
    private val COMPONENT_FLAGS = listOf(
        PackageManager.GET_ACTIVITIES,
        PackageManager.GET_RECEIVERS,
        PackageManager.GET_SERVICES,
        PackageManager.GET_PROVIDERS,
    )
    private val MANIFEST_FLAGS = listOf(PackageManager.GET_PERMISSIONS, PackageManager.GET_META_DATA) + COMPONENT_FLAGS

    private class ScanOutcome(val scan: AppScan, val persist: Boolean)

    private class ManifestRead(val info: PackageInfo?, val complete: Boolean)

    private class Target(
        val packageName: String,
        val versionCode: Long,
        val lastUpdate: Long,
        val isSystem: Boolean,
        val info: ApplicationInfo,
    )

    private val main = Handler(Looper.getMainLooper())
    private val coordinator = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val matcher by lazy { SignatureMatcher(Catalog.trackers) }
    private val results = ConcurrentHashMap<String, AppScan>()
    private val labels = ConcurrentHashMap<String, String>()
    private val retryAt = ConcurrentHashMap<String, Long>()
    private val lock = Any()
    private val publishLock = Any()
    private var active = false
    private var pending = false
    private var cacheLoaded = false
    private var lastPublish = 0L

    @Volatile
    var snapshot = Snapshot()
        private set

    @Volatile
    var includeSystem = false

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun refresh(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (active) {
                pending = true
                return
            }
            active = true
        }
        coordinator.execute {
            while (true) {
                try {
                    runOnce(appContext)
                } catch (e: Throwable) {
                    val current = snapshot
                    publish(current.copy(running = false, failed = !current.loaded))
                }
                val again = synchronized(lock) {
                    if (pending) {
                        pending = false
                        true
                    } else {
                        active = false
                        false
                    }
                }
                if (!again) break
            }
        }
    }

    private fun runOnce(context: Context) {
        val manager = context.packageManager
        if (!cacheLoaded) {
            loadCache(context)
            cacheLoaded = true
        }
        val targets = enumerate(manager)
        val before = results.size
        results.keys.retainAll(targets.keys)
        labels.keys.retainAll(targets.keys)
        retryAt.keys.retainAll(targets.keys)
        for (target in targets.values) {
            val known = results[target.packageName] ?: continue
            if (known.versionCode != target.versionCode || known.lastUpdate != target.lastUpdate) {
                results.remove(target.packageName)
                labels.remove(target.packageName)
                retryAt.remove(target.packageName)
            }
        }
        val clock = SystemClock.elapsedRealtime()
        val queue = targets.values.filter { target ->
            val due = retryAt[target.packageName]?.let { it <= clock } ?: false
            (!target.isSystem || includeSystem) && (due || !results.containsKey(target.packageName))
        }
        if (queue.isEmpty()) {
            publish(buildSnapshot(manager, targets, 0, 0, false))
            if (results.size != before) saveCache(context)
            return
        }

        publish(buildSnapshot(manager, targets, 0, queue.size, true))
        val pool = Executors.newFixedThreadPool((Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 3))
        val done = java.util.concurrent.atomic.AtomicInteger()
        try {
            val tasks = queue.map { target ->
                Callable {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    scan(manager, target)?.let { outcome ->
                        if (!outcome.persist) {
                            retryAt[target.packageName] = SystemClock.elapsedRealtime() + RETRY_INTERVAL_MS
                        }
                        results[target.packageName] = outcome.scan
                        if (outcome.persist) retryAt.remove(target.packageName)
                    }
                    val finished = done.incrementAndGet()
                    if (finished % SAVE_EVERY == 0) saveCache(context)
                    val now = SystemClock.elapsedRealtime()
                    val due = synchronized(publishLock) {
                        if (now - lastPublish >= PUBLISH_INTERVAL_MS) {
                            lastPublish = now
                            true
                        } else {
                            false
                        }
                    }
                    if (due) publish(buildSnapshot(manager, targets, finished, queue.size, true))
                }
            }
            pool.invokeAll(tasks)
        } finally {
            pool.shutdown()
        }
        saveCache(context)
        publish(buildSnapshot(manager, targets, queue.size, queue.size, false))
    }

    private fun enumerate(manager: PackageManager): Map<String, Target> {
        val targets = HashMap<String, Target>()
        for (info in manager.getInstalledPackages(0)) {
            val application = info.applicationInfo ?: continue
            val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            targets[info.packageName] = Target(
                info.packageName,
                info.longVersionCode,
                info.lastUpdateTime,
                application.flags and systemFlags != 0,
                application,
            )
        }
        return targets
    }

    private fun buildSnapshot(
        manager: PackageManager,
        targets: Map<String, Target>,
        scanned: Int,
        total: Int,
        running: Boolean,
    ): Snapshot {
        val apps = results.values.mapNotNull { scan ->
            val target = targets[scan.packageName] ?: return@mapNotNull null
            val label = labels.getOrPut(scan.packageName) {
                runCatching { AppLabels.clean(target.info.loadLabel(manager), scan.packageName) }
                    .getOrDefault(scan.packageName)
            }
            AppReport(scan, label, target.isSystem)
        }
        return Snapshot(apps, scanned, total, running, loaded = true, failed = false)
    }

    private fun scan(manager: PackageManager, target: Target): ScanOutcome? {
        val collector = EvidenceCollector(matcher)
        var usesAdvertisingId = false
        var manifestComplete = false

        fun failed(persist: Boolean) = ScanOutcome(
            AppScan(
                target.packageName,
                target.versionCode,
                target.lastUpdate,
                Coverage.NONE,
                collector.classes,
                usesAdvertisingId,
                runCatching { collector.detections() }.getOrDefault(emptyList()),
                manifestComplete,
            ),
            persist && manifestComplete,
        )

        return try {
            val read = readManifest(manager, target.packageName) ?: return null
            val manifest = read.info
            manifestComplete = read.complete
            manifest?.requestedPermissions?.forEach { if (it == AD_ID_PERMISSION) usesAdvertisingId = true }
            manifest?.activities?.forEach { collector.addComponent(it.name) }
            manifest?.receivers?.forEach { collector.addComponent(it.name) }
            manifest?.services?.forEach { collector.addComponent(it.name) }
            manifest?.providers?.forEach { collector.addComponent(it.name) }
            manifest?.applicationInfo?.metaData?.keySet()?.forEach(collector::addManifestKey)

            val application = manifest?.applicationInfo ?: target.info
            val apks = listOfNotNull(application.sourceDir) + application.splitSourceDirs.orEmpty()
            val budget = ScanBudget()
            var result = CodeScanResult(0, 0)
            for (apk in apks) {
                result += ApkCodeScanner.scan(File(apk), budget) { collector.addDescriptor(it) }
            }
            ScanOutcome(
                AppScan(
                    target.packageName,
                    target.versionCode,
                    target.lastUpdate,
                    result.coverage,
                    collector.classes,
                    usesAdvertisingId,
                    collector.detections(),
                    manifestComplete,
                ),
                manifestComplete,
            )
        } catch (e: ScanBudgetExceeded) {
            failed(persist = !e.retryable)
        } catch (e: Throwable) {
            failed(persist = false)
        }
    }

    private fun readManifest(manager: PackageManager, packageName: String): ManifestRead? {
        val all = MANIFEST_FLAGS.reduce { flags, flag -> flags or flag } or PackageManager.MATCH_DISABLED_COMPONENTS
        try {
            return ManifestRead(manager.getPackageInfo(packageName, all), true)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        } catch (e: RuntimeException) {
            var merged: PackageInfo? = null
            var complete = true
            for (flag in MANIFEST_FLAGS) {
                val part = runCatching {
                    manager.getPackageInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS or flag)
                }.getOrNull()
                if (part == null) {
                    complete = false
                    continue
                }
                val info = merged ?: part.also { merged = it }
                part.requestedPermissions?.let { info.requestedPermissions = it }
                part.activities?.let { info.activities = it }
                part.receivers?.let { info.receivers = it }
                part.services?.let { info.services = it }
                part.providers?.let { info.providers = it }
                if (flag == PackageManager.GET_META_DATA) info.applicationInfo = part.applicationInfo
            }
            return ManifestRead(merged, complete)
        }
    }

    private fun publish(next: Snapshot) {
        main.post {
            snapshot = next
            listeners.forEach { it() }
        }
    }

    private fun loadCache(context: Context) {
        val text = runCatching {
            String(AtomicFile(File(context.filesDir, CACHE_FILE)).readFully(), Charsets.UTF_8)
        }.getOrNull() ?: return
        ReportCache.decode(text, Catalog.version).forEach { results[it.packageName] = it }
    }

    @Synchronized
    private fun saveCache(context: Context) {
        val file = AtomicFile(File(context.filesDir, CACHE_FILE))
        val stream = runCatching { file.startWrite() }.getOrNull() ?: return
        try {
            val saved = results.values.filter { !retryAt.containsKey(it.packageName) }
            stream.write(ReportCache.encode(saved, Catalog.version).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Throwable) {
            file.failWrite(stream)
        }
    }
}
