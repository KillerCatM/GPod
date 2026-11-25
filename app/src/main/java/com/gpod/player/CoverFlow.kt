package com.gpod.player

import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import android.os.SystemClock
import kotlin.math.min
import kotlin.math.sign

/**
 * iPod-style Cover Flow: the wheel scrolls through albums, the center button flips the cover
 * to show its tracks, the center button again plays, MENU flips back.
 */
class CoverFlowPage(private val host: MainActivity) : Page("Cover Flow") {

    private var albums: List<Album> = emptyList()
    private var sel = 0
    private var pos = 0f
    private var flip = 0f
    private var flipTarget = 0f
    private var track = 0
    private var trackScroll = 0
    private var firstShow = true
    private var letterUntil = 0L

    private val camera = Camera()
    private val m = Matrix()
    private val local = RectF()
    private val fade = Paint()
    private val dim = Paint()
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hl = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backRect = RectF()

    private val flipped get() = flipTarget == 1f
    private val album get() = albums.getOrNull(sel)

    override fun onShow() {
        albums = host.library?.albumsByArtist.orEmpty()
        if (firstShow && albums.isNotEmpty()) {
            firstShow = false
            // Open on the album that's playing, like the iPod.
            host.currentSong()?.let { cur -> albums.indexOfFirst { it.id == cur.albumId }.takeIf { it >= 0 }?.let { sel = it } }
            pos = sel.toFloat()
        }
        sel = if (albums.isEmpty()) 0 else sel.coerceIn(0, albums.size - 1)
    }

    override fun onWheel(d: Int): Boolean {
        return if (flipped) {
            val n = album?.songs?.size ?: 0
            val before = track
            track = (track + d).coerceIn(0, max(0, n - 1))
            track != before
        } else {
            val before = sel
            sel = (sel + d).coerceIn(0, max(0, albums.size - 1))
            sel != before
        }
    }

    override fun onSelect() {
        val a = album ?: return
        if (!flipped) {
            flipTarget = 1f
            track = 0
            trackScroll = 0
        } else {
            host.playSongs(a.songs, track)
        }
        screen.invalidate()
    }

    /** Albums are ordered by artist, so letters follow the artist name ("The" ignored). */
    private fun letterAt(i: Int): Char {
        val c = sortKey(albums[i].artist).firstOrNull()?.uppercaseChar() ?: '#'
        return if (c.isLetter()) c else '#'
    }

    private fun groupStart(i: Int): Int {
        var s = i
        while (s > 0 && letterAt(s - 1) == letterAt(i)) s--
        return s
    }

    /** ⏭ = first album of the next letter, ⏮ = first album of the previous letter. */
    override fun onSkip(dir: Int): Boolean {
        if (flipped || albums.isEmpty()) return false
        val cur = letterAt(sel)
        val target = if (dir > 0) {
            (sel + 1 until albums.size).firstOrNull { letterAt(it) != cur }
        } else {
            val start = groupStart(sel)
            if (start > 0) groupStart(start - 1) else null
        }
        if (target != null) sel = target
        letterUntil = SystemClock.uptimeMillis() + 700
        screen.postInvalidateDelayed(750)
        screen.invalidate()
        return true
    }

    override fun onMenu(): Boolean {
        if (!flipped) return false
        flipTarget = 0f
        screen.invalidate()
        return true
    }

    override fun onTap(x: Float, y: Float, w: Float, h: Float): Boolean {
        if (albums.isEmpty()) return false
        if (flipped) {
            if (flip < 0.99f) return false
            if (!backRect.contains(x, y)) return onMenu()
            val rowsTop = backRect.top + backRect.height() * HEADER
            if (y < rowsTop) return false
            val rowH = backRect.height() * (1 - HEADER) / BACK_ROWS
            val i = trackScroll + ((y - rowsTop) / rowH).toInt()
            val songs = album?.songs ?: return false
            if (i !in songs.indices) return false
            track = i
            onSelect()
            return true
        }
        val s = h * SIZE
        val dx = x - w / 2
        if (abs(dx) < s / 2) {
            onSelect()
        } else {
            val extra = ((abs(dx) - s * SPREAD) / (s * STACK)).toInt().coerceAtLeast(0)
            sel = (sel + sign(dx).toInt() * (1 + extra)).coerceIn(0, albums.size - 1)
        }
        return true
    }

    override fun draw(c: Canvas, w: Float, h: Float) {
        screen.fill.color = Color.BLACK
        c.drawRect(0f, 0f, w, h, screen.fill)
        if (albums.isEmpty()) {
            val t = screen.sub
            t.textSize = h * 0.075f
            t.color = 0xFFA0A0A5.toInt()
            val msg = if (host.library == null) "Loading…" else "No albums"
            c.drawText(msg, (w - t.measureText(msg)) / 2, h / 2, t)
            return
        }

        // Ease scroll + flip towards their targets.
        var animating = false
        val diff = sel - pos
        if (abs(diff) > 0.002f) {
            pos += diff * 0.25f; animating = true
        } else pos = sel.toFloat()
        val fd = flipTarget - flip
        if (abs(fd) > 0.01f) {
            flip += sign(fd) * min(abs(fd), 0.09f); animating = true
        } else flip = flipTarget
        if (animating) screen.postInvalidateOnAnimation()

        val s = h * SIZE
        val cx = w / 2
        val cy = h * 0.43f
        camera.setLocation(0f, 0f, -s * 5f / 72f)

        val first = max(0, floor(pos).toInt() - 7)
        val last = min(albums.size - 1, ceil(pos).toInt() + 7)
        val order = (first..last).sortedByDescending { abs(it - pos) }
        for (i in order) {
            if (flip > 0f && i == sel) continue
            drawCover(c, i, i - pos, s, cx, cy, flip * 0.85f)
        }
        if (flip > 0f) drawFlipping(c, s, w, h, cx, cy)

        // Big letter while jumping with ⏮/⏭
        if (SystemClock.uptimeMillis() < letterUntil && flip == 0f) {
            val letter = letterAt(sel).toString()
            val box = h * 0.3f
            val l = (w - box) / 2
            val t = cy - box / 2
            screen.fill.color = 0xD0303034.toInt()
            c.drawRoundRect(l, t, l + box, t + box, box * 0.15f, box * 0.15f, screen.fill)
            val b = screen.bold
            b.textSize = box * 0.62f
            b.color = Color.WHITE
            c.drawText(letter, l + (box - b.measureText(letter)) / 2, t + box * 0.72f, b)
            b.color = Color.BLACK
        }

        // Title + artist
        if (flip < 0.5f) {
            val a = albums[sel]
            val alpha = ((1 - flip * 2) * 255).toInt()
            val b = screen.bold
            b.textSize = h * 0.068f
            b.color = Color.argb(alpha, 255, 255, 255)
            val t1 = screen.ellipsize(a.title, b, w * 0.9f)
            c.drawText(t1, (w - b.measureText(t1)) / 2, h * 0.875f, b)
            b.color = Color.BLACK
            val sp = screen.sub
            sp.textSize = h * 0.058f
            sp.color = Color.argb(alpha, 170, 170, 176)
            val t2 = screen.ellipsize(a.artist, sp, w * 0.9f)
            c.drawText(t2, (w - sp.measureText(t2)) / 2, h * 0.955f, sp)
        }
    }

    private fun drawCover(c: Canvas, i: Int, o: Float, s: Float, cx: Float, cy: Float, extraDim: Float) {
        val a = abs(o)
        val x = if (a <= 1f) o * s * SPREAD else sign(o) * (s * SPREAD + (a - 1f) * s * STACK)
        val angle = o.coerceIn(-1f, 1f) * ANGLE
        val depth = min(a, 1f) * s * 0.45f
        camera.save()
        camera.translate(0f, 0f, depth)
        camera.rotateY(angle)
        camera.getMatrix(m)
        camera.restore()
        m.preTranslate(-s / 2, -s / 2)
        m.postTranslate(cx + x, cy)

        c.save()
        c.concat(m)
        local.set(0f, 0f, s, s)
        paintArt(c, albums[i])
        // reflection
        c.save()
        c.clipRect(0f, s, s, s * 1.38f)
        c.translate(0f, 2 * s)
        c.scale(1f, -1f)
        paintArt(c, albums[i])
        c.restore()
        fade.shader = LinearGradient(0f, s, 0f, s * 1.4f, 0xA0000000.toInt(), Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRect(0f, s - 0.5f, s, s * 1.4f + 1f, fade)
        val d = min(1f, min(a, 1f) * 0.35f + extraDim)
        if (d > 0f) {
            dim.color = Color.argb((d * 255).toInt(), 0, 0, 0)
            c.drawRect(0f, 0f, s, s * 1.4f, dim)
        }
        c.restore()
    }

    private fun paintArt(c: Canvas, a: Album) {
        val bmp = a.songs.firstOrNull()?.let { host.art.get(it) }
        if (bmp != null) screen.drawCover(c, bmp, local) else screen.drawPlaceholderArt(c, local)
    }

    private fun drawFlipping(c: Canvas, s: Float, w: Float, h: Float, cx: Float, cy: Float) {
        val deg = flip * 180f
        if (deg <= 90f) {
            camera.save()
            camera.rotateY(deg)
            camera.getMatrix(m)
            camera.restore()
            m.preTranslate(-s / 2, -s / 2)
            m.postTranslate(cx, cy)
            c.save()
            c.concat(m)
            local.set(0f, 0f, s, s)
            paintArt(c, albums[sel])
            c.restore()
            return
        }
        val bw = w * 0.84f
        val bh = h * 0.9f
        backRect.set(cx - bw / 2, h / 2 - bh / 2, cx + bw / 2, h / 2 + bh / 2)
        camera.save()
        camera.rotateY(deg - 180f)
        camera.getMatrix(m)
        camera.restore()
        m.preTranslate(-bw / 2, -bh / 2)
        m.postTranslate(cx, h / 2)
        c.save()
        c.concat(m)
        drawBack(c, bw, bh)
        c.restore()
    }

    private fun drawBack(c: Canvas, bw: Float, bh: Float) {
        val a = albums[sel]
        panel.shader = LinearGradient(0f, 0f, 0f, bh, 0xFFFFFFFF.toInt(), 0xFFE3E4E7.toInt(), Shader.TileMode.CLAMP)
        val r = bh * 0.03f
        c.drawRoundRect(0f, 0f, bw, bh, r, r, panel)

        val hh = bh * HEADER
        val b = screen.bold
        b.color = Color.BLACK
        b.textSize = hh * 0.34f
        val t1 = screen.ellipsize(a.title, b, bw * 0.9f)
        c.drawText(t1, (bw - b.measureText(t1)) / 2, hh * 0.45f, b)
        val sp = screen.sub
        sp.textSize = hh * 0.28f
        sp.color = 0xFF55555A.toInt()
        val t2 = screen.ellipsize(a.artist, sp, bw * 0.9f)
        c.drawText(t2, (bw - sp.measureText(t2)) / 2, hh * 0.82f, sp)
        screen.fill.color = 0xFFB5B7BC.toInt()
        c.drawRect(0f, hh - 1.5f, bw, hh, screen.fill)

        val songs = a.songs
        if (track < trackScroll) trackScroll = track
        if (track >= trackScroll + BACK_ROWS) trackScroll = track - BACK_ROWS + 1
        val rowH = (bh - hh) / BACK_ROWS
        hl.shader = LinearGradient(0f, 0f, 0f, rowH, 0xFF6DB3F6.toInt(), 0xFF1D6BD2.toInt(), Shader.TileMode.CLAMP)
        val p = screen.text
        p.textSize = rowH * 0.48f
        sp.textSize = rowH * 0.42f
        val pad = rowH * 0.3f
        for (row in 0 until BACK_ROWS) {
            val i = trackScroll + row
            if (i >= songs.size) break
            val y = hh + row * rowH
            val on = i == track
            if (on) {
                c.save(); c.translate(0f, y)
                if (row == BACK_ROWS - 1 || i == songs.size - 1) c.clipRect(0f, 0f, bw, rowH - r * 0.3f)
                c.drawRect(0f, 0f, bw, rowH, hl)
                c.restore()
            }
            val dur = fmt(songs[i].duration)
            sp.color = if (on) Color.WHITE else 0xFF606065.toInt()
            val dw = sp.measureText(dur)
            c.drawText(dur, bw - pad - dw, y + rowH * 0.67f, sp)
            p.color = if (on) Color.WHITE else Color.BLACK
            c.drawText(screen.ellipsize(songs[i].title, p, bw - dw - pad * 3), pad, y + rowH * 0.67f, p)
        }
        p.color = Color.BLACK
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        private const val SIZE = 0.58f    // cover size relative to the screen height
        private const val SPREAD = 0.78f  // gap between the center cover and its neighbours
        private const val STACK = 0.24f   // spacing of the stacked side covers
        private const val ANGLE = 62f     // side-cover tilt
        private const val HEADER = 0.2f
        private const val BACK_ROWS = 6
    }
}
