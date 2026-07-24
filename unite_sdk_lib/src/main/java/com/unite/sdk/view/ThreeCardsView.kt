package com.unite.sdk.view

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.unite.sdk.slot.SlotListener
import com.unite.sdk.slot.GameSlot
import com.unite.sdk.game.GameLauncher
import com.unite.sdk.network.SlotApi
import com.unite.sdk.tracking.TrackingReporter
import com.unite.sdk.utils.ClickGuard
import com.unite.sdk.utils.ImpressionTracker
import com.unite.sdk.utils.designPx
import com.unite.sdk.view.cards.GameAdCardVertical106_188View

class ThreeCardsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // threeCards 对齐分布式 API 契约：该游戏位只有**一种**规范渲染 = 106×188 竖卡
    // （GameAdCardVertical106_188View，白描边+右下角 M 标，与 OpenAPI-V4 §7 threecard UI 标注
    // 106.33×189.04 逐项对应）。早期遗留的其余 4 个卡型（136/128/103/103218）及 CardStyle
    // 选择机制已于 v1.1.0 删除——API 类型表中 threeCards 不存在多样式，若未来新增卡型需
    // 重新实现并为其补齐 M 标能力（当前只有 106_188 有），否则会漏标。
    private var gameSlot: String? = null
    // 布局里 app:slotId 的值；attach 时若尚未 loadSlot 则自动加载（零代码接入）。
    private var pendingSlotId: String? = attrs?.let {
        val ta = context.obtainStyledAttributes(it, com.unite.sdk.R.styleable.ThreeCardsView)
        try { ta.getString(com.unite.sdk.R.styleable.ThreeCardsView_slotId) } finally { ta.recycle() }
    }
    private var slotListener: SlotListener? = null
    private var slotList: List<GameSlot> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val tagName = "mlog"
    private var reloadIntervalMs: Long = 0
    private val reloadRunnable = Runnable { loadSlotInternal() }
    private var rotateIntervalMs: Long = 0L
    private var currentOffset = 0
    private val rotateRunnable = Runnable { rotateToNext() }
    private var reloadAfterFullCycle = true
    private var cycleCount = 0

    private val scrollView = HorizontalScrollView(context)
    private val container = LinearLayout(context)
    private val currentPagePositions = HashMap<String, Int>()
    private val impressionTracker = ImpressionTracker(
        hostView = scrollView,
        minVisibleRatio = 0.5f,
        minVisibleMs = 800L,
        throttleMs = 200L
    ) { ids ->
        val positions = ids.mapNotNull { id ->
            currentPagePositions[id]?.let { position -> id to position }
        }.toMap()
        val slotIds = gameSlot
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { slot -> ids.associateWith { slot } }
        TrackingReporter.reportImpression(
            contentIds = ids,
            positions = positions.takeIf { it.isNotEmpty() },
            slotIds = slotIds
        )
        Log.d(tagName, "Threes Slot 曝光: $ids")
    }

    init {
        scrollView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        )
        scrollView.isHorizontalScrollBarEnabled = false
        scrollView.overScrollMode = OVER_SCROLL_NEVER
        scrollView.isFillViewport = true

        container.orientation = LinearLayout.HORIZONTAL
        container.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        scrollView.addView(container)
        addView(scrollView)
        impressionTracker.start()
    }

    fun setSlotListener(listener: SlotListener?) {
        slotListener = listener
    }

    fun setReloadIntervalMillis(interval: Long) {
        reloadIntervalMs = interval
    }

    fun setRotateIntervalMillis(interval: Long) {
        rotateIntervalMs = interval
    }

    fun setReloadAfterFullCycle(enabled: Boolean) {
        reloadAfterFullCycle = enabled
    }

    fun setDisplayCount(count: Int) {
        if (count != 3) {
            Log.w(tagName, "ThreeCardsView only supports 3 cards now. Use TwoCardsView for count=2.")
        }
        if (slotList.isNotEmpty()) {
            renderSlots(slotList)
        }
    }

    // 客户唯一的 threeCards 接入方式（与 XML app:slotId 等价）：固定渲染 API 规范卡型。
    fun loadSlot(gameSlot: String) {
        this.gameSlot = gameSlot
        loadSlotInternal()
    }

    fun loadSlot(gameSlot: String, displayCount: Int) {
        if (displayCount != 3) {
            Log.w(tagName, "ThreeCardsView only supports 3 cards now. Use TwoCardsView for count=2.")
        }
        this.gameSlot = gameSlot
        loadSlotInternal()
    }

    private fun loadSlotInternal() {
        val gameSlotId = gameSlot ?: return
        SlotApi.loadSlots(gameSlotId) { result ->
            result.onSuccess { list ->
                slotList = list
                if (slotList.isNotEmpty()) {
                    renderSlots(slotList)
                } else {
                    Log.d(tagName, "no slot available gameSlot=$gameSlotId")
                    slotListener?.onSlotFailed("No slot available", gameSlot ?: "")
                }
                scheduleReload()
            }.onFailure {
                Log.e(tagName, "loadSlots failed gameSlot=$gameSlotId error=${it.message}")
                slotListener?.onSlotFailed(it.message ?: "Load failed", gameSlot ?: "")
                scheduleReload()
            }
        }
    }

    private fun scheduleReload() {
        handler.removeCallbacks(reloadRunnable)
        if (reloadIntervalMs > 0 && gameSlot != null) {
            handler.postDelayed(reloadRunnable, reloadIntervalMs)
        }
    }

    private fun renderSlots(list: List<GameSlot>) {
        currentOffset = 0
        cycleCount = 0
        val preview = list.take(3).joinToString(separator = " | ") { it.title }
        Log.d(tagName, "loadSlots success gameSlot=${gameSlot.orEmpty()} size=${list.size} preview=$preview")
        renderPage()

        for (i in 0 until minOf(3, list.size)) {
            slotListener?.onSlotLoaded(list[i], gameSlot ?: "")
            slotListener?.onSlotShow(list[i], gameSlot ?: "")
        }

        Log.i(tagName, "show ThreeCards")
        scheduleRotate()
    }

    private fun renderPage() {
        impressionTracker.reset()
        container.removeAllViews()
        currentPagePositions.clear()
        val showList = pageSlots()
        // provisional（过期缓存兜底）内容照常渲染但不计曝光；刷新结果回来后会以非 provisional 再渲染一次
        val countExposure = showList.firstOrNull()?.provisional != true
        val gap = resources.designPx(12)

        showList.forEachIndexed { index, gs ->
            fun handleSlotClick() {
                if (!ClickGuard.canClick(gs.id.ifEmpty { gs.title })) return
                slotListener?.onSlotClick(gs, gameSlot ?: "")
                TrackingReporter.reportClick(gs, index + 1, gameSlot)
                Log.d(tagName, "Threes Slot 点击: ${gs.id}")

                GameLauncher.openGame(context, gs, slotListener)
            }

            val card = GameAdCardVertical106_188View(context)
            card.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) marginStart = gap
            }
            card.bind(gs) { handleSlotClick() }
            container.addView(card)
            if (gs.id.isNotBlank()) {
                currentPagePositions[gs.id] = index + 1
            }
            if (countExposure) impressionTracker.track(gs.id, card)
        }
        applyCardWidths()
        if (countExposure) impressionTracker.schedule()
    }

    // 按本视图实际可用宽度，把每张卡片切成确切像素宽度（最后一张吃掉取整误差），
    // 卡片高度由各自的宽高比（AspectRatioFrameLayout）保证。用确切宽度而非 weight，
    // 可避免 HorizontalScrollView 首屏以 weight=0 折叠测量出偏矮的高度而裁掉卡片底部。
    private fun applyCardWidths() {
        val count = container.childCount
        if (count == 0) return
        val available = width - paddingLeft - paddingRight
        if (available <= 0) {
            post { applyCardWidths() }
            return
        }
        val gap = resources.designPx(12)
        val totalGap = gap * (count - 1)
        val each = ((available - totalGap) / count).coerceAtLeast(0)
        var consumed = 0
        for (i in 0 until count) {
            val child = container.getChildAt(i)
            val lp = child.layoutParams as LinearLayout.LayoutParams
            lp.width = if (i == count - 1) (available - totalGap - consumed) else each
            lp.marginStart = if (i > 0) gap else 0
            child.layoutParams = lp
            consumed += each
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) {
            applyCardWidths()
        }
    }

    private fun pageSlots(): List<GameSlot> {
        if (slotList.size <= 3) return slotList
        return (0 until 3).map { slotList[(currentOffset + it) % slotList.size] }
    }

    private fun rotateToNext() {
        if (slotList.size <= 3) return
        val nextOffset = (currentOffset + 3) % slotList.size
        val completedCycle = nextOffset < currentOffset || nextOffset == 0
        currentOffset = nextOffset

        if (completedCycle) {
            cycleCount++
            if (reloadAfterFullCycle) {
                Log.d(tagName, "full cycle completed, reloading slots")
                loadSlotInternal()
                return
            }
        }

        container.animate().alpha(0f).setDuration(60).withEndAction {
            renderPage()
            container.animate().alpha(1f).setDuration(60).start()
        }.start()
        scheduleRotate()
    }

    private fun scheduleRotate() {
        handler.removeCallbacks(rotateRunnable)
        if (rotateIntervalMs > 0 && slotList.size > 3) {
            handler.postDelayed(rotateRunnable, rotateIntervalMs)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pendingSlotId?.let { sid ->
            pendingSlotId = null
            if (gameSlot == null) loadSlot(sid)
        }
        if (gameSlot != null) {
            scheduleReload()
        }
        if (slotList.size > 3) {
            scheduleRotate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(reloadRunnable)
        handler.removeCallbacks(rotateRunnable)
        impressionTracker.stop()
        impressionTracker.reset()
    }
}
