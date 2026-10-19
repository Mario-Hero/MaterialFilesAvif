/*
 * Copyright (c) 2026 Jingge Chen <mariocanfly@hotmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.slideshow

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 幻灯片播放的全局统一设置，修改后立即持久化。
 */
object SlideshowSettings {
    const val DEFAULT_INTERVAL_SECONDS = 3
    const val INTERVAL_SECONDS_MIN = 1
    const val INTERVAL_SECONDS_MAX = 3600

    private const val PREFS_NAME = "slideshow"
    private const val KEY_INTERVAL_SECONDS = "interval_seconds"
    private const val KEY_TRANSITION_EFFECT = "transition_effect"
    private const val KEY_RANDOM = "random"
    private const val KEY_LOOP = "loop"
    private const val KEY_SKIP_SIZE_MISMATCH = "skip_size_mismatch"

    private fun getPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getIntervalSeconds(context: Context): Int =
        getPreferences(context).getInt(KEY_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS)

    fun putIntervalSeconds(context: Context, value: Int) {
        getPreferences(context).edit { putInt(KEY_INTERVAL_SECONDS, value) }
    }

    fun getTransitionEffect(context: Context): Int =
        getPreferences(context).getInt(KEY_TRANSITION_EFFECT, SlideshowActivity.TRANSITION_EFFECT_FADE)

    fun putTransitionEffect(context: Context, value: Int) {
        getPreferences(context).edit { putInt(KEY_TRANSITION_EFFECT, value) }
    }

    fun isRandom(context: Context): Boolean =
        getPreferences(context).getBoolean(KEY_RANDOM, false)

    fun putRandom(context: Context, value: Boolean) {
        getPreferences(context).edit { putBoolean(KEY_RANDOM, value) }
    }

    fun isLoop(context: Context): Boolean =
        getPreferences(context).getBoolean(KEY_LOOP, false)

    fun putLoop(context: Context, value: Boolean) {
        getPreferences(context).edit { putBoolean(KEY_LOOP, value) }
    }

    fun isSkipSizeMismatch(context: Context): Boolean =
        getPreferences(context).getBoolean(KEY_SKIP_SIZE_MISMATCH, false)

    fun putSkipSizeMismatch(context: Context, value: Boolean) {
        getPreferences(context).edit { putBoolean(KEY_SKIP_SIZE_MISMATCH, value) }
    }
}
