package com.anant.splitbill.data.backup

/**
 * Sync paused because local and cloud recharge history diverge.
 * The UI asks whether to overwrite this device from the held cloud snapshot.
 */
data class CloudHistoryConflict(
    val missingCount: Int,
    val reason: Reason,
) {
    enum class Reason {
        /** Local is empty or missing cloud recharge ids — a push would wipe history. */
        LOCAL_DIVERGING,

        /** Cloud is missing recharge ids that exist locally. */
        CLOUD_MISSING_LOCAL,
    }

    val promptMessage: String
        get() {
            val n = missingCount.coerceAtLeast(1)
            return when (reason) {
                Reason.LOCAL_DIVERGING ->
                    "This device is missing $n cloud recharge${if (n == 1) "" else "s"} " +
                        "(empty or outdated). Sync was blocked so cloud history is not wiped. " +
                        "Overwrite this device from cloud?"
                Reason.CLOUD_MISSING_LOCAL ->
                    "Cloud is missing $n recharge${if (n == 1) "" else "s"} that exist on this device. " +
                        "Overwrite this device with the cloud copy? Local-only recharges will be lost."
            }
        }

    companion object {
        fun localDiverging(missingCloudRechargeCount: Int) = CloudHistoryConflict(
            missingCount = missingCloudRechargeCount,
            reason = Reason.LOCAL_DIVERGING,
        )

        fun cloudMissingLocal(missingLocalRechargeCount: Int) = CloudHistoryConflict(
            missingCount = missingLocalRechargeCount,
            reason = Reason.CLOUD_MISSING_LOCAL,
        )
    }
}
