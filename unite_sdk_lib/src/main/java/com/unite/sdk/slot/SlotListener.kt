package com.unite.sdk.slot

/**
 * 插槽回调。所有方法均有默认空实现——宿主只需覆盖关心的回调即可，
 * 不必为了忽略而空实现 6 个方法（配合 XML `app:slotId` 自加载，监听器整体变可选）。
 */
interface SlotListener {
    fun onSlotLoaded(gs: GameSlot, slotId: String) {}
    fun onSlotFailed(message: String, slotId: String) {}
    fun onSlotShow(gs: GameSlot, slotId: String) {}
    fun onSlotClick(gs: GameSlot, slotId: String) {}
    fun onGameStart(gs: GameSlot) {}
    fun onGameClose(gs: GameSlot) {}
}

