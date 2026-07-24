package com.unite.sdk.utils

import java.util.concurrent.ConcurrentHashMap

object ClickGuard {
    private val lastClick = ConcurrentHashMap<String, Long>()
    private const val cooldownMs = 1000L

    fun canClick(key: String): Boolean {
        val now = System.currentTimeMillis()
        val prev = lastClick[key] ?: 0L
        if (now - prev < cooldownMs) return false
        lastClick[key] = now
        return true
    }
}

