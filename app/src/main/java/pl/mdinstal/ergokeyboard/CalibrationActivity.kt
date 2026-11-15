package pl.mdinstal.ergokeyboard

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Test kalibracji: stukasz podświetlone klawisze (dwa przebiegi przez wszystkie litery),
 * a apka mierzy, gdzie faktycznie ląduje palec względem środka klawisza.
 * Wynik (mediana, osobno lewa i prawa strona) zapisuje jako korektę dotyku.
 */
class CalibrationActivity : Activity() {

    private class Sample(val label: String, val rx: Float, val ry: Float, val cx: Float, val cy: Float)

    private lateinit var kv: KeyboardView
    private lateinit var progress: TextView
    private lateinit var result: TextView
    private lateinit var buttons: LinearLayout
    private val targets = ArrayList<String>()
    private val samples = ArrayList<Sample>()
    private var left = FloatArray(4)
    private var right = FloatArray(4)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(s: String, size: Float = 16f) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(0xFFECEDEF.toInt())
        setPadding(0, dp(8), 0, dp(4))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF16171B.toInt())
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(8))
        }
        top.addView(tv("Kalibracja dotyku", 24f))
        top.addView(tv("Trzymaj telefon tak jak zwykle przy pisaniu (jedną ręką) i stukaj podświetlony klawisz. " +
                "Nie przejmuj się dokładnością, o to chodzi. Potrzeba 52 stuknięć."))
        progress = tv("")
        result = tv("")
        top.addView(progress)
        top.addView(result)
        buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        buttons.addView(Button(this).apply {
            text = "Zapisz"
            setOnClickListener { save() }
        })
        buttons.addView(Button(this).apply {
            text = "Powtórz"
            setOnClickListener { start() }
        })
        top.addView(buttons)
        val scroll = ScrollView(this)
        scroll.addView(top)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        kv = KeyboardView(this)
        root.addView(
            kv,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        setContentView(root)

        kv.calibrating = true
        kv.calibListener = { label, rx, ry, cx, cy -> onSample(label, rx, ry, cx, cy) }
        start()
    }

    private fun start() {
        samples.clear()
        targets.clear()
        val letters = "qwertyuiopasdfghjklzxcvbnm".map { it.toString() }
        targets.addAll(letters.shuffled())
        targets.addAll(letters.shuffled())
        buttons.visibility = View.GONE
        result.text = ""
        kv.symbols = false
        kv.setCalibTarget(targets[0])
        progress.text = "Stuknięcie 1 / ${targets.size}"
    }

    private fun onSample(label: String, rx: Float, ry: Float, cx: Float, cy: Float) {
        if (samples.size >= targets.size) return
        samples.add(Sample(label, rx, ry, cx, cy))
        val n = samples.size
        if (n < targets.size) {
            kv.setCalibTarget(targets[n])
            progress.text = "Stuknięcie ${n + 1} / ${targets.size}"
        } else {
            finishTest()
        }
    }

    private fun median(v: List<Float>): Float {
        if (v.isEmpty()) return 0f
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
    }

    /** [dx, dy, rozrzut x, rozrzut y] w dp; dx/dy = o ile trzeba przesunąć dotyk, żeby trafiał w środek. */
    private fun side(list: List<Sample>): FloatArray {
        if (list.isEmpty()) return FloatArray(4)
        val d = resources.displayMetrics.density
        val dx = median(list.map { it.cx - it.rx })
        val dy = median(list.map { it.cy - it.ry })
        val sx = sqrt(list.map { val e = it.cx - it.rx - dx; (e * e).toDouble() }.average()).toFloat()
        val sy = sqrt(list.map { val e = it.cy - it.ry - dy; (e * e).toDouble() }.average()).toFloat()
        return floatArrayOf(dx / d, dy / d, sx / d, sy / d)
    }

    private fun describe(name: String, a: FloatArray): String {
        val h = if (a[0] > 0) "na lewo" else "na prawo"
        val v = if (a[1] > 0) "wyżej" else "niżej"
        return "$name: ląduję średnio %.1f dp $h i %.1f dp $v od środka klawisza (rozrzut ±%.1f dp poziomo, ±%.1f dp pionowo)."
            .format(abs(a[0]), abs(a[1]), a[2], a[3])
    }

    private fun finishTest() {
        kv.setCalibTarget(null)
        val half = kv.width / 2f
        left = side(samples.filter { it.cx < half })
        right = side(samples.filter { it.cx >= half })
        progress.text = "Gotowe"
        result.text = describe("Lewa strona", left) + "\n\n" + describe("Prawa strona", right) +
                breakdown() +
                "\n\nZapisz, żeby klawiatura od razu używała tej korekty. Dalej będzie ją sama dostrajać z Twoich poprawek."
        buttons.visibility = View.VISIBLE
    }

    /** Rozkład trafień: czy przesunięcie jest równe na całej klawiaturze, czy tylko w jednym rejonie. */
    private fun breakdown(): String {
        val d = resources.displayMetrics.density
        val unit = kv.width / 10f
        val sb = StringBuilder()
        sb.append("\n\nGdzie ląduje palec względem środka klawisza (dp; poziomo + = w prawo, pionowo + = niżej).\n")
        sb.append("Kolumny od lewej:\n")
        for (c in 0 until 10) {
            val l = samples.filter { (it.cx / unit + 0.01f).toInt() == c }
            if (l.isEmpty()) continue
            val h = median(l.map { it.rx - it.cx }) / d
            val v = median(l.map { it.ry - it.cy }) / d
            sb.append("${c + 1}: poziomo %+.1f, pionowo %+.1f (n=${l.size})\n".format(h, v))
        }
        sb.append("Rzędy:\n")
        val rowDefs = listOf("qwertyuiop" to "górny", "asdfghjkl" to "środkowy", "zxcvbnm" to "dolny")
        for ((letters, name) in rowDefs) {
            val l = samples.filter { letters.contains(it.label) }
            if (l.isEmpty()) continue
            val h = median(l.map { it.rx - it.cx }) / d
            val v = median(l.map { it.ry - it.cy }) / d
            sb.append("$name: poziomo %+.1f, pionowo %+.1f (n=${l.size})\n".format(h, v))
        }
        for (ch in listOf("k", "l")) {
            val l = samples.filter { it.label == ch }
            if (l.isEmpty()) continue
            val h = median(l.map { it.rx - it.cx }) / d
            val v = median(l.map { it.ry - it.cy }) / d
            sb.append("Litera $ch: poziomo %+.1f, pionowo %+.1f (n=${l.size})\n".format(h, v))
        }
        return sb.toString()
    }

    private fun save() {
        val prefs = getSharedPreferences("ergo", MODE_PRIVATE)
        // ręczny suwak "Korekta dotyku" zostaje, kalibracja dokłada tylko resztę
        val manual = prefs.getInt("offset", 0).toFloat()
        prefs.edit()
            .putFloat("cal_lx", left[0])
            .putFloat("cal_ly", left[1] - manual)
            .putFloat("cal_rx", right[0])
            .putFloat("cal_ry", right[1] - manual)
            .apply()
        Toast.makeText(this, "Zapisano", Toast.LENGTH_SHORT).show()
        finish()
    }
}
