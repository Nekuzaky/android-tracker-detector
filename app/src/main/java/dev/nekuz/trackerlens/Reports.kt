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
) {
    val confirmed get() = detections.filter { it.confirmed }
    val declaredOnly get() = detections.filter { !it.confirmed }
}

data class AppReport(val scan: AppScan, val label: String, val isSystem: Boolean) {
    val packageName get() = scan.packageName
}

data class TrackerSummary(val tracker: Tracker, val apps: List<AppReport>)

object Ranking {
    val detectionOrder: Comparator<Detection> =
        compareBy<Detection> { it.tracker.category.ordinal }.thenBy { it.tracker.name.lowercase() }

    fun apps(reports: List<AppReport>): List<AppReport> = reports.sortedWith(
        compareByDescending<AppReport> { it.scan.confirmed.size }
            .thenByDescending { it.scan.declaredOnly.size }
            .thenBy { it.scan.coverage != Coverage.FULL }
            .thenBy { it.label.lowercase() }
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
    private const val HEADER = "trackerlens-cache-1"

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
