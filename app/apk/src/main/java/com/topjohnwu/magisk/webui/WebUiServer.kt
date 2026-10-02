package com.topjohnwu.magisk.webui

import android.content.Context
import android.os.Build
import com.topjohnwu.magisk.core.BuildConfig as CoreBuildConfig
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.di.ServiceLocator
import com.topjohnwu.magisk.core.model.module.LocalModule
import com.topjohnwu.magisk.core.model.su.SuPolicy
import com.topjohnwu.superuser.Shell
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal class WebUiServer(
    private val context: Context,
    bindHost: String,
    port: Int,
    private val onConfigChanged: () -> Unit,
) : NanoHTTPD(bindHost, port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            return text(Response.Status.OK, "text/plain", "")
        }

        val path = session.uri.substringBefore('?')
        when (path) {
            "/", "/index.html" -> return asset("webui/index.html", "text/html; charset=utf-8")
            "/app.css" -> return asset("webui/app.css", "text/css; charset=utf-8")
            "/app.js" -> return asset("webui/app.js", "application/javascript; charset=utf-8")
        }

        if (path == "/api/public/config") {
            return json(publicConfigJson())
        }

        if (!path.startsWith("/api/")) {
            return jsonError(Response.Status.NOT_FOUND, "not_found", "Resource not found")
        }

        if (!isAuthorized(session)) {
            return jsonError(Response.Status.UNAUTHORIZED, "unauthorized", "Authentication required")
        }

        return try {
            when {
                path == "/api/status" && session.method == Method.GET -> json(statusJson())
                path == "/api/modules" && session.method == Method.GET -> json(modulesJson())
                path.startsWith("/api/modules/") -> moduleRoute(session, path)
                path == "/api/superuser" && session.method == Method.GET -> json(superuserJson())
                path.startsWith("/api/superuser/") -> superuserRoute(session, path)
                path == "/api/logs/su" -> suLogRoute(session)
                path == "/api/logs/magisk" -> magiskLogRoute(session)
                path == "/api/settings" -> settingsRoute(session)
                path == "/api/config" -> configRoute(session)
                path == "/api/install/module" && session.method == Method.POST -> installModuleRoute(session)
                path == "/api/install/patch" && session.method == Method.POST -> patchImageRoute(session)
                path.startsWith("/api/power/") && session.method == Method.POST -> powerRoute(path)
                else -> jsonError(Response.Status.NOT_FOUND, "not_found", "API route not found")
            }
        } catch (error: Throwable) {
            jsonError(
                Response.Status.INTERNAL_ERROR,
                "server_error",
                error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        if (Config.webUiAuthMode == Config.Value.WEBUI_AUTH_NONE) return true
        val expected = WebUiManager.effectiveToken()
        if (expected.isBlank()) return false
        val authorization = session.headers["authorization"].orEmpty()
        if (!authorization.startsWith("Bearer ", ignoreCase = true)) return false
        val supplied = authorization.substringAfter(' ').trim()
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            supplied.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun publicConfigJson() = JSONObject().apply {
        put("authMode", authModeName(Config.webUiAuthMode))
        put("theme", themeName(Config.webUiTheme))
        put("port", Config.webUiPort)
    }

    private fun statusJson() = JSONObject().apply {
        put("appVersion", CoreBuildConfig.VERSION_NAME)
        put("appVersionCode", CoreBuildConfig.VERSION_CODE)
        put("magiskVersion", Info.env.versionString)
        put("magiskVersionCode", Info.env.versionCode)
        put("active", Info.isMagiskInstalled)
        put("rooted", Info.isRooted)
        put("zygiskConfigured", Config.zygisk)
        put("zygiskRunning", Info.isZygiskEnabled)
        put("denyList", Config.denyList)
        put("android", Build.VERSION.RELEASE)
        put("sdk", Build.VERSION.SDK_INT)
        put("device", Build.MODEL ?: Build.DEVICE)
        put("manufacturer", Build.MANUFACTURER)
        put("webui", publicConfigJson())
    }

    private fun modulesJson() = JSONObject().apply {
        val modules = JSONArray()
        LocalModule.installed().forEach { module ->
            modules.put(JSONObject().apply {
                put("id", module.id)
                put("name", module.name)
                put("version", module.version)
                put("versionCode", module.versionCode)
                put("author", module.author)
                put("description", module.description)
                put("enabled", module.enable)
                put("remove", module.remove)
                put("update", module.update)
                put("hasAction", module.action)
            })
        }
        put("modules", modules)
    }

    private fun moduleRoute(session: IHTTPSession, path: String): Response {
        val segments = path.split('/').filter(String::isNotBlank)
        if (segments.size < 3) return jsonError(Response.Status.BAD_REQUEST, "bad_module", "Missing module id")
        val id = URLDecoder.decode(segments[2], StandardCharsets.UTF_8.name())
        val module = LocalModule.installed().firstOrNull { it.id == id }
            ?: return jsonError(Response.Status.NOT_FOUND, "module_not_found", "Module not found")
        return when {
            segments.size == 4 && segments[3] == "state" && session.method == Method.POST -> {
                val body = parseJsonBody(session)
                if (body.has("enabled")) module.enable = body.getBoolean("enabled")
                if (body.has("remove")) module.remove = body.getBoolean("remove")
                json(JSONObject().put("ok", true))
            }
            segments.size == 4 && segments[3] == "action" && session.method == Method.POST -> {
                if (!module.action) return jsonError(Response.Status.BAD_REQUEST, "no_action", "Module has no action.sh")
                val result = Shell.cmd("${Const.MODULE_PATH}/$id/action.sh").exec()
                json(JSONObject().apply {
                    put("ok", result.isSuccess)
                    put("stdout", JSONArray(result.out))
                    put("stderr", JSONArray(result.err))
                })
            }
            else -> jsonError(Response.Status.NOT_FOUND, "not_found", "Module route not found")
        }
    }

    private fun superuserJson() = JSONObject().apply {
        val policies = JSONArray()
        runBlocking(Dispatchers.IO) {
            ServiceLocator.suPolicyDao.fetchAll().forEach { policy ->
                policies.put(policyJson(policy))
            }
        }
        put("policies", policies)
    }

    private fun policyJson(policy: SuPolicy) = JSONObject().apply {
        put("uid", policy.uid)
        put("policy", policy.policy.toString().lowercase())
        put("until", policy.until)
        put("logging", policy.logging)
        put("notification", policy.notification)
        put("apps", JSONArray().apply {
            policy.apps.forEach { app ->
                put(JSONObject().apply {
                    put("packageName", app.packageName)
                    put("appName", app.appName)
                })
            }
        })
    }

    private fun superuserRoute(session: IHTTPSession, path: String): Response {
        val uid = path.substringAfterLast('/').toIntOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "bad_uid", "Invalid UID")
        return runBlocking(Dispatchers.IO) {
            val dao = ServiceLocator.suPolicyDao
            val policy = dao.fetch(uid)
                ?: return@runBlocking jsonError(Response.Status.NOT_FOUND, "policy_not_found", "Policy not found")
            when (session.method) {
                Method.DELETE -> {
                    dao.delete(policy)
                    json(JSONObject().put("ok", true))
                }
                Method.POST -> {
                    val body = parseJsonBody(session)
                    if (body.has("policy")) {
                        policy.policy = SuPolicy.Policy.valueOf(body.getString("policy").uppercase())
                    }
                    if (body.has("notification")) policy.notification = body.getBoolean("notification")
                    if (body.has("logging")) policy.logging = body.getBoolean("logging")
                    dao.update(policy)
                    json(JSONObject().put("ok", true).put("policy", policyJson(policy)))
                }
                else -> jsonError(Response.Status.METHOD_NOT_ALLOWED, "method", "Method not allowed")
            }
        }
    }

    private fun suLogRoute(session: IHTTPSession): Response = runBlocking(Dispatchers.IO) {
        val dao = ServiceLocator.suLogDao
        when (session.method) {
            Method.GET -> {
                val logs = JSONArray()
                dao.fetchAll().forEach { log ->
                    logs.put(JSONObject().apply {
                        put("fromUid", log.fromUid)
                        put("toUid", log.toUid)
                        put("packageName", log.packageName)
                        put("appName", log.appName)
                        put("command", log.command)
                        put("action", log.action)
                        put("time", log.time)
                    })
                }
                json(JSONObject().put("logs", logs))
            }
            Method.DELETE -> {
                dao.deleteAll()
                json(JSONObject().put("ok", true))
            }
            else -> jsonError(Response.Status.METHOD_NOT_ALLOWED, "method", "Method not allowed")
        }
    }

    private fun magiskLogRoute(session: IHTTPSession): Response = when (session.method) {
        Method.GET -> json(JSONObject().put("log", Shell.cmd("cat ${Const.MAGISK_LOG}").exec().out.joinToString("\n")))
        Method.DELETE -> {
            Shell.cmd(": > ${Const.MAGISK_LOG}").exec()
            json(JSONObject().put("ok", true))
        }
        else -> jsonError(Response.Status.METHOD_NOT_ALLOWED, "method", "Method not allowed")
    }

    private fun settingsRoute(session: IHTTPSession): Response = when (session.method) {
        Method.GET -> json(settingsJson())
        Method.POST -> {
            val body = parseJsonBody(session)
            body.optBooleanValue("zygisk")?.let { Config.zygisk = it }
            body.optBooleanValue("denyList")?.let { Config.denyList = it }
            body.optIntValue("rootMode")?.let { Config.rootMode = it }
            body.optIntValue("namespaceMode")?.let { Config.suMntNamespaceMode = it }
            body.optIntValue("suAutoResponse")?.let { Config.suAutoResponse = it }
            body.optIntValue("suNotification")?.let { Config.suNotification = it }
            body.optBooleanValue("suReAuth")?.let { Config.suReAuth = it }
            body.optBooleanValue("suTapjack")?.let { Config.suTapjack = it }
            body.optBooleanValue("suRestrict")?.let { Config.suRestrict = it }
            body.optBooleanValue("checkUpdate")?.let { Config.checkUpdate = it }
            body.optBooleanValue("doh")?.let { Config.doh = it }
            json(JSONObject().put("ok", true).put("settings", settingsJson()))
        }
        else -> jsonError(Response.Status.METHOD_NOT_ALLOWED, "method", "Method not allowed")
    }

    private fun settingsJson() = JSONObject().apply {
        put("zygisk", Config.zygisk)
        put("denyList", Config.denyList)
        put("rootMode", Config.rootMode)
        put("namespaceMode", Config.suMntNamespaceMode)
        put("suAutoResponse", Config.suAutoResponse)
        put("suNotification", Config.suNotification)
        put("suReAuth", Config.suReAuth)
        put("suTapjack", Config.suTapjack)
        put("suRestrict", Config.suRestrict)
        put("checkUpdate", Config.checkUpdate)
        put("doh", Config.doh)
    }

    private fun configRoute(session: IHTTPSession): Response = when (session.method) {
        Method.GET -> json(webUiConfigJson())
        Method.POST -> {
            val body = parseJsonBody(session)
            body.optStringValue("authMode")?.let { value ->
                Config.webUiAuthMode = when (value) {
                    "none" -> Config.Value.WEBUI_AUTH_NONE
                    "custom" -> Config.Value.WEBUI_AUTH_CUSTOM_TOKEN
                    else -> Config.Value.WEBUI_AUTH_RANDOM_TOKEN
                }
            }
            body.optStringValue("customToken")?.let { Config.webUiCustomToken = it.trim() }
            body.optStringValue("theme")?.let { value ->
                Config.webUiTheme = when (value) {
                    "light" -> Config.Value.WEBUI_THEME_LIGHT
                    "dark" -> Config.Value.WEBUI_THEME_DARK
                    else -> Config.Value.WEBUI_THEME_SYSTEM
                }
            }
            if (body.optBoolean("regenerateToken", false)) {
                WebUiManager.regenerateRandomToken()
            }
            onConfigChanged()
            json(JSONObject().put("ok", true).put("config", webUiConfigJson()))
        }
        else -> jsonError(Response.Status.METHOD_NOT_ALLOWED, "method", "Method not allowed")
    }

    private fun webUiConfigJson() = JSONObject().apply {
        put("authMode", authModeName(Config.webUiAuthMode))
        put("theme", themeName(Config.webUiTheme))
        put("token", WebUiManager.effectiveToken())
    }

    private fun installModuleRoute(session: IHTTPSession): Response =
        WebUiInstaller.installModule(context, session)

    private fun patchImageRoute(session: IHTTPSession): Response =
        WebUiInstaller.patchImage(context, session)

    private fun powerRoute(path: String): Response {
        val target = path.substringAfterLast('/')
        val command = when (target) {
            "recovery" -> "reboot recovery"
            "bootloader" -> "reboot bootloader"
            else -> "reboot"
        }
        val result = Shell.cmd(command).exec()
        return json(JSONObject().apply {
            put("ok", result.isSuccess)
            put("stdout", JSONArray(result.out))
            put("stderr", JSONArray(result.err))
        })
    }

    private fun parseJsonBody(session: IHTTPSession): JSONObject {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val raw = files["postData"].orEmpty()
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    private fun asset(path: String, mime: String): Response =
        context.assets.open(path).use { input ->
            val bytes = input.readBytes()
            newFixedLengthResponse(Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong())
        }.apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("X-Frame-Options", "DENY")
            addHeader("Referrer-Policy", "no-referrer")
        }

    private fun json(body: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body.toString()).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
        }

    private fun jsonError(status: Response.Status, code: String, message: String): Response =
        newFixedLengthResponse(
            status,
            "application/json; charset=utf-8",
            JSONObject().put("error", code).put("message", message).toString(),
        ).apply {
            addHeader("Cache-Control", "no-store")
        }

    private fun text(status: Response.Status, mime: String, body: String): Response =
        newFixedLengthResponse(status, mime, body)

    private fun authModeName(value: Int) = when (value) {
        Config.Value.WEBUI_AUTH_NONE -> "none"
        Config.Value.WEBUI_AUTH_CUSTOM_TOKEN -> "custom"
        else -> "random"
    }

    private fun themeName(value: Int) = when (value) {
        Config.Value.WEBUI_THEME_LIGHT -> "light"
        Config.Value.WEBUI_THEME_DARK -> "dark"
        else -> "system"
    }

    private fun JSONObject.optBooleanValue(name: String): Boolean? =
        if (has(name) && !isNull(name)) getBoolean(name) else null

    private fun JSONObject.optIntValue(name: String): Int? =
        if (has(name) && !isNull(name)) getInt(name) else null

    private fun JSONObject.optStringValue(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null
}
