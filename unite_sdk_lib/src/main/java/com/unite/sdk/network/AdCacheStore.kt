package com.unite.sdk.network

import com.unite.sdk.GameSlotSdk
import java.io.File
import java.security.MessageDigest

object SlotCacheStore {
    private const val CACHE_DIR = "unite_sdk/slot_api"
    private const val CACHE_EXT = ".json"

    @Synchronized
    fun read(cacheKey: String, maxAgeMs: Long): String? {
        if (maxAgeMs < 0L) return null
        val file = fileForKey(cacheKey)
        if (!file.exists()) return null

        val ageMs = System.currentTimeMillis() - file.lastModified()
        if (ageMs > maxAgeMs) {
            if (maxAgeMs == 0L) {
                file.delete()
            }
            return null
        }

        return try {
            file.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun write(cacheKey: String, body: String) {
        val file = fileForKey(cacheKey)
        try {
            file.parentFile?.mkdirs()
            file.writeText(body, Charsets.UTF_8)
            file.setLastModified(System.currentTimeMillis())
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun clearAll() {
        val cacheRoot = File(GameSlotSdk.applicationContext.cacheDir, CACHE_DIR)
        if (!cacheRoot.exists()) return
        try {
            cacheRoot.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    private fun fileForKey(cacheKey: String): File {
        val cacheRoot = File(GameSlotSdk.applicationContext.cacheDir, CACHE_DIR)
        if (!cacheRoot.exists()) {
            cacheRoot.mkdirs()
        }
        return File(cacheRoot, sha256(cacheKey) + CACHE_EXT)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val output = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            output.append(String.format("%02x", b))
        }
        return output.toString()
    }
}
