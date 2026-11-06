package dev.nekuz.trackerlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportsTest {
    private fun detection(id: String, classes: Int = 3, manifest: Boolean = false) =
        Detection(Catalog.byId.getValue(id), classes, manifest)

    private fun scan(
        name: String,
        vararg detections: Detection,
        coverage: Coverage = Coverage.FULL,
    ) = AppScan(name, 7L, 1234567890123L, coverage, 42, true, detections.toList())

    private fun report(scan: AppScan, label: String = scan.packageName) = AppReport(scan, label, false)

    @Test
    fun cacheRoundTrip() {
        val scans = listOf(
            scan("com.a", detection("applovin"), detection("adjust", 0, true)),
            scan("com.b"),
            scan("com.c", coverage = Coverage.NONE),
        )
        assertEquals(scans, ReportCache.decode(ReportCache.encode(scans, "v1"), "v1"))
    }

    @Test
    fun cacheIsDroppedWhenCatalogChanges() {
        val text = ReportCache.encode(listOf(scan("com.a")), "v1")
        assertEquals(emptyList<AppScan>(), ReportCache.decode(text, "v2"))
        assertEquals(emptyList<AppScan>(), ReportCache.decode("", "v1"))
    }

    @Test
    fun corruptedLinesAreIgnored() {
        val text = ReportCache.encode(listOf(scan("com.a"), scan("com.b")), "v1")
        val damaged = text +
            "broken\n" +
            "com.c\tx\t1\tFULL\t1\t0\t\n" +
            "com.d\t1\t1\tWRONG\t1\t0\t\n" +
            "com.e\t1\t1\tFULL\t1\t0\tunknown_tracker:1:0\n" +
            "com.f\t1\t1\tFULL\t1\t0\tapplovin:x:0\n"
        assertEquals(listOf("com.a", "com.b"), ReportCache.decode(damaged, "v1").map { it.packageName })
    }

    @Test
    fun appsAreRankedByTrackerCount() {
        val reports = listOf(
            report(scan("com.none"), "Zed"),
            report(scan("com.unreadable", coverage = Coverage.NONE), "Alpha"),
            report(scan("com.one", detection("adjust")), "One"),
            report(scan("com.two", detection("adjust"), detection("applovin")), "Two"),
            report(scan("com.none2"), "beta"),
        )
        assertEquals(
            listOf("com.two", "com.one", "com.none2", "com.none", "com.unreadable"),
            Ranking.apps(reports).map { it.packageName },
        )
    }

    @Test
    fun trackersAreGroupedAndRanked() {
        val reports = listOf(
            report(scan("com.a", detection("adjust"))),
            report(scan("com.b", detection("adjust"), detection("applovin"))),
        )
        val summaries = Ranking.trackers(reports)
        assertEquals(listOf("adjust", "applovin"), summaries.map { it.tracker.id })
        assertEquals(listOf("com.a", "com.b"), summaries[0].apps.map { it.packageName }.sorted())
        assertEquals(listOf("com.b"), summaries[1].apps.map { it.packageName })
    }

    @Test
    fun declaredOnlyDetectionsDoNotCountAsTrackers() {
        val declared = report(scan("com.declared", detection("admob", 0, true)))
        val found = report(scan("com.found", detection("adjust")))
        val clean = report(scan("com.clean"))
        assertEquals(1, declared.scan.declaredOnly.size)
        assertEquals(0, declared.scan.confirmed.size)
        assertEquals(listOf("adjust"), Ranking.trackers(listOf(declared, found)).map { it.tracker.id })
        assertEquals(
            listOf("com.found", "com.declared", "com.clean"),
            Ranking.apps(listOf(clean, declared, found)).map { it.packageName },
        )
    }

    @Test
    fun partlyReadCodeIsNeverShownAsClean() {
        val partial = scan("com.partial", coverage = Coverage.PARTIAL)
        assertEquals(ScanStatus.PARTLY_READ, partial.status)
        assertTrue(partial.incomplete)
        assertEquals(ScanStatus.PARTLY_READ, scan("com.d", detection("admob", 0, true), coverage = Coverage.PARTIAL).status)
        assertEquals(ScanStatus.FOUND, scan("com.f", detection("adjust"), coverage = Coverage.PARTIAL).status)
        assertEquals(ScanStatus.UNREADABLE, scan("com.u", coverage = Coverage.NONE).status)
        assertEquals(ScanStatus.NOTHING_FOUND, scan("com.c").status)
        assertFalse(scan("com.c").incomplete)
    }

    @Test
    fun missingManifestEvidenceIsMarked() {
        val scan = scan("com.m").copy(manifestComplete = false)
        assertEquals(ScanStatus.MANIFEST_INCOMPLETE, scan.status)
        assertTrue(scan.incomplete)
    }

    @Test
    fun labelsAreCappedAndCleaned() {
        assertEquals("Signal", AppLabels.clean("Sig‮nal‏\n", "com.x"))
        assertEquals("Two words", AppLabels.clean("  Two  words\t", "com.x"))
        assertEquals("com.x", AppLabels.clean("‮⁦\n", "com.x"))
        assertEquals("com.x", AppLabels.clean("", "com.x"))
        assertEquals("com.x", AppLabels.clean(null, "com.x"))
        assertEquals(AppLabels.MAX_LENGTH, AppLabels.clean("a".repeat(5_000_000), "com.x").length)
        assertEquals("😀".repeat(AppLabels.MAX_LENGTH / 2), AppLabels.clean("😀".repeat(1000), "com.x"))
        assertEquals("a" + "😀".repeat((AppLabels.MAX_LENGTH - 1) / 2), AppLabels.clean("a" + "😀".repeat(1000), "com.x"))
        assertEquals("com.x", AppLabels.clean("\uD83D", "com.x"))
    }

    @Test
    fun sortKeyIsLowercaseLabel() {
        assertEquals("zed", report(scan("com.a"), "ZED").sortKey)
    }

    @Test
    fun detectionsPutAdvertisingFirst() {
        val sorted = listOf(detection("sentry"), detection("mixpanel"), detection("applovin"))
            .sortedWith(Ranking.detectionOrder)
        assertEquals(listOf("applovin", "mixpanel", "sentry"), sorted.map { it.tracker.id })
    }
}
