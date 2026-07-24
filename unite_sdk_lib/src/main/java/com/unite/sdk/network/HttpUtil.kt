package com.unite.sdk.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.unite.sdk.GameSlotSdk
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.json.JSONObject

object HttpUtil {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val TAG = "mlog"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    fun request(
        method: String,
        url: String,
        params: Map<String, Any>? = null,
        extraHeaders: Map<String, String>? = null,
        callback: (Result<String>) -> Unit
    ) {
        val upperMethod = method.uppercase()
        if (upperMethod == "GET") {
            val fullUrl = if (params != null && params.isNotEmpty()) {
                val sb = StringBuilder(url)
                if (!url.contains("?")) sb.append("?") else sb.append("&")
                params.entries.forEachIndexed { index, entry ->
                    if (index > 0) sb.append("&")
                    sb.append(URLEncoder.encode(entry.key, "UTF-8"))
                    sb.append("=")
                    sb.append(URLEncoder.encode(entry.value.toString(), "UTF-8"))
                }
                sb.toString()
            } else {
                url
            }
            Log.d(TAG, fullUrl)
            val request = Request.Builder()
                .url(fullUrl)
                .apply { applyCommonHeaders(this, params, extraHeaders) }
                .apply { applyExtraHeaders(this, extraHeaders) }
                .get()
                .build()
            execute(request, callback)
        } else if (upperMethod == "POST") {
            val json = if (params != null) JSONObject(params).toString() else "{}"
            val body = json.toRequestBody(JSON_TYPE)

            val request = Request.Builder()
                .url(url)
                .apply { applyCommonHeaders(this, params, extraHeaders) }
                .apply { applyExtraHeaders(this, extraHeaders) }
                .post(body)
                .build()
            execute(request, callback)
        } else {
            mainHandler.post { callback(Result.failure(IllegalArgumentException("Unsupported method: $method"))) }
        }
    }

    fun get(url: String, params: Map<String, Any>? = null, callback: (Result<String>) -> Unit) {
        request("GET", url, params, callback = callback)
    }

    fun post(url: String, bodyParams: Map<String, Any>, callback: (Result<String>) -> Unit) {
        request("POST", url, bodyParams, callback = callback)
    }

    class HttpException(val code: Int, val responseBody: String) : IOException("HTTP $code: $responseBody")

    fun postForm(url: String, bodyParams: Map<String, Any>, callback: (Result<String>) -> Unit) {
        val sb = StringBuilder()
        var first = true
        for ((k, v) in bodyParams) {
            if (!first) sb.append("&") else first = false
            sb.append(URLEncoder.encode(k, "UTF-8"))
            sb.append("=")
            sb.append(URLEncoder.encode(v.toString(), "UTF-8"))
        }
        val media = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
        val body = sb.toString().toRequestBody(media)
        val request = Request.Builder()
            .url(url)
            .apply { applyCommonHeaders(this, bodyParams) }
            .post(body)
            .build()
        execute(request, callback)
    }

    private fun applyExtraHeaders(builder: Request.Builder, extraHeaders: Map<String, String>?) {
        extraHeaders?.forEach { (k, v) ->
            if (v.isNotEmpty()) builder.header(k, v)
        }
    }

    // 签名版本号。v2 = 完整参数签名（业务参数 + ts + client_id + x-user-ip 全部纳入签名串），
    // 修复 v1 遗留的字段遗漏（client_id 与 x-user-ip 不参与签名、可被伪造）。
    // 过渡期双签名并存：请求同时携带 v1 `sign`（旧服务端继续校验通过）与 `sign-v2` + `sign-ver`
    // （新服务端按 sign-ver 头选择校验规则），待服务端全量支持 v2 后可下线 v1。
    private const val SIGN_VERSION = "2"

    private fun applyCommonHeaders(
        builder: Request.Builder,
        params: Map<String, Any>?,
        extraHeaders: Map<String, String>? = null
    ) {
        val cid = GameSlotSdk.client_id
        val key = GameSlotSdk.secret_key
        if (cid.isEmpty() || key.isEmpty()) return

        val ts = System.currentTimeMillis().toString()
        builder.header("ts", ts)
        builder.header("x-md-global-cid", cid)
        builder.header("sign", buildSignV1(ts, params, key))
        builder.header("sign-v2", buildSignV2(ts, cid, params, extraHeaders, key))
        builder.header("sign-ver", SIGN_VERSION)
    }

    private fun buildSignV1(ts: String, params: Map<String, Any>?, secret: String): String {
        val map = mutableMapOf<String, String>()
        params?.forEach { (k, v) ->
            map[k] = v.toString()
        }
        map["ts"] = ts
        return sha256Lower(secret + joinSorted(map))
    }

    // v2 完整参数签名：业务参数 ∪ { ts, client_id, user_ip(有 x-user-ip 头时) }，
    // key 字典序拼 k1=v1&k2=v2...，sha256Lower(secret + signData)。与 v1 同一散列原语，服务端实现成本低。
    private fun buildSignV2(
        ts: String,
        clientId: String,
        params: Map<String, Any>?,
        extraHeaders: Map<String, String>?,
        secret: String
    ): String {
        val map = mutableMapOf<String, String>()
        params?.forEach { (k, v) ->
            map[k] = v.toString()
        }
        map["ts"] = ts
        map["client_id"] = clientId
        extraHeaders?.get("x-user-ip")?.takeIf { it.isNotEmpty() }?.let { map["user_ip"] = it }
        return sha256Lower(secret + joinSorted(map))
    }

    private fun joinSorted(map: Map<String, String>): String {
        return map.keys
            .sorted()
            .joinToString(separator = "&") { k -> "$k=${map[k].orEmpty()}" }
    }

    private fun sha256Lower(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            out.append(String.format("%02x", b))
        }
        return out.toString()
    }

    private fun execute(request: Request, callback: (Result<String>) -> Unit) {
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                mainHandler.post { callback(Result.failure(e)) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        val errorBody = it.body?.string().orEmpty()
                        mainHandler.post { callback(Result.failure(HttpException(it.code, errorBody))) }
                        return
                    }
                    val data = it.body?.string().orEmpty()
                    mainHandler.post { callback(Result.success(data)) }
                }
            }
        })
    }
}
