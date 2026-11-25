package com.gpod.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sign

class ClickWheelView(context: Context) : View(context) {

    enum class Button { MENU, NEXT, PREV, PLAY, CENTER }

    interface Listener {
        fun onWheel(delta: Int)
        fun onButton(b: Button)
        fun onHoldStart(b: Button)
        fun onHoldEnd(b: Button)
    }

    var listener: Listener? = null
    var skin: Skin = Skin.SILVER
        set(v) { field = v; shadersFor = -1f; invalidate() }
    /** Detents per full rotation. */
    var ticksPerRev = 22

    private var cx = 0f
    private var cy = 0f
    private var r = 0f
    private var cr = 0f

    private var downButton: Button? = null
    private var pressed: Button? = null
    private var held: Button? = null
    private var moved = false
    private var tracking = false
    private var lastAngle = 0.0
    private var acc = 0.0

    private val holdRunnable = Runnable {
        val b = downButton ?: return@Runnable
        if (!moved) {
            held = b
            listener?.onHoldStart(b)
        }
    }

    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.06f
    }
    private val path = Path()
    private var shadersFor = -1f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        r = min(w, h) * 0.42f
        cr = r * 0.355f
        shadersFor = -1f
    }

    private fun ensureShaders() {
        if (shadersFor == r) return
        shadersFor = r
        wheelPaint.shader = LinearGradient(0f, cy - r, 0f, cy + r, skin.wheelTop, skin.wheelBottom, Shader.TileMode.CLAMP)
        centerPaint.shader = LinearGradient(0f, cy - cr, 0f, cy + cr, skin.centerTop, skin.centerBottom, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        ensureShaders()
        // wheel
        c.drawCircle(cx, cy, r, wheelPaint)
        edge.color = skin.wheelEdge
        edge.strokeWidth = r * 0.012f
        c.drawCircle(cx, cy, r, edge)

        // (No press highlight: haptics are the feedback.)

        // labels
        val s = r * 0.05f // skip + play/pause glyph size (MENU text sized separately)
        fill.color = skin.label
        label.color = skin.label
        label.textSize = r * 0.088f
        c.drawText("MENU", cx, cy - r * 0.68f - (label.ascent() + label.descent()) / 2, label)
        drawSkip(c, cx + r * 0.70f, cy, s, forward = true)
        drawSkip(c, cx - r * 0.70f, cy, s, forward = false)
        drawPlayPause(c, cx, cy + r * 0.70f, s)

        // center button
        c.drawCircle(cx, cy, cr, centerPaint)
        edge.color = skin.centerEdge
        edge.strokeWidth = r * 0.01f
        c.drawCircle(cx, cy, cr, edge)
    }

    private fun tri(c: Canvas, x0: Float, y: Float, s: Float, dir: Int) {
        path.reset()
        path.moveTo(x0, y - s)
        path.lineTo(x0 + dir * s * 1.25f, y)
        path.lineTo(x0, y + s)
        path.close()
        c.drawPath(path, fill)
    }

    private fun drawSkip(c: Canvas, x: Float, y: Float, s: Float, forward: Boolean) {
        val d = if (forward) 1 else -1
        val w = s * 1.25f
        tri(c, x - d * (w + s * 0.15f), y, s, d)
        tri(c, x - d * s * 0.15f, y, s, d)
        val bx = x + d * (s * 0.15f + w)
        c.drawRect(min(bx, bx + d * s * 0.28f), y - s, maxOf(bx, bx + d * s * 0.28f), y + s, fill)
    }

    private fun drawPlayPause(c: Canvas, x: Float, y: Float, s: Float) {
        val total = s * 1.25f + s * 0.45f + s * 0.95f
        val x0 = x - total / 2
        tri(c, x0, y, s, 1)
        val bx = x0 + s * 1.25f + s * 0.45f
        val bw = s * 0.32f
        c.drawRect(bx, y - s, bx + bw, y + s, fill)
        c.drawRect(bx + s * 0.63f, y - s, bx + s * 0.63f + bw, y + s, fill)
    }

    private fun quadrant(dx: Float, dy: Float): Button {
        val deg = Math.toDegrees(atan2(dy, dx).toDouble())
        return when {
            deg >= -45 && deg < 45 -> Button.NEXT
            deg >= 45 && deg < 135 -> Button.PLAY
            deg >= -135 && deg < -45 -> Button.MENU
            else -> Button.PREV
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val dx = e.x - cx
        val dy = e.y - cy
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dist = hypot(dx, dy)
                if (dist > r * 1.1f) return false
                downButton = if (dist <= cr) Button.CENTER else quadrant(dx, dy)
                pressed = downButton
                held = null
                moved = false
                tracking = dist > cr
                lastAngle = atan2(dy, dx).toDouble()
                acc = 0.0
                postDelayed(holdRunnable, 550)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dist = hypot(dx, dy)
                // Allow a drag that starts on the center button to become a scroll once it reaches the ring.
                // (A press on the center needs a clear move onto the ring, so a slightly
                // wobbly press still counts as Select.)
                if (!tracking && dist > cr * 1.3f && held == null) {
                    tracking = true
                    lastAngle = atan2(dy, dx).toDouble()
                }
                if (!tracking || held != null || dist < r * 0.2f) return true
                val a = atan2(dy, dx).toDouble()
                var d = a - lastAngle
                if (d > PI) d -= 2 * PI
                if (d < -PI) d += 2 * PI
                lastAngle = a
                acc += d
                val step = 2 * PI / ticksPerRev
                while (abs(acc) >= step) {
                    val s = sign(acc)
                    acc -= s * step
                    if (!moved) {
                        moved = true
                        removeCallbacks(holdRunnable)
                        pressed = null
                        invalidate()
                    }
                    listener?.onWheel(s.toInt())
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                val h = held
                if (h != null) {
                    listener?.onHoldEnd(h)
                } else if (!moved && e.actionMasked == MotionEvent.ACTION_UP) {
                    downButton?.let { listener?.onButton(it) }
                }
                held = null
                downButton = null
                pressed = null
                invalidate()
                return true
            }
        }
        return true
    }
}
