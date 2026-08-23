package com.anant.splitbill.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

object DeviceIdentity {
    fun deviceName(context: Context): String {
        val fromSettings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } else {
            null
        }
        return fromSettings?.trim()?.takeIf { it.isNotBlank() }
            ?: Build.MODEL.orEmpty().trim().ifBlank { "Android" }
    }

    @SuppressLint("HardwareIds")
    fun macId(context: Context): String {
        readWifiMac()?.let { return it }
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            .orEmpty()
    }

    private fun readWifiMac(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                val name = iface.name?.lowercase(Locale.US).orEmpty()
                if (!name.startsWith("wlan") && !name.startsWith("eth")) continue
                val mac = iface.hardwareAddress ?: continue
                if (mac.isEmpty() || mac.all { it == 0.toByte() }) continue
                val formatted = mac.joinToString(":") { b ->
                    String.format(Locale.US, "%02x", b)
                }
                if (formatted == "02:00:00:00:00:00") continue
                return formatted
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
