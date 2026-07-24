package com.unite.sdk.network

import android.util.Log
import com.unite.sdk.GameSlotSdk
import com.unite.sdk.utils.PublicIp
import com.google.gson.Gson

object EventApi {
    private val gson = Gson()

    fun post(
        eventId: String,
        params: Map<String, Any>,
        callback: ((Boolean) -> Unit)? = null,
        reportType: String = "实时"
    ) {
        if (!GameSlotSdk.isInitialized()) {
            callback?.invoke(false)
            return
        }
        val base = GameSlotSdk.eventUrl
        val url = Api.eventUrl(base, eventId) ?: run {
            callback?.invoke(false)
            return
        }

        val extraHeaders = PublicIp.current()
            .takeIf { it.isNotEmpty() }
            ?.let { mapOf("x-user-ip" to it) }

        HttpUtil.request("POST", url, params, extraHeaders) { result ->
            result.onSuccess { body ->
                try {
                    val resp = gson.fromJson(body, EventReportResponse::class.java)
                    if (resp.code != 200) {
                        throw IllegalStateException("api code=${resp.code} msg=${resp.message.orEmpty()}")
                    }
                    Log.i("mlog", "上报成功[$reportType] event=$eventId $params")
                    callback?.invoke(true)
                } catch (_: Exception) {
                    Log.e("mlog", "上报失败[$reportType] event=$eventId err=${body}")
                    Log.e("mlog", "参数 err=${params}")
                    callback?.invoke(false)
                }
            }.onFailure { e ->
                when (e) {
                    is HttpUtil.HttpException -> Log.e("mlog", "上报失败[$reportType] event=$eventId http=${e.code} body=${e.responseBody}")
                    else -> Log.e("mlog", "上报失败[$reportType] event=$eventId err=${e.message}")
                }
                callback?.invoke(false)
            }
        }
    }
}

data class EventReportResponse(
    val code: Int?,
    val message: String?,
    val data: Any?,
    val ts: Long?,
    val tid: String?
)
