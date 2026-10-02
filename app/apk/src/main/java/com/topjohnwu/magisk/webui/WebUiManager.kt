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
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.security.SecureRandom

private const val TOKEN_BYTES = 24
private const val SELF_TEST_TIMEOUT_MS = 1_500

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
    private var ipv6StartError: String? = null
    private var appContext: Context? = null
    private var selfTestGeneration = 0L

    private val _state = MutableStateFlow(WebUiState())
    val state: StateFlow<WebUiState> = _state.asStateFlow()

    fun start(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            if (!Config.webUiEnabled) {
                stopServersLocked()
                publishState()
                return
            }
            ensureRandomToken()
            if (hasLiveServer()) {
                publishState()
                return
            }

            stopServersLocked()
            val errors = mutableListOf<String>()

            runCatching {
                createBoundServer(context.applicationContext, InetAddress.getByName("0.0.0.0"))
                    .also { server ->
                        server.start(5_000, false)
                        check(server.isAlive) { "IPv4 listener did not stay alive" }
                        ipv4Server = server
                    }
            }.onFailure { error ->
                errors += "IPv4: ${error.message ?: error.javaClass.simpleName}"
                ipv4Server?.stop()
                ipv4Server = null
            }

            ipv6StartError = null
            runCatching {
                createBoundServer(context.applicationContext, InetAddress.getByName("::"))
                    .also { server ->
                        server.start(5_000, false)
                        check(server.isAlive) { "IPv6 listener did not stay alive" }
                        ipv6Server = server
                    }
            }.onFailure { error ->
                ipv6StartError = error.message ?: error.javaClass.simpleName
                ipv6Server?.stop()
                ipv6Server = null
                errors += "IPv6: $ipv6StartError"
            }

            if (!hasLiveServer()) {
                _state.value = WebUiState(
                    running = false,
                    authMode = Config.webUiAuthMode,
                    themeMode = Config.webUiTheme,
                    error = errors.joinToString("; ").ifBlank { "No WebUI listener could be started" },
                )
            } else {
                publishState()
                scheduleLocalSelfTest()
            }
        }
    }

    fun reportError(message: String) {
        synchronized(lock) {
            stopServersLocked()
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
            publishState()
        }
    }

    fun restart() {
        synchronized(lock) {
            stopServersLocked()
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

    fun currentAccessUrl(): String? {
        val ipv4 = ipv4Server?.isAlive == true
        val ipv6 = ipv6Server?.isAlive == true
        return buildAccessUrl(lanUrls(Config.webUiPort, ipv4, ipv6).firstOrNull())
    }

    internal fun publishState() {
        val ipv4 = ipv4Server?.isAlive == true
        val ipv6 = ipv6Server?.isAlive == true
        val urls = if (ipv4 || ipv6) {
            lanUrls(Config.webUiPort, ipv4, ipv6)
        } else {
            emptyList()
        }
        val previousSelfTest = _state.value.localSelfTest
        _state.value = WebUiState(
            running = ipv4 || ipv6,
            urls = urls,
            accessUrl = buildAccessUrl(urls.firstOrNull()),
            authMode = Config.webUiAuthMode,
            themeMode = Config.webUiTheme,
            ipv4Listening = ipv4,
            ipv6Listening = ipv6,
            localSelfTest = if (ipv4) previousSelfTest else null,
            error = if (ipv4 || ipv6) null else ipv6StartError,
        )
    }

    private fun scheduleLocalSelfTest() {
        if (ipv4Server?.isAlive != true) return
        val generation = ++selfTestGeneration
        _state.value = _state.value.copy(localSelfTest = null)
        Thread({
            val ok = runCatching {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(InetAddress.getByName("127.0.0.1"), Config.webUiPort),
                        SELF_TEST_TIMEOUT_MS,
                    )
                    socket.isConnected
                }
            }.getOrDefault(false)
            synchronized(lock) {
                if (generation == selfTestGeneration && ipv4Server?.isAlive == true) {
                    _state.value = _state.value.copy(localSelfTest = ok)
                }
            }
        }, "WebUiSelfTest").apply {
            isDaemon = true
            start()
        }
    }

    private fun hasLiveServer(): Boolean =
        ipv4Server?.isAlive == true || ipv6Server?.isAlive == true

    private fun stopServersLocked() {
        selfTestGeneration++
        ipv4Server?.stop()
        ipv6Server?.stop()
        ipv4Server = null
        ipv6Server = null
        ipv6StartError = null
    }

    private fun createBoundServer(context: Context, bindAddress: InetAddress): WebUiServer =
        WebUiServer(
            context = context,
            port = Config.webUiPort,
            onConfigChanged = ::publishState,
        ).apply {
            setServerSocketFactory {
                object : ServerSocket() {
                    override fun bind(endpoint: SocketAddress?) {
                        val requested = endpoint as? InetSocketAddress
                            ?: throw IllegalArgumentException("Unsupported bind endpoint: $endpoint")
                        super.bind(InetSocketAddress(bindAddress, requested.port))
                    }

                    override fun bind(endpoint: SocketAddress?, backlog: Int) {
                        val requested = endpoint as? InetSocketAddress
                            ?: throw IllegalArgumentException("Unsupported bind endpoint: $endpoint")
                        super.bind(InetSocketAddress(bindAddress, requested.port), backlog)
                    }
                }
            }
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

    private fun lanUrls(port: Int, allowIpv4: Boolean, allowIpv6: Boolean): List<String> {
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

                val familyPriority = when (address) {
                    is Inet4Address -> {
                        if (!allowIpv4) continue
                        0
                    }
                    is Inet6Address -> {
                        if (!allowIpv6) continue
                        1
                    }
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
                val escaped = rawHost.replace("%", "%25")
                "[$escaped]"
            }
            else -> return null
        }
        return "http://$host:$port"
    }
}
