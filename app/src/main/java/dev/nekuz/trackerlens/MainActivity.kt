package dev.nekuz.trackerlens

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private enum class Tab { APPS, TRACKERS }

    private lateinit var heroCount: TextView
    private lateinit var heroLabel: TextView
    private lateinit var heroDetail: TextView
    private lateinit var heroProgress: ProgressBar
    private lateinit var tabApps: Button
    private lateinit var tabTrackers: Button
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var adapter: RowAdapter

    private var tab = Tab.APPS
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val hero = findViewById<View>(R.id.heroCard)
        hero.clipToOutline = true
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            hero.visibility = View.GONE
            findViewById<View>(R.id.headerRow).visibility = View.GONE
        }
        heroCount = findViewById(R.id.heroCount)
        heroLabel = findViewById(R.id.heroLabel)
        heroDetail = findViewById(R.id.heroDetail)
        heroProgress = findViewById(R.id.heroProgress)
        tabApps = findViewById(R.id.tabApps)
        tabTrackers = findViewById(R.id.tabTrackers)
        search = findViewById(R.id.search)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        progress = findViewById(R.id.progress)

        tab = savedInstanceState?.getString(STATE_TAB)?.let { name ->
            Tab.entries.firstOrNull { it.name == name }
        } ?: Tab.APPS
        if (savedInstanceState != null) ScanEngine.includeSystem = savedInstanceState.getBoolean(STATE_SYSTEM)

        adapter = RowAdapter(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> onRowClick(adapter.rows[position]) }
        tabApps.setOnClickListener { selectTab(Tab.APPS) }
        tabTrackers.setOnClickListener { selectTab(Tab.TRACKERS) }
        val systemSwitch = findViewById<Switch>(R.id.systemSwitch)
        systemSwitch.isChecked = ScanEngine.includeSystem
        systemSwitch.setOnCheckedChangeListener { _, checked ->
            ScanEngine.includeSystem = checked
            render()
            ScanEngine.refresh(this)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render()
        })
        findViewById<View>(R.id.infoButton).setOnClickListener { showInfo() }
        updateTabs()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, tab.name)
        outState.putBoolean(STATE_SYSTEM, ScanEngine.includeSystem)
    }

    override fun onStart() {
        super.onStart()
        ScanEngine.addListener(listener)
        render()
    }

    override fun onResume() {
        super.onResume()
        ScanEngine.refresh(this)
    }

    override fun onStop() {
        super.onStop()
        ScanEngine.removeListener(listener)
    }

    private fun selectTab(selected: Tab) {
        tab = selected
        updateTabs()
        render()
        list.setSelection(0)
    }

    private fun updateTabs() {
        styleTab(tabApps, tab == Tab.APPS)
        styleTab(tabTrackers, tab == Tab.TRACKERS)
    }

    private fun styleTab(button: Button, selected: Boolean) {
        button.setBackgroundResource(if (selected) R.drawable.bg_button_primary else R.drawable.bg_button_outline)
        button.setTextColor(getColor(if (selected) R.color.on_brand else R.color.text_primary))
    }

    private fun visibleReports(snapshot: ScanEngine.Snapshot) =
        snapshot.apps.filter { ScanEngine.includeSystem || !it.isSystem }

    private fun render() {
        val snapshot = ScanEngine.snapshot
        val reports = visibleReports(snapshot)
        renderHero(snapshot, reports)
        progress.visibility = if (!snapshot.loaded && !snapshot.failed) View.VISIBLE else View.GONE
        val query = search.text.toString().trim()
        val rows = when (tab) {
            Tab.APPS -> appRows(reports, query)
            Tab.TRACKERS -> trackerRows(reports, query)
        }
        adapter.rows = rows
        empty.visibility = if (rows.isEmpty() && (snapshot.loaded || snapshot.failed)) View.VISIBLE else View.GONE
        empty.setText(
            when {
                snapshot.failed -> R.string.load_failed
                query.isNotEmpty() -> R.string.empty_search
                snapshot.running -> R.string.empty_scanning
                tab == Tab.TRACKERS -> R.string.empty_trackers
                else -> R.string.empty_apps
            }
        )
    }

    private fun renderHero(snapshot: ScanEngine.Snapshot, reports: List<AppReport>) {
        heroProgress.visibility = if (snapshot.running) View.VISIBLE else View.GONE
        heroProgress.max = snapshot.total.coerceAtLeast(1)
        heroProgress.progress = snapshot.scanned
        if (!snapshot.loaded) {
            heroCount.text = "…"
            heroLabel.text = getString(R.string.hero_loading)
            heroDetail.text = null
            return
        }
        val withTrackers = reports.count { it.scan.confirmed.isNotEmpty() }
        heroCount.text = withTrackers.toString()
        heroLabel.text = resources.getQuantityText(R.plurals.hero_apps, withTrackers)
        val trackers = reports.flatMap { report -> report.scan.confirmed.map { it.tracker.id } }.toSet().size
        val unreadable = reports.count { it.scan.coverage == Coverage.NONE }
        heroDetail.text = when {
            snapshot.running -> getString(R.string.hero_scanning, snapshot.scanned, snapshot.total)
            unreadable > 0 -> getString(R.string.hero_done_unreadable, trackers, reports.size, unreadable)
            else -> getString(R.string.hero_done, trackers, reports.size)
        }
    }

    private fun appRows(reports: List<AppReport>, query: String): List<Row> = Ranking.apps(reports)
        .filter { report ->
            query.isEmpty() ||
                report.label.contains(query, ignoreCase = true) ||
                report.packageName.contains(query, ignoreCase = true) ||
                report.scan.detections.any { it.tracker.name.contains(query, ignoreCase = true) }
        }
        .map(::appRow)

    private fun appRow(report: AppReport): Row {
        val confirmed = report.scan.confirmed
        val tag = when {
            confirmed.isNotEmpty() -> confirmed.size.toString()
            report.scan.coverage == Coverage.NONE -> "?"
            else -> null
        }
        return Row.AppRow(
            report.packageName,
            report.label,
            Hub.appSummary(this, report.scan),
            tag,
            Hub.trackerColor(confirmed),
        )
    }

    private fun trackerRows(reports: List<AppReport>, query: String): List<Row> = Ranking.trackers(reports)
        .filter { summary ->
            val tracker = summary.tracker
            query.isEmpty() ||
                tracker.name.contains(query, ignoreCase = true) ||
                tracker.owner.contains(query, ignoreCase = true) ||
                getString(tracker.category.label).contains(query, ignoreCase = true)
        }
        .map { summary ->
            val tracker = summary.tracker
            Row.TrackerRow(
                tracker.id,
                tracker.name,
                "${getString(tracker.category.label)} · ${tracker.owner}",
                summary.apps.size.toString(),
                Hub.categoryColor(tracker.category),
            )
        }

    private fun onRowClick(row: Row) {
        when (row) {
            is Row.AppRow -> startActivity(
                Intent(this, AppDetailActivity::class.java)
                    .putExtra(AppDetailActivity.EXTRA_PACKAGE, row.packageName)
            )
            is Row.TrackerRow -> startActivity(
                Intent(this, TrackerDetailActivity::class.java)
                    .putExtra(TrackerDetailActivity.EXTRA_TRACKER, row.trackerId)
            )
            else -> Unit
        }
    }

    private fun showInfo() {
        AlertDialog.Builder(this)
            .setTitle(R.string.info)
            .setMessage(getString(R.string.info_message, Catalog.trackers.size))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private companion object {
        const val STATE_TAB = "tab"
        const val STATE_SYSTEM = "system"
    }
}
