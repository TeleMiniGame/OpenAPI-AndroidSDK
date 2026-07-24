package com.unite.sdk.utils

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.TimeUnit

// 启动时异步拉取公网出口 IP 并缓存，供上报接口写入 x-user-ip 请求头。
object PublicIp {
    @Volatile
    var value: String = ""
        private set

    private const val IP_URL = "https://api.ipify.org"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // 上报取值：优先公网出口 IP，拿不到则回退本机网卡 IP。
    fun current(): String = value.ifEmpty { localIp() }

    // 遍历网卡读取本机 IP，优先 IPv4，纯本地零延迟、无需联网。
    fun localIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
            var ipv6Fallback = ""
            for (nif in Collections.list(interfaces)) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val host = addr.hostAddress?.substringBefore('%').orEmpty()
                    if (host.isEmpty()) continue
                    if (addr is Inet4Address) return host
                    if (ipv6Fallback.isEmpty()) ipv6Fallback = host
                }
            }
            return ipv6Fallback
        } catch (e: Exception) {
            Log.w("mlog", "本机 IP 读取失败 err=${e.message}")
            return ""
        }
    }

    fun refresh() {
        val request = Request.Builder().url(IP_URL).get().build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.w("mlog", "公网 IP 拉取失败 err=${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val ip = it.body?.string()?.trim().orEmpty()
                    if (it.isSuccessful && ip.isNotEmpty()) {
                        value = ip
                        Log.d("mlog", "公网 IP=$ip")
                    }
                }
            }
        })
    }
}
