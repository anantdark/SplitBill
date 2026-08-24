package com.anant.splitbill.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit

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

    /**
     * Cloud-only device fingerprint for the audit log (mirrors the web backend's
     * ip/ua/lang/tz/screen detail string). Never shown in the app UI — only the
     * member ID is surfaced there; this stays in synced settings/audit data.
     */
    fun fingerprint(context: Context): String {
        val metrics = context.resources.displayMetrics
        val locale = context.resources.configuration.locales.get(0) ?: Locale.getDefault()
        val tz = java.util.TimeZone.getDefault().id
        return "model=${Build.MANUFACTURER} ${Build.MODEL}; " +
            "os=Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}); " +
            "lang=${locale.toLanguageTag()}; tz=$tz; " +
            "screen=${metrics.widthPixels}x${metrics.heightPixels}"
    }

    private val ipLookupClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Best-effort public IP for the cloud-only audit trail, matching the web
     * client's use of the same lookup. Returns null on any failure — never
     * blocks the recharge save.
     */
    suspend fun fetchPublicIp(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("https://api.ipify.org?format=json").build()
            ipLookupClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string().orEmpty()
                Regex("\"ip\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            }
        }.getOrNull()
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
