package com.unite.sdk.utils

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver

class ImpressionTracker(
    private val hostView: View,
    private val minVisibleRatio: Float = 0.5f,
    private val minVisibleMs: Long = 800L,
    private val throttleMs: Long = 200L,
    private val onImpression: (List<String>) -> Unit
) {
    private data class Item(
        val id: String,
        val view: View,
        var visibleSinceMs: Long? = null
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val visibleRect = Rect()
    private val items = LinkedHashMap<String, Item>()
    private val exposed = HashSet<String>()
    private var scheduledAtMs: Long? = null
    private val checkRunnable = Runnable {
        scheduledAtMs = null
        check()
    }

    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { schedule() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { schedule() }

    fun start() {
        hostView.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        hostView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        schedule()
    }

    fun stop() {
        try {
            hostView.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        } catch (_: Exception) {
        }
        try {
            hostView.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        } catch (_: Exception) {
        }
        mainHandler.removeCallbacks(checkRunnable)
        scheduledAtMs = null
    }

    fun reset() {
        items.clear()
        exposed.clear()
        mainHandler.removeCallbacks(checkRunnable)
        scheduledAtMs = null
    }

    fun track(id: String, view: View) {
        if (id.isBlank()) return
        items[id] = Item(id = id, view = view)
    }

    fun schedule() {
        scheduleAfter(throttleMs)
    }

    private fun check() {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val newlyExposed = ArrayList<String>()
        var nextDelayMs: Long? = null

        for ((id, item) in items) {
            if (exposed.contains(id)) continue
            val ratio = visibleRatio(item.view)
            if (ratio >= minVisibleRatio) {
                val since = item.visibleSinceMs
                if (since == null) {
                    item.visibleSinceMs = now
                    nextDelayMs = minDelay(nextDelayMs, minVisibleMs)
                } else if (now - since >= minVisibleMs) {
                    exposed.add(id)
                    newlyExposed.add(id)
                } else {
                    nextDelayMs = minDelay(nextDelayMs, minVisibleMs - (now - since))
                }
            } else {
                item.visibleSinceMs = null
            }
        }

        if (newlyExposed.isNotEmpty()) {
            onImpression(newlyExposed)
        }
        if (nextDelayMs != null) {
            scheduleAfter(nextDelayMs)
        }
    }

    private fun scheduleAfter(delayMs: Long) {
        val safeDelayMs = delayMs.coerceAtLeast(0L)
        val targetAt = SystemClock.uptimeMillis() + safeDelayMs
        val currentAt = scheduledAtMs
        if (currentAt != null && currentAt <= targetAt) return
        mainHandler.removeCallbacks(checkRunnable)
        scheduledAtMs = targetAt
        mainHandler.postAtTime(checkRunnable, targetAt)
    }

    private fun minDelay(current: Long?, candidate: Long): Long {
        val safeCandidate = candidate.coerceAtLeast(0L)
        return if (current == null) safeCandidate else minOf(current, safeCandidate)
    }

    private fun visibleRatio(view: View): Float {
        if (!view.isShown) return 0f
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return 0f
        val ok = view.getGlobalVisibleRect(visibleRect)
        if (!ok) return 0f
        val visibleArea = visibleRect.width().toFloat() * visibleRect.height().toFloat()
        val totalArea = w.toFloat() * h.toFloat()
        if (totalArea <= 0f) return 0f
        return (visibleArea / totalArea).coerceIn(0f, 1f)
    }
}
