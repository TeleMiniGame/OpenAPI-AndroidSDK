package com.unite.sdk.utils

import android.content.Context
import android.util.Log
import com.unite.sdk.GameSlotSdk
import org.json.JSONObject

object ExposureStore {
    private const val PREF_NAME = "unite_sdk_exposure"
    private const val KEY_PENDING_IDS = "pending_ids"
    private const val KEY_PENDING_REPORT_AT_MS = "pending_report_at_ms"
    private const val KEY_PENDING_DISPLAY_DURATION = "pending_display_duration"
    private const val KEY_PENDING_POSITIONS = "pending_positions"
    private const val KEY_PENDING_SLOT_IDS = "pending_slot_ids"
    private const val KEY_IN_FLIGHT_IDS = "in_flight_ids"
    private const val KEY_IN_FLIGHT_DISPLAY_DURATION = "in_flight_display_duration"
    private const val KEY_IN_FLIGHT_POSITIONS = "in_flight_positions"
    private const val KEY_IN_FLIGHT_SLOT_IDS = "in_flight_slot_ids"
    private const val DEFAULT_DISPLAY_DURATION = 1

    data class PendingBatch(
        val ids: List<String>,
        val displayDuration: Int,
        val positions: Map<String, Int>,
        val slotIds: Map<String, String>,
        // true 表示该批次包含由持久化存储恢复的曝光（即跨进程/重启后的补报），
        // false 表示纯本次会话内排队后延迟上报。
        val recovered: Boolean = false
    )

    // 内存标记：当前 pending 队列里是否含有从磁盘恢复的曝光数据。
    @Volatile
    private var pendingRecovered = false

    private data class State(
        val pendingIds: LinkedHashSet<String>,
        var pendingReportAtMs: Long,
        var pendingDisplayDuration: Int,
        val pendingPositions: LinkedHashMap<String, Int>,
        val pendingSlotIds: LinkedHashMap<String, String>,
        val inFlightIds: LinkedHashSet<String>,
        var inFlightDisplayDuration: Int,
        val inFlightPositions: LinkedHashMap<String, Int>,
        val inFlightSlotIds: LinkedHashMap<String, String>,
        var dirty: Boolean
    )

    private fun prefs(): android.content.SharedPreferences {
        return GameSlotSdk.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun safeDisplayDuration(displayDuration: Int): Int {
        return if (displayDuration > 0) displayDuration else DEFAULT_DISPLAY_DURATION
    }

    private fun loadIds(key: String): LinkedHashSet<String> {
        val raw = prefs().getStringSet(key, emptySet()) ?: emptySet()
        return raw
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())
    }

    private fun loadPositions(key: String): LinkedHashMap<String, Int> {
        val raw = prefs().getString(key, null)?.trim().orEmpty()
        if (raw.isEmpty()) return LinkedHashMap()
        return try {
            val json = JSONObject(raw)
            val out = LinkedHashMap<String, Int>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val id = keys.next().trim()
                val position = json.optInt(id, 0)
                if (id.isNotEmpty() && position > 0) {
                    out[id] = position
                }
            }
            out
        } catch (_: Exception) {
            LinkedHashMap()
        }
    }

    private fun loadSlotIds(key: String): LinkedHashMap<String, String> {
        val raw = prefs().getString(key, null)?.trim().orEmpty()
        if (raw.isEmpty()) return LinkedHashMap()
        return try {
            val json = JSONObject(raw)
            val out = LinkedHashMap<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val id = keys.next().trim()
                val slotId = json.optString(id, "").trim()
                if (id.isNotEmpty() && slotId.isNotEmpty()) {
                    out[id] = slotId
                }
            }
            out
        } catch (_: Exception) {
            LinkedHashMap()
        }
    }

    private fun loadState(): State {
        return State(
            pendingIds = loadIds(KEY_PENDING_IDS),
            pendingReportAtMs = prefs().getLong(KEY_PENDING_REPORT_AT_MS, 0L),
            pendingDisplayDuration = prefs().getInt(KEY_PENDING_DISPLAY_DURATION, DEFAULT_DISPLAY_DURATION),
            pendingPositions = loadPositions(KEY_PENDING_POSITIONS),
            pendingSlotIds = loadSlotIds(KEY_PENDING_SLOT_IDS),
            inFlightIds = loadIds(KEY_IN_FLIGHT_IDS),
            inFlightDisplayDuration = prefs().getInt(KEY_IN_FLIGHT_DISPLAY_DURATION, DEFAULT_DISPLAY_DURATION),
            inFlightPositions = loadPositions(KEY_IN_FLIGHT_POSITIONS),
            inFlightSlotIds = loadSlotIds(KEY_IN_FLIGHT_SLOT_IDS),
            dirty = false
        )
    }

    private fun saveState(state: State) {
        val editor = prefs().edit()
            .putStringSet(KEY_PENDING_IDS, state.pendingIds.toSet())
            .putLong(KEY_PENDING_REPORT_AT_MS, state.pendingReportAtMs)
            .putInt(KEY_PENDING_DISPLAY_DURATION, state.pendingDisplayDuration)
            .putStringSet(KEY_IN_FLIGHT_IDS, state.inFlightIds.toSet())
            .putInt(KEY_IN_FLIGHT_DISPLAY_DURATION, state.inFlightDisplayDuration)

        if (state.pendingPositions.isEmpty()) {
            editor.remove(KEY_PENDING_POSITIONS)
        } else {
            editor.putString(KEY_PENDING_POSITIONS, JSONObject(state.pendingPositions as Map<*, *>).toString())
        }
        if (state.pendingSlotIds.isEmpty()) {
            editor.remove(KEY_PENDING_SLOT_IDS)
        } else {
            editor.putString(KEY_PENDING_SLOT_IDS, JSONObject(state.pendingSlotIds as Map<*, *>).toString())
        }
        if (state.inFlightPositions.isEmpty()) {
            editor.remove(KEY_IN_FLIGHT_POSITIONS)
        } else {
            editor.putString(KEY_IN_FLIGHT_POSITIONS, JSONObject(state.inFlightPositions as Map<*, *>).toString())
        }
        if (state.inFlightSlotIds.isEmpty()) {
            editor.remove(KEY_IN_FLIGHT_SLOT_IDS)
        } else {
            editor.putString(KEY_IN_FLIGHT_SLOT_IDS, JSONObject(state.inFlightSlotIds as Map<*, *>).toString())
        }

        editor.apply()
    }

    private fun normalizeState(state: State, ttlMs: Long, now: Long) {
        if (state.pendingIds.isEmpty()) {
            if (state.pendingReportAtMs != 0L) {
                state.pendingReportAtMs = 0L
                state.dirty = true
            }
            val safePendingDuration = safeDisplayDuration(state.pendingDisplayDuration)
            if (state.pendingDisplayDuration != safePendingDuration) {
                state.pendingDisplayDuration = safePendingDuration
                state.dirty = true
            }
            if (state.pendingPositions.isNotEmpty()) {
                state.pendingPositions.clear()
                state.dirty = true
            }
            if (state.pendingSlotIds.isNotEmpty()) {
                state.pendingSlotIds.clear()
                state.dirty = true
            }
        } else {
            if (state.pendingReportAtMs <= 0L) {
                state.pendingReportAtMs = now + ttlMs.coerceAtLeast(0L)
                state.dirty = true
            }
            val safePendingDuration = safeDisplayDuration(state.pendingDisplayDuration)
            if (state.pendingDisplayDuration != safePendingDuration) {
                state.pendingDisplayDuration = safePendingDuration
                state.dirty = true
            }
            val pendingPositionIt = state.pendingPositions.iterator()
            while (pendingPositionIt.hasNext()) {
                if (!state.pendingIds.contains(pendingPositionIt.next().key)) {
                    pendingPositionIt.remove()
                    state.dirty = true
                }
            }
            val pendingSlotIt = state.pendingSlotIds.iterator()
            while (pendingSlotIt.hasNext()) {
                if (!state.pendingIds.contains(pendingSlotIt.next().key)) {
                    pendingSlotIt.remove()
                    state.dirty = true
                }
            }
        }

        if (state.inFlightIds.isEmpty()) {
            if (state.inFlightPositions.isNotEmpty()) {
                state.inFlightPositions.clear()
                state.dirty = true
            }
            if (state.inFlightSlotIds.isNotEmpty()) {
                state.inFlightSlotIds.clear()
                state.dirty = true
            }
        } else {
            val inFlightPositionIt = state.inFlightPositions.iterator()
            while (inFlightPositionIt.hasNext()) {
                if (!state.inFlightIds.contains(inFlightPositionIt.next().key)) {
                    inFlightPositionIt.remove()
                    state.dirty = true
                }
            }
            val inFlightSlotIt = state.inFlightSlotIds.iterator()
            while (inFlightSlotIt.hasNext()) {
                if (!state.inFlightIds.contains(inFlightSlotIt.next().key)) {
                    inFlightSlotIt.remove()
                    state.dirty = true
                }
            }
        }
    }

    @Synchronized
    fun recoverInFlight(ttlMs: Long) {
        val now = System.currentTimeMillis()
        val state = loadState()
        if (state.inFlightIds.isNotEmpty()) {
            state.inFlightIds.forEach { id ->
                if (!state.pendingIds.contains(id)) {
                    state.pendingIds.add(id)
                }
                val position = state.inFlightPositions[id]
                if (position != null && position > 0 && !state.pendingPositions.containsKey(id)) {
                    state.pendingPositions[id] = position
                }
                val slotId = state.inFlightSlotIds[id]
                if (!slotId.isNullOrEmpty() && !state.pendingSlotIds.containsKey(id)) {
                    state.pendingSlotIds[id] = slotId
                }
            }
            state.inFlightIds.clear()
            state.inFlightDisplayDuration = DEFAULT_DISPLAY_DURATION
            state.inFlightPositions.clear()
            state.inFlightSlotIds.clear()
            state.dirty = true
        }
        // Any pending data surviving a process kill should be reported immediately on next launch
        if (state.pendingIds.isNotEmpty()) {
            Log.i("mlog", "进行补报 ids=${state.pendingIds.toList()} → report immediately")
            pendingRecovered = true
            state.pendingReportAtMs = now
            state.pendingDisplayDuration = safeDisplayDuration(state.pendingDisplayDuration)
            state.dirty = true
        } else {
            state.pendingReportAtMs = 0L
            state.pendingDisplayDuration = DEFAULT_DISPLAY_DURATION
        }
        if (state.dirty) {
            saveState(state)
        }
    }

    @Synchronized
    fun queueExposures(
        ids: Collection<String>,
        displayDuration: Int,
        ttlMs: Long,
        positions: Map<String, Int>? = null,
        slotIds: Map<String, String>? = null
    ): Boolean {
        if (ids.isEmpty()) return false
        val now = System.currentTimeMillis()
        val state = loadState()
        normalizeState(state, ttlMs, now)
        val normalizedPositions = positions ?: emptyMap()
        val normalizedSlotIds = slotIds ?: emptyMap()

        var added = false
        ids.forEach { rawId ->
            val id = rawId.trim()
            if (id.isEmpty()) return@forEach
            val position = normalizedPositions[id]?.takeIf { it > 0 }
            val slotId = normalizedSlotIds[id]?.trim()?.takeIf { it.isNotEmpty() }
            if (state.pendingIds.contains(id)) {
                if (position != null && !state.pendingPositions.containsKey(id)) {
                    state.pendingPositions[id] = position
                    state.dirty = true
                }
                if (slotId != null && !state.pendingSlotIds.containsKey(id)) {
                    state.pendingSlotIds[id] = slotId
                    state.dirty = true
                }
                return@forEach
            }
            if (state.inFlightIds.contains(id)) return@forEach
            state.pendingIds.add(id)
            if (position != null) {
                state.pendingPositions[id] = position
            }
            if (slotId != null) {
                state.pendingSlotIds[id] = slotId
            }
            added = true
        }

        if (added) {
            if (state.pendingReportAtMs <= 0L) {
                state.pendingReportAtMs = now + ttlMs.coerceAtLeast(0L)
            }
            state.pendingDisplayDuration = safeDisplayDuration(displayDuration)
            state.dirty = true
        }

        if (state.dirty) {
            saveState(state)
        }
        return added
    }

    @Synchronized
    fun nextFlushDelayMs(ttlMs: Long): Long? {
        val now = System.currentTimeMillis()
        val state = loadState()
        normalizeState(state, ttlMs, now)
        if (state.dirty) {
            saveState(state)
        }
        if (state.pendingIds.isEmpty()) return null
        return (state.pendingReportAtMs - now).coerceAtLeast(0L)
    }

    @Synchronized
    fun beginFlush(ttlMs: Long): PendingBatch? {
        val now = System.currentTimeMillis()
        val state = loadState()
        normalizeState(state, ttlMs, now)
        if (state.pendingIds.isEmpty()) {
            if (state.dirty) saveState(state)
            return null
        }
        if (now < state.pendingReportAtMs) {
            if (state.dirty) saveState(state)
            return null
        }

        val batch = PendingBatch(
            ids = state.pendingIds.toList(),
            displayDuration = safeDisplayDuration(state.pendingDisplayDuration),
            positions = state.pendingIds
                .mapNotNull { id ->
                    state.pendingPositions[id]?.takeIf { it > 0 }?.let { position -> id to position }
                }
                .toMap(LinkedHashMap()),
            slotIds = state.pendingIds
                .mapNotNull { id ->
                    state.pendingSlotIds[id]?.trim()?.takeIf { it.isNotEmpty() }?.let { slotId -> id to slotId }
                }
                .toMap(LinkedHashMap()),
            recovered = pendingRecovered
        )

        state.inFlightIds.clear()
        state.inFlightIds.addAll(batch.ids)
        state.inFlightDisplayDuration = batch.displayDuration
        state.inFlightPositions.clear()
        state.inFlightPositions.putAll(batch.positions)
        state.inFlightSlotIds.clear()
        state.inFlightSlotIds.putAll(batch.slotIds)
        state.pendingIds.clear()
        state.pendingReportAtMs = 0L
        state.pendingDisplayDuration = DEFAULT_DISPLAY_DURATION
        state.pendingPositions.clear()
        state.pendingSlotIds.clear()
        state.dirty = true
        saveState(state)
        return batch
    }

    @Synchronized
    fun completeFlushSuccess() {
        pendingRecovered = false
        val state = loadState()
        if (state.inFlightIds.isNotEmpty()) {
            state.inFlightIds.clear()
            state.dirty = true
        }
        if (state.inFlightDisplayDuration != DEFAULT_DISPLAY_DURATION) {
            state.inFlightDisplayDuration = DEFAULT_DISPLAY_DURATION
            state.dirty = true
        }
        if (state.inFlightPositions.isNotEmpty()) {
            state.inFlightPositions.clear()
            state.dirty = true
        }
        if (state.inFlightSlotIds.isNotEmpty()) {
            state.inFlightSlotIds.clear()
            state.dirty = true
        }
        if (state.dirty) {
            saveState(state)
        }
    }

    @Synchronized
    fun completeFlushFailure(ttlMs: Long) {
        val now = System.currentTimeMillis()
        val state = loadState()
        val restoredDuration = safeDisplayDuration(state.inFlightDisplayDuration)
        var restored = false
        state.inFlightIds.forEach { id ->
            if (!state.pendingIds.contains(id)) {
                state.pendingIds.add(id)
                restored = true
            }
            val position = state.inFlightPositions[id]
            if (position != null && position > 0 && !state.pendingPositions.containsKey(id)) {
                state.pendingPositions[id] = position
                state.dirty = true
            }
            val slotId = state.inFlightSlotIds[id]
            if (!slotId.isNullOrEmpty() && !state.pendingSlotIds.containsKey(id)) {
                state.pendingSlotIds[id] = slotId
                state.dirty = true
            }
        }
        if (state.inFlightIds.isNotEmpty()) {
            state.inFlightIds.clear()
            state.dirty = true
        }
        if (state.inFlightDisplayDuration != DEFAULT_DISPLAY_DURATION) {
            state.inFlightDisplayDuration = DEFAULT_DISPLAY_DURATION
            state.dirty = true
        }
        if (state.inFlightPositions.isNotEmpty()) {
            state.inFlightPositions.clear()
            state.dirty = true
        }
        if (state.inFlightSlotIds.isNotEmpty()) {
            state.inFlightSlotIds.clear()
            state.dirty = true
        }
        if (restored && state.pendingDisplayDuration <= 0) {
            state.pendingDisplayDuration = restoredDuration
            state.dirty = true
        }
        if (state.pendingIds.isNotEmpty()) {
            state.pendingReportAtMs = now + ttlMs.coerceAtLeast(0L)
            state.pendingDisplayDuration = safeDisplayDuration(state.pendingDisplayDuration)
            state.dirty = true
        } else if (state.pendingReportAtMs != 0L) {
            state.pendingReportAtMs = 0L
            state.dirty = true
        }
        if (state.dirty) {
            saveState(state)
        }
    }

    @Synchronized
    fun clearAll() {
        pendingRecovered = false
        prefs().edit().clear().apply()
    }
}
