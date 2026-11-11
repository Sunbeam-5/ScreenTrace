package com.screentrace.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.screentrace.app.ui.AppNav

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 系统栏必须真正进入 edge-to-edge，否则状态栏是系统默认的**不透明黑条**，
        // 而 App 背景是浅色卡片色——真机上一眼就是「上面多了一条黑边」。
        //
        // 之前完全没调这个 API，只在 themes.xml 写了 `windowLightStatusBar=true`：
        // 那只让**图标**变深色，底色仍是系统默认黑，于是深色图标压黑底，同样难看。
        // enableEdgeToEdge 把两个系统栏都设成透明并接管图标明暗，palette.background
        // 才能真正铺到状态栏下方（对齐 nsh07/Tomato 等 M3 应用的观感）。
        //
        // 图标明暗的最终值由 AppNav 按用户选择的深浅色再覆盖一次：本 App 支持
        // 「App 内独立切深色」，不能只跟随系统。
        enableEdgeToEdge()
        setContent { AppNav() }
    }
}
