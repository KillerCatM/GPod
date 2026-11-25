package com.gpod.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import androidx.media3.common.Player
import kotlin.math.max

abstract class Page(open val title: String) {
    lateinit var screen: PodScreenView
    abstract fun draw(c: Canvas, w: Float, h: Float)
    /** Wheel turned one detent; return true only if something changed (that's when it clicks). */
    open fun onWheel(d: Int): Boolean = false
    open fun onSelect() {}
    open fun onTap(x: Float, y: Float, w: Float, h: Float): Boolean = false
    open fun onShow() {}
    /** MENU pressed; return true if the page handled it (otherwise we go back). */
    open fun onMenu(): Boolean = false
    /** ⏮/⏭ pressed while nothing is playing; return true if the page used it (otherwise skip track). */
    open fun onSkip(dir: Int): Boolean = false
    /** Center button held; return true if handled (otherwise it acts as select). */
    open fun onHoldSelect(): Boolean = false
}

class MenuItem(
    val label: String,
    /** Defaults to a chevron unless the row shows a value. */
    chevron: Boolean? = null,
    val value: (() -> String)? = null,
    val onHold: (() -> Unit)? = null,
    /** Stable identity so the highlight can follow this row when the list changes. */
    key: String? = null,
    val action: () -> Unit,
) {
    val key: String = key ?: label

    val chevron: Boolean = chevron ?: (value == null)
}

// =====================================================================================
// Scrolling list
// =====================================================================================

class MenuPage(
    title: String,
    private val titleFn: (() -> String)? = null,
    /** Whether to show the album-art panel on the right half (checked every frame). */
    private val split: () -> Boolean = { false },
    private val dynamic: Boolean = false,
    private val emptyText: () -> String = { "No items" },
    private val artProvider: (() -> Bitmap?)? = null,
    /** Alphabetical list: ⏮/⏭ jump by letter when nothing is playing. */
    private val letterJump: Boolean = false,
    private val provider: () -> List<MenuItem>,
) : Page(title) {

    private fun letterAt(i: Int): Char {
        val c = sortKey(items[i].label).firstOrNull()?.uppercaseChar() ?: '#'
        return if (c.isLetter()) c else '#'
    }

    private fun groupStart(i: Int): Int {
        var s = i
        while (s > 0 && letterAt(s - 1) == letterAt(i)) s--
        return s
    }

    override fun onSkip(dir: Int): Boolean {
        if (!letterJump || items.isEmpty()) return false
        val cur = letterAt(sel)
        val target = if (dir > 0) {
            (sel + 1 until items.size).firstOrNull { letterAt(it) != cur }
        } else {
            val start = groupStart(sel)
            if (start > 0) groupStart(start - 1) else null
        }
        if (target != null) sel = target
        letterUntil = SystemClock.uptimeMillis() + 700
        screen.postInvalidateDelayed(750)
        return true
    }

    companion object { const val ROWS = 8 }

    override val title: String get() = titleFn?.invoke() ?: super.title

    private var items: List<MenuItem> = emptyList()
    private var sel = 0
    private var scroll = 0
    private var lastTick = 0L
    private var streak = 0
    private var letterUntil = 0L
    private val hl = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint()
    private var shadeKey = Float.NaN
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private var thumbKey = Float.NaN
    private var hlH = -1f
    private val art = RectF()

    override fun onShow() {
        val prevKey = items.getOrNull(sel)?.key
        items = provider()
        // Follow the highlighted row if the list changed around it (e.g. a rename re-sorted it),
        // or jump to a row the app asked for (e.g. a playlist that was just created).
        val want = screen.selectKey?.takeIf { k -> items.any { it.key == k } } ?: prevKey
        if (want != null && items.getOrNull(sel)?.key != want) {
            val i = items.indexOfFirst { it.key == want }
            if (i >= 0) sel = i
        }
        clamp()
    }

    private fun clamp() {
        sel = if (items.isEmpty()) 0 else sel.coerceIn(0, items.size - 1)
    }

    override fun onWheel(d: Int): Boolean {
        val before = sel
        val now = SystemClock.uptimeMillis()
        streak = if (now - lastTick < 70) streak + 1 else 0
        lastTick = now
        // Accelerate through long lists, like the iPod.
        val step = when {
            items.size > 80 && streak > 22 -> 8
            items.size > 30 && streak > 9 -> 3
            else -> 1
        }
        sel = (sel + d * step).coerceIn(0, max(0, items.size - 1))
        if (step > 1) {
            letterUntil = now + 600
            screen.postInvalidateDelayed(650)
        }
        return sel != before
    }

    override fun onSelect() {
        items.getOrNull(sel)?.action?.invoke()
        screen.invalidate()
    }

    override fun onHoldSelect(): Boolean {
        val hold = items.getOrNull(sel)?.onHold ?: return false
        hold()
        screen.invalidate()
        return true
    }

    override fun onTap(x: Float, y: Float, w: Float, h: Float): Boolean {
        val listW = if (split()) w * 0.5f else w
        if (x > listW) return false
        val i = scroll + (y / (h / ROWS)).toInt()
        if (i !in items.indices) return false
        sel = i
        onSelect()
        return true
    }

    override fun draw(c: Canvas, w: Float, h: Float) {
        if (dynamic) { items = provider(); clamp() }
        val rowH = h / ROWS
        val listW = if (split()) w * 0.5f else w
        val needBar = items.size > ROWS
        val barW = if (needBar) w * 0.032f else 0f
        val textW = listW - barW

        if (sel < scroll) scroll = sel
        if (sel >= scroll + ROWS) scroll = sel - ROWS + 1
        scroll = scroll.coerceIn(0, max(0, items.size - ROWS))

        val p = screen.text
        p.textSize = rowH * 0.5f
        val v = screen.sub
        v.textSize = rowH * 0.44f
        val pad = rowH * 0.32f
        val base = rowH * 0.68f

        if (items.isEmpty()) {
            v.color = 0xFF808085.toInt()
            val t = screen.ellipsize(emptyText(), v, listW - 2 * pad)
            c.drawText(t, (listW - v.measureText(t)) / 2, h / 2, v)
        }

        if (hlH != rowH) {
            hlH = rowH
            hl.shader = LinearGradient(0f, 0f, 0f, rowH, 0xFF6DB3F6.toInt(), 0xFF1D6BD2.toInt(), Shader.TileMode.CLAMP)
        }

        for (row in 0 until ROWS) {
            val i = scroll + row
            if (i >= items.size) break
            val it = items[i]
            val y = row * rowH
            val selected = i == sel
            if (selected) {
                c.save(); c.translate(0f, y)
                c.drawRect(0f, 0f, textW, rowH, hl)
                c.restore()
            }
            p.color = if (selected) Color.WHITE else Color.BLACK
            var right = textW - pad
            if (it.chevron) {
                val st = screen.stroke
                st.color = if (selected) Color.WHITE else 0xFF505055.toInt()
                st.strokeWidth = rowH * 0.065f
                val cw = rowH * 0.15f
                screen.path.reset()
                screen.path.moveTo(right - cw, y + rowH * 0.33f)
                screen.path.lineTo(right, y + rowH * 0.5f)
                screen.path.lineTo(right - cw, y + rowH * 0.67f)
                c.drawPath(screen.path, st)
                right -= cw + pad
            }
            val value = it.value?.invoke()
            if (value != null) {
                v.color = if (selected) Color.WHITE else 0xFF606065.toInt()
                val vw = v.measureText(value)
                c.drawText(value, right - vw, y + base, v)
                right -= vw + pad
            }
            c.drawText(screen.ellipsize(it.label, p, right - pad), pad, y + base, p)
        }

        if (needBar) {
            val f = screen.fill
            f.color = 0xFFE4E5E8.toInt()
            c.drawRect(textW, 0f, listW, h, f)
            f.color = 0xFFB0B2B7.toInt()
            c.drawRect(textW, 0f, textW + w * 0.002f + 1f, h, f)
            val thumbH = max(h * ROWS / items.size, rowH * 0.5f)
            val ty = (h - thumbH) * scroll / (items.size - ROWS)
            if (thumbKey != textW * 31 + listW) {
                thumbKey = textW * 31 + listW
                thumb.shader = LinearGradient(textW, 0f, listW, 0f, 0xFF7E8187.toInt(), 0xFF4F5257.toInt(), Shader.TileMode.CLAMP)
            }
            c.drawRect(textW + barW * 0.15f, ty + barW * 0.15f, listW - barW * 0.15f, ty + thumbH - barW * 0.15f, thumb)
        }

        if (listW < w) {
            art.set(listW, 0f, w, h)
            val bmp = artProvider?.invoke()
            if (bmp != null) screen.drawCover(c, bmp, art) else screen.drawPlaceholderArt(c, art)
            if (shadeKey != listW * 31 + w) {
                shadeKey = listW * 31 + w
                shade.shader = LinearGradient(listW, 0f, listW + w * 0.03f, 0f, 0x55000000, 0x00000000, Shader.TileMode.CLAMP)
            }
            c.drawRect(listW, 0f, listW + w * 0.03f, h, shade)
        }

        if (SystemClock.uptimeMillis() < letterUntil && items.isNotEmpty()) {
            val letter = letterAt(sel).toString()
            if (letter.isNotEmpty()) {
                val box = h * 0.34f
                val l = (listW - box) / 2
                val t = (h - box) / 2
                screen.fill.color = 0xD0303034.toInt()
                c.drawRoundRect(l, t, l + box, t + box, box * 0.15f, box * 0.15f, screen.fill)
                val b = screen.bold
                b.textSize = box * 0.62f
                b.color = Color.WHITE
                c.drawText(letter, l + (box - b.measureText(letter)) / 2, t + box * 0.72f, b)
                b.color = Color.BLACK
            }
        }
    }
}

// =====================================================================================
// Now Playing
// =====================================================================================

class NowPlayingPage(private val host: MainActivity) : Page("Now Playing") {

    private var scrubUntil = 0L
    private var volumeUntil = 0L
    private val repeatHit = RectF()
    private val shuffleHit = RectF()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var barKey = Float.NaN
    private val tmp = RectF()

    private val scrubbing get() = SystemClock.uptimeMillis() < scrubUntil
    private val showVolume get() = SystemClock.uptimeMillis() < volumeUntil
    /** True while an overlay is up (or just expired), so a paused screen still clears it. */
    val needsTick get() = SystemClock.uptimeMillis() < max(scrubUntil, volumeUntil) + 300

    override fun onWheel(d: Int): Boolean {
        val c = host.controller ?: return false
        val now = SystemClock.uptimeMillis()
        return if (scrubbing) {
            scrubUntil = now + 3500
            val dur = c.duration
            if (dur <= 0) return false
            val step = max(1000L, dur / 100)
            val from = c.currentPosition.coerceIn(0L, max(0L, dur - 500))
            val to = (from + d * step).coerceIn(0L, max(0L, dur - 500))
            if (to != from) c.seekTo(to)
            to != from
        } else {
            volumeUntil = now + 1800
            host.adjustVolume(d)
        }
    }

    /** Center button toggles the scrubber, like the iPod classic. */
    override fun onSelect() {
        if (scrubbing) scrubUntil = 0L
        else {
            scrubUntil = SystemClock.uptimeMillis() + 3500
            volumeUntil = 0L
        }
    }

    override fun onTap(x: Float, y: Float, w: Float, h: Float): Boolean {
        when {
            repeatHit.contains(x, y) -> host.cycleRepeat()
            shuffleHit.contains(x, y) -> host.toggleShuffle()
            else -> return false
        }
        return true
    }

    override fun draw(c: Canvas, w: Float, h: Float) {
        val ctl = host.controller
        val md = ctl?.mediaMetadata
        if (ctl == null || ctl.mediaItemCount == 0 || md == null) {
            val s = screen.sub
            s.textSize = h * 0.075f
            s.color = 0xFF808085.toInt()
            val t = "Nothing playing"
            c.drawText(t, (w - s.measureText(t)) / 2, h / 2, s)
            return
        }
        val pad = h * 0.055f
        val small = screen.sub
        small.textSize = h * 0.062f
        small.color = 0xFF505055.toInt()

        // "3 of 12"
        val countY = pad + small.textSize * 0.85f
        c.drawText("${ctl.currentMediaItemIndex + 1} of ${ctl.mediaItemCount}", pad * 1.4f, countY, small)

        // shuffle + repeat (tap to change)
        val iconS = h * 0.11f
        val iy = pad * 0.45f
        val rx = w - pad * 1.2f - iconS
        val sx = rx - iconS * 1.35f
        drawRepeat(c, rx, iy, iconS, ctl.repeatMode)
        drawShuffle(c, sx, iy, iconS, ctl.shuffleModeEnabled)
        val grow = iconS * 0.3f
        repeatHit.set(rx - grow * 0.5f, 0f, rx + iconS + grow, iy + iconS + grow)
        shuffleHit.set(sx - grow, 0f, rx - grow * 0.5f, iy + iconS + grow)

        // bottom bar position (computed first so the art can be centered above it).
        // Times sit well clear of the bottom edge; the bar sits just above them.
        val barH = h * 0.05f
        val timesY = h * 0.95f
        val barY = timesY - h * 0.075f - barH

        // album art: centered between the "3 of 12" text and the progress bar
        val artS = h * 0.5f
        val ax = pad * 1.4f
        val topRow = countY
        val ay = topRow + (barY - topRow - artS) / 2
        tmp.set(ax, ay, ax + artS, ay + artS)
        val bmp = host.currentSong()?.let { host.art.get(it) }
        if (bmp != null) screen.drawCover(c, bmp, tmp) else screen.drawPlaceholderArt(c, tmp)

        // text block
        val tx = ax + artS + pad * 1.3f
        val maxW = w - tx - pad
        val b = screen.bold
        b.textSize = h * 0.075f
        b.color = Color.BLACK
        val t = screen.text
        t.textSize = h * 0.066f
        t.color = 0xFF333336.toInt()
        // title / artist / album as one block, centered on the art
        val titleTop = -b.ascent()
        val blockH = titleTop + h * 0.105f + h * 0.095f + t.descent()
        var ty = ay + artS / 2 - blockH / 2 + titleTop
        c.drawText(screen.ellipsize(md.title?.toString() ?: "Unknown", b, maxW), tx, ty, b)
        ty += h * 0.105f
        c.drawText(screen.ellipsize(md.artist?.toString() ?: "", t, maxW), tx, ty, t)
        ty += h * 0.095f
        c.drawText(screen.ellipsize(md.albumTitle?.toString() ?: "", t, maxW), tx, ty, t)

        // bottom bar
        val barL = pad * 1.4f
        val barR = w - pad * 1.4f
        val dur = max(0L, ctl.duration)
        val pos = ctl.currentPosition.coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)

        if (showVolume) {
            val f = host.volumeFraction()
            val sp = barH * 0.9f
            drawSpeaker(c, barL, barY + barH / 2, sp, 0)
            drawSpeaker(c, barR - sp * 2.2f, barY + barH / 2, sp, 2)
            drawBar(c, barL + sp * 2.2f, barY, barR - sp * 2.8f, barH, f, knob = false)
        } else {
            val f = if (dur > 0) pos.toFloat() / dur else 0f
            drawBar(c, barL, barY, barR, barH, f, knob = scrubbing)
            small.textSize = h * 0.058f
            small.color = 0xFF333336.toInt()
            val ey = timesY
            c.drawText(fmt(pos), barL, ey, small)
            val rem = "-" + fmt(max(0L, dur - pos))
            c.drawText(if (dur > 0) rem else "--:--", barR - small.measureText(if (dur > 0) rem else "--:--"), ey, small)
        }
    }

    private fun drawBar(c: Canvas, l: Float, t: Float, r: Float, bh: Float, frac: Float, knob: Boolean) {
        if (barKey != t * 31 + bh) {
            barKey = t * 31 + bh
            trackPaint.shader = LinearGradient(0f, t, 0f, t + bh, 0xFFF4F4F6.toInt(), 0xFFD5D7DB.toInt(), Shader.TileMode.CLAMP)
            barPaint.shader = LinearGradient(0f, t, 0f, t + bh, 0xFF8EC4F8.toInt(), 0xFF1F6BD0.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(l, t, r, t + bh, trackPaint)
        val fx = l + (r - l) * frac.coerceIn(0f, 1f)
        c.drawRect(l, t, fx, t + bh, barPaint)
        val s = screen.stroke
        s.color = 0xFF7C7F85.toInt()
        s.strokeWidth = bh * 0.08f
        c.drawRect(l, t, r, t + bh, s)
        if (knob) {
            val k = bh * 0.95f
            val cy = t + bh / 2
            screen.path.reset()
            screen.path.moveTo(fx, cy - k); screen.path.lineTo(fx + k * 0.7f, cy)
            screen.path.lineTo(fx, cy + k); screen.path.lineTo(fx - k * 0.7f, cy); screen.path.close()
            val f = screen.fill
            f.color = 0xFF2A2C30.toInt()
            c.drawPath(screen.path, f)
        }
    }

    private fun drawSpeaker(c: Canvas, x: Float, cy: Float, s: Float, waves: Int) {
        val f = screen.fill
        f.color = 0xFF505055.toInt()
        val p = screen.path
        p.reset()
        p.moveTo(x, cy - s * 0.35f); p.lineTo(x + s * 0.4f, cy - s * 0.35f); p.lineTo(x + s, cy - s)
        p.lineTo(x + s, cy + s); p.lineTo(x + s * 0.4f, cy + s * 0.35f); p.lineTo(x, cy + s * 0.35f); p.close()
        c.drawPath(p, f)
        val st = screen.stroke
        st.color = 0xFF505055.toInt()
        st.strokeWidth = s * 0.15f
        for (i in 1..waves) {
            val r = s * (0.45f + i * 0.4f)
            tmp.set(x + s - r * 0.4f, cy - r, x + s + r, cy + r)
            c.drawArc(tmp, -45f, 90f, false, st)
        }
    }

    private fun iconColor(on: Boolean) = if (on) 0xFF1F6BD0.toInt() else 0xFFC4C5CA.toInt()

    private fun drawRepeat(c: Canvas, x: Float, y: Float, s: Float, mode: Int) {
        val on = mode != Player.REPEAT_MODE_OFF
        val st = screen.stroke
        st.color = iconColor(on)
        st.strokeWidth = s * 0.1f
        tmp.set(x + s * 0.1f, y + s * 0.26f, x + s * 0.9f, y + s * 0.74f)
        c.drawRoundRect(tmp, s * 0.16f, s * 0.16f, st)
        val f = screen.fill
        f.color = iconColor(on)
        val p = screen.path
        // gap + arrowheads
        p.reset()
        p.moveTo(x + s * 0.5f, y + s * 0.1f); p.lineTo(x + s * 0.72f, y + s * 0.26f); p.lineTo(x + s * 0.5f, y + s * 0.42f); p.close()
        p.moveTo(x + s * 0.5f, y + s * 0.58f); p.lineTo(x + s * 0.28f, y + s * 0.74f); p.lineTo(x + s * 0.5f, y + s * 0.9f); p.close()
        c.drawPath(p, f)
        if (mode == Player.REPEAT_MODE_ONE) {
            // "1" badge on the corner, clear of the arrows
            val bx = x + s * 0.9f
            val by = y + s * 0.2f
            val br = s * 0.24f
            f.color = Color.WHITE
            c.drawCircle(bx, by, br, f)
            f.color = iconColor(true)
            c.drawCircle(bx, by, br * 0.82f, f)
            val b = screen.bold
            b.textSize = s * 0.32f
            b.color = Color.WHITE
            c.drawText("1", bx - b.measureText("1") / 2, by + s * 0.115f, b)
            b.color = Color.BLACK
        }
    }

    private fun drawShuffle(c: Canvas, x: Float, y: Float, s: Float, on: Boolean) {
        val st = screen.stroke
        st.color = iconColor(on)
        st.strokeWidth = s * 0.1f
        val p = screen.path
        p.reset()
        p.moveTo(x + s * 0.05f, y + s * 0.3f); p.lineTo(x + s * 0.25f, y + s * 0.3f)
        p.cubicTo(x + s * 0.5f, y + s * 0.3f, x + s * 0.5f, y + s * 0.7f, x + s * 0.75f, y + s * 0.7f)
        p.moveTo(x + s * 0.05f, y + s * 0.7f); p.lineTo(x + s * 0.25f, y + s * 0.7f)
        p.cubicTo(x + s * 0.5f, y + s * 0.7f, x + s * 0.5f, y + s * 0.3f, x + s * 0.75f, y + s * 0.3f)
        c.drawPath(p, st)
        val f = screen.fill
        f.color = iconColor(on)
        p.reset()
        p.moveTo(x + s * 0.72f, y + s * 0.16f); p.lineTo(x + s * 0.95f, y + s * 0.3f); p.lineTo(x + s * 0.72f, y + s * 0.44f); p.close()
        p.moveTo(x + s * 0.72f, y + s * 0.56f); p.lineTo(x + s * 0.95f, y + s * 0.7f); p.lineTo(x + s * 0.72f, y + s * 0.84f); p.close()
        c.drawPath(p, f)
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}

// =====================================================================================
// Menu tree
// =====================================================================================

object Menus {
    fun root(h: MainActivity) = MenuPage(
        "GPod", split = { h.showArtPanel }, dynamic = true,
        artProvider = { h.currentSong()?.let { h.art.get(it) } },
    ) {
        buildList {
            if (!h.hasPermission()) add(MenuItem("Allow Music Access", chevron = false) { h.requestPermission() })
            add(MenuItem("Music") { h.screen.push(music(h)) })
            val shown = h.prefs.mainMenuLists
            addAll(browseLists(h).filter { it.label in shown })
            add(MenuItem("Settings") { h.screen.push(settings(h)) })
            add(MenuItem("Shuffle Songs", chevron = false) { h.shuffleAll() })
            add(MenuItem("Sleep", chevron = false) { h.sleep() })
            if (h.hasQueue()) add(MenuItem("Now Playing") { h.showNowPlaying() })
            add(MenuItem("Exit", chevron = false) { h.exitApp() })
        }
    }

    private fun empty(h: MainActivity): () -> String = {
        when {
            !h.hasPermission() -> "Music access not allowed"
            h.library == null -> "Loading…"
            h.library?.songs.isNullOrEmpty() && h.prefs.excludedFolders.isNotEmpty() -> "All music folders are off"
            else -> "No music found"
        }
    }

    private fun music(h: MainActivity) = MenuPage("Music") { browseLists(h) }

    /** The Music lists; any of them can also be shown on the main menu (Settings > Main Menu). */
    private fun browseLists(h: MainActivity) =
        listOf(
            MenuItem("Cover Flow") { h.screen.push(CoverFlowPage(h)) },
            MenuItem("Playlists") { h.screen.push(playlists(h)) },
            MenuItem("Artists") { h.screen.push(artists(h)) },
            MenuItem("Albums") { h.screen.push(albums(h, "Albums") { h.library?.albums.orEmpty() }) },
            MenuItem("Songs") { h.screen.push(songList(h, "Songs", letterJump = true) { h.library?.songsByTitle.orEmpty() }) },
            MenuItem("Genres") { h.screen.push(groups(h, "Genres") { h.library?.genres.orEmpty() }) },
            MenuItem("Folders") { h.screen.push(groups(h, "Folders") { h.library?.folders.orEmpty() }) },
        )

    private fun mainMenuSettings(h: MainActivity) = MenuPage("Main Menu") {
        browseLists(h).map { b ->
            MenuItem(b.label, value = { if (b.label in h.prefs.mainMenuLists) "On" else "Off" }) {
                val cur = h.prefs.mainMenuLists
                h.prefs.mainMenuLists = if (b.label in cur) cur - b.label else cur + b.label
            }
        }
    }

    private fun artists(h: MainActivity) = MenuPage("Artists", emptyText = empty(h), letterJump = true) {
        h.library?.artists.orEmpty().map { (name, songs) ->
            MenuItem(name) {
                h.screen.push(albums(h, name, allSongs = songs) {
                    val lib = h.library ?: return@albums emptyList()
                    val ids = songs.map { it.albumId }.toSet()
                    lib.albums.filter { it.id in ids }.map { a ->
                        Album(a.id, a.title, name, a.songs.filter { it.artist == name })
                    }
                })
            }
        }
    }

    private fun albums(h: MainActivity, title: String, allSongs: List<Song>? = null, src: () -> List<Album>) =
        MenuPage(title, emptyText = empty(h), letterJump = true) {
            val list = src().map { a -> MenuItem(a.title) { h.screen.push(songList(h, a.title) { a.songs }) } }
            if (allSongs != null) listOf(MenuItem("All Songs") { h.screen.push(songList(h, title) { allSongs }) }) + list
            else list
        }

    private fun groups(h: MainActivity, title: String, src: () -> List<Pair<String, List<Song>>>) =
        MenuPage(title, emptyText = empty(h), letterJump = true) {
            src().map { (name, songs) ->
                MenuItem(name.substringAfterLast('/').ifEmpty { name }) { h.screen.push(songList(h, name.substringAfterLast('/')) { songs }) }
            }
        }

    private fun songList(
        h: MainActivity, title: String,
        emptyText: (() -> String)? = null,
        letterJump: Boolean = false,
        songs: () -> List<Song>,
    ) = MenuPage(title, emptyText = emptyText ?: empty(h), letterJump = letterJump) {
        val list = songs()
        list.mapIndexed { i, s ->
            MenuItem(s.title, chevron = false, onHold = { h.screen.push(songOptions(h, s)) }) { h.playSongs(list, i) }
        }
    }

    private fun keys(h: MainActivity, keys: List<String>): List<Song> {
        val lib = h.library ?: return emptyList()
        return keys.mapNotNull { lib.byKey[it] }
    }

    /** Hold the center button on any song to get here. */
    private fun songOptions(
        h: MainActivity, song: Song,
        fromPlaylist: Long? = null,
    ) = MenuPage(song.title) {
        buildList {
            if (fromPlaylist != null) add(MenuItem("Remove from Playlist", chevron = false) {
                h.removeFromPlaylist(fromPlaylist, song); h.screen.pop()
            })
            h.userPlaylists.all().filter { it.id != fromPlaylist }.sortedBy { sortKey(it.name) }.forEach { pl ->
                add(MenuItem("Add to ${pl.name}", chevron = false) { h.addToPlaylist(pl.id, song); h.screen.pop() })
            }
            add(MenuItem("Add to New Playlist…", chevron = false) {
                h.promptText("New Playlist", h.nextPlaylistName()) { name ->
                    h.createPlaylist(name, listOf(song.key)); h.screen.pop()
                }
            })
        }
    }

    private fun playlists(h: MainActivity) = MenuPage("Playlists", emptyText = empty(h), letterJump = true) {
        buildList {
            val hidden = h.prefs.hiddenSmartLists
            if (SMART_ADDED !in hidden) add(MenuItem(SMART_ADDED) {
                h.screen.push(songList(h, "Recently Added") { h.library?.recentlyAdded.orEmpty() })
            })
            h.userPlaylists.all().sortedBy { sortKey(it.name) }.forEach { pl ->
                add(MenuItem(pl.name, key = "playlist:${pl.id}") { h.screen.push(userPlaylist(h, pl.id)) })
            }
            h.library?.playlists.orEmpty().forEach { (name, songs) ->
                add(MenuItem(name, key = "device:$name") { h.screen.push(devicePlaylist(h, name, songs)) })
            }
            add(MenuItem("New Playlist…", chevron = false) {
                h.promptText("New Playlist", h.nextPlaylistName()) { name -> h.createPlaylist(name, emptyList()) }
            })
        }
    }

    private fun userPlaylist(h: MainActivity, id: Long) = MenuPage(
        "Playlist", titleFn = { h.userPlaylists.get(id)?.name ?: "Playlist" },
    ) {
        val pl = h.userPlaylists.get(id) ?: return@MenuPage emptyList()
        val list = keys(h, pl.songs)
        buildList {
            list.forEachIndexed { i, s ->
                add(MenuItem(s.title, chevron = false, onHold = { h.screen.push(songOptions(h, s, fromPlaylist = id)) }) {
                    h.playSongs(list, i)
                })
            }
            if (list.isEmpty()) add(MenuItem("Hold ● on a song to add it", chevron = false) {})
            add(MenuItem("Rename Playlist…", chevron = false) {
                h.promptText("Rename Playlist", pl.name) { name -> h.renamePlaylist(id, name) }
            })
            add(MenuItem("Delete Playlist…", chevron = false) {
                h.screen.push(MenuPage("Delete Playlist?") {
                    listOf(
                        MenuItem("Cancel", chevron = false) { h.screen.pop() },
                        MenuItem("Delete “${pl.name}”", chevron = false) { h.deletePlaylist(id) },
                    )
                })
            })
        }
    }

    /** Playlists from the phone are read-only; an editable copy can be renamed and changed. */
    private fun devicePlaylist(h: MainActivity, name: String, songs: List<Song>) = MenuPage(name) {
        songs.mapIndexed { i, s ->
            MenuItem(s.title, chevron = false, onHold = { h.screen.push(songOptions(h, s)) }) { h.playSongs(songs, i) }
        } + MenuItem("Make Editable Copy…", chevron = false) {
            h.promptText("Name the Copy", name) { n -> h.createPlaylist(n, songs.map { it.key }) }
        }
    }

    private const val SMART_ADDED = "Recently Added"

    // ---------------------------------------------------------------- Settings > Music Folders

    private val stateNames = arrayOf("On", "Some", "Off")

    private fun musicFolders(h: MainActivity, path: String = ""): MenuPage = MenuPage(
        if (path.isEmpty()) "Music Folders" else path.substringAfterLast('/'),
        emptyText = empty(h),
    ) {
        if (h.allFolders.isEmpty()) return@MenuPage emptyList()
        buildList {
            add(MenuItem(if (path.isEmpty()) "All Folders" else "Entire Folder", value = { stateNames[h.folderState(path)] }) {
                h.toggleFolder(path)
            })
            // A folder can hold songs and sub-folders; let its own songs be switched separately.
            if (path in h.allFolders) {
                add(MenuItem("Songs Directly in Here", value = { stateNames[h.folderState(path, exact = true)] }) {
                    h.toggleFolder(path, exact = true)
                })
            }
            h.folderChildren(path).forEach { child ->
                val leaf = h.folderChildren(child).isEmpty()
                add(MenuItem(child.substringAfterLast('/'), chevron = !leaf, value = { stateNames[h.folderState(child)] }) {
                    if (leaf) h.toggleFolder(child) else h.screen.push(musicFolders(h, child))
                })
            }
        }
    }

    private fun settings(h: MainActivity): MenuPage {
        val p = h.prefs
        return MenuPage("Settings") {
            listOf(
                MenuItem("Shuffle", value = { if (h.shuffleOn()) "Songs" else "Off" }) { h.toggleShuffle() },
                MenuItem("Repeat", value = { repeatName(h.repeatMode()) }) { h.cycleRepeat() },
                MenuItem("Haptics", value = { arrayOf("Off", "Light", "Medium", "Strong")[p.haptics] }) {
                    p.haptics = (p.haptics + 1) % 4
                },
                MenuItem("Clicker", value = { if (p.clicker) "On" else "Off" }) { p.clicker = !p.clicker },
                MenuItem("Wheel Speed", value = { arrayOf("Slow", "Normal", "Fast")[p.wheelSpeed] }) {
                    p.wheelSpeed = (p.wheelSpeed + 1) % 3
                    h.applyPrefs()
                },
                MenuItem("Theme", value = { Skin.name(p.theme) }) {
                    p.theme = Skin.next(p.theme)
                    h.applyPrefs()
                },
                MenuItem("Screen Corners", value = { if (p.squareCorners) "Square" else "Rounded" }) {
                    p.squareCorners = !p.squareCorners
                    h.applyPrefs()
                },
                MenuItem("Navigation Bar", value = { if (p.hideNavBar) "Hidden" else "Shown" }) {
                    p.hideNavBar = !p.hideNavBar
                    h.applyPrefs()
                },
                MenuItem("Main Menu") { h.screen.push(mainMenuSettings(h)) },
                MenuItem(SMART_ADDED, value = { if (SMART_ADDED in h.prefs.hiddenSmartLists) "Off" else "On" }) {
                    val cur = h.prefs.hiddenSmartLists
                    h.prefs.hiddenSmartLists = if (SMART_ADDED in cur) cur - SMART_ADDED else cur + SMART_ADDED
                },
                MenuItem("Music Folders") { h.screen.push(musicFolders(h)) },
                MenuItem("Rescan Library", chevron = false) { h.loadLibrary(announce = true) },
                MenuItem("About", value = { h.library?.let { "${it.songs.size} songs" } ?: "" }) {},
            )
        }
    }

    fun repeatName(mode: Int) = when (mode) {
        Player.REPEAT_MODE_ONE -> "One"
        Player.REPEAT_MODE_ALL -> "All"
        else -> "Off"
    }
}
