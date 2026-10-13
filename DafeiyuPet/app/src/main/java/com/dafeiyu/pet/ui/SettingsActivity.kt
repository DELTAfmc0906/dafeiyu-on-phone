package com.dafeiyu.pet.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dafeiyu.pet.R
import com.dafeiyu.pet.ai.AiClient
import com.dafeiyu.pet.ai.ChatHistoryStore
import com.dafeiyu.pet.data.ApiPreset
import com.dafeiyu.pet.data.ConfigRepo
import com.dafeiyu.pet.data.PetMode
import com.dafeiyu.pet.data.PetSize
import com.dafeiyu.pet.update.UpdateClient
import com.dafeiyu.pet.update.UpdateInfo

/**
 * 设置 / 控制面板。
 *
 * 对应 Python 版右键菜单里的各项开关，但排布成适合手机触摸的表单。
 * 用代码构建界面而非 XML 布局 —— 控件不多，且能避免 id 与 theme 的耦合。
 *
 * 权限说明：
 *   - SYSTEM_ALERT_WINDOW（悬浮窗）必须用户去系统设置里手动开，无法运行时申请
 *   - POST_NOTIFICATIONS 在 Android 13+ 是运行时权限
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        /** 打开设置页并要求滚动/聚焦到 AI 区（长按鱼身但没配 Key 时会带上） */
        const val EXTRA_FOCUS_API = "focus_api"

        /** 供 ChatActivity 跳过来配 Key */
        fun startForApi(context: android.content.Context) {
            context.startActivity(
                Intent(context, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_FOCUS_API, true)
            )
        }
    }

    private lateinit var config: ConfigRepo
    private lateinit var statusText: TextView
    private lateinit var overlayBtn: Button
    private lateinit var notifyBtn: Button
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    /** AI 区控件，保存后要刷新提示文字 */
    private var aiKeyEdit: EditText? = null
    private var aiBaseEdit: EditText? = null
    private var aiModelEdit: EditText? = null
    private var aiHintText: TextView? = null
    private var aiHistoryText: TextView? = null
    private var aiScrollTarget: View? = null

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshState() }

    private val notifyLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshState() }

    /**
     * 架构说明（v1.2 修正）：设置面板的"让路"**不再改动用户的配置**。
     *
     * 面板打开时只置 [PetRuntime.settingsVisible] 标记，服务端由
     * `shouldBeTouchable()` 统一裁决：
     *
     *     可触摸 = 用户开了可互动(config.interactive) && 面板不在前台(settingsVisible=false)
     *
     * 这样"用户意图"与"面板临时让路"彻底分离。
     * 早期版本让面板直接改 config.interactive，导致用户主动关掉可互动后、
     * 一进出设置面板就被改回开启。
     */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = ConfigRepo(this)
        supportActionBar?.title = getString(R.string.settings_title)
        setContentView(buildUi())
        refreshState()
        if (intent?.getBooleanExtra(EXTRA_FOCUS_API, false) == true) {
            focusAiSection()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_FOCUS_API, false) == true) {
            focusAiSection()
        }
    }

    override fun onResume() {
        super.onResume()
        // 面板在前台期间让鱼"让路"：否则鱼一大就盖住面板自己的控件，
        // 用户想把它调小都点不到。服务端读这个标记来裁决触摸穿透。
        PetRuntime.settingsVisible = true
        if (PetRuntime.isRunning) PetService.refreshTouchable(this)
        refreshState()
    }

    override fun onPause() {
        PetRuntime.settingsVisible = false
        // 只需让服务重算一次（此时 settingsVisible 已变），
        // 绝不能用 setTouchable —— 那会覆盖用户刚改的可互动设置。
        if (PetRuntime.isRunning) PetService.refreshTouchable(this)
        super.onPause()
    }

    // =============================== 界面构建 ===============================

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply { addView(root) }

        // ---------- 状态 ----------
        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(statusText)

        // ---------- 启动与权限 ----------
        root.addView(sectionTitle(getString(R.string.sec_start)))

        overlayBtn = Button(this).apply {
            text = getString(R.string.grant_overlay)
            setOnClickListener { requestOverlay() }
        }
        root.addView(overlayBtn)

        notifyBtn = Button(this).apply {
            text = getString(R.string.grant_notify)
            setOnClickListener { requestNotify() }
        }
        root.addView(notifyBtn)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        startBtn = Button(this).apply {
            text = getString(R.string.start_pet)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@SettingsActivity)) {
                    toast(getString(R.string.need_overlay))
                    requestOverlay()
                } else {
                    PetService.start(this@SettingsActivity)
                    toast("大肥鱼出来啦～")
                }
            }
        }
        stopBtn = Button(this).apply {
            text = getString(R.string.stop_pet)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                PetService.stop(this@SettingsActivity)
                toast("大肥鱼收工了")
            }
        }
        row.addView(startBtn)
        row.addView(stopBtn)
        root.addView(row)

        // ---------- 模式 ----------
        root.addView(sectionTitle(getString(R.string.sec_mode)))
        val modeLabels = arrayOf(
            getString(R.string.mode_wander),
            getString(R.string.mode_follow),
            getString(R.string.mode_still)
        )
        val modeValues = arrayOf(PetMode.WANDER, PetMode.FOLLOW, PetMode.STILL)
        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                modeLabels
            )
            setSelection(modeValues.indexOf(config.mode).coerceAtLeast(0))
            onItemSelected { pos ->
                config.mode = modeValues[pos]
                PetService.reload(this@SettingsActivity)
            }
        }
        root.addView(modeSpinner)

        // ---------- 大小（v1.1：自由滑杆 + 三档快捷）----------
        root.addView(sectionTitle(getString(R.string.sec_size)))

        // 显示当前百分比
        val sizeValueText = TextView(this).apply {
            textSize = 17f
            setPadding(0, 0, 0, dp(2))
        }
        root.addView(sizeValueText)

        // 换算：滑杆位置 -> 百分比
        val steps = (PetSize.MAX_PERCENT - PetSize.MIN_PERCENT) / PetSize.STEP
        fun percentOf(progress: Int) = PetSize.MIN_PERCENT + progress * PetSize.STEP
        fun progressOf(percent: Int) =
            ((PetSize.clamp(percent) - PetSize.MIN_PERCENT) / PetSize.STEP)
                .coerceIn(0, steps)

        val sizeSeek = SeekBar(this).apply {
            max = steps
            progress = progressOf(config.sizePercent)
            setPadding(0, dp(4), 0, dp(4))
        }

        fun updateSizeLabel(percent: Int) {
            sizeValueText.text = getString(R.string.size_current, percent)
        }
        updateSizeLabel(config.sizePercent)

        sizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val p = percentOf(progress)
                updateSizeLabel(p)
                if (!fromUser) return
                // 拖动过程中就实时应用，能立刻看到鱼变大变小
                config.sizePercent = p
                PetService.reload(this@SettingsActivity)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        root.addView(sizeSeek)

        // 三档快捷按钮
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        val presets = listOf(
            getString(R.string.size_small) to PetSize.SMALL_PERCENT,
            getString(R.string.size_medium) to PetSize.MEDIUM_PERCENT,
            getString(R.string.size_large) to PetSize.LARGE_PERCENT
        )
        for ((label, percent) in presets) {
            presetRow.addView(Button(this).apply {
                text = label
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(4) }
                setOnClickListener {
                    config.sizePercent = percent
                    sizeSeek.progress = progressOf(percent)
                    updateSizeLabel(percent)
                    PetService.reload(this@SettingsActivity)
                }
            })
        }
        root.addView(presetRow)

        // 说明
        root.addView(TextView(this).apply {
            text = getString(R.string.size_hint)
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(4), 0, 0)
        })

        // ---------- 其它 ----------
        root.addView(sectionTitle(getString(R.string.sec_misc)))

        root.addView(checkBox(getString(R.string.opt_interactive), config.interactive) { on ->
            config.interactive = on
            PetService.reload(this)
        })

        // v1.2.1：鱼在 app 前台期间是否也保持可互动
        root.addView(checkBox(
            getString(R.string.opt_force_interactive), config.forceInteractive
        ) { on ->
            config.forceInteractive = on
            PetService.refreshTouchable(this)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.opt_force_interactive_hint)
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(dp(28), 0, 0, dp(4))
        })

        root.addView(checkBox(getString(R.string.opt_battery), config.batteryAlert) { on ->
            config.batteryAlert = on
            PetService.reload(this)
        })

        root.addView(checkBox(getString(R.string.opt_autostart), config.autoStart) { on ->
            config.autoStart = on
            BootReceiver.setEnabled(this, on)
        })

        // 城市
        val cityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        cityRow.addView(TextView(this).apply {
            text = getString(R.string.city_label)
            textSize = 15f
        })
        val cityEdit = EditText(this).apply {
            hint = getString(R.string.city_hint)
            setText(config.city)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
        }
        cityRow.addView(cityEdit)
        root.addView(cityRow)

        val cityBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        cityBtns.addView(Button(this).apply {
            text = "保存城市"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val c = cityEdit.text.toString().trim()
                if (c.isEmpty()) {
                    toast("城市不能为空")
                } else {
                    config.city = c
                    PetService.reload(this@SettingsActivity)
                    toast("城市已设置为$c")
                }
            }
        })
        cityBtns.addView(Button(this).apply {
            text = getString(R.string.check_weather)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val c = cityEdit.text.toString().trim().ifEmpty { config.city }
                PetService.requestWeather(this@SettingsActivity, c)
                toast("正在查询$c 的天气…")
            }
        })
        root.addView(cityBtns)

        // ---------- AI 对话（v1.2）----------
        val aiSection = buildAiSection()
        aiScrollTarget = aiSection
        root.addView(aiSection)

        // ---------- 更新（v1.2.1）----------
        root.addView(buildUpdateSection())

        // 关于
        root.addView(sectionTitle(getString(R.string.about)))
        root.addView(TextView(this).apply {
            text = getString(R.string.about_text)
            textSize = 13f
            setTextColor(Color.GRAY)
        })

        return scroll
    }

    /**
     * AI 配置区。
     *
     * 两种模式：
     *   - DeepSeek 预设：地址和模型自动填好且不可编辑，用户只需填 Key
     *   - 自定义：地址和模型可编辑，用于任意 OpenAI 兼容服务
     */
    private fun buildAiSection(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        box.addView(sectionTitle(getString(R.string.sec_ai)))

        box.addView(checkBox(getString(R.string.ai_enabled), config.aiEnabled) { on ->
            config.aiEnabled = on
            PetService.reload(this)
        })

        // 预设选择
        val presetLabels = ApiPreset.entries.map { it.label }.toTypedArray()
        val presetValues = ApiPreset.entries.toList()
        box.addView(TextView(this).apply {
            text = getString(R.string.ai_preset)
            textSize = 14f
            setPadding(0, dp(10), 0, dp(2))
        })
        var suppressPresetCallback = true
        val presetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                presetLabels
            )
            setSelection(presetValues.indexOf(config.apiPreset).coerceAtLeast(0))
        }
        presetSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    if (suppressPresetCallback) { suppressPresetCallback = false; return }
                    config.apiPreset = presetValues[position]
                    applyPresetToFields()
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        box.addView(presetSpinner)

        // Key
        box.addView(fieldLabel(getString(R.string.ai_key)))
        aiKeyEdit = EditText(this).apply {
            hint = getString(R.string.ai_key_hint)
            setText(config.apiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        box.addView(aiKeyEdit)

        // Base URL
        box.addView(fieldLabel(getString(R.string.ai_base_url)))
        aiBaseEdit = EditText(this).apply {
            hint = getString(R.string.ai_base_url_hint)
            setText(config.apiBaseUrl)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        box.addView(aiBaseEdit)

        // Model
        box.addView(fieldLabel(getString(R.string.ai_model)))
        aiModelEdit = EditText(this).apply {
            hint = getString(R.string.ai_model_hint)
            setText(config.apiModel)
            setSingleLine()
        }
        box.addView(aiModelEdit)

        box.addView(checkBox(
            getString(R.string.ai_thinking), config.aiThinking
        ) { on ->
            config.aiThinking = on
        })
        box.addView(TextView(this).apply {
            text = getString(R.string.ai_thinking_hint)
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(dp(28), 0, 0, dp(4))
        })

        box.addView(checkBox(
            getString(R.string.voice_auto_send), config.voiceAutoSend
        ) { on ->
            config.voiceAutoSend = on
        })
        box.addView(TextView(this).apply {
            text = getString(R.string.voice_auto_send_hint)
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(dp(28), 0, 0, dp(6))
        })

        // 提示
        aiHintText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(6), 0, 0)
        }
        box.addView(aiHintText)

        // 按钮行
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        btnRow.addView(Button(this).apply {
            text = getString(R.string.ai_save)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(4) }
            setOnClickListener { saveAi(showToast = true) }
        })
        btnRow.addView(Button(this).apply {
            text = getString(R.string.ai_test)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                saveAi(showToast = false)
                testAiConnection()
            }
        })
        box.addView(btnRow)

        // 历史
        aiHistoryText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(10), 0, 0)
        }
        box.addView(aiHistoryText)

        box.addView(Button(this).apply {
            text = getString(R.string.ai_clear_history)
            setOnClickListener {
                ChatHistoryStore(config).clear()
                refreshAiUi()
                toast("对话记录已清空")
            }
        })

        applyPresetToFields()
        return box
    }

    private fun fieldLabel(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setPadding(0, dp(10), 0, dp(2))
    }

    // =============================== 更新（v1.2.1）===============================

    private var updateStatusText: TextView? = null

    /**
     * "更新"区。
     *
     * 只做「检查 + 跳转浏览器」，**不自动下载安装 APK**：
     * Android 8+ 安装未知来源应用需要 REQUEST_INSTALL_PACKAGES 权限，
     * 而且静默装包容易被安全软件拦、用户也不放心。把安装交给用户点一下更稳妥。
     */
    private fun buildUpdateSection(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(sectionTitle(getString(R.string.sec_update)))

        val curVer = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"

        box.addView(TextView(this).apply {
            text = getString(R.string.update_current, curVer)
            textSize = 14f
        })

        updateStatusText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(6), 0, 0)
        }
        box.addView(updateStatusText)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(Button(this).apply {
            text = getString(R.string.update_check)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(4) }
            setOnClickListener { checkForUpdate() }
        })
        row.addView(Button(this).apply {
            text = getString(R.string.update_open_github)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { openUrl(UpdateClient.repoUrl) }
        })
        box.addView(row)

        box.addView(TextView(this).apply {
            text = UpdateClient.repoUrl
            textSize = 11f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(0, dp(6), 0, 0)
            setOnClickListener { openUrl(UpdateClient.repoUrl) }
        })

        return box
    }

    private fun checkForUpdate() {
        val status = updateStatusText ?: return
        status.text = getString(R.string.update_checking)
        status.setTextColor(Color.GRAY)
        UpdateClient.clearCache()
        UpdateClient.checkLatest(force = true) { result ->
            result.onSuccess { info ->
                val curVer = runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull() ?: "0"
                when {
                    info == null -> {
                        status.text = getString(R.string.update_none)
                        status.setTextColor(Color.GRAY)
                    }
                    info.isNewerThan(curVer) -> {
                        status.text = getString(R.string.update_available, info.version)
                        status.setTextColor(Color.parseColor("#2E7D32"))
                        showUpdateDialog(info)
                    }
                    else -> {
                        status.text = getString(R.string.update_latest_ok)
                        status.setTextColor(Color.parseColor("#2E7D32"))
                    }
                }
            }.onFailure { e ->
                status.text = getString(R.string.update_failed, e.message ?: "网络错误")
                status.setTextColor(Color.parseColor("#C62828"))
            }
        }
    }

    /** 新版本弹窗：显示更新说明，提供"去下载"（跳 Release 页）*/
    private fun showUpdateDialog(info: UpdateInfo) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val notes = info.notes.trim().ifEmpty { "（该版本没有填写更新说明）" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        box.addView(TextView(this).apply {
            text = info.title.ifBlank { "v${info.version}" }
            textSize = 16f
        })
        box.addView(TextView(this).apply {
            text = getString(R.string.update_notes_title)
            textSize = 13f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(0, dp(12), 0, dp(4))
        })
        box.addView(ScrollView(this).apply {
            addView(TextView(this@SettingsActivity).apply {
                text = notes
                textSize = 13f
                setTextColor(Color.parseColor("#444444"))
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200)
            )
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available, info.version))
            .setView(box)
            .setPositiveButton(R.string.update_now) { _, _ ->
                // 指向该 Release 页面；如果附带 APK 也可直链下载
                openUrl(info.apkUrl ?: info.pageUrl)
            }
            .setNegativeButton(R.string.update_later, null)
            .create()
        dialog.show()
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            toast("打不开浏览器：$url")
        }
    }

    /** 按当前预设决定 base/model 是否可编辑，并同步提示语 */
    private fun applyPresetToFields() {
        val isDeepSeek = config.apiPreset == ApiPreset.DEEPSEEK
        aiBaseEdit?.apply {
            isEnabled = !isDeepSeek
            setText(if (isDeepSeek) ApiPreset.DEEPSEEK.baseUrl else config.apiBaseUrl)
            alpha = if (isDeepSeek) 0.6f else 1f
        }
        aiModelEdit?.apply {
            isEnabled = !isDeepSeek
            setText(if (isDeepSeek) ApiPreset.DEEPSEEK.model else config.apiModel)
            alpha = if (isDeepSeek) 0.6f else 1f
        }
        aiHintText?.text = getString(
            if (isDeepSeek) R.string.ai_hint_deepseek else R.string.ai_hint_custom
        )
        refreshAiUi()
    }

    private fun refreshAiUi() {
        val n = ChatHistoryStore(config).size()
        aiHistoryText?.text = getString(R.string.ai_history_count, n)
    }

    private fun saveAi(showToast: Boolean) {
        config.apiKey = aiKeyEdit?.text?.toString().orEmpty()
        if (config.apiPreset == ApiPreset.CUSTOM) {
            config.apiBaseUrl = aiBaseEdit?.text?.toString().orEmpty()
            config.apiModel = aiModelEdit?.text?.toString().orEmpty()
        }
        PetService.reload(this)
        refreshAiUi()
        if (showToast) {
            toast(
                if (config.isAiConfigured()) "AI 设置已保存"
                else "已保存，但还没填 API Key"
            )
        }
    }

    /** 用一句最简请求验证 Key / 地址 / 模型是否可用 */
    private fun testAiConnection() {
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

    /** 从长按鱼身跳过来时，滚动到 AI 区并聚焦 Key 输入框 */
    private fun focusAiSection() {
        val target = aiScrollTarget ?: return
        target.post {
            (target.parent as? ScrollView)?.smoothScrollTo(0, target.top)
        }
        aiKeyEdit?.post {
            aiKeyEdit?.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(aiKeyEdit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(Color.parseColor("#1565C0"))
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun checkBox(
        label: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ) = CheckBox(this).apply {
        text = label
        isChecked = checked
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    /** Spinner 的选中回调包一层，避免初始化时误触发写配置 */
    private fun Spinner.onItemSelected(action: (Int) -> Unit) {
        var first = true
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                if (first) { first = false; return }
                action(position)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    // =============================== 权限 ===============================

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            toast(getString(R.string.grant_overlay_done))
            return
        }
        // 悬浮窗权限没有运行时弹窗，只能跳系统设置页。
        // 用 launcher 包一层，回来时能自动 refreshState()。
        overlayLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun requestNotify() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            toast("当前系统版本无需申请通知权限")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            toast("通知权限已授予 ✓")
            return
        }
        notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // =============================== 状态刷新 ===============================

    private fun refreshState() {
        val overlayOk = Settings.canDrawOverlays(this)
        val notifyOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED)
        val running = PetRuntime.isRunning

        statusText.text = buildString {
            append(if (running) getString(R.string.pet_running) else getString(R.string.pet_stopped))
            append("　|　")
            append(if (overlayOk) "悬浮窗 ✓" else "悬浮窗 ✗")
            append("　|　")
            append(if (notifyOk) "通知 ✓" else "通知 ✗")
        }

        overlayBtn.isEnabled = !overlayOk
        overlayBtn.text = if (overlayOk) getString(R.string.grant_overlay_done)
        else getString(R.string.grant_overlay)

        notifyBtn.isEnabled = !notifyOk
        notifyBtn.text = if (notifyOk) "通知权限已授予 ✓" else getString(R.string.grant_notify)

        startBtn.isEnabled = !running
        stopBtn.isEnabled = running

        if (running && !overlayOk) {
            // 悬浮窗权限被撤销了，服务会自杀，这里提示一下
            toast("悬浮窗权限已失效，桌宠可能已停止")
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
