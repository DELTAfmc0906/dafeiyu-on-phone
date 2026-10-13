package com.dafeiyu.pet.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.dafeiyu.pet.data.ConfigRepo
import com.dafeiyu.pet.data.PetMode
import com.dafeiyu.pet.data.PetSize
import com.dafeiyu.pet.engine.PetEngine

/**
 * 长按鱼身弹出的菜单。
 *
 * 对应 Python 版的右键菜单 `_build_menu()`。差异说明：
 *   模式           -> 原样保留（弹二级菜单）
 *   大小           -> v1.1 起改为**滑杆自由调节**（8%~50% 占屏高），并保留三档快捷值
 *   设置 Key       -> 已移除（AI 对话功能砍掉）
 *   查看天气       -> 保留
 *   显示/隐藏      -> 移除（Android 上由通知栏"收工"代替）
 *   回到屏幕内     -> 保留
 *   鼠标穿透       -> 改名「可互动」，语义与原版相反
 *   窗口置顶       -> 移除（悬浮窗天然置顶）
 *   开机自启       -> 保留
 *   退出           -> 保留（改为"收工"，停止服务）
 */
object PetMenu {

    /**
     * 大小调节弹窗：一个滑杆 + 实时百分比 + 三档快捷。
     * 拖动时**立刻**应用到引擎，所以用户能边拖边看到鱼变大变小。
     */
    private fun showSizeDialog(
        context: Context,
        engine: PetEngine,
        config: ConfigRepo,
        onChanged: () -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val steps = (PetSize.MAX_PERCENT - PetSize.MIN_PERCENT) / PetSize.STEP
        fun percentOf(progress: Int) = PetSize.MIN_PERCENT + progress * PetSize.STEP
        fun progressOf(percent: Int) =
            ((PetSize.clamp(percent) - PetSize.MIN_PERCENT) / PetSize.STEP).coerceIn(0, steps)

        val label = TextView(context).apply {
            text = "当前：占屏幕高度 ${engine.sizePercent}%"
            textSize = 16f
            setPadding(dp(24), dp(16), dp(24), dp(4))
        }
        val seek = SeekBar(context).apply {
            max = steps
            progress = progressOf(engine.sizePercent)
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        fun apply(percent: Int) {
            engine.sizePercent = percent
            config.sizePercent = percent
            label.text = "当前：占屏幕高度 $percent%"
            onChanged()
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) apply(percentOf(progress))
                else label.text = "当前：占屏幕高度 ${percentOf(progress)}%"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        val presetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        val presets = listOf(
            "小 ${PetSize.SMALL_PERCENT}%" to PetSize.SMALL_PERCENT,
            "中 ${PetSize.MEDIUM_PERCENT}%" to PetSize.MEDIUM_PERCENT,
            "大 ${PetSize.LARGE_PERCENT}%" to PetSize.LARGE_PERCENT
        )
        for ((text, percent) in presets) {
            presetRow.addView(TextView(context).apply {
                this.text = text
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.parseColor("#1565C0"))
                setPadding(dp(6), dp(10), dp(6), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                setOnClickListener {
                    seek.progress = progressOf(percent)
                    apply(percent)
                }
            })
        }

        val tip = TextView(context).apply {
            text = "可调范围 ${PetSize.MIN_PERCENT}%~${PetSize.MAX_PERCENT}%。嫌挡事就拉到 ${PetSize.SMALL_PERCENT}% 左右。"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(dp(24), 0, dp(24), dp(12))
        }

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(seek)
            addView(presetRow)
            addView(tip)
        }

        AlertDialog.Builder(context)
            .setTitle("调整大小")
            .setView(box)
            .setPositiveButton("完成", null)
            .show()
    }

    fun show(
        context: Context,
        engine: PetEngine,
        config: ConfigRepo,
        onChanged: () -> Unit
    ) {
        val modeLabels = arrayOf("自由散步", "跟随手指", "原地待着")
        val modeValues = arrayOf(PetMode.WANDER, PetMode.FOLLOW, PetMode.STILL)

        var dialog: AlertDialog? = null
        val builder = AlertDialog.Builder(context)

        fun rebuild() {
            dialog?.dismiss()
            val modeIdx = modeValues.indexOf(engine.mode).coerceAtLeast(0)

            val items = arrayOf(
                "模式：${modeLabels[modeIdx]}",
                "大小：占屏高 ${engine.sizePercent}%",
                "查看天气",
                if (config.interactive) "不可互动（让触摸穿透）" else "恢复可互动",
                "${if (config.batteryAlert) "关闭" else "开启"}低电量提醒",
                "回到屏幕内",
                "${if (config.autoStart) "关闭" else "开启"}开机自启",
                "收工（关闭桌宠）"
            )

            dialog = builder
                .setTitle("大肥鱼")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> {
                            AlertDialog.Builder(context)
                                .setTitle("模式")
                                .setSingleChoiceItems(modeLabels, modeIdx) { d, i ->
                                    engine.mode = modeValues[i]
                                    config.mode = modeValues[i]
                                    d.dismiss()
                                    onChanged()
                                    rebuild()
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                        1 -> {
                            showSizeDialog(context, engine, config) {
                                onChanged()
                                rebuild()
                            }
                        }
                        2 -> {
                            PetService.requestWeather(context, config.city)
                        }
                        3 -> {
                            config.interactive = !config.interactive
                            onChanged()
                            rebuild()
                        }
                        4 -> {
                            config.batteryAlert = !config.batteryAlert
                            engine.setBatteryAlertEnabled(config.batteryAlert)
                            rebuild()
                        }
                        5 -> {
                            engine.placeAtDefault()
                            config.savePosition(engine.x.toInt(), engine.y.toInt())
                            onChanged()
                            rebuild()
                        }
                        6 -> {
                            config.autoStart = !config.autoStart
                            BootReceiver.setEnabled(context, config.autoStart)
                            rebuild()
                        }
                        7 -> {
                            PetService.stop(context)
                        }
                    }
                }
                .setNegativeButton("关闭", null)
                .show()
        }

        rebuild()
    }
}
