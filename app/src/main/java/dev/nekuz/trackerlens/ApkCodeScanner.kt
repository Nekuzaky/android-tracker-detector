package dev.nekuz.trackerlens

import java.io.File
import java.util.zip.ZipFile

data class CodeScanResult(val dexFiles: Int, val failedFiles: Int, val skippedClasses: Int = 0) {
    operator fun plus(other: CodeScanResult) = CodeScanResult(
        dexFiles + other.dexFiles,
        failedFiles + other.failedFiles,
        skippedClasses + other.skippedClasses,
    )

    val coverage: Coverage
        get() = when {
            dexFiles == 0 -> Coverage.NONE
            failedFiles >= dexFiles -> Coverage.NONE
            failedFiles == 0 && skippedClasses == 0 -> Coverage.FULL
            else -> Coverage.PARTIAL
        }
}

object ApkCodeScanner {
    fun scan(apk: File, budget: ScanBudget = ScanBudget(), onClass: (String) -> Unit): CodeScanResult {
        var dexFiles = 0
        var failedFiles = 0
        var skippedClasses = 0
        try {
            ZipFile(apk).use { zip ->
                var index = 1
                while (true) {
                    val entry = zip.getEntry(dexName(index)) ?: break
                    if (entry.isDirectory) break
                    budget.startDexFile(entry.size)
                    dexFiles++
                    try {
                        zip.getInputStream(entry).use {
                            skippedClasses += DexReader.readClassDescriptors(it, budget, entry.size, onClass).skipped
                        }
                    } catch (e: ScanBudgetExceeded) {
                        throw e
                    } catch (e: Exception) {
                        failedFiles++
                    }
                    index++
                }
            }
        } catch (e: ScanBudgetExceeded) {
            throw e
        } catch (e: Exception) {
            return CodeScanResult(dexFiles + 1, failedFiles + 1, skippedClasses)
        }
        return CodeScanResult(dexFiles, failedFiles, skippedClasses)
    }

    private fun dexName(index: Int) = if (index == 1) "classes.dex" else "classes$index.dex"
}
