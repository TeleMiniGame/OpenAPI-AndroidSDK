package com.unite.sdk

import android.content.Context
import android.util.Log
import com.unite.sdk.tracking.TrackingReporter
import com.unite.sdk.network.SlotCacheStore
import com.unite.sdk.utils.Device
import com.unite.sdk.utils.ExposureStore
import com.unite.sdk.utils.ImageLoader
import com.unite.sdk.utils.IpCountry
import com.unite.sdk.utils.PublicIp
import java.security.MessageDigest

object GameSlotSdk {
    enum class Environment {
        AUTO,
        DEV,
        PROD
    }

    internal lateinit var applicationContext: Context
        private set

    internal var client_id: String = ""
        private set

    internal var secret_key: String = ""
        private set

    internal var app_id: String = ""
        private set

    internal var app_version: String = ""
        private set

    internal var uid: String = ""
        private set

    // App 自定义语言。为空时回退系统语言（见 Device.getLanguage）。
    internal var language: String = ""
        private set

    // App 自定义国家/地区（如 "US"、"VN"）。为空时按 网络ISO→SIM ISO→系统地区 兜底（见 Device.getCountry）。
    internal var country: String = ""
        private set

    internal var baseUrl: String = ""

    internal var eventUrl: String = ""

    internal var personalizedEnabled: Boolean = true

    internal var sessionId: String = ""
    // 曝光批量上报的聚合窗口 / 去重 TTL，默认 3 分钟（可用 setImpressionTtlMillis 覆盖）。
    internal var impressionTtlMs: Long = 3 * 60 * 1000L
    // 广告缓存新鲜期（命中直接用，不发请求）；过期后进入 stale 兜底窗（先渲染缓存、后台刷新）。
    internal var adCacheTtlMs: Long = 5 * 1000L
    internal var adCacheFallbackMaxAgeMs: Long = 24 * 60 * 60 * 1000L

    internal var environment: Environment = Environment.AUTO
        private set

    private var initialized = false

    private const val CONFIG_PREF_NAME = "unite_sdk_config"
    private const val KEY_CONFIG_FINGERPRINT = "config_fingerprint"

    /**
     * 一次性初始化配置。appId / appVersion 不填时自动取宿主包名 / versionName，
     * environment 默认 AUTO —— 把原先「setEnvironment + init(7参)」压成一次调用。
     */
    data class GameSlotConfig(
        val clientId: String,
        val secretKey: String,
        val uid: String,
        val appId: String? = null,
        val appVersion: String? = null,
        val environment: Environment = Environment.AUTO,
    )

    /** 推荐的一次性初始化入口（向后兼容：旧的 6 参 init 仍保留）。 */
    fun init(context: Context, config: GameSlotConfig) {
        setEnvironment(config.environment)
        val ctx = context.applicationContext
        val resolvedAppId = config.appId ?: ctx.packageName
        val resolvedVersion = config.appVersion
            ?: runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
            ?: "1.0"
        init(ctx, config.clientId, config.secretKey, resolvedAppId, config.uid, resolvedVersion)
    }

    fun init(context: Context, clientId: String, secretKey: String, appId: String, uid: String, appVersion: String) {
        this.applicationContext = context.applicationContext
        this.client_id = clientId
        this.secret_key = secretKey
        this.uid = uid
        this.app_id = appId
        this.app_version = appVersion
        Device.init(applicationContext)
        ImageLoader.init(applicationContext)
        PublicIp.refresh()
        IpCountry.refresh()

        initialized = true
        clearStaleStateIfConfigChanged()
        TrackingReporter.resumePendingImpressionUpload()
    }

    // 配置（client_id/app_id/uid）相比上次启动发生变化时，清空残留曝光，
    // 避免旧配置排队的曝光被新凭证补报，导致服务端身份校验/签名失败。
    private fun clearStaleStateIfConfigChanged() {
        val prefs = applicationContext.getSharedPreferences(CONFIG_PREF_NAME, Context.MODE_PRIVATE)
        val fingerprint = configFingerprint()
        val previous = prefs.getString(KEY_CONFIG_FINGERPRINT, null)
        if (previous != null && previous != fingerprint) {
            Log.i("mlog", "SDK 配置变更，清空残留曝光与广告缓存以避免跨配置补报")
            ExposureStore.clearAll()
            SlotCacheStore.clearAll()
        }
        prefs.edit().putString(KEY_CONFIG_FINGERPRINT, fingerprint).apply()
    }

    private fun configFingerprint(): String {
        val raw = "$client_id|$app_id|$uid"
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    fun setEnvironment(environment: Environment) {
        this.environment = environment
    }

    /**
     * 设置上报使用的语言（如 "en"、"zh"、"ar"）。
     * App 内切换多语言后调用即可生效；不调用或传空则回退系统语言。
     */
    fun setLanguage(language: String) {
        this.language = language.trim()
    }

    /**
     * 设置国家/地区（ISO 3166 两位码，如 "US"、"VN"、"BR"）。
     * App 自己有选区逻辑时调用，最权威；不调用或传空则按 网络国家→SIM 国家→系统地区 自动判定。
     */
    fun setCountry(country: String) {
        this.country = country.trim()
    }

    fun setServerBaseUrl(url: String) {
        baseUrl = url
    }

    fun setEventUrl(url: String) {
        eventUrl = url
    }

    fun setSessionId(sessionId: String) {
        this.sessionId = sessionId
    }

    fun setImpressionTtlMillis(ttlMs: Long) {
        this.impressionTtlMs = ttlMs
        if (initialized) {
            TrackingReporter.resumePendingImpressionUpload()
        }
    }

    fun setAdCacheTtlMillis(ttlMs: Long) {
        this.adCacheTtlMs = ttlMs.coerceAtLeast(0L)
    }

    fun setAdCacheFallbackMaxAgeMillis(maxAgeMs: Long) {
        this.adCacheFallbackMaxAgeMs = maxAgeMs.coerceAtLeast(0L)
    }


    fun setPersonalizedEnabled(enabled: Boolean) {
        personalizedEnabled = enabled
    }

    internal fun isInitialized(): Boolean {
        return initialized
    }
}

