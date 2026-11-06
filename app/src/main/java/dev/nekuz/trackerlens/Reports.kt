package dev.nekuz.trackerlens

enum class Coverage { FULL, PARTIAL, NONE }

data class Detection(val tracker: Tracker, val classes: Int, val inManifest: Boolean) {
    val confirmed get() = classes > 0
}

data class AppScan(
    val packageName: String,
    val versionCode: Long,
    val lastUpdate: Long,
    val coverage: Coverage,
    val classes: Int,
    val usesAdvertisingId: Boolean,
    val detections: List<Detection>,
    val manifestComplete: Boolean = true,
) {
    val confirmed get() = detections.filter { it.confirmed }
    val declaredOnly get() = detections.filter { !it.confirmed }
    val incomplete get() = coverage != Coverage.FULL || !manifestComplete
    val status: ScanStatus
        get() = when {
            confirmed.isNotEmpty() -> ScanStatus.FOUND
            coverage == Coverage.NONE -> ScanStatus.UNREADABLE
            coverage == Coverage.PARTIAL -> ScanStatus.PARTLY_READ
            declaredOnly.isNotEmpty() -> ScanStatus.DECLARED_ONLY
            !manifestComplete -> ScanStatus.MANIFEST_INCOMPLETE
            else -> ScanStatus.NOTHING_FOUND
        }
}

enum class ScanStatus { FOUND, UNREADABLE, PARTLY_READ, DECLARED_ONLY, MANIFEST_INCOMPLETE, NOTHING_FOUND }

data class AppReport(val scan: AppScan, val label: String, val isSystem: Boolean) {
    val packageName get() = scan.packageName
    val sortKey = label.lowercase()
}

object AppLabels {
    const val MAX_LENGTH = 128

    fun clean(raw: CharSequence?, fallback: String): String {
        if (raw == null) return fallback
        val text = StringBuilder()
        var index = 0
        while (index < raw.length && text.length < MAX_LENGTH) {
            val codePoint = Character.codePointAt(raw, index)
            index += Character.charCount(codePoint)
            if (isHidden(codePoint)) continue
            if (text.length + Character.charCount(codePoint) > MAX_LENGTH) break
            text.appendCodePoint(codePoint)
        }
        val label = text.toString().trim()
        return label.ifEmpty { fallback }
    }

    private fun isHidden(codePoint: Int) = when (Character.getType(codePoint).toByte()) {
        Character.CONTROL,
        Character.FORMAT,
        Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR,
        Character.SURROGATE,
        Character.UNASSIGNED -> true
        else -> false
    }
}

data class TrackerSummary(val tracker: Tracker, val apps: List<AppReport>)

object Ranking {
    val detectionOrder: Comparator<Detection> =
        compareBy<Detection> { it.tracker.category.ordinal }.thenBy { it.tracker.name.lowercase() }

    fun apps(reports: List<AppReport>): List<AppReport> = reports.sortedWith(
        compareByDescending<AppReport> { it.scan.confirmed.size }
            .thenByDescending { it.scan.declaredOnly.size }
            .thenBy { it.scan.coverage != Coverage.FULL }
            .thenBy { it.sortKey }
            .thenBy { it.packageName }
    )

    fun trackers(reports: List<AppReport>): List<TrackerSummary> {
        val grouped = LinkedHashMap<String, MutableList<AppReport>>()
        val known = HashMap<String, Tracker>()
        for (report in apps(reports)) {
            for (detection in report.scan.confirmed) {
                known[detection.tracker.id] = detection.tracker
                grouped.getOrPut(detection.tracker.id) { ArrayList() }.add(report)
            }
        }
        return grouped
            .map { (id, apps) -> TrackerSummary(known.getValue(id), apps) }
            .sortedWith(
                compareByDescending<TrackerSummary> { it.apps.size }
                    .thenBy { it.tracker.name.lowercase() }
            )
    }
}

object ReportCache {
    private const val HEADER = "trackerlens-cache-2"

    fun encode(scans: Collection<AppScan>, version: String): String = buildString {
        append(HEADER).append('\t').append(version).append('\n')
        for (scan in scans) {
            append(scan.packageName).append('\t')
            append(scan.versionCode).append('\t')
            append(scan.lastUpdate).append('\t')
            append(scan.coverage.name).append('\t')
            append(scan.classes).append('\t')
            append(if (scan.usesAdvertisingId) 1 else 0).append('\t')
            scan.detections.joinTo(this, ",") {
                "${it.tracker.id}:${it.classes}:${if (it.inManifest) 1 else 0}"
            }
            append('\n')
        }
    }

    fun decode(text: String, version: String): List<AppScan> {
        val lines = text.lineSequence().iterator()
        if (!lines.hasNext() || lines.next() != "$HEADER\t$version") return emptyList()
        val scans = ArrayList<AppScan>()
        while (lines.hasNext()) decodeLine(lines.next())?.let(scans::add)
        return scans
    }

    private fun decodeLine(line: String): AppScan? {
        val fields = line.split('\t')
        if (fields.size != 7 || fields[0].isEmpty()) return null
        val versionCode = fields[1].toLongOrNull() ?: return null
        val lastUpdate = fields[2].toLongOrNull() ?: return null
        val coverage = Coverage.entries.firstOrNull { it.name == fields[3] } ?: return null
        val classes = fields[4].toIntOrNull() ?: return null
        val detections = if (fields[6].isEmpty()) {
            emptyList()
        } else {
            fields[6].split(',').map { decodeDetection(it) ?: return null }
        }
        return AppScan(fields[0], versionCode, lastUpdate, coverage, classes, fields[5] == "1", detections)
    }

    private fun decodeDetection(text: String): Detection? {
        val parts = text.split(':')
        if (parts.size != 3) return null
        val tracker = Catalog.byId[parts[0]] ?: return null
        val classes = parts[1].toIntOrNull() ?: return null
        return Detection(tracker, classes, parts[2] == "1")
    }
}
