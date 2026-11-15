package pl.mdinstal.ergokeyboard

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Celowo NIE używamy setComposingText ani podpowiedzi: każdy znak trafia do pola przez commitText.
 * Jedyna podmiana: dopisanie polskich znaków w znanych słowach po spacji/interpunkcji
 * (PlCorrector). Backspace zaraz po takiej podmianie przywraca to, co wpisałeś.
 */
class KeyboardService : InputMethodService(), KeyboardView.Listener {

    private lateinit var kv: KeyboardView
    private var lastShiftTap = 0L
    private var autoCap = true
    private var fixPl = true

    private class Fix(val original: String, val corrected: String, val delim: String)

    private var lastFix: Fix? = null

    override fun onCreate() {
        super.onCreate()
        PlCorrector.load(this)
    }

    override fun onCreateInputView(): View {
        kv = KeyboardView(this)
        kv.listener = this
        return kv
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!::kv.isInitialized) return
        kv.reloadPrefs()
        val prefs = getSharedPreferences("ergo", MODE_PRIVATE)
        autoCap = prefs.getBoolean("autocap", true)
        fixPl = prefs.getBoolean("fixpl", true) && fieldAllowsFix(info)
        lastFix = null
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        kv.symbols = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE
        kv.shiftState = KeyboardView.OFF
        refreshShift()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        refreshShift()
    }

    override fun onText(text: String) {
        val ic = currentInputConnection ?: return
        lastFix = null
        if (!(fixPl && text.length == 1 && text[0] in " .,!?;:)" && applyFix(ic, text))) {
            ic.commitText(text, 1)
        }
        refreshShift()
    }

    override fun onKey(code: Int) {
        when (code) {
            KeyboardView.DEL -> {
                if (!undoFix()) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                refreshShift()
            }
            KeyboardView.ENTER -> {
                if (fixPl) currentInputConnection?.let { applyFix(it, "") }
                lastFix = null
                enter()
                refreshShift()
            }
            KeyboardView.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                kv.shiftState = when {
                    kv.shiftState == KeyboardView.CAPS -> KeyboardView.OFF
                    now - lastShiftTap < 350 -> KeyboardView.CAPS
                    kv.shiftState == KeyboardView.ONCE -> KeyboardView.OFF
                    else -> KeyboardView.ONCE
                }
                lastShiftTap = now
            }
        }
    }

    override fun onCursorMove(dir: Int) {
        lastFix = null
        sendDownUpKeyEvents(if (dir < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    private fun enter() {
        val ei = currentInputEditorInfo
        val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = ei != null && (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            currentInputConnection?.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    /** Wielka litera na początku zdania (jeśli pole tego chce); Caps Lock zostaje nietknięty. */
    private fun refreshShift() {
        if (!::kv.isInitialized || kv.shiftState == KeyboardView.CAPS) return
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo ?: return
        val caps = autoCap && ic.getCursorCapsMode(ei.inputType) != 0
        kv.shiftState = if (caps) KeyboardView.ONCE else KeyboardView.OFF
    }

    private fun fieldAllowsFix(info: EditorInfo): Boolean {
        val t = info.inputType
        if (t and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        if (t and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return false
        return when (t and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> false
            else -> true
        }
    }

    /** Jeśli słowo przed kursorem to znane słowo bez polskich znaków, podmienia je i dopisuje delimiter. */
    private fun applyFix(ic: InputConnection, delim: String): Boolean {
        if (!ic.getSelectedText(0).isNullOrEmpty()) return false
        val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: return false
        var i = before.length
        while (i > 0 && before[i - 1].isLetter()) i--
        val word = before.substring(i)
        if (word.isEmpty() || (i == 0 && before.length >= 40)) return false
        if (i > 0 && before[i - 1].let { it.isDigit() || it in "@/._#" }) return false
        val fixed = PlCorrector.fix(word) ?: return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(fixed + delim, 1)
        ic.endBatchEdit()
        lastFix = Fix(word, fixed, delim)
        return true
    }

    /** Backspace zaraz po podmianie: przywraca słowo dokładnie tak, jak je wpisałeś. */
    private fun undoFix(): Boolean {
        val f = lastFix ?: return false
        lastFix = null
        val ic = currentInputConnection ?: return false
        val want = f.corrected + f.delim
        if (ic.getTextBeforeCursor(want.length, 0)?.toString() != want) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(want.length, 0)
        ic.commitText(f.original + f.delim, 1)
        ic.endBatchEdit()
        return true
    }
}
