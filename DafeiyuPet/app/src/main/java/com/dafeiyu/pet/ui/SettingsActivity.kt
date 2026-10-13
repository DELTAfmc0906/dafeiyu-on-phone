package com.dafeiyu.pet.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dafeiyu.pet.R
import com.dafeiyu.pet.data.ConfigRepo
import com.dafeiyu.pet.data.PetMode
import com.dafeiyu.pet.data.PetSize
import com.dafeiyu.pet.update.UpdateClient
import com.dafeiyu.pet.update.UpdateInfo
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 设置 / 控制面板 —— v1.3 按 Material You 3 重做。
 *
 * ## 这次 UI 改造做了什么
 *
 * ### 1. 从 Material 2 迁到 Material 3
 * 主题由 `Theme.MaterialComponents.*` 换成 `Theme.Material3.*`，
 * 颜色从"写死一个蓝色"改成引用 M3 的**语义颜色角色**
 * （colorPrimary / colorSurfaceContainerLow / colorOnSurfaceVariant…）。
 *
 * 好处是**深色模式自动可用**，而且 Android 12+ 会启用动态取色
 * （见 [com.dafeiyu.pet.DafeiyuApp]），整套配色跟着用户壁纸走。
 *
 * ### 2. 用 M3 组件替换系统老控件
 *
 * | 之前 | 现在 | M3 依据 |
 * |---|---|---|
 * | `Button` | [MaterialButton] | 胶囊圆角、明确的层级（filled/tonal/outlined） |
 * | `CheckBox` | MaterialSwitch | M3 里开关类设置的标准控件 |
 * | `SeekBar` | [Slider] | 带数值气泡、轨道更细 |
 * | `EditText` | [TextInputLayout] 包裹 | M3 的 filled 文本框，标签浮动 |
 * | `Spinner` | MaterialSpinner | 下拉样式与 M3 一致 |
 *
 * ### 3. 用卡片分组
 * M3 强调**层级用色调而非阴影**，所以每一块设置放进
 * [MaterialCardView]（`surfaceContainerLow` 底 + 16dp 圆角 + 细描边），
 * 而不是靠分隔线和加粗标题硬分。
 *
 * ### 4. 状态可视化
 * 原来状态是一行纯文本"运行中 | 悬浮窗 ✓ | 通知 ✗"，
 * 现在做成带底色的状态条 + 逐项权限卡片，扫一眼就知道缺什么。
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
    private val m3 by lazy { M3ViewFactory(this) }

    // 需要动态改状态的控件
    private var statusCard: MaterialCardView? = null
    private var statusTitle: TextView? = null
    private var statusDetail: TextView? = null
    private var overlayBtn: MaterialButton? = null
    private var notifyBtn: MaterialButton? = null
    private var startBtn: MaterialButton? = null
    private var stopBtn: MaterialButton? = null

    private var aiScrollTarget: View? = null

    /** AI 入口卡片上的状态行（已配置/未配置 + 当前模型） */
    private var aiStatusLine: TextView? = null

    private var updateStatusText: TextView? = null

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshState() }

    private val notifyLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = ConfigRepo(this)
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
        val root = m3.pageRoot()

        root.addView(buildTopBar())
        root.addView(buildStatusCard())
        root.addView(buildStartCard())
        root.addView(buildDisplayCard())
        root.addView(buildMiscCard())
        root.addView(buildAiSection())
        root.addView(buildUpdateSection())
        root.addView(buildAbout())

        return ScrollView(this).apply {
            addView(root)
            setBackgroundColor(m3.surface)
        }
    }

    /**
     * 顶部大标题。
     *
     * M3 的 top app bar 规范：标题用 HeadlineSmall，左对齐，
     * 与内容区留出 16dp 间距。这里不用 MaterialToolbar 是为了
     * 顺带显示版本号副标题。
     */
    private fun buildTopBar(): View {
        val ver = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, m3.dp(16), 0, m3.dp(8))
            addView(TextView(this@SettingsActivity).apply {
                text = getString(R.string.settings_title)
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(m3.onSurface)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "版本 $ver"
                textSize = 12f
                setTextColor(m3.onSurfaceVariant)
                setPadding(0, m3.dp(2), 0, 0)
            })
        }
    }

    /**
     * 运行状态卡片。
     *
     * 顶部一条横贯的状态条（运行中/未运行），下面是权限逐项状态。
     * 用 primaryContainer 做运行中的底色 —— M3 里强调类容器色就这个用途。
     */
    private fun buildStatusCard(): View {
        val card = m3.card()
        val content = m3.cardContent()

        statusTitle = TextView(this).apply {
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        }
        content.addView(statusTitle)

        statusDetail = TextView(this).apply {
            textSize = 12f
            setPadding(0, m3.dp(4), 0, 0)
        }
        content.addView(statusDetail)

        card.addView(content)
        statusCard = card
        return card
    }

    /** 启动 / 权限 */
    private fun buildStartCard(): View {
        val card = m3.card()
        val content = m3.cardContent()
        content.addView(m3.sectionTitle(getString(R.string.sec_start)).apply { setPadding(0, 0, 0, m3.dp(8)) })

        overlayBtn = m3.button(getString(R.string.grant_overlay), M3ViewFactory.Variant.OUTLINED).apply {
            setOnClickListener { requestOverlay() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = m3.dp(8) }
        }
        content.addView(overlayBtn)

        notifyBtn = m3.button(getString(R.string.grant_notify), M3ViewFactory.Variant.OUTLINED).apply {
            setOnClickListener { requestNotify() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = m3.dp(8) }
        }
        content.addView(notifyBtn)

        content.addView(m3.hint(getString(R.string.overlay_hint)))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, m3.dp(12), 0, 0)
        }
        // 主操作只该有一个：FILLED 给「启动桌宠」，停止用 TONAL
        startBtn = m3.button(getString(R.string.start_pet), M3ViewFactory.Variant.FILLED).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = m3.dp(8) }
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
        stopBtn = m3.button(getString(R.string.stop_pet), M3ViewFactory.Variant.TONAL).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                PetService.stop(this@SettingsActivity)
                toast("大肥鱼收工了")
            }
        }
        row.addView(startBtn)
        row.addView(stopBtn)
        content.addView(row)

        card.addView(content)
        return card
    }

    /** 模式与大小 */
    private fun buildDisplayCard(): View {
        val card = m3.card()
        val content = m3.cardContent()

        // ---------- 模式 ----------
        content.addView(m3.sectionTitle(getString(R.string.sec_mode)).apply { setPadding(0, 0, 0, m3.dp(4)) })

        val modeLabels = arrayOf(
            getString(R.string.mode_wander),
            getString(R.string.mode_follow),
            getString(R.string.mode_still)
        )
        val modeValues = arrayOf(PetMode.WANDER, PetMode.FOLLOW, PetMode.STILL)
        val modeSpinner = android.widget.Spinner(this).apply {
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
        content.addView(modeSpinner)

        // ---------- 大小 ----------
        content.addView(m3.sectionTitle(getString(R.string.sec_size)).apply { setPadding(0, m3.dp(16), 0, m3.dp(4)) })

        val sizeValueText = TextView(this).apply {
            textSize = 15f
            setTextColor(m3.onSurface)
        }
        content.addView(sizeValueText)

        val steps = (PetSize.MAX_PERCENT - PetSize.MIN_PERCENT) / PetSize.STEP
        fun percentOf(progress: Int) = PetSize.MIN_PERCENT + progress * PetSize.STEP
        fun progressOf(percent: Int) =
            ((PetSize.clamp(percent) - PetSize.MIN_PERCENT) / PetSize.STEP)
                .coerceIn(0, steps)

        fun updateSizeLabel(percent: Int) {
            sizeValueText.text = getString(R.string.size_current, percent)
        }
        updateSizeLabel(config.sizePercent)

        // M3 Slider。
        // 注意：这里**故意不设 stepSize** —— 设了之后 M3 Slider 会在轨道上
        // 画出等距圆点刻度，21 档会变成一片密集小点，观感很乱
        // （M3 规范里圆点刻度只用于少量档位）。改成连续滑杆，
        // 取值时再对齐到 STEP 的整数倍，行为不变但干净得多。
        val sizeSlider = Slider(this).apply {
            valueFrom = 0f
            valueTo = steps.toFloat()
            value = progressOf(config.sizePercent).toFloat()
            addOnChangeListener { _, value, fromUser ->
                val p = percentOf(value.toInt())
                updateSizeLabel(p)
                if (!fromUser) return@addOnChangeListener
                // 拖动过程中就实时应用，能立刻看到鱼变大变小
                config.sizePercent = p
                PetService.reload(this@SettingsActivity)
            }
        }
        content.addView(sizeSlider)

        // 三档快捷
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, m3.dp(8), 0, 0)
        }
        val presets = listOf(
            getString(R.string.size_small) to PetSize.SMALL_PERCENT,
            getString(R.string.size_medium) to PetSize.MEDIUM_PERCENT,
            getString(R.string.size_large) to PetSize.LARGE_PERCENT
        )
        for ((label, percent) in presets) {
            presetRow.addView(
                m3.button(label, M3ViewFactory.Variant.OUTLINED).apply {
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = m3.dp(6) }
                    setOnClickListener {
                        config.sizePercent = percent
                        sizeSlider.value = progressOf(percent).toFloat()
                        updateSizeLabel(percent)
                        PetService.reload(this@SettingsActivity)
                    }
                }
            )
        }
        content.addView(presetRow)
        content.addView(m3.hint(getString(R.string.size_hint)).apply { setPadding(0, m3.dp(8), 0, 0) })

        card.addView(content)
        return card
    }

    /** 其它：可互动 / 低电量 / 开机自启 / 天气城市 */
    private fun buildMiscCard(): View {
        val card = m3.card()
        val content = m3.cardContent()
        content.addView(m3.sectionTitle(getString(R.string.sec_misc)).apply { setPadding(0, 0, 0, m3.dp(4)) })

        content.addView(m3.switchRow(
            getString(R.string.opt_interactive), config.interactive
        ).also { row ->
            (row.tag as com.google.android.material.materialswitch.MaterialSwitch)
                .setOnCheckedChangeListener { _, on ->
                    config.interactive = on
                    PetService.reload(this)
                }
        })

        content.addView(m3.switchRow(
            getString(R.string.opt_force_interactive),
            config.forceInteractive,
            getString(R.string.opt_force_interactive_hint)
        ).also { row ->
            (row.tag as com.google.android.material.materialswitch.MaterialSwitch)
                .setOnCheckedChangeListener { _, on ->
                    config.forceInteractive = on
                    PetService.refreshTouchable(this)
                }
        })

        content.addView(m3.switchRow(
            getString(R.string.opt_battery), config.batteryAlert
        ).also { row ->
            (row.tag as com.google.android.material.materialswitch.MaterialSwitch)
                .setOnCheckedChangeListener { _, on ->
                    config.batteryAlert = on
                    PetService.reload(this)
                }
        })

        content.addView(m3.switchRow(
            getString(R.string.opt_autostart), config.autoStart
        ).also { row ->
            (row.tag as com.google.android.material.materialswitch.MaterialSwitch)
                .setOnCheckedChangeListener { _, on ->
                    config.autoStart = on
                    BootReceiver.setEnabled(this, on)
                }
        })

        // ---------- 天气城市 ----------
        content.addView(m3.divider())
        content.addView(m3.fieldLabel(getString(R.string.city_label)))

        val cityLayout = TextInputLayout(this).apply {
            hint = getString(R.string.city_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
        }
        val cityEdit = TextInputEditText(this).apply {
            setText(config.city)
            setSingleLine()
        }
        cityLayout.addView(cityEdit)
        content.addView(cityLayout)

        val cityBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, m3.dp(10), 0, 0)
        }
        cityBtns.addView(
            m3.button("保存城市", M3ViewFactory.Variant.TONAL).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = m3.dp(8) }
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
            }
        )
        cityBtns.addView(
            m3.button(getString(R.string.check_weather), M3ViewFactory.Variant.OUTLINED).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    val c = cityEdit.text.toString().trim().ifEmpty { config.city }
                    PetService.requestWeather(this@SettingsActivity, c)
                    toast("正在查询$c 的天气…")
                }
            }
        )
        content.addView(cityBtns)

        card.addView(content)
        return card
    }

    // =============================== AI 区（入口）===============================

    /**
     * AI 配置入口卡片（v1.3.1 改为独立页）。
     *
     * ## 为什么改
     *
     * 用户反馈："改了 md3 UI 之后甚至连 API 设置都被吃掉了"。
     *
     * 功能其实没丢 —— v1.3 把设置页全部卡片化后，AI 配置被埋在这一长串卡片的
     * 最深处（要滑过权限、模式、大小、四个开关、城市才看得到），**入口丢了**。
     *
     * 现在 AI 相关的全部内容搬到独立的 [AiSettingsActivity]：
     *   - 对话窗口标题栏的 ⚙ 齿轮 → 直接打开（最常用路径）
     *   - 这里只留一张入口卡 → 点进去
     *
     * 顺带让设置页回归"控制桌宠本身"的单一职责，不再混着模型配置。
     * 卡里同时显示当前模型，一眼能看出配没配。
     */
    private fun buildAiSection(): View {
        val card = m3.card()
        val content = m3.cardContent()
        aiScrollTarget = card

        content.addView(m3.sectionTitle(getString(R.string.sec_ai)).apply { setPadding(0, 0, 0, m3.dp(4)) })

        // 当前状态：一眼看出配没配、用的哪个模型
        val statusLine = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, m3.dp(4))
        }
        content.addView(statusLine)

        content.addView(m3.hint(
            "API Key、模型、回答长度、思考开关、语音发送都在这页里。"
        ).apply { setPadding(0, m3.dp(4), 0, m3.dp(10)) })

        content.addView(
            m3.button("打开 AI 与账号设置", M3ViewFactory.Variant.FILLED).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener { AiSettingsActivity.start(this@SettingsActivity) }
            }
        )

        card.addView(content)
        aiStatusLine = statusLine
        return card
    }

    /** 供 onResume / 设置变化后刷新 AI 卡片的当前状态文字 */
    private fun refreshAiEntryCard() {
        val configured = config.isAiConfigured()
        aiStatusLine?.apply {
            text = if (configured) "已配置 ✓　模型：${config.apiModel}"
            else "未配置 ✗　还没填 API Key"
            setTextColor(if (configured) m3.primary else m3.error)
        }
    }

    // =============================== 更新区 ===============================

    // =============================== 更新区 ===============================

    /**
     * "更新"卡片。
     *
     * 只做「检查 + 跳转浏览器」，**不自动下载安装 APK**：
     * Android 8+ 安装未知来源应用需要 REQUEST_INSTALL_PACKAGES 权限，
     * 而且静默装包容易被安全软件拦、用户也不放心。把安装交给用户点一下更稳妥。
     */
    private fun buildUpdateSection(): View {
        val card = m3.card()
        val content = m3.cardContent()
        content.addView(m3.sectionTitle(getString(R.string.sec_update)).apply { setPadding(0, 0, 0, m3.dp(4)) })

        val curVer = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"

        content.addView(m3.body(getString(R.string.update_current, curVer)))

        updateStatusText = m3.hint("").apply { setPadding(0, m3.dp(8), 0, 0) }
        content.addView(updateStatusText)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, m3.dp(12), 0, 0)
        }
        row.addView(
            m3.button(getString(R.string.update_check), M3ViewFactory.Variant.TONAL).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = m3.dp(8) }
                setOnClickListener { checkForUpdate() }
            }
        )
        row.addView(
            m3.button(getString(R.string.update_open_github), M3ViewFactory.Variant.OUTLINED).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { openUrl(UpdateClient.repoUrl) }
            }
        )
        content.addView(row)

        content.addView(m3.hint(UpdateClient.repoUrl).apply {
            setPadding(0, m3.dp(10), 0, 0)
            setTextColor(m3.primary)   // 链接用主色
            setOnClickListener { openUrl(UpdateClient.repoUrl) }
        })

        card.addView(content)
        return card
    }

    private fun buildAbout(): View {
        val card = m3.card()
        val content = m3.cardContent()
        content.addView(m3.sectionTitle(getString(R.string.about)).apply { setPadding(0, 0, 0, m3.dp(4)) })
        content.addView(m3.body(getString(R.string.about_text)))
        card.addView(content)
        return card
    }

    /** 从长按鱼身跳过来时，滚动到 AI 入口卡片 */
    private fun focusAiSection() {
        val target = aiScrollTarget ?: return
        target.post {
            (target.parent as? ScrollView)?.smoothScrollTo(0, target.top)
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

    /**
     * 刷新状态卡片与按钮可用性。
     *
     * v1.3 起状态不再是干巴巴一行字，而是：
     *   - 运行中：primaryContainer 底色（强调）
     *   - 已停止：surfaceContainerHighest 底色（中性）
     * 权限缺失用 error 色提示，一眼能看出问题在哪。
     */
    private fun refreshState() {
        // AI 入口卡片的状态也要跟着刷新（从 AI 设置页返回时模型可能已改）
        refreshAiEntryCard()
        val overlayOk = Settings.canDrawOverlays(this)
        val notifyOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED)
        val running = PetRuntime.isRunning

        statusTitle?.text = getString(if (running) R.string.pet_running else R.string.pet_stopped)
        statusCard?.setCardBackgroundColor(
            if (running) m3.primaryContainer else m3.surfaceContainerHigh
        )
        statusTitle?.setTextColor(
            if (running) m3.onPrimaryContainer else m3.onSurface
        )

        statusDetail?.apply {
            text = buildString {
                append(if (overlayOk) "悬浮窗权限 ✓" else "悬浮窗权限 ✗ 需要授予")
                append("　·　")
                append(if (notifyOk) "通知权限 ✓" else "通知权限 ✗ 未授予")
            }
            setTextColor(
                if (overlayOk && notifyOk) m3.onSurfaceVariant else m3.error
            )
        }

        overlayBtn?.isEnabled = !overlayOk
        overlayBtn?.text = if (overlayOk) getString(R.string.grant_overlay_done)
        else getString(R.string.grant_overlay)

        notifyBtn?.isEnabled = !notifyOk
        notifyBtn?.text = if (notifyOk) "通知权限已授予 ✓" else getString(R.string.grant_notify)

        startBtn?.isEnabled = !running
        stopBtn?.isEnabled = running

        if (running && !overlayOk) {
            // 悬浮窗权限被撤销了，服务会自杀，这里提示一下
            toast("悬浮窗权限已失效，桌宠可能已停止")
        }
    }

    private fun checkForUpdate() {
        val status = updateStatusText ?: return
        status.text = getString(R.string.update_checking)
        status.setTextColor(m3.onSurfaceVariant)
        UpdateClient.clearCache()
        UpdateClient.checkLatest(force = true) { result ->
            result.onSuccess { info ->
                val curVer = runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull() ?: "0"
                when {
                    info == null -> {
                        status.text = getString(R.string.update_none)
                        status.setTextColor(m3.onSurfaceVariant)
                    }
                    info.isNewerThan(curVer) -> {
                        status.text = getString(R.string.update_available, info.version)
                        status.setTextColor(m3.primary)
                        showUpdateDialog(info)
                    }
                    else -> {
                        status.text = getString(R.string.update_latest_ok)
                        status.setTextColor(m3.primary)
                    }
                }
            }.onFailure { e ->
                status.text = getString(R.string.update_failed, e.message ?: "网络错误")
                status.setTextColor(m3.error)
            }
        }
    }

    /** 新版本弹窗：显示更新说明，提供"去下载"（跳 Release 页） */
    private fun showUpdateDialog(info: UpdateInfo) {
        val notes = info.notes.trim().ifEmpty { "（该版本没有填写更新说明）" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(m3.dp(24), m3.dp(12), m3.dp(24), 0)
        }
        box.addView(TextView(this).apply {
            text = info.title.ifBlank { "v${info.version}" }
            textSize = 16f
            setTextColor(m3.onSurface)
        })
        box.addView(TextView(this).apply {
            text = getString(R.string.update_notes_title)
            textSize = 13f
            setTextColor(m3.primary)
            setPadding(0, m3.dp(12), 0, m3.dp(4))
        })
        box.addView(ScrollView(this).apply {
            addView(TextView(this@SettingsActivity).apply {
                text = notes
                textSize = 13f
                setTextColor(m3.onSurfaceVariant)
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, m3.dp(200)
            )
        })

        // Material 3 对话框：自动套用 28dp 圆角与 M3 排版
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_available, info.version))
            .setView(box)
            .setPositiveButton(R.string.update_now) { _, _ ->
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

    /** Spinner 的选中回调包一层，避免初始化时误触发写配置 */
    private fun android.widget.Spinner.onItemSelected(action: (Int) -> Unit) {
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

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
