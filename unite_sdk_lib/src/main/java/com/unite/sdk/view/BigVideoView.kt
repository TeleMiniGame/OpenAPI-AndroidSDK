package com.unite.sdk.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.unite.sdk.R
import com.unite.sdk.slot.SlotListener
import com.unite.sdk.slot.GameSlot
import com.unite.sdk.game.GameLauncher
import com.unite.sdk.network.SlotApi
import com.unite.sdk.tracking.TrackingReporter
import com.unite.sdk.utils.ClickGuard
import com.unite.sdk.utils.ImageLoader
import com.unite.sdk.utils.ImpressionTracker
import com.unite.sdk.utils.designPx

class BigVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var gameSlot: String? = null
    // 布局里 app:slotId 的值；attach 时若尚未 loadSlot 则自动加载（零代码接入）。
    private var pendingSlotId: String? = attrs?.let {
        val ta = context.obtainStyledAttributes(it, com.unite.sdk.R.styleable.BigVideoView)
        try { ta.getString(com.unite.sdk.R.styleable.BigVideoView_slotId) } finally { ta.recycle() }
    }
    private var slotListener: SlotListener? = null
    private var currentSlot: GameSlot? = null

    private val desiredWidthPx = resources.designPx(327)
    private val coverHeightPx = resources.designPx(219)
    private val bottomHeightPx = resources.designPx(44)
    private val desiredHeightPx = resources.designPx(275)

    // cover area
    private val coverContainer = FrameLayout(context)
    private val coverImageView = ImageView(context)
    private val videoSurfaceView = SurfaceView(context)
    private val badgeView = ImageView(context)
    private val cornerBadge = ImageView(context)

    // ExoPlayer
    private var exoPlayer: ExoPlayer? = null

    // bottom bar
    private val iconView = ImageView(context)
    private val iconCornerBadge = ImageView(context)
    private val titleView = TextView(context)
    private val descView = TextView(context)
    private val playButton = SlotActionButton(context)

    // skeleton
    private val skeletonPlaceholders = mutableListOf<View>()
    private var shimmerAnimator: ValueAnimator? = null
    private lateinit var skeletonView: FrameLayout

    private var videoReady = false
    // 封面区当前露出的是视频画面还是封面图。composited 模式下封面素材已被服务端烤上角标、
    // 而视频画面没有，所以 cornerBadge 的显隐必须跟着这个状态走（见 applyCornerBadge）。
    private var videoShowing = false
    private var shouldPlay = false
    private var savedPosition = 0L
    // 记住当前视频地址，detach 释放播放器后，re-attach 时据此重建续播
    private var videoUrl: String? = null

    // 播放监控相关
    private var playStartTs = 0L  // 播放开始时间戳（毫秒）
    private var playStopTs = 0L   // 播放停止时间戳（毫秒）
    private var isPlayingTracked = false  // 是否正在追踪播放

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
        Log.d("mlog", "bigVideo 曝光 $ids")
    }

    // visibility watcher — drives auto play/pause
    private val visibilityListener = ViewTreeObserver.OnScrollChangedListener { checkVisibility() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { checkVisibility() }

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
            val shadow = Color.parseColor("#0D000000")
            outlineAmbientShadowColor = shadow
            outlineSpotShadowColor = shadow
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        coverContainer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, coverHeightPx
        )
        coverContainer.background = GradientDrawable().apply {
            setColor(Color.parseColor("#ffffff"))
            cornerRadius = resources.designPx(8).toFloat()
        }

        coverImageView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        coverImageView.scaleType = ImageView.ScaleType.CENTER_CROP
        coverImageView.background = GradientDrawable().apply {
            setColor(Color.parseColor("#ffffff"))
            cornerRadius = resources.designPx(8).toFloat()
        }
        coverImageView.clipToOutline = true

        videoSurfaceView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        videoSurfaceView.visibility = GONE
        videoSurfaceView.setZOrderMediaOverlay(true)
        videoSurfaceView.holder.setFormat(android.graphics.PixelFormat.TRANSPARENT)

        // 设置视频圆角
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            videoSurfaceView.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, resources.designPx(8).toFloat())
                }
            }
            videoSurfaceView.clipToOutline = true
        }

        // 初始化 ExoPlayer
        exoPlayer = createExoPlayer()

        badgeView.layoutParams = LayoutParams(
            resources.designPx(44),
            resources.designPx(30)
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = resources.designPx(-5)
            marginStart = resources.designPx(-5)
        }
        badgeView.scaleType = ImageView.ScaleType.FIT_XY
        badgeView.visibility = View.VISIBLE

        cornerBadge.layoutParams = LayoutParams(
            resources.designPx(34),
            resources.designPx(34)
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = 0
            marginEnd = 0

        }
        cornerBadge.setImageResource(R.drawable.ic_badge_m34)
        cornerBadge.elevation = resources.designPx(4).toFloat()

        coverContainer.addView(coverImageView)
        coverContainer.addView(videoSurfaceView)
        // hot/new 分类标（badgeView）暂不上屏：接口尚未下发 status 字段（bindSlot 里为模拟取值），
        // 待字段就绪后在此 addView(badgeView) 启用。
        coverContainer.addView(cornerBadge)

        val pad = resources.designPx(0)
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, bottomHeightPx).apply {
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

        // PLAY 按钮：按 OpenAPI-V4 §7 bigVideo UI 标注为蓝青渐变 #23B3CD→#1DE8CA（非纯色）
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

        setOnClickListener { handleClick() }
        impressionTracker.start()
    }

    fun setSlotListener(listener: SlotListener?) {
        slotListener = listener
    }

    fun loadSlot(gameSlot: String) {
        this.gameSlot = gameSlot
        val slotId = gameSlot
        SlotApi.loadSlots(slotId) { result ->
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
        val bannerUrl = gs.banner
        if (bannerUrl.isEmpty()) {
            tryBindSlot(list, index + 1)
            return
        }
        ImageLoader.loadWithFallback(
            urls = listOf(bannerUrl),
            target = coverImageView,
            onSuccess = {
                bindSlot(gs)
                slotListener?.onSlotLoaded(gs, gameSlot ?: "")
                slotListener?.onSlotShow(gs, gameSlot ?: "")
            },
            onAllFailed = {
                Log.w("mlog", "bigVideo[$index] banner 404，尝试下一条")
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

        ImageLoader.loadWithFallback(listOf(gs.icon, gs.smallIcon), iconView)

        // 底部横条小图标的角标：composited 位里已被服务端合成的 icon/small_icon 不再端上叠，
        // 未合成的仍端上叠：空=内置默认；URL=服务端自定义；"none"=隐藏（兼容保留）。
        when {
            !gs.overlayBadgeOn(gs.icon, gs.smallIcon) -> iconCornerBadge.visibility = View.GONE
            else -> {
                iconCornerBadge.visibility = View.VISIBLE
                ImageLoader.loadBadge(gs.mIcon, iconCornerBadge, R.drawable.ic_badge_m)
            }
        }

        val videoUrl = gs.video
        if (videoUrl.isNotEmpty() && isVideoUrl(videoUrl) && isGoodNetwork()) {
            // 先加载封面图作为视频占位，视频准备好后再切换
            ImageLoader.loadWithFallback(listOf(gs.banner, gs.cover), coverImageView)
            setupVideo(videoUrl)
        } else {
            // 没有视频或网络不好，直接显示封面图
            ImageLoader.loadWithFallback(listOf(gs.banner, gs.cover), coverImageView)
            showImageMode()
        }
    }

    // 封面区角标。cornerBadge 是 coverContainer 里压在封面图和视频画面之上的同一个 ImageView，
    // 而两者的 M 标归属不同：composited 时封面图已由服务端烤上角标（不能再叠），视频画面无法
    // 服务端合成（必须端上叠）。所以每次封面/视频切换都要重算一次，不能只在 bindSlot 定死。
    private fun applyCornerBadge() {
        val gs = currentSlot
        if (gs == null) {
            cornerBadge.visibility = View.GONE
            return
        }
        val needBadge = if (videoShowing) {
            gs.overlayBadgeOnVideo
        } else {
            gs.overlayBadgeOn(gs.banner, gs.cover)
        }
        if (!needBadge) {
            cornerBadge.visibility = View.GONE
            return
        }
        cornerBadge.visibility = View.VISIBLE
        // composited 模式下服务端把官方角标图放在 m_icon 里下发，专供叠视频用；
        // 缺失或加载失败都回退内置 M 标（M 标属合规展示，不能因图挂了就没有）。
        ImageLoader.loadBadge(gs.mIcon, cornerBadge, R.drawable.ic_badge_m34)
    }

    private fun isVideoUrl(url: String): Boolean {
        val videoExtensions = listOf(".mp4", ".m3u8", ".webm", ".mov", ".avi", ".mkv", ".flv", ".3gp")
        val lowerUrl = url.lowercase()
        return videoExtensions.any { lowerUrl.contains(it) }
    }

    private fun createExoPlayer(): ExoPlayer {
        val player = ExoPlayer.Builder(context).build()
        player.setVideoSurfaceView(videoSurfaceView)
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.volume = 0f
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d("mlog", "bigVideo onPlaybackStateChanged: $playbackState")
                when (playbackState) {
                    Player.STATE_READY -> {
                        videoReady = true
                        if (shouldPlay) startVideo()
                        // 淡出封面，露出视频
                        coverImageView.animate().alpha(0f).setDuration(200).withEndAction {
                            coverImageView.visibility = GONE
                        }.start()
                        videoSurfaceView.animate().alpha(1f).setDuration(200).start()
                        // 露出的是视频画面了：composited 位此时才需要把 M 标叠上来
                        videoShowing = true
                        applyCornerBadge()
                    }
                    Player.STATE_ENDED -> {
                        // ExoPlayer 会自动循环，这里不需要处理
                    }
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                Log.d("mlog", "bigVideo onPlayWhenReadyChanged: playWhenReady=$playWhenReady, reason=$reason")
                handlePlayStateChange(playWhenReady && player.playbackState == Player.STATE_READY)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d("mlog", "bigVideo onIsPlayingChanged: $isPlaying")
                handlePlayStateChange(isPlaying)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.w("mlog", "bigVideo 播放失败: ${error.message}，降级到图片")
                showImageMode()
            }
        })
        return player
    }

    private fun setupVideo(url: String) {
        videoUrl = url
        videoReady = false
        // 封面图保持可见，视频在下层准备，ready 后再切换
        coverImageView.visibility = VISIBLE
        videoSurfaceView.visibility = VISIBLE
        videoSurfaceView.alpha = 0f
        // 视频起播前露出的仍是封面图，按封面规则处理角标
        videoShowing = false
        applyCornerBadge()

        // 使用 ExoPlayer 加载视频
        exoPlayer?.apply {
            val mediaItem = MediaItem.fromUri(url)
            setMediaItem(mediaItem)
            prepare()
            if (savedPosition > 0) seekTo(savedPosition)
            if (shouldPlay) {
                play()
            }
        }
    }

    private fun showImageMode() {
        videoSurfaceView.visibility = GONE
        videoSurfaceView.alpha = 1f
        coverImageView.alpha = 1f
        coverImageView.visibility = VISIBLE
        videoReady = false
        // 降级回封面图（无视频/弱网/播放失败）：composited 位的封面自带角标，端上要撤掉
        videoShowing = false
        applyCornerBadge()
    }

    // ── play tracking ────────────────────────────────────────────────────────

    private fun handlePlayStateChange(isPlaying: Boolean) {
        Log.d("mlog", "bigVideo handlePlayStateChange: isPlaying=$isPlaying, isPlayingTracked=$isPlayingTracked")
        if (isPlaying) {
            // 视频开始播放
            if (!isPlayingTracked) {
                playStartTs = System.currentTimeMillis()
                isPlayingTracked = true
                Log.d("mlog", "bigVideo 开始播放追踪，playStartTs=$playStartTs")
            }
        } else {
            // 缓冲卡顿不算「停止」：缓冲时 ExoPlayer 的 isPlaying 也会变 false，但用户意图仍是播放
            // （playWhenReady=true 且 playbackState=BUFFERING）。此时若结算并上报 vplay，则每次卡顿/
            // seek/返回后重缓冲都会多切出一条 duration≈0 的脏 vplay，拉低平均播放时长统计。
            // 缓冲期间保持当前播放会话不结算，等真正暂停/离开时才上报。
            val player = exoPlayer
            if (player != null && player.playWhenReady &&
                player.playbackState == Player.STATE_BUFFERING) {
                Log.d("mlog", "bigVideo 缓冲卡顿（非停止），不结算 vplay")
                return
            }
            // 视频真正停止播放（暂停/滚出可视区/离开页面）
            if (isPlayingTracked) {
                playStopTs = System.currentTimeMillis()
                val playDuration = playStopTs - playStartTs
                Log.d("mlog", "bigVideo 停止播放追踪，playStopTs=$playStopTs, duration=$playDuration")

                // 上报 play 事件
                currentSlot?.let { gs ->
                    TrackingReporter.reportPlay(
                        gs = gs,
                        playStartTs = playStartTs,
                        playStopTs = playStopTs,
                        playDuration = playDuration,
                        slotId = gameSlot
                    )
                }

                // 重置追踪状态
                isPlayingTracked = false
                playStartTs = 0L
                playStopTs = 0L
            }
        }
    }

    // ── auto play / pause ────────────────────────────────────────────────────

    private fun checkVisibility() {
        val ratio = visibleRatio()
        if (ratio >= 0.5f) {
            shouldPlay = true
            if (videoReady) {
                exoPlayer?.let { player ->
                    if (!player.isPlaying) startVideo()
                }
            }
        } else {
            shouldPlay = false
            exoPlayer?.let { player ->
                if (player.isPlaying) pauseVideo()
            }
        }
    }

    private fun startVideo() {
        if (!videoReady) return
        exoPlayer?.play()
    }

    private fun pauseVideo() {
        exoPlayer?.pause()
    }

    private fun visibleRatio(): Float {
        if (!isShown) return 0f
        val w = width; val h = height
        if (w <= 0 || h <= 0) return 0f
        val rect = android.graphics.Rect()
        val ok = getGlobalVisibleRect(rect)
        if (!ok) return 0f
        val visible = rect.width().toFloat() * rect.height().toFloat()
        return (visible / (w.toFloat() * h.toFloat())).coerceIn(0f, 1f)
    }

    // ── network quality ──────────────────────────────────────────────────────

    private fun isGoodNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nc = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            // NET_CAPABILITY_NOT_CONGESTED 是 API 28 才有的能力位，低版本系统永远不会设置它——
            // 不加守卫会让 Android 6-8 蜂窝网络被永判「拥塞」，视频广告在这些设备上从不播放。
            val cellularOk = nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                 nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED))
            return nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                   nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                   cellularOk
        }
        @Suppress("DEPRECATION")
        val info = cm.activeNetworkInfo ?: return false
        @Suppress("DEPRECATION")
        return info.isConnected && info.type == ConnectivityManager.TYPE_WIFI
    }

    // ── click ────────────────────────────────────────────────────────────────

    private fun handleClick() {
        val gs = currentSlot ?: return
        if (!ClickGuard.canClick(gs.id.ifEmpty { gs.title })) return
        slotListener?.onSlotClick(gs, gameSlot ?: "")
        TrackingReporter.reportClick(gs, slotId = gameSlot)
        GameLauncher.openGame(context, gs, slotListener)
    }

    // ── skeleton ─────────────────────────────────────────────────────────────

    private fun buildSkeleton(): FrameLayout {
        val skeleton = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.WHITE)
        }
        val coverPh = View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, coverHeightPx)
            background = GradientDrawable().apply { setColor(Color.parseColor("#E8E8E8")) }
        }
        skeletonPlaceholders.add(coverPh)

        val pad = resources.designPx(10)
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, bottomHeightPx).apply { topMargin = coverHeightPx }
            setPadding(pad, pad, pad, pad)
        }
        val iconPh = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(resources.designPx(40), resources.designPx(40)).apply { marginEnd = resources.designPx(10) }
            background = GradientDrawable().apply { setColor(Color.parseColor("#E8E8E8")); cornerRadius = resources.designPx(8).toFloat() }
        }
        skeletonPlaceholders.add(iconPh)
        val textBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titlePh = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(resources.designPx(120), resources.designPx(14)).apply { bottomMargin = resources.designPx(4) }
            background = GradientDrawable().apply { setColor(Color.parseColor("#E8E8E8")); cornerRadius = resources.designPx(4).toFloat() }
        }
        skeletonPlaceholders.add(titlePh)
        val descPh = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(resources.designPx(80), resources.designPx(12))
            background = GradientDrawable().apply { setColor(Color.parseColor("#E8E8E8")); cornerRadius = resources.designPx(4).toFloat() }
        }
        skeletonPlaceholders.add(descPh)
        val btnPh = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(resources.designPx(63), resources.designPx(26)).apply { gravity = Gravity.CENTER_VERTICAL }
            background = GradientDrawable().apply { setColor(Color.parseColor("#E8E8E8")); cornerRadius = resources.designPx(37).toFloat() }
        }
        skeletonPlaceholders.add(btnPh)
        textBox.addView(titlePh); textBox.addView(descPh)
        bottom.addView(iconPh); bottom.addView(textBox); bottom.addView(btnPh)
        skeleton.addView(coverPh); skeleton.addView(bottom)
        return skeleton
    }

    private fun startShimmer() {
        shimmerAnimator = ValueAnimator.ofArgb(Color.parseColor("#E8E8E8"), Color.parseColor("#F5F5F5")).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val color = anim.animatedValue as Int
                skeletonPlaceholders.forEach { (it.background as? GradientDrawable)?.setColor(color) }
            }
            start()
        }
    }

    private fun hideSkeleton() {
        shimmerAnimator?.cancel(); shimmerAnimator = null
        skeletonView.visibility = GONE
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        Log.d("mlog", "bigVideo 窗口焦点变化: $hasWindowFocus")

        if (!hasWindowFocus) {
            // 失去焦点（跳转到 WebView），保存播放位置并暂停
            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    savedPosition = player.currentPosition
                    // 使用 playWhenReady = false 而不是 pause()，保持缓冲区
                    player.playWhenReady = false
                    Log.d("mlog", "bigVideo 失去焦点，暂停并保存位置: $savedPosition")
                }
            }
        } else {
            // 获得焦点（从 WebView 返回），恢复播放。
            // 失焦时用的是 playWhenReady=false（见上，未 release、缓冲区与播放位置都在），
            // 所以这里**不要再 seek**——seek 会让 ExoPlayer 丢弃已缓冲数据重新拉流，返回后出现
            // 数秒卡顿再起播。只需恢复 playWhenReady 即可从原位置瞬时续播。
            // savedPosition 仅用于「播放器被 onDetachedFromWindow 释放后重建」那条路径（见 onAttachedToWindow）。
            if (videoReady && shouldPlay) {
                exoPlayer?.let { player ->
                    player.playWhenReady = true
                    Log.d("mlog", "bigVideo 已恢复播（不 seek，续用缓冲）")
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pendingSlotId?.let { sid ->
            pendingSlotId = null
            if (gameSlot == null) loadSlot(sid)
        }
        viewTreeObserver.addOnScrollChangedListener(visibilityListener)
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)

        // 切走再切回时，onDetachedFromWindow 已释放播放器；这里自愈重建并续播，
        // 避免透明 SurfaceView 露出白底变空白。第三方无需任何改动。
        val url = videoUrl
        if (url != null && exoPlayer == null) {
            Log.d("mlog", "bigVideo re-attach 重建播放器并续播 url=$url pos=$savedPosition")
            exoPlayer = createExoPlayer()
            // 重建期间先恢复封面可见，视频准备好后再淡出
            coverImageView.alpha = 1f
            coverImageView.visibility = VISIBLE
            setupVideo(url)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try { viewTreeObserver.removeOnScrollChangedListener(visibilityListener) } catch (_: Exception) {}
        try { viewTreeObserver.removeOnGlobalLayoutListener(layoutListener) } catch (_: Exception) {}
        // 保存进度，re-attach 后从此处续播
        exoPlayer?.let { savedPosition = it.currentPosition }
        exoPlayer?.release()
        exoPlayer = null
        videoReady = false
        // 恢复封面可见、隐藏透明视频层，防止下次 attach 前露出白底
        coverImageView.alpha = 1f
        coverImageView.visibility = VISIBLE
        videoSurfaceView.alpha = 0f
        shimmerAnimator?.cancel()
        impressionTracker.stop()
        impressionTracker.reset()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec); val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec); val hSize = MeasureSpec.getSize(heightMeasureSpec)
        val w = when (wMode) { MeasureSpec.EXACTLY -> wSize; MeasureSpec.AT_MOST -> minOf(desiredWidthPx, wSize); else -> desiredWidthPx }
        val h = when (hMode) { MeasureSpec.EXACTLY -> hSize; MeasureSpec.AT_MOST -> minOf(desiredHeightPx, hSize); else -> desiredHeightPx }
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }
}
