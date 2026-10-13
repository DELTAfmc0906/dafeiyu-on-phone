package com.dafeiyu.pet.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Material You 3 组件工厂（v1.3 新增）。
 *
 * ## 为什么单独抽一个
 *
 * 项目所有界面都是代码构建的（无 XML 布局），如果每个界面各自
 * `TextView(...).apply { setTextColor(Color.parseColor("#333")) }`，
 * 颜色就会散落在各处、硬编码，换主题时必然漏改。
 *
 * 这里把「取 M3 颜色角色」和「套 M3 形状/字体令牌」两件事集中收口：
 *
 * ```kotlin
 * // 之前（硬编码，深色模式下面目全非）
 * tv.setTextColor(Color.parseColor("#33333D"))
 *
 * // 现在（引用颜色角色，深浅主题与动态取色都自动适配）
 * factory.onSurface(tv)
 * ```
 *
 * ## 用到的 M3 规则
 *
 * - **颜色**：`colorOnSurface` / `colorOnSurfaceVariant` / `colorPrimary` /
 *   `colorPrimaryContainer` / `colorSurfaceContainer*` —— 一律通过
 *   [MaterialColors.getColor] 从主题解析，不写死色值
 * - **形状**：卡片与对话框 16dp（L 档），按钮胶囊（Full 档）
 * - **字体**：区块标题 TitleMedium、正文 BodyMedium、说明 BodySmall
 */
class M3ViewFactory(private val context: Context) {

    private val density = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    fun dpf(v: Int) = (v * density)

    // =============================== 颜色角色 ===============================

    /**
     * 从当前主题解析 M3 颜色角色。
     *
     * 注意两个"陷阱属性"：
     *   - `colorPrimary` 和 `colorError` 并**不是** Material 库定义的，
     *     它们来自 AppCompat（Material 只定义了 colorPrimaryContainer /
     *     colorErrorContainer 这些容器色）
     *   - 其余 surface / outline / container 系列都是 Material 库的
     *
     * 引用错了会在编译期报 Unresolved reference —— 这个坑我踩过一次。
     */
    fun role(attr: Int): Int = MaterialColors.getColor(context, attr, 0)

    /** 主要文字色 */
    val onSurface: Int get() = role(com.google.android.material.R.attr.colorOnSurface)
    /** 次要文字色（说明、副标题） */
    val onSurfaceVariant: Int get() = role(com.google.android.material.R.attr.colorOnSurfaceVariant)
    /** 主色（来自 AppCompat） */
    val primary: Int get() = role(androidx.appcompat.R.attr.colorPrimary)
    /** 主色容器（浅底强调块） */
    val primaryContainer: Int get() = role(com.google.android.material.R.attr.colorPrimaryContainer)
    val onPrimaryContainer: Int
        get() = role(com.google.android.material.R.attr.colorOnPrimaryContainer)
    /** 次级容器（聊天气泡、tag 底色） */
    val secondaryContainer: Int
        get() = role(com.google.android.material.R.attr.colorSecondaryContainer)
    val onSecondaryContainer: Int
        get() = role(com.google.android.material.R.attr.colorOnSecondaryContainer)
    /** 表面层级：Low 用于卡片底、High 用于更突出的容器 */
    val surface: Int get() = role(com.google.android.material.R.attr.colorSurface)
    val surfaceContainerLow: Int
        get() = role(com.google.android.material.R.attr.colorSurfaceContainerLow)
    val surfaceContainer: Int
        get() = role(com.google.android.material.R.attr.colorSurfaceContainer)
    val surfaceContainerHigh: Int
        get() = role(com.google.android.material.R.attr.colorSurfaceContainerHigh)
    val outline: Int get() = role(com.google.android.material.R.attr.colorOutline)
    val outlineVariant: Int get() = role(com.google.android.material.R.attr.colorOutlineVariant)
    /** 错误色（来自 AppCompat） */
    val error: Int get() = role(androidx.appcompat.R.attr.colorError)
    val errorContainer: Int = role(com.google.android.material.R.attr.colorErrorContainer)
    val onErrorContainer: Int
        get() = role(com.google.android.material.R.attr.colorOnErrorContainer)

    // =============================== 文字 ===============================

    /**
     * 区块标题（TitleMedium）。
     *
     * M3 里区块标题应当用主色、带字重，和正文拉开层次。
     * 之前是 `textSize = 15f` 加一个下划线，比较随意。
     */
    fun sectionTitle(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(primary)
        setPadding(0, dp(20), 0, dp(8))
    }

    /** 正文 */
    fun body(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTextColor(onSurface)
    }

    /** 辅助说明（BodySmall） */
    fun hint(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(onSurfaceVariant)
        setLineSpacing(0f, 1.3f)
    }

    /** 字段标签（LabelLarge） */
    fun fieldLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(onSurfaceVariant)
        setPadding(0, dp(8), 0, dp(2))
    }

    // =============================== 容器 ===============================

    /**
     * 卡片。[MaterialCardView] 已经是 M3 风格：
     * 走 `colorSurfaceContainerLow` 底 + 16dp 圆角 + 细描边，
     * 而不是 M2 那种重阴影。
     */
    fun card(): MaterialCardView = MaterialCardView(context).apply {
        radius = dpf(16)
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = outlineVariant
        setCardBackgroundColor(surfaceContainerLow)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
    }

    /** 卡片内的竖向内容容器 */
    fun cardContent(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(16))
    }

    // =============================== 按钮 ===============================

    /**
     * M3 按钮。三种 variant 对应 M3 的按钮层级：
     *
     * - FILLED（filled button）：页面上**唯一**的主操作，一屏只该有一个
     * - TONAL（filled tonal）：次一级操作，用 primaryContainer 底
     * - OUTLINED：低优先级、需要边界的操作
     */
    enum class Variant { FILLED, TONAL, OUTLINED }

    fun button(text: String, variant: Variant = Variant.TONAL): MaterialButton =
        MaterialButton(context).apply {
            this.text = text
            textSize = 14f
            isAllCaps = false            // M3 不用全大写
            cornerRadius = dp(20)         // 胶囊感（Full 档的近似）
            minHeight = dp(40)
            insetTop = 0
            insetBottom = 0
            when (variant) {
                Variant.FILLED -> {
                    backgroundTintList = ColorStateList.valueOf(primary)
                    setTextColor(role(com.google.android.material.R.attr.colorOnPrimary))
                }
                Variant.TONAL -> {
                    backgroundTintList = ColorStateList.valueOf(primaryContainer)
                    setTextColor(onPrimaryContainer)
                }
                Variant.OUTLINED -> {
                    backgroundTintList = ColorStateList.valueOf(surface)
                    setTextColor(primary)
                    strokeWidth = dp(1)
                    strokeColor = ColorStateList.valueOf(outline)
                }
            }
        }

    /**
     * M3 开关。[MaterialSwitch] 是 M3 规范里的 switch：滑块更大、
     * 选中时轨道填充主色，比系统 CheckBox 更符合 M3 观感。
     *
     * **注意**：这里必须用 **ColorStateList** 分别指定选中/未选中，
     * 不能只 `thumbTintList = ColorStateList.valueOf(primary)` ——
     * 单一颜色会把"未选中"状态也染成主色，于是所有开关看起来都是打开的。
     * 这个 bug 我在真机截图里抓到过。
     */
    fun switch(checked: Boolean): MaterialSwitch = MaterialSwitch(context).apply {
        isChecked = checked

        // 滑块：选中=onPrimary（在主色轨道上要白/浅色），未选中=outline
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(
                role(com.google.android.material.R.attr.colorOnPrimary),
                outline
            )
        )
        // 轨道：选中=primary，未选中=surfaceContainerHighest，边框=outline
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(primary, surfaceContainerHigh)
        )
    }

    /** 「开关 + 标题 + 说明」一行的标准布局 */
    fun switchRow(title: String, checked: Boolean, hint: String? = null): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        column.addView(body(title))
        if (!hint.isNullOrEmpty()) {
            column.addView(hint(hint).apply { setPadding(0, dp(2), dp(8), 0) })
        }
        val sw = switch(checked)
        row.addView(column)
        row.addView(sw)
        row.tag = sw   // 方便调用方拿回开关
        return row
    }

    // =============================== 杂项 ===============================

    /** 分隔线（outlineVariant，M3 里比背景深一点点即可） */
    fun divider(): View = View(context).apply {
        setBackgroundColor(outlineVariant)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(8); bottomMargin = dp(8) }
    }

    /** 让 TextView 使用指定 M3 颜色角色 */
    fun tint(view: TextView, color: Int): TextView = view.apply { setTextColor(color) }

    /** 页面根容器的标准内边距 */
    fun pageRoot(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(40))
        setBackgroundColor(surface)
    }
}
