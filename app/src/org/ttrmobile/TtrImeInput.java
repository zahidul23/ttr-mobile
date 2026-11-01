package org.ttrmobile;

import android.content.Context;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/**
 * The phone's own keyboard for the game. An invisible editor receives the keyboard's text; after every change
 * (typing, word suggestions, autocorrect, swipe typing, deleting) the difference from what the game already has
 * is sent as backspaces plus new text. Enter (or the keyboard's Send key) sends Enter and starts afresh.
 * The window is not resized for the keyboard (TtrActivity), so the game and controls stay where they are.
 */
final class TtrImeInput extends View {
    interface Output { void text(String s); void key(int code); }
    private final Output output;
    private final InputMethodManager imm;
    private Editable editor;
    /** What the game has been sent since the last Enter. */
    private String sent = "";
    private boolean open, seen; // seen: the keyboard has appeared since show()

    TtrImeInput(Context c, Output output) {
        super(c); this.output = output; imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        setFocusable(true); setFocusableInTouchMode(true);
        // The keyboard closed by itself (back gesture, its own hide key): forget the text so far.
        if (Build.VERSION.SDK_INT >= 30) setOnApplyWindowInsetsListener((v, insets) -> {
            boolean visible = insets.isVisible(WindowInsets.Type.ime());
            if (open && visible) seen = true;
            else if (open && seen) { open = false; reset(); clearFocus(); }
            return insets;
        });
    }
    boolean isOpen() { return open; }
    void show() { open = true; seen = false; reset(); requestFocus(); imm.restartInput(this); imm.showSoftInput(this, 0); }
    void hide() { if (!open) return; open = false; imm.hideSoftInputFromWindow(getWindowToken(), 0); reset(); clearFocus(); }

    private void reset() { sent = ""; if (editor != null) { editor.clear(); android.text.Selection.setSelection(editor, 0); } }
    /** Brings the game in line with the editor's text. */
    private void sync() {
        if (editor == null) return;
        String now = editor.toString(); int nl = now.indexOf('\n');
        if (nl >= 0) now = now.substring(0, nl); // some keyboards commit Enter as a newline
        int same = 0, max = Math.min(now.length(), sent.length());
        while (same < max && now.charAt(same) == sent.charAt(same)) same++;
        for (int i = sent.length(); i > same; i--) tap(KeyEvent.KEYCODE_DEL);
        if (now.length() > same) output.text(now.substring(same));
        sent = now;
        if (nl >= 0) enter();
    }
    private void enter() { tap(KeyEvent.KEYCODE_ENTER); sent = ""; if (editor != null) { editor.clear(); android.text.Selection.setSelection(editor, 0); } imm.restartInput(this); }
    private void tap(int code) { output.key(code); }

    @Override public boolean onCheckIsTextEditor() { return true; }
    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;
        // No full-screen editor in landscape; Send key; nothing learned from in-game chat.
        info.imeOptions = EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        info.initialSelStart = info.initialSelEnd = 0;
        return new BaseInputConnection(this, true) {
            @Override public Editable getEditable() { if (editor == null) { editor = Editable.Factory.getInstance().newEditable(""); android.text.Selection.setSelection(editor, 0); } return editor; }
            @Override public boolean commitText(CharSequence t, int cursor) { boolean r = super.commitText(t, cursor); sync(); return r; }
            @Override public boolean setComposingText(CharSequence t, int cursor) { boolean r = super.setComposingText(t, cursor); sync(); return r; }
            @Override public boolean finishComposingText() { boolean r = super.finishComposingText(); sync(); return r; }
            @Override public boolean deleteSurroundingText(int before, int after) { boolean r = super.deleteSurroundingText(before, after); sync(); return r; }
            @Override public boolean setComposingRegion(int start, int end) { boolean r = super.setComposingRegion(start, end); sync(); return r; }
            @Override public boolean performEditorAction(int action) { sync(); enter(); return true; }
            /** Key presses from the keyboard: Backspace on an empty editor and Enter go straight to the game. */
            @Override public boolean sendKeyEvent(KeyEvent e) {
                int code = e.getKeyCode();
                if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
                if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) { sync(); enter(); return true; }
                Editable ed = getEditable();
                if (code == KeyEvent.KEYCODE_DEL && ed.length() > 0) {
                    int end = Math.max(0, android.text.Selection.getSelectionEnd(ed)); if (end == 0) end = ed.length();
                    ed.delete(end - 1, end); sync(); return true;
                }
                int ch = e.getUnicodeChar();
                if (ch > 0 && code != KeyEvent.KEYCODE_DEL) { ed.append((char) ch); sync(); return true; }
                tap(code); return true;
            }
        };
    }
}
