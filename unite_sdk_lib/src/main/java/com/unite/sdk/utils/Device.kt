package com.unite.sdk.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import com.unite.sdk.GameSlotSdk
import java.util.Locale

object Device {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }
    fun getDeviceId(): String {
        return Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    // App 通过 GameSlotSdk.setLanguage 设置则优先，否则回退系统语言。
    fun getLanguage(): String {
        return GameSlotSdk.language.ifEmpty { Locale.getDefault().language }
    }

    // 国家判定优先级：App 显式覆盖 > 网络接入国家(漫游也准) > SIM 归属国 > IP 反查国家(无SIM/WiFi设备) > 系统地区(仅兜底)。
    // 前四级都独立于用户在设置里选的地区；Locale.getDefault().country 只是系统设置，可能为空或与真实位置不符，仅作最后兜底。
    fun getCountry(): String {
        GameSlotSdk.country.takeIf { it.isNotEmpty() }?.let { return it.uppercase(Locale.ROOT) }
        try {
            val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm != null) {
                tm.networkCountryIso?.takeIf { it.isNotBlank() }?.let { return it.uppercase(Locale.ROOT) }
                tm.simCountryIso?.takeIf { it.isNotBlank() }?.let { return it.uppercase(Locale.ROOT) }
            }
        } catch (_: Exception) {
            // 部分设备/无电话能力(平板/模拟器)取 TelephonyManager 可能抛异常，忽略后走下一级
        }
        // 无 SIM 的纯 WiFi 设备：用公网 IP 反查的国家，仍独立于系统地区设置（启动时异步预取，见 IpCountry）
        IpCountry.value.takeIf { it.isNotEmpty() }?.let { return it }
        return Locale.getDefault().country.uppercase(Locale.ROOT)
    }

    fun getOrientation(): String {
        val context = GameSlotSdk.applicationContext
        return if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            "horizontal"
        } else {
            "vertical"
        }
    }

    fun buildSlotParams(gameSlotId: String): MutableMap<String, String> {
        val params = mutableMapOf<String, String>()
        params["slot_id"] = gameSlotId
        params["user_id"] = GameSlotSdk.uid
        params["device_id"] = getDeviceId()
        params["device_brand"] = Build.BRAND
        params["device_model"] = Build.MODEL
        params["language"] = getLanguage()
        params["country"] = getCountry()
        params["orientation"] = getOrientation()

        return params
    }
}

