package com.jarvis.ai.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build

/**
 * v1.3.9 — Network security audit + ethical vulnerability assessment, for the
 * USER'S OWN phone and Wi-Fi only. Jarvis never scans, probes or attacks
 * anyone else's network or device — that would be illegal and unethical.
 * What it checks: is the current Wi-Fi open/unencrypted, is a VPN on, is
 * Jarvis's own Wi-Fi web server (HostServer) currently exposing the phone,
 * and is the connection a captive portal. Then it gives plain-language fixes.
 */
object NetworkAudit {
    fun audit(ctx: Context): String {
        val sb = StringBuilder("🌐 Network security audit (tomar nijer phone + Wi-Fi):\n")
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        if (caps == null) {
            sb.appendLine("- Network: offline — kono connection nei.")
            return sb.toString()
        }
        val onWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val onCell = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val onVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        sb.appendLine("- Connection: " + when {
            onVpn -> "VPN (encrypted tunnel ✅)"
            onWifi -> "Wi-Fi"
            onCell -> "Mobile data"
            else -> "other"
        })
        if (onWifi) {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = runCatching { wm.connectionInfo }.getOrNull()
            val ssid = info?.ssid?.replace("\"", "") ?: "unknown"
            sb.appendLine("- Wi-Fi network: $ssid (signal ${info?.rssi ?: "?"} dBm)")
            if (Build.VERSION.SDK_INT >= 31) {
                val sec = runCatching { info?.currentSecurityType }.getOrNull()
                val secName = when (sec) {
                    android.net.wifi.WifiInfo.SECURITY_TYPE_OPEN -> "OPEN — kono password nei! ⚠️"
                    android.net.wifi.WifiInfo.SECURITY_TYPE_WEP -> "WEP — purono, sahoje bhanga jay ⚠️"
                    android.net.wifi.WifiInfo.SECURITY_TYPE_PSK -> "WPA/WPA2 (password ache ✅)"
                    android.net.wifi.WifiInfo.SECURITY_TYPE_SAE -> "WPA3 (strong ✅)"
                    android.net.wifi.WifiInfo.SECURITY_TYPE_EAP -> "Enterprise (WPA2-Enterprise ✅)"
                    else -> "unknown"
                }
                sb.appendLine("- Wi-Fi security: $secName")
                if (sec == android.net.wifi.WifiInfo.SECURITY_TYPE_OPEN || sec == android.net.wifi.WifiInfo.SECURITY_TYPE_WEP) {
                    sb.appendLine("  👉 Open/WEP Wi-Fi-te banking ba login koro na — VPN on koro, ba mobile data use koro.")
                }
            }
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
                sb.appendLine("- Captive portal (hotel/cafe login page) detect hoyeche — login page-e sob data share koro na.")
            }
        }
        if (onVpn) sb.appendLine("- VPN on ache — traffic encrypted ✅")
        else sb.appendLine("- VPN off — public Wi-Fi-te VPN use kora bhalo.")
        // Jarvis's own exposure: is the on-phone web server running?
        if (HostServer.running) {
            sb.appendLine("- ⚠️ Jarvis-er Wi-Fi web server ON ache (${HostServer.url}) — ei Wi-Fi-te je keu tomar hosted site dekhte parbe. Bondho korte 'stop hosting' bolo.")
        } else {
            sb.appendLine("- Jarvis web server: off (kono port khola nei) ✅")
        }
        sb.appendLine("\nEthical note: Jarvis sudhu TOMAR phone ar Wi-Fi check kore. Onner network/device scan ba attack kora illegal — Jarvis seta kokhono kore na, ar korte bolleo korbe na.")
        return sb.toString()
    }
}
