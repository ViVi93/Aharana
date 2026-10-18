package com.vivi.aharana

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class StoryRefreshState(
    val preview: JSONObject? = null,
    val selected: Set<String> = emptySet(),
    val busy: Boolean = false,
    val applying: Boolean = false,
    val message: String = "",
    val result: JSONObject? = null
)

/** Owns network/apply work across rotation; no preview/cover bytes travel in Intent or Bundle. */
internal class StoryRefreshViewModel(application: Application) : AndroidViewModel(application) {
    val state = MutableLiveData(StoryRefreshState())
    private var sourcePath: String? = null
    private var restoredSelection: Set<String>? = null
    private val bridge by lazy { PythonBridge(getApplication<Application>()) }
    private val store by lazy { StoryRefreshStore(getApplication(), bridge) }

    fun initialize(path: String, restored: Set<String>?) {
        if (sourcePath != null) return
        sourcePath = path
        restoredSelection = restored
        load(false)
    }

    fun select(key: String, checked: Boolean) {
        val current = state.value ?: return
        if (current.busy || current.result != null) return
        val selected = current.selected.toMutableSet()
        if (checked) selected.add(key) else selected.remove(key)
        state.value = current.copy(selected = selected)
    }

    fun load(includeCover: Boolean) {
        val previous = state.value ?: return
        if (previous.busy || previous.result != null) return
        val path = sourcePath ?: return
        state.value = previous.copy(busy = true, message = if (includeCover)
            "Waiting for engine; fetching metadata and original-site cover…" else
            "Waiting for engine; fetching original-site metadata…")
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    FanFicFareGate.run {
                        bridge.initialize(SettingsActivity.getConfigDir(getApplication()).absolutePath)
                        DiagnosticLog.append(getApplication(), "StoryRefresh", "preview_start cover=$includeCover")
                        val raw = StorageBridge.withLocalEpub(getApplication(), path) {
                            bridge.previewStoryRefresh(it.absolutePath, includeCover)
                        } ?: error("Cannot read this EPUB")
                        checkedJson(raw)
                    }
                }
                val fields = result.optJSONArray("fields") ?: JSONArray()
                val availableChanged = (0 until fields.length()).mapNotNull { index ->
                    val row = fields.optJSONObject(index)
                    if (row?.optBoolean("changed") == true) row.optString("key") else null
                }.toSet()
                // Explicit choices only. Fetching a cover never selects extra metadata fields.
                val selected = (restoredSelection ?: previous.selected).intersect(availableChanged)
                restoredSelection = null
                state.value = previous.copy(preview = result, selected = selected, busy = false,
                    message = "Preview ready. Select only fields you want to replace; unchecked fields stay unchanged.")
                DiagnosticLog.append(getApplication(), "StoryRefresh", "preview_success fields=${fields.length()}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                DiagnosticLog.appendException(getApplication(), "StoryRefresh", "preview_failed", e)
                state.value = previous.copy(busy = false, message = "Refresh failed: ${e.message}")
            }
        }
    }

    fun apply(withMetadata: Boolean, withCover: Boolean) {
        val current = state.value ?: return
        val preview = current.preview ?: return
        if (current.busy || current.result != null) return
        val selected = if (withMetadata) current.selected else emptySet()
        if ((withMetadata && selected.isEmpty()) || (!withMetadata && !withCover)) return
        if (withCover && preview.optJSONObject("cover")?.optBoolean("available") != true) return
        val path = sourcePath ?: return
        state.value = current.copy(busy = true, applying = true, message = "Waiting for engine; saving safely with backup…")
        viewModelScope.launch {
            try {
                // Once accepted, a bounded file rewrite/library update finishes despite rotation/closing.
                val result = withContext(NonCancellable + Dispatchers.IO) {
                    FanFicFareGate.run {
                        DiagnosticLog.append(getApplication(), "StoryRefresh", "apply_start fields=${selected.size} cover=$withCover")
                        store.apply(path, preview, selected, withCover)
                    }
                }
                state.value = current.copy(busy = false, applying = false, result = result,
                    message = result.optString("notice", "Saved. Chapters and title page unchanged.") +
                        "\nSaved to: ${result.optString("output_path")}\nOriginal/backup: ${result.optString("backup_path")}")
                DiagnosticLog.append(getApplication(), "StoryRefresh", "apply_success chapters_preserved=${result.optBoolean("chapters_preserved")}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                DiagnosticLog.appendException(getApplication(), "StoryRefresh", "apply_failed", e)
                state.value = current.copy(busy = false, applying = false, message = "Apply failed: ${e.message}")
            }
        }
    }

    private fun checkedJson(raw: String): JSONObject {
        val result = JSONObject(raw)
        check(result.optBoolean("ok")) { result.optString("error", "Story-site operation failed") }
        return result
    }
}
