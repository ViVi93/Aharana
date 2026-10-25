package com.vivi.aharana

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.vivi.aharana.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Plans stay in retained state, not Intent/Bundle (a library may have thousands of books). */
class OrganiseLibraryViewModel(application: Application) : AndroidViewModel(application) {
    val busy = MutableLiveData(false)
    val message = MutableLiveData("Preview before applying. Back up your library first.")
    val plan = MutableLiveData<JSONObject?>(null)
    val canUndo = MutableLiveData(false)
    private val context get() = getApplication<Application>()
    private var initialized = false

    fun initialize() {
        if (initialized) return
        initialized = true
        launchOperation("Checking previous organisation…") {
            val recovery = LibraryStorageGate.run { BookRepository.reconcileOrganisedPaths(context) }
            canUndo.postValue(recovery.optBoolean("can_undo"))
            message.postValue("Choose your library root, then preview. Uses the first author; missing authors go in Unknown Author. EPUB contents stay unchanged.")
        }
    }

    fun preview(root: String) {
        if (busy.value == true) return
        plan.value = null
        launchOperation("Reading EPUB metadata and planning filenames…") {
            require(root.startsWith("/")) { "Enter an absolute filesystem folder, not a picked content:// document." }
            val result = LibraryStorageGate.run {
                BookRepository.reconcileOrganisedPaths(context)
                JSONObject(PythonBridge(context).organiseLibrary("preview", root))
            }
            check(result.optBoolean("ok")) { result.optString("error", "Preview failed") }
            plan.postValue(result)
            message.postValue("${result.optInt("move_count")} to move · ${result.optInt("unchanged_count")} unchanged · ${result.optInt("skipped_count")} skipped. Review all paths below; nothing has moved.")
        }
    }

    fun apply() {
        val approved = plan.value ?: return
        mutate("apply", approved.toString())
    }

    fun undo() = mutate("undo", "")

    private fun mutate(action: String, payload: String) {
        if (busy.value == true) return
        launchOperation(if (action == "undo") "Restoring previous paths…" else "Organising books and updating library paths…") {
            // Do not cancel between moving files and updating Room on Activity close/rotation.
            withContext(NonCancellable) {
                LibraryStorageGate.run {
                    BookRepository.reconcileOrganisedPaths(context)
                    val jobs = AppDatabase.getInstance(context).downloadJobDao().getAll()
                    check(jobs.none { it.type != "metadata" && it.status in setOf("queued", "waiting", "running") }) {
                        "Finish or cancel queued downloads/updates first, then try again."
                    }
                    val bridge = PythonBridge(context)
                    val database = AppDatabase.getInstance(context)
                    val (result, recovery) = database.withTransaction {
                        // Hold Room's writer reservation through preflight, filesystem work and
                        // reconciliation. Another writer cannot claim a destination in between.
                        val planned = if (action == "apply") JSONObject(payload).getJSONArray("entries") else {
                            val reverse = JSONObject(bridge.organiseLibrary("undo_preview"))
                            check(reverse.optBoolean("ok")) { reverse.optString("error", "Cannot preview undo") }
                            reverse.getJSONArray("moves")
                        }
                        val moves = buildList {
                            for (i in 0 until planned.length()) {
                                val row = planned.getJSONObject(i)
                                if (action == "undo" || row.optString("status") == "move") {
                                    add(row.getString("source") to row.getString("target"))
                                }
                            }
                        }
                        requireUnoccupiedOrganisationTargets(database.bookDao().getAll().map { it.id to it.filePath }, moves)
                        val applied = JSONObject(bridge.organiseLibrary(action, payload))
                        // Do not throw inside this transaction for a partial recovery failure:
                        // completed moves must commit even when another file needs attention.
                        val recovered = BookRepository.reconcileOrganisedPaths(context, failOnError = false)
                        applied to recovered
                    }
                    canUndo.postValue(recovery.optBoolean("can_undo"))
                    plan.postValue(null)
                    check(recovery.optBoolean("ok")) { recovery.optString("error", "Recovery needs attention") }
                    check(result.optBoolean("ok")) { result.optString("error", "Organisation incomplete; see undo/recovery") }
                    val count = result.optJSONArray("moves")?.length() ?: 0
                    message.postValue(if (action == "undo") "Restored $count book paths. Library records updated."
                        else "Organised $count books. Library records updated. You can undo this operation below.")
                }
            }
        }
    }

    private fun launchOperation(label: String, work: suspend () -> Unit) {
        if (busy.value == true) return
        busy.value = true
        message.value = label
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { work() }
            } catch (e: Exception) {
                message.value = "Not completed: ${e.message ?: e.javaClass.simpleName}"
                DiagnosticLog.appendException(context, "Library.Organise", "operation_failed", e)
            } finally {
                busy.value = false
            }
        }
    }
}
