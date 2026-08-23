package com.anant.splitbill.util

import android.content.Context
import android.content.Intent

object ShareUtils {
    /** Stable GitHub APK alias — triggers direct download in most browsers. */
    const val APP_DOWNLOAD_URL =
        "https://github.com/anantdark/SplitBill/releases/latest/download/SplitBill-latest.apk"

    fun shareText(context: Context, text: String, title: String = "Share balances") {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, title))
    }

    fun buildInviteMessage(roomId: String, roomName: String? = null): String {
        val id = roomId.trim()
        require(id.isNotBlank()) { "Room ID is blank" }
        val nameLine = roomName?.trim()?.takeIf { it.isNotEmpty() }?.let { "Room: $it\n" }.orEmpty()
        return buildString {
            appendLine("Join our prepaid meter room on SplitBill!")
            appendLine()
            appendLine("1. Download the app:")
            appendLine(APP_DOWNLOAD_URL)
            appendLine()
            appendLine("2. Open SplitBill and tap Join room on the welcome screen.")
            appendLine()
            appendLine("3. Enter this Room ID:")
            appendLine(id)
            if (nameLine.isNotEmpty()) {
                appendLine()
                append(nameLine.trimEnd())
            }
        }
    }

    fun shareInvite(context: Context, roomId: String, roomName: String? = null) {
        shareText(
            context = context,
            text = buildInviteMessage(roomId, roomName),
            title = "Invite to room"
        )
    }
}
