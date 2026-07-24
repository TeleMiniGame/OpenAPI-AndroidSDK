package com.unite.sdk.view

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.unite.sdk.game.GameLauncher
import com.unite.sdk.game.OpenGameCenterUrl
import com.unite.sdk.network.SlotApi
import com.unite.sdk.slot.GameSlot
import com.unite.sdk.slot.SlotListener
import com.unite.sdk.tracking.TrackingReporter
import com.unite.sdk.utils.ClickGuard
import com.unite.sdk.utils.ImageLoader
import com.unite.sdk.utils.ImpressionTracker

class GameCenterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val image = ImageView(context)
    private val titleView = TextView(context)
    private var targetUrl: String = ""

    // ── 大厅游戏位（type=hall）模式 ──────────────────────────────────────────
    // 接口只下发动态域名（hall_url），无单机游戏与素材；曝光/点击直接按该游戏位上报。
    private var gameSlot: String? = null
    private var currentSlot: GameSlot? = null
    private var slotListener: SlotListener? = null

    // 布局里 app:slotId 的值；attach 时若尚未 loadSlot 则自动加载（零代码接入）。
    private var pendingSlotId: String? = attrs?.let {
        val ta = context.obtainStyledAttributes(it, com.unite.sdk.R.styleable.GameCenterView)
        try { ta.getString(com.unite.sdk.R.styleable.GameCenterView_slotId) } finally { ta.recycle() }
    }

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
        Log.d("mlog", "gameCenter 曝光$ids")
    }

    init {
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        layoutParams = lp

        image.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        image.adjustViewBounds = true
        addView(image)

        val tvParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        tvParams.marginStart = (16 * resources.displayMetrics.density).toInt()
        tvParams.topMargin = (8 * resources.displayMetrics.density).toInt()
        titleView.layoutParams = tvParams
        titleView.textSize = 16f
        addView(titleView)

        setOnClickListener { handleClick() }
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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        impressionTracker.stop()
        impressionTracker.reset()
    }

    /**
     * 按「大厅游戏位」（slot_type=gameCenter）加载：请求大厅 slot_id，取响应 data.hall_url
     * 作为游戏中心入口地址（动态获取，禁止硬编码域名）；曝光/点击以 content_ids="game_center"
     * + slot_id=大厅位 上报。大厅 slot_id 由运营分配，宿主经 app:slotId 或本方法传入，勿写死。
     * 入口的图片/标题素材仍由宿主通过 setConfig 提供（大厅位不下发素材）。
     */
    fun loadSlot(gameSlot: String) {
        this.gameSlot = gameSlot
        SlotApi.loadHallUrl(gameSlot) { result ->
            result.onSuccess { hallUrl ->
                // 契约：hall_url 非空 ⇒ 大厅位；空串 = 该 slot 不是大厅位（普通位因 EmitUnpopulated 也带空串）
                if (hallUrl.isEmpty()) {
                    slotListener?.onSlotFailed("Not a hall slot (empty hall_url)", gameSlot)
                    return@onSuccess
                }
                val gs = GameSlot.gameCenter(hallUrl)
                currentSlot = gs
                targetUrl = hallUrl
                impressionTracker.reset()
                impressionTracker.track(GameSlot.GAME_CENTER_CONTENT_ID, this)
                impressionTracker.schedule()
                slotListener?.onSlotLoaded(gs, gameSlot)
                slotListener?.onSlotShow(gs, gameSlot)
                Log.i("mlog", "gameCenter 大厅位加载成功 hall_url=$hallUrl")
            }.onFailure {
                slotListener?.onSlotFailed(it.message ?: "Load failed", gameSlot)
            }
        }
    }

    private fun handleClick() {
        if (targetUrl.isEmpty()) return
        val gs = currentSlot
        if (gs != null) {
            // 大厅位：点击按游戏位上报，回调走 SlotListener，容器复用 WebActivity
            if (!ClickGuard.canClick(gs.id.ifEmpty { gameSlot ?: "game_center" })) return
            slotListener?.onSlotClick(gs, gameSlot ?: "")
            TrackingReporter.reportClick(gs, slotId = gameSlot)
            GameLauncher.openGame(context, gs, slotListener)
        } else {
            // 旧接入路径：setConfig 手配 URL
            OpenGameCenterUrl.open(context, targetUrl)
            TrackingReporter.reportClick("game_center")
        }
    }

    @JvmOverloads
    fun setConfig(
        url: String,
        imageUrl: String?,
        title: String?,
        imageWidthDp: Int? = null,
        imageHeightDp: Int? = null
    ) {
        setConfigInternal(url, title, imageUrl, imageWidthDp, imageHeightDp)
    }

    @JvmOverloads
    fun setConfig(
        url: String,
        imageResId: Int,
        title: String?,
        imageWidthDp: Int? = null,
        imageHeightDp: Int? = null
    ) {
        setConfigInternal(url, title, imageResId, imageWidthDp, imageHeightDp)
    }

    private fun setConfigInternal(
        url: String,
        title: String?,
        imageData: Any?,
        imageWidthDp: Int?,
        imageHeightDp: Int?
    ) {
        // 大厅位已加载时，跳转目标以接口下发的动态域名为准，setConfig 只更新素材
        if (currentSlot == null && url.isNotBlank()) {
            targetUrl = url
        }

        if (title.isNullOrEmpty()) {
            titleView.visibility = View.GONE
        } else {
            titleView.visibility = View.VISIBLE
            titleView.text = title
        }

        if (imageData == null || (imageData is String && imageData.isEmpty())) {
            image.visibility = View.GONE
            return
        }

        image.visibility = View.VISIBLE
        applyImageSize(imageWidthDp, imageHeightDp)
        ImageLoader.loadAny(imageData, image, null, null)
    }

    private fun applyImageSize(imageWidthDp: Int?, imageHeightDp: Int?) {
        val lp = image.layoutParams as LayoutParams
        if (imageWidthDp != null) lp.width = dp(imageWidthDp)
        if (imageHeightDp != null) lp.height = dp(imageHeightDp)
        image.layoutParams = lp
    }

    private fun dp(value: Int): Int {
        return (resources.displayMetrics.density * value).toInt()
    }
}
