package com.unite.sdk.utils

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

// 启动时异步按设备公网 IP 反查国家并缓存。
// 用于「无 SIM / 纯 WiFi / 平板」等拿不到 network/SIM ISO 的设备：此时仍能给出独立于系统地区设置的国家，
// 且无需任何权限。插卡手机走 TelephonyManager，不会用到这里。
object IpCountry {
    @Volatile
    var value: String = ""
        private set

    // 返回 {"ip":"x.x.x.x","country":"US"}，免费、无需鉴权、HTTPS、由 Cloudflare 支撑。
    private const val GEO_URL = "https://api.country.is/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun refresh() {
        val request = Request.Builder().url(GEO_URL).get().build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.w("mlog", "IP 国家拉取失败 err=${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (!it.isSuccessful || body.isEmpty()) return
                    try {
                        val country = JSONObject(body).optString("country").trim()
                        if (country.isNotEmpty()) {
                            value = country.uppercase(Locale.ROOT)
                            Log.d("mlog", "公网出口IP国家=$value")
                        }
                    } catch (e: Exception) {
                        Log.w("mlog", "IP 国家解析失败 err=${e.message}")
                    }
                }
            }
        })
    }
}
