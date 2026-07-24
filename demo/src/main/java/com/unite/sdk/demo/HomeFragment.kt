package com.unite.sdk.demo

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.unite.sdk.GameSlotSdk
import com.unite.sdk.slot.GameSlot
import com.unite.sdk.slot.SlotListener
import com.unite.sdk.view.BigCardView
import com.unite.sdk.view.BigVideoView
import com.unite.sdk.view.ColCardView
import com.unite.sdk.view.RandomCardsView
import com.unite.sdk.view.ThreeCardsView
import com.unite.sdk.view.TwoCardsView

class HomeFragment : Fragment(), SlotListener {
    private lateinit var threeCardsViewVerBorder: ThreeCardsView
    private lateinit var threeCardsViewLargeVer: ThreeCardsView
    private lateinit var twoCardsViewRect: TwoCardsView
    private lateinit var twoVideoView: TwoCardsView
    private lateinit var bigVideo: BigVideoView
    private lateinit var bigCardView: BigCardView
    private lateinit var randomView: RandomCardsView
    private lateinit var rowCardView: ColCardView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var languageSpinner: Spinner

    // 显示label -> 上报用的 language code
    private val languages = listOf(
        "中文 zh" to "zh",
        "English en" to "en",
        "Tiếng Việt vi" to "vi"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        threeCardsViewVerBorder = view.findViewById(R.id.threeCardViewVerBorder)
        threeCardsViewLargeVer = view.findViewById(R.id.threeCardViewLargeVer)
        twoCardsViewRect = view.findViewById(R.id.threeHotView3)
        bigCardView = view.findViewById(R.id.bigCardView)
        randomView = view.findViewById(R.id.randomView)
        rowCardView = view.findViewById(R.id.ColCardView)
        twoVideoView = view.findViewById(R.id.twoVideoView)
        bigVideo = view.findViewById(R.id.bigVideo)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        languageSpinner = view.findViewById(R.id.languageSpinner)

        setupLanguageSpinner()

        swipeRefresh.setOnRefreshListener { loadAllSlots() }

        threeCardsViewVerBorder.setSlotListener(this)
        threeCardsViewLargeVer.setSlotListener(this)
        twoCardsViewRect.setSlotListener(this)
        twoVideoView.setSlotListener(this)
        bigVideo.setSlotListener(this)
        bigCardView.setSlotListener(this)
        randomView.setSlotListener(this)
        rowCardView.setSlotListener(this)

        randomView.setAutoScroll(true)
        // 首屏加载由语言下拉的首次回调驱动（见 setupLanguageSpinner），
        // 保证第一条请求就带上选中的 language。
    }

    /** 语言下拉：选择后调用 SDK setLanguage 并重新拉广告，新请求即带新 language。 */
    private fun setupLanguageSpinner() {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            languages.map { it.first }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        languageSpinner.adapter = adapter

        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val code = languages[position].second
                GameSlotSdk.setLanguage(code)
                Log.d("mlog", "切换语言 language=$code")
                loadAllSlots()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** 加载/重新加载所有广告位，下拉刷新时复用。 */
    private fun loadAllSlots() {
        threeCardsViewVerBorder.loadSlot("TSK9P4X1Q6B2", 3)
        bigVideo.loadSlot("TSB4F2H8Y3Q7")
        bigCardView.loadSlot("TSZ3R9W6P2K8")
    }

    override fun onSlotLoaded(gs: GameSlot, slotId: String) {
        swipeRefresh.isRefreshing = false
    }

    override fun onSlotFailed(message: String, slotId: String) {
        swipeRefresh.isRefreshing = false
    }

    override fun onSlotShow(gs: GameSlot, slotId: String) {
    }

    override fun onSlotClick(gs: GameSlot, slotId: String) {
        Log.d("mlog", gs.id + "__" + slotId)
    }

    override fun onGameStart(gs: GameSlot) {
    }

    override fun onGameClose(gs: GameSlot) {
    }
}
