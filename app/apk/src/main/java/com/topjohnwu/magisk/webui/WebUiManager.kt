package com.topjohnwu.magisk.webui

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.topjohnwu.magisk.core.Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
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
            ?: return emptyList()
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            if (runCatching { !network.isUp || network.isLoopback }.getOrDefault(true)) continue
            val interfacePriority = interfacePriority(network.name)
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!isUsableLanAddress(address)) continue

                // Prefer IPv4 for QR compatibility when both families are available on
                // the same interface, but keep IPv6 as a first-class fallback for
                // IPv6-only networks.
                val familyPriority = when (address) {
                    is Inet4Address -> 0
                    is Inet6Address -> 1
                    else -> continue
                }
                val url = address.toHttpUrl(port) ?: continue
                candidates += (interfacePriority * 10 + familyPriority) to url
            }
        }
        return candidates
            .sortedBy { it.first }
            .map { it.second }
            .distinct()
    }

    private fun interfacePriority(name: String): Int {
        val normalized = name.lowercase()
        return when {
            normalized.startsWith("wlan") || normalized.startsWith("wifi") -> 0
            normalized.startsWith("eth") -> 1
            normalized.startsWith("en") -> 2
            normalized.startsWith("tun") || normalized.startsWith("wg") -> 4
            else -> 3
        }
    }

    private fun isUsableLanAddress(address: InetAddress): Boolean =
        !address.isAnyLocalAddress &&
            !address.isLoopbackAddress &&
            !address.isLinkLocalAddress &&
            !address.isMulticastAddress &&
            (address is Inet4Address || address is Inet6Address)

    private fun InetAddress.toHttpUrl(port: Int): String? {
        val rawHost = hostAddress ?: return null
        val host = when (this) {
            is Inet4Address -> rawHost
            is Inet6Address -> {
                // RFC 6874 requires a literal '%' used by a scoped IPv6 address to
                // be escaped in a URI. Link-local addresses are filtered above, but
                // preserve this for devices that attach a scope to other addresses.
                val escaped = rawHost.replace("%", "%25")
                "[$escaped]"
            }
            else -> return null
        }
        return "http://$host:$port"
    }
}
