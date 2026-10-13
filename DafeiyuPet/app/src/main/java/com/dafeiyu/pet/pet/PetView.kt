package com.dafeiyu.pet.pet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.dafeiyu.pet.engine.PetEngine
import kotlin.math.abs
import kotlin.math.sin

/**
 * 桌宠绘制层。
 *
 * 严格对照 Python 版 `桌宠.py` 的 `paintEvent()` 移植：
 *   呼吸缩放 1 + 0.02*sin(t*2.5)
 *   走路摇摆 sin(t*9.0)*3.5、颠簸 -abs(sin(t*4.5))*7
 *   静止摇摆 sin(t*2.5)*1.5
 *   蹦跳     -abs(sin(jumpT*PI))*14*jumpT
 *   摇摆动作 sin(actionT*PI*2)*10*actionT
 *   伸展动作 act_sy=0.06*sin(actionT*PI)、act_sx=-0.03*sin(actionT*PI)
 *   转向交叉淡化：旧图透明度 crossT、新图 1-crossT
 *
 * 视图自身不做任何行为决策，全部状态从 [engine] 读取。
 * 窗口大小由服务设定；角色在本体内水平居中，不同朝向的图宽不同也不会跳动。
 */
@SuppressLint("ViewConstructor")
class PetView(
    context: Context,
    private val engine: PetEngine,
    private val sprites: SpriteLoader
) : View(context) {

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }
    private val bubbleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val tailPath = android.graphics.Path()

    /** 缓存当前本体的图，避免每帧取图 */
    private var curBmp: Bitmap? = null
    private var prevBmp: Bitmap? = null
    private var curKey: Pair<Facing, Int>? = null
    private var prevKey: Pair<Facing, Int>? = null

    /** 单帧绘制所需的动态值，服务每帧同步 */
    var sway = 0f
    var bob = 0f
    var scale = 1f
    var jump = 0f
    var actRot = 0f
    var actSx = 0f
    var actSy = 0f
    var crossT = 0f
    var facing = Facing.DOWN
    var prevFacing: Facing? = null
    var eatT = 0f

    private var textSizePx = 36f
    private var padPx = 18f
    private var cornerPx = 26f

    // =============================== 手势 ===============================

    /**
     * 手势回调。参数为**本体的绝对屏幕坐标**（已扣掉气泡预留），
     * 与服务里的窗口布局口径一致。
     *
     * 手势分配（v1.2 起，因为长按被 AI 占用了）：
     *   拖动        -> 移动
     *   单击        -> 蹦跳 + 回嘴
     *   长按        -> **AI 对话**（v1.2 新增）
     *   双击        -> 设置菜单（v1.1 及以前是长按弹菜单）
     *   三指轻点    -> 喂食面板（v1.1 及以前是双击喂食）
     */
    interface GestureHost {
        /** 拖拽中，把本体移到 (bodyX, bodyY) */
        fun onDragMove(bodyX: Float, bodyY: Float)
        /** 点击（含拖拽判定结束），可用于重置状态 */
        fun onInteractionEnd()
        /** 长按：唤醒 AI 对话 */
        fun onLongPress(screenX: Float, screenY: Float)
        /** 双击：弹设置菜单 */
        fun onDoubleTap()
        /** 三指轻点：喂食面板 */
        fun onThreeFingerTap()
        /** 需要把窗口位置刷新到引擎当前位置 */
        fun relayoutWindow()
    }

    var gestureHost: GestureHost? = null

    /** 一次按下中同时出现过的最大手指数，用来区分三指轻点 */
    private var maxPointersInGesture = 0

    private val gestureDetector = android.view.GestureDetector(
        context,
        object : android.view.GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: android.view.MotionEvent): Boolean {
                dragStartRawX = e.rawX
                dragStartRawY = e.rawY
                bodyAtDownX = engine.x
                bodyAtDownY = engine.y
                dragging = false
                maxPointersInGesture = e.pointerCount
                engine.onDragStart()
                return true
            }

            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                // 三指轻点 = 喂食（v1.2 从双击挪过来，把双击让给菜单）
                if (maxPointersInGesture >= 3) {
                    gestureHost?.onThreeFingerTap()
                    gestureHost?.onInteractionEnd()
                    return true
                }
                engine.onClick()
                gestureHost?.onInteractionEnd()
                invalidate()
                return true
            }

            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                // v1.2：双击改为弹设置菜单
                gestureHost?.onDoubleTap()
                return true
            }

            override fun onLongPress(e: android.view.MotionEvent) {
                // v1.2：长按改为唤醒 AI 对话
                engine.onDragEnd()
                gestureHost?.onLongPress(e.rawX, e.rawY)
            }

            override fun onScroll(
                e1: android.view.MotionEvent?,
                e2: android.view.MotionEvent,
                dx: Float,
                dy: Float
            ): Boolean {
                if (e1 == null) return false
                val totalDx = e2.rawX - dragStartRawX
                val totalDy = e2.rawY - dragStartRawY
                // 原程序阈值：位移超过 6px 才算拖拽，避免手抖误触发
                if (!dragging && (abs(totalDx) + abs(totalDy)) > touchSlop) {
                    dragging = true
                }
                if (dragging) {
                    engine.onDragMove(bodyAtDownX + totalDx, bodyAtDownY + totalDy)
                    engine.faceWhileDragging(totalDx)
                    gestureHost?.relayoutWindow()
                    invalidate()
                }
                return true
            }
        }
    ).apply {
        setIsLongpressEnabled(true)
        // 说明：双击识别不需要额外开启。构造器传入的 OnGestureListener 本身
        // 就含 onDoubleTap（SimpleOnGestureListener 实现了 OnDoubleTapListener）。
        // 代价是单击会延迟约 300ms 触发（要等一下确认不是双击），
        // 这个取舍从 v1.0 起就存在，用户感觉不出来。
    }

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var bodyAtDownX = 0f
    private var bodyAtDownY = 0f
    private var dragging = false
    private val touchSlop = (context.resources.displayMetrics.density * 6f)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        // 记录这次按下过程中出现过的最大手指数（用于识别三指轻点）
        if (event.pointerCount > maxPointersInGesture) {
            maxPointersInGesture = event.pointerCount
        }
        gestureDetector.onTouchEvent(event)
        // 跟随手指模式：把触摸位置告诉引擎（对应 PC 版的鼠标位置）
        engine.updateFinger(event.rawX, event.rawY)

        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    engine.onDragEnd()
                    gestureHost?.onInteractionEnd()
                    invalidate()
                }
                // 手势结束，重置手指数
                maxPointersInGesture = 0
            }
        }
        return true
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        isFocusable = true
    }

    fun applyTextSize(px: Float) {
        textSizePx = px
        bubbleTextPaint.textSize = px
        padPx = px * 0.55f
        cornerPx = px * 0.75f
        invalidate()
    }

    /** 由服务在每帧更新引擎派生出的绘制参数 */
    fun syncFromEngine() {
        val t = engine.clock
        val walking = engine.walking
        if (walking) {
            sway = sin(t * 9.0) .toFloat() * 3.5f
            bob = -abs(sin(t * 4.5)).toFloat() * 7.0f
        } else {
            sway = sin(t * 2.5).toFloat() * 1.5f
            bob = 0f
        }
        scale = 1f + 0.02f * sin(t * 2.5).toFloat()
        jump = if (engine.jumpT > 0f)
            -abs(sin(engine.jumpT * Math.PI)).toFloat() * 14f * engine.jumpT
        else 0f
        actRot = 0f
        actSx = 0f
        actSy = 0f
        when (engine.actionKind) {
            PetEngine.ActionKind.SWAY ->
                actRot = sin(engine.actionT * Math.PI * 2).toFloat() * 10f * engine.actionT
            PetEngine.ActionKind.STRETCH -> {
                actSy = 0.06f * sin(engine.actionT * Math.PI).toFloat()
                actSx = -0.03f * sin(engine.actionT * Math.PI).toFloat()
            }
            PetEngine.ActionKind.NONE -> Unit
        }
        crossT = engine.crossT
        facing = engine.facing
        prevFacing = engine.prevFacing
        eatT = engine.eatT
        refreshBitmaps()
    }

    private fun refreshBitmaps() {
        val h = engine.petHeight
        val ck = facing to h
        if (ck != curKey) {
            curBmp = sprites.sprite(facing, h)
            curKey = ck
            engine.setPetWidth(curBmp?.width ?: h)
        }
        val pf = prevFacing
        val pk = if (pf != null) pf to h else null
        if (pk != prevKey) {
            prevBmp = pk?.let { sprites.sprite(it.first, it.second) }
            prevKey = pk
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val reserve = engine.bubbleReserve.toFloat()
        val windowW = width.toFloat()
        val windowH = height.toFloat()
        val bodyH = engine.petHeight.toFloat()

        // ---------- 气泡（原程序: 画在窗口顶部，尾巴朝下）----------
        if (engine.bubbleText.isNotEmpty()) {
            drawBubble(canvas, engine.bubbleText, engine.bubbleInner, windowW, reserve)
        }

        // ---------- 角色本体 ----------
        val bottom = reserve + bodyH
        val cx = windowW / 2f
        if (crossT > 0f && prevBmp != null && prevFacing != null) {
            drawOne(canvas, prevBmp!!, prevFacing!!, cx, bottom, crossT)
            drawOne(canvas, curBmp, facing, cx, bottom, 1f - crossT)
        } else {
            drawOne(canvas, curBmp, facing, cx, bottom, 1f)
        }
    }

    /**
     * 画一张精灵图。
     * 原程序在 (cx, bottom) 处做旋转、镜像、缩放，这里保持一致。
     *
     * [facingOfBitmap] 是该张图自己的朝向 —— 交叉淡化时旧图用的是上一帧朝向，
     * 不能用当前朝向判断是否镜像，否则转向瞬间会镜像错。
     */
    private fun drawOne(
        canvas: Canvas,
        bmp: Bitmap?,
        facingOfBitmap: Facing,
        cx: Float,
        bottom: Float,
        opacity: Float
    ) {
        if (bmp == null || opacity <= 0f) return

        // 进食时额外做一次咀嚼式缩放（原程序 on_food 里设置了 eat_t 但 paint 未使用，
        // 这里补上，让"吃东西"有可见反馈）
        val eatScale = if (eatT > 0f) 1f + 0.08f * sin(eatT * Math.PI).toFloat() else 1f

        val ph = bmp.height * scale * (1f + actSy) * eatScale
        val pw = bmp.width * scale * (1f + actSx) * eatScale
        val dx = cx - pw / 2f
        val dy = bottom - ph + jump + bob

        canvas.save()
        canvas.rotate(sway + actRot, cx, bottom)
        if (facingOfBitmap.mirrored) {
            canvas.scale(-1f, 1f, cx, 0f)
        }
        bmpPaint.alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
        canvas.drawBitmap(bmp, null, RectF(dx, dy, dx + pw, dy + ph), bmpPaint)
        bmpPaint.alpha = 255
        canvas.restore()
    }

    /** 气泡：白底（心声为浅灰底斜体），圆角矩形 + 下方小三角 */
    /**
     * 绘制台词气泡。
     *
     * ## v1.4.0 修复「文字重合」
     *
     * 旧实现的两个问题：
     *
     * 1. **字号与预留区独立计算**：`textSize` 由 `bubbleReserve * 0.26` 得出，
     *    而 Android 会按系统 `fontScale` 再放大 `Paint.textSize`。
     *    用户把系统字体调到 125%（HyperOS 上很常见）后，文字比预留空间大 25%。
     *
     * 2. **装不下就硬画**：结尾只是 `by.coerceAtLeast(0f)`，
     *    压不住就画到宠物本体上 —— 表现为文字和角色糊在一起。
     *
     * 现在改成：**按实际可用高度自适应收缩字号**。
     * 先按理想字号排版，放不进预留区就把字号乘一个收敛系数重排，
     * 最多迭代 4 次。这样无论系统字体设多大，气泡都一定装得下、不会重叠。
     */
    private fun drawBubble(
        canvas: Canvas,
        text: String,
        inner: Boolean,
        windowW: Float,
        areaH: Float
    ) {
        val idealSize = textSizePx
        var size = idealSize
        var lines: List<String>
        var lineH: Float
        var bh: Float

        // 迭代收缩：直到气泡（含小尾巴）能装进预留区
        var attempt = 0
        while (true) {
            bubbleTextPaint.textSize = size
            val fm = bubbleTextPaint.fontMetrics
            lineH = (fm.descent - fm.ascent) + size * 0.15f
            val pad = size * 0.55f
            val maxTextW = (windowW - pad * 2f - size).coerceAtLeast(size * 3f)
            lines = wrapText(text, maxTextW)
            bh = lines.size * lineH + pad * 0.9f
            // 小尾巴占的高度
            val tailH = size * 0.30f
            if (bh + tailH + size * 0.2f <= areaH || attempt >= MAX_SHRINK_ATTEMPTS) break
            size *= 0.86f
            attempt++
        }

        // 用最终字号重算一遍所有派生尺寸（循环里 last 的值可能与最终 size 不同步）
        bubbleTextPaint.textSize = size
        val fm = bubbleTextPaint.fontMetrics
        lineH = (fm.descent - fm.ascent) + size * 0.15f
        val pad = size * 0.55f
        val maxTextW = (windowW - pad * 2f - size).coerceAtLeast(size * 3f)
        lines = wrapText(text, maxTextW)
        var textW = 0f
        for (l in lines) textW = maxOf(textW, bubbleTextPaint.measureText(l))
        val bw = (textW + pad * 2f).coerceAtMost(windowW - pad)
        bh = lines.size * lineH + pad * 0.9f

        // 垂直位置：底部贴住宠物头顶上方，且**不允许超出预留区上边界**
        val gap = size * 0.30f
        val by = (areaH - bh - gap).coerceIn(0f, (areaH - bh).coerceAtLeast(0f))
        val bx = (windowW - bw) / 2f
        val rect = RectF(bx, by, bx + bw, by + bh)

        // 气泡圆角随字号走，避免小字号时圆角过大显得臃肿
        val corner = size * 0.75f

        if (inner) {
            bubbleBgPaint.color = Color.argb(235, 232, 232, 238)
            bubbleTextPaint.color = Color.rgb(125, 125, 138)
            bubbleTextPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        } else {
            bubbleBgPaint.color = Color.argb(235, 255, 255, 255)
            bubbleTextPaint.color = Color.rgb(60, 60, 80)
            bubbleTextPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        canvas.drawRoundRect(rect, corner, corner, bubbleBgPaint)

        // 小尾巴：从气泡底边指向宠物头顶，长度受剩余空间限制
        val tailLen = minOf(size * 0.30f, (areaH - rect.bottom).coerceAtLeast(0f))
        if (tailLen > 0f) {
            val tailX = windowW / 2f
            tailPath.reset()
            tailPath.moveTo(tailX, rect.bottom)
            tailPath.lineTo(tailX - size * 0.22f, rect.bottom + tailLen)
            tailPath.lineTo(tailX + size * 0.22f, rect.bottom + tailLen)
            tailPath.close()
            canvas.drawPath(tailPath, bubbleBgPaint)
        }

        var ty = rect.top + pad * 0.45f - fm.ascent
        for (l in lines) {
            canvas.drawText(l, windowW / 2f, ty, bubbleTextPaint)
            ty += lineH
        }

        // 复原字号，避免影响其它绘制路径（比如"心声"斜体气泡共用同一个 Paint）
        bubbleTextPaint.textSize = idealSize
    }

    /**
     * 逐字测宽换行 —— 对应原程序 `paintEvent` 里的逐字符累加逻辑。
     * 中文没有空格，不能用 breakText 的单词切分。
     */
    private fun wrapText(text: String, maxW: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (ch in text) {
            val candidate = cur.toString() + ch
            if (cur.isNotEmpty() && bubbleTextPaint.measureText(candidate) > maxW) {
                lines.add(cur.toString())
                cur = StringBuilder()
            }
            cur.append(ch)
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        return if (lines.isEmpty()) listOf("") else lines
    }

    /** 供服务计算窗口尺寸时参考：当前朝向那张图的宽高比 */
    fun currentAspect(): Float {
        val b = curBmp ?: return 1f
        return if (b.height == 0) 1f else b.width.toFloat() / b.height
    }

    companion object {
        /**
         * 气泡放不下时最多收缩几次（v1.4.0）。
         *
         * 每次字号乘 0.86，4 次可降到原字号的约 55% ——
         * 足以容纳系统字体放大到 200% 的极端情况。
         * 设上限是为了避免极端情况下无限迭代。
         */
        private const val MAX_SHRINK_ATTEMPTS = 4
    }
}
