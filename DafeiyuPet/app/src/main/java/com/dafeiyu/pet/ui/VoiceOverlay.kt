package com.dafeiyu.pet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「按住说话」浮层 —— v1.3.1 按官方/微信的形态重做。
 *
 * ## 上一版错在哪
 *
 * 我原来做成一个**小圆话筒图标**，用户的原话是：
 * "官方的设计是一个类似微信语音输入的椭圆形UI，你个小话筒算个啥"。
 *
 * 确实跑偏了。官方 App 与微信的按住说话都是**底部一条宽大的椭圆形按钮**，
 * 按住后整条变成录音态。小图标既不是那个形态，也没有足够的触控面积。
 *
 * ## 现在的形态
 *
 * ```
 *   ┌─────────────────────────────────────┐
 *   │           按住  说话                │   ← 宽椭圆，高 52dp
 *   └─────────────────────────────────────┘
 * ```
 *
 * 按住后：
 *  - 按钮原地变成**录音态**：主色填充 + 实时波形 + "松开发送"
 *  - 屏幕中央弹出浮层：波形 + 实时识别文字 + 取消提示
 *  - 上滑超过阈值：全部变红，提示改「松开 取消」
 *  - 松手：**立即**收回浮层并恢复按钮（不等待任何回调）
 *
 * ## 两个必须记住的坑
 *
 * ### 1. 浮层不能等回调才消失
 * 上一版把"移除浮层"寄托在 `SpeechRecognizer.onResult/onError` 上。
 * 但识别服务出错时那两个回调**都不保证会来**，于是浮层永远挂在屏幕上，
 * 只有杀掉 App 才消失 —— 典型的悬浮窗泄漏。
 *
 * 现在：松手那一刻**由触摸事件直接**收回浮层，与识别结果完全解耦；
 * 另外还有 [SAFETY_TIMEOUT_MS] 兜底。
 *
 * ### 2. 波形必须自己动
 * `onRmsChanged` 只在真有音频输入时才回调。真机上静音段不回调、
 * 模拟器上（没有麦克风）**一次都不回调** —— 于是波形完全不动，
 * 看起来像坏了。所以现在有一路 60fps 的动画驱动：
 * 没有真实音量时也画出明显起伏，有真实音量时按音量放大。
 */
class VoiceOverlay(private val context: Context) {

    companion object {
        /** 上滑超过这个距离（dp）进入取消态 */
        private const val CANCEL_THRESHOLD_DP = 60f

        /** 按下后短于这个时长松手，视为"说话时间太短" */
        private const val MIN_DURATION_MS = 600L

        /**
         * 兜底：无论如何最多显示这么久。
         * 防止任何未知异常路径让浮层滞留。
         */
        private const val SAFETY_TIMEOUT_MS = 20_000L
    }

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    private val m3 by lazy { M3ViewFactory(context) }

    var onCancel: (() -> Unit)? = null

    private var root: View? = null
    private var wave: WaveformView? = null
    private var hintText: TextView? = null
    private var resultText: TextView? = null

    private var cancelling = false
    private var startedAt = 0L

    /** 当前真实音量（0..1），由 onRmsChanged 更新 */
    private var realLevel = 0f

    val isShowing: Boolean get() = root != null
    val isCancelling: Boolean get() = cancelling

    // =============================== 波形动画驱动 ===============================

    /*
     * 波形的动画由调用方（AiChatPanelView.waveDrive）统一驱动，
     * 通过 [pushWave] 推值进来。
     *
     * 为什么不在这里自己起循环：按住说话时屏幕上同时有两处波形
     * （浮层大波形 + 按钮上的小波形），若各自起循环会画得不一致；
     * 由调用方算一次推两处最省也最同步。
     *
     * 但**必须有外部驱动**，否则就是用户反馈的"波形极为平静"：
     * `onRmsChanged` 在真机静音段不回调、模拟器无麦克风时一次都不回调，
     * 波形会一直是直线。
     */

    // =============================== 显示 ===============================

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return
        cancelling = false
        realLevel = 0f
        startedAt = System.currentTimeMillis()

        // ---------- 实时识别结果 ----------
        resultText = TextView(context).apply {
            text = "请开始说话…"
            textSize = 16f
            setTextColor(m3.onSurface)
            gravity = Gravity.CENTER
            maxLines = 3
            setPadding(dp(24f), dp(18f), dp(24f), dp(6f))
        }

        // ---------- 波形 ----------
        wave = WaveformView(context).apply { setColor(m3.primary) }

        // ---------- 提示 ----------
        hintText = TextView(context).apply {
            text = "手指上滑，取消发送"
            textSize = 12f
            setTextColor(m3.onSurfaceVariant)
            gravity = Gravity.CENTER
            setPadding(0, dp(2f), 0, dp(18f))
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // M3：surfaceContainerHigh + XL 圆角（28dp）
            background = GradientDrawable().apply {
                cornerRadius = dp(28f).toFloat()
                setColor(m3.surfaceContainerHigh)
                setStroke(dp(1f), m3.outlineVariant)
            }
            addView(resultText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(wave, LinearLayout.LayoutParams(dp(240f), dp(60f)))
            addView(hintText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        val params = WindowManager.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.78f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不吃触摸（手指按在下面的按住说话条上）、不抢焦点
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        runCatching { windowManager.addView(column, params) }
        root = column

        // 兜底：任何异常路径都不该让浮层无限滞留
        main.postDelayed(safetyDismiss, SAFETY_TIMEOUT_MS)
    }

    /**
     * 兜底移除。
     *
     * 正常路径下浮层在松手时就被 [dismiss] 收掉了，这个定时器只是保险：
     * 万一有没预料到的异常路径让浮层滞留，到时间也会被强制清掉，
     * 绝不会出现"只有杀 App 才消失"的情况。
     */
    private val safetyDismiss = Runnable { dismiss() }

    /** 由触摸事件转发上滑位移，判断是否进入取消态 */
    fun onDrag(dy: Float) {
        val entering = dy < -dp(CANCEL_THRESHOLD_DP)
        if (entering == cancelling) return
        cancelling = entering
        if (entering) {
            hintText?.text = "松开 取消"
            hintText?.setTextColor(m3.error)
            wave?.setColor(m3.error)
            resultText?.setTextColor(m3.error)
        } else {
            hintText?.text = "手指上滑，取消发送"
            hintText?.setTextColor(m3.onSurfaceVariant)
            wave?.setColor(m3.primary)
            resultText?.setTextColor(m3.onSurface)
        }
        root?.scaleX = if (entering) 0.94f else 1f
        root?.scaleY = if (entering) 0.94f else 1f
    }

    /** 识别中间结果 / 最终结果都往这里塞 */
    fun setText(text: String) {
        resultText?.text = text
    }

    /**
     * 状态说明（离线识别 / 在线识别 / 正在重试）。
     *
     * 放在提示行上，让用户能看出"卡住"到底卡在哪 ——
     * 尤其是网络不稳时"正在重试"比一个转圈友好得多。
     */
    fun setStatus(text: String) {
        if (cancelling) return
        hintText?.text = text
    }

    /** 真实音量 0..1（来自 onRmsChanged），驱动波形幅度 */
    fun setLevel(level: Float) {
        realLevel = level.coerceIn(0f, 1f)
    }

    /**
     * 直接推一个波形值。
     *
     * 与 [setLevel] 的区别：setLevel 只更新"真实音量"这个基准，
     * 由内部的 [waveLoop] 决定每帧画多高；而这个是外部（对话界面）
     * 的动画循环算好值直接推给浮层，避免两个动画循环各画各的。
     */
    fun pushWave(level: Float) {
        wave?.pushLevel(level.coerceIn(0f, 1f))
    }

    /**
     * 松手。
     *
     * **不负责移除浮层** —— 移除由 [dismiss] 立即执行，
     * 见类注释里关于"回调不可靠"的说明。
     */
    fun release() {
        val duration = System.currentTimeMillis() - startedAt
        when {
            cancelling -> flashHint("已取消")
            duration < MIN_DURATION_MS -> flashHint("说话时间太短")
        }
    }

    private fun flashHint(msg: String) {
        hintText?.text = msg
        hintText?.setTextColor(m3.error)
        wave?.setColor(m3.error)
    }

    /**
     * 立即移除浮层。**幂等**，可以随便多调几次。
     * 只要调用，浮层必定消失 —— 这是上一版缺失的保证。
     */
    fun dismiss() {
        main.removeCallbacks(safetyDismiss)
        main.removeCallbacks(dismissSoonRunnable)
        val v = root ?: return
        root = null
        runCatching { windowManager.removeView(v) }
        wave = null
        hintText = null
        resultText = null
        cancelling = false
        realLevel = 0f
    }

    private val dismissSoonRunnable = Runnable { dismiss() }

    /** 让"已取消/太短"的提示停留一下再消失，避免用户看不清 */
    fun dismissAfterHint() {
        main.postDelayed(dismissSoonRunnable, 450L)
    }

    /**
     * 音量波形。
     *
     * 用一根根竖条表示最近的音量，新数据从右侧进、整体左移。
     * 条本身带圆角，配合主色就是主流语音输入的观感。
     */
    class WaveformView(context: Context, attrs: android.util.AttributeSet? = null) :
        View(context, attrs) {

        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var barColor = Color.GRAY

        /** 固定 28 根条，每根记录一个音量值 0..1 */
        private val levels = FloatArray(28)
        private var head = 0

        private val barW = 4.5f * resources.displayMetrics.density
        private val gapW = 3.5f * resources.displayMetrics.density

        fun setColor(c: Int) {
            barColor = c
            invalidate()
        }

        fun pushLevel(level: Float) {
            levels[head] = level.coerceIn(0f, 1f)
            head = (head + 1) % levels.size
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            barPaint.color = barColor
            val cy = height / 2f
            val n = levels.size
            val totalW = n * barW + (n - 1) * gapW
            var x = (width - totalW) / 2f

            for (i in 0 until n) {
                val v = levels[(head + i) % n]
                // 最矮也有 18% 高，保证是一条连续的波形而不是断续的点
                val h = (height * 0.18f) + (height * 0.78f) * v
                canvas.drawRoundRect(
                    x, cy - h / 2f, x + barW, cy + h / 2f,
                    barW / 2f, barW / 2f, barPaint
                )
                x += barW + gapW
            }
        }
    }
}
