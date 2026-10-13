package com.dafeiyu.pet.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dafeiyu.pet.ai.ChatHistoryStore
import com.dafeiyu.pet.ai.VoiceInput

/**
 * AI 对话界面（纯 View，不含窗口管理）。
 *
 * ## 为什么从"悬浮窗"改成"Activity 里的 View"（v1.2.2 的关键修复）
 *
 * v1.2 / v1.2.1 把聊天界面做成了一个独立的 `TYPE_APPLICATION_OVERLAY` 悬浮窗。
 * 实测证明这条路走不通：
 *
 * ```
 * mCurrentFocus  = com.dafeiyu.pet        ← 窗口焦点拿到了
 * mIsInputViewShown = true                ← 键盘也弹出来了
 * mServedView    = DecorView{...其他Activity}   ← 但输入法连的是别的窗口
 * mServedInputConnection = null           ← 输入连接是空的
 * ```
 *
 * 即：**悬浮窗可以获得窗口焦点，但输入法不会为它建立 InputConnection**，
 * 结果就是"键盘看得见、打字进不去"。加多少 flag 都没用 ——
 * 文本输入应当交给有正常窗口令牌的 Activity。
 *
 * 改成 Activity 还顺带修掉了另一个更严重的问题：悬浮窗脱离 Activity 生命周期，
 * 一旦没被正确移除就会变成"孤儿窗口"，持续吃掉全局触摸，
 * 表现为整个手机点不动、只能重启。Activity 由系统托管，不会出现这种情况。
 */
class AiChatPanelView(
    context: Context,
    private val history: ChatHistoryStore,
    private val onSend: (String) -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onClose: () -> Unit,
    /** 语音识别完是否自动发送；false 则只填进输入框 */
    private val voiceAutoSend: Boolean = true,
    /** 请求录音权限；由 Activity 转发 */
    private val onRequestMicPermission: (() -> Unit)? = null
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private lateinit var messageBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var statusText: TextView
    private lateinit var micBtn: TextView

    private var streamingView: TextView? = null

    /** 思考过程气泡（只在开启思考时出现） */
    private var thinkingView: TextView? = null

    private var busy = false

    // ---- 语音 ----
    private lateinit var voice: VoiceInput
    private var recording = false

    /** 供 Activity 在窗口获得焦点后调用，拉起输入法 */
    fun inputView(): EditText = input

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        voice = VoiceInput(context)
        build()
        rebuildMessages()
        refreshStatus()
    }

    /** 界面销毁时必须调用，否则麦克风不释放 */
    fun release() {
        runCatching { voice.release() }
    }

    /** 录音权限回来后的回调（Activity 转发） */
    fun onMicPermissionResult(granted: Boolean) {
        if (granted) startRecording()
        else setStatus("没有麦克风权限，无法语音输入")
    }

    /** 设备是否支持语音识别 */
    fun isVoiceAvailable(): Boolean = voice.isAvailable()

    // =============================== 构建 ===============================

    private fun build() {
        // ---------- 标题栏 ----------
        val title = TextView(context).apply {
            text = "和大肥鱼说话"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val clearBtn = TextView(context).apply {
            text = "清空"
            textSize = 13f
            setTextColor(Color.parseColor("#B9C7FF"))
            setPadding(dp(12), dp(12), dp(8), dp(12))
            setOnClickListener {
                history.clear()
                rebuildMessages()
            }
        }
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.parseColor("#DDDDDD"))
            setPadding(dp(12), dp(10), dp(16), dp(12))
            setOnClickListener { onClose() }
        }
        val titleBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1565C0"))
            addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(clearBtn)
            addView(closeBtn)
        }

        // ---------- 消息区 ----------
        messageBox = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        scroll = ScrollView(context).apply {
            addView(messageBox)
            setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        // ---------- 状态提示 ----------
        statusText = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#8A8A99"))
            setPadding(dp(16), dp(4), dp(16), dp(2))
            visibility = GONE
        }

        // ---------- 输入区 ----------
        input = EditText(context).apply {
            hint = "说点什么…"
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(6), dp(10), dp(6), dp(10))
            isFocusable = true
            isFocusableInTouchMode = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    doSend(); true
                } else false
            }
        }
        sendBtn = TextView(context).apply {
            text = "↑"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            layoutParams = LayoutParams(dp(38), dp(38)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(6)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#5686FE"))
            }
            setOnClickListener { doSend() }
        }
        // ---------- 语音按钮：按住说话 ----------
        micBtn = TextView(context).apply {
            text = "🎤"
            textSize = 17f
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(dp(38), dp(38)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(4)
            }
            background = micCircle(Color.parseColor("#E8EAF6"))
            isEnabled = voice.isAvailable()
            alpha = if (voice.isAvailable()) 1f else 0.35f
            // 注意：不要在这里调用 attachHoldToTalk()。
            // 那个方法内部会读 micBtn，而此刻 lateinit 的赋值还没完成，
            // 会抛 UninitializedPropertyAccessException 导致 Activity 启动崩溃。
            // 监听器在 build() 末尾统一挂。
        }

        val inputRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(6), dp(4))
            addView(input, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(micBtn)
            addView(sendBtn)
        }

        addView(titleBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll)
        addView(statusText)
        addView(inputRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 所有 View 都已赋值完毕，此刻才挂按住说话的监听
        attachHoldToTalk()
    }

    // =============================== 语音输入（按住说话）===============================

    private fun micCircle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /**
     * 按住说话。
     *
     * [SpeechRecognizer] 没有"只在按住期间录音"的接口，这里用组合模拟：
     *   按下 -> startListening；松开 -> stopListening（立刻结束并取回结果）
     * 配合 partial results，边说边能看到文字。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachHoldToTalk() {
        micBtn.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!voice.isAvailable()) {
                        setStatus("这台设备没有可用的语音识别服务")
                        true
                    } else if (ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        onRequestMicPermission?.invoke()
                            ?: setStatus("没有麦克风权限")
                        true
                    } else {
                        startRecording()
                        true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (recording) stopRecording()
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun startRecording() {
        recording = true
        micBtn.background = micCircle(Color.parseColor("#FFCDD2"))
        micBtn.text = "🔴"
        setStatus("正在听…松开结束")
        voice.start(object : VoiceInput.Listener {
            override fun onReady() {}

            override fun onPartial(text: String) {
                // 中间结果实时填进输入框，用户能看到自己说的话
                input.setText(text)
                input.setSelection(text.length)
            }

            override fun onResult(text: String) {
                input.setText(text)
                input.setSelection(text.length)
                setStatus("")
                if (voiceAutoSend) {
                    // 稍等一下让用户看到识别结果再发送
                    postDelayed({ if (input.text.toString().isNotBlank()) doSend() }, 220L)
                }
            }

            override fun onError(message: String) {
                setStatus(message)
            }

            override fun onFinished() {
                recording = false
                micBtn.background = micCircle(Color.parseColor("#E8EAF6"))
                micBtn.text = "🎤"
            }
        })
    }

    private fun stopRecording() {
        recording = false
        micBtn.background = micCircle(Color.parseColor("#E8EAF6"))
        micBtn.text = "🎤"
        voice.stop()
    }

    private fun setStatus(msg: String) {
        if (msg.isEmpty()) {
            if (!busy) statusText.visibility = GONE
            return
        }
        statusText.text = msg
        statusText.visibility = VISIBLE
    }

    // =============================== 消息渲染 ===============================

    private fun rebuildMessages() {
        messageBox.removeAllViews()
        val list = history.all()
        if (list.isEmpty()) {
            messageBox.addView(bubble("长按我说话吧～问我什么都可以", isUser = false, hint = true))
        } else {
            list.forEach { messageBox.addView(bubble(it.content, isUser = it.role == "user")) }
        }
        scrollToBottom()
    }

    /**
     * 生成一个对话气泡。
     * 用 margin 把气泡推到左/右侧，并限制最大宽度，避免长句铺满整行。
     */
    private fun bubble(text: String, isUser: Boolean, hint: Boolean = false): TextView {
        val tv = TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(if (isUser) Color.WHITE else Color.parseColor("#2C2C38"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(
                    when {
                        isUser -> Color.parseColor("#5686FE")
                        hint -> Color.parseColor("#EDEDF2")
                        else -> Color.WHITE
                    }
                )
            }
        }
        val maxBubbleW = (resources.displayMetrics.widthPixels * 0.90f * 0.78f).toInt()
        tv.layoutParams = MarginLayoutParams(
            maxBubbleW, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            if (isUser) marginStart = dp(40) else marginEnd = dp(40)
        }
        return tv
    }

    private fun scrollToBottom() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refreshStatus() {
        if (busy) {
            statusText.text = "大肥鱼正在想…"
            statusText.visibility = VISIBLE
        } else {
            statusText.visibility = GONE
        }
    }

    // =============================== 发送与回调 ===============================

    private fun doSend() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || busy) return
        input.setText("")

        history.addUser(text)
        rebuildMessages()

        busy = true
        refreshStatus()
        sendBtn.alpha = 0.4f

        onSend(text)
    }

    /** 流式增量：不断更新最后一条助手气泡 */
    fun onDelta(fullTextSoFar: String) {
        val tv = streamingView
        if (tv == null) {
            val created = bubble(fullTextSoFar, isUser = false)
            messageBox.addView(created)
            streamingView = created
        } else {
            tv.text = fullTextSoFar
        }
        scrollToBottom()
    }

    /**
     * 思考过程增量（开启思考时才有）。
     *
     * 单独用灰色斜体气泡显示，样式刻意对齐原 Python 版的「思维链心声」
     * （也是灰色斜体括号）。思考很长，所以加了最大高度限制并只显示尾部。
     */
    fun onThinking(text: String) {
        val tv = thinkingView
        if (tv == null) {
            val created = TextView(context).apply {
                textSize = 12f
                setTextColor(Color.parseColor("#9A9AA8"))
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setMaxLines(6)
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.parseColor("#F0F0F4"))
                }
                layoutParams = MarginLayoutParams(
                    (resources.displayMetrics.widthPixels * 0.90f * 0.82f).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(40) }
            }
            messageBox.addView(created)
            thinkingView = created
        }
        // 思考可能极长（实测一次请求 73KB），只保留末尾 400 字避免内存与排版压力
        val shown = if (text.length > 400) "…" + text.takeLast(400) else text
        tv?.text = "（思考中…）$shown"
        scrollToBottom()
    }

    fun onComplete(fullText: String) {
        busy = false
        sendBtn.alpha = 1f
        history.addAssistant(fullText)
        streamingView = null
        thinkingView = null
        rebuildMessages()
        refreshStatus()
    }

    fun onError(message: String) {
        busy = false
        sendBtn.alpha = 1f
        streamingView = null
        thinkingView = null
        rebuildMessages()
        statusText.text = "⚠ $message"
        statusText.visibility = VISIBLE
        statusText.setOnClickListener { onOpenSettings() }
    }
}
