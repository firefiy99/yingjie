package com.yingjie.app

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View

/**
 * iOS 风格毛玻璃效果。
 * 真模糊只作用于「背景装饰层」（不含文字），避免把标题/按钮等文字一起糊掉。
 */
object FrostedGlass {

    /** 给背景装饰层加真模糊（API 31+）；低版本保持原样，玻璃靠半透明近似。 */
    fun blur(view: View, radius: Float = 24f) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
    }
}
