package com.dafeiyu.pet.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.dafeiyu.pet.R

/** 朝向决定用哪张视图，与原程序 `_sprite_key()` 的映射一致。 */
enum class Facing(val view: View) {
    /** 向上走 -> 背面 */
    UP(View.BACK),

    /** 向下走 -> 正面 */
    DOWN(View.FRONT),

    /** 向左走 -> 侧面（水平镜像） */
    LEFT(View.SIDE),

    /** 向右走 -> 侧面 */
    RIGHT(View.SIDE);

    enum class View(val resBase: String) {
        FRONT("front"),
        SIDE("side"),
        BACK("back")
    }

    /** 侧面朝左时水平翻转，朝右时不翻转 */
    val mirrored: Boolean get() = this == LEFT
}

/**
 * 精灵图加载与缓存。
 *
 * 原程序准备了 4 档预渲染尺寸（340 / 306 / 238 / 187），运行时按"最接近目标高度的档位"
 * 取图，避免每次缩放掉画质。Android 上沿用同样策略：
 * 先选最接近的档位，再精确缩放到目标高度。
 *
 * 图片放在 `res/drawable-nodpi`，这样系统不会按屏幕密度二次缩放。
 */
class SpriteLoader(context: Context) {

    /** 原始素材里有的档位高度 */
    private val availableHeights = intArrayOf(340, 306, 238, 187)

    /** 缓存：key = "front_238" / "side_238_m"（带 _m 表示镜像） */
    private val cache = HashMap<String, Bitmap>()

    private val res: android.content.res.Resources = context.resources
    private val pkg = context.packageName

    init {
        // 预热全部 3 视图 x 4 档位 x 2 朝向 = 24 张，总共约 1.2 MB，代价可接受
        for (v in Facing.View.entries) {
            for (h in availableHeights) {
                load(v, h, mirrored = false)
                load(v, h, mirrored = true)
            }
        }
    }

    private fun rawId(view: Facing.View, height: Int): Int =
        res.getIdentifier("${view.resBase}_$height", "drawable", pkg)

    private fun load(view: Facing.View, height: Int, mirrored: Boolean): Bitmap? {
        val key = "${view.resBase}_$height" + if (mirrored) "_m" else ""
        cache[key]?.let { return it }
        val id = rawId(view, height)
        if (id == 0) return null
        val bmp = BitmapFactory.decodeResource(res, id) ?: return null
        val out = if (mirrored) {
            val m = Matrix().apply { preScale(-1f, 1f) }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } else {
            bmp
        }
        cache[key] = out
        return out
    }

    /** 选最接近目标高度的档位 */
    private fun bestHeightFor(targetHeight: Int): Int =
        availableHeights.minByOrNull { kotlin.math.abs(it - targetHeight) } ?: 340

    /**
     * 取得用于绘制的位图，保证高度等于 [targetHeight]（四舍五入）。
     * 结果按 (视图, 档位, 朝向, 目标高度) 缓存。
     */
    fun sprite(view: Facing.View, targetHeight: Int, mirrored: Boolean): Bitmap? {
        val th = targetHeight.coerceAtLeast(16)
        val h = bestHeightFor(th)
        val src = load(view, h, mirrored) ?: return null
        if (src.height == th) return src
        val key = "${view.resBase}_${h}" + (if (mirrored) "_m" else "") + "@$th"
        cache[key]?.let { return it }
        val w = (src.width.toFloat() / src.height * th).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, th, true)
        cache[key] = scaled
        return scaled
    }

    /** 按朝向取图 */
    fun sprite(facing: Facing, targetHeight: Int): Bitmap? =
        sprite(facing.view, targetHeight, facing.mirrored)

    /** 供通知栏小图标用 */
    fun iconBitmap(): Bitmap? {
        val id = res.getIdentifier("pet_icon", "drawable", pkg)
        return if (id != 0) BitmapFactory.decodeResource(res, id) else null
    }
}
