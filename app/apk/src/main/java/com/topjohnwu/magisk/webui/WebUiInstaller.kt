package com.topjohnwu.magisk.webui

import android.content.Context
import android.net.Uri
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.tasks.FlashZip
import com.topjohnwu.magisk.core.tasks.MagiskInstaller
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

private const val MAX_WEBUI_UPLOAD_BYTES = 2L * 1024L * 1024L * 1024L

internal object WebUiInstaller {
    data class Result(
        val status: NanoHTTPD.Response.Status,
        val body: JSONObject,
    )

    private data class Upload(
        val file: File,
        val originalName: String,
    )

    private val operationLock = Any()

    fun installModule(
        context: Context,
        session: NanoHTTPD.IHTTPSession,
    ): Result = synchronized(operationLock) {
        if (!Info.env.isActive || !Info.isRooted) {
            return@synchronized error(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                "magisk_inactive",
                "Magisk must be active with root access before installing modules",
            )
        }

        val upload = parseUpload(context, session)
            ?: return@synchronized error(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                "missing_upload",
                "Multipart field 'file' is required",
            )

        if (!upload.originalName.lowercase().endsWith(".zip")) {
            upload.file.delete()
            return@synchronized error(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                "invalid_module_file",
                "Module upload must be a .zip file",
            )
        }

        val console = mutableListOf<String>()
        val logs = mutableListOf<String>()
        try {
            val success = runBlocking(Dispatchers.IO) {
                FlashZip(Uri.fromFile(upload.file), console, logs).exec()
            }
            Result(
                if (success) NanoHTTPD.Response.Status.OK else NanoHTTPD.Response.Status.INTERNAL_ERROR,
                operationJson(success, upload, console, logs),
            )
        } finally {
            upload.file.delete()
        }
    }

    fun patchImage(
        context: Context,
        session: NanoHTTPD.IHTTPSession,
    ): Result = synchronized(operationLock) {
        val upload = parseUpload(context, session)
            ?: return@synchronized error(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                "missing_upload",
                "Multipart field 'file' is required",
            )

        if (!isPatchCandidate(upload.originalName)) {
            upload.file.delete()
            return@synchronized error(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                "invalid_patch_file",
                "Patch upload must be .img, .bin, .tar, .tar.md5, or .zip",
            )
        }

        val console = mutableListOf<String>()
        val logs = mutableListOf<String>()
        try {
            val success = runBlocking(Dispatchers.IO) {
                MagiskInstaller.Patch(Uri.fromFile(upload.file), console, logs).exec()
            }
            Result(
                if (success) NanoHTTPD.Response.Status.OK else NanoHTTPD.Response.Status.INTERNAL_ERROR,
                operationJson(success, upload, console, logs),
            )
        } finally {
            upload.file.delete()
        }
    }

    private fun parseUpload(
        context: Context,
        session: NanoHTTPD.IHTTPSession,
    ): Upload? {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val tempPath = files["file"] ?: return null
        val source = File(tempPath)
        if (!source.isFile) return null
        if (source.length() <= 0L || source.length() > MAX_WEBUI_UPLOAD_BYTES) {
            return null
        }

        val submittedName = session.parameters["file"]
            ?.firstOrNull()
            ?.takeIf(String::isNotBlank)
            ?: "upload.bin"
        val safeName = submittedName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._() -]"), "_")
            .take(180)
            .ifBlank { "upload.bin" }

        val directory = File(context.cacheDir, "webui_uploads").apply { mkdirs() }
        val destination = File(directory, "${UUID.randomUUID()}-$safeName")
        source.copyTo(destination, overwrite = true)
        if (destination.length() <= 0L || destination.length() > MAX_WEBUI_UPLOAD_BYTES) {
            destination.delete()
            return null
        }
        return Upload(destination, safeName)
    }

    private fun operationJson(
        success: Boolean,
        upload: Upload,
        console: List<String>,
        logs: List<String>,
    ) = JSONObject().apply {
        put("ok", success)
        put("fileName", upload.originalName)
        put("size", upload.file.length())
        put("console", JSONArray(console))
        put("logs", JSONArray(logs))
    }

    private fun error(
        status: NanoHTTPD.Response.Status,
        code: String,
        message: String,
    ) = Result(
        status,
        JSONObject()
            .put("ok", false)
            .put("error", code)
            .put("message", message),
    )

    private fun isPatchCandidate(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".img") ||
            lower.endsWith(".bin") ||
            lower.endsWith(".tar") ||
            lower.endsWith(".tar.md5") ||
            lower.endsWith(".zip")
    }
}
