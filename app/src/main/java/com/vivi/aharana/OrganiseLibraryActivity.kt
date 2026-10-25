package com.vivi.aharana

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray

class OrganiseLibraryActivity : BaseActivity() {
    private lateinit var model: OrganiseLibraryViewModel
    private lateinit var root: EditText
    private lateinit var preview: MaterialButton
    private lateinit var apply: MaterialButton
    private lateinit var undo: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_organise_library)
        applyTopInset(findViewById(R.id.appBar))
        applyBottomInset(findViewById(R.id.organiseContent), includeIme = true)
        model = ViewModelProvider(this)[OrganiseLibraryViewModel::class.java]
        root = findViewById(R.id.organiseRoot)
        preview = findViewById(R.id.buttonOrganisePreview)
        apply = findViewById(R.id.buttonOrganiseApply)
        undo = findViewById(R.id.buttonOrganiseUndo)
        if (savedInstanceState == null) root.setText(intent.getStringExtra("library_root").orEmpty())
        root.doAfterTextChanged { renderControls() }
        val adapter = PathsAdapter()
        findViewById<RecyclerView>(R.id.organiseRows).apply {
            layoutManager = LinearLayoutManager(this@OrganiseLibraryActivity)
            this.adapter = adapter
        }
        preview.setOnClickListener { model.preview(root.text.toString().trim()) }
        apply.setOnClickListener {
            val plan = model.plan.value ?: return@setOnClickListener
            if (!organisationRootMatches(root.text.toString(), plan.optString("root"))) {
                showMessage("Folder changed. Preview again before applying.")
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Organise ${plan.optInt("move_count")} books?")
                .setMessage("Rename and move the previewed EPUBs under ${plan.optString("root")} into Author/Title.epub. Library paths will be updated. Back up your library first; undo restores paths, not older book contents.")
                .setPositiveButton("Organise") { _, _ -> model.apply() }
                .setNegativeButton("Cancel", null).show()
        }
        undo.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Undo last organisation?")
                .setMessage("Restore the last operation's original paths, even if you have since updated the books. Existing files are never overwritten. This applies to the last organisation, not the folder currently typed above.")
                .setPositiveButton("Undo") { _, _ -> model.undo() }
                .setNegativeButton("Cancel", null).show()
        }
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        model.busy.observe(this) {
            findViewById<View>(R.id.organiseProgress).visibility = if (it) View.VISIBLE else View.GONE
            renderControls()
        }
        model.message.observe(this) { findViewById<TextView>(R.id.organiseStatus).text = it }
        model.canUndo.observe(this) { renderControls() }
        model.plan.observe(this) {
            adapter.rows = it?.optJSONArray("entries") ?: JSONArray()
            adapter.notifyDataSetChanged()
            renderControls()
        }
        model.initialize()
    }

    private fun leave() {
        if (model.busy.value == true) showMessage("Please wait for this operation to finish") else finish()
    }

    private fun renderControls() {
        val idle = model.busy.value != true
        root.isEnabled = idle
        preview.isEnabled = idle
        apply.isEnabled = idle && (model.plan.value?.optInt("move_count") ?: 0) > 0 &&
            model.plan.value?.let { organisationRootMatches(root.text.toString(), it.optString("root")) } == true
        undo.isEnabled = idle && model.canUndo.value == true
    }

    private class PathsAdapter : RecyclerView.Adapter<PathsAdapter.Holder>() {
        var rows = JSONArray()
        class Holder(val label: TextView) : RecyclerView.ViewHolder(label)
        override fun getItemCount(): Int = rows.length()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows.getJSONObject(position)
            holder.label.text = "${position + 1}. ${row.optString("status").replaceFirstChar { it.uppercase() }}\n" +
                row.optString("source") + if (row.optString("status") == "skipped") {
                    "\n${row.optString("error", "Metadata could not be read")}"
                } else "\n→ ${row.optString("target")}"
        }
    }
}
