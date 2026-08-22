package com.myfile.ui.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** Tiện ích lấy địa chỉ IP LAN của thiết bị để hiển thị cho người dùng kết nối FTP tới. */
object NetworkUtils {

    fun getLocalIpAddress(context: Context): String? {
        // Ưu tiên lấy qua ConnectivityManager (chính xác với mạng đang active: WiFi/Hotspot/Ethernet)
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return fallbackInterfaceIp()
            val linkProperties: LinkProperties = cm.getLinkProperties(network) ?: return fallbackInterfaceIp()
            val ipv4 = linkProperties.linkAddresses
                .mapNotNull { it.address as? Inet4Address }
                .firstOrNull { !it.isLoopbackAddress }
            if (ipv4 != null) return ipv4.hostAddress
        } catch (e: Exception) {
            // fallthrough
        }
        return fallbackInterfaceIp()
    }

    private fun fallbackInterfaceIp(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun isWifiOrEthernetConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
