package com.dafeiyu.pet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「按住说话」浮层 —— v1.2.5 重做。
 *
 * ## 参考的交互（官方 App / 主流 IM 的做法）
 *
 * DeepSeek 官方 App 1.6.3 起支持"按住说话"；通用的长按语音交互约定是：
 *
 *   按住      -> 弹出浮层，开始收音，**边说边出字**
 *   说话中    -> 波形跟随音量跳动，实时显示识别中间结果
 *   上滑      -> 进入"取消"区域，松手丢弃这次录音
 *   松手      -> 把识别结果写进输入框（或直接发送）
 *   松手太快  -> 提示"说话时间太短"
 *
 * 之前那版只有一个图标变色加一行状态文字，没有任何反馈，所以重做。
 *
 * ## 实现要点
 *
 * - 波形用 [WaveformView] 自绘；数据来自 `SpeechRecognizer.onRmsChanged`
 * - "上滑取消"用 `translationY` 判断，超过阈值即进入取消态（变红 + 提示）
 * - 浮层是独立悬浮窗（`TYPE_APPLICATION_OVERLAY`），因为说话时用户的手
 *   按在按钮上，不能占用对话 Activity 的布局
 */
class VoiceOverlay(private val context: Context) {

    companion object {
        /** 上滑超过这个距离（dp）进入取消态 */
        private const val CANCEL_THRESHOLD_DP = 60f

        /** 按下后短于这个时长松手，视为"说话时间太短" */
        private const val MIN_DURATION_MS = 600L
    }

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    var onCancel: (() -> Unit)? = null
    var onFinish: ((String) -> Unit)? = null

    private var root: View? = null
    private var wave: WaveformView? = null
    private var hintText: TextView? = null
    private var resultText: TextView? = null

    private var cancelling = false
    private var startedAt = 0L
    private var latestText = ""

    val isShowing: Boolean get() = root != null
    val isCancelling: Boolean get() = cancelling

    // =============================== 显示 ===============================

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return
        cancelling = false
        latestText = ""
        startedAt = System.currentTimeMillis()

        // ---------- 实时识别结果 ----------
        resultText = TextView(context).apply {
            text = ""
            textSize = 15f
            setTextColor(Color.parseColor("#33333D"))
            gravity = Gravity.CENTER
            maxLines = 3
            setPadding(dp(20f), dp(10f), dp(20f), dp(4f))
        }

        // ---------- 波形 ----------
        wave = WaveformView(context)

        // ---------- 提示 ----------
        hintText = TextView(context).apply {
            text = "手指上滑，取消发送"
            textSize = 12f
            setTextColor(Color.parseColor("#9A9AA8"))
            gravity = Gravity.CENTER
            setPadding(0, dp(2f), 0, dp(12f))
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(Color.parseColor("#F7F7FB"))
                setStroke(dp(1f), Color.parseColor("#E2E2EC"))
            }
            addView(resultText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(wave, LinearLayout.LayoutParams(
                dp(200f), dp(56f)
            ))
            addView(hintText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        val params = WindowManager.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.72f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 浮层不吃触摸（手已经按在下面的按钮上），也不抢焦点
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        runCatching { windowManager.addView(column, params) }
        root = column
    }

    /** 由触摸事件转发上滑位移，判断是否进入取消态 */
    fun onDrag(dy: Float) {
        val entering = dy < -dp(CANCEL_THRESHOLD_DP)
        if (entering == cancelling) return
        cancelling = entering
        if (entering) {
            hintText?.text = "松开取消"
            hintText?.setTextColor(Color.parseColor("#E53935"))
            wave?.setColor(Color.parseColor("#E53935"))
            resultText?.setTextColor(Color.parseColor("#E53935"))
        } else {
            hintText?.text = "手指上滑，取消发送"
            hintText?.setTextColor(Color.parseColor("#9A9AA8"))
            wave?.setColor(Color.parseColor("#5686FE"))
            resultText?.setTextColor(Color.parseColor("#33333D"))
        }
        root?.scaleX = if (entering) 0.94f else 1f
        root?.scaleY = if (entering) 0.94f else 1f
    }

    /** 识别中间结果 / 最终结果都往这里塞 */
    fun setText(text: String) {
        latestText = text
        resultText?.text = text
    }

    /** 音量 0..1，驱动波形 */
    fun setLevel(level: Float) {
        wave?.pushLevel(level)
    }

    /**
     * 静音时的呼吸动画。
     * `SpeechRecognizer.onRmsChanged` 只在有声时频繁回调，
     * 用户一停顿波形就完全静止，看起来像卡死，所以给个最小起伏。
     */
    fun idlePulse() {
        wave?.idlePulse()
    }

    /**
     * 松手。返回 true 表示这次录音有效（应由调用方发送/填入）。
     * 取消或时长过短时返回 false，并给出提示。
     */
    fun release(): Boolean {
        val duration = System.currentTimeMillis() - startedAt
        return when {
            cancelling -> {
                flashHint("已取消")
                dismissSoon()
                onCancel?.invoke()
                false
            }
            duration < MIN_DURATION_MS -> {
                flashHint("说话时间太短")
                dismissSoon()
                false
            }
            else -> false // 真正的结果由 SpeechRecognizer 的 onResults 回调给，不在这里返回
        }
    }

    private fun flashHint(msg: String) {
        hintText?.text = msg
        hintText?.setTextColor(Color.parseColor("#E53935"))
        wave?.setColor(Color.parseColor("#E53935"))
    }

    fun dismiss() {
        val v = root ?: return
        runCatching { windowManager.removeView(v) }
        root = null
        wave = null
        hintText = null
        resultText = null
        cancelling = false
    }

    private fun dismissSoon() {
        main.postDelayed({ dismiss() }, 400L)
    }

    /**
     * 音量波形。
     *
     * 用一根根竖条表示最近的音量，新数据从右侧进、整体左移，
     * 效果接近主流语音输入的动态波形。
     */
    class WaveformView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var barColor = Color.parseColor("#5686FE")

        /** 固定 24 根条，每根记录一个音量值 0..1 */
        private val levels = FloatArray(24)
        private var head = 0

        private val barW = 4f * resources.displayMetrics.density
        private val gapW = 3f * resources.displayMetrics.density

        fun setColor(c: Int) {
            barColor = c
            invalidate()
        }

        fun pushLevel(level: Float) {
            levels[head] = level.coerceIn(0f, 1f)
            head = (head + 1) % levels.size
            postInvalidateOnAnimation()
        }

        /** 没声音时也让波形轻微呼吸，避免看起来像卡住 */
        fun idlePulse() {
            levels[head] = 0.06f
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
                // 从 head 开始取，保证新的在右侧
                val v = levels[(head + i) % n]
                // 最小高度给一点，看起来是一条连续的波形
                val h = (height * 0.12f) + (height * 0.78f) * v
                canvas.drawRoundRect(
                    x, cy - h / 2f, x + barW, cy + h / 2f,
                    barW / 2f, barW / 2f, barPaint
                )
                x += barW + gapW
            }
        }
    }
}
