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
private const val SELF_TEST_TIMEOUT_MS = 250
private const val SELF_TEST_ATTEMPTS = 4
private const val SELF_TEST_RETRY_DELAY_MS = 50L
private const val STOP_WAIT_ATTEMPTS = 10
private const val STOP_WAIT_DELAY_MS = 25L
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
    private var boundPort: Int? = null
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
            val requestedPort = Config.webUiPort

            // A successfully bound listener is authoritative. Loopback probes are only
            // health/capability checks and must never destroy a listener after one transient
            // failure. Rebuild only when the configured port actually changed.
            if (hasLiveServer() && boundPort == requestedPort) {
                probeExistingServersLocked()
                publishStateLocked()
                return
            }

            stopServersLocked()
            startDualStackListenersLocked(context.applicationContext, requestedPort)
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
            // Authentication/theme changes are read from Config on each request. If Save did
            // not change the listening port, keep ownership of the existing socket and only
            // refresh health/UI state. This removes the same-port EADDRINUSE window entirely.
            if (
                Config.webUiEnabled &&
                hasLiveServer() &&
                boundPort == Config.webUiPort
            ) {
                probeExistingServersLocked()
                publishStateLocked()
                return
            }

            // A port/enabled-state change really requires teardown. NanoHTTPD closes its
            // server socket during stop(); wait a short bounded interval before rebinding.
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
        val port = activePortLocked()
        buildAccessUrl(
            networkMonitor?.lanUrls(port, ipv4, ipv6)?.firstOrNull()
        )
    }

    internal fun publishState() {
        synchronized(lock) {
            publishStateLocked()
        }
    }

    private fun startDualStackListenersLocked(context: Context, port: Int) {
        val errors = mutableListOf<String>()
        boundPort = port
        ipv4Probe = null
        ipv6Probe = null

        // Prefer one IPv6 wildcard socket. On the usual Android/Linux dual-stack setup this
        // socket also owns the IPv4 port; on IPV6_V6ONLY devices we add a separate IPv4 socket.
        val ipv6Result = startServer(context, IPV6_ANY, "IPv6", port)
        ipv6Server = ipv6Result.server
        if (ipv6Server?.isAlive == true) {
            ipv6Probe = canConnectEventually(IPV6_LOOPBACK, port)
            ipv4Probe = canConnectEventually(IPV4_LOOPBACK, port)
            if (ipv6Probe != true) {
                errors += "IPv6 listener is bound but its local self-test did not answer"
            }
        } else {
            ipv6Probe = false
            ipv6Result.error?.let { errors += formatListenerError("IPv6", it) }
        }

        if (ipv4Probe != true) {
            val ipv4Result = startServer(context, IPV4_ANY, "IPv4", port)
            ipv4Server = ipv4Result.server
            if (ipv4Server?.isAlive == true) {
                ipv4Probe = canConnectEventually(IPV4_LOOPBACK, port)
                if (ipv4Probe != true) {
                    errors += "IPv4 listener is bound but its local self-test did not answer"
                }
            } else {
                val error = ipv4Result.error
                if (error != null && error.isAddressAlreadyInUse() && ipv6Server?.isAlive == true) {
                    // Some Android kernels reserve the IPv4 port when :: is bound even before
                    // an immediate IPv4 loopback probe succeeds. Preserve the valid IPv6 socket
                    // and retry the IPv4 capability probe instead of tearing everything down.
                    ipv4Probe = canConnectEventually(
                        IPV4_LOOPBACK,
                        port,
                        attempts = SELF_TEST_ATTEMPTS + 2,
                    )
                    if (ipv4Probe != true) {
                        errors += "IPv4 port $port is already owned while the IPv6 listener is active"
                    }
                } else if (error != null) {
                    errors += formatListenerError("IPv4", error)
                }
            }
        }

        listenerErrors = errors
        if (!hasLiveServer()) {
            boundPort = null
        }
    }

    private fun startServer(
        context: Context,
        bindHost: String,
        familyName: String,
        port: Int,
    ): ServerStartResult {
        val server = WebUiServer(
            context = context,
            bindHost = bindHost,
            port = port,
            onConfigChanged = ::publishState,
        )
        return try {
            server.start(5_000, false)
            check(server.isAlive) { "$familyName listener did not stay alive" }
            ServerStartResult(server = server)
        } catch (error: Throwable) {
            runCatching { server.stop() }
            ServerStartResult(error = error)
        }
    }

    private fun probeExistingServersLocked(): Boolean {
        val port = boundPort ?: return false
        val ipv4Live = ipv4Server?.isAlive == true
        val ipv6Live = ipv6Server?.isAlive == true

        // Probes describe health; the server object's live/bound state describes ownership.
        // Never stop or discard a bound socket solely because a loopback connection missed.
        ipv6Probe = if (ipv6Live) canConnectEventually(IPV6_LOOPBACK, port) else false
        ipv4Probe = if (ipv4Live || ipv6Live) {
            canConnectEventually(IPV4_LOOPBACK, port)
        } else {
            false
        }
        return ipv4Live || ipv6Live
    }

    private fun canConnectEventually(
        host: String,
        port: Int,
        attempts: Int = SELF_TEST_ATTEMPTS,
    ): Boolean {
        repeat(attempts.coerceAtLeast(1)) { attempt ->
            if (canConnect(host, port)) return true
            if (attempt + 1 < attempts) {
                runCatching { Thread.sleep(SELF_TEST_RETRY_DELAY_MS) }
            }
        }
        return false
    }

    private fun canConnect(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(InetAddress.getByName(host), port),
                SELF_TEST_TIMEOUT_MS,
            )
            socket.isConnected
        }
    }.getOrDefault(false)

    private fun hasLiveServer(): Boolean =
        ipv4Server?.isAlive == true || ipv6Server?.isAlive == true

    private fun activeFamiliesLocked(): Pair<Boolean, Boolean> {
        val ipv4Live = ipv4Server?.isAlive == true
        val ipv6Live = ipv6Server?.isAlive == true
        val ipv4ViaIpv6 = ipv6Live && ipv4Probe == true
        return (ipv4Live || ipv4ViaIpv6) to ipv6Live
    }

    private fun activePortLocked(): Int =
        if (hasLiveServer()) boundPort ?: Config.webUiPort else Config.webUiPort

    private fun publishStateLocked() {
        val (ipv4, ipv6) = activeFamiliesLocked()
        val port = activePortLocked()
        val urls = if (ipv4 || ipv6) {
            networkMonitor?.lanUrls(port, ipv4, ipv6).orEmpty()
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
        val servers = listOfNotNull(ipv4Server, ipv6Server)
        servers.forEach { server -> runCatching { server.stop() } }
        awaitServersStopped(servers)

        ipv4Server = null
        ipv6Server = null
        ipv4Probe = null
        ipv6Probe = null
        boundPort = null
        if (clearErrors) listenerErrors = emptyList()
    }

    private fun awaitServersStopped(servers: List<WebUiServer>) {
        if (servers.isEmpty()) return
        repeat(STOP_WAIT_ATTEMPTS) { attempt ->
            if (servers.none { it.isAlive }) return
            if (attempt + 1 < STOP_WAIT_ATTEMPTS) {
                runCatching { Thread.sleep(STOP_WAIT_DELAY_MS) }
            }
        }
    }

    private fun Throwable.isAddressAlreadyInUse(): Boolean {
        var error: Throwable? = this
        while (error != null) {
            val message = error.message.orEmpty()
            if (
                message.contains("EADDRINUSE", ignoreCase = true) ||
                message.contains("Address already in use", ignoreCase = true)
            ) {
                return true
            }
            error = error.cause
        }
        return false
    }

    private fun formatListenerError(familyName: String, error: Throwable): String =
        "$familyName: ${error.message ?: error.javaClass.simpleName}"

    private fun buildAccessUrl(base: String?): String? {
        base ?: return null
        val token = effectiveToken()
        return if (Config.webUiAuthMode == Config.Value.WEBUI_AUTH_NONE || token.isBlank()) {
            base
        } else {
            "$base/#token=${Uri.encode(token)}"
        }
    }

    private data class ServerStartResult(
        val server: WebUiServer? = null,
        val error: Throwable? = null,
    )
}
