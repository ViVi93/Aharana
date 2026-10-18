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
        if (model.filterIndexReady() && !force) { categories(); return }
        val message = TextView(activity).apply { setPadding(32, 24, 32, 24); text = "Loading saved filter index…" }
        val progress = MaterialAlertDialogBuilder(activity).setTitle("Library filters")
            .setView(message).setNegativeButton("Cancel") { _, _ ->
                scan?.cancel()
            }.create()
        progress.setOnCancelListener { scan?.cancel() }
        val observer = Observer<String> { message.text = it.ifBlank { "Preparing filters…" } }
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
            } catch (_: Exception) {
                progress.dismiss()
                MaterialAlertDialogBuilder(activity).setTitle("Filters unavailable")
                    .setMessage("Saved metadata could not be indexed. Your current filters and books were not changed.")
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
        content.addView(TextView(activity).apply {
            text = "Choose multiple values in each category. Values in the same category match ANY; categories combine with AND. Counts use the full library. Search and sort still apply.\n" +
                "Applied selections persist across app restarts. Saved metadata is cached; only new/changed books need EPUB reads." +
                if (model.filterReadFailures > 0) "\n${model.filterReadFailures} books unreadable; marked separately, not missing metadata." else ""
        })
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
            text = "Rebuild metadata cache (read all EPUBs)"
            setOnClickListener { dialog.dismiss(); show(force = true) }
        })
        dialog.show()
    }

    private fun values(key: String, name: String) {
        val selected = selection[key].orEmpty().toMutableSet()
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 0, 24, 0) }
        val search = EditText(activity).apply { hint = "Search values"; isSingleLine = true }
        val list = ListView(activity).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE }
        val all = model.filterOptions(key).toMutableMap()
        // Preserve chosen values even if a new index no longer has matching books.
        selected.forEach { all.putIfAbsent(it, 0) }
        var displayed = emptyList<String>()
        fun render() {
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
        dialog.show()
    }
}
