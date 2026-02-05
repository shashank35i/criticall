package com.simats.criticall.ui.subscription

import android.content.Context
import java.util.Calendar
import java.util.TimeZone

object SubscriptionGate {

    private const val PREF = "criticall_ai_gate"
    private const val KEY_WEEK_START = "week_start_ms"
    private const val KEY_USED = "used_count"
    private const val KEY_SUBSCRIBED = "is_subscribed"

    private const val FREE_USES_PER_WEEK = 5

    fun isSubscribed(ctx: Context): Boolean {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_SUBSCRIBED, false)
    }

    fun setSubscribed(ctx: Context, subscribed: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SUBSCRIBED, subscribed)
            .apply()
    }

    fun remainingUses(ctx: Context): Int {
        if (isSubscribed(ctx)) return Int.MAX_VALUE
        resetIfNewWeek(ctx)
        val used = prefs(ctx).getInt(KEY_USED, 0)
        return (FREE_USES_PER_WEEK - used).coerceAtLeast(0)
    }

    fun canUseAi(ctx: Context): Boolean {
        if (isSubscribed(ctx)) return true
        resetIfNewWeek(ctx)
        val used = prefs(ctx).getInt(KEY_USED, 0)
        return used < FREE_USES_PER_WEEK
    }

    fun consumeAiUse(ctx: Context) {
        if (isSubscribed(ctx)) return
        resetIfNewWeek(ctx)
        val p = prefs(ctx)
        val used = p.getInt(KEY_USED, 0)
        p.edit().putInt(KEY_USED, used + 1).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun resetIfNewWeek(ctx: Context) {
        val p = prefs(ctx)
        val currentWeekStart = weekStartMs()
        val saved = p.getLong(KEY_WEEK_START, 0L)
        if (saved != currentWeekStart) {
            p.edit()
                .putLong(KEY_WEEK_START, currentWeekStart)
                .putInt(KEY_USED, 0)
                .apply()
        }
    }

    private fun weekStartMs(): Long {
        val cal = Calendar.getInstance()
        cal.timeZone = TimeZone.getDefault()
        cal.firstDayOfWeek = Calendar.MONDAY

        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        return cal.timeInMillis
    }
}
