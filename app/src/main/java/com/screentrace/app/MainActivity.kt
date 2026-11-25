package com.screentrace.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.screentrace.app.contract.PeriodType
import com.screentrace.app.data.reminder.PeriodReminderScheduler
import com.screentrace.app.ui.AppNav

class MainActivity : ComponentActivity() {
    private val pendingPeriodType = mutableStateOf<PeriodType?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingPeriodType.value = parsePeriodIntent(intent)
        setContent {
            AppNav(
                launchPeriodType = pendingPeriodType.value,
                onConsumeLaunchPeriod = { pendingPeriodType.value = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parsePeriodIntent(intent)?.let { pendingPeriodType.value = it }
    }

    private fun parsePeriodIntent(intent: Intent?): PeriodType? {
        val raw = intent?.getStringExtra(PeriodReminderScheduler.EXTRA_PERIOD_TYPE) ?: return null
        return runCatching { PeriodType.valueOf(raw) }.getOrNull()
    }
}
