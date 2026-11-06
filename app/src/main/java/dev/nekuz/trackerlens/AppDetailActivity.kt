package dev.nekuz.trackerlens

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView

class AppDetailActivity : Activity() {

    private lateinit var adapter: RowAdapter
    private lateinit var progress: ProgressBar
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private var packageId = ""
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageId = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        if (packageId.isEmpty()) {
            finish()
            return
        }
        setContentView(R.layout.activity_detail)
        progress = findViewById(R.id.progress)
        title = findViewById(R.id.title)
        subtitle = findViewById(R.id.subtitle)
        title.text = packageId
        subtitle.text = packageId
        adapter = RowAdapter(this)
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val row = adapter.rows[position]
            if (row is Row.TrackerRow) {
                startActivity(
                    Intent(this, TrackerDetailActivity::class.java)
                        .putExtra(TrackerDetailActivity.EXTRA_TRACKER, row.trackerId)
                )
            }
        }
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        val icon = findViewById<ImageView>(R.id.icon)
        icon.visibility = View.VISIBLE
        Hub.bindIcon(this, icon, packageId)

        val primary = findViewById<Button>(R.id.primaryButton)
        primary.setText(R.string.open_settings)
        primary.setOnClickListener { Hub.openAppSettings(this, packageId) }

        val appWall = Hub.appWallIntent(this)
        if (appWall != null) {
            val secondary = findViewById<Button>(R.id.secondaryButton)
            secondary.visibility = View.VISIBLE
            secondary.setText(R.string.open_appwall)
            secondary.setOnClickListener { runCatching { startActivity(appWall) } }
        }
    }

    override fun onStart() {
        super.onStart()
        if (packageId.isEmpty()) return
        ScanEngine.addListener(listener)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (packageId.isNotEmpty()) ScanEngine.refresh(this)
    }

    override fun onStop() {
        super.onStop()
        ScanEngine.removeListener(listener)
    }

    private fun render() {
        val snapshot = ScanEngine.snapshot
        val report = snapshot.apps.firstOrNull { it.packageName == packageId }
        if (report == null) {
            if (snapshot.loaded && !snapshot.running) {
                finish()
            } else {
                progress.visibility = View.VISIBLE
            }
            return
        }
        progress.visibility = View.GONE
        title.text = report.label
        subtitle.text = if (report.isSystem) {
            "${getString(R.string.app_system)} · ${report.packageName}"
        } else {
            report.packageName
        }
        adapter.rows = buildRows(report.scan)
    }

    private fun buildRows(scan: AppScan): List<Row> = buildList {
        add(Row.Header(getString(R.string.section_trackers)))
        val confirmed = scan.confirmed
        if (confirmed.isEmpty()) {
            val text = if (scan.coverage == Coverage.NONE) R.string.summary_unreadable else R.string.summary_none
            add(Row.InfoRow(getString(text), ""))
        } else {
            confirmed.forEach { add(trackerRow(it)) }
        }
        val declared = scan.declaredOnly
        if (declared.isNotEmpty()) {
            add(Row.Header(getString(R.string.section_declared)))
            declared.forEach { add(trackerRow(it)) }
        }
        if (scan.usesAdvertisingId) {
            add(Row.Header(getString(R.string.section_signals)))
            add(Row.InfoRow(getString(R.string.signal_ad_id), getString(R.string.signal_ad_id_detail)))
        }
        add(Row.Header(getString(R.string.section_coverage)))
        val coverage = when (scan.coverage) {
            Coverage.FULL -> resources.getQuantityString(R.plurals.coverage_full, scan.classes, scan.classes)
            Coverage.PARTIAL -> resources.getQuantityString(R.plurals.coverage_partial, scan.classes, scan.classes)
            Coverage.NONE -> getString(R.string.coverage_none)
        }
        add(Row.InfoRow(coverage, getString(R.string.coverage_note)))
    }

    private fun trackerRow(detection: Detection): Row {
        val tracker = detection.tracker
        return Row.TrackerRow(
            tracker.id,
            tracker.name,
            "${getString(tracker.category.label)} · ${tracker.owner}\n${Hub.evidence(this, detection)}",
            null,
            0,
        )
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}
