package pl.mdinstal.ergokeyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var calInfo: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ergo", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }

        root.addView(label("MD Keypad", 26f))
        root.addView(label("Bez autokorekty i podmiany słów. Duże klawisze, podgląd litery nad palcem, " +
                "przesuń palec żeby poprawić klawisz przed puszczeniem. Przytrzymaj literę dla polskich znaków."))

        root.addView(Button(this).apply {
            text = "1. Włącz klawiaturę"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "2. Wybierz jako aktywną"
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        })

        addSeek(root, "Wysokość klawiszy", "height", 70, 130, 100) { "$it%" }
        addSeek(root, "Korekta dotyku (− wyżej, + niżej)", "offset", -10, 10, 0) { "$it dp" }
        addSwitch(root, "Wibracja przy dotyku", "haptic", true)
        addSwitch(root, "Wielka litera na początku zdania", "autocap", true)
        addSwitch(root, "Dopisuj polskie znaki (sie → się, wiecej → więcej)", "fixpl", true)
        root.addView(label("Tylko dodaje ogonki w znanych słowach po spacji lub interpunkcji, nic poza tym. " +
                "Backspace zaraz po podmianie przywraca to, co wpisałeś."))

        root.addView(label("Kalibracja dotyku", 20f))
        root.addView(Button(this).apply {
            text = "Test kalibracji dotyku"
            setOnClickListener { startActivity(Intent(this@SettingsActivity, CalibrationActivity::class.java)) }
        })
        addSwitch(root, "Uczenie się z poprawek (litera, backspace, sąsiedni klawisz)", "learn", true)
        calInfo = label("")
        root.addView(calInfo)
        root.addView(Button(this).apply {
            text = "Resetuj kalibrację"
            setOnClickListener {
                prefs.edit().remove("cal_lx").remove("cal_ly").remove("cal_rx").remove("cal_ry").apply()
                refreshCal()
            }
        })

        root.addView(label("Aktualizacje", 20f))
        val ver = label("Wersja ${versionName()}")
        root.addView(ver)
        val updStatus = label("")
        root.addView(Button(this).apply {
            text = "Sprawdź aktualizacje"
            setOnClickListener { checkUpdate(updStatus) }
        })
        root.addView(Button(this).apply {
            text = "Pobierz wersję debug"
            setOnClickListener { downloadDebug(updStatus) }
        })
        root.addView(updStatus)

        root.addView(label("Pole testowe"))
        root.addView(EditText(this).apply { hint = "Pisz tutaj" })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshCal()
    }

    private fun refreshCal() {
        fun f(k: String) = prefs.getFloat(k, 0f)
        calInfo.text = "Korekta dotyku (dp), lewa strona: poziomo %+.1f, pionowo %+.1f; prawa: poziomo %+.1f, pionowo %+.1f"
            .format(f("cal_lx"), f("cal_ly"), f("cal_rx"), f("cal_ry"))
    }

    private fun label(s: String, size: Float = 16f) = TextView(this).apply {
        text = s
        textSize = size
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun addSeek(
        root: LinearLayout, title: String, key: String,
        lo: Int, hi: Int, def: Int, fmt: (Int) -> String
    ) {
        val tv = label("")
        val start = prefs.getInt(key, def)
        tv.text = "$title: ${fmt(start)}"
        val sb = SeekBar(this)
        sb.max = hi - lo
        sb.progress = start - lo
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val v = p + lo
                prefs.edit().putInt(key, v).apply()
                tv.text = "$title: ${fmt(v)}"
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        root.addView(tv)
        root.addView(sb)
    }

    private fun addSwitch(root: LinearLayout, title: String, key: String, def: Boolean) {
        val sw = Switch(this)
        sw.text = title
        sw.isChecked = prefs.getBoolean(key, def)
        sw.setPadding(0, dp(12), 0, dp(12))
        sw.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(key, v).apply() }
        root.addView(sw)
    }

    // ---------- aktualizacje ----------

    private fun versionName(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

    private fun checkUpdate(status: TextView) {
        status.text = "Sprawdzam…"
        Thread {
            try {
                val info = Updater.latest()
                runOnUiThread {
                    if (info != null && Updater.isNewer(info.version, versionName())) {
                        status.text = "Dostępna wersja ${info.version}"
                        AlertDialog.Builder(this)
                            .setTitle("Dostępna wersja ${info.version}")
                            .setMessage(info.notes.take(600))
                            .setPositiveButton("Pobierz i zainstaluj") { _, _ -> downloadAndInstall(info, status) }
                            .setNegativeButton("Później", null)
                            .show()
                    } else status.text = "Masz najnowszą wersję (${versionName()})"
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Nie udało się sprawdzić (brak sieci albo brak wydań)." }
            }
        }.start()
    }

    private fun downloadDebug(status: TextView) {
        if (!Updater.canInstall(this)) {
            status.text = "Zezwól tej aplikacji na instalowanie, wróć i kliknij ponownie."
            Updater.openInstallPermission(this)
            return
        }
        status.text = "Szukam pliku debug…"
        Thread {
            try {
                val info = Updater.latestDebug()
                if (info == null) runOnUiThread { status.text = "Nie znaleziono wydania debug z archiwum ZIP ani plikiem APK." }
                else runOnUiThread { downloadAndInstall(info, status) }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Nie udało się pobrać (${e.message ?: "brak sieci"})." }
            }
        }.start()
    }

    private fun downloadAndInstall(info: UpdateInfo, status: TextView) {
        if (!Updater.canInstall(this)) {
            status.text = "Zezwól tej aplikacji na instalowanie, wróć i kliknij ponownie."
            Updater.openInstallPermission(this)
            return
        }
        status.text = "Pobieranie… 0%"
        Thread {
            try {
                var f = Updater.download(this, info) { p -> runOnUiThread { status.text = "Pobieranie wersji ${info.version}… $p%" } }
                if (f.name.endsWith(".zip", ignoreCase = true)) {
                    runOnUiThread { status.text = "Rozpakowuję…" }
                    f = Updater.extractApk(f)
                }
                runOnUiThread {
                    status.text = if (Updater.install(this, f)) "Pobrano. Potwierdź instalację w oknie Androida."
                    else "Zezwól na instalację i kliknij ponownie."
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Nie udało się pobrać (${e.message ?: "brak sieci"})." }
            }
        }.start()
    }
}
