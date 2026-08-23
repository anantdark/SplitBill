package com.anant.splitbill.util

import android.content.Context
import android.widget.Toast

/** Android system Toast (pill with app icon on Android 12+). */
object SystemToast {
    fun show(context: Context, message: String, long: Boolean = false) {
        Toast.makeText(
            context.applicationContext,
            message,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
        ).show()
    }
}
