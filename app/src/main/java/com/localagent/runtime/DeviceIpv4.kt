package com.localagent.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import java.net.Inet4Address

object DeviceIpv4 {
    /**
     * Primary non-loopback IPv4 on the active network (Wi‑Fi, Ethernet, VPN, etc.).
     * Used so Termux (another UID) can reach LocalAgent services; loopback is UID‑scoped on Android.
     */
    fun primarySiteLocalIpv4(context: Context): Inet4Address? {
        val cm = context.applicationContext.getSystemService<ConnectivityManager>() ?: return null
        val network = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(network) ?: return null
        for (addr in lp.linkAddresses) {
            val ip = addr.address
            if (ip is Inet4Address && !ip.isLoopbackAddress && !ip.isLinkLocalAddress) {
                return ip
            }
        }
        for (addr in lp.linkAddresses) {
            val ip = addr.address
            if (ip is Inet4Address && !ip.isLoopbackAddress) {
                return ip
            }
        }
        return null
    }

    fun isActiveTransportWifi(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService<ConnectivityManager>() ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
