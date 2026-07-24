import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat

object BrowserHelper {
    fun openWebPage(context: Context, url: String) {
        try {
            // 1. 创建 CustomTabsIntent 构造器
            val builder = CustomTabsIntent.Builder()

            // 2. 自定义外观（可选）
            // 设置工具栏颜色（让它看起来更像你的 App）
            // builder.setToolbarColor(ContextCompat.getColor(context, R.color.purple_500))

            // 显示网页标题
            builder.setShowTitle(true)

            // 3. 构建并启动
            val customTabsIntent = builder.build()

            // 关键：这行代码会直接启动内部浏览器窗口，不触发系统弹窗
            customTabsIntent.launchUrl(context, Uri.parse(url))

        } catch (e: Exception) {
            // 兜底逻辑：如果用户手机没有任何浏览器（极少见），可以跳转 WebView 或普通 Intent
            e.printStackTrace()
        }
    }
}