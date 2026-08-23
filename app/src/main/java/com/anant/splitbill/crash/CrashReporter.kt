package com.anant.splitbill.crash

import android.app.Application
import android.os.Build
import android.util.Log
import com.anant.splitbill.BuildConfig
import com.anant.splitbill.data.backup.mongo.MongoUriVault
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions.BeforeSendCallback
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.User
import java.util.concurrent.atomic.AtomicBoolean

data class HeartbeatInfo(
    val roomCount: Int = 0,
    val entryCount: Int = 0,
    val isDeveloper: Boolean = false,
    val androidSdk: Int = Build.VERSION.SDK_INT,
    val manufacturer: String = Build.MANUFACTURER.orEmpty().take(64),
    val model: String = Build.MODEL.orEmpty().take(64)
)

object CrashReporter {

    private val ready = AtomicBoolean(false)

    @Volatile
    private var reportingEnabled: Boolean = true

    fun init(app: Application, enabled: Boolean, supportId: String) {
        if (BuildConfig.IS_FDROID) return
        if (BuildConfig.SENTRY_DSN_BLOB.isBlank()) return
        val dsn = MongoUriVault.decode(
            BuildConfig.SENTRY_DSN_BLOB,
            BuildConfig.SENTRY_DSN_MASK
        ).trim()
        if (dsn.isEmpty()) return

        reportingEnabled = enabled
        SentryAndroid.init(app) { options ->
            options.dsn = dsn
            options.isSendDefaultPii = false
            options.tracesSampleRate = 0.0
            options.isEnableUserInteractionTracing = false
            options.isEnableAutoSessionTracking = false
            options.isSendClientReports = false
            options.environment = if (BuildConfig.DEBUG) "debug" else "release"
            options.release =
                "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            options.setBeforeSend(BeforeSendCallback { event, _ ->
                if (!reportingEnabled) return@BeforeSendCallback null
                scrub(event)
            })
        }
        if (supportId.isNotBlank()) {
            Sentry.setUser(User().apply { id = supportId })
        }
        ready.set(true)
    }

    fun setReportingEnabled(enabled: Boolean) {
        reportingEnabled = enabled
    }

    fun setSupportId(supportId: String) {
        if (!ready.get() || supportId.isBlank()) return
        Sentry.setUser(User().apply { id = supportId })
    }

    fun breadcrumb(category: String, message: String) {
        if (!ready.get() || !reportingEnabled) return
        Sentry.addBreadcrumb(
            Breadcrumb().apply {
                this.category = category
                this.message = message
                level = SentryLevel.INFO
            }
        )
    }

    fun sendDailyHeartbeat(info: HeartbeatInfo): Boolean {
        if (!ready.get()) return false
        return runCatching {
            breadcrumb("heartbeat", "rooms=${info.roomCount} entries=${info.entryCount}")
            Sentry.flush(3_000L)
            true
        }.onFailure { e ->
            Log.e(TAG, "heartbeat failed", e)
        }.getOrDefault(false)
    }

    private const val TAG = "SplitBillCrash"

    private fun scrub(event: SentryEvent): SentryEvent {
        event.request = null
        event.user?.apply {
            email = null
            username = null
            ipAddress = null
        }
        event.extras?.keys?.toList()?.forEach { key ->
            val value = event.extras?.get(key)?.toString().orEmpty()
            if (looksSecret(value) || looksSecret(key)) {
                event.extras?.remove(key)
            }
        }
        return event
    }

    private fun looksSecret(value: String): Boolean {
        if (value.length < 8) return false
        val lower = value.lowercase()
        return lower.contains("bearer ") ||
            lower.contains("api_key") ||
            lower.contains("apikey") ||
            Regex("eyJ[A-Za-z0-9_-]{20,}").containsMatchIn(value)
    }
}
