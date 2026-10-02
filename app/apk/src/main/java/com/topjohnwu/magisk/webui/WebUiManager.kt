package com.topjohnwu.magisk.webui

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.topjohnwu.magisk.core.Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

private const val TOKEN_BYTES = 24

data class WebUiState(
    val running: Boolean = false,
    val urls: List<String> = emptyList(),
    val accessUrl: String? = null,
    val authMode: Int = Config.Value.WEBUI_AUTH_RANDOM_TOKEN,
    val themeMode: Int = Config.Value.WEBUI_THEME_SYSTEM,
    val error: String? = null,
)

object WebUiManager {
    private val lock = Any()
    private val random = SecureRandom()
    private var server: WebUiServer? = null
    private var appContext: Context? = null

    private val _state = MutableStateFlow(WebUiState())
    val state: StateFlow<WebUiState> = _state.asStateFlow()

    fun start(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            if (!Config.webUiEnabled) {
                server?.stop()
                server = null
                publishState()
                return
            }
            ensureRandomToken()
            if (server != null) {
                publishState()
                return
            }
            runCatching {
                WebUiServer(
                    context = context.applicationContext,
                    port = Config.webUiPort,
                    onConfigChanged = ::publishState,
                ).also {
                    it.start(5_000, false)
                    server = it
                }
            }.onFailure { error ->
                server = null
                _state.value = WebUiState(
                    running = false,
                    authMode = Config.webUiAuthMode,
                    themeMode = Config.webUiTheme,
                    error = error.message ?: error.javaClass.simpleName,
                )
            }.onSuccess {
                publishState()
            }
        }
    }

    fun reportError(message: String) {
        synchronized(lock) {
            server?.stop()
            server = null
            _state.value = WebUiState(
                running = false,
                authMode = Config.webUiAuthMode,
                themeMode = Config.webUiTheme,
                error = message,
            )
        }
    }

    fun stop() {
        synchronized(lock) {
            server?.stop()
            server = null
            publishState()
        }
    }

    fun restart() {
        synchronized(lock) {
            server?.stop()
            server = null
            appContext?.let(::start) ?: publishState()
        }
    }

    fun ensureRandomToken(): String {
        if (Config.webUiRandomToken.isBlank()) {
            val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
            Config.webUiRandomToken = Base64.encodeToString(
                bytes,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
        }
        return Config.webUiRandomToken
    }

    fun regenerateRandomToken(): String {
        Config.webUiRandomToken = ""
        return ensureRandomToken()
    }

    fun effectiveToken(): String = when (Config.webUiAuthMode) {
        Config.Value.WEBUI_AUTH_RANDOM_TOKEN -> ensureRandomToken()
        Config.Value.WEBUI_AUTH_CUSTOM_TOKEN -> Config.webUiCustomToken
        else -> ""
    }

    fun currentAccessUrl(): String? = buildAccessUrl(lanUrls(Config.webUiPort).firstOrNull())

    internal fun publishState() {
        val urls = if (server != null) lanUrls(Config.webUiPort) else emptyList()
        _state.value = WebUiState(
            running = server != null,
            urls = urls,
            accessUrl = buildAccessUrl(urls.firstOrNull()),
            authMode = Config.webUiAuthMode,
            themeMode = Config.webUiTheme,
            error = null,
        )
    }

    private fun buildAccessUrl(base: String?): String? {
        base ?: return null
        val token = effectiveToken()
        return if (Config.webUiAuthMode == Config.Value.WEBUI_AUTH_NONE || token.isBlank()) {
            base
        } else {
            "$base/#token=${Uri.encode(token)}"
        }
    }

    private fun lanUrls(port: Int): List<String> {
        val candidates = mutableListOf<Pair<Int, String>>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return listOf("http://127.0.0.1:$port")
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            if (runCatching { !network.isUp || network.isLoopback }.getOrDefault(true)) continue
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address !is Inet4Address || address.isLoopbackAddress || address.isLinkLocalAddress) continue
                val name = network.name.lowercase()
                val priority = when {
                    name.startsWith("wlan") || name.startsWith("wifi") -> 0
                    name.startsWith("eth") -> 1
                    address.isSiteLocalAddress -> 2
                    else -> 3
                }
                candidates += priority to "http://${address.hostAddress}:$port"
            }
        }
        return candidates
            .sortedBy { it.first }
            .map { it.second }
            .distinct()
            .ifEmpty { listOf("http://127.0.0.1:$port") }
    }
}
