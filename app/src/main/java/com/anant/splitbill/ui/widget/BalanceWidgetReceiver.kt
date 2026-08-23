package com.anant.splitbill.ui.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BalanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BalanceWidget()

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun requestUpdate(context: Context) {
            val appContext = context.applicationContext
            scope.launch {
                BalanceWidget().updateAll(appContext)
            }
        }
    }
}
