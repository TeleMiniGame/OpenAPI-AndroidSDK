package com.unite.sdk.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.unite.sdk.GameSlotSdk
import com.unite.sdk.slot.GameSlot
import com.unite.sdk.slot.GameListApiResponse
import com.unite.sdk.slot.toGameSlot
import com.unite.sdk.utils.Device
import com.unite.sdk.utils.ImageLoader
import com.unite.sdk.utils.PublicIp

object SlotApi {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private val inFlightRequests = LinkedHashMap<String, MutableList<(Result<List<GameSlot>>) -> Unit>>()

    // 冷启动时 view.loadSlot 可能早于 GameSlotSdk.init() 完成（杀进程重开 + 切语言导致无缓存命中），
    // 首个请求会因 "not initialized" 或瞬时网络抖动直接失败，骨架屏卡死。失败后延时重试一次即可自愈。
    // 空数据（success(emptyList)）不算失败，不重试——后端确实无库存。
    private const val RETRY_DELAY_MS = 800L

    // 广告请求附带公网出口 IP，便于服务端按真实 IP 做 GeoIP 国家判定（即使在 CDN/代理后也能拿到真实来源）。
    // v2 签名已把 x-user-ip 纳入签名串（user_ip 字段），防篡改；v1 旧签名仍不含它（兼容期）。
    private fun userIpHeaders(): Map<String, String>? =
        PublicIp.current().takeIf { it.isNotEmpty() }?.let { mapOf("x-user-ip" to it) }

    fun loadSlots(gameSlotId: String, callback: (Result<List<GameSlot>>) -> Unit) {
        loadSlotsInternal(gameSlotId, retriesLeft = 1, callback)
    }

    private fun loadSlotsInternal(
        gameSlotId: String,
        retriesLeft: Int,
        callback: (Result<List<GameSlot>>) -> Unit
    ) {
        // 包一层：失败且还有重试次数时延时重投，否则把结果交还给真正的 callback。成功（含空列表）直接透传。
        val retryingCallback: (Result<List<GameSlot>>) -> Unit = { result ->
            if (result.isFailure && retriesLeft > 0) {
                Log.w("mlog", "loadSlots failed, retry in ${RETRY_DELAY_MS}ms slot=$gameSlotId left=$retriesLeft err=${result.exceptionOrNull()?.message}")
                mainHandler.postDelayed({
                    loadSlotsInternal(gameSlotId, retriesLeft - 1, callback)
                }, RETRY_DELAY_MS)
            } else {
                callback(result)
            }
        }

        if (!GameSlotSdk.isInitialized()) {
            mainHandler.post {
                retryingCallback(Result.failure(IllegalStateException("GameSlotSdk not initialized")))
            }
            return
        }

        val base = GameSlotSdk.baseUrl
        val url = Api.slotListUrl(base) ?: run {
            mainHandler.post { retryingCallback(Result.failure(IllegalArgumentException("Invalid baseUrl"))) }
            return
        }


        val params = Device.buildSlotParams(gameSlotId)
        val cacheKey = buildCacheKey(url, params)

        // 1. Fresh cache hit → return immediately
        loadCachedSlots(cacheKey, GameSlotSdk.adCacheTtlMs)?.let { slots ->
            Log.i("mlog", "loadSlots hit fresh cache slot=$gameSlotId size=${slots.size}")
            prefetchAssets(slots)
            mainHandler.post { callback(Result.success(slots)) }
            return
        }

        // 2. Stale cache hit → 先渲染兜底（标记 provisional，视图渲染但不计曝光），后台静默刷新
        val staleSlots = loadCachedSlots(cacheKey, GameSlotSdk.adCacheFallbackMaxAgeMs)
        if (staleSlots != null) {
            Log.i("mlog", "loadSlots hit stale cache slot=$gameSlotId size=${staleSlots.size}")
            prefetchAssets(staleSlots)
            val provisional = staleSlots.map { it.copy(provisional = true) }
            mainHandler.post { callback(Result.success(provisional)) }
            refreshInBackground(cacheKey, gameSlotId, url, params, staleSlots, callback)
            return
        }

        // 3. No cache → normal network request
        if (!enqueueInFlight(cacheKey, retryingCallback)) {
            Log.i("mlog", "loadSlots join in-flight request slot=$gameSlotId")
            return
        }

        HttpUtil.request("GET", url, params, userIpHeaders()) { result ->
            result.onSuccess { body ->
                Log.d("mlog", "loadSlots network success slot=$gameSlotId body=$body")
                try {
                    val slots = parseSlots(body)
                    SlotCacheStore.write(cacheKey, body)
                    prefetchAssets(slots)
                    dispatchInFlight(cacheKey, Result.success(slots))
                } catch (e: Exception) {
                    Log.e("mlog", "loadSlots parse failed slot=$gameSlotId err=${e.message}")
                    dispatchInFlight(cacheKey, Result.failure(e))
                }
            }.onFailure { e ->
                Log.e("mlog", "loadSlots network failed slot=$gameSlotId err=${e.message}")
                dispatchInFlight(cacheKey, Result.failure(e))
            }
        }
    }

    fun loadSlot(gameSlotId: String, callback: (Result<GameSlot?>) -> Unit) {
        loadSlots(gameSlotId) { result ->
            result.onSuccess { list ->
                when {
                    list.isEmpty() -> mainHandler.post { callback(Result.success(null)) }
                    else -> mainHandler.post { callback(Result.success(list[0])) }
                }
            }.onFailure { e ->
                mainHandler.post { callback(Result.failure(e)) }
            }
        }
    }

    /**
     * 大厅游戏位（slot_type=gameCenter）：请求与普通游戏位完全一致（同 URL/参数/签名），
     * 区别只在响应——大厅位 items 为空、data.hall_url 下发游戏中心域名。
     * 判定规则：hall_url 非空 ⇒ 大厅位（服务端 EmitUnpopulated=true，普通位也带空串，不能看字段是否存在）。
     * 与 loadSlots 共用同一份原始响应缓存（同 cacheKey）；冷启动竞态同样延时重试一次。
     */
    fun loadHallUrl(gameSlotId: String, callback: (Result<String>) -> Unit) {
        loadHallUrlInternal(gameSlotId, retriesLeft = 1, callback)
    }

    private fun loadHallUrlInternal(
        gameSlotId: String,
        retriesLeft: Int,
        callback: (Result<String>) -> Unit
    ) {
        val retryingCallback: (Result<String>) -> Unit = { result ->
            if (result.isFailure && retriesLeft > 0) {
                Log.w("mlog", "loadHallUrl failed, retry in ${RETRY_DELAY_MS}ms slot=$gameSlotId err=${result.exceptionOrNull()?.message}")
                mainHandler.postDelayed({
                    loadHallUrlInternal(gameSlotId, retriesLeft - 1, callback)
                }, RETRY_DELAY_MS)
            } else {
                callback(result)
            }
        }

        if (!GameSlotSdk.isInitialized()) {
            mainHandler.post {
                retryingCallback(Result.failure(IllegalStateException("GameSlotSdk not initialized")))
            }
            return
        }
        val base = GameSlotSdk.baseUrl
        val url = Api.slotListUrl(base) ?: run {
            mainHandler.post { retryingCallback(Result.failure(IllegalArgumentException("Invalid baseUrl"))) }
            return
        }
        val params = Device.buildSlotParams(gameSlotId)
        val cacheKey = buildCacheKey(url, params)

        // 新鲜缓存命中（域名变更时以最新响应为准：fresh TTL 默认仅 5s，可接受）
        val cachedBody = SlotCacheStore.read(cacheKey, GameSlotSdk.adCacheTtlMs)
        if (cachedBody != null) {
            val hall = try { parseHallUrl(cachedBody) } catch (_: Exception) { null }
            if (hall != null) {
                Log.i("mlog", "loadHallUrl hit cache slot=$gameSlotId hall=$hall")
                mainHandler.post { callback(Result.success(hall)) }
                return
            }
        }

        HttpUtil.request("GET", url, params, userIpHeaders()) { result ->
            result.onSuccess { body ->
                try {
                    val hall = parseHallUrl(body)
                    SlotCacheStore.write(cacheKey, body)
                    Log.i("mlog", "loadHallUrl network success slot=$gameSlotId hall=$hall")
                    retryingCallback(Result.success(hall))
                } catch (e: Exception) {
                    Log.e("mlog", "loadHallUrl parse failed slot=$gameSlotId err=${e.message}")
                    retryingCallback(Result.failure(e))
                }
            }.onFailure { e ->
                Log.e("mlog", "loadHallUrl network failed slot=$gameSlotId err=${e.message}")
                retryingCallback(Result.failure(e))
            }
        }
    }

    // 返回 hall_url（可能为空串——空表示该 slot 不是大厅位，由调用方决定失败语义）
    private fun parseHallUrl(json: String): String {
        val resp = gson.fromJson(json, GameListApiResponse::class.java)
        if (resp.code != 200) {
            throw IllegalStateException("api code=${resp.code} msg=${resp.message.orEmpty()}")
        }
        resp.data?.requestId?.takeIf { it.isNotEmpty() }?.let { GameSlotSdk.sessionId = it }
        return resp.data?.hallUrl?.trim().orEmpty()
    }

    // 过期缓存兜底后的后台静默刷新：刷新结果**回灌给发起方的 callback**（provisional=false），
    // 让视图换成最新数据并开始计曝光；刷新失败则回落到 staleSlots 并以 provisional=false
    // 重新下发，使这批缓存内容转为可计曝光——弱网/离线场景不丢曝光。
    // 通过 inFlightRequests 与并发的真实请求去重：onRefreshed 入队后，无论本次是否真正发起网络请求，
    // 都会在该 cacheKey 完成时被 dispatchInFlight 统一回调。
    private fun refreshInBackground(
        cacheKey: String,
        gameSlotId: String,
        url: String,
        params: Map<String, String>,
        staleSlots: List<GameSlot>,
        callback: (Result<List<GameSlot>>) -> Unit
    ) {
        val onRefreshed: (Result<List<GameSlot>>) -> Unit = { result ->
            // 成功用新数据；失败回落到缓存内容。两者都是 provisional=false（parseSlots 默认 false；
            // staleSlots 是原始未标记对象），到视图即开始计曝光。
            val finalSlots = result.getOrNull() ?: staleSlots
            if (result.isFailure) {
                Log.w("mlog", "loadSlots background refresh failed, 回落缓存内容计曝光 slot=$gameSlotId err=${result.exceptionOrNull()?.message}")
            }
            callback(Result.success(finalSlots))
        }
        if (!enqueueInFlight(cacheKey, onRefreshed)) {
            // 已有在途请求：onRefreshed 已搭车入队，完成后一并回调，无需再发一次网络请求
            Log.i("mlog", "loadSlots background refresh 搭车在途请求 slot=$gameSlotId")
            return
        }
        HttpUtil.request("GET", url, params, userIpHeaders()) { result ->
            result.onSuccess { body ->
                Log.d("mlog", "loadSlots background refresh success slot=$gameSlotId")
                try {
                    val slots = parseSlots(body)
                    SlotCacheStore.write(cacheKey, body)
                    prefetchAssets(slots)
                    dispatchInFlight(cacheKey, Result.success(slots))
                } catch (e: Exception) {
                    Log.e("mlog", "loadSlots background refresh parse failed err=${e.message}")
                    dispatchInFlight(cacheKey, Result.failure(e))
                }
            }.onFailure { e ->
                Log.e("mlog", "loadSlots background refresh failed slot=$gameSlotId err=${e.message}")
                dispatchInFlight(cacheKey, Result.failure(e))
            }
        }
    }

    private fun loadCachedSlots(cacheKey: String, maxAgeMs: Long): List<GameSlot>? {
        val cachedBody = SlotCacheStore.read(cacheKey, maxAgeMs) ?: return null
        return try {
            parseSlots(cachedBody)
        } catch (e: Exception) {
            Log.e("mlog", "loadSlots cache parse failed err=${e.message}")
            null
        }
    }

    private fun buildCacheKey(url: String, params: Map<String, String>): String {
        return buildString {
            append(url)
            append('?')
            params.keys.sorted().forEachIndexed { index, key ->
                if (index > 0) append('&')
                append(key)
                append('=')
                append(params[key].orEmpty())
            }
        }
    }

    @Synchronized
    private fun enqueueInFlight(
        cacheKey: String,
        callback: (Result<List<GameSlot>>) -> Unit
    ): Boolean {
        val callbacks = inFlightRequests[cacheKey]
        return if (callbacks != null) {
            callbacks.add(callback)
            false
        } else {
            inFlightRequests[cacheKey] = mutableListOf(callback)
            true
        }
    }

    @Synchronized
    private fun takeInFlight(cacheKey: String): List<(Result<List<GameSlot>>) -> Unit> {
        return inFlightRequests.remove(cacheKey).orEmpty()
    }

    private fun dispatchInFlight(cacheKey: String, result: Result<List<GameSlot>>) {
        val callbacks = takeInFlight(cacheKey)
        if (callbacks.isEmpty()) return
        mainHandler.post {
            callbacks.forEach { callback -> callback(result) }
        }
    }

    private fun parseSlots(json: String): List<GameSlot> {
        val resp = gson.fromJson(json, GameListApiResponse::class.java)
        if (resp.code != 200) {
            throw IllegalStateException("api code=${resp.code} msg=${resp.message.orEmpty()}")
        }
        val requestId = resp.data?.requestId.orEmpty()
        if (requestId.isNotEmpty()) {
            GameSlotSdk.sessionId = requestId
        }
        // m_icon_mode 是 data 级字段，而视图层只拿得到 GameSlot —— 在这里逐 item 复制进去。
        val mIconMode = GameSlot.normalizeMIconMode(resp.data?.mIconMode)
        return resp.data?.items.orEmpty().mapNotNull { it.toGameSlot(mIconMode) }
    }

    private fun prefetchAssets(ads: List<GameSlot>) {
        if (ads.isEmpty()) return
        val urls = LinkedHashSet<String>()
        ads.forEach { gs ->
            listOf(
                gs.icon,
                gs.bigIcon,
                gs.smallIcon,
                gs.banner,
                gs.badgeIcon,
                // 只在这个 item 确实还需要端上叠角标时才预取 m_icon（全部素材都已服务端合成
                // 且没有视频时，预取角标图纯属浪费流量）。
                if (gs.mIcon.isNotEmpty() && (
                        gs.overlayBadgeOn(gs.banner, gs.bigIcon, gs.cover) ||
                        gs.overlayBadgeOn(gs.icon, gs.smallIcon) ||
                        gs.overlayBadgeOn(gs.flash, gs.banner, gs.image) ||
                        (gs.overlayBadgeOnVideo && gs.video.isNotEmpty())
                    )
                ) gs.mIcon else "",
                gs.cover,
                gs.image,
                gs.thumbnailUrl
            ).forEach { url ->
                if (url.isNotBlank()) {
                    urls.add(url)
                }
            }
        }
        if (urls.isNotEmpty()) {
            ImageLoader.prefetchAll(urls)
        }
    }
}
