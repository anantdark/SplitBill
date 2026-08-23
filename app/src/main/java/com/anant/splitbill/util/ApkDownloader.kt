package com.anant.splitbill.util

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

/** Enqueues an APK download to the public Downloads folder — never triggers install. */
object ApkDownloader {
    fun enqueue(context: Context, downloadUrl: String, fileName: String): Long {
        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("SplitBill update")
            .setDescription("Downloading SplitBill $fileName")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return manager.enqueue(request)
    }

    fun fileNameFor(versionName: String, versionCode: Int): String {
        val safeVersion = versionName.replace(Regex("""[^\w.\-]"""), "_")
        return "SplitBill-$safeVersion-build$versionCode.apk"
    }
}
