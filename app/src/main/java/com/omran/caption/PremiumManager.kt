package com.omran.caption

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tracks premium (subscription) status and daily free-usage quota.
 * Free tier: 10 minutes of live captioning per day.
 */
object PremiumManager {
    private const val PREFS = "omran_caption_premium"
    private const val KEY_IS_PREMIUM = "is_premium"
    private const val KEY_EXPIRY = "expiry_millis"
    private const val KEY_USAGE_DATE = "usage_date"
    private const val KEY_USAGE_SECONDS = "usage_seconds"

    const val FREE_DAILY_LIMIT_SECONDS = 10 * 60 // 10 minutes/day

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun isPremium(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_IS_PREMIUM, false)) return false
        val expiry = p.getLong(KEY_EXPIRY, 0L)
        // expiry == 0 means lifetime/unknown-managed-by-store; treat as valid until explicitly revoked
        return expiry == 0L || expiry > System.currentTimeMillis()
    }

    fun setPremium(ctx: Context, active: Boolean, expiryMillis: Long = 0L) {
        prefs(ctx).edit()
            .putBoolean(KEY_IS_PREMIUM, active)
            .putLong(KEY_EXPIRY, expiryMillis)
            .apply()
    }

    private fun resetIfNewDay(ctx: Context) {
        val p = prefs(ctx)
        if (p.getString(KEY_USAGE_DATE, "") != today()) {
            p.edit().putString(KEY_USAGE_DATE, today()).putInt(KEY_USAGE_SECONDS, 0).apply()
        }
    }

    fun secondsUsedToday(ctx: Context): Int {
        resetIfNewDay(ctx)
        return prefs(ctx).getInt(KEY_USAGE_SECONDS, 0)
    }

    fun secondsRemainingToday(ctx: Context): Int {
        if (isPremium(ctx)) return Int.MAX_VALUE
        return (FREE_DAILY_LIMIT_SECONDS - secondsUsedToday(ctx)).coerceAtLeast(0)
    }

    fun hasFreeQuotaLeft(ctx: Context): Boolean {
        if (isPremium(ctx)) return true
        return secondsRemainingToday(ctx) > 0
    }

    fun addUsageSeconds(ctx: Context, seconds: Int) {
        if (seconds <= 0) return
        resetIfNewDay(ctx)
        val p = prefs(ctx)
        val newTotal = p.getInt(KEY_USAGE_SECONDS, 0) + seconds
        p.edit().putInt(KEY_USAGE_SECONDS, newTotal).apply()
    }
}
