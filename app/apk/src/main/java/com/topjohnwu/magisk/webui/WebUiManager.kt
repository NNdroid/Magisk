package com.topjohnwu.magisk.webui

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.topjohnwu.magisk.core.Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

private const val TOKEN_BYTES = 24
private const val SELF_TEST_TIMEOUT_MS = 750
private const val IPV4_ANY = "0.0.0.0"
private const val IPV6_ANY = "::"
private const val IPV4_LOOPBACK = "127.0.0.1"
private const val IPV6_LOOPBACK = "::1"

data class WebUiState(
    val running: Boolean = false,
    val urls: List<String> = emptyList(),
    val accessUrl: String? = null,
    val authMode: Int = Config.Value.WEBUI_AUTH_RANDOM_TOKEN,
    val themeMode: Int = Config.Value.WEBUI_THEME_SYSTEM,
    val ipv4Listening: Boolean = false,
    val ipv6Listening: Boolean = false,
    val localSelfTest: Boolean? = null,
    val error: String? = null,
)

object WebUiManager {
    private val lock = Any()
    private val random = SecureRandom()

    private var ipv4Server: WebUiServer? = null
    private var ipv6Server: WebUiServer? = null
    private var ipv4Probe: Boolean? = null
    private var ipv6Probe: Boolean? = null
    private var listenerErrors: List<String> = emptyList()
    private var appContext: Context? = null
    private var networkMonitor: WebUiNetworkMonitor? = null

    private val _state = MutableStateFlow(WebUiState())
    val state: StateFlow<WebUiState> = _state.asStateFlow()

    fun start(context: Context) {
        // Activity/application callers only request service ownership. The listener itself
        // is created when WebUiService calls back with its Service instance, allowing the
        // WebUI to outlive MainActivity and remain available after the TV UI is closed.
        if (context !is WebUiService) {
            appContext = context.applicationContext
            if (!Config.webUiEnabled) {
                WebUiService.stop(context.applicationContext)
                stop()
                return
            }
            runCatching {
                WebUiService.start(context.applicationContext)
            }.onFailure { error ->
                reportError(error.message ?: error.javaClass.simpleName)
            }
            return
        }

        synchronized(lock) {
            appContext = context.applicationContext
            ensureNetworkMonitorLocked(context.applicationContext)

            if (!Config.webUiEnabled) {
                stopServersLocked()
                publishStateLocked()
                return
            }

            ensureRandomToken()

            // A sticky foreground service can be redelivered without process death. Re-probe
            // existing sockets before deciding to rebuild them.
            if (hasLiveServer() && probeExistingServersLocked()) {
                publishStateLocked()
                return
            }

            stopServersLocked()
            startDualStackListenersLocked(context.applicationContext)
            publishStateLocked()
        }
    }

    fun reportError(message: String) {
        synchronized(lock) {
            stopServersLocked()
            stopNetworkMonitorLocked()
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
            stopServersLocked()
            stopNetworkMonitorLocked()
            publishStateLocked()
        }
    }

    fun restart() {
        val context = synchronized(lock) {
            stopServersLocked()
            appContext
        }
        if (context != null) {
            runCatching { WebUiService.start(context) }
                .onFailure { error -> reportError(error.message ?: error.javaClass.simpleName) }
        } else {
            publishState()
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

    fun currentAccessUrl(): String? = synchronized(lock) {
        val (ipv4, ipv6) = activeFamiliesLocked()
        buildAccessUrl(
            networkMonitor?.lanUrls(Config.webUiPort, ipv4, ipv6)?.firstOrNull()
        )
    }

    internal fun publishState() {
        synchronized(lock) {
            publishStateLocked()
        }
    }

    private fun startDualStackListenersLocked(context: Context) {
        val errors = mutableListOf<String>()

        // Prefer one IPv6 wildcard socket first. Linux/Android may make this socket
        // dual-stack, in which case it also accepts IPv4-mapped clients and no second
        // socket should be created on the same port.
        ipv6Server = startServer(context, IPV6_ANY, "IPv6", errors)
        if (ipv6Server != null) {
            ipv6Probe = canConnect(IPV6_LOOPBACK)
            val ipv4ViaIpv6 = canConnect(IPV4_LOOPBACK)
            if (ipv4ViaIpv6) {
                ipv4Probe = true
            }
        } else {
            ipv6Probe = false
        }

        // Only add an explicit IPv4 listener when the IPv6 wildcard did not actually
        // accept IPv4. This avoids EADDRINUSE on dual-stack Android kernels.
        if (ipv4Probe != true) {
            ipv4Server = startServer(context, IPV4_ANY, "IPv4", errors)
            ipv4Probe = ipv4Server != null && canConnect(IPV4_LOOPBACK)
        }

        // A socket that cannot pass its own loopback probe is not useful. Keep the IPv6
        // socket only when it serves IPv6 or is the verified dual-stack owner of IPv4.
        if (ipv6Probe != true && ipv4Server != null) {
            ipv6Server?.stop()
            ipv6Server = null
        }
        if (ipv4Probe != true) {
            ipv4Server?.stop()
            ipv4Server = null
        }

        listenerErrors = errors
        if (ipv4Probe != true && ipv6Probe != true) {
            stopServersLocked(clearErrors = false)
        }
    }

    private fun startServer(
        context: Context,
        bindHost: String,
        familyName: String,
        errors: MutableList<String>,
    ): WebUiServer? = runCatching {
        WebUiServer(
            context = context,
            bindHost = bindHost,
            port = Config.webUiPort,
            onConfigChanged = ::publishState,
        ).also { server ->
            server.start(5_000, false)
            check(server.isAlive) { "$familyName listener did not stay alive" }
        }
    }.onFailure { error ->
        errors += "$familyName: ${error.message ?: error.javaClass.simpleName}"
    }.getOrNull()

    private fun probeExistingServersLocked(): Boolean {
        val ipv4Ok = canConnect(IPV4_LOOPBACK)
        val ipv6Ok = canConnect(IPV6_LOOPBACK)
        ipv4Probe = ipv4Ok
        ipv6Probe = ipv6Ok
        if (!ipv4Ok && !ipv6Ok) {
            stopServersLocked()
            return false
        }
        return true
    }

    private fun canConnect(host: String): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(InetAddress.getByName(host), Config.webUiPort),
                SELF_TEST_TIMEOUT_MS,
            )
            socket.isConnected
        }
    }.getOrDefault(false)

    private fun hasLiveServer(): Boolean =
        ipv4Server?.isAlive == true || ipv6Server?.isAlive == true

    private fun activeFamiliesLocked(): Pair<Boolean, Boolean> =
        (ipv4Probe == true) to (ipv6Probe == true)

    private fun publishStateLocked() {
        val (ipv4, ipv6) = activeFamiliesLocked()
        val urls = if (ipv4 || ipv6) {
            networkMonitor?.lanUrls(Config.webUiPort, ipv4, ipv6).orEmpty()
        } else {
            emptyList()
        }
        val localTest = when {
            ipv4Probe == true || ipv6Probe == true -> true
            ipv4Probe == false && ipv6Probe == false -> false
            else -> null
        }
        _state.value = WebUiState(
            running = ipv4 || ipv6,
            urls = urls,
            accessUrl = buildAccessUrl(urls.firstOrNull()),
            authMode = Config.webUiAuthMode,
            themeMode = Config.webUiTheme,
            ipv4Listening = ipv4,
            ipv6Listening = ipv6,
            localSelfTest = localTest,
            error = if (ipv4 || ipv6) {
                null
            } else {
                listenerErrors.joinToString("; ").ifBlank { null }
            },
        )
    }

    private fun ensureNetworkMonitorLocked(context: Context) {
        if (networkMonitor == null) {
            networkMonitor = WebUiNetworkMonitor(context) {
                synchronized(lock) {
                    if (hasLiveServer()) publishStateLocked()
                }
            }.also(WebUiNetworkMonitor::start)
        }
    }

    private fun stopNetworkMonitorLocked() {
        networkMonitor?.stop()
        networkMonitor = null
    }

    private fun stopServersLocked(clearErrors: Boolean = true) {
        ipv4Server?.stop()
        ipv6Server?.stop()
        ipv4Server = null
        ipv6Server = null
        ipv4Probe = null
        ipv6Probe = null
        if (clearErrors) listenerErrors = emptyList()
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
}
