package dev.nekuz.trackerlens

import java.io.File
import java.util.zip.ZipFile

data class CodeScanResult(val dexFiles: Int, val failedFiles: Int) {
    operator fun plus(other: CodeScanResult) =
        CodeScanResult(dexFiles + other.dexFiles, failedFiles + other.failedFiles)

    val coverage: Coverage
        get() = when {
            dexFiles == 0 -> Coverage.NONE
            failedFiles == 0 -> Coverage.FULL
            failedFiles >= dexFiles -> Coverage.NONE
            else -> Coverage.PARTIAL
        }
}

object ApkCodeScanner {
    private val dexEntry = Regex("classes\\d*\\.dex")

    fun scan(apk: File, onClass: (String) -> Unit): CodeScanResult {
        var dexFiles = 0
        var failedFiles = 0
        try {
            ZipFile(apk).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory || !dexEntry.matches(entry.name)) continue
                    dexFiles++
                    try {
                        zip.getInputStream(entry).use { DexReader.readClassDescriptors(it, onClass) }
                    } catch (e: Exception) {
                        failedFiles++
                    }
                }
            }
        } catch (e: Exception) {
            return CodeScanResult(dexFiles + 1, failedFiles + 1)
        }
        return CodeScanResult(dexFiles, failedFiles)
    }
}
