package com.unite.sdk.view

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.TouchDelegate
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import com.unite.sdk.utils.designPx
class SlotActionButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    enum class SizePreset(val widthPx: Int, val heightPx: Int) {
        W44_H16(44, 16), // radius 24px
        W96_H18(96, 18), // radius 24px
        W64_H16(64, 16), // radius 24px
        W62_H26(62, 26), // radius 37px
        W63_H26(63, 26)  // radius 37px
    }

    enum class BackgroundPreset {
        SOLID_TEAL,
        GRADIENT_BLUE_TEAL
    }

    init {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTypeface(null, Typeface.NORMAL)
        maxLines = 1
        isAllCaps = true
        text = "PLAY"
        // 按钮视觉高度最小仅 ~16dp，把父容器上的可点区扩到 ≥48dp（Android 无障碍触控标准），
        // 小屏/手指粗也能可靠点中。视觉尺寸不变。
        addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (l != ol || t != ot || r != orr || b != ob) expandTouchTarget()
        }
    }

    private fun expandTouchTarget() {
        val parentView = parent as? View ?: return
        val minPx = (48 * resources.displayMetrics.density).toInt()
        val rect = Rect(left, top, right, bottom)
        val extraH = ((minPx - rect.height()) / 2).coerceAtLeast(0)
        val extraW = ((minPx - rect.width()) / 2).coerceAtLeast(0)
        if (extraH == 0 && extraW == 0) return
        rect.inset(-extraW, -extraH)
        parentView.touchDelegate = TouchDelegate(rect, this)
    }

    @JvmOverloads
    fun applyPreset(
        size: SizePreset,
        background: BackgroundPreset,
        radiusPx: Int? = null
    ) {
        val w = resources.designPx(size.widthPx)
        val h = resources.designPx(size.heightPx)
        layoutParams = (layoutParams ?: android.view.ViewGroup.LayoutParams(w, h)).apply {
            width = w
            height = h
        }

        textSize = when (size) {
            SizePreset.W44_H16, SizePreset.W64_H16 -> 10f
            SizePreset.W96_H18 -> 11f
            SizePreset.W62_H26, SizePreset.W63_H26 -> 14f
        }

        val defaultRadiusPx = when (size) {
            SizePreset.W44_H16, SizePreset.W96_H18, SizePreset.W64_H16 -> 24
            SizePreset.W62_H26, SizePreset.W63_H26 -> 37
        }
        val radius = resources.designPx(radiusPx ?: defaultRadiusPx).toFloat()
        this.background = createBackground(background, radius)
    }

    // GRADIENT_BLUE_TEAL（#23B3CD→#1DE8CA 左右渐变）为 OpenAPI-V4 §7 UI 标注的 PLAY 按钮标准配色。
    private fun createBackground(preset: BackgroundPreset, radius: Float): GradientDrawable {
        return when (preset) {
            BackgroundPreset.SOLID_TEAL -> {
                GradientDrawable().apply {
                    setColor(Color.parseColor("#23CCC2"))
                    cornerRadius = radius
                }
            }

            BackgroundPreset.GRADIENT_BLUE_TEAL -> {
                GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.parseColor("#23B3CD"), Color.parseColor("#1DE8CA"))
                ).apply {
                    cornerRadius = radius
                }
            }
        }
    }
}
