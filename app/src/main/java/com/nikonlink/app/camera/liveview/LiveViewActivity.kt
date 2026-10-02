package com.nikonlink.app.camera.liveview

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nikonlink.app.databinding.ActivityLiveviewBinding
import dagger.hilt.android.AndroidEntryPoint

/**
 * 全屏实时取景页。
 * 由遥控页的「全屏监看」入口打开，支持横竖屏跟随重力感应，进入后自动启动 LiveView。
 */
@AndroidEntryPoint
class LiveViewActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, LiveViewActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityLiveviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 先让内容绘制到系统栏区域，再由 Fragment 用 insets 自己避让。
        // 不打开这一项的话，insets 会被 DecorView 消费掉，Fragment 拿到的全是 0，
        // 挖孔屏横屏时顶栏左侧的关闭按钮就会被切掉一块。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(binding.fragmentContainer.id, LiveViewFragment.newAutoStart())
                .commit()
        }
    }
}
