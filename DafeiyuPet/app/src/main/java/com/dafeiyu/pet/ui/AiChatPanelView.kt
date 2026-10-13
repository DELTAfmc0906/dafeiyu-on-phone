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
    /** 是否优先用设备端离线识别（解决"卡/断连"） */
    private val voicePreferOffline: Boolean = true,
    /** 请求录音权限；由 Activity 转发 */
    private val onRequestMicPermission: (() -> Unit)? = null
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    /**
     * M3 颜色角色解析器（v1.3）。
     *
     * 改造前这里到处是 `Color.parseColor("#5686FE")` 这类硬编码 ——
     * 深色模式下会瞎眼，也没法跟随动态取色。
     * 现在一律引用 M3 语义角色，深浅主题与 Material You 取色都自动适配。
     */
    private val m3 by lazy { M3ViewFactory(context) }

    /** dp 的 Float 版本（GradientDrawable 的 cornerRadii 要 FloatArray） */
    private fun dpf(v: Int) = v * density

    private lateinit var messageBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var statusText: TextView

    /**
     * 「按住 说话」大椭圆条（v1.3.1）。
     *
     * 上一版是个小圆话筒图标，形态跑偏了 —— 官方 App 与微信都是
     * 底部一条宽大的椭圆形按钮。现在按那个形态重做：
     * 空闲态显示「按住 说话」，按住后原地变成录音态（波形 + 松开发送）。
     */
    private lateinit var holdBtn: LinearLayout
    private lateinit var holdLabel: TextView
    private lateinit var holdWave: VoiceOverlay.WaveformView

    private var streamingView: TextView? = null

    /** 思考过程气泡（只在开启思考时出现） */
    private var thinkingView: TextView? = null

    private var busy = false

    // ---- 语音 ----
    private lateinit var voice: VoiceInput
    private var recording = false
    private var voiceOverlay: VoiceOverlay? = null
    private var holdStartY = 0f

    /** 当前是否处于"上滑取消"态；只在变化时重设按钮外观 */
    private var holdCancelling = false

    /** 最近一次真实音量（0..1），用于驱动按钮上的波形幅度 */
    private var realLevel = 0f

    /** 松手那一刻是否处于"上滑取消"态，用于决定要不要报错 */
    private var wasCancelling = false

    /**
     * 识别过程中攒下的错误消息。
     *
     * 识别器出错时手指可能还按着，此时**不能**动 UI（否则按钮会在录音中途
     * 突然变回空闲态）。先存起来，等松手后再提示。
     */
    private var pendingError: String? = null

    /** 识别器是否已结束（仅用于状态记录，不驱动 UI） */
    private var voiceEnded = false

    /** 供 Activity 在窗口获得焦点后调用，拉起输入法 */
    fun inputView(): EditText = input

    init {
        orientation = VERTICAL
        // M3：浮层本体用 surfaceContainerLow，靠色调与背后的内容分层
        setBackgroundColor(m3.surfaceContainerLow)
        voice = VoiceInput(context)
        // 优先离线识别：音频不出本机，没有网络环节，因此不会断连、延迟也低。
        // 这是解决"连接很卡、经常断连"的关键开关。
        voice.preferOnDevice = voicePreferOffline
        build()
        rebuildMessages()
        refreshStatus()
    }

    /** 界面销毁时必须调用，否则麦克风不释放 */
    fun release() {
        runCatching { voice.release() }
        voiceOverlay?.dismiss()
        voiceOverlay = null
    }

    /**
     * 强制中止当前录音并收回浮层（切后台、Activity 暂停时调用）。
     *
     * 悬浮窗不会随 Activity 隐藏而消失，漏收就会一直挂在屏幕上 ——
     * 这正是上一版"松手后浮层不消失、只能杀 App"的成因之一。
     */
    fun cancelVoice() {
        recording = false
        stopWaveDrive()
        voiceOverlay?.dismiss()
        voiceOverlay = null
        runCatching { voice.release() }
        applyHoldButtonState()
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
        // M3 顶部栏：用 primaryContainer 底 + onPrimaryContainer 文字，
        // 比原来的深蓝底白字更柔和，也更符合 M3 的"容器色"用法。
        val title = TextView(context).apply {
            text = "AI 对话"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(m3.onPrimaryContainer)
            setPadding(dp(20), dp(14), dp(16), dp(14))
        }
        val clearBtn = TextView(context).apply {
            text = "清空"
            textSize = 13f
            setTextColor(m3.primary)
            setPadding(dp(12), dp(14), dp(8), dp(14))
            setOnClickListener {
                history.clear()
                rebuildMessages()
            }
        }
        // 齿轮：直达 AI 配置。
        // v1.3 把设置页卡片化后，AI 配置被埋在滚动深处，用户反馈"设置被吃掉了"。
        // 这里放个常驻入口，一点就滚到 AI 区并聚焦 Key 输入框。
        val gearBtn = TextView(context).apply {
            text = "⚙"
            textSize = 18f
            setTextColor(m3.onPrimaryContainer)
            setPadding(dp(12), dp(12), dp(4), dp(14))
            contentDescription = "AI 设置"
            setOnClickListener { onOpenSettings() }
        }
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(m3.onPrimaryContainer)
            setPadding(dp(12), dp(12), dp(20), dp(14))
            setOnClickListener { onClose() }
        }
        val titleBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(m3.primaryContainer)
            addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(clearBtn)
            addView(gearBtn)
            addView(closeBtn)
        }

        // ---------- 消息区 ----------
        messageBox = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        scroll = ScrollView(context).apply {
            addView(messageBox)
            setBackgroundColor(m3.surface)
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        // ---------- 状态提示 ----------
        statusText = TextView(context).apply {
            textSize = 12f
            setTextColor(m3.onSurfaceVariant)
            setPadding(dp(20), dp(4), dp(20), dp(4))
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
            setTextColor(m3.onSurface)
            setHintTextColor(m3.onSurfaceVariant)
            setPadding(dp(16), dp(12), dp(8), dp(12))
            isFocusable = true
            isFocusableInTouchMode = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    doSend(); true
                } else false
            }
        }
        // 发送按钮：M3 的 FAB 观感 —— 主色底、圆形、白字
        sendBtn = TextView(context).apply {
            text = "↑"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(m3.role(com.google.android.material.R.attr.colorOnPrimary))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(m3.primary)
            }
            setOnClickListener { doSend() }
        }
        // ---------- 底部「按住 说话」椭圆条（v1.3.1 重做）----------
        //
        // 对照官方 App / 微信的形态：一条**宽大的椭圆按钮**占据整行宽度，
        // 而不是一个小圆话筒图标。空闲态显示「按住 说话」，
        // 按住后原地变成录音态：主色填充 + 实时波形 + 「松开发送」。
        holdWave = VoiceOverlay.WaveformView(context).apply {
            setColor(m3.role(com.google.android.material.R.attr.colorOnPrimary))
            visibility = GONE
        }
        holdLabel = TextView(context).apply {
            text = "按住 说话"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(m3.role(com.google.android.material.R.attr.colorOnPrimary))
        }
        holdBtn = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            // 椭圆：圆角 = 高度的一半
            background = GradientDrawable().apply {
                cornerRadius = dpf(26)
                setColor(m3.secondaryContainer)
            }
            addView(holdWave, LinearLayout.LayoutParams(dp(88), dp(26)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(8)
            })
            addView(holdLabel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            /*
             * 注意：**永远不要禁用这个按钮**。
             *
             * 上一版写成 `isEnabled = voice.isAvailable()`，本意是"设备不支持就置灰"。
             * 但 Android 在 View `isEnabled == false` 时**不会把触摸事件派发给
             * OnTouchListener**，而 uiautomator 仍会报 enabled=true ——
             * 结果是按钮看起来正常、按下去毫无反应，极难排查。
             *
             * 正确做法：按钮始终可点，不支持时在按下那一刻给出明确提示。
             */
        }

        // 文本输入行
        val inputRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(m3.surfaceContainerHigh)
            setPadding(dp(8), dp(4), dp(6), dp(4))
        }

        // 语音行（整行一个椭圆条）
        val voiceRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(m3.surfaceContainerHigh)
            setPadding(0, 0, 0, dp(8))
        }

        val inputDivider = View(context).apply {
            setBackgroundColor(m3.outlineVariant)
        }

        /*
         * ★ 布局陷阱（v1.3.1 修）
         *
         * `parent.addView(child)` 这个**不带 LayoutParams** 的重载，
         * 会用父容器的默认参数**覆盖** child 自己设好的 `layoutParams`。
         *
         * 原来我先把 holdBtn.layoutParams 设成 MATCH_PARENT，随后又写
         * `voiceRow.addView(holdBtn)` —— 宽高被换成 WRAP_CONTENT，
         * 于是"按住 说话"椭圆条缩成 166px 宽的一小块文字，
         * 完全不是微信/官方那种整行椭圆。
         *
         * 实测证据（uiautomator）：bounds=[457,2243][623,2301]，宽仅 166px。
         *
         * 现在一律用带 LayoutParams 的 addView 重载。
         */
        addView(titleBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll)
        addView(statusText)
        addView(inputDivider, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(sendBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginEnd = dp(8)
        })
        addView(inputRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        voiceRow.addView(holdBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        })
        addView(voiceRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 所有 View 都已赋值完毕，此刻才挂按住说话的监听
        attachHoldToTalk()
    }

    // =============================== 语音输入（按住说话）===============================

    /** 把椭圆条的圆角重设一遍（背景被替换后圆角会丢） */
    private fun ovalBackground(color: Int) = GradientDrawable().apply {
        cornerRadius = dpf(26)
        setColor(color)
    }

    /**
     * 按住说话。
     *
     * 对照官方 App / 微信的交互（v1.3.1 重做）：
     *   按下   -> 椭圆条原地变录音态（主色 + 波形 + 「松开发送」），同时弹浮层
     *   上滑   -> 进入取消态，浮层与按钮一起变红
     *   松开   -> **立即**收回浮层并恢复按钮，再让识别收尾
     *
     * 关键点：收回浮层由**触摸事件**负责，不等 `SpeechRecognizer` 回调。
     * 上一版等回调，而回调不保证会来，导致浮层挂死到杀进程。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachHoldToTalk() {
        holdBtn.setOnTouchListener { v, e ->
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
                        holdStartY = e.rawY
                        startRecording()
                        true
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (recording) {
                        /*
                         * 上滑超过阈值即进入取消态。
                         *
                         * **只在与上一次不同时才重设外观。**
                         * 这里原本每收到一个 MOVE 就无条件调用
                         * `applyHoldButtonState(cancelling = ...)`，
                         * 而该重载的 recording 默认 false —— 一次 2 秒的长按
                         * 会产生近百个 MOVE 事件，于是按钮被每帧刷回"按住 说话"，
                         * 看起来像根本没进入录音态。
                         */
                        val dy = e.rawY - holdStartY
                        val nowCancelling = dy < -dp(60)
                        voiceOverlay?.onDrag(dy)
                        if (nowCancelling != holdCancelling) {
                            holdCancelling = nowCancelling
                            applyHoldButtonState(
                                recording = !nowCancelling,
                                cancelling = nowCancelling
                            )
                        }
                    }
                    true
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

    /**
     * 切换椭圆条外观。
     * 空闲：secondaryContainer 底 + onSecondaryContainer 字 + 「按住 说话」
     * 录音：primary 底 + onPrimary 字白 + 波形可见 + 「松开发送」
     * 取消：error 底 + 「松开 取消」
     */
    private fun applyHoldButtonState(recording: Boolean = false, cancelling: Boolean = false) {
        when {
            cancelling -> {
                holdBtn.background = ovalBackground(m3.error)
                holdLabel.text = "松开 取消"
                holdLabel.setTextColor(m3.role(com.google.android.material.R.attr.colorOnError))
                holdWave.setColor(m3.role(com.google.android.material.R.attr.colorOnError))
                holdWave.visibility = VISIBLE
            }
            recording -> {
                holdBtn.background = ovalBackground(m3.primary)
                holdLabel.text = "松开发送"
                holdLabel.setTextColor(m3.role(com.google.android.material.R.attr.colorOnPrimary))
                holdWave.setColor(m3.role(com.google.android.material.R.attr.colorOnPrimary))
                holdWave.visibility = VISIBLE
            }
            else -> {
                holdBtn.background = ovalBackground(m3.secondaryContainer)
                holdLabel.text = "按住 说话"
                holdLabel.setTextColor(m3.onSecondaryContainer)
                holdWave.visibility = GONE
            }
        }
    }

    private fun startRecording() {
        recording = true
        holdCancelling = false
        setStatus("")
        applyHoldButtonState(recording = true)

        // 弹出浮层（波形 + 实时文字 + 上滑取消提示）
        voiceOverlay = VoiceOverlay(context).also { it.show() }
        startWaveDrive()

        voice.start(object : VoiceInput.Listener {
            override fun onReady() {
                voiceOverlay?.setText("请开始说话…")
            }

            override fun onPartial(text: String) {
                // 边说边出字：浮层实时显示，同时填进输入框
                voiceOverlay?.setText(text)
                input.setText(text)
                input.setSelection(text.length)
            }

            override fun onLevel(level: Float) {
                // 同一个音量同时驱动浮层波形和按钮上的小波形
                voiceOverlay?.setLevel(level)
                realLevel = level
            }

            override fun onResult(text: String) {
                input.setText(text)
                input.setSelection(text.length)
                setStatus("")
                if (voiceAutoSend) {
                    postDelayed({ if (input.text.toString().isNotBlank()) doSend() }, 260L)
                }
            }

            override fun onError(message: String) {
                /*
                 * **不要把错误当成"录音结束"**。
                 *
                 * 识别器出错时（模拟器没有识别服务、网络不通……）这个回调会
                 * 立刻触发，而此刻用户的手指**还按在按钮上**。
                 * 早期版本在这里直接复位 UI，于是出现"浮层还在、按钮已经变回
                 * 空闲态"的错乱画面。
                 *
                 * 正确做法：把消息存起来，等手指松开时再提示。
                 * UI 状态的唯一权威是**手指**，不是识别器。
                 */
                pendingError = if (wasCancelling) null else message
            }

            override fun onStatus(text: String) {
                // 显示"离线识别/在线识别/正在重试"，用户能看出卡在哪
                voiceOverlay?.setStatus(text)
            }

            override fun onFinished() {
                // 同样只记录"识别已结束"，不动 UI —— 收尾统一走 stopRecording()
                voiceEnded = true
            }
        })
    }

    /** 松手：立即收回浮层 + 恢复按钮，然后让识别收尾 */
    private fun stopRecording() {
        recording = false
        stopWaveDrive()
        wasCancelling = voiceOverlay?.isCancelling == true

        val overlay = voiceOverlay

        if (wasCancelling) {
            // 取消：丢掉这次识别，浮层留着显示「已取消」提示后自动消失
            overlay?.release()
            voice.release()
            overlay?.dismissAfterHint()
            voiceOverlay = null
            applyHoldButtonState()
            setStatus("已取消")
            pendingError = null
            return
        }

        overlay?.release()
        // ★ 关键修复：手指一松就立即收回浮层，不等任何识别回调。
        // 识别回调不保证会来（服务异常时压根不来），
        // 上一版等回调，浮层就挂死到杀进程。
        overlay?.dismiss()
        voiceOverlay = null
        applyHoldButtonState()
        voice.stop()

        // 识别过程中攒下的错误，现在才提示（此时用户已经松手，不会再干扰录音态）
        pendingError?.let {
            setStatus(it)
            pendingError = null
        }
    }

    /** 兜底复位（浮层超时等异常路径） */
    private fun finishRecordingUi() {
        recording = false
        stopWaveDrive()
        voiceOverlay?.dismiss()   // 幂等兜底
        voiceOverlay = null
        applyHoldButtonState()
    }

    private fun setStatus(msg: String) {
        if (msg.isEmpty()) {
            if (!busy) statusText.visibility = GONE
            return
        }
        statusText.text = msg
        statusText.visibility = VISIBLE
    }

    /**
     * 波形动画驱动（30fps）。
     *
     * 同时驱动浮层大波形和按钮上的小波形。
     * 不能只依赖 `onRmsChanged`：真机静音段它不回调、模拟器无麦克风时
     * 一次都不回调，波形会完全不动 —— 这正是用户反馈的"极为平静"。
     */
    private val waveDrive = object : Runnable {
        private var phase = 0.0
        override fun run() {
            if (!recording) return
            phase += 0.42
            val base = 0.30 + realLevel * 1.4
            val v = (base * (0.60 + 0.40 * kotlin.math.sin(phase))).toFloat()
            voiceOverlay?.pushWave(v)
            holdWave.pushLevel(v)
            postDelayed(this, 32L)
        }
    }

    private fun startWaveDrive() {
        removeCallbacks(waveDrive)
        post(waveDrive)
    }

    private fun stopWaveDrive() {
        removeCallbacks(waveDrive)
        realLevel = 0f
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
     *
     * M3 的颜色用法（这也是聊天界面最标准的映射）：
     *   - 用户消息：primary 底 + onPrimary 文字 —— 主色代表"我说的"
     *   - 助手回复：surfaceContainerHighest 底 + onSurface 文字 —— 中性表面
     *   - 提示语：surfaceContainer 底 + onSurfaceVariant 文字，视觉更弱
     *
     * 圆角用 M3 的 L 档（16dp），并在靠说话人一侧收小到 4dp（XS 档），
     * 形成"指向说话人"的方向感 —— 这是聊天气泡的通用做法。
     */
    private fun bubble(text: String, isUser: Boolean, hint: Boolean = false): TextView {
        val tv = TextView(context).apply {
            this.text = text
            textSize = 15f
            setLineSpacing(0f, 1.3f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextColor(
                when {
                    isUser -> m3.role(com.google.android.material.R.attr.colorOnPrimary)
                    hint -> m3.onSurfaceVariant
                    else -> m3.onSurface
                }
            )
            val bg = GradientDrawable()
            bg.setColor(
                when {
                    isUser -> m3.primary
                    hint -> m3.surfaceContainer
                    else -> m3.surfaceContainerHigh
                }
            )
            // 靠说话人一侧的角小一些，形成方向感
            bg.cornerRadii = if (isUser) {
                floatArrayOf(
                    dpf(16), dpf(16),   // 左上、右上
                    dpf(4), dpf(4),     // 右下（靠说话人侧收小）
                    dpf(16), dpf(16),   // 左下
                    dpf(16), dpf(16)
                )
            } else {
                floatArrayOf(
                    dpf(16), dpf(16),
                    dpf(16), dpf(16),
                    dpf(16), dpf(16),
                    dpf(4), dpf(4)      // 左下（靠说话人侧收小）
                )
            }
            background = bg
        }
        val maxBubbleW = (resources.displayMetrics.widthPixels * 0.90f * 0.78f).toInt()
        tv.layoutParams = MarginLayoutParams(
            maxBubbleW, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            if (isUser) {
                marginStart = dp(40)
                bottomMargin = dp(8)
            } else {
                marginEnd = dp(40)
                bottomMargin = dp(8)
            }
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
     * 用 M3 的 `surfaceContainer` 底 + `onSurfaceVariant` 文字，
     * 视觉上比正文气泡更弱 —— 正确表达"这是辅助内容，不是答案本身"。
     * 斜体保留，对应原 Python 版「思维链心声」的呈现。
     */
    fun onThinking(text: String) {
        val tv = thinkingView
        if (tv == null) {
            val created = TextView(context).apply {
                textSize = 12f
                setTextColor(m3.onSurfaceVariant)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                setLineSpacing(0f, 1.3f)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setMaxLines(6)
                background = GradientDrawable().apply {
                    cornerRadius = dpf(12)
                    setColor(m3.surfaceContainer)
                }
                layoutParams = MarginLayoutParams(
                    (resources.displayMetrics.widthPixels * 0.90f * 0.82f).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(40)
                    bottomMargin = dp(8)
                }
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
