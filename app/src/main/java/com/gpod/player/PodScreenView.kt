package com.gpod.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.hypot
import kotlin.math.min

/** The iPod LCD: a header bar plus a stack of pages that slide left/right. */
class PodScreenView(context: Context) : View(context) {

    val pages = ArrayList<Page>()
    val top: Page get() = pages.last()

    /** Rounded or square LCD corners (Settings > Screen Corners). */
    var roundedCorners = true
        set(v) { field = v; invalidate() }
    var isPlaying = false
    var hasMedia = false
    var batteryLevel = 1f
    var charging = false
    /** Called when a tap on the screen did something (for haptics). */
    var onTapHandled: (() -> Unit)? = null
    /** Row key that pages should highlight on their next refresh, if they contain it. */
    var selectKey: String? = null

    private var animT = 1f
    private var animDir = 0
    private var outgoing: Page? = null
    private var animator: ValueAnimator? = null

    private var flashText: String? = null
    private var flashUntil = 0L

    // Shared paints for pages
    val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    val bold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF555559.toInt()
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    val path = Path()
    private val headerPaint = Paint()
    private var headerShaderH = -1f
    private val clip = Path()
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tmpRect = Rect()
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG)
    private var placeholderKey = Float.NaN

    var headerH = 0f
        private set

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val avail = MeasureSpec.getSize(heightMeasureSpec)
        val h = min((w * 0.80f).toInt(), (avail * 0.48f).toInt())
        setMeasuredDimension(w, h)
    }

    // ---------------------------------------------------------- navigation

    fun setRoot(p: Page) {
        animator?.cancel()
        pages.clear()
        p.screen = this
        pages += p
        p.onShow()
        invalidate()
    }

    fun push(p: Page) {
        p.screen = this
        val old = pages.lastOrNull()
        pages += p
        p.onShow()
        animate(1, old)
    }

    fun pop(): Boolean {
        if (pages.size <= 1) return false
        val old = pages.removeAt(pages.size - 1)
        top.onShow()
        animate(-1, old)
        return true
    }

    fun popToRoot() {
        if (pages.size <= 1) return
        val old = pages.last()
        while (pages.size > 1) pages.removeAt(pages.size - 1)
        top.onShow()
        animate(-1, old)
    }

    fun refreshAll() {
        pages.forEach { it.onShow() }
        invalidate()
    }

    private fun animate(dir: Int, old: Page?) {
        animator?.cancel()
        outgoing = old
        animDir = dir
        animT = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 210
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { animT = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) {
                        outgoing = null
                        animT = 1f
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    fun flash(msg: String) {
        flashText = msg
        flashUntil = SystemClock.uptimeMillis() + 1100
        invalidate()
        postInvalidateDelayed(1150)
    }

    // ---------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val corner = if (roundedCorners) w * 0.025f else 0f
        clip.reset()
        clip.addRoundRect(0f, 0f, w, h, corner, corner, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        c.drawColor(Color.WHITE)

        headerH = h * 0.115f
        drawHeader(c, w)

        val ch = h - headerH
        c.save()
        c.translate(0f, headerH)
        c.clipRect(0f, 0f, w, ch)
        val out = outgoing
        if (out != null && animT < 1f) {
            val newX = if (animDir > 0) w * (1 - animT) else -w * (1 - animT)
            val oldX = if (animDir > 0) -w * animT else w * animT
            drawPage(c, out, oldX, w, ch)
            drawPage(c, top, newX, w, ch)
        } else if (pages.isNotEmpty()) {
            drawPage(c, top, 0f, w, ch)
        }
        drawFlash(c, w, ch)
        c.restore()
        c.restore()
    }

    private fun drawPage(c: Canvas, p: Page, x: Float, w: Float, h: Float) {
        c.save()
        c.translate(x, 0f)
        p.draw(c, w, h)
        c.restore()
    }

    private fun drawHeader(c: Canvas, w: Float) {
        val hh = headerH
        if (headerShaderH != hh) {
            headerShaderH = hh
            headerPaint.shader = LinearGradient(0f, 0f, 0f, hh, 0xFFFBFBFC.toInt(), 0xFFD0D3D8.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, hh, headerPaint)
        fill.color = 0xFF8A8D93.toInt()
        c.drawRect(0f, hh - hh * 0.03f, w, hh, fill)

        // title
        if (pages.isNotEmpty()) {
            bold.textSize = hh * 0.5f
            bold.color = Color.BLACK
            val t = ellipsize(top.title, bold, w * 0.62f)
            c.drawText(t, (w - bold.measureText(t)) / 2, hh * 0.66f, bold)
        }

        // play state
        val s = hh * 0.22f
        val px = hh * 0.45f
        val py = hh * 0.48f
        if (hasMedia) {
            fill.color = 0xFF2A6FD0.toInt()
            if (isPlaying) {
                path.reset()
                path.moveTo(px, py - s); path.lineTo(px + s * 1.6f, py); path.lineTo(px, py + s); path.close()
                c.drawPath(path, fill)
            } else {
                c.drawRect(px, py - s, px + s * 0.55f, py + s, fill)
                c.drawRect(px + s * 0.95f, py - s, px + s * 1.5f, py + s, fill)
            }
        }

        // battery
        val bw = hh * 0.95f
        val bh = hh * 0.44f
        val bx = w - hh * 0.4f - bw
        val by = (hh - bh) / 2
        stroke.color = 0xFF4A4C50.toInt()
        stroke.strokeWidth = hh * 0.05f
        c.drawRoundRect(bx, by, bx + bw, by + bh, bh * 0.15f, bh * 0.15f, stroke)
        fill.color = 0xFF4A4C50.toInt()
        c.drawRect(bx + bw, by + bh * 0.3f, bx + bw + hh * 0.07f, by + bh * 0.7f, fill)
        val inset = hh * 0.07f
        val lvl = batteryLevel.coerceIn(0f, 1f)
        val pct = Math.round(lvl * 100)
        fill.color = when {
            pct <= 15 -> 0xFFE0453A.toInt() // red: 0–15%
            pct < 50 -> 0xFFF2C230.toInt()  // yellow: 16–49%
            else -> 0xFF5DBB3C.toInt()      // green: 50%+
        }
        c.drawRect(bx + inset, by + inset, bx + inset + (bw - 2 * inset) * lvl, by + bh - inset, fill)

        // charging: lightning bolt over the battery, like the iPod classic
        if (charging) {
            val cx = bx + bw / 2
            val cy = by + bh / 2
            val u = bh * 0.34f // bolt half-height: fits inside the outline
            path.reset()
            path.moveTo(cx + u * 0.30f, cy - u)
            path.lineTo(cx - u * 0.42f, cy + u * 0.12f)
            path.lineTo(cx - u * 0.02f, cy + u * 0.12f)
            path.lineTo(cx - u * 0.30f, cy + u)
            path.lineTo(cx + u * 0.42f, cy - u * 0.12f)
            path.lineTo(cx + u * 0.02f, cy - u * 0.12f)
            path.close()
            stroke.color = 0xFF4A4C50.toInt()
            stroke.strokeWidth = hh * 0.025f
            c.drawPath(path, stroke)
            fill.color = Color.WHITE
            c.drawPath(path, fill)
        }
    }

    private fun drawFlash(c: Canvas, w: Float, h: Float) {
        val msg = flashText ?: return
        if (SystemClock.uptimeMillis() > flashUntil) {
            flashText = null; return
        }
        bold.textSize = h * 0.085f
        bold.color = Color.WHITE
        val tw = bold.measureText(msg)
        val bw = tw + h * 0.16f
        val bh = h * 0.2f
        val l = (w - bw) / 2
        val t = (h - bh) / 2
        fill.color = 0xD0202022.toInt()
        c.drawRoundRect(l, t, l + bw, t + bh, bh * 0.2f, bh * 0.2f, fill)
        c.drawText(msg, (w - tw) / 2, t + bh * 0.64f, bold)
        bold.color = Color.BLACK
    }

    // ---------------------------------------------------------- helpers for pages

    fun ellipsize(s: String, p: TextPaint, maxW: Float): String =
        if (maxW <= 0f) "" else TextUtils.ellipsize(s, p, maxW, TextUtils.TruncateAt.END).toString()

    fun drawCover(c: Canvas, bmp: Bitmap, dst: RectF) {
        val bw = bmp.width
        val bh = bmp.height
        val target = dst.width() / dst.height()
        if (bw.toFloat() / bh > target) {
            val sw = (bh * target).toInt()
            tmpRect.set((bw - sw) / 2, 0, (bw - sw) / 2 + sw, bh)
        } else {
            val sh = (bw / target).toInt()
            tmpRect.set(0, (bh - sh) / 2, bw, (bh - sh) / 2 + sh)
        }
        c.drawBitmap(bmp, tmpRect, dst, bmpPaint)
    }

    fun drawPlaceholderArt(c: Canvas, dst: RectF) {
        if (placeholderKey != dst.top * 31 + dst.bottom) {
            placeholderKey = dst.top * 31 + dst.bottom
            placeholder.shader = LinearGradient(0f, dst.top, 0f, dst.bottom, 0xFFE9EAEC.toInt(), 0xFFB9BBC0.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(dst, placeholder)
        bold.textSize = dst.height() * 0.5f
        bold.color = 0xFF8D9096.toInt()
        val note = "♪"
        c.drawText(note, dst.centerX() - bold.measureText(note) / 2, dst.centerY() + dst.height() * 0.17f, bold)
        bold.color = Color.BLACK
    }

    // ---------------------------------------------------------- touch

    private var downX = 0f
    private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val slop = 12 * resources.displayMetrics.density
                if (hypot(e.x - downX, e.y - downY) < slop && e.y > headerH && outgoing == null && pages.isNotEmpty()) {
                    if (top.onTap(e.x, e.y - headerH, width.toFloat(), height - headerH)) {
                        onTapHandled?.invoke()
                        invalidate()
                    }
                }
                return true
            }
        }
        return true
    }
}
