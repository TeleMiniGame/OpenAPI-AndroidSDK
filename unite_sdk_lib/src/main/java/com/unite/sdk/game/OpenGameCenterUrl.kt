package com.unite.sdk.game

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.unite.sdk.WebActivity

object OpenGameCenterUrl {
    fun open(context: Context, url: String) {
        if (url.isBlank()) return
        val intent = Intent(context, WebActivity::class.java)
        intent.putExtra("URL", url)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
