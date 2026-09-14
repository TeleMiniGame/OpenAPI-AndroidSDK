package com.unite.sdk.network

import com.unite.sdk.BuildConfig
import com.unite.sdk.GameSlotSdk
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object Api {
    private val isProdBuild: Boolean
        get() = when (GameSlotSdk.environment) {
            GameSlotSdk.Environment.PROD -> true
            GameSlotSdk.Environment.DEV -> false
            GameSlotSdk.Environment.AUTO -> BuildConfig.BUILD_TYPE == "release"
        }

    const val prodAPI = "https://openapi.minigame.ai"
    const val devAPI= "https://openapi-sandbox.minigame.ai"
    const val prodReport= "https://stats.minigame.com"
    const val devReport = "https://stats-sandbox.minigame.com"

    private val slotBaseUrl: String
        get() = if (isProdBuild) prodAPI else devAPI

    private val eventBaseUrl: String
        get() = if (isProdBuild) prodReport else devReport

    val SLOT_LIST_PATH: String
        get() = "/openapi/v4/game/slot"

    fun eventPath(eventId: String): String {
        val id = eventId.trim().trim('/')
        return "/api/wy/report/$id"
    }

    fun slotListUrl(baseUrl: String): String? {
        val base = baseUrl.ifBlank { slotBaseUrl }
        return resolve(base, SLOT_LIST_PATH)
    }

    fun eventUrl(baseUrl: String, eventId: String): String? {
        val base = baseUrl.ifBlank { eventBaseUrl }
        return resolve(base, eventPath(eventId))
    }

    fun resolve(baseUrl: String, path: String): String? {
        val normalizedPath = path.trim()
        normalizedPath.toHttpUrlOrNull()?.let { return it.toString() }

        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null

        val relative = if (normalizedPath.startsWith("/")) normalizedPath else "/$normalizedPath"
        return "$base$relative".toHttpUrlOrNull()?.toString()
    }
}

