package com.unite.sdk.view.cards

import android.content.Context
import android.widget.FrameLayout

/**
 * 卡片基类：当父布局以 EXACTLY 的宽度（如 LinearLayout weight 等分）测量时，
 * 强制高度 = 宽度 * designHeight / designWidth，从而锁定设计稿宽高比。
 * 父布局未给定确切宽度时退回默认测量，保证卡片单独使用时仍可用 designPx 固定尺寸。
 */
open class AspectRatioFrameLayout(
    context: Context,
    private val designWidth: Int,
    private val designHeight: Int
) : FrameLayout(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (widthMode == MeasureSpec.EXACTLY && width > 0 && designWidth > 0) {
            val height = Math.round(width * designHeight.toFloat() / designWidth)
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
