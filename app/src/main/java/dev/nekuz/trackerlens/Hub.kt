package dev.nekuz.trackerlens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.LruCache
import android.widget.ImageView
import android.widget.Toast
import java.util.concurrent.Executors

object Hub {
    private const val APPWALL_PACKAGE = "dev.nekuz.appwall"
    private const val APPWALL_CERTIFICATE = "5b337ce0f98772c46a9b7ee869312fc6c42596c2036d7910643991b549acd031"
    private const val ICON_DP = 48
    private const val MAX_ICON_PX = 192
    private const val MAX_ICON_SOURCE_PX = 2048
    private const val MAX_CACHED_ICONS = 128

    private val main = Handler(Looper.getMainLooper())
    private val iconExecutor = Executors.newSingleThreadExecutor()
    private val icons = LruCache<String, Bitmap>(MAX_CACHED_ICONS)
    private val failedIcons = HashSet<String>()

    fun openAppSettings(activity: Activity, packageName: String) {
        try {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.open_failed, Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            Toast.makeText(activity, R.string.open_failed, Toast.LENGTH_LONG).show()
        }
    }

    fun appWallIntent(context: Context): Intent? = runCatching {
        val manager = context.packageManager
        val certificate = APPWALL_CERTIFICATE.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (manager.hasSigningCertificate(APPWALL_PACKAGE, certificate, PackageManager.CERT_INPUT_SHA256)) {
            manager.getLaunchIntentForPackage(APPWALL_PACKAGE)
        } else {
            null
        }
    }.getOrNull()

    fun bindIcon(context: Context, view: ImageView, packageName: String) {
        view.tag = packageName
        val cached = synchronized(icons) {
            if (packageName in failedIcons) {
                view.setImageResource(android.R.drawable.sym_def_app_icon)
                return
            }
            icons[packageName]
        }
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        val appContext = context.applicationContext
        iconExecutor.execute {
            val icon = try {
                renderIcon(appContext, packageName)
            } catch (e: Throwable) {
                null
            }
            synchronized(icons) {
                if (icon != null) icons.put(packageName, icon) else failedIcons.add(packageName)
            }
            main.post {
                if (view.tag != packageName) return@post
                if (icon != null) {
                    view.setImageBitmap(icon)
                } else {
                    view.setImageResource(android.R.drawable.sym_def_app_icon)
                }
            }
        }
    }

    private fun renderIcon(context: Context, packageName: String): Bitmap? {
        val manager = context.packageManager
        if (iconSourceTooLarge(manager, packageName)) return null
        val size = (ICON_DP * context.resources.displayMetrics.density).toInt().coerceIn(1, MAX_ICON_PX)
        val drawable = manager.getApplicationIcon(packageName)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun iconSourceTooLarge(manager: PackageManager, packageName: String): Boolean = runCatching {
        val application = manager.getApplicationInfo(packageName, 0)
        if (application.icon == 0) return@runCatching false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        manager.getResourcesForApplication(application).openRawResource(application.icon).use {
            BitmapFactory.decodeStream(it, null, options)
        }
        options.outWidth > MAX_ICON_SOURCE_PX || options.outHeight > MAX_ICON_SOURCE_PX
    }.getOrDefault(false)

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
        return when (scan.status) {
            ScanStatus.UNREADABLE -> context.getString(R.string.summary_unreadable)
            ScanStatus.PARTLY_READ -> context.getString(R.string.summary_partial)
            ScanStatus.DECLARED_ONLY -> context.getString(
                R.string.summary_declared,
                scan.declaredOnly.joinToString(", ") { it.tracker.name },
            )
            ScanStatus.MANIFEST_INCOMPLETE -> context.getString(R.string.summary_manifest_incomplete)
            ScanStatus.NOTHING_FOUND -> context.getString(R.string.summary_none)
            ScanStatus.FOUND -> if (names.size <= 2) {
                names.joinToString(", ")
            } else {
                context.getString(R.string.summary_more, names.take(2).joinToString(", "), names.size - 2)
            }
        }
    }
}
