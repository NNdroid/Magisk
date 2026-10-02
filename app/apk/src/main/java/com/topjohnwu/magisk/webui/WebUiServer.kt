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
    port: Int,
    private val onConfigChanged: () -> Unit,
) : NanoHTTPD(port) {

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
            return jsonError(Response.Status.UNAUTHORIZED, "unauthorized", "Valid WebUI token required")
        }

        return try {
            when {
                path == "/api/status" && session.method == Method.GET -> json(statusJson())
                path == "/api/config" && session.method == Method.GET -> json(webUiConfigJson(includeToken = true))
                path == "/api/config" && session.method == Method.POST -> updateWebUiConfig(session)
                path == "/api/install/module" && session.method == Method.POST ->
                    installResponse(WebUiInstaller.installModule(context, session))
                path == "/api/install/patch" && session.method == Method.POST ->
                    installResponse(WebUiInstaller.patchImage(context, session))
                path == "/api/modules" && session.method == Method.GET -> modulesJson()
                path.startsWith("/api/modules/") -> moduleAction(path, session)
                path == "/api/superuser" && session.method == Method.GET -> superuserJson()
                path.startsWith("/api/superuser/") -> superuserAction(path, session)
                path == "/api/logs/su" && session.method == Method.GET -> suLogsJson()
                path == "/api/logs/su" && session.method == Method.DELETE -> clearSuLogs()
                path == "/api/logs/magisk" && session.method == Method.GET -> magiskLogJson()
                path == "/api/logs/magisk" && session.method == Method.DELETE -> clearMagiskLog()
                path == "/api/settings" && session.method == Method.GET -> json(settingsJson())
                path == "/api/settings" && session.method == Method.POST -> updateSettings(session)
                path == "/api/power" && session.method == Method.POST -> powerAction(session)
                else -> jsonError(Response.Status.NOT_FOUND, "not_found", "API endpoint not found")
            }
        } catch (error: Throwable) {
            jsonError(
                Response.Status.INTERNAL_ERROR,
                "internal_error",
                error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun publicConfigJson() = JSONObject().apply {
        put("name", "Magisk for Android TV")
        put("port", Config.webUiPort)
        put("enabled", Config.webUiEnabled)
        put("authMode", authModeName(Config.webUiAuthMode))
        put("theme", themeModeName(Config.webUiTheme))
        put("requiresAuth", Config.webUiAuthMode != Config.Value.WEBUI_AUTH_NONE)
    }

    private fun statusJson() = JSONObject().apply {
        put("appVersion", CoreBuildConfig.APP_VERSION_NAME)
        put("appVersionCode", CoreBuildConfig.APP_VERSION_CODE)
        put("magiskVersion", Info.env.versionString)
        put("magiskVersionCode", Info.env.versionCode)
        put("rooted", Info.isRooted)
        put("active", Info.env.isActive)
        put("zygiskRunning", Info.isZygiskEnabled)
        put("zygiskConfigured", Config.zygisk)
        put("denyList", Config.denyList)
        put("showSuperuser", Info.showSuperUser)
        put("device", Build.MODEL)
        put("manufacturer", Build.MANUFACTURER)
        put("android", Build.VERSION.RELEASE)
        put("sdk", Build.VERSION.SDK_INT)
        put("webui", webUiConfigJson(includeToken = false))
    }

    private fun webUiConfigJson(includeToken: Boolean) = JSONObject().apply {
        put("enabled", Config.webUiEnabled)
        put("port", Config.webUiPort)
        put("authMode", authModeName(Config.webUiAuthMode))
        put("theme", themeModeName(Config.webUiTheme))
        if (includeToken && Config.webUiAuthMode != Config.Value.WEBUI_AUTH_NONE) {
            put("token", WebUiManager.effectiveToken())
        }
    }

    private fun updateWebUiConfig(session: IHTTPSession): Response {
        val body = requestJson(session)

        val requestedAuth = body.optString("authMode", authModeName(Config.webUiAuthMode))
        val newAuthMode = when (requestedAuth) {
            "none" -> Config.Value.WEBUI_AUTH_NONE
            "random" -> Config.Value.WEBUI_AUTH_RANDOM_TOKEN
            "custom" -> Config.Value.WEBUI_AUTH_CUSTOM_TOKEN
            else -> return jsonError(Response.Status.BAD_REQUEST, "invalid_auth_mode", "Unknown authentication mode")
        }

        if (body.optBoolean("regenerateRandomToken", false)) {
            WebUiManager.regenerateRandomToken()
        }

        if (newAuthMode == Config.Value.WEBUI_AUTH_CUSTOM_TOKEN) {
            val custom = body.optString("customToken", Config.webUiCustomToken)
            if (custom.length < 8) {
                return jsonError(
                    Response.Status.BAD_REQUEST,
                    "weak_custom_token",
                    "Custom token must contain at least 8 characters",
                )
            }
            Config.webUiCustomToken = custom
        }
        Config.webUiAuthMode = newAuthMode
        if (newAuthMode == Config.Value.WEBUI_AUTH_RANDOM_TOKEN) {
            WebUiManager.ensureRandomToken()
        }

        when (body.optString("theme", themeModeName(Config.webUiTheme))) {
            "system" -> Config.webUiTheme = Config.Value.WEBUI_THEME_SYSTEM
            "light" -> Config.webUiTheme = Config.Value.WEBUI_THEME_LIGHT
            "dark" -> Config.webUiTheme = Config.Value.WEBUI_THEME_DARK
            else -> return jsonError(Response.Status.BAD_REQUEST, "invalid_theme", "Unknown theme mode")
        }

        onConfigChanged()
        return json(JSONObject().put("ok", true).put("config", webUiConfigJson(includeToken = true)))
    }

    private fun installResponse(result: WebUiInstaller.Result): Response =
        json(result.body, result.status)

    private fun modulesJson(): Response = runBlocking(Dispatchers.IO) {
        val modules = if (Info.env.isActive && LocalModule.loaded()) LocalModule.installed() else emptyList()
        val data = JSONArray()
        modules.forEach { module ->
            data.put(JSONObject().apply {
                put("id", module.id)
                put("name", module.name)
                put("version", module.version)
                put("versionCode", module.versionCode)
                put("author", module.author)
                put("description", module.description)
                put("enabled", module.enable)
                put("remove", module.remove)
                put("updated", module.updated)
                put("hasAction", module.hasAction)
                put("isZygisk", module.isZygisk)
            })
        }
        json(JSONObject().put("modules", data))
    }

    private fun moduleAction(path: String, session: IHTTPSession): Response = runBlocking(Dispatchers.IO) {
        val parts = path.removePrefix("/api/modules/").split('/').filter(String::isNotEmpty)
        if (parts.size != 2 || session.method != Method.POST) {
            return@runBlocking jsonError(Response.Status.NOT_FOUND, "not_found", "Module endpoint not found")
        }
        val id = URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name())
        if (!id.matches(Regex("[A-Za-z0-9._-]+"))) {
            return@runBlocking jsonError(Response.Status.BAD_REQUEST, "invalid_module", "Invalid module id")
        }
        val module = LocalModule.installed().firstOrNull { it.id == id }
            ?: return@runBlocking jsonError(Response.Status.NOT_FOUND, "module_not_found", "Module not found")

        when (parts[1]) {
            "state" -> {
                val body = requestJson(session)
                if (body.has("enabled")) module.enable = body.getBoolean("enabled")
                if (body.has("remove")) module.remove = body.getBoolean("remove")
                json(JSONObject().put("ok", true).put("enabled", module.enable).put("remove", module.remove))
            }
            "action" -> {
                if (!module.hasAction) {
                    return@runBlocking jsonError(Response.Status.BAD_REQUEST, "no_action", "Module has no action.sh")
                }
                val result = Shell.cmd("cd /data/adb/modules/$id && sh ./action.sh").exec()
                json(JSONObject().apply {
                    put("ok", result.isSuccess)
                    put("code", result.code)
                    put("stdout", JSONArray(result.out))
                    put("stderr", JSONArray(result.err))
                })
            }
            else -> jsonError(Response.Status.NOT_FOUND, "not_found", "Module endpoint not found")
        }
    }

    private fun superuserJson(): Response = runBlocking(Dispatchers.IO) {
        val policies = ServiceLocator.policyDB.fetchAll()
        val pm = context.packageManager
        val data = JSONArray()
        policies.forEach { policy ->
            val packages = pm.getPackagesForUid(policy.uid).orEmpty()
            val names = JSONArray()
            packages.forEach { pkg ->
                val label = runCatching {
                    val info = pm.getApplicationInfo(pkg, 0)
                    info.loadLabel(pm).toString()
                }.getOrDefault(pkg)
                names.put(JSONObject().put("packageName", pkg).put("appName", label))
            }
            data.put(JSONObject().apply {
                put("uid", policy.uid)
                put("policy", policyName(policy.policy))
                put("notification", policy.notification)
                put("logging", policy.logging)
                put("remain", policy.remain)
                put("apps", names)
            })
        }
        json(JSONObject().put("policies", data))
    }

    private fun superuserAction(path: String, session: IHTTPSession): Response = runBlocking(Dispatchers.IO) {
        val rawUid = path.removePrefix("/api/superuser/").substringBefore('/')
        val uid = rawUid.toIntOrNull()
            ?: return@runBlocking jsonError(Response.Status.BAD_REQUEST, "invalid_uid", "Invalid uid")
        val db = ServiceLocator.policyDB
        val policy = db.fetch(uid)
            ?: return@runBlocking jsonError(Response.Status.NOT_FOUND, "policy_not_found", "Policy not found")

        when (session.method) {
            Method.DELETE -> {
                db.delete(uid)
                json(JSONObject().put("ok", true))
            }
            Method.POST -> {
                val body = requestJson(session)
                if (body.has("policy")) {
                    policy.policy = when (body.getString("policy")) {
                        "query" -> SuPolicy.QUERY
                        "deny" -> SuPolicy.DENY
                        "allow" -> SuPolicy.ALLOW
                        "restrict" -> SuPolicy.RESTRICT
                        else -> return@runBlocking jsonError(
                            Response.Status.BAD_REQUEST,
                            "invalid_policy",
                            "Unknown superuser policy",
                        )
                    }
                }
                if (body.has("notification")) policy.notification = body.getBoolean("notification")
                if (body.has("logging")) policy.logging = body.getBoolean("logging")
                db.update(policy)
                json(JSONObject().put("ok", true).put("policy", policyName(policy.policy)))
            }
            else -> jsonError(Response.Status.NOT_FOUND, "not_found", "Superuser endpoint not found")
        }
    }

    private fun suLogsJson(): Response = runBlocking(Dispatchers.IO) {
        val logs = ServiceLocator.logRepo.fetchSuLogs().takeLast(300)
        val data = JSONArray()
        logs.asReversed().forEach { log ->
            data.put(JSONObject().apply {
                put("id", log.id)
                put("time", log.time)
                put("appName", log.appName)
                put("packageName", log.packageName)
                put("fromUid", log.fromUid)
                put("toUid", log.toUid)
                put("fromPid", log.fromPid)
                put("command", log.command)
                put("action", log.action)
                put("target", log.target)
                put("context", log.context)
                put("gids", log.gids)
            })
        }
        json(JSONObject().put("logs", data))
    }

    private fun clearSuLogs(): Response = runBlocking(Dispatchers.IO) {
        ServiceLocator.logRepo.clearLogs()
        json(JSONObject().put("ok", true))
    }

    private fun magiskLogJson(): Response = runBlocking(Dispatchers.IO) {
        val log = ServiceLocator.logRepo.fetchMagiskLogs()
        val bounded = if (log.length > 250_000) log.takeLast(250_000) else log
        json(JSONObject().put("log", bounded))
    }

    private fun clearMagiskLog(): Response = runBlocking(Dispatchers.IO) {
        val result = Shell.cmd("echo -n > ${Const.MAGISK_LOG}").exec()
        json(JSONObject().put("ok", result.isSuccess).put("code", result.code))
    }

    private fun settingsJson() = JSONObject().apply {
        put("zygisk", Config.zygisk)
        put("denyList", Config.denyList)
        put("rootMode", Config.rootMode)
        put("namespaceMode", Config.suMntNamespaceMode)
        put("multiuserMode", Config.suMultiuserMode)
        put("suDefaultTimeout", Config.suDefaultTimeout)
        put("suAutoResponse", Config.suAutoResponse)
        put("suNotification", Config.suNotification)
        put("suReAuth", Config.suReAuth)
        put("suTapjack", Config.suTapjack)
        put("suRestrict", Config.suRestrict)
        put("checkUpdate", Config.checkUpdate)
        put("doh", Config.doh)
    }

    private fun updateSettings(session: IHTTPSession): Response {
        val body = requestJson(session)
        if (body.has("zygisk")) Config.zygisk = body.getBoolean("zygisk")
        if (body.has("rootMode")) Config.rootMode = body.getInt("rootMode")
        if (body.has("namespaceMode")) Config.suMntNamespaceMode = body.getInt("namespaceMode")
        if (body.has("multiuserMode")) Config.suMultiuserMode = body.getInt("multiuserMode")
        if (body.has("suDefaultTimeout")) Config.suDefaultTimeout = body.getInt("suDefaultTimeout")
        if (body.has("suAutoResponse")) Config.suAutoResponse = body.getInt("suAutoResponse")
        if (body.has("suNotification")) Config.suNotification = body.getInt("suNotification")
        if (body.has("suReAuth")) Config.suReAuth = body.getBoolean("suReAuth")
        if (body.has("suTapjack")) Config.suTapjack = body.getBoolean("suTapjack")
        if (body.has("suRestrict")) Config.suRestrict = body.getBoolean("suRestrict")
        if (body.has("checkUpdate")) Config.checkUpdate = body.getBoolean("checkUpdate")
        if (body.has("doh")) Config.doh = body.getBoolean("doh")

        if (body.has("denyList")) {
            val enabled = body.getBoolean("denyList")
            val result = Shell.cmd("magisk --denylist ${if (enabled) "enable" else "disable"}").exec()
            if (!result.isSuccess) {
                return jsonError(Response.Status.INTERNAL_ERROR, "denylist_failed", "Unable to update DenyList state")
            }
            Config.denyList = enabled
        }
        return json(JSONObject().put("ok", true).put("settings", settingsJson()))
    }

    private fun powerAction(session: IHTTPSession): Response {
        val mode = requestJson(session).optString("mode", "reboot")
        val command = when (mode) {
            "reboot" -> "reboot"
            "recovery" -> "reboot recovery"
            "bootloader" -> "reboot bootloader"
            "download" -> "reboot download"
            "edl" -> "reboot edl"
            else -> return jsonError(Response.Status.BAD_REQUEST, "invalid_power_mode", "Unknown reboot mode")
        }
        Shell.cmd(command).submit()
        return json(JSONObject().put("ok", true).put("mode", mode))
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        if (Config.webUiAuthMode == Config.Value.WEBUI_AUTH_NONE) return true
        val expected = WebUiManager.effectiveToken()
        if (expected.isBlank()) return false
        val authorization = session.headers["authorization"].orEmpty()
        val supplied = when {
            authorization.startsWith("Bearer ", ignoreCase = true) -> authorization.substring(7).trim()
            session.headers["x-magisk-token"] != null -> session.headers["x-magisk-token"].orEmpty()
            else -> session.parameters["token"]?.firstOrNull().orEmpty()
        }
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            supplied.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun requestJson(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"].orEmpty()
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    private fun asset(path: String, mime: String): Response {
        return runCatching {
            val stream = context.assets.open(path)
            newChunkedResponse(Response.Status.OK, mime, stream).apply {
                addHeader("Cache-Control", "no-store, max-age=0")
                addHeader("X-Content-Type-Options", "nosniff")
                addHeader("Referrer-Policy", "no-referrer")
                addHeader("X-Frame-Options", "DENY")
            }
        }.getOrElse {
            jsonError(Response.Status.NOT_FOUND, "asset_not_found", "WebUI asset not found")
        }
    }

    private fun json(value: JSONObject, status: Response.Status = Response.Status.OK): Response {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", value.toString()).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("Referrer-Policy", "no-referrer")
        }
    }

    private fun jsonError(status: Response.Status, code: String, message: String): Response {
        return json(
            JSONObject().put("ok", false).put("error", code).put("message", message),
            status,
        )
    }

    private fun text(status: Response.Status, mime: String, value: String): Response =
        newFixedLengthResponse(status, mime, value)

    private fun authModeName(mode: Int) = when (mode) {
        Config.Value.WEBUI_AUTH_NONE -> "none"
        Config.Value.WEBUI_AUTH_CUSTOM_TOKEN -> "custom"
        else -> "random"
    }

    private fun themeModeName(mode: Int) = when (mode) {
        Config.Value.WEBUI_THEME_LIGHT -> "light"
        Config.Value.WEBUI_THEME_DARK -> "dark"
        else -> "system"
    }

    private fun policyName(policy: Int) = when (policy) {
        SuPolicy.DENY -> "deny"
        SuPolicy.ALLOW -> "allow"
        SuPolicy.RESTRICT -> "restrict"
        else -> "query"
    }
}
