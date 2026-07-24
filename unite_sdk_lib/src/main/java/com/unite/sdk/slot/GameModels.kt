package com.unite.sdk.slot

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class GameListApiResponse(
    @SerializedName("code") val code: Int?,
    @SerializedName("message") val message: String?,
    @SerializedName("data") val data: GameListData?,
    @SerializedName("ts") val ts: Long?
)

data class GameListData(
    @SerializedName("total") val total: Int?,
    @SerializedName("request_id") val requestId: String?,
    @SerializedName("items") val items: List<GameItem>?,
    // 大厅游戏位（slot_type=gameCenter）下发的游戏中心域名。大厅位：hall_url 非空且 items 为空；
    // 普通位：服务端 EmitUnpopulated=true 也会带空串 —— 判定只能用「非空 ⇒ 大厅位」，不能看字段是否存在。
    @SerializedName("hall_url") val hallUrl: String? = null,
    // M 标接入模式（data 级，对整个游戏位生效）。composited = 服务端已用 OSS 把角标烤进图片素材，
    // 端上不得再叠（否则双重 M 标）；overlay/缺省 = 图片素材不含角标，维持端上叠加。
    @SerializedName(value = "m_icon_mode", alternate = ["mIconMode"]) val mIconMode: String? = null,
)

data class GameItem(
    @SerializedName("id") val id: String?,
    @SerializedName("link") val link: String?,
    @SerializedName("detail") val detail: GameDetail?
)

data class GameDetail(
    @SerializedName("name") val name: String?,
    @SerializedName("catalog") val catalog: JsonElement?,
    @SerializedName("categories") val categories: List<String>?,
    @SerializedName(value = "howToPlay", alternate = ["how_to_play"]) val howToPlay: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("icon") val icon: String?,
    @SerializedName(value = "bigIcon", alternate = ["big_icon"]) val bigIcon: String?,
    @SerializedName(value = "smallIcon", alternate = ["small_icon"]) val smallIcon: String?,
    @SerializedName("banner") val banner: String?,
    @SerializedName(value = "badgeIcon", alternate = ["badge_icon", "badge_icon_url"]) val badgeIcon: String?,
    // M 标角标图 URL（与图片素材分开、由接口单独下发，端上叠加在素材图右下角）。
    // 服务端未下发或加载失败时回退 SDK 内置 M 标（见 ImageLoader.loadBadge）。
    @SerializedName(value = "mIcon", alternate = ["m_icon", "m_icon_url"]) val mIcon: String? = null,
    @SerializedName("flash") val flash: String?,
    @SerializedName("video") val video: String?,
    @SerializedName("orientation") val orientation: String?,
    @SerializedName("createdAt") val createdAt: String?
)

data class GameSlot(
    val id: String,
    val detail: GameDetail,
    val link: String?,
    // 来自响应 data.m_icon_mode，解析时逐 item 复制进来（视图层只拿得到 GameSlot）。
    // 缺省值给 overlay：老服务端不下发该字段时行为与 1.1.0 完全一致（向后兼容）。
    val mIconMode: String = M_ICON_MODE_OVERLAY,
    // 「过期缓存兜底」标记：命中过期缓存时先渲染给用户看的那批数据带此标记。视图应照常渲染，
    // 但**不得对其计曝光**——它随时会被后台刷新结果替换，若计曝光会上报「本次请求根本没下发」
    // 的旧游戏，污染 CTR/归因。后台刷新成功后视图会拿到 provisional=false 的新数据再计曝光；
    // 刷新失败则回落为把这批缓存数据以 provisional=false 重新下发（此时才计曝光）。见 AdApi。
    val provisional: Boolean = false,
) {
    companion object {
        // m_icon 取值约定：空/缺省 = 显示 SDK 内置默认 M 标；URL = 加载服务端下发的自定义 M 标；
        // 字面量 "none"（不区分大小写）= 该素材不叠加 M 标。服务端当前不会下发 "none"，
        // 此分支为历史契约的兼容保留（集中在 overlayBadgeOn 一处，便于将来恢复该能力）。
        const val M_ICON_HIDE = "none"

        // m_icon_mode 两态：服务端 OSS 已合成 / 端上叠加。未知值与缺省一律按 overlay 处理。
        const val M_ICON_MODE_COMPOSITED = "composited"
        const val M_ICON_MODE_OVERLAY = "overlay"

        // 已合成素材 URL 上的 OSS 图片处理参数特征（形如
        // ?x-oss-process=image/watermark,image_xxx,g_se,x_0,y_0）。
        // 服务端承诺：已合成素材的 URL 必带此参数（合成方式变更会提前通知），是逐素材判定的依据。
        const val OSS_WATERMARK_MARKER = "x-oss-process=image/watermark"

        // 把服务端下发的 m_icon_mode 归一化：只认 "composited"，其余（含 null/空/未知值）→ overlay。
        // 向后兼容优先——宁可多叠一层（旧行为）也不要漏叠导致素材完全没有 M 标。
        fun normalizeMIconMode(raw: String?): String =
            if (raw?.trim().equals(M_ICON_MODE_COMPOSITED, ignoreCase = true)) {
                M_ICON_MODE_COMPOSITED
            } else {
                M_ICON_MODE_OVERLAY
            }

        // 游戏中心曝光/点击上报用的固定 content_id（服务端已约定；上报接口强校验 content_ids 非空，
        // 而游戏中心按钮不是游戏，用哨兵值与真实游戏 CTR 天然区分）。
        const val GAME_CENTER_CONTENT_ID = "game_center"

        // 大厅位没有游戏 item；为游戏中心入口合成一个 GameSlot（link=hall_url），
        // 使其统一复用 SlotListener 回调 / GameLauncher 打开 / TrackingReporter 上报链路。
        fun gameCenter(hallUrl: String): GameSlot =
            GameSlot(id = GAME_CENTER_CONTENT_ID, detail = EMPTY_DETAIL, link = hallUrl)
    }

    val title: String
        get() = detail.name.orEmpty()

    val desc: String
        get() = (detail.description ?: detail.howToPlay).orEmpty()

    val clickUrl: String
        get() = link.normalizeUrl()

    val icon: String
        get() = detail.icon.normalizeUrl()

    val bigIcon: String
        get() = detail.bigIcon.normalizeUrl()

    val smallIcon: String
        get() = detail.smallIcon.normalizeUrl()

    val flash: String
        get() = detail.flash.normalizeUrl()

    val banner: String
        get() = detail.banner.normalizeUrl()

    val badgeIcon: String
        get() = detail.badgeIcon.normalizeUrl()

    val mIcon: String
        get() = detail.mIcon.normalizeUrl()

    val mIconHidden: Boolean
        get() = mIcon.equals(M_ICON_HIDE, ignoreCase = true)

    // 服务端已用 OSS 把 M 标合成进图片素材（banner/big_icon/flash/icon/small_icon）。
    val mIconComposited: Boolean
        get() = mIconMode.equals(M_ICON_MODE_COMPOSITED, ignoreCase = true)

    /**
     * 某个图片素材是否还需要端上叠加 M 标。传入该处渲染实际使用的素材回退链（顺序与
     * ImageLoader.loadWithFallback 一致），取首个非空 URL 判定。
     *
     * composited 模式下**不是**整位一刀切不叠，而是逐素材看 URL 上有没有 OSS 合成参数，原因有二：
     *  1. 合成是按「游戏 × 素材字段」做的，同一个 composited 位里可能存在个别未合成素材
     *     （如素材域名不在服务端合成白名单内）；整位不叠会让这些素材彻底没有 M 标（漏标）。
     *  2. 视图取图走回退链（如 banner→big_icon→icon），首选素材合成了、回退到的那张未必合成，
     *     只有按实际使用的 URL 判定才不会判错。
     * 服务端把所有素材补齐合成后，本判定与「整位不叠」结果完全一致，属于安全的超集。
     */
    fun overlayBadgeOn(vararg assetChain: String): Boolean {
        if (mIconHidden) return false
        if (!mIconComposited) return true
        val shown = assetChain.firstOrNull { it.isNotEmpty() }.orEmpty()
        return !shown.contains(OSS_WATERMARK_MARKER, ignoreCase = true)
    }

    // 视频画面是否需要端上叠加 M 标。mp4 无法做服务端图片合成，所以两种模式下都要端上叠。
    val overlayBadgeOnVideo: Boolean
        get() = !mIconHidden

    val cover: String
        get() = (detail.banner ?: detail.bigIcon ?: detail.icon).normalizeUrl()

    val image: String
        get() = (detail.icon ?: detail.smallIcon).normalizeUrl()

    val thumbnailUrl: String
        get() = (detail.smallIcon ?: detail.icon).normalizeUrl()

    val video: String
        get() = detail.video.normalizeUrl()

}

private val EMPTY_DETAIL = GameDetail(
    name = null, catalog = null, categories = null, howToPlay = null, description = null,
    icon = null, bigIcon = null, smallIcon = null, banner = null, badgeIcon = null,
    mIcon = null, flash = null, video = null, orientation = null, createdAt = null
)

fun GameItem.toGameSlot(mIconMode: String = GameSlot.M_ICON_MODE_OVERLAY): GameSlot? {
    val d = detail ?: return null
    val click = link.normalizeUrl()
    if (click.isEmpty()) return null
    return GameSlot(id = id.orEmpty(), detail = d, link = link, mIconMode = mIconMode)
}

private fun String?.normalizeUrl(): String {
    if (this.isNullOrBlank()) return ""
    var s = this.trim()
    s = s.trim('`').trim()
    s = s.removePrefix("`").removeSuffix("`").trim()
    s = s.removeSurrounding("\"").trim()
    s = s.trim('`').trim()
    return s
}
