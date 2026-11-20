package org.stypox.dicio.skills.timer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import android.util.Log
import java.time.Duration

/**
 * Sets timers in the system Clock app through the standard [AlarmClock] intents. There is no
 * standard way to query or cancel a timer of another app, so for those the Clock app's list of
 * timers is shown instead.
 */
internal object ClockAppTimer {
    private val TAG = ClockAppTimer::class.simpleName

    /** [AlarmClock.EXTRA_LENGTH] must be between 1 second and 24 hours */
    private const val MAX_LENGTH_SECONDS = 24 * 60 * 60L

    fun canSet(context: Context, duration: Duration): Boolean {
        return duration.seconds in 1..MAX_LENGTH_SECONDS &&
                Intent(AlarmClock.ACTION_SET_TIMER).resolveActivity(context.packageManager) != null
    }

    /** @return whether the Clock app accepted the timer */
    fun set(context: Context, duration: Duration, name: String?): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, duration.seconds.toInt())
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (name != null) {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, name)
        }
        return start(context, intent)
    }

    /** @return whether the Clock app's list of timers could be opened */
    fun showTimers(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false
        }
        return start(
            context,
            Intent(AlarmClock.ACTION_SHOW_TIMERS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun start(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No Clock app handles ${intent.action}", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to send ${intent.action}", e)
            false
        }
    }
}
