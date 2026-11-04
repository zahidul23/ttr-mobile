package org.ttrmobile;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The app's dialogs in the launcher's style: a dark rounded card, a short message, full-width buttons. */
public final class TtrDialog {
    /** A button action; true closes the dialog. */
    public interface Action { boolean run(); }
    public static final int PRIMARY = 0, SECONDARY = 1, DANGER = 2, QUIET = 3;
    private static final int GOLD = 0xffffd65e, INK = 0xff0a2634, MUTED = 0xffb8d0dd, SOFT = 0xffdcebf1, CHIP = 0xff254e66, CARD = 0xff102b3a;
    private final Activity activity;
    private final Dialog dialog;
    private final LinearLayout card, buttons;

    public TtrDialog(Activity a, CharSequence title, CharSequence message) {
        activity = a; dialog = new Dialog(a); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        card = new LinearLayout(a); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(22), dp(22), dp(22), dp(14));
        card.setBackground(shape(CARD, 24));
        TextView t = text(title, 19, Color.WHITE); t.setTypeface(null, Typeface.BOLD); card.addView(t);
        if (message != null && message.length() > 0) { TextView m = text(message, 14, SOFT); m.setLineSpacing(0, 1.15f); add(card, m, 8); }
        buttons = new LinearLayout(a); buttons.setOrientation(LinearLayout.VERTICAL); add(card, buttons, 14);
        dialog.setContentView(card);
    }
    /** Extra content (e.g. a text field) between the message and the buttons. */
    public TtrDialog view(View v) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(14);
        card.addView(v, card.indexOfChild(buttons), p); return this;
    }
    /** A button that runs its action (if any) and closes the dialog. */
    public TtrDialog button(String label, int style, Runnable action) { return buttonIf(label, style, () -> { if (action != null) action.run(); return true; }); }
    /** A button whose action decides whether the dialog closes (e.g. after checking a field). */
    public TtrDialog buttonIf(String label, int style, Action action) {
        Button b = new Button(activity); b.setText(label); b.setAllCaps(false); b.setTextSize(16); b.setStateListAnimator(null);
        int fill = style == PRIMARY ? GOLD : style == DANGER ? 0xffd93a3a : style == QUIET ? 0x00000000 : CHIP;
        b.setTextColor(style == PRIMARY ? INK : style == QUIET ? MUTED : Color.WHITE);
        if (style == PRIMARY || style == DANGER) b.setTypeface(null, Typeface.BOLD);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(style == PRIMARY || style == DANGER ? 0x33000000 : 0x26ffffff), shape(fill, 14), shape(Color.WHITE, 14)));
        b.setOnClickListener(v -> { if (action.run()) dialog.dismiss(); });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(style == QUIET ? 46 : 52)); p.topMargin = dp(buttons.getChildCount() == 0 ? 0 : 8);
        buttons.addView(b, p); return this;
    }
    /** Runs when the dialog is dismissed with Back or a tap outside it. */
    public TtrDialog onCancel(Runnable r) { dialog.setOnCancelListener(d -> r.run()); return this; }
    public Dialog show() {
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); w.setDimAmount(0.6f);
            w.setLayout(Math.min(dp(400), activity.getResources().getDisplayMetrics().widthPixels - dp(48)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    private int dp(int v) { return Math.round(v * activity.getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private TextView text(CharSequence s, int size, int color) { TextView t = new TextView(activity); t.setText(s); t.setTextSize(size); t.setTextColor(color); return t; }
    private void add(LinearLayout parent, View v, int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); parent.addView(v, p); }
}
