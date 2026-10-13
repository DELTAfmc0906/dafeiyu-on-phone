package com.dafeiyu.pet.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.dafeiyu.pet.ai.AiClient
import com.dafeiyu.pet.ai.ChatHistoryStore
import com.dafeiyu.pet.data.ConfigRepo

/**
 * AI 对话界面（Ask 模式）。
 *
 * ## 为什么用 Activity 而不是悬浮窗
 *
 * v1.2/v1.2.1 用独立的 `TYPE_APPLICATION_OVERLAY` 悬浮窗做聊天界面，实测两个致命问题：
 *
 * 1. **键盘弹出来但打字进不去**。`dumpsys` 证据：
 *    ```
 *    mCurrentFocus = com.dafeiyu.pet        ← 窗口焦点有
 *    mIsInputViewShown = true               ← 键盘显示了
 *    mServedView = DecorView{...其它Activity}  ← 输入法连的是别的窗口
 *    mServedInputConnection = null          ← 输入连接为空
 *    ```
 *    悬浮窗拿得到窗口焦点，但输入法不会为它建立 InputConnection。
 *
 * 2. **孤儿窗口吃掉全局触摸**。悬浮窗不归 Activity 生命周期管，
 *    一旦没被正确 removeView 就会残留，持续拦截触摸 ——
 *    表现为整个手机点不动、只能重启。
 *
 * Android 原生支持"透明浮层 Activity"（`Theme.Dialog` + 半透明背景），
 * 既保留悬浮观感，又有正常的窗口令牌和生命周期。
 */
// 必须继承 AppCompatActivity（而不是 android.app.Activity）：
// registerForActivityResult 是 ComponentActivity 的能力，普通 Activity 没有。
class ChatActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_FOCUS_INPUT = "focus_input"

        fun start(context: Context, focusInput: Boolean = true) {
            context.startActivity(
                Intent(context, ChatActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_FOCUS_INPUT, focusInput)
            )
        }
    }

    private lateinit var config: ConfigRepo
    private lateinit var history: ChatHistoryStore
    private var panel: AiChatPanelView? = null

    /** M3 颜色角色（v1.3） */
    private val m3 by lazy { M3ViewFactory(this) }

    private val aiCallback = object : AiClient.Callback {
        override fun onDelta(text: String) {
            panel?.onDelta(text)
        }

        override fun onThinking(text: String) {
            panel?.onThinking(text)
        }

        override fun onComplete(text: String) {
            panel?.onComplete(text)
            // 让桌宠也用气泡说一句（转发给服务）。
            // 对话界面改成 Activity 后不能直接操作引擎，所以走 Intent。
            PetService.say(this@ChatActivity, text)
        }

        override fun onError(message: String) {
            panel?.onError(message)
        }
    }

    /** 请求录音权限的结果回调 */
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        panel?.onMicPermissionResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 由 Activity 自己维护这个标记，而不是让服务设置。
        // 好处是生命周期天然对齐：Activity 被系统销毁时标记一定跟着复位，
        // 不会像悬浮窗那样留下"孤儿"状态把全局触摸吃掉。
        PetRuntime.chatVisible = true
        config = ConfigRepo(this)
        history = ChatHistoryStore(config)

        // 做成"贴底浮层"而不是全屏 Activity：
        //   - 背景透明 + 底部对齐 + 宽 94%，观感与之前的悬浮面板一致
        //   - 无标题栏，不占系统栏
        // 窗口背景保持透明，面板本体（AiChatPanelView）自带
        // surfaceContainerLow 底色 + 圆角，这样浮层是一块干净的卡片。
        //
        // 注意：**面板本体必须不透明**。之前深色模式下背景偏透，
        // 后面的设置页会隐约透上来，观感很脏。
        window.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.32f }
            setLayout(
                (resources.displayMetrics.widthPixels * 0.94f).toInt(),
                (resources.displayMetrics.heightPixels * 0.62f).toInt()
            )
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            // 键盘弹出时把窗口顶上去，不要遮住输入框
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            )
        }

        // 容器也铺一层不透明底色并加 M3 的大圆角（L 档 16dp）。
        // 双保险：即使面板内部某块背景没设色，也不会透出后面的界面。
        val rootHolder = FrameLayout(this).apply {
            setBackgroundColor(m3.surfaceContainerLow)
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        rootHolder.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = m3.dpf(16)
            setColor(m3.surfaceContainerLow)
        }
        val view = AiChatPanelView(
            context = this,
            history = history,
            onSend = { text -> sendToAi(text) },
            onOpenSettings = {
                // ⚙ 齿轮直达"AI 与账号"页。
                // 原来跳的是主设置页，而 AI 配置在那页的最深处，
                // 用户得手动滑很久 —— 这也是"设置被吃掉了"的观感来源。
                AiSettingsActivity.start(this)
                finish()
            },
            onClose = { finish() },
            voiceAutoSend = config.voiceAutoSend,
            voicePreferOffline = config.voicePreferOffline,
            onRequestMicPermission = {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        )
        panel = view
        rootHolder.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(rootHolder)

        // 没配 Key 时直接给出可点提示
        if (!config.isAiConfigured()) {
            view.onError("还没设置 API Key，点这里去设置")
        }

        if (intent?.getBooleanExtra(EXTRA_FOCUS_INPUT, true) == true) {
            focusInputSoon()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_FOCUS_INPUT, true) == true) {
            focusInputSoon()
        }
    }

    /**
     * 拉起输入法。
     *
     * Activity 里的文本框弹键盘比悬浮窗可靠得多，但仍要注意时机：
     * 必须在窗口真正获得焦点之后再调，否则 showSoftInput 会静默失败。
     * 所以监听一次窗口焦点变化，拿到焦点后再弹。
     */
    private fun focusInputSoon() {
        val et = panel?.inputView() ?: return
        et.requestFocus()
        window.decorView.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            runCatching { imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT) }
            // 兜底：若第一次没起来，等窗口焦点稳定后再试一次
            window.decorView.postDelayed({
                if (!isFinishing && !imm.isActive(et)) {
                    runCatching { imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT) }
                }
            }, 200L)
        }
    }

    private fun sendToAi(userText: String) {
        if (!config.isAiConfigured()) {
            panel?.onError("请先设置 API Key")
            return
        }
        // 历史快照要排除刚加进去的这条用户消息，由 AiClient 单独传
        val snapshot = history.forRequest().dropLast(1)
        AiClient.ask(
            baseUrl = config.apiBaseUrl,
            apiKey = config.apiKey,
            model = config.apiModel,
            history = snapshot,
            userText = userText,
            enableThinking = config.aiThinking,
            limit = config.aiLengthLimit,
            callback = aiCallback
        )
    }

    /**
     * 对话界面离开前台时立刻复位标记。
     *
     * 放进 onPause 而不是 onDestroy 是必须的：
     * 用户按返回键时 Activity 通常只走到 onStop（实例还留着以便复用），
     * 若只在 onDestroy 复位，标记会一直是 true ——
     * 结果鱼永久处于"触摸穿透"，用户回桌面后怎么点都没反应。
     */
    override fun onPause() {
        PetRuntime.chatVisible = false
        // 切后台时把语音浮层和录音一起收掉。
        // 悬浮窗不随 Activity 隐藏，漏收就会停在屏幕上（上一版的 bug）。
        panel?.cancelVoice()
        PetService.refreshTouchable(this)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        PetRuntime.chatVisible = true
        PetService.refreshTouchable(this)
    }

    override fun onDestroy() {
        PetRuntime.chatVisible = false
        // 必须释放麦克风，否则会一直占着录音设备
        panel?.release()
        panel = null
        super.onDestroy()
    }

    /** 气泡台词由桌宠服务负责；这里只做对话 UI */
}
