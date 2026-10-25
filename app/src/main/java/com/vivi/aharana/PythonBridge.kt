package com.vivi.aharana

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.PyObject
import org.json.JSONObject

class PythonBridge(private val context: Context) {

    private var module: PyObject? = null
    private var initError: String? = null

    init {
        try {
            val python = Python.getInstance()
            module = python.getModule("fanficfare_bridge")
        } catch (e: Exception) {
            initError = e.message ?: "unknown"
        }
    }

    fun fanficfareDownload(url: String, outDir: String): String =
        safeCall("download_story", url, outDir)

    fun fanficfareMetadata(url: String): String =
        safeCall("get_metadata", url)

    fun fanficfareListSites(): String =
        safeCall("list_sites")

    fun fanficfareLoginStatus(url: String): String =
        safeCall("get_login_status", url)

    fun fanficfareLiteroticaConfigStatus(url: String): String =
        safeCall("get_literotica_config_status", url)

    fun getLoginStatus(url: String): String =
        safeCall("get_login_status", url)

    fun organiseLibrary(action: String, payload: String = ""): String = try {
        Python.getInstance().getModule("library_organizer").callAttr(
            "json_call", action, payload, organisationPrivatePath(context.filesDir, "organisation").absolutePath
        ).toString()
    } catch (e: Exception) {
        JSONObject().put("ok", false).put("error", e.message ?: "Organisation failed").toString()
    }

    fun scanEpubDir(directory: String): String =
        safeCall("scan_epub_dir", directory)

    fun saveLibraryIndex(indexPath: String, booksJson: String): String =
        safeCall("save_library_index", indexPath, booksJson)

    fun loadLibraryIndex(indexPath: String): String =
        safeCall("load_library_index", indexPath)

    fun getEpubUpdateUrl(epubPath: String): String =
        safeCall("get_epub_update_url", epubPath)

    fun deleteEpub(epubPath: String): String =
        safeCall("delete_epub", epubPath)

    fun updateEpubFromPath(epubPath: String, outDir: String): String =
        safeCall("update_epub_from_path", epubPath, outDir)

    fun forceDownloadFromEpub(epubPath: String, outDir: String): String =
        safeCall("force_download_from_epub", epubPath, outDir)

    fun getCoverFromEpub(epubPath: String): String =
        safeCall("get_cover_from_epub", epubPath)

    fun readDownloadDebug(): String =
        safeCall("read_download_debug")

    fun runDnsDiagnostics(): String =
        safeCall("run_dns_diagnostics")

    fun clearDownloadDebug(): String =
        safeCall("clear_download_debug")

    fun parseStoryUrlText(text: String, normalize: Boolean = true): String =
        safeCall("parse_story_url_text", text, normalize)

    fun listStoryUrls(pageUrl: String, normalize: Boolean = false): String =
        safeCall("list_story_urls", pageUrl, normalize)

    fun normalizeStoryUrls(pageUrl: String): String =
        safeCall("normalize_story_urls", pageUrl)

    fun downloadStoryList(pageUrl: String): String =
        safeCall("download_story_list", pageUrl)

    fun exportEpubOpf(epubPath: String, outputPath: String? = null): String =
        if (outputPath != null) safeCall("export_epub_opf", epubPath, outputPath)
        else safeCall("export_epub_opf", epubPath)

    fun importEpubOpf(epubPath: String, opfXml: String, outputPath: String? = null, backupSuffix: String? = null): String {
        val args = mutableListOf<Any>(epubPath, opfXml)
        if (outputPath != null) args.add(outputPath)
        if (backupSuffix != null) args.add(backupSuffix)
        return safeCall("import_epub_opf", *args.toTypedArray())
    }

    fun readEpubMetadata(epubPath: String): String =
        safeCall("read_epub_metadata", epubPath)

    fun writeEpubMetadata(epubPath: String, fieldsJson: String, outputPath: String? = null, backupSuffix: String? = null): String {
        // Always pass outputPath and backupSuffix as explicit positional args.
        // The old conditional approach skipped them when null, causing
        // backupSuffix to land in Python's output_path parameter — producing
        // a relative path like ".metadata_edit_bak" written to the read-only
        // Python cwd, triggering OSError: [Errno 30] Read-only file system.
        return safeCall("write_epub_metadata", epubPath, fieldsJson, outputPath, backupSuffix)
    }

    fun replaceEpubCover(epubPath: String, imageDataBase64: String, imageMime: String, outputPath: String? = null, backupSuffix: String? = null): String {
        // Always pass outputPath and backupSuffix — see writeEpubMetadata comment.
        return safeCall("replace_epub_cover", epubPath, imageDataBase64, imageMime, outputPath, backupSuffix)
    }

    fun getEpubMetadataSummary(epubPath: String): String =
        safeCall("get_epub_metadata_summary", epubPath)

    // --- Merge books: combine several EPUBs into one new book ---

    fun mergeBooksPreview(pathsJson: String, baseIndex: Int = 0): String =
        safeCall("epub_merge_preview", pathsJson, baseIndex)

    fun mergeBooks(
        pathsJson: String,
        outputPath: String? = null,
        title: String? = null,
        author: String? = null,
        baseIndex: Int = 0,
        tocStyle: String = "sections",
        shortenLabels: Boolean = false,
        renumberChapters: Boolean = false,
    ): String = safeCall(
        "epub_merge_books", pathsJson, outputPath, title, author, baseIndex, tocStyle,
        shortenLabels, renumberChapters
    )

    fun epubMetadataJson(epubPath: String): String =
        safeCall("epub_metadata_json", epubPath)

    // --- Phase 7: metadata preview / cover selection bridge adapters ---

    fun lookupOnlineMetadata(title: String, author: String, isbn: String): String =
        safeCall("lookup_online_metadata", title, author, isbn)

    fun diffMetadata(currentJson: String, onlineJson: String): String =
        safeCall("diff_metadata", currentJson, onlineJson)

    fun extractCoverCandidates(resultsJson: String): String =
        safeCall("extract_cover_candidates", resultsJson)

    fun downloadCover(url: String, timeout: Int = 15): String =
        safeCall("download_cover", url, timeout)

    fun applyMetadataAndCover(
        epubPath: String,
        fieldsJson: String,
        imageDataBase64: String? = null,
        imageMime: String? = null,
        outputPath: String? = null,
        backupSuffix: String? = null,
    ): String {
        // All args passed explicitly (null → Python None via safeCall's Any? vararg)
        return safeCall("apply_metadata_and_cover", epubPath, fieldsJson, imageDataBase64 ?: "", imageMime ?: "", outputPath, backupSuffix)
    }

    fun getLibraryFacets(epubPath: String): String =
        safeCall("get_library_facets", epubPath)

    fun getStoryDetails(epubPath: String): String =
        safeCall("get_story_details", epubPath)

    fun previewStoryRefresh(epubPath: String, includeCover: Boolean = false): String =
        safeCall("preview_story_refresh", epubPath, includeCover)

    fun applyStoryRefresh(epubPath: String, previewJson: String, selectedFieldsJson: String,
                          applyCover: Boolean, outputPath: String? = null): String =
        safeCall("apply_story_refresh", epubPath, previewJson, selectedFieldsJson, applyCover, outputPath)

    fun getInitError(): String? = initError

    fun getFanFicFareError(): String? {
        return try {
            val result = safeCall("get_fanficfare_error")
            if (result.isBlank() || result == "None") null else result
        } catch (e: Exception) {
            null
        }
    }

    fun diagnoseFanFicFareImports(): String =
        safeCall("diagnose_fanficfare_imports")

    fun getConfigStatus(): JSONObject {
        val raw = try { safeCall("get_config_status") } catch (e: Exception) { null }
        return try {
            org.json.JSONObject(raw ?: "{}")
        } catch (e: Exception) {
            org.json.JSONObject().put("exists", false).put("parse_error", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun testConfiguration(url: String): JSONObject {
        val raw = try { safeCall("test_configuration", url) } catch (e: Exception) { null }
        return try {
            org.json.JSONObject(raw ?: "{}")
        } catch (e: Exception) {
            org.json.JSONObject().put("ok", false).put("error", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun getFanFicFareVersion(): String {
        return try {
            val raw = safeCall("get_fanficfare_version")
            if (raw.isBlank() || raw == "None") "" else raw
        } catch (e: Exception) {
            ""
        }
    }

    fun initialize(configDir: String) {
        try {
            safeCall("set_config_dir", configDir)
        } catch (e: Exception) {
            // non-fatal; config dir will fall back to default behavior
        }
    }

    private fun safeCall(method: String, vararg args: Any?): String {
        val mod = module
        if (mod == null) {
            return JSONObject().put("ok", false).put("error", "Bridge init failed: $initError").toString()
        }
        return try {
            val result = mod.callAttr(method, *args)
            result.toString()
        } catch (e: Throwable) {
            JSONObject().put("ok", false).put("error", e.message ?: "unknown").toString()
        }
    }
}
