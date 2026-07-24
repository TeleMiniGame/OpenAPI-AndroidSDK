package com.unite.sdk.utils

import android.content.Context
import android.util.Log
import android.widget.ImageView
import android.graphics.drawable.BitmapDrawable
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.size.Size
import com.unite.sdk.GameSlotSdk
import java.io.File

object ImageLoader {
    private const val TAG = "mlog"
    private const val MEMORY_CACHE_PERCENT = 0.2
    private const val DISK_CACHE_BYTES = 64L * 1024L * 1024L
    private const val DISK_CACHE_DIR = "unite_sdk/image_cache"

    @Volatile
    private var sdkImageLoader: coil.ImageLoader? = null

    fun init(context: Context) {
        ensureImageLoader(context.applicationContext)
    }

    fun load(url: String, target: ImageView) {
        loadAny(url, target, null, null)
    }

    fun loadWithFallback(urls: List<String>, target: ImageView) {
        val candidates = urls.filter { it.isNotBlank() }
        if (candidates.isEmpty()) return
        loadFallbackChain(candidates, 0, target, onSuccess = null, onAllFailed = null)
    }

    fun loadWithFallback(urls: List<String>, target: ImageView, onSuccess: (() -> Unit)?, onAllFailed: (() -> Unit)?) {
        val candidates = urls.filter { it.isNotBlank() }
        if (candidates.isEmpty()) {
            onAllFailed?.invoke()
            return
        }
        loadFallbackChain(candidates, 0, target, onSuccess, onAllFailed)
    }

    private fun loadFallbackChain(urls: List<String>, index: Int, target: ImageView, onSuccess: (() -> Unit)?, onAllFailed: (() -> Unit)?) {
        if (index >= urls.size) {
            onAllFailed?.invoke()
            return
        }
        val url = urls[index]
        val loader = ensureImageLoader(target.context)

        val memKey = MemoryCache.Key(url)
        val cached = loader.memoryCache?.get(memKey)
        if (cached != null) {
            target.setImageDrawable(BitmapDrawable(target.resources, cached.bitmap))
            onSuccess?.invoke()
            return
        }

        val request = ImageRequest.Builder(target.context)
            .data(normalizeUrl(url))
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(150)
            .target(target)
            .listener(
                onSuccess = { _, _ -> onSuccess?.invoke() },
                onError = { _, result ->
                    Log.d(TAG, "ImageLoader: fallback[$index] failed url=$url err=${result.throwable.message}")
                    loadFallbackChain(urls, index + 1, target, onSuccess, onAllFailed)
                }
            )
            .build()
        loader.enqueue(request)
    }

    fun loadAny(data: Any?, target: ImageView, widthPx: Int?, heightPx: Int?) {
        val normalized = when (data) {
            is String -> normalizeUrl(data)
            else -> data
        }
        if (normalized == null || (normalized is String && normalized.isBlank())) return

        val loader = ensureImageLoader(target.context)
        // Check memory cache first — if hit, set synchronously to avoid any blank frame
        val memKey = MemoryCache.Key(normalized.toString())
        val cached = loader.memoryCache?.get(memKey)
        if (cached != null) {
            target.setImageDrawable(BitmapDrawable(target.resources, cached.bitmap))
            return
        }

        val request = ImageRequest.Builder(target.context)
            .data(normalized)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(150)
            .apply {
                if (widthPx != null && heightPx != null) {
                    size(Size(widthPx, heightPx))
                }
            }
            .target(target)
            .listener(
                onError = { _, result ->
                    Log.d(TAG, "ImageLoader: load failed data=$normalized err=${result.throwable.message}")
                }
            )
            .build()

        loader.enqueue(request)
    }

    /**
     * 加载 M 标角标图，失败时回退到 SDK 内置角标而不是留空。
     *
     * 角标图挂在图床上，存在整域不可用的可能（曾出现过 TLS 故障导致加载失败、角标静默消失
     * 且无任何界面提示的情况）—— M 标属于合规展示，宁可显示内置角标也不能没有。
     */
    fun loadBadge(url: String, target: ImageView, fallbackRes: Int) {
        if (url.isBlank()) {
            target.setImageResource(fallbackRes)
            return
        }
        val normalized = normalizeUrl(url)
        val loader = ensureImageLoader(target.context)
        val cached = loader.memoryCache?.get(MemoryCache.Key(normalized))
        if (cached != null) {
            target.setImageDrawable(BitmapDrawable(target.resources, cached.bitmap))
            return
        }
        // 先摆上内置角标兜底，成功加载后由 Coil 覆盖；失败则原样保留内置角标
        target.setImageResource(fallbackRes)
        val request = ImageRequest.Builder(target.context)
            .data(normalized)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(150)
            .target(
                onSuccess = { target.setImageDrawable(it) },
                onError = { Log.d(TAG, "ImageLoader: m_icon 加载失败，保留内置角标 url=$normalized") }
            )
            .build()
        loader.enqueue(request)
    }

    fun prefetchAll(dataItems: Collection<Any?>) {
        if (dataItems.isEmpty()) return
        val context = GameSlotSdk.applicationContext
        val imageLoader = ensureImageLoader(context)
        dataItems.asSequence()
            .mapNotNull { data ->
                when (data) {
                    is String -> normalizeUrl(data).takeIf { it.isNotBlank() }
                    null -> null
                    else -> data
                }
            }
            .distinct()
            .forEach { data ->
                val request = ImageRequest.Builder(context)
                    .data(data)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .networkCachePolicy(CachePolicy.ENABLED)
                    .listener(
                        onError = { _, result ->
                            Log.d(TAG, "ImageLoader: prefetch failed data=$data err=${result.throwable.message}")
                        }
                    )
                    .build()
                imageLoader.enqueue(request)
            }
    }

    private fun normalizeUrl(url: String): String {
        var s = url.trim()
        s = s.trim('`').trim()
        s = s.removePrefix("`").removeSuffix("`").trim()
        s = s.removeSurrounding("\"").trim()
        s = s.trim('`').trim()
        return s
    }

    private fun ensureImageLoader(context: Context): coil.ImageLoader {
        val existing = sdkImageLoader
        if (existing != null) return existing

        return synchronized(this) {
            val doubleChecked = sdkImageLoader
            if (doubleChecked != null) {
                doubleChecked
            } else {
                val appContext = context.applicationContext
                coil.ImageLoader.Builder(appContext)
                    .memoryCache {
                        MemoryCache.Builder(appContext)
                            .maxSizePercent(MEMORY_CACHE_PERCENT)
                            .build()
                    }
                    .diskCache {
                        DiskCache.Builder()
                            .directory(File(appContext.cacheDir, DISK_CACHE_DIR))
                            .maxSizeBytes(DISK_CACHE_BYTES)
                            .build()
                    }
                    .build()
                    .also { sdkImageLoader = it }
            }
        }
    }
}
