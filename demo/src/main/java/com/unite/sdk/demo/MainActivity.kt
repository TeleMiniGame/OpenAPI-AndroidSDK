package com.unite.sdk.demo

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.unite.sdk.GameSlotSdk
import com.unite.sdk.view.GameCenterView

class MainActivity : AppCompatActivity() {
    private lateinit var gameCenter: GameCenterView
    private lateinit var tabDemo: View
    private lateinit var tabAbout: View

    private lateinit var homeFragment: Fragment
    private lateinit var demoFragment: Fragment
    private lateinit var active: Fragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 凭证从 local.properties/环境变量注入（BuildConfig），不硬编码进仓库；
        // 用 M1 的一次性 init：environment 折进 config，appId/appVersion 自动取包信息
        GameSlotSdk.init(
            applicationContext,
            GameSlotSdk.GameSlotConfig(
                clientId = BuildConfig.UNITE_CLIENT_ID,
                secretKey = BuildConfig.UNITE_SECRET_KEY,
                uid = BuildConfig.UNITE_UID,
                environment = GameSlotSdk.Environment.DEV,
            )
        )

        tabDemo = findViewById(R.id.tabDemo)
        tabAbout = findViewById(R.id.tabAbout)

        val fm = supportFragmentManager
        homeFragment = fm.findFragmentByTag(TAG_HOME) ?: HomeFragment()
        demoFragment = fm.findFragmentByTag(TAG_DEMO) ?: DemoFragment()

        if (savedInstanceState == null) {
            fm.beginTransaction()
                .add(R.id.contentContainer, homeFragment, TAG_HOME)
                .add(R.id.contentContainer, demoFragment, TAG_DEMO)
                .hide(demoFragment)
                .commit()
            active = homeFragment
        } else {
            // 重建后按恢复的隐藏态判断当前页
            active = if (demoFragment.isHidden) homeFragment else demoFragment
        }

        selectTab(if (active === homeFragment) tabDemo else tabAbout)
        tabDemo.setOnClickListener {
            selectTab(tabDemo)
            showFragment(homeFragment)
        }
        tabAbout.setOnClickListener {
            selectTab(tabAbout)
            showFragment(demoFragment)
        }

        gameCenter = findViewById(R.id.gameCenter)
        gameCenter.setConfig(
            url = "https://m.minigame.com/main",
            imageUrl = "https://oss.televs.com/oss/tgpush/2024-11-25/98cf326a-amsg_image_game_581732517872960.png",
            imageWidthDp = 44,
            imageHeightDp = 44,
            title = null
        )
        // 配置了大厅位 slot_id 时走大厅位：入口地址取接口下发的 hall_url（覆盖上面的固定 URL），
        // 曝光/点击按 content_ids="game_center" + 大厅 slot_id 上报
        if (BuildConfig.UNITE_HALL_SLOT_ID.isNotEmpty()) {
            gameCenter.loadSlot(BuildConfig.UNITE_HALL_SLOT_ID)
        }
    }

    /** 固定底部 Tab 的选中态切换。 */
    private fun selectTab(selected: View) {
        tabDemo.isSelected = selected === tabDemo
        tabAbout.isSelected = selected === tabAbout
    }

    /** 页内切换 Fragment，保留各自状态，不跳转新页面。 */
    private fun showFragment(target: Fragment) {
        if (target === active) return
        supportFragmentManager.beginTransaction()
            .hide(active)
            .show(target)
            .commit()
        active = target
    }

    private companion object {
        const val TAG_HOME = "home"
        const val TAG_DEMO = "demo"
    }
}
