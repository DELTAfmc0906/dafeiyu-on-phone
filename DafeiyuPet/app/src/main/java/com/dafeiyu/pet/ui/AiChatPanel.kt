package com.dafeiyu.pet.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dafeiyu.pet.ai.AiClient
import com.dafeiyu.pet.ai.ChatHistoryStore

/**
 * AI 对话悬浮面板。
 *
 * v1.2 新增。长按鱼身弹出，对应原 Python 版的 `ChatDialog`（那个是 420x56 的单行输入框）。
 * Android 上屏幕窄，改成**底部聊天面板**：上方是对话记录，下方是输入框。
 *
 * ## 焦点与输入法（手机上最容易踩的坑）
 * 桌宠主悬浮窗是 `FLAG_NOT_FOCUSABLE`（不然会抢走下面应用的触摸/焦点）。
 * 但输入框必须能拿到焦点，否则**软键盘弹不出来、也没法打字**。
 * 所以这个面板是**独立的第二个悬浮窗**，而且：
 *   1. 不带 `FLAG_NOT_FOCUSABLE` —— 允许获取焦点
 *   2. 带 `FLAG_ALT_FOCUSABLE_IM` —— 有焦点但不挡住输入法
 *   3. softInputMode 设 `ADJUST_RESIZE` —— 键盘弹出时面板上移，输入框不被遮住
 * 关闭面板时会主动隐藏输入法并把焦点交还。
 */
class AiChatPanel(
    private val context: Context,
    private val windowManager: WindowManager,
    private val history: ChatHistoryStore,
    private val onSend: (String) -> Unit,
    private val onOpenSettings: () -> Unit
) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var root: View? = null
    private var messageBox: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var input: EditText? = null
    private var sendBtn: TextView? = null
    private var statusText: TextView? = null

    /** 正在等待回复时为 true，此时禁止再次发送 */
    private var busy = false
    private var streamingView: TextView? = null

    val isShowing: Boolean get() = root != null

    // =============================== 显示 / 隐藏 ===============================

    fun show() {
        if (root != null) {
            focusInput()
            return
        }

        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels

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
            setOnClickListener { hide() }
        }
        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1565C0"))
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(clearBtn)
            addView(closeBtn)
        }

        // ---------- 消息区 ----------
        messageBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        scroll = ScrollView(context).apply {
            addView(messageBox)
            setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        // ---------- 状态提示（错误 / 思考中）----------
        statusText = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#8A8A99"))
            setPadding(dp(16), dp(4), dp(16), dp(2))
            visibility = View.GONE
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
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    doSend()
                    true
                } else false
            }
        }
        sendBtn = TextView(context).apply {
            text = "↑"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(6)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#5686FE"))
            }
            setOnClickListener { doSend() }
        }
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(6), dp(4))
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendBtn)
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(titleBar, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(scroll)
            addView(statusText)
            addView(inputRow, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val wrapper = FrameLayout(context).apply {
            setPadding(dp(8), 0, dp(8), dp(8))
            addView(column, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = dp(6)
            })
        }

        val panelH = (screenH * 0.62f).toInt()
        val lp = WindowManager.LayoutParams(
            (screenW * 0.94f).toInt(),
            panelH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 注意：这里故意不加 FLAG_NOT_FOCUSABLE —— 输入框需要焦点才能弹出键盘
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        runCatching { windowManager.addView(wrapper, lp) }
        root = wrapper

        rebuildMessages()
        refreshStatus()
        focusInput()
    }

    fun hide() {
        val v = root ?: return
        hideKeyboard()
        runCatching { windowManager.removeView(v) }
        root = null
        messageBox = null
        scroll = null
        input = null
        sendBtn = null
        statusText = null
        streamingView = null
        busy = false
    }

    // =============================== 消息渲染 ===============================

    private fun rebuildMessages() {
        val box = messageBox ?: return
        box.removeAllViews()
        val list = history.all()
        if (list.isEmpty()) {
            box.addView(bubble("长按我说话吧～问我什么都可以", isUser = false, hint = true))
        } else {
            list.forEach { box.addView(bubble(it.content, isUser = it.role == "user")) }
        }
        scrollToBottom()
    }

    /**
     * 生成一个对话气泡。
     *
     * 直接返回 TextView（不套一层容器），因为：
     *   1. 流式更新时需要直接改 text，拿 TextView 最省事
     *   2. 用 ViewGroup.MarginLayoutParams 就能控制左右对齐，不必额外包一层 LinearLayout
     * 用户消息靠右、助手消息靠左，靠 gravity + margin 实现。
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
        // 用 margin 把气泡推到左/右侧，并限制最大宽度避免长句铺满整行
        val maxBubbleW = (context.resources.displayMetrics.widthPixels * 0.94f * 0.78f).toInt()
        val lp = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.width = maxBubbleW
        if (isUser) {
            lp.marginStart = dp(40)
        } else {
            lp.marginEnd = dp(40)
        }
        tv.layoutParams = lp
        return tv
    }

    private fun scrollToBottom() {
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refreshStatus() {
        val s = statusText ?: return
        if (busy) {
            s.text = "大肥鱼正在想…"
            s.visibility = View.VISIBLE
        } else {
            s.visibility = View.GONE
        }
    }

    // =============================== 发送与回调 ===============================

    private fun doSend() {
        val et = input ?: return
        val text = et.text.toString().trim()
        if (text.isEmpty() || busy) return
        et.setText("")

        // 用户消息立刻上屏
        history.addUser(text)
        rebuildMessages()

        busy = true
        refreshStatus()
        sendBtn?.alpha = 0.4f

        onSend(text)
    }

    /** 流式增量：不断更新最后一条助手气泡 */
    fun onDelta(fullTextSoFar: String) {
        val box = messageBox ?: return
        val tv = streamingView
        if (tv == null) {
            val created = bubble(fullTextSoFar, isUser = false)
            box.addView(created)
            streamingView = created
        } else {
            tv.text = fullTextSoFar
        }
        scrollToBottom()
    }

    fun onComplete(fullText: String) {
        busy = false
        sendBtn?.alpha = 1f
        history.addAssistant(fullText)
        streamingView = null
        // 用落库后的内容重建一次，保证与历史一致
        rebuildMessages()
        refreshStatus()
    }

    fun onError(message: String) {
        busy = false
        sendBtn?.alpha = 1f
        streamingView = null
        rebuildMessages()
        val s = statusText ?: return
        s.text = "⚠ $message"
        s.visibility = View.VISIBLE
        // 附带一个"去设置"入口，Key 没配好时用户知道去哪
        s.setOnClickListener { hide(); onOpenSettings() }
    }

    // =============================== 输入法 ===============================

    private fun focusInput() {
        val et = input ?: return
        et.requestFocus()
        et.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        val et = input ?: return
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(et.windowToken, 0)
        }
        et.clearFocus()
    }
}
