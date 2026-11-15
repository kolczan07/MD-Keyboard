package pl.mdinstal.ergokeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Klawiatura rysowana ręcznie. Założenia ergonomiczne:
 *  - cała powierzchnia rzędu jest "trafialna" (brak martwych szczelin między klawiszami),
 *  - klawisz wybiera się po pozycji palca, można przesunąć palec i poprawić przed puszczeniem,
 *  - podgląd litery nad palcem,
 *  - pełny multi-touch (szybkie pisanie dwoma kciukami nie gubi znaków),
 *  - żadnej autokorekty: tekst idzie wprost przez commitText.
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onText(text: String)
        fun onKey(code: Int)
        fun onCursorMove(dir: Int)
    }

    companion object {
        const val SHIFT = -1
        const val DEL = -2
        const val SYM = -3
        const val ENTER = -4
        const val SPACE = -5
        const val SYM2 = -6
        const val SPACER = -9

        const val OFF = 0
        const val ONCE = 1
        const val CAPS = 2

        private const val EDGE_EXT = 0.25f
        private const val SPACE_EXT = 0.2f

        private val BG = 0xFF16171B.toInt()
        private val KEY = 0xFF2B2D34.toInt()
        private val KEY_SPECIAL = 0xFF202228.toInt()
        private val KEY_ON = 0xFF4A4E5C.toInt()
        private val ENTER_BG = 0xFF2F5FB3.toInt()
        private val ACCENT = 0xFF3D7BFF.toInt()
        private val BUBBLE = 0xFF454854.toInt()
        private val TEXT = 0xFFECEDEF.toInt()
        private val HINT = 0xFF8A8D98.toInt()
    }

    private class Key(
        val label: String,
        val code: Int = 0,
        val weight: Float = 1f,
        val alts: String = ""
    ) {
        var l = 0f
        var t = 0f
        var r = 0f
        var b = 0f
    }

    // ---------- układy ----------

    private fun chars(s: String, alts: Map<Char, String> = emptyMap()): List<Key> =
        s.map { Key(it.toString(), alts = alts[it] ?: "") }

    private val letterAlts = mapOf(
        'a' to "ą", 'c' to "ć", 'e' to "ę€", 'l' to "ł", 'n' to "ń",
        'o' to "ó", 's' to "ś", 'z' to "żź"
    )
    // rząd cyfr przy literach: przytrzymanie daje znak specjalny
    private val digitAlts = mapOf(
        '1' to "!", '2' to "@", '3' to "#", '4' to "$", '5' to "%",
        '6' to "^", '7' to "&", '8' to "*", '9' to "(", '0' to ")"
    )
    // rząd cyfr na stronie symboli: sam indeks górny
    private val supAlts = mapOf(
        '1' to "¹", '2' to "²", '3' to "³", '4' to "⁴", '5' to "⁵",
        '6' to "⁶", '7' to "⁷", '8' to "⁸", '9' to "⁹", '0' to "⁰"
    )
    private val symAlts = mapOf(
        '$' to "€£¢¥", '-' to "_–—", '+' to "±", '(' to "[{<", ')' to "]}>", '/' to "\\",
        '*' to "†‡★", '"' to "„”«»“", '\'' to "’‘‚", '!' to "¡", '?' to "¿", '&' to "§", '#' to "№"
    )

    private val letterRows: List<List<Key>> = listOf(
        chars("1234567890", digitAlts),
        chars("qwertyuiop", letterAlts),
        listOf(Key("", SPACER, 0.5f)) + chars("asdfghjkl", letterAlts) + Key("", SPACER, 0.5f),
        listOf(Key("", SHIFT, 1.5f)) + chars("zxcvbnm", letterAlts) + Key("", DEL, 1.5f),
        listOf(
            Key("?123", SYM, 1.5f),
            Key(",", alts = "';:\""),
            Key(" ", SPACE, 5f),
            Key(".", alts = "?!…:;-"),
            Key("", ENTER, 1.5f)
        )
    )

    // strona symboli 1 (jak w Gboard): cyfry (przytrzymanie = indeks górny), @#$_&-+()/, =\< * " ' : ; ! ?
    private val symbolRows: List<List<Key>> = listOf(
        chars("1234567890", supAlts),
        chars("@#\$_&-+()/", symAlts),
        listOf(Key("=\\<", SYM2, 1.5f)) + chars("*\"':;!?", symAlts) + Key("", DEL, 1.5f),
        listOf(
            Key("ABC", SYM, 1.5f),
            Key(","),
            Key(" ", SPACE, 5f),
            Key("."),
            Key("", ENTER, 1.5f)
        )
    )

    // strona symboli 2: ~ ` | • √ π ÷ × ¶ ∆ / £ ¢ € ¥ ^ ° = { } \ / % © ® ™ ✓ [ ]
    private val symbolRows2: List<List<Key>> = listOf(
        chars("~`|•√π÷×¶∆"),
        chars("£¢€¥^°={}\\"),
        listOf(Key("?123", SYM2, 1.5f)) + chars("%©®™✓[]") + Key("", DEL, 1.5f),
        listOf(
            Key("ABC", SYM, 1.5f),
            Key("<"),
            Key(" ", SPACE, 5f),
            Key(">"),
            Key("", ENTER, 1.5f)
        )
    )

    // ---------- stan i ustawienia ----------

    var listener: Listener? = null

    var shiftState = OFF
        set(value) {
            field = value
            invalidate()
        }

    private var page2 = false

    var symbols = false
        set(value) {
            field = value
            page2 = false
            layoutKeys()
            invalidate()
        }

    private val rows: List<List<Key>> get() = if (!symbols) letterRows else if (page2) symbolRows2 else symbolRows

    private var heightScale = 1f
    private var touchOffset = 0f
    private var haptic = true

    // kalibracja (dp -> px): korekta punktu dotyku osobno dla lewej i prawej strony ekranu
    private var calLx = 0f
    private var calLy = 0f
    private var calRx = 0f
    private var calRy = 0f
    private var learn = true

    // tryb testu kalibracji (używany tylko przez CalibrationActivity)
    var calibrating = false
    var calibListener: ((String, Float, Float, Float, Float) -> Unit)? = null
    private var calibKey: Key? = null

    fun setCalibTarget(label: String?) {
        calibKey = if (label == null) null else letterRows.flatten().firstOrNull { it.code == 0 && it.label == label }
        invalidate()
    }

    private var rowH = 0f
    private var baseRowH = 0f
    private val padTop: Float get() = dp(46f)
    private val altW: Float get() = dp(44f)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val rect = RectF()

    init {
        reloadPrefs()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    fun reloadPrefs() {
        val p = context.getSharedPreferences("ergo", Context.MODE_PRIVATE)
        heightScale = p.getInt("height", 100) / 100f
        touchOffset = dp(p.getInt("offset", 0).toFloat())
        haptic = p.getBoolean("haptic", true)
        learn = p.getBoolean("learn", true)
        calLx = dp(p.getFloat("cal_lx", 0f).coerceIn(-15f, 15f))
        calLy = dp(p.getFloat("cal_ly", 0f).coerceIn(-15f, 15f))
        calRx = dp(p.getFloat("cal_rx", 0f).coerceIn(-15f, 15f))
        calRy = dp(p.getFloat("cal_ry", 0f).coerceIn(-15f, 15f))
        requestLayout()
        invalidate()
    }

    // ---------- geometria ----------

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        var rh = dp(50f) * heightScale
        val maxH = resources.displayMetrics.heightPixels * 0.55f
        if (padTop + rh * 5 > maxH) rh = (maxH - padTop) / 5
        baseRowH = rh
        setMeasuredDimension(w, (padTop + rh * 5).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys()
    }

    private fun layoutKeys() {
        if (width == 0 || height == 0) return
        rowH = (height - padTop) / rows.size
        rows.forEachIndexed { ri, row ->
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            val unit = width / total
            val top = padTop + ri * rowH
            var x = 0f
            for (k in row) {
                k.l = x
                k.r = x + k.weight * unit
                k.t = top
                k.b = top + rowH
                x = k.r
            }
        }
    }

    /** Cała powierzchnia rzędu należy do jakiegoś klawisza, bez martwych stref. */
    private fun findKey(x: Float, y: Float): Key? {
        val rs = rows
        val t = (x / width).coerceIn(0f, 1f)
        val ax = x + calLx + (calRx - calLx) * t
        val ay = y + touchOffset + calLy + (calRy - calLy) * t
        val ri = ((ay - padTop) / rowH).toInt().coerceIn(0, rs.size - 1)
        val row = rs[ri]
        val cx = ax.coerceIn(0f, width - 1f)
        // Skrajne klawisze (q, a, p, l, 1, 0…) są trudniej trafić kciukiem przy krawędzi ekranu,
        // więc ich strefa dotyku wchodzi o ćwierć szerokości w sąsiada.
        val real = row.filter { it.code != SPACER }
        val first = real.first()
        val last = real.last()
        if (last.code == 0 && cx >= last.l - EDGE_EXT * (last.r - last.l)) return last
        if (first.code == 0 && cx < first.r + EDGE_EXT * (first.r - first.l)) return first
        // Spacja to najczęstszy klawisz: jej strefa wchodzi o 0,2 szerokości zwykłego klawisza w , i .
        row.firstOrNull { it.code == SPACE }?.let { sp ->
            val ext = SPACE_EXT * (sp.r - sp.l) / sp.weight
            if (cx >= sp.l - ext && cx < sp.r + ext) return sp
        }
        var best = row.firstOrNull { cx >= it.l && cx < it.r } ?: row.last()
        if (best.code == SPACER) {
            best = if (cx < width / 2f) row.first { it.code != SPACER } else row.last { it.code != SPACER }
        }
        return best
    }

    // ---------- dotyk ----------

    private inner class Ptr(val id: Int, var key: Key?, val downX: Float, val downY: Float) {
        val downKey: Key? = key
        var altIdx = -1
        var cursorMode = false
        var lastStepX = downX
        var repeating = false

        val longPress = Runnable { startAlts(this) }
        val repeat: Runnable = object : Runnable {
            override fun run() {
                repeating = true
                pendingTap = null
                listener?.onKey(DEL)
                buzz()
                postDelayed(this, 45)
            }
        }
    }

    private val ptrs = SparseArray<Ptr>()

    private fun buzz() {
        if (haptic) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                onDown(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) onMove(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                onMove(id, e.getX(i), e.getY(i))
                onUp(id)
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    private fun onDown(id: Int, x: Float, y: Float) {
        if (calibrating) {
            val t = calibKey ?: return
            buzz()
            calibListener?.invoke(t.label, x, y, (t.l + t.r) / 2f, (t.t + t.b) / 2f)
            return
        }
        val k = findKey(x, y) ?: return
        val p = Ptr(id, k, x, y)
        ptrs.put(id, p)
        buzz()
        if (k.code == DEL) {
            val pt = prevTap
            val now = SystemClock.uptimeMillis()
            if (pt != null && now - pt.time < 1500 && pendingTap == null) {
                pendingTap = pt
                pendingTime = now
            } else pendingTap = null
            prevTap = null
            listener?.onKey(DEL)
            postDelayed(p.repeat, 400)
        } else if (k.alts.isNotEmpty()) {
            postDelayed(p.longPress, 350)
        }
        invalidate()
    }

    private fun onMove(id: Int, x: Float, y: Float) {
        val p = ptrs.get(id) ?: return
        val k = p.key ?: return
        when {
            k.code == DEL || p.repeating -> Unit

            p.altIdx >= 0 -> {
                val n = k.alts.length
                val slot = ((x - popupLeft(k, n)) / altW).toInt().coerceIn(0, n - 1)
                val idx = if (reversed(k, n)) n - 1 - slot else slot
                if (idx != p.altIdx) {
                    p.altIdx = idx
                    buzz()
                    invalidate()
                }
            }

            k.code == SPACE || p.cursorMode -> {
                if (!p.cursorMode && abs(x - p.downX) > dp(24f)) {
                    p.cursorMode = true
                    p.lastStepX = x
                }
                if (p.cursorMode) {
                    val step = dp(14f)
                    while (x - p.lastStepX >= step) {
                        listener?.onCursorMove(1)
                        p.lastStepX += step
                    }
                    while (p.lastStepX - x >= step) {
                        listener?.onCursorMove(-1)
                        p.lastStepX -= step
                    }
                }
            }

            else -> {
                // drobne drżenie palca ignorujemy; świadome przesunięcie zmienia klawisz
                val moved = hypot(x - p.downX, y - p.downY) > dp(12f)
                val nk = if (moved) findKey(x, y) else p.downKey
                if (nk != null && nk !== k && nk.code != SPACER) {
                    removeCallbacks(p.longPress)
                    p.key = nk
                    if (nk.alts.isNotEmpty()) postDelayed(p.longPress, 350)
                    invalidate()
                }
            }
        }
    }

    private fun onUp(id: Int) {
        val p = ptrs.get(id) ?: return
        ptrs.remove(id)
        removeCallbacks(p.longPress)
        removeCallbacks(p.repeat)
        val k = p.key
        if (k != null && !p.repeating && !p.cursorMode && k.code != DEL) {
            if (p.altIdx >= 0) {
                listener?.onText(shifted(k.alts[p.altIdx].toString()))
                clearLearn()
            } else {
                when (k.code) {
                    0 -> {
                        listener?.onText(shifted(k.label))
                        noteCharTap(k, p)
                    }
                    SPACE -> {
                        listener?.onText(" ")
                        clearLearn()
                    }
                    SYM -> {
                        symbols = !symbols
                        clearLearn()
                    }
                    SYM2 -> {
                        page2 = !page2
                        layoutKeys()
                        clearLearn()
                    }
                    SHIFT, ENTER -> {
                        listener?.onKey(k.code)
                        if (k.code == ENTER) clearLearn()
                    }
                }
            }
        }
        invalidate()
    }

    private fun cancelAll() {
        for (i in 0 until ptrs.size()) {
            val p = ptrs.valueAt(i)
            removeCallbacks(p.longPress)
            removeCallbacks(p.repeat)
        }
        ptrs.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelAll()
        super.onDetachedFromWindow()
    }

    private fun startAlts(p: Ptr) {
        val k = p.key ?: return
        if (k.alts.isEmpty()) return
        p.altIdx = 0
        if (haptic) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    // ---------- uczenie się z poprawek ----------
    // Sygnał: litera A, od razu backspace, potem sąsiedni klawisz B => dotyk w A miał trafić w B.
    // Co poprawkę przesuwamy korektę o małą stałą w stronę B (znak kierunku), więc losowe pomyłki
    // w różne strony się znoszą, a systematyczne przesunięcie narasta aż do zrównoważenia.

    private class Tap(val key: Key, val rx: Float, val ry: Float, val time: Long)

    private var prevTap: Tap? = null
    private var pendingTap: Tap? = null
    private var pendingTime = 0L

    private fun clearLearn() {
        prevTap = null
        pendingTap = null
    }

    private fun noteCharTap(k: Key, p: Ptr) {
        val now = SystemClock.uptimeMillis()
        val pt = pendingTap
        val direct = p.key === p.downKey
        if (learn && direct && pt != null && now - pendingTime < 2500) learnFrom(pt, k)
        pendingTap = null
        prevTap = if (direct) Tap(k, p.downX, p.downY, now) else null
    }

    private fun learnFrom(tap: Tap, b: Key) {
        val a = tap.key
        if (a === b || a.code != 0 || b.code != 0) return
        val acx = (a.l + a.r) / 2f
        val acy = (a.t + a.b) / 2f
        val bcx = (b.l + b.r) / 2f
        val bcy = (b.t + b.b) / 2f
        val unit = width / 10f
        if (abs(acx - bcx) > 1.6f * unit || abs(acy - bcy) > 1.3f * rowH) return
        val sx = if (abs(bcx - acx) < 2f) 0f else sign(bcx - acx)
        val sy = if (abs(bcy - acy) < 2f) 0f else sign(bcy - acy)
        val step = dp(0.3f)
        val lim = dp(12f)
        if (tap.rx < width / 2f) {
            calLx = (calLx + sx * step).coerceIn(-lim, lim)
            calLy = (calLy + sy * step).coerceIn(-lim, lim)
        } else {
            calRx = (calRx + sx * step).coerceIn(-lim, lim)
            calRy = (calRy + sy * step).coerceIn(-lim, lim)
        }
        val d = resources.displayMetrics.density
        context.getSharedPreferences("ergo", Context.MODE_PRIVATE).edit()
            .putFloat("cal_lx", calLx / d).putFloat("cal_ly", calLy / d)
            .putFloat("cal_rx", calRx / d).putFloat("cal_ry", calRy / d)
            .apply()
    }

    private fun shifted(s: String) = if (shiftState != OFF) s.uppercase() else s

    // popup z alternatywami: pierwszy znak zawsze pod palcem, reszta w stronę wolnego miejsca
    private fun reversed(k: Key, n: Int): Boolean =
        (k.l + k.r) / 2f - altW / 2f + n * altW > width

    private fun popupLeft(k: Key, n: Int): Float {
        val cx = (k.l + k.r) / 2f
        return if (reversed(k, n)) min(width.toFloat(), cx + altW / 2f) - n * altW
        else max(0f, cx - altW / 2f)
    }

    // ---------- rysowanie ----------

    override fun onDraw(c: Canvas) {
        c.drawColor(BG)
        val gx = dp(2.5f)
        val gy = dp(3.5f)
        val down = HashSet<Key>()
        for (i in 0 until ptrs.size()) ptrs.valueAt(i).key?.let { down.add(it) }

        for (row in rows) for (k in row) {
            if (k.code == SPACER) continue
            rect.set(k.l + gx, k.t + gy, k.r - gx, k.b - gy)
            paint.style = Paint.Style.FILL
            paint.color = when {
                k in down || k === calibKey -> ACCENT
                k.code == ENTER -> ENTER_BG
                k.code == SHIFT && shiftState != OFF -> KEY_ON
                k.code < 0 && k.code != SPACE -> KEY_SPECIAL
                else -> KEY
            }
            c.drawRoundRect(rect, dp(7f), dp(7f), paint)
            val cx = rect.centerX()
            val cy = rect.centerY()
            val s = rowH * 0.2f
            when (k.code) {
                0 -> {
                    drawCentered(c, shifted(k.label), cx, cy, rowH * 0.46f, TEXT)
                    if (k.alts.isNotEmpty()) {
                        drawCentered(
                            c, shifted(k.alts.substring(0, 1)),
                            rect.right - dp(8f), rect.top + dp(11f), rowH * 0.24f, HINT
                        )
                    }
                }
                SYM, SYM2 -> drawCentered(c, k.label, cx, cy, rowH * 0.3f, TEXT)
                SHIFT -> iconShift(c, cx, cy, s)
                DEL -> iconDel(c, cx, cy, s)
                ENTER -> iconEnter(c, cx, cy, s)
            }
        }

        for (i in 0 until ptrs.size()) {
            val p = ptrs.valueAt(i)
            val k = p.key ?: continue
            if (p.altIdx >= 0) drawAlts(c, p, k)
            else if (k.code == 0 && !p.cursorMode) drawBubble(c, k)
        }
    }

    private fun drawCentered(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.textSize = size
        paint.color = color
        val fm = paint.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, paint)
    }

    private fun drawBubble(c: Canvas, k: Key) {
        val bw = (k.r - k.l) * 1.35f
        val bh = dp(44f)
        val cx = ((k.l + k.r) / 2f).coerceIn(bw / 2f, width - bw / 2f)
        val top = max(0f, k.t - bh - dp(2f))
        rect.set(cx - bw / 2f, top, cx + bw / 2f, top + bh)
        paint.style = Paint.Style.FILL
        paint.color = BUBBLE
        c.drawRoundRect(rect, dp(10f), dp(10f), paint)
        drawCentered(c, shifted(k.label), rect.centerX(), rect.centerY(), bh * 0.6f, TEXT)
    }

    private fun drawAlts(c: Canvas, p: Ptr, k: Key) {
        val n = k.alts.length
        val rev = reversed(k, n)
        val left = popupLeft(k, n)
        val bh = dp(48f)
        val top = max(0f, k.t - bh - dp(2f))
        rect.set(left, top, left + n * altW, top + bh)
        paint.style = Paint.Style.FILL
        paint.color = BUBBLE
        c.drawRoundRect(rect, dp(10f), dp(10f), paint)
        for (slot in 0 until n) {
            val idx = if (rev) n - 1 - slot else slot
            val sl = left + slot * altW
            if (idx == p.altIdx) {
                rect.set(sl + dp(3f), top + dp(3f), sl + altW - dp(3f), top + bh - dp(3f))
                paint.style = Paint.Style.FILL
                paint.color = ACCENT
                c.drawRoundRect(rect, dp(8f), dp(8f), paint)
            }
            drawCentered(c, shifted(k.alts[idx].toString()), sl + altW / 2f, top + bh / 2f, bh * 0.5f, TEXT)
        }
    }

    private fun stroke() {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = TEXT
    }

    private fun iconShift(c: Canvas, cx: Float, cy: Float, s: Float) {
        stroke()
        if (shiftState != OFF) paint.style = Paint.Style.FILL_AND_STROKE
        path.reset()
        path.moveTo(cx, cy - s)
        path.lineTo(cx + s, cy)
        path.lineTo(cx + s * 0.4f, cy)
        path.lineTo(cx + s * 0.4f, cy + s)
        path.lineTo(cx - s * 0.4f, cy + s)
        path.lineTo(cx - s * 0.4f, cy)
        path.lineTo(cx - s, cy)
        path.close()
        c.drawPath(path, paint)
        if (shiftState == CAPS) {
            paint.style = Paint.Style.STROKE
            c.drawLine(cx - s * 0.6f, cy + s * 1.5f, cx + s * 0.6f, cy + s * 1.5f, paint)
        }
    }

    private fun iconDel(c: Canvas, cx: Float, cy: Float, s: Float) {
        stroke()
        path.reset()
        path.moveTo(cx - s, cy)
        path.lineTo(cx - s * 0.35f, cy - s * 0.75f)
        path.lineTo(cx + s, cy - s * 0.75f)
        path.lineTo(cx + s, cy + s * 0.75f)
        path.lineTo(cx - s * 0.35f, cy + s * 0.75f)
        path.close()
        c.drawPath(path, paint)
        c.drawLine(cx - s * 0.1f, cy - s * 0.35f, cx + s * 0.55f, cy + s * 0.35f, paint)
        c.drawLine(cx - s * 0.1f, cy + s * 0.35f, cx + s * 0.55f, cy - s * 0.35f, paint)
    }

    private fun iconEnter(c: Canvas, cx: Float, cy: Float, s: Float) {
        stroke()
        path.reset()
        path.moveTo(cx + s, cy - s * 0.8f)
        path.lineTo(cx + s, cy + s * 0.3f)
        path.lineTo(cx - s, cy + s * 0.3f)
        path.moveTo(cx - s * 0.2f, cy - s * 0.4f)
        path.lineTo(cx - s, cy + s * 0.3f)
        path.lineTo(cx - s * 0.2f, cy + s)
        c.drawPath(path, paint)
    }
}
