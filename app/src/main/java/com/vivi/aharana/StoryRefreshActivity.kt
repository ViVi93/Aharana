package com.vivi.aharana

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject

/** Original fanfiction site only; catalogue-provider preview remains a separate action. */
class StoryRefreshActivity : BaseActivity() {
    private lateinit var model: StoryRefreshViewModel
    private lateinit var fields: LinearLayout
    private lateinit var status: TextView
    private lateinit var source: TextView
    private lateinit var coverStatus: TextView
    private lateinit var coverImage: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var fetch: MaterialButton
    private lateinit var fetchCover: MaterialButton
    private lateinit var applyMetadata: MaterialButton
    private lateinit var applyCover: MaterialButton
    private lateinit var applyBoth: MaterialButton
    private lateinit var done: MaterialButton
    private var renderedPreview: JSONObject? = null
    private val fieldChecks = mutableMapOf<String, MaterialCheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_story_refresh)
        applyTopInset(findViewById(R.id.appBar))
        applyBottomInset(findViewById(R.id.contentScroll))
        fields = findViewById(R.id.storyFields)
        status = findViewById(R.id.storyRefreshStatus)
        source = findViewById(R.id.storySource)
        coverStatus = findViewById(R.id.storyCoverStatus)
        coverImage = findViewById(R.id.storyCoverImage)
        progress = findViewById(R.id.storyRefreshProgress)
        fetch = findViewById(R.id.buttonRefetchStory)
        fetchCover = findViewById(R.id.buttonFetchStoryCover)
        applyMetadata = findViewById(R.id.buttonApplyStoryMetadata)
        applyCover = findViewById(R.id.buttonApplyStoryCover)
        applyBoth = findViewById(R.id.buttonApplyStoryBoth)
        done = findViewById(R.id.buttonStoryDone)
        model = ViewModelProvider(this)[StoryRefreshViewModel::class.java]
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        fetch.setOnClickListener { model.load(false) }
        fetchCover.setOnClickListener { model.load(true) }
        applyMetadata.setOnClickListener { confirmApply(true, false) }
        applyCover.setOnClickListener { confirmApply(false, true) }
        applyBoth.setOnClickListener { confirmApply(true, true) }
        done.setOnClickListener { leave() }
        model.state.observe(this) { render(it) }
        val path = intent.getStringExtra("epub_path").orEmpty()
        if (path.isBlank()) {
            status.text = "No EPUB selected"
            return
        }
        val selection = savedInstanceState?.getStringArrayList("selected_fields")?.toSet()
        model.initialize(path, selection)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // At most the small allowlisted set of field keys, never cover or preview bytes.
        outState.putStringArrayList("selected_fields", ArrayList(model.state.value?.selected.orEmpty()))
        super.onSaveInstanceState(outState)
    }

    private fun leave() {
        val state = model.state.value ?: return
        if (state.applying) {
            showMessage("Please wait while the EPUB is saved")
            return
        }
        state.result?.let { result ->
            // The exact library row, cover and path are already updated. Keep Intent small.
            setResult(RESULT_OK, Intent().putExtra("story_refreshed", true)
                .putExtra("path", result.optString("output_path")))
        }
        finish()
    }

    private fun confirmApply(metadata: Boolean, cover: Boolean) {
        val state = model.state.value ?: return
        val count = if (metadata) state.selected.size else 0
        MaterialAlertDialogBuilder(this)
            .setTitle(when { metadata && cover -> "Apply selected + cover?"; cover -> "Apply cover only?"; else -> "Apply selected metadata?" })
            .setMessage("Replace $count selected metadata fields${if (cover) " and the cover" else ""}. " +
                "Unchecked fields, story chapters and the existing title page remain unchanged. " +
                "A filesystem original is backed up. For a picked document, a new copy is saved to your output folder and the original is kept.")
            .setPositiveButton("Apply") { _, _ -> model.apply(metadata, cover) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun render(state: StoryRefreshState) {
        status.text = state.message
        progress.visibility = if (state.busy) View.VISIBLE else View.GONE
        val preview = state.preview
        if (preview != null && preview !== renderedPreview) {
            renderedPreview = preview
            source.text = "Source: ${preview.optString("source_url")}"
            fields.removeAllViews()
            fieldChecks.clear()
            val rows = preview.optJSONArray("fields") ?: JSONArray()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val key = row.optString("key")
                val changed = row.optBoolean("changed")
                val box = MaterialCheckBox(this).apply {
                    text = "${row.optString("label", key)}${if (changed) " — changed" else " — unchanged"}"
                    isChecked = key in state.selected
                    isEnabled = changed && !state.busy && state.result == null
                    setOnCheckedChangeListener { _, checked -> model.select(key, checked) }
                }
                fieldChecks[key] = box
                fields.addView(box)
                fields.addView(TextView(this).apply {
                    text = "Current: ${formatValue(row.opt("display_current") ?: row.opt("current"))}\nStory site: ${formatValue(row.opt("display_online") ?: row.opt("online"))}"
                    setTextIsSelectable(true)
                    setPadding(16, 0, 16, 24)
                })
            }
            if (rows.length() == 0) fields.addView(TextView(this).apply { text = "This site returned no supported metadata fields." })
            val warnings = preview.optJSONArray("warnings")
            val cover = preview.optJSONObject("cover")
            coverStatus.text = if (cover?.optBoolean("available") == true) {
                "Original-site cover ready (${cover.optInt("width")} × ${cover.optInt("height")}). Apply it separately or with selected metadata."
            } else cover?.optString("error")?.takeIf { it.isNotBlank() }
                ?: "Cover not fetched. Tap Fetch site cover to preview it; sites without a cover leave your existing cover unchanged."
            if (warnings != null && warnings.length() > 0) {
                coverStatus.append("\n" + (0 until warnings.length()).joinToString("\n") { warnings.optString(it) })
            }
            coverImage.visibility = View.GONE
            if (cover?.optBoolean("available") == true) {
                try {
                    val bytes = android.util.Base64.decode(cover.optString("image_data"), android.util.Base64.DEFAULT)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 800 || bounds.outHeight / sample > 800) sample *= 2
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sample })
                    if (bitmap != null) { coverImage.setImageBitmap(bitmap); coverImage.visibility = View.VISIBLE }
                    else coverStatus.append("\nImage cannot be displayed on this device.")
                } catch (e: Exception) {
                    coverStatus.append("\nCannot display cover: ${e.message}")
                }
            }
        }
        val enabled = !state.busy && state.result == null
        val hasCover = preview?.optJSONObject("cover")?.optBoolean("available") == true
        val hasSelection = state.selected.isNotEmpty()
        val rows = preview?.optJSONArray("fields") ?: JSONArray()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            fieldChecks[row.optString("key")]?.isEnabled = enabled && row.optBoolean("changed")
        }
        fetch.isEnabled = enabled
        fetchCover.isEnabled = enabled && preview != null
        applyMetadata.isEnabled = enabled && hasSelection
        applyCover.isEnabled = enabled && hasCover
        applyBoth.isEnabled = enabled && hasSelection && hasCover
        done.isEnabled = !state.applying
        done.text = if (state.result != null) "Done" else "Close without applying"
    }

    private fun formatValue(value: Any?): String = when {
        value == null || value == JSONObject.NULL -> "(none)"
        value is JSONArray -> (0 until value.length()).joinToString(", ") { value.optString(it) }.ifBlank { "(none)" }
        else -> value.toString().ifBlank { "(none)" }
    }
}
