package com.dafeiyu.pet

import android.app.Application
import android.os.Build
import com.dafeiyu.pet.ui.VoiceOverlay
import com.google.android.material.color.DynamicColors

/**
 * 应用入口（v1.3 新增）。
 *
 * ## 为什么需要它：启用 Material You 动态取色
 *
 * Material You（M3）最核心的特色就是**动态取色**：系统从用户壁纸里
 * 提取主色，生成一整套配色方案，应用直接使用 —— 于是每个用户看到的
 * App 颜色都是独一无二的，且和系统其他界面协调。
 *
 * Android 12（API 31）起系统提供这个能力，
 * [DynamicColors.applyToActivitiesIfAvailable] 会自动把系统配色
 * 覆盖到主题里声明的 M3 颜色角色上。
 *
 * ## 为什么还要保留静态配色
 *
 * - Android 11 及以下没有动态取色，仍然用 `colors.xml` / `values-night` 里的
 *   手写配色（那套也是按 M3 的 HCT 规则推出来的，观感一致）
 * - 部分 ROM（尤其国产定制系统）即便版本够也可能不支持，会静默回退
 *
 * 两种情况下界面都不会崩 —— 因为组件引用的始终是**颜色角色名**
 * （colorPrimary / colorSurfaceContainerHigh…），而不是写死的色值。
 */
class DafeiyuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 第二个参数是"是否也应用到已声明自定义主题的 Activity"，
            // 传 true 才能覆盖我们自己的 Theme.DafeiyuPet。
            DynamicColors.applyToActivitiesIfAvailable(this)
        }

        /*
         * 启动语音浮层的心跳回收（v1.3.3）。
         *
         * 放在 Application 而不是某个 Activity：悬浮窗的残留与界面生命周期
         * 无关，对话界面可能早就销毁了，浮层还挂在屏幕上。心跳跑在应用进程里，
         * 只要进程活着就能把它收掉 —— 这是"永久漂浮"的最后一道保险。
         */
        VoiceOverlay.startJanitor()
    }
}
