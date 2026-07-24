package com.unite.sdk.game

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.unite.sdk.WebActivity
import com.unite.sdk.slot.SlotListener
import com.unite.sdk.slot.GameSlot

object GameLauncher {
    internal var currentGameListener: SlotListener? = null
        private set
    private var currentGameSlot: GameSlot? = null

    fun openGame(context: Context, gs: GameSlot, listener: SlotListener?) {
        currentGameListener = listener
        currentGameSlot = gs
        openInBrowser(context, gs.clickUrl)
    }

    internal fun notifyGameStart() {
        currentGameSlot?.let { gs ->
            currentGameListener?.onGameStart(gs)
        }
    }

    internal fun notifyGameClose() {
        currentGameSlot?.let { gs ->
            currentGameListener?.onGameClose(gs)
        }
        currentGameListener = null
        currentGameSlot = null
    }

    // 游戏统一在 SDK 内置的 WebActivity 中打开（不跳外部浏览器），
    // 以便托管 WebView 生命周期并回调 onGameStart/onGameClose。
    private fun openInBrowser(context: Context, url: String) {
        val intent = Intent(context, WebActivity::class.java)
        intent.putExtra("URL", url)
        // 非 Activity 上下文启动需要 NEW_TASK
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

