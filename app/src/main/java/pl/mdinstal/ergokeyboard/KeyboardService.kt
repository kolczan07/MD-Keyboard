package pl.mdinstal.ergokeyboard

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo

/**
 * Celowo NIE używamy setComposingText ani żadnych podpowiedzi:
 * każdy znak trafia do pola przez commitText, więc nic nie może być
 * "poprawione" ani podmienione bez Twojej wiedzy.
 */
class KeyboardService : InputMethodService(), KeyboardView.Listener {

    private lateinit var kv: KeyboardView
    private var lastShiftTap = 0L
    private var autoCap = true

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
        autoCap = getSharedPreferences("ergo", MODE_PRIVATE).getBoolean("autocap", true)
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
        currentInputConnection?.commitText(text, 1)
        refreshShift()
    }

    override fun onKey(code: Int) {
        when (code) {
            KeyboardView.DEL -> {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                refreshShift()
            }
            KeyboardView.ENTER -> {
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
}
