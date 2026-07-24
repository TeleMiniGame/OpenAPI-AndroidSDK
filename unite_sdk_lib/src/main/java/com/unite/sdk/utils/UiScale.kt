package com.unite.sdk.utils

import android.content.res.Resources
import kotlin.math.min

object UiScale {
    private const val DESIGN_WIDTH_DP = 375f  // 设计稿基准宽度（dp，非 px）

    // 参与缩放的屏宽上限（dp）。designPx 是「按屏宽/375 等比放大」，不设上限时
    // 平板/折叠屏/横屏上卡片会无限放大（大卡撑成整屏）。超过 480dp 的宽度按 480dp 计，
    // 卡片在大屏上保持手机端的合理观感，由宿主布局决定摆放（居中/多列）。
    private const val MAX_SCALE_WIDTH_DP = 480f

    fun designPx(resources: Resources, value: Int): Int {
        val dm = resources.displayMetrics
        val effectiveWidth = min(dm.widthPixels.toFloat(), MAX_SCALE_WIDTH_DP * dm.density)
        return (value * effectiveWidth / DESIGN_WIDTH_DP + 0.5f).toInt()
    }
}

fun Resources.designPx(value: Int): Int = UiScale.designPx(this, value)
