package com.unite.sdk.demo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.unite.sdk.demo.adapter.CardAdapter
import com.unite.sdk.demo.adapter.DeviceAdapter
import com.unite.sdk.demo.adapter.GridAdapter
import com.unite.sdk.demo.databinding.ActivityDemoBinding
import com.unite.sdk.demo.model.DemoItem
import com.unite.sdk.GameSlotSdk
import java.util.Locale

class DemoFragment : Fragment() {

    private var _binding: ActivityDemoBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityDemoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 本页游戏位固定用系统语言：清掉首页下拉可能设置的语言覆盖，再展示当前系统语言
        GameSlotSdk.setLanguage("")
        val sysLang = Locale.getDefault().language
        binding.languageInfo.text = "本页游戏位语言：$sysLang（系统语言）"

        binding.threeCardsView.loadSlot("TSK9P4X1Q6B2", 3)
        binding.bigcardAd.loadSlot("TSZ3R9W6P2K8")
        binding.bigVideo.loadSlot("TSB4F2H8Y3Q7")

        initGrid()
        initCard()
        initDevice()
    }

    private fun initGrid() {
        val data = listOf(
            DemoItem("灯光"),
            DemoItem("空调"),
            DemoItem("摄像头"),
            DemoItem("窗帘"),
            DemoItem("插座"),
            DemoItem("门锁"),
            DemoItem("网关"),
            DemoItem("温湿度"),
            DemoItem("更多")
        )
        binding.gridMenu.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.gridMenu.adapter = GridAdapter(data)
    }

    private fun initCard() {
        val data = listOf(
            DemoItem("推荐设备"),
            DemoItem("家庭场景"),
            DemoItem("自动化")
        )
        binding.cardList.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.cardList.adapter = CardAdapter(data)
    }

    private fun initDevice() {
        val data = listOf(
            DemoItem("客厅灯"),
            DemoItem("卧室空调"),
            DemoItem("门口摄像头"),
            DemoItem("厨房插座")
        )
        binding.deviceList.layoutManager = LinearLayoutManager(requireContext())
        binding.deviceList.adapter = DeviceAdapter(data)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
