package com.unite.sdk.view

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.os.Build
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import com.unite.sdk.R
import com.unite.sdk.utils.ImageLoader
import com.unite.sdk.slot.SlotListener
import com.unite.sdk.slot.GameSlot
import com.unite.sdk.utils.ClickGuard
import com.unite.sdk.game.GameLauncher
import com.unite.sdk.network.SlotApi
import com.unite.sdk.tracking.TrackingReporter
import com.unite.sdk.utils.designPx
import com.unite.sdk.utils.ImpressionTracker

class BigCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class BadgeStyle {
        HOT,
        NEW
    }

    private var gameSlot: String? = null

    // 布局里 app:slotId 的值；attach 时若尚未 loadSlot 则自动加载（零代码接入）。
    private var pendingSlotId: String? = attrs?.let {
        val ta = context.obtainStyledAttributes(it, com.unite.sdk.R.styleable.BigCardView)
        try { ta.getString(com.unite.sdk.R.styleable.BigCardView_slotId) } finally { ta.recycle() }
    }

    private var slotListener: SlotListener? = null
    private var currentSlot: GameSlot? = null

    private val skeletonPlaceholders = mutableListOf<View>()
    private var shimmerAnimator: ValueAnimator? = null
    private lateinit var skeletonView: FrameLayout

    private val coverView = ImageView(context)
    private val badgeView = ImageView(context)
    private val cornerBadge = ImageView(context)
    private val iconView = ImageView(context)
    private val iconCornerBadge = ImageView(context)
    private val titleView = TextView(context)
    private val descView = TextView(context)
    private val playButton = SlotActionButton(context)

    private val desiredWidthPx = resources.designPx(335)
    private val bottomHeightPx = resources.designPx(44)
    private val coverHeightPx = resources.designPx(220)

    private val desiredHeightPx = resources.designPx(275)

    private val impressionTracker = ImpressionTracker(
        hostView = this,
        minVisibleRatio = 0.5f,
        minVisibleMs = 800L,
        throttleMs = 200L
    ) { ids ->
        val slotIds = gameSlot
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { slot -> ids.associateWith { slot } }
        TrackingReporter.reportImpression(ids, slotIds = slotIds)
        Log.d("mlog", "bigCard 曝光${ids}")
    }

    init {
        val radius = resources.designPx(10).toFloat()
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = radius
        }
        clipToOutline = true
        setPadding(
            resources.designPx(4),
            resources.designPx(4),
            resources.designPx(4),
            resources.designPx(4)
        )

        elevation = resources.designPx(8).toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val shadowColor = Color.parseColor("#0D000000")
            outlineAmbientShadowColor = shadowColor
            outlineSpotShadowColor = shadowColor
        }
        

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
        }

        val coverContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                coverHeightPx
            )
        }

        coverView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        coverView.scaleType = ImageView.ScaleType.CENTER_CROP
        coverView.background = GradientDrawable().apply {
            setColor(Color.parseColor("#ffffff"))
            cornerRadius = resources.designPx(8).toFloat()
        }
        coverView.clipToOutline = true

        // badge
        badgeView.layoutParams = LayoutParams(
            resources.designPx(44),
            resources.designPx(30)
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = resources.designPx(-4)
            marginStart = resources.designPx(-4)
        }
        badgeView.scaleType = ImageView.ScaleType.FIT_XY
        badgeView.visibility = View.VISIBLE

        // corner badge
        cornerBadge.layoutParams = LayoutParams(
            resources.designPx(27),
            resources.designPx(27)
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = 0
            marginEnd = 0
        }
        cornerBadge.setImageResource(R.drawable.ic_badge_m27)
        cornerBadge.elevation = resources.designPx(4).toFloat()

        coverContainer.addView(coverView)
        // hot/new 分类标（badgeView）暂不上屏：接口尚未下发 status 字段（bindBadge 里为模拟取值），
        // 待字段就绪后在此 addView(badgeView) 启用。
        coverContainer.addView(cornerBadge)

        val pad = resources.designPx(0)
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                bottomHeightPx
            ).apply {
                topMargin = resources.designPx(4)
            }
           setPadding(pad, pad, pad, pad)
        }

        // Icon container with badge
        val iconContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                resources.designPx(40),
                resources.designPx(40)
            ).apply {
                marginEnd = resources.designPx(10)
            }
        }

        iconView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        iconView.scaleType = ImageView.ScaleType.CENTER_CROP
        iconView.background = GradientDrawable().apply {
            setColor(Color.parseColor("#F2F3F5"))
            cornerRadius = resources.designPx(8).toFloat()
        }
        iconView.clipToOutline = true

        iconCornerBadge.layoutParams = LayoutParams(
            resources.designPx(17),
            resources.designPx(17)
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = 0
            marginEnd = 0
        }
        iconCornerBadge.setImageResource(R.drawable.ic_badge_m)
        iconCornerBadge.elevation = resources.designPx(2).toFloat()

        iconContainer.addView(iconView)
        iconContainer.addView(iconCornerBadge)

        val textBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        titleView.apply {
            textSize = 14f
            setTypeface(null, Typeface.NORMAL)
            setTextColor(Color.parseColor("#000000"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        descView.apply {
            textSize = 12f
            setTextColor(Color.parseColor("#CC000000"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        // PLAY 按钮：按 OpenAPI-V4 §7 bigCard UI 标注为蓝青渐变 #23B3CD→#1DE8CA（非纯色）
        playButton.applyPreset(
            size = SlotActionButton.SizePreset.W63_H26,
            background = SlotActionButton.BackgroundPreset.GRADIENT_BLUE_TEAL,
            radiusPx = 37
        )
        playButton.setOnClickListener { handleClick() }

        textBox.addView(titleView)
        textBox.addView(descView)

        bottom.addView(iconContainer)
        bottom.addView(textBox)
        bottom.addView(playButton)

        container.addView(coverContainer)
        container.addView(bottom)
        addView(container)

        skeletonView = buildSkeleton()
        addView(skeletonView)
        startShimmer()

        setOnClickListener {
            handleClick()
        }
        impressionTracker.start()
    }

    fun setSlotListener(listener: SlotListener?) {
        slotListener = listener
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pendingSlotId?.let { sid ->
            pendingSlotId = null
            if (gameSlot == null) loadSlot(sid)
        }
    }

    fun loadSlot(gameSlot: String) {
        this.gameSlot = gameSlot
        val gameSlotId = this.gameSlot ?: return
        SlotApi.loadSlots(gameSlotId) { result ->
            result.onSuccess { list ->
                if (list.isEmpty()) {
                    hideSkeleton()
                    slotListener?.onSlotFailed("No slot available", gameSlot ?: "")
                    return@onSuccess
                }
                tryBindSlot(list, 0)
            }.onFailure {
                hideSkeleton()
                slotListener?.onSlotFailed(it.message ?: "Load failed", gameSlot ?: "")
            }
        }
    }

    private fun tryBindSlot(list: List<GameSlot>, index: Int) {
        if (index >= list.size) {
            hideSkeleton()
            slotListener?.onSlotFailed("No slot available", gameSlot ?: "")
            return
        }
        val gs = list[index]
        val coverUrl = listOf(gs.banner, gs.bigIcon, gs.cover).firstOrNull { it.isNotEmpty() }
        if (coverUrl == null) {
            Log.w("mlog", "大卡片[$index] 无封面，尝试下一条")
            tryBindSlot(list, index + 1)
            return
        }
        ImageLoader.loadWithFallback(
            urls = listOf(gs.banner, gs.bigIcon, gs.cover),
            target = coverView,
            onSuccess = {
                bindSlot(gs)
                slotListener?.onSlotLoaded(gs, gameSlot ?: "")
                Log.i("mlog", "show Big card index=$index")
                slotListener?.onSlotShow(gs, gameSlot ?: "")
            },
            onAllFailed = {
                Log.w("mlog", "大卡片[$index] 封面全部404，尝试下一条")
                tryBindSlot(list, index + 1)
            }
        )
    }

    private fun bindSlot(gs: GameSlot) {
        hideSkeleton()
        currentSlot = gs
        titleView.text = gs.title
        descView.text = gs.desc
        impressionTracker.reset()
        // provisional（过期缓存兜底）内容照常渲染但不计曝光；刷新结果回来后会以非 provisional 再绑一次
        if (!gs.provisional) {
            impressionTracker.track(gs.id, this)
            impressionTracker.schedule()
        }
        bindBadge(gs)

        val thumUrl = gs.icon
        if (thumUrl.isNotEmpty()) {
            ImageLoader.loadWithFallback(listOf(gs.icon, gs.smallIcon), iconView)
        }

        // M 标：composited 位里服务端已把角标烤进素材的，端上不再叠（否则双重 M 标）；
        // 未合成的素材仍端上叠（空=内置默认；URL=服务端自定义；"none"=隐藏，兼容保留）。
        // 封面与底部小图标各自判定——两者用的素材链不同，合成覆盖情况也可能不同。
        // bigCard 不渲染视频，所以没有「叠到视频上」的分支。
        applyBadge(cornerBadge, gs, gs.overlayBadgeOn(gs.banner, gs.bigIcon, gs.cover), R.drawable.ic_badge_m27)
        applyBadge(iconCornerBadge, gs, gs.overlayBadgeOn(gs.icon, gs.smallIcon), R.drawable.ic_badge_m)
    }

    private fun applyBadge(target: ImageView, gs: GameSlot, needBadge: Boolean, defaultRes: Int) {
        if (!needBadge) {
            target.visibility = View.GONE
            return
        }
        target.visibility = View.VISIBLE
        ImageLoader.loadBadge(gs.mIcon, target, defaultRes)
    }

    private fun bindBadge(gs: GameSlot) {
        // hot/new 分类标：接口暂未下发 status 字段，先以 id 稳定散列模拟取值占位；
        // 待服务端支持后改读 gs.status（可选值 "hot" / "new" / 其他值不显示角标）。
        val simulatedStatus = if (gs.id.hashCode() % 2 == 0) "hot" else "new"

        when (simulatedStatus) {
            "hot" -> {
                badgeView.setImageResource(R.drawable.ic_badge_hot)
                badgeView.visibility = View.VISIBLE
            }
            "new" -> {
                badgeView.setImageResource(R.drawable.ic_badge_new)
                badgeView.visibility = View.VISIBLE
            }
            else -> {
                badgeView.visibility = View.GONE
            }
        }
    }

    private fun handleClick() {
        val gs = currentSlot ?: return
        if (!ClickGuard.canClick(gs.id.ifEmpty { gs.title })) return
        slotListener?.onSlotClick(gs, gameSlot ?: "")
        TrackingReporter.reportClick(gs, slotId = gameSlot)
        GameLauncher.openGame(context, gs, slotListener)
    }

    private fun buildSkeleton(): FrameLayout {
        val skeleton = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.WHITE)
        }

        val coverPlaceholder = View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, coverHeightPx)
            background = GradientDrawable().apply { setColor(Color.parseColor("#E8E8E8")) }
        }
        skeletonPlaceholders.add(coverPlaceholder)

        val pad = resources.designPx(10)
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, bottomHeightPx).apply {
                topMargin = coverHeightPx
            }
            setPadding(pad, pad, pad, pad)
        }

        val iconPlaceholder = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(resources.designPx(40), resources.designPx(40)).apply {
                marginEnd = resources.designPx(10)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E8E8E8"))
                cornerRadius = resources.designPx(8).toFloat()
            }
        }
        skeletonPlaceholders.add(iconPlaceholder)

        val textBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titlePlaceholder = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(resources.designPx(120), resources.designPx(14)).apply {
                bottomMargin = resources.designPx(4)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E8E8E8"))
                cornerRadius = resources.designPx(4).toFloat()
            }
        }
        skeletonPlaceholders.add(titlePlaceholder)

        val descPlaceholder = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(resources.designPx(80), resources.designPx(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E8E8E8"))
                cornerRadius = resources.designPx(4).toFloat()
            }
        }
        skeletonPlaceholders.add(descPlaceholder)

        val buttonPlaceholder = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(resources.designPx(63), resources.designPx(26)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E8E8E8"))
                cornerRadius = resources.designPx(37).toFloat()
            }
        }
        skeletonPlaceholders.add(buttonPlaceholder)

        textBox.addView(titlePlaceholder)
        textBox.addView(descPlaceholder)
        bottom.addView(iconPlaceholder)
        bottom.addView(textBox)
        bottom.addView(buttonPlaceholder)
        skeleton.addView(coverPlaceholder)
        skeleton.addView(bottom)
        return skeleton
    }

    private fun startShimmer() {
        shimmerAnimator = ValueAnimator.ofArgb(
            Color.parseColor("#E8E8E8"),
            Color.parseColor("#F5F5F5")
        ).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                skeletonPlaceholders.forEach { view ->
                    (view.background as? GradientDrawable)?.setColor(color)
                }
            }
            start()
        }
    }

    private fun hideSkeleton() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        skeletonView.visibility = View.GONE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        shimmerAnimator?.cancel()
        impressionTracker.stop()
        impressionTracker.reset()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val w = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> minOf(desiredWidthPx, widthSize)
            else -> desiredWidthPx
        }
        val h = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> minOf(desiredHeightPx, heightSize)
            else -> desiredHeightPx
        }

        val wSpec = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val hSpec = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        super.onMeasure(wSpec, hSpec)
    }
}
