package com.vivi.aharana

import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Observer
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One draft session: Apply commits, Back/Cancel discards; published state is retained in ViewModel. */
internal class LibraryFilterDialog(private val activity: AppCompatActivity, private val model: LibraryViewModel) {
    private val selection = mutableMapOf<String, MutableSet<String>>()
    private var draftInitialized = false
    private var scan: Job? = null

    fun show(force: Boolean = false) {
        if (!model.isRestoringLibrary() && !force) { categories(); return }
        val message = TextView(activity).apply {
            setPadding(32, 24, 32, 24)
            text = if (force) "Refreshing filter data…" else "Loading saved filter data…"
        }
        val progress = MaterialAlertDialogBuilder(activity).setTitle("Library filters")
            .setView(message).setNegativeButton("Cancel") { _, _ ->
                scan?.cancel()
            }.create()
        progress.setOnCancelListener { scan?.cancel() }
        val observer = Observer<String> {
            message.text = it.ifBlank { if (force) "Refreshing filter data…" else "Loading saved filter data…" }
        }
        model.filterProgress.observe(activity, observer)
        progress.setOnDismissListener { model.filterProgress.removeObserver(observer) }
        progress.show()
        scan = activity.lifecycleScope.launch {
            try {
                model.prepareFilterIndex(force)
                progress.dismiss()
                if (!activity.isFinishing && !activity.isDestroyed) categories()
            } catch (cancelled: CancellationException) {
                progress.dismiss()
                throw cancelled
            } catch (e: Exception) {
                progress.dismiss()
                DiagnosticLog.appendException(activity, "LibraryFilters", "filter_refresh_failed", e)
                if (activity.isFinishing || activity.isDestroyed) return@launch
                MaterialAlertDialogBuilder(activity).setTitle("Filters unavailable")
                    .setMessage("Filter data could not be refreshed. Cached filter data and your current choices were kept. Choose Refresh filter data to retry; clear filters to show all books.")
                    .setPositiveButton("Close", null).show()
            }
        }
    }

    private fun categories() {
        if (!draftInitialized) {
            selection.putAll(model.getLibraryFilters().mapValues { it.value.toMutableSet() })
            draftInitialized = true
        }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 0, 24, 8) }
        val explanation = TextView(activity)
        fun explain() {
            explanation.text = "Choose multiple values in each category. Values in the same category match ANY; categories combine with AND. Counts use cached filter data for books still in the library. Search and sort still apply.\n" +
                "Applied selections persist across app restarts. Filter data is not checked automatically: startup, downloads, opening filters and Apply never scan EPUBs. Refresh filter data rereads all books, including new/changed data and external edits.\n" +
                model.filterDataStatus() +
                if (model.filterReadFailures > 0) "\n" + model.filterReadFailureSummary() else ""
        }
        explain()
        content.addView(explanation)
        val dialog = MaterialAlertDialogBuilder(activity).setTitle("Library filters")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Apply") { _, _ -> model.setLibraryFilters(selection.mapValues { it.value.toSet() }) }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear all") { _, _ -> model.setLibraryFilters(emptyMap()) }
            .create()
        for ((key, name) in LibraryFilters.categories) {
            content.addView(MaterialButton(activity).apply {
                text = "$name · ${selection[key]?.size ?: 0} selected"
                setOnClickListener { dialog.dismiss(); values(key, name) }
            })
        }
        content.addView(MaterialButton(activity).apply {
            text = "Refresh filter data"
            setOnClickListener { dialog.dismiss(); show(force = true) }
        })
        val observer = Observer<Int> { explain() }
        model.filterDataUpdates.observe(activity, observer)
        dialog.setOnDismissListener { model.filterDataUpdates.removeObserver(observer) }
        dialog.show()
    }

    private fun values(key: String, name: String) {
        val selected = selection[key].orEmpty().toMutableSet()
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 0, 24, 0) }
        val search = EditText(activity).apply { hint = "Search values"; isSingleLine = true }
        val list = ListView(activity).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE }
        var displayed = emptyList<String>()
        fun render() {
            val all = model.filterOptions(key).toMutableMap()
            // Selected zero-count choices remain clearable, but deleted-only unselected options disappear.
            selected.forEach { all.putIfAbsent(it, 0) }
            val query = search.text.toString().trim()
            displayed = all.keys.filter { it.contains(query, ignoreCase = true) }
            list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_multiple_choice,
                displayed.map { "$it (${all[it]})" })
            displayed.forEachIndexed { index, value -> list.setItemChecked(index, value in selected) }
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val value = displayed[position]
            if (list.isItemChecked(position)) selected.add(value) else selected.remove(value)
        }
        search.addTextChangedListener { render() }
        content.addView(search)
        content.addView(TextView(activity).apply { text = "Choose any values; none selected means all." })
        content.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            (activity.resources.displayMetrics.heightPixels * 0.45).toInt()))
        render()
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(name).setView(content)
            .setPositiveButton("Done") { _, _ -> selection[key] = selected; categories() }
            .setNegativeButton("Back") { _, _ -> categories() }
            .setNeutralButton("Clear category") { _, _ -> selection.remove(key); categories() }.create()
        dialog.setOnCancelListener { categories() }
        val observer = Observer<Int> { render() }
        model.filterDataUpdates.observe(activity, observer)
        dialog.setOnDismissListener { model.filterDataUpdates.removeObserver(observer) }
        dialog.show()
    }
}
