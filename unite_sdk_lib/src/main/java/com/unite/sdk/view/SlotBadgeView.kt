package com.unite.sdk.view

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.unite.sdk.utils.ImageLoader
import com.unite.sdk.utils.designPx

class SlotBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    sealed interface Config {
        data class Text(val badge: TextBadge) : Config
        data class Image(val badge: ImageBadge) : Config
    }

    enum class Preset {
        HOT,
        NEW
    }

    data class TextBadge(
        val text: String,
        val backgroundColor: Int,
        val textColor: Int = Color.WHITE,
        val cornerRadiusDesignPx: Int = 8,
        val paddingHorizontalDesignPx: Int = 8,
        val paddingVerticalDesignPx: Int = 3,
        val textSizeSp: Float = 10f
    )

    data class ImageBadge(
        val data: Any,
        val widthDesignPx: Int,
        val heightDesignPx: Int
    )

    private val imageView = ImageView(context)
    private val textView = TextView(context)

    init {
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

        imageView.visibility = View.GONE
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(
            imageView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        )

        textView.visibility = View.GONE
        textView.gravity = Gravity.CENTER
        textView.maxLines = 1
        addView(
            textView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        )

        visibility = View.GONE
    }

    fun hide() {
        visibility = View.GONE
        imageView.visibility = View.GONE
        textView.visibility = View.GONE
    }

    fun setConfig(config: Config?) {
        when (config) {
            null -> hide()
            is Config.Text -> setTextBadge(config.badge)
            is Config.Image -> setImageBadge(config.badge)
        }
    }

    fun setPreset(preset: Preset) {
        when (preset) {
            Preset.HOT -> setTextBadge(TextBadge(text = "Hot", backgroundColor = Color.parseColor("#FFB74D")))
            Preset.NEW -> setTextBadge(TextBadge(text = "New", backgroundColor = Color.parseColor("#35C27A")))
        }
    }

    fun setTextBadge(badge: TextBadge) {
        val t = badge.text.trim()
        if (t.isEmpty()) {
            hide()
            return
        }

        visibility = View.VISIBLE
        imageView.visibility = View.GONE
        textView.visibility = View.VISIBLE

        textView.text = t
        textView.setTextColor(badge.textColor)
        textView.textSize = badge.textSizeSp
        textView.setPadding(
            resources.designPx(badge.paddingHorizontalDesignPx),
            resources.designPx(badge.paddingVerticalDesignPx),
            resources.designPx(badge.paddingHorizontalDesignPx),
            resources.designPx(badge.paddingVerticalDesignPx)
        )
        textView.background = GradientDrawable().apply {
            setColor(badge.backgroundColor)
            cornerRadius = resources.designPx(badge.cornerRadiusDesignPx).toFloat()
        }
    }

    fun setImageBadge(badge: ImageBadge) {
        visibility = View.VISIBLE
        textView.visibility = View.GONE
        imageView.visibility = View.VISIBLE

        val w = resources.designPx(badge.widthDesignPx)
        val h = resources.designPx(badge.heightDesignPx)
        imageView.layoutParams = (imageView.layoutParams as LayoutParams).apply {
            width = w
            height = h
        }
        ImageLoader.loadAny(badge.data, imageView, w, h)
    }
}

