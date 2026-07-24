package com.unite.sdk.view.cards

import com.unite.sdk.view.cards.GameAdCardVertical106_188View.Companion.BOTTOM_BAR_HEIGHT_DP
import com.unite.sdk.view.cards.GameAdCardVertical106_188View.Companion.CARD_WIDTH_DP
import com.unite.sdk.view.cards.GameAdCardVertical106_188View.Companion.SERVER_BADGE_WIDTH_RATIO_FLASH
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守护 [GameAdCardVertical106_188View] 「恒定端上叠 M 标、不看 m_icon_mode」的成立前提。
 *
 * 该卡型之所以可以无视 `m_icon_mode` 直接叠加而不会出现双重 M 标，唯一依据是：
 * **服务端烤进主图的那个角标会被卡片底部的标题条完全遮住**。
 *
 * 这是个隐式的几何依赖——若哪天标题条改矮、改透明或取消，服务端那个角标就会露出来，
 * 与 SDK 自绘角标形成双重 M 标，而且不会有任何编译或运行期报错。
 *
 * 所以把前提固化在这里：一旦有人动了卡片宽度或标题条高度，本测试立即失败并给出处置指引。
 *
 * 注：引用的都是 `const val`，编译期即被内联，不会真正加载 Android View 类。
 */
class MIconOcclusionInvariantTest {

    @Test
    fun `server composited badge stays fully hidden behind the bottom bar`() {
        // 主图 flash（720×1280）与本卡（106×188）近似同比，centerCrop 后整图可见，
        // 故角标在卡片上的边长 ≈ 卡片宽度 × 角标占图宽的比例。
        val badgeSizeOnCardDp = CARD_WIDTH_DP * SERVER_BADGE_WIDTH_RATIO_FLASH

        assertTrue(
            """
            竖卡的 M 标遮挡前提已被破坏：服务端合成的角标在卡片上约 ${"%.1f".format(badgeSizeOnCardDp)}dp，
            而底部标题条只有 ${BOTTOM_BAR_HEIGHT_DP}dp，已盖不住它。
            此时 GameAdCardVertical106_188View 继续无条件叠加 SDK 角标会出现【双重 M 标】。

            处置方式二选一：
              1) 恢复标题条高度，使其 >= 角标尺寸；或
              2) 把该卡的角标判定改回 gs.overlayBadgeOn(gs.flash, gs.banner, gs.image)
                 —— 即已合成就不叠。注意此时未合成素材仍会正常补叠，不会漏标。
            详见该 View 的 bind 方法内的注释。
            """.trimIndent(),
            badgeSizeOnCardDp <= BOTTOM_BAR_HEIGHT_DP
        )
    }
}
