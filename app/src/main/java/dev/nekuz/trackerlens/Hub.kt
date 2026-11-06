package dev.nekuz.trackerlens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ImageView
import android.widget.Toast
import java.util.concurrent.Executors

object Hub {
    private const val APPWALL_PACKAGE = "dev.nekuz.appwall"

    private val main = Handler(Looper.getMainLooper())
    private val iconExecutor = Executors.newSingleThreadExecutor()
    private val icons = HashMap<String, Drawable>()

    fun openAppSettings(activity: Activity, packageName: String) {
        try {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.open_failed, Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            Toast.makeText(activity, R.string.open_failed, Toast.LENGTH_LONG).show()
        }
    }

    fun appWallIntent(context: Context): Intent? =
        runCatching { context.packageManager.getLaunchIntentForPackage(APPWALL_PACKAGE) }.getOrNull()

    fun bindIcon(context: Context, view: ImageView, packageName: String) {
        view.tag = packageName
        val cached = synchronized(icons) { icons[packageName] }
        view.setImageDrawable(cached)
        if (cached != null) return
        val appContext = context.applicationContext
        iconExecutor.execute {
            val icon = runCatching { appContext.packageManager.getApplicationIcon(packageName) }.getOrNull()
                ?: return@execute
            synchronized(icons) { icons[packageName] = icon }
            main.post { if (view.tag == packageName) view.setImageDrawable(icon) }
        }
    }

    fun trackerColor(detections: List<Detection>): Int {
        val categories = detections.map { it.tracker.category }
        return when {
            categories.any {
                it == TrackerCategory.ADVERTISING || it == TrackerCategory.ATTRIBUTION || it == TrackerCategory.LOCATION
            } -> R.color.danger
            categories.any { it == TrackerCategory.ANALYTICS || it == TrackerCategory.ENGAGEMENT } -> R.color.amber
            else -> R.color.text_secondary
        }
    }

    fun categoryColor(category: TrackerCategory): Int = when (category) {
        TrackerCategory.ADVERTISING, TrackerCategory.ATTRIBUTION, TrackerCategory.LOCATION -> R.color.danger
        TrackerCategory.ANALYTICS, TrackerCategory.ENGAGEMENT -> R.color.amber
        TrackerCategory.DIAGNOSTICS -> R.color.text_secondary
    }

    fun evidence(context: Context, detection: Detection): String {
        val resources = context.resources
        val code = if (detection.classes > 0) {
            resources.getQuantityString(R.plurals.evidence_classes, detection.classes, detection.classes)
        } else {
            null
        }
        val manifest = when {
            !detection.confirmed -> context.getString(R.string.evidence_manifest_only)
            detection.inManifest -> context.getString(R.string.evidence_manifest)
            else -> null
        }
        return listOfNotNull(code, manifest).joinToString(" · ")
    }

    fun appSummary(context: Context, scan: AppScan): String {
        val names = scan.confirmed.map { it.tracker.name }
        return when {
            names.isEmpty() && scan.coverage == Coverage.NONE -> context.getString(R.string.summary_unreadable)
            names.isEmpty() && scan.declaredOnly.isNotEmpty() -> context.getString(
                R.string.summary_declared,
                scan.declaredOnly.joinToString(", ") { it.tracker.name },
            )
            names.isEmpty() -> context.getString(R.string.summary_none)
            names.size <= 2 -> names.joinToString(", ")
            else -> context.getString(R.string.summary_more, names.take(2).joinToString(", "), names.size - 2)
        }
    }
}
