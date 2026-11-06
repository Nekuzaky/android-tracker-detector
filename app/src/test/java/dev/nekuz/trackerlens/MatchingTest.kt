package dev.nekuz.trackerlens

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchingTest {
    private val matcher = SignatureMatcher(Catalog.trackers)

    private fun ids(path: String) = matcher.matchPackage(path).map { it.id }

    @Test
    fun matchesPackageAndSubpackages() {
        assertEquals(listOf("meta_audience"), ids("com/facebook/ads"))
        assertEquals(listOf("meta_audience"), ids("com/facebook/ads/internal/view"))
    }

    @Test
    fun onlyMatchesAtPackageBoundaries() {
        assertEquals(emptyList<String>(), ids("com/facebook/adsmanager"))
        assertEquals(emptyList<String>(), ids("com/applovinish"))
        assertEquals(emptyList<String>(), ids("org/com/applovin"))
    }

    @Test
    fun doesNotFlagCommonLibraries() {
        listOf(
            "com/facebook/react",
            "com/facebook/fresco",
            "com/facebook/imagepipeline",
            "com/facebook/soloader",
            "com/google/android/gms/common",
            "com/google/android/gms/location",
            "com/google/android/gms/auth",
            "com/google/firebase/messaging",
            "com/google/firebase/auth",
            "com/google/firebase/installations",
            "com/unity3d/player",
            "okhttp3",
            "kotlin/collections",
            "androidx/core/app",
        ).forEach { assertEquals(it, emptyList<String>(), ids(it)) }
    }

    @Test
    fun honorsExclusions() {
        assertEquals(emptyList<String>(), ids("com/google/android/gms/ads/identifier"))
        assertEquals(listOf("admob"), ids("com/google/android/gms/ads/rewarded"))
        assertEquals(emptyList<String>(), ids("com/google/firebase/analytics/connector"))
        assertEquals(emptyList<String>(), ids("com/google/firebase/analytics/connector/internal"))
        assertEquals(listOf("firebase_analytics"), ids("com/google/firebase/analytics"))
    }

    @Test
    fun matchesManifestComponentsAndKeys() {
        assertEquals("applovin", matcher.matchClassName("com.applovin.adview.AppLovinFullscreenActivity").single().id)
        assertEquals(emptyList<Tracker>(), matcher.matchClassName("NoPackage"))
        assertEquals("admob", matcher.matchManifestKey("com.google.android.gms.ads.APPLICATION_ID").single().id)
        assertEquals(emptyList<Tracker>(), matcher.matchManifestKey("com.example.KEY"))
    }

    @Test
    fun collectorCountsClassesPerTracker() {
        val collector = EvidenceCollector(matcher)
        listOf(
            "Lcom/applovin/A;",
            "Lcom/applovin/B;",
            "Lcom/applovin/sdk/C;",
            "Lcom/example/D;",
            "La;",
            "[I",
            "Lcom/applovinish/E;",
        ).forEach(collector::addDescriptor)
        collector.addComponent("com.adjust.sdk.AdjustReferrerReceiver")
        val detections = collector.detections()
        assertEquals(7, collector.classes)
        assertEquals(setOf("applovin", "adjust"), detections.map { it.tracker.id }.toSet())
        val applovin = detections.first { it.tracker.id == "applovin" }
        assertEquals(3, applovin.classes)
        assertFalse(applovin.inManifest)
        assertTrue(detections.first { it.tracker.id == "adjust" }.inManifest)
    }

    @Test
    fun catalogIsConsistent() {
        assertEquals(Catalog.trackers.size, Catalog.trackers.map { it.id }.toSet().size)
        assertEquals(Catalog.trackers.size, Catalog.trackers.map { it.name }.toSet().size)
        val pattern = Regex("[a-z0-9_]+(\\.[a-z0-9_]+)+")
        for (tracker in Catalog.trackers) {
            assertTrue(tracker.id, tracker.id.matches(Regex("[a-z0-9_]+")))
            assertTrue(tracker.name, tracker.packages.isNotEmpty())
            (tracker.packages + tracker.excluded).forEach {
                assertTrue(it, pattern.matches(it))
            }
            tracker.excluded.forEach { excluded ->
                assertTrue(excluded, tracker.packages.any { excluded.startsWith("$it.") })
            }
        }
        val packages = Catalog.trackers.flatMap { it.packages }
        assertEquals(packages.size, packages.toSet().size)
    }

    @Test
    fun scansEveryDexFileInAnApk() {
        val apk = apk(
            "classes.dex" to DexFixture.build(listOf("Lcom/applovin/A;")),
            "classes2.dex" to DexFixture.build(listOf("Lcom/adjust/sdk/B;")),
            "assets/classes.dex" to DexFixture.build(listOf("Lcom/mixpanel/C;")),
            "res/raw/data.bin" to ByteArray(10),
        )
        val names = ArrayList<String>()
        val result = ApkCodeScanner.scan(apk) { names.add(it) }
        assertEquals(CodeScanResult(2, 0), result)
        assertEquals(Coverage.FULL, result.coverage)
        assertEquals(listOf("Lcom/adjust/sdk/B;", "Lcom/applovin/A;"), names.sorted())
    }

    @Test
    fun reportsPartialAndMissingCode() {
        val partial = apk(
            "classes.dex" to DexFixture.build(listOf("La;")),
            "classes2.dex" to ByteArray(200),
        )
        assertEquals(Coverage.PARTIAL, ApkCodeScanner.scan(partial) {}.coverage)
        assertEquals(Coverage.NONE, ApkCodeScanner.scan(apk("res/raw/a" to ByteArray(1))) {}.coverage)
        val broken = Files.createTempFile("broken", ".apk").toFile().apply { writeBytes(ByteArray(64)); deleteOnExit() }
        assertEquals(Coverage.NONE, ApkCodeScanner.scan(broken) {}.coverage)
        assertEquals(Coverage.NONE, ApkCodeScanner.scan(File("missing.apk")) {}.coverage)
    }

    @Test
    fun readsOnlyConsecutiveDexNames() {
        val apk = apk(
            "classes.dex" to DexFixture.build(listOf("La/A;")),
            "classes2.dex" to DexFixture.build(listOf("La/B;")),
            "classes4.dex" to DexFixture.build(listOf("La/D;")),
            "classes0.dex" to DexFixture.build(listOf("La/E;")),
            "classes02.dex" to DexFixture.build(listOf("La/F;")),
        )
        val names = ArrayList<String>()
        assertEquals(CodeScanResult(2, 0), ApkCodeScanner.scan(apk) { names.add(it) })
        assertEquals(listOf("La/A;", "La/B;"), names)
    }

    @Test
    fun ignoresNumberedDexWithoutTheFirstFile() {
        val apk = apk("classes2.dex" to DexFixture.build(listOf("Lcom/applovin/A;")))
        assertEquals(CodeScanResult(0, 0), ApkCodeScanner.scan(apk) {})
    }

    @Test
    fun tooManyDexFilesExhaustTheBudget() {
        val apk = apk(
            "classes.dex" to DexFixture.build(listOf("La/A;")),
            "classes2.dex" to DexFixture.build(listOf("La/B;")),
            "classes3.dex" to DexFixture.build(listOf("La/C;")),
        )
        val exceeded = assertThrows(ScanBudgetExceeded::class.java) {
            ApkCodeScanner.scan(apk, ScanBudget(maxDexFiles = 2)) {}
        }
        assertFalse(exceeded.retryable)
    }

    @Test
    fun oversizedEntryIsRefusedBeforeInflating() {
        val apk = apk("classes.dex" to DexFixture.build(listOf("La/A;"), padding = 2 * 1024 * 1024))
        val names = ArrayList<String>()
        assertThrows(ScanBudgetExceeded::class.java) {
            ApkCodeScanner.scan(apk, ScanBudget(maxBytes = 1024 * 1024)) { names.add(it) }
        }
        assertEquals(emptyList<String>(), names)
    }

    @Test
    fun budgetIsSharedAcrossSplits() {
        val first = apk("classes.dex" to DexFixture.build(listOf("La/A;"), padding = 600 * 1024))
        val second = apk("classes.dex" to DexFixture.build(listOf("La/B;"), padding = 600 * 1024))
        val budget = ScanBudget(maxBytes = 1024 * 1024)
        ApkCodeScanner.scan(first, budget) {}
        assertThrows(ScanBudgetExceeded::class.java) { ApkCodeScanner.scan(second, budget) {} }
    }

    @Test
    fun zipBombClaimingAHugeSizeCostsOnlyTheBudget() {
        val bomb = DexFixture.build(listOf("La/A;"), padding = 64 * 1024 * 1024)
        val apk = apk("classes.dex" to bomb)
        assertTrue(apk.length() < bomb.size / 100)
        val exceeded = assertThrows(ScanBudgetExceeded::class.java) {
            ApkCodeScanner.scan(apk, ScanBudget(maxBytes = 32L * 1024 * 1024)) {}
        }
        assertFalse(exceeded.retryable)
    }

    @Test
    fun entrySizeMismatchFailsThatFile() {
        val claimsMore = DexFixture.build(listOf("Lcom/applovin/A;")) { it.putInt(0x20, it.capacity() + 1000) }
        val apk = apk(
            "classes.dex" to claimsMore,
            "classes2.dex" to DexFixture.build(listOf("La/B;")),
        )
        val names = ArrayList<String>()
        val result = ApkCodeScanner.scan(apk) { names.add(it) }
        assertEquals(CodeScanResult(2, 1), result)
        assertEquals(Coverage.PARTIAL, result.coverage)
        assertEquals(listOf("La/B;"), names)
    }

    @Test
    fun overlongClassMakesAFilePartialButKeepsItsTrackers() {
        val long = "L${"a".repeat(5000)};"
        val apk = apk("classes.dex" to DexFixture.build(listOf(long, "Lcom/applovin/A;")))
        val names = ArrayList<String>()
        val result = ApkCodeScanner.scan(apk) { names.add(it) }
        assertEquals(CodeScanResult(1, 0, 1), result)
        assertEquals(Coverage.PARTIAL, result.coverage)
        assertEquals(listOf("Lcom/applovin/A;"), names)
    }

    @Test
    fun containerDexIsNeverFullCoverage() {
        val container = DexFixture.build(listOf("La/A;"), magic = "dex\n041\u0000")
        assertEquals(Coverage.NONE, ApkCodeScanner.scan(apk("classes.dex" to container)) {}.coverage)
        val mixed = apk("classes.dex" to container, "classes2.dex" to DexFixture.build(listOf("La/B;")))
        assertEquals(Coverage.PARTIAL, ApkCodeScanner.scan(mixed) {}.coverage)
    }

    @Test
    fun splitResultsAddUp() {
        assertEquals(Coverage.PARTIAL, (CodeScanResult(1, 0) + CodeScanResult(1, 0, 1)).coverage)
        assertEquals(Coverage.PARTIAL, (CodeScanResult(1, 1) + CodeScanResult(1, 0)).coverage)
        assertEquals(Coverage.FULL, (CodeScanResult(1, 0) + CodeScanResult(0, 0)).coverage)
    }

    @Test
    fun matchingIgnoresDepthBeyondTheCatalog() {
        val deep = List(5000) { "x" }.joinToString("/")
        assertEquals(emptyList<String>(), ids(deep))
        assertEquals(listOf("applovin"), ids("com/applovin/$deep"))
        assertEquals(listOf("admob"), ids("com/google/android/gms/internal/ads/$deep"))
        assertEquals(emptyList<String>(), ids("${"y".repeat(100_000)}/com/applovin"))
    }

    @Test
    fun collectorCacheStaysBounded() {
        val collector = EvidenceCollector(matcher)
        val total = EvidenceCollector.MAX_CACHED_PACKAGES * 3
        repeat(total) { collector.addDescriptor("Lcom/example/p$it/A;") }
        collector.addDescriptor("Lcom/applovin/A;")
        assertTrue(collector.cachedPackages <= EvidenceCollector.MAX_CACHED_PACKAGES)
        assertEquals(total + 1, collector.classes)
        assertEquals(listOf("applovin"), collector.detections().map { it.tracker.id })
    }

    private fun apk(vararg entries: Pair<String, ByteArray>): File {
        val file = Files.createTempFile("test", ".apk").toFile()
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
}
