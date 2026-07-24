package com.unite.sdk.slot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M 标 icon 服务端合成适配（m_icon_mode）的判定矩阵：
 * 覆盖模式归一化、逐素材 URL 合成特征判定、视频恒端上叠、"none" 隐藏等全部分支。
 */
class MIconModeTest {

    private val composited = "https://res.minigame.vip/a_banner.png" +
        "?x-oss-process=image/watermark,image_MC9tZ19jb3JuZXIucG5n,g_se,x_0,y_0"
    private val plain = "https://res.minigame.vip/a_banner.png"
    private val badge = "https://res.minigame.vip/gc-assets/0/mg_corner.png"

    private fun slot(
        mode: String = GameSlot.M_ICON_MODE_OVERLAY,
        mIcon: String? = badge,
        banner: String? = plain,
        video: String? = null,
    ) = GameSlot(
        id = "g1",
        detail = GameDetail(
            name = "g", catalog = null, categories = null, howToPlay = null, description = null,
            icon = null, bigIcon = null, smallIcon = null, banner = banner, badgeIcon = null,
            mIcon = mIcon, flash = null, video = video, orientation = null, createdAt = null
        ),
        link = "https://example.com",
        mIconMode = mode,
    )

    // ── 模式归一化：只认 composited，其余一律 overlay（向后兼容） ──────────────

    @Test
    fun `mode normalization only accepts composited`() {
        assertEquals(GameSlot.M_ICON_MODE_COMPOSITED, GameSlot.normalizeMIconMode("composited"))
        assertEquals(GameSlot.M_ICON_MODE_COMPOSITED, GameSlot.normalizeMIconMode(" COMPOSITED "))
        assertEquals(GameSlot.M_ICON_MODE_OVERLAY, GameSlot.normalizeMIconMode("overlay"))
        // 缺省 / 空 / 未知值 → overlay，维持 1.1.0 旧行为，避免漏叠
        assertEquals(GameSlot.M_ICON_MODE_OVERLAY, GameSlot.normalizeMIconMode(null))
        assertEquals(GameSlot.M_ICON_MODE_OVERLAY, GameSlot.normalizeMIconMode(""))
        assertEquals(GameSlot.M_ICON_MODE_OVERLAY, GameSlot.normalizeMIconMode("burned_in"))
    }

    @Test
    fun `absent m_icon_mode keeps legacy overlay behaviour`() {
        val gs = slot(mode = GameSlot.M_ICON_MODE_OVERLAY, banner = plain)
        assertTrue(gs.overlayBadgeOn(gs.banner))
        assertFalse(gs.mIconComposited)
    }

    // ── composited：已合成素材不叠，未合成素材仍叠 ────────────────────────────

    @Test
    fun `composited asset is not overlaid`() {
        val gs = slot(mode = GameSlot.M_ICON_MODE_COMPOSITED, banner = composited)
        assertFalse("已含烤入角标的素材再叠 = 双重 M 标", gs.overlayBadgeOn(gs.banner))
    }

    @Test
    fun `uncomposited asset in composited slot is still overlaid`() {
        // DEV 实测：composited 位里约 1/3 的游戏存在未合成素材，整位一刀切会导致漏标
        val gs = slot(mode = GameSlot.M_ICON_MODE_COMPOSITED, banner = plain)
        assertTrue("未合成的素材必须端上补叠，否则完全没有 M 标", gs.overlayBadgeOn(gs.banner))
    }

    @Test
    fun `fallback chain is judged by the asset actually used`() {
        val gs = slot(mode = GameSlot.M_ICON_MODE_COMPOSITED)
        // 首选已合成 → 不叠；首选为空时回退到未合成的那张 → 要叠
        assertFalse(gs.overlayBadgeOn(composited, plain))
        assertTrue(gs.overlayBadgeOn("", plain))
    }

    // ── video：两种模式都要端上叠（mp4 无法服务端合成） ────────────────────────

    @Test
    fun `video is always overlaid regardless of mode`() {
        assertTrue(slot(mode = GameSlot.M_ICON_MODE_COMPOSITED, video = "a.mp4").overlayBadgeOnVideo)
        assertTrue(slot(mode = GameSlot.M_ICON_MODE_OVERLAY, video = "a.mp4").overlayBadgeOnVideo)
    }

    // ── "none" 优先级最高：任何模式下都不叠 ──────────────────────────────────

    @Test
    fun `none hides badge in both modes and on video`() {
        val c = slot(mode = GameSlot.M_ICON_MODE_COMPOSITED, mIcon = GameSlot.M_ICON_HIDE, banner = plain)
        val o = slot(mode = GameSlot.M_ICON_MODE_OVERLAY, mIcon = "NONE", banner = plain)
        assertFalse(c.overlayBadgeOn(c.banner))
        assertFalse(c.overlayBadgeOnVideo)
        assertFalse(o.overlayBadgeOn(o.banner))
        assertFalse(o.overlayBadgeOnVideo)
    }

    // ── 竖卡（106_188）例外：不看模式，恒叠 ──────────────────────────────────

    @Test
    fun `vertical card overlays regardless of compositing but still honours none`() {
        // GameAdCardVertical106_188View 刻意不调用 overlayBadgeOn：服务端烤进主图的角标
        // 在该卡型里必然被 44dp 底部条盖住（flash 竖图）或被 centerCrop 裁掉（banner 横图），
        // 只有 "none" 该隐藏。这里锁住"'none' 仍然生效"这一点，其余由该 View 的注释约束。
        assertFalse(slot(mode = GameSlot.M_ICON_MODE_COMPOSITED, mIcon = GameSlot.M_ICON_HIDE).mIconHidden.not())
        assertFalse(slot(mode = GameSlot.M_ICON_MODE_COMPOSITED, mIcon = badge).mIconHidden)
    }

    // ── URL 原样保留：带 x-oss-process 的 URL 不能被截断/改写 ─────────────────

    @Test
    fun `composited url survives normalization intact`() {
        val gs = slot(mode = GameSlot.M_ICON_MODE_COMPOSITED, banner = composited)
        assertEquals("素材 URL 的 x-oss-process 参数被改写会拿到无角标原图", composited, gs.banner)
    }

    // ── 解析：data 级 m_icon_mode 要落到每个 item ─────────────────────────────

    @Test
    fun `data level mode propagates to every slot`() {
        val item = GameItem(id = "g1", link = "https://example.com", detail = GameDetail(
            name = "g", catalog = null, categories = null, howToPlay = null, description = null,
            icon = null, bigIcon = null, smallIcon = null, banner = composited, badgeIcon = null,
            mIcon = badge, flash = null, video = null, orientation = null, createdAt = null
        ))
        val gs = item.toGameSlot(GameSlot.normalizeMIconMode("composited"))!!
        assertTrue(gs.mIconComposited)
        // 不传（老调用点/大厅位合成的 GameSlot）默认 overlay
        assertFalse(item.toGameSlot()!!.mIconComposited)
    }
}
