package dev.nekuz.trackerlens

class SignatureMatcher(trackers: List<Tracker>) {
    private val byPackage = HashMap<String, MutableList<Tracker>>()
    private val byManifestKey = HashMap<String, MutableList<Tracker>>()
    private val excluded = HashMap<String, List<String>>()
    private val maxDepth: Int
    private val maxLength: Int

    init {
        for (tracker in trackers) {
            tracker.packages.forEach { byPackage.getOrPut(slashed(it)) { ArrayList() }.add(tracker) }
            tracker.manifestKeys.forEach { byManifestKey.getOrPut(it) { ArrayList() }.add(tracker) }
            excluded[tracker.id] = tracker.excluded.map(::slashed)
        }
        maxDepth = byPackage.keys.maxOfOrNull { key -> key.count { it == '/' } + 1 } ?: 0
        maxLength = byPackage.keys.maxOfOrNull { it.length } ?: 0
    }

    fun matchPackage(packagePath: String): List<Tracker> {
        var matches: MutableList<Tracker>? = null
        var end = packagePath.indexOf('/')
        var depth = 1
        while (depth <= maxDepth) {
            val stop = if (end < 0) packagePath.length else end
            if (stop > maxLength) break
            byPackage[packagePath.substring(0, stop)]?.forEach { tracker ->
                if (!isExcluded(tracker, packagePath)) {
                    val list = matches ?: ArrayList<Tracker>().also { matches = it }
                    if (tracker !in list) list.add(tracker)
                }
            }
            if (end < 0) break
            end = packagePath.indexOf('/', end + 1)
            depth++
        }
        return matches ?: emptyList()
    }

    fun matchClassName(className: String): List<Tracker> {
        val end = className.lastIndexOf('.')
        if (end <= 0) return emptyList()
        return matchPackage(slashed(className.substring(0, end)))
    }

    fun matchManifestKey(key: String): List<Tracker> = byManifestKey[key].orEmpty()

    private fun isExcluded(tracker: Tracker, packagePath: String): Boolean =
        excluded[tracker.id].orEmpty().any { packagePath == it || packagePath.startsWith("$it/") }

    private fun slashed(name: String) = name.replace('.', '/')
}

class EvidenceCollector(private val matcher: SignatureMatcher) {
    private val classCounts = HashMap<String, Int>()
    private val manifestHits = HashSet<String>()
    private val cache = HashMap<String, List<Tracker>>()
    private var lastPackage = ""
    private var lastMatches: List<Tracker> = emptyList()

    var classes = 0
        private set

    val cachedPackages get() = cache.size

    fun addDescriptor(descriptor: String) {
        classes++
        if (descriptor.length < 3 || descriptor[0] != 'L') return
        val end = descriptor.lastIndexOf('/')
        if (end <= 1) return
        val sameAsLast = lastPackage.length == end - 1 && descriptor.regionMatches(1, lastPackage, 0, end - 1)
        if (!sameAsLast) {
            lastPackage = descriptor.substring(1, end)
            lastMatches = cache[lastPackage] ?: matcher.matchPackage(lastPackage).also {
                if (cache.size >= MAX_CACHED_PACKAGES) cache.clear()
                cache[lastPackage] = it
            }
        }
        for (tracker in lastMatches) {
            classCounts[tracker.id] = (classCounts[tracker.id] ?: 0) + 1
        }
    }

    fun addComponent(className: String) {
        matcher.matchClassName(className).forEach { manifestHits.add(it.id) }
    }

    fun addManifestKey(key: String) {
        matcher.matchManifestKey(key).forEach { manifestHits.add(it.id) }
    }

    fun detections(): List<Detection> = (classCounts.keys + manifestHits)
        .mapNotNull { id ->
            Catalog.byId[id]?.let { Detection(it, classCounts[id] ?: 0, id in manifestHits) }
        }
        .sortedWith(Ranking.detectionOrder)

    companion object {
        const val MAX_CACHED_PACKAGES = 8_192
    }
}
