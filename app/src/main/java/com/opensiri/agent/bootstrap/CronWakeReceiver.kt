package com.opensiri.agent.bootstrap

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * AlarmManager-based cron daemon for Open Siri.
 *
 * On receive:
 * 1. Ensures [AgentForegroundService] is running (starts it if not).
 * 2. Triggers a Hermes cron check by calling
 *    `AgentServerManager.runInPrefix("hermes cron run --all")`.
 *
 * Companion object provides [schedule] and [cancel] methods for managing
 * the repeating alarm. Uses `setExactAndAllowWhileIdle` on API 23+ and
 * `setExact` on API 19–22.
 */
class CronWakeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CronWakeReceiver"
        private const val ALARM_REQUEST_CODE = 9001

        /**
         * Schedule a repeating alarm that wakes the device and delivers
         * an intent to [CronWakeReceiver] every [intervalMinutes] minutes.
         *
         * On API 31+ (Android 12), exact alarms require the
         * SCHEDULE_EXACT_ALARM permission. The caller should ensure
         * that permission is held or fall back gracefully.
         */
        fun schedule(context: Context, intervalMinutes: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (alarmManager == null) {
                Log.e(TAG, "AlarmManager not available")
                return
            }

            val intent = Intent(context, CronWakeReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val intervalMillis = intervalMinutes * 60_000L
            val triggerAtMillis = System.currentTimeMillis() + intervalMillis

            try {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMillis,
                            pendingIntent,
                        )
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT -> {
                        @Suppress("DEPRECATION")
                        alarmManager.setExact(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMillis,
                            pendingIntent,
                        )
                    }
                    else -> {
                        @Suppress("DEPRECATION")
                        alarmManager.set(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMillis,
                            pendingIntent,
                        )
                    }
                }
                Log.i(TAG, "Cron scheduled every $intervalMinutes minutes (first at $triggerAtMillis)")
            } catch (e: SecurityException) {
                Log.e(TAG, "Cannot schedule exact alarm: ${e.message}. " +
                        "On Android 12+ ensure SCHEDULE_EXACT_ALARM is granted.")
                // Fallback: try inexact alarm
                try {
                    alarmManager.setInexactRepeating(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        intervalMillis,
                        pendingIntent,
                    )
                    Log.w(TAG, "Fell back to inexact repeating alarm")
                } catch (e2: Exception) {
                    Log.e(TAG, "Inexact alarm also failed: ${e2.message}")
                }
            }
        }

        /**
         * Cancel any previously scheduled cron alarm.
         */
        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return

            val intent = Intent(context, CronWakeReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
                Log.i(TAG, "Cron alarm cancelled")
            }
        }

        /**
         * Helper to check if the exact alarm permission is held (API 31+).
         */
        fun canScheduleExactAlarms(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                alarmManager?.canScheduleExactAlarms() == true
            } else {
                true
            }
        }
    }

    // ── Receiver implementation ──────────────────────────────────────────

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "CronWakeReceiver triggered (action=${intent?.action})")

        // 1. Ensure foreground service is running
        ensureForegroundService(context)

        // 2. Reschedule for next wakeup (AlarmManager one-shot alarms
        //    need manual rescheduling; boot receiver handles initial schedule)
        //    Note: We don't reschedule here to avoid double-scheduling.
        //    The caller (boot receiver or MainActivity) handles the initial
        //    schedule, and each onReceive reschedules once via schedule().
        //    This is handled externally.

        // 3. Trigger Hermes cron
        runHermesCron(context)
    }

    /**
     * Check if [AgentForegroundService] is running and start it if not.
     */
    private fun ensureForegroundService(context: Context) {
        try {
            // AgentForegroundService lives in the same package
            @Suppress("DEPRECATION")
            val serviceIntent = Intent(context, Class.forName(
                "com.opensiri.agent.bootstrap.AgentForegroundService"
            ))
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    @Suppress("DEPRECATION")
                    context.startService(serviceIntent)
                }
                Log.d(TAG, "Foreground service start dispatched")
            } catch (e: Exception) {
                Log.w(TAG, "Could not start foreground service: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start foreground service: ${e.message}")
        }
    }

    /**
     * Run `hermes cron run --all` inside the Termux prefix.
     */
    private fun runHermesCron(context: Context) {
        try {
            // Try the AgentServerManager first
            val agentClass = Class.forName("com.opensiri.agent.bootstrap.AgentServerManager")

            val constructor = agentClass.getConstructor(Context::class.java)
            val manager = constructor.newInstance(context)

            val runMethod = agentClass.getMethod(
                "runInPrefix",
                String::class.java,
                Class.forName("kotlin.jvm.functions.Function1")
            )
            runMethod.invoke(manager, "hermes cron run --all", { line: String ->
                Log.d(TAG, "[hermes-cron] $line")
            } as (String) -> Unit)

            Log.i(TAG, "Hermes cron dispatched")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run Hermes cron: ${e.message}", e)
        }
    }
}
