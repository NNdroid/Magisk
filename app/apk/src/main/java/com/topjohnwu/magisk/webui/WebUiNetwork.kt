package com.topjohnwu.magisk.webui

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Discovers remotely usable WebUI addresses and keeps the published URLs in sync with
 * Wi-Fi/Ethernet changes. Android LinkProperties are authoritative; NetworkInterface is
 * retained only as a fallback for vendor builds that do not expose useful LinkProperties.
 */
internal class WebUiNetworkMonitor(
    context: Context,
    private val onChanged: () -> Unit,
) {
    private val connectivity = requireNotNull(
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    )
    private var callbackRegistered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = changed()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = changed()
    }

    fun start() {
        if (callbackRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching {
            connectivity.registerDefaultNetworkCallback(callback)
        }.onSuccess {
            callbackRegistered = true
        }
    }

    fun stop() {
        if (!callbackRegistered) return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        callbackRegistered = false
    }

    fun lanUrls(port: Int, allowIpv4: Boolean, allowIpv6: Boolean): List<String> {
        if (!allowIpv4 && !allowIpv6) return emptyList()

        val candidates = mutableListOf<Candidate>()
        collectAndroidNetworks(candidates, port, allowIpv4, allowIpv6)
        if (candidates.isEmpty()) {
            collectJavaInterfaces(candidates, port, allowIpv4, allowIpv6)
        }
        return candidates
            .sortedBy(Candidate::score)
            .map(Candidate::url)
            .distinct()
    }

    private fun collectAndroidNetworks(
        output: MutableList<Candidate>,
        port: Int,
        allowIpv4: Boolean,
        allowIpv6: Boolean,
    ) {
        val active = runCatching { connectivity.activeNetwork }.getOrNull()
        val networks = runCatching { connectivity.allNetworks.toList() }.getOrDefault(emptyList())
        for (network in networks) {
            val properties = runCatching { connectivity.getLinkProperties(network) }.getOrNull() ?: continue
            val capabilities = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
            val networkScore = networkPriority(
                network = network,
                active = active,
                capabilities = capabilities,
                interfaceName = properties.interfaceName,
            )
            for (linkAddress in properties.linkAddresses) {
                val address = linkAddress.address
                addCandidate(output, address, port, allowIpv4, allowIpv6, networkScore)
            }
        }
    }

    private fun collectJavaInterfaces(
        output: MutableList<Candidate>,
        port: Int,
        allowIpv4: Boolean,
        allowIpv6: Boolean,
    ) {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            if (runCatching { !network.isUp || network.isLoopback }.getOrDefault(true)) continue
            val networkScore = 1_000 + interfacePriority(network.name) * 100
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                addCandidate(output, addresses.nextElement(), port, allowIpv4, allowIpv6, networkScore)
            }
        }
    }

    private fun addCandidate(
        output: MutableList<Candidate>,
        address: InetAddress,
        port: Int,
        allowIpv4: Boolean,
        allowIpv6: Boolean,
        networkScore: Int,
    ) {
        if (!isUsableRemoteAddress(address)) return
        val familyScore = when (address) {
            is Inet4Address -> {
                if (!allowIpv4) return
                0
            }
            is Inet6Address -> {
                if (!allowIpv6) return
                1
            }
            else -> return
        }
        val url = address.toHttpUrl(port) ?: return
        output += Candidate(networkScore + familyScore, url)
    }

    private fun networkPriority(
        network: Network,
        active: Network?,
        capabilities: NetworkCapabilities?,
        interfaceName: String?,
    ): Int {
        var score = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> 800
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 0
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> 20
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> 300
            else -> 100 + interfacePriority(interfaceName.orEmpty()) * 20
        }
        // Prefer the actual active physical network, but never let an active VPN outrank
        // a directly reachable Wi-Fi/Ethernet LAN address.
        if (network == active && score < 800) score -= 100
        return score * 10
    }

    private fun interfacePriority(name: String): Int {
        val normalized = name.lowercase()
        return when {
            normalized.startsWith("wlan") || normalized.startsWith("wifi") -> 0
            normalized.startsWith("eth") || normalized.startsWith("en") -> 1
            normalized.startsWith("br") -> 2
            normalized.startsWith("tap") || normalized.startsWith("tun") ||
                normalized.startsWith("wg") || normalized.startsWith("zt") -> 8
            else -> 4
        }
    }

    private fun isUsableRemoteAddress(address: InetAddress): Boolean =
        !address.isAnyLocalAddress &&
            !address.isLoopbackAddress &&
            !address.isLinkLocalAddress &&
            !address.isMulticastAddress &&
            (address is Inet4Address || address is Inet6Address)

    private fun InetAddress.toHttpUrl(port: Int): String? {
        // Scope IDs are meaningful only on the local host. Link-local IPv6 addresses are
        // filtered above, so stripping a vendor-supplied scope from a global/ULA address
        // produces the correct remotely usable literal URL.
        val rawHost = hostAddress?.substringBefore('%') ?: return null
        val host = when (this) {
            is Inet4Address -> rawHost
            is Inet6Address -> "[$rawHost]"
            else -> return null
        }
        return "http://$host:$port"
    }

    private fun changed() {
        runCatching { onChanged() }
    }

    private data class Candidate(
        val score: Int,
        val url: String,
    )
}
