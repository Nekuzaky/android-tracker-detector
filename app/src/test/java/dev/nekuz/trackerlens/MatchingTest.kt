package dev.nekuz.trackerlens

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
