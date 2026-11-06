package com.aarongoodermuth.questphonemonitor.sender

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Finds this phone's LAN IPv4 address so the UI can show the stream URL.
 * Prefers a Wi-Fi interface (`wlan0`) and falls back to any non-loopback
 * IPv4 address. Uses [NetworkInterface] enumeration on purpose: it needs
 * no permission, unlike [android.net.wifi.WifiManager.connectionInfo].
 */
object NetworkUtils {

    fun localIpv4Address(): String? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            return null
        }
        // Wi-Fi first.
        val wifi = interfaces.firstOrNull { it.name == "wlan0" }
        val fromWifi = wifi?.let { ipv4Of(it) }
        if (fromWifi != null) return fromWifi
        // Then any other up, non-loopback interface.
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val ip = ipv4Of(iface)
            if (ip != null) return ip
        }
        return null
    }

    private fun ipv4Of(iface: NetworkInterface): String? {
        for (address in iface.inetAddresses) {
            if (address is Inet4Address && !address.isLoopbackAddress) {
                return address.hostAddress
            }
        }
        return null
    }
}
