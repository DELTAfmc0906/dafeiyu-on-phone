package com.dafeiyu.pet.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.dafeiyu.pet.data.Lines
import com.dafeiyu.pet.engine.PetEngine

/**
 * 喂食面板。
 *
 * 移植 Python 版的 `FoodPanel`：双击鱼身弹出，横向排列 🐟🍰🍭🍡💎 五种食物，
 * 点哪个就喂哪个，鱼会播报对应台词。面板本身带一个 ✕ 关闭按钮。
 *
 * 与 PC 版的差异：PC 上是独立小窗贴着鱼头顶；Android 上仍是悬浮窗，
 * 但尺寸按 dp 放大以适应手指点击（原版按钮 44px，手机上太小）。
 */
object FeedPanel {

    private var current: View? = null

    fun show(
        context: Context,
        engine: PetEngine,
        wm: WindowManager,
        onFed: () -> Unit
    ) {
        dismiss(wm)

        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(225, 40, 40, 60))
            }
        }

        for (food in Lines.FOODS) {
            val btn = TextView(context).apply {
                text = food
                textSize = 22f
                gravity = Gravity.CENTER
                val size = dp(48)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = dp(8)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.argb(240, 255, 255, 255))
                    setStroke(dp(2), Color.parseColor("#FFB3C8"))
                }
                setOnClickListener {
                    engine.onFeed(food)
                    onFed()
                    dismiss(wm)
                }
            }
            row.addView(btn)
        }

        val close = TextView(context).apply {
            text = "✕"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#DDDDDD"))
            val size = dp(30)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { dismiss(wm) }
        }
        row.addView(close)

        // 未测量前无法知道宽度，先给个足够大的值，系统会按 wrap_content 收缩
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 贴着鱼头顶居中
            x = (engine.x + engine.petWidth / 2 - dp(140)).toInt().coerceAtLeast(0)
            y = (engine.y - engine.bubbleReserve - dp(60)).toInt().coerceAtLeast(0)
        }

        runCatching { wm.addView(row, params) }
        current = row
    }

    fun dismiss(wm: WindowManager) {
        current?.let { v -> runCatching { wm.removeView(v) } }
        current = null
    }
}
