package com.unite.sdk.tracking

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.unite.sdk.GameSlotSdk
import com.unite.sdk.slot.GameSlot
import com.unite.sdk.network.EventApi
import com.unite.sdk.utils.Device
import com.unite.sdk.utils.ExposureStore

object TrackingReporter {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var flushScheduledAtMs: Long? = null
    private var flushInFlight = false
    private val flushRunnable = Runnable {
        flushScheduledAtMs = null
        flushPendingImpressions()
    }

    fun reportImpression(gs: GameSlot) {
        reportImpression(listOf(gs.id), 1)
    }

    fun reportImpression(
        contentIds: List<String>,
        displayDuration: Int = 1,
        positions: Map<String, Int>? = null,
        slotIds: Map<String, String>? = null
    ) {
        if (!GameSlotSdk.isInitialized()) return
        val ids = contentIds.map { it.trim() }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) return
        val normalizedPositions = positions
            ?.mapNotNull { (rawId, rawPosition) ->
                val id = rawId.trim()
                val position = rawPosition
                if (id.isEmpty() || position <= 0) null else id to position
            }
            ?.toMap()
        val normalizedSlotIds = slotIds
            ?.mapNotNull { (rawId, rawSlotId) ->
                val id = rawId.trim()
                val slotId = rawSlotId.trim()
                if (id.isEmpty() || slotId.isEmpty()) null else id to slotId
            }
            ?.toMap()
        val ttl = GameSlotSdk.impressionTtlMs
        val added = ExposureStore.queueExposures(
            ids = ids,
            displayDuration = displayDuration,
            ttlMs = ttl,
            positions = normalizedPositions,
            slotIds = normalizedSlotIds
        )
        if (added) {
            Log.i("mlog", "cache show ids=$ids")
        }
        schedulePendingImpressionFlush()
    }

    internal fun resumePendingImpressionUpload() {
        if (!GameSlotSdk.isInitialized()) return
        ExposureStore.recoverInFlight(GameSlotSdk.impressionTtlMs)
        schedulePendingImpressionFlush()
    }

    fun reportClick(gs: GameSlot, position: Int? = null, slotId: String? = null) {
        Log.d("mlog", "click 上报"+gs.id)
        val clickParams = mutableMapOf<String, Any>()
        position?.takeIf { it > 0 }?.let { clickParams["position"] = it.toString() }
        slotId?.trim()?.takeIf { it.isNotEmpty() }?.let { clickParams["slot_id"] = it }
        reportContentEvent(
            eventId = "click",
            contentIds = listOf(gs.id),
            displayDuration = 1,
            extraParams = clickParams.takeIf { it.isNotEmpty() }
        )
    }

    fun reportClick(contentId: String) { // gameCenter
        if (contentId.isBlank()) return
        reportContentEvent("click", listOf(contentId), 1)
    }

    fun reportPlay(
        gs: GameSlot,
        playStartTs: Long,
        playStopTs: Long,
        playDuration: Long,
        position: Int? = null,
        slotId: String? = null
    ) {
        Log.d("mlog", "vplay 上报 ${gs.id}, duration=$playDuration")
        val playParams = mutableMapOf<String, Any>()
        playParams["play_start_ts"] = playStartTs
        playParams["play_stop_ts"] = playStopTs
        playParams["play_duration"] = playDuration
        position?.takeIf { it > 0 }?.let { playParams["position"] = it.toString() }
        slotId?.trim()?.takeIf { it.isNotEmpty() }?.let { playParams["slot_id"] = it }
        reportContentEvent(
            eventId = "vplay",
            contentIds = listOf(gs.id),
            displayDuration = 1,
            extraParams = playParams
        )
    }

    // 预留接口：request（广告请求）事件上报。服务端统计契约目前只定义了 show / click / vplay
    // 三种事件，request 尚未纳入，故本方法为空实现，待契约扩展后补齐。
    fun reportRequest(gameSlot: String) {
    }

    @Synchronized
    private fun schedulePendingImpressionFlush() {
        val delayMs = ExposureStore.nextFlushDelayMs(GameSlotSdk.impressionTtlMs) ?: run {
            cancelPendingImpressionFlush()
            return
        }
        val targetAtMs = SystemClock.uptimeMillis() + delayMs
        val currentScheduledAt = flushScheduledAtMs
        if (currentScheduledAt != null && currentScheduledAt <= targetAtMs) return
        mainHandler.removeCallbacks(flushRunnable)
        flushScheduledAtMs = targetAtMs
        mainHandler.postAtTime(flushRunnable, targetAtMs)
    }

    @Synchronized
    private fun cancelPendingImpressionFlush() {
        mainHandler.removeCallbacks(flushRunnable)
        flushScheduledAtMs = null
    }

    @Synchronized
    private fun flushPendingImpressions() {
        if (!GameSlotSdk.isInitialized()) return
        if (flushInFlight) return

        val ttl = GameSlotSdk.impressionTtlMs
        val batch = ExposureStore.beginFlush(ttl) ?: run {
            schedulePendingImpressionFlush()
            return
        }

        flushInFlight = true
        Log.i("mlog", "flush show ids=${batch.ids}")
        val positionPayload = buildPositionPayload(batch.ids, batch.positions)
        val slotPayload = buildSlotPayload(batch.ids, batch.slotIds)
        val showParams = mutableMapOf<String, Any>()
        positionPayload?.let { showParams["position"] = it }
        slotPayload?.let { showParams["slot_id"] = it }
        reportContentEvent(
            eventId = "show",
            contentIds = batch.ids,
            displayDuration = batch.displayDuration,
            reportType = if (batch.recovered) "补报" else "实时",
            callback = { success ->
            synchronized(this) {
                flushInFlight = false
                if (success) {
                    ExposureStore.completeFlushSuccess()
                } else {
                    ExposureStore.completeFlushFailure(ttl)
                }
                schedulePendingImpressionFlush()
            }
            },
            extraParams = showParams.takeIf { it.isNotEmpty() }
        )
    }

    private fun buildPositionPayload(ids: List<String>, positions: Map<String, Int>): String? {
        if (positions.isEmpty()) return null
        val ordered = ids.map { id -> (positions[id] ?: 0).coerceAtLeast(0).toString() }
        if (ordered.none { it != "0" }) return null
        return ordered.joinToString(separator = ",")
    }

    private fun buildSlotPayload(ids: List<String>, slotIds: Map<String, String>): String? {
        if (slotIds.isEmpty()) return null
        val ordered = ids.map { id -> slotIds[id].orEmpty().trim() }
        if (ordered.all { it.isEmpty() }) return null
        return ordered.joinToString(separator = ",")
    }

    private fun reportContentEvent(
        eventId: String,
        contentIds: List<String>,
        displayDuration: Int,
        callback: ((Boolean) -> Unit)? = null,
        extraParams: Map<String, Any>? = null,
        reportType: String = "实时"
    ) {
        if (!GameSlotSdk.isInitialized()) return

        val ctx = GameSlotSdk.applicationContext
        val viewport = "${ctx.resources.displayMetrics.widthPixels}x${ctx.resources.displayMetrics.heightPixels}"
        val params = mutableMapOf<String, Any>()
        params["content_ids"] = contentIds.joinToString(separator = ",")
        params["session_id"] = GameSlotSdk.sessionId
        params["device_id"] = Device.getDeviceId()
        params["device_brand"] = android.os.Build.BRAND
        params["device_model"] = android.os.Build.MODEL
        params["app_id"] = GameSlotSdk.app_id
        params["device_type"] = "mobile"
        params["orientation"] = Device.getOrientation()
        params["user_id"] = GameSlotSdk.uid
        params["country"] = Device.getCountry()
        params["language"] = Device.getLanguage()
        params["display_duration"] = displayDuration
        params["viewport"] = viewport
        params["app_version"] = GameSlotSdk.app_version
        extraParams?.let { params.putAll(it) }

        EventApi.post(eventId, params, callback, reportType)
    }
}
