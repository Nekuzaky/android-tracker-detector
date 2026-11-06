package dev.nekuz.trackerlens

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView

class TrackerDetailActivity : Activity() {

    private lateinit var adapter: RowAdapter
    private lateinit var progress: ProgressBar
    private lateinit var subtitle: TextView
    private var tracker: Tracker? = null
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selected = intent.getStringExtra(EXTRA_TRACKER)?.let { Catalog.byId[it] }
        if (selected == null) {
            finish()
            return
        }
        tracker = selected
        setContentView(R.layout.activity_detail)
        progress = findViewById(R.id.progress)
        subtitle = findViewById(R.id.subtitle)
        findViewById<TextView>(R.id.title).apply {
            maxLines = 2
            text = selected.name
        }
        findViewById<View>(R.id.primaryButton).visibility = View.GONE
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        adapter = RowAdapter(this)
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val row = adapter.rows[position]
            if (row is Row.AppRow) {
                startActivity(
                    Intent(this, AppDetailActivity::class.java)
                        .putExtra(AppDetailActivity.EXTRA_PACKAGE, row.packageName)
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (tracker == null) return
        ScanEngine.addListener(listener)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (tracker != null) ScanEngine.refresh(this)
    }

    override fun onStop() {
        super.onStop()
        ScanEngine.removeListener(listener)
    }

    private fun render() {
        val selected = tracker ?: return
        val snapshot = ScanEngine.snapshot
        progress.visibility = if (snapshot.loaded) View.GONE else View.VISIBLE
        val reports = snapshot.apps.filter { report ->
            (ScanEngine.includeSystem || !report.isSystem) &&
                report.scan.detections.any { it.tracker.id == selected.id }
        }
        val apps = Ranking.apps(reports)
        val confirmed = apps.filter { report -> report.scan.confirmed.any { it.tracker.id == selected.id } }
        val declared = apps - confirmed.toSet()
        subtitle.text = "${getString(selected.category.label)} · ${selected.owner}"
        adapter.rows = buildList {
            add(Row.Header(getString(R.string.section_about_tracker)))
            add(Row.InfoRow(getString(selected.category.label), getString(selected.category.description)))
            add(Row.InfoRow(getString(R.string.detected_by), detectedBy(selected)))
            add(Row.Header(resources.getQuantityString(R.plurals.section_apps, confirmed.size, confirmed.size)))
            if (confirmed.isEmpty()) {
                add(Row.InfoRow(getString(R.string.no_apps_with_tracker), ""))
            } else {
                confirmed.forEach { add(appRow(it, selected)) }
            }
            if (declared.isNotEmpty()) {
                add(Row.Header(getString(R.string.section_declared)))
                declared.forEach { add(appRow(it, selected)) }
            }
        }
    }

    private fun appRow(report: AppReport, selected: Tracker): Row {
        val detection = report.scan.detections.first { it.tracker.id == selected.id }
        return Row.AppRow(report.packageName, report.label, Hub.evidence(this, detection), null, 0)
    }

    private fun detectedBy(tracker: Tracker) = (tracker.packages + tracker.manifestKeys).joinToString("\n")

    companion object {
        const val EXTRA_TRACKER = "tracker"
    }
}
