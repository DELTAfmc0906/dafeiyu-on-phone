package com.dafeiyu.pet.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dafeiyu.pet.R
import com.dafeiyu.pet.ai.AiClient
import com.dafeiyu.pet.ai.ChatHistoryStore
import com.dafeiyu.pet.data.ApiPreset
import com.dafeiyu.pet.data.ConfigRepo
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * AI 设置页（v1.3.1 新增）。
 *
 * ## 为什么要独立成一页
 *
 * 用户反馈："改了 md3 UI 之后甚至连 API 设置都被吃掉了"。
 *
 * 确实 —— v1.3 把设置页全部卡片化后，AI 配置被埋在一长串卡片的最深处，
 * 要滑过权限、模式、大小、开关、城市才能看到。功能没丢，但**入口丢了**。
 *
 * 现在改成两层：
 *   - 对话窗口标题栏的 **⚙ 齿轮** → 直接打开这里（最常用的路径）
 *   - 主设置页只留一个「AI 与账号」入口卡片 → 点进来
 *
 * 这样既找回入口，也让设置页不必再塞满所有细节。
 *
 * ## 关于"登录 DeepSeek 账号"
 *
 * 用户希望支持登录自有 DeepSeek 账号。**目前做不到，原因是官方没有开放接口**：
 * DeepSeek 开放平台只提供 API Key（在 platform.deepseek.com 自行申请），
 * 没有面向第三方应用的 OAuth2 授权流程。App 里的账号体系是私有的，
 * 第三方只能靠逆向，既不稳定也不合规。
 *
 * 所以这里做的是**"凭据管理"**：以账号条目的形式管理 API Key，
 * 并在界面上预留 Agent 模式的开关位。等官方真的开放 OAuth，
 * 替换掉凭据来源即可，上层结构不用动。
 */
class AiSettingsActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, AiSettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private lateinit var config: ConfigRepo
    private val m3 by lazy { M3ViewFactory(this) }

    private var keyEdit: TextInputEditText? = null
    private var baseEdit: TextInputEditText? = null
    private var modelEdit: TextInputEditText? = null
    private var baseLayout: TextInputLayout? = null
    private var modelLayout: TextInputLayout? = null
    private var hintText: TextView? = null
    private var historyText: TextView? = null
    private var accountStatus: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = ConfigRepo(this)
        setContentView(buildUi())
        applyPresetToFields()
    }

    override fun onResume() {
        super.onResume()
        PetRuntime.settingsVisible = true
        if (PetRuntime.isRunning) PetService.refreshTouchable(this)
        refreshAccount()
    }

    override fun onPause() {
        PetRuntime.settingsVisible = false
        if (PetRuntime.isRunning) PetService.refreshTouchable(this)
        super.onPause()
    }

    // =============================== 界面 ===============================

    private fun buildUi(): View {
        val root = m3.pageRoot()

        // 标题
        root.addView(TextView(this).apply {
            text = "AI 与账号"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(m3.onSurface)
            setPadding(0, m3.dp(16), 0, m3.dp(4))
        })
        root.addView(m3.hint("配置对话使用的模型服务").apply { setPadding(0, 0, 0, m3.dp(12)) })

        root.addView(buildAccountCard())
        root.addView(buildServiceCard())
        root.addView(buildBehaviorCard())
        root.addView(buildAgentCard())

        return ScrollView(this).apply {
            addView(root)
            setBackgroundColor(m3.surface)
        }
    }

    /**
     * 账号卡片。
     *
     * 说明清楚"为什么不能直接登录 DeepSeek 账号"，避免用户以为是漏做了。
     */
    private fun buildAccountCard(): MaterialCardView {
        val card = m3.card()
        val content = m3.cardContent()
        content.addView(m3.sectionTitle("账号").apply { setPadding(0, 0, 0, m3.dp(6)) })

        accountStatus = TextView(this).apply {
            textSize = 15f
            setTextColor(m3.onSurface)
        }
        content.addView(accountStatus)

        content.addView(m3.hint(
            "说明：DeepSeek 官方**没有开放第三方账号登录**（OAuth），" +
                    "只有 API Key 这一种凭据。所以这里用「API Key 即账号」的方式管理，" +
                    "在 platform.deepseek.com 申请后填到下面即可。"
        ).apply { setPadding(0, m3.dp(8), 0, 0) })

        content.addView(
            m3.button("去 DeepSeek 申请 API Key", M3ViewFactory.Variant.OUTLINED).apply {
                setOnClickListener {
                    runCatching {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://platform.deepseek.com/api_keys")
                            )
                        )
                    }.onFailure { toast("打不开浏览器") }
                }
            }
        )

        card.addView(content)
        return card
    }

    /** 服务与凭据 */
    private fun buildServiceCard(): MaterialCardView {
        val card = m3.card()
        val content = m3.cardContent()
        content.addView(m3.sectionTitle("服务与凭据").apply { setPadding(0, 0, 0, m3.dp(6)) })

        content.addView(m3.switchRow("启用 AI 对话", config.aiEnabled).also { row ->
            (row.tag as com.google.android.material.materialswitch.MaterialSwitch)
                .setOnCheckedChangeListener { _, on ->
                    config.aiEnabled = on
                    PetService.reload(this)
                }
        })

        // 预设
        content.addView(m3.fieldLabel(getString(R.string.ai_preset)))
        val presets = ApiPreset.entries.toList()
        var suppressPreset = true
        val presetSpinner = android.widget.Spinner(this).apply {
            adapter = ArrayAdapter(
                this@AiSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                presets.map { it.label }.toTypedArray()
            )
            setSelection(presets.indexOf(config.apiPreset).coerceAtLeast(0))
        }
        presetSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long
                ) {
                    if (suppressPreset) { suppressPreset = false; return }
                    config.apiPreset = presets[pos]
                    applyPresetToFields()
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
            }
        content.addView(presetSpinner)

        // API Key
        val keyLayout = TextInputLayout(this).apply {
            hint = getString(R.string.ai_key)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
            setPadding(0, m3.dp(12), 0, 0)
        }
        keyEdit = TextInputEditText(this).apply {
            hint = getString(R.string.ai_key_hint)
            setText(config.apiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        keyLayout.addView(keyEdit)
        content.addView(keyLayout)

        // Base URL
        baseLayout = TextInputLayout(this).apply {
            hint = getString(R.string.ai_base_url)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
            setPadding(0, m3.dp(10), 0, 0)
        }
        baseEdit = TextInputEditText(this).apply {
            hint = getString(R.string.ai_base_url_hint)
            setText(config.apiBaseUrl)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        baseLayout!!.addView(baseEdit)
        content.addView(baseLayout)

        // Model
        modelLayout = TextInputLayout(this).apply {
            hint = getString(R.string.ai_model)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
            setPadding(0, m3.dp(10), 0, 0)
        }
        modelEdit = TextInputEditText(this).apply {
            hint = getString(R.string.ai_model_hint)
            setText(config.apiModel)
            setSingleLine()
        }
        modelLayout!!.addView(modelEdit)
        content.addView(modelLayout)

        hintText = m3.hint("").apply { setPadding(0, m3.dp(10), 0, 0) }
        content.addView(hintText)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, m3.dp(12), 0, 0)
        }
        row.addView(m3.button(getString(R.string.ai_save), M3ViewFactory.Variant.FILLED).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = m3.dp(8) }
            setOnClickListener { save(showToast = true) }
        })
        row.addView(m3.button(getString(R.string.ai_test), M3ViewFactory.Variant.OUTLINED).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                save(showToast = false)
                testConnection()
            }
        })
        content.addView(row)

        card.addView(content)
        return card
    }

    /** 对话行为 */
    private fun buildBehaviorCard(): MaterialCardView {
        val card = m3.card()
        val content = m3.cardContent()
        content.addView(m3.sectionTitle("对话行为").apply { setPadding(0, 0, 0, m3.dp(4)) })

        // 回答长度
        content.addView(m3.fieldLabel(getString(R.string.ai_style)))
        val limits = AiClient.LengthLimit.entries.toList()
        var suppressLimit = true
        val limitSpinner = android.widget.Spinner(this).apply {
            adapter = ArrayAdapter(
                this@AiSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                limits.map { it.label }.toTypedArray()
            )
            setSelection(limits.indexOf(config.aiLengthLimit).coerceAtLeast(0))
        }
        limitSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long
                ) {
                    if (suppressLimit) { suppressLimit = false; return }
                    config.aiLengthLimit = limits[pos]
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
            }
        content.addView(limitSpinner)
        content.addView(m3.hint(getString(R.string.ai_style_hint)).apply { setPadding(0, m3.dp(4), 0, 0) })

        content.addView(m3.divider())

        content.addView(m3.switchRow(
            getString(R.string.ai_thinking), config.aiThinking,
            getString(R.string.ai_thinking_hint)
        ).also { row ->
            (row.tag as com.google.android.material.materialswitch.MaterialSwitch)
                .setOnCheckedChangeListener { _, on -> config.aiThinking = on }
        })
        content.addView(m3.switchRow(
            getString(R.string.voice_auto_send), config.voiceAutoSend,
            getString(R.string.voice_auto_send_hint)
        ).also { row ->
            (row.tag as com.google.android.material.materialswitch.MaterialSwitch)
                .setOnCheckedChangeListener { _, on -> config.voiceAutoSend = on }
        })

        // ---------- 离线识别（v1.3.2：解决"卡/断连"）----------
        content.addView(m3.switchRow(
            getString(R.string.voice_offline), config.voicePreferOffline,
            getString(R.string.voice_offline_hint)
        ).also { row ->
            (row.tag as com.google.android.material.materialswitch.MaterialSwitch)
                .setOnCheckedChangeListener { _, on -> config.voicePreferOffline = on }
        })
        // 把设备能力摊开写清楚，免得用户看到开关却不知道为什么没生效
        content.addView(m3.hint(voiceCapabilityText()).apply {
            setPadding(0, 0, 0, m3.dp(4))
        })

        card.addView(content)
        return card
    }

    /**
     * 说明本机语音识别能力。
     *
     * 离线识别需要 Android 12+ 且系统已安装对应语言的离线识别包，
     * 不是所有机器都有。把实际情况显示出来，比让用户猜要好。
     */
    private fun voiceCapabilityText(): String {
        val v = com.dafeiyu.pet.ai.VoiceInput(this)
        val offline = v.hasOnDevice()
        val online = v.hasNetwork()
        return when {
            offline && online -> "本机支持离线识别 ✓（同时可用在线识别）"
            offline -> "本机支持离线识别 ✓（在线识别不可用）"
            online -> "本机不支持离线识别，只能用在线识别。" +
                    "若经常断连，可在系统设置里下载「离线语音识别」语言包。"
            else -> "本机没有可用的语音识别服务。"
        }
    }
    /**
     * Agent 模式占位。
     *
     * 用户提到这是为"下一个低版本的 Agent 模式"做准备。
     * 现在只把**能力边界**写清楚并留出开关位，不做未实现的功能承诺 ——
     * 免得点了没反应像是坏了。
     */
    private fun buildAgentCard(): MaterialCardView {
        val card = m3.card()
        val content = m3.cardContent()
        content.addView(m3.sectionTitle("Agent 模式（尚未开放）").apply { setPadding(0, 0, 0, m3.dp(6)) })
        content.addView(m3.body(
            "当前只有 Ask 模式（纯问答）。Agent 模式计划让它能读写文件、" +
                    "执行命令、调用工具 —— 这需要更完整的工具调用链路与权限管理，" +
                    "会在后续版本单独做。"
        ))
        content.addView(m3.hint(
            "本页的结构已经为它留好位置：凭据、模型能力、工具权限会分卡管理。"
        ).apply { setPadding(0, m3.dp(8), 0, 0) })

        // 历史记录管理放这里，避免主界面堆太多
        content.addView(m3.divider())
        historyText = m3.hint("").apply { setPadding(0, 0, 0, m3.dp(8)) }
        content.addView(historyText)
        content.addView(
            m3.button(getString(R.string.ai_clear_history), M3ViewFactory.Variant.OUTLINED).apply {
                setOnClickListener {
                    ChatHistoryStore(config).clear()
                    refreshHistory()
                    toast("对话记录已清空")
                }
            }
        )

        card.addView(content)
        return card
    }

    // =============================== 逻辑 ===============================

    private fun applyPresetToFields() {
        val isDeepSeek = config.apiPreset == ApiPreset.DEEPSEEK
        baseEdit?.apply {
            isEnabled = !isDeepSeek
            setText(if (isDeepSeek) ApiPreset.DEEPSEEK.baseUrl else config.apiBaseUrl)
        }
        baseLayout?.alpha = if (isDeepSeek) 0.6f else 1f
        modelEdit?.apply {
            isEnabled = !isDeepSeek
            setText(if (isDeepSeek) ApiPreset.DEEPSEEK.model else config.apiModel)
        }
        modelLayout?.alpha = if (isDeepSeek) 0.6f else 1f
        hintText?.text = getString(
            if (isDeepSeek) R.string.ai_hint_deepseek else R.string.ai_hint_custom
        )
        refreshAccount()
        refreshHistory()
    }

    private fun refreshAccount() {
        val configured = config.isAiConfigured()
        accountStatus?.apply {
            text = if (configured) "已配置 ✓　${config.apiModel}" else "未配置 ✗　还没有填 API Key"
            setTextColor(if (configured) m3.primary else m3.error)
        }
    }

    private fun refreshHistory() {
        historyText?.text = getString(R.string.ai_history_count, ChatHistoryStore(config).size())
    }

    private fun save(showToast: Boolean) {
        config.apiKey = keyEdit?.text?.toString().orEmpty()
        if (config.apiPreset == ApiPreset.CUSTOM) {
            config.apiBaseUrl = baseEdit?.text?.toString().orEmpty()
            config.apiModel = modelEdit?.text?.toString().orEmpty()
        }
        PetService.reload(this)
        refreshAccount()
        if (showToast) {
            toast(if (config.isAiConfigured()) "已保存" else "已保存，但还没填 API Key")
        }
    }

    private fun testConnection() {
        if (!config.isAiConfigured()) {
            toast("请先填写 API Key")
            return
        }
        toast("正在测试连接…")
        AiClient.ask(
            baseUrl = config.apiBaseUrl,
            apiKey = config.apiKey,
            model = config.apiModel,
            history = emptyList(),
            userText = "回复两个字：在的",
            limit = AiClient.LengthLimit.NONE,
            callback = object : AiClient.Callback {
                override fun onDelta(text: String) = Unit
                override fun onComplete(text: String) {
                    toast("连接成功 ✓ 模型回复：${text.take(20)}")
                }

                override fun onError(message: String) {
                    toast("连接失败：$message")
                }
            }
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
