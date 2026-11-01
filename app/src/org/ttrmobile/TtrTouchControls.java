package org.ttrmobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Independent touch targets allow movement, jump and throw at the same time. */
public final class TtrTouchControls extends FrameLayout {
    public interface Output {
        void bind(String binding, boolean down); void keyboard();
        /** A one-finger touch for the game, in these controls' coordinates; false when it is not over the game. */
        default boolean game(MotionEvent e) { return false; }
    }
    private final Output output;
    private ModeSwitch pointerToggle;
    private boolean cursorMode;
    private int gamePointer = -1;
    private Joystick joystick;
    /** Landscape: the small button right of Keyboard that tucks these controls away and brings them back. */
    private final Tuck tuck;
    /** Controls that tuck away; Keyboard, the Tap/Cursor switch and Groups always stay. */
    private static final Set<String> TUCKED = new HashSet<>(Arrays.asList("Move", "Jump", "Throw", "Map", "Gags", "Tasks", "Camera"));
    private boolean tucked, controller;
    /** Button backgrounds are see-through so the game shows behind them; labels stay opaque. */
    private static final int FILL_ALPHA = 0x70;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public TtrTouchControls(Context c, Output output) {
        super(c); this.output = output; setWillNotDraw(false); setMotionEventSplittingEnabled(true);
        joystick = new Joystick(); addView(joystick, new LayoutParams(1, 1));
        button("Jump", "KEY_CTRL_L", 0xa3b3a524); button("Throw", "KEY_DEL", 0xb5bb7136);
        button("Gags", "KEY_HOME", 0xb52b837b); button("Tasks", "KEY_END", 0xb53d7794); button("Map", "KEY_ALT_L", 0xb5705ca0);
        View keyboard = new KeyboardIcon(); keyboard.setTag("Keyboard"); keyboard.setContentDescription("Keyboard");
        keyboard.setOnClickListener(v -> { tick(v); output.keyboard(); }); addView(keyboard, new LayoutParams(1, 1));
        button("Camera", "KEY_TAB", 0xb5476a8c); // Tab cycles TTR's camera angles
        tuck = new Tuck(); tuck.setOnClickListener(v -> { tick(v); tucked = !tucked; applyTuck(true); }); addView(tuck, new LayoutParams(1, 1));
    }
    /** A connected gamepad (or a handheld's built-in controls) tucks the landscape controls away; removing it brings them back. */
    static boolean controllerConnected() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true;
        }
        return false;
    }
    private final InputManager.InputDeviceListener devices = new InputManager.InputDeviceListener() {
        public void onInputDeviceAdded(int id) { controllersChanged(); }
        public void onInputDeviceRemoved(int id) { controllersChanged(); }
        public void onInputDeviceChanged(int id) { controllersChanged(); }
    };
    private void controllersChanged() {
        boolean now = controllerConnected(); if (now == controller) return;
        controller = tucked = now; applyTuck(isShown());
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        InputManager im = (InputManager) getContext().getSystemService(Context.INPUT_SERVICE);
        if (im != null) im.registerInputDeviceListener(devices, getHandler());
        controller = tucked = controllerConnected(); post(() -> applyTuck(false));
    }
    @Override protected void onDetachedFromWindow() {
        InputManager im = (InputManager) getContext().getSystemService(Context.INPUT_SERVICE);
        if (im != null) im.unregisterInputDeviceListener(devices);
        super.onDetachedFromWindow();
    }
    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) { super.onSizeChanged(w, h, oldW, oldH); post(() -> applyTuck(false)); }
    /**
     * Shows or tucks away the game-play controls (landscape only; portrait always shows them). Animated, they shrink
     * and fly into the Controls button one after another, nearest first, and spring back out to their places.
     */
    private void applyTuck(boolean animate) {
        int w = getWidth(), h = getHeight(); if (w < 1 || h < 1) return;
        boolean landscape = w > h, hide = tucked && landscape;
        tuck.setVisibility(landscape ? VISIBLE : GONE); tuck.tucked = hide; tuck.invalidate();
        tuck.setContentDescription(hide ? "Show touch controls" : "Hide touch controls");
        float tx = tuck.getLeft() + tuck.getWidth() / 2f, ty = tuck.getTop() + tuck.getHeight() / 2f;
        float density = getResources().getDisplayMetrics().density;
        List<View> views = new ArrayList<>(); List<float[]> centres = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i); if (!TUCKED.contains(String.valueOf(v.getTag()))) continue;
            float[] c;
            if (v == joystick) { int[] j = TtrControlLayout.joystick(w, h, density); c = new float[]{j[0], j[1]}; }
            else c = new float[]{v.getLeft() + v.getWidth() / 2f, v.getTop() + v.getHeight() / 2f};
            views.add(v); centres.add(c);
        }
        Integer[] order = new Integer[views.size()]; for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(distance(centres.get(a), tx, ty), distance(centres.get(b), tx, ty)));
        long now = android.os.SystemClock.uptimeMillis();
        for (int rank = 0; rank < order.length; rank++) {
            View v = views.get(order[rank]); float[] c = centres.get(order[rank]);
            float dx = tx - c[0], dy = ty - c[1];
            v.animate().cancel(); v.setPivotX(c[0] - v.getLeft()); v.setPivotY(c[1] - v.getTop());
            if (hide) {
                // Let go of anything held there (a finger on Jump, the joystick mid-move).
                MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0); v.dispatchTouchEvent(cancel); cancel.recycle();
                if (!animate || v.getVisibility() != VISIBLE) { v.setVisibility(INVISIBLE); settle(v); continue; }
                boolean last = rank == order.length - 1;
                v.animate().translationX(dx).translationY(dy).scaleX(.06f).scaleY(.06f).alpha(0).setStartDelay(rank * 28L).setDuration(230)
                    .setInterpolator(new AccelerateInterpolator(1.3f)).withEndAction(() -> { v.setVisibility(INVISIBLE); settle(v); if (last) pop(); });
            } else {
                if (!animate) { v.setVisibility(VISIBLE); settle(v); continue; }
                if (v.getVisibility() != VISIBLE) { v.setTranslationX(dx); v.setTranslationY(dy); v.setScaleX(.06f); v.setScaleY(.06f); v.setAlpha(0); v.setVisibility(VISIBLE); }
                v.animate().translationX(0).translationY(0).scaleX(1).scaleY(1).alpha(1).setStartDelay(rank * 32L).setDuration(340)
                    .setInterpolator(new OvershootInterpolator(1.1f)).withEndAction(null);
            }
        }
        if (animate && !hide) pop();
    }
    private static float distance(float[] c, float x, float y) { return (float) Math.hypot(c[0] - x, c[1] - y); }
    private static void settle(View v) { v.setTranslationX(0); v.setTranslationY(0); v.setScaleX(1); v.setScaleY(1); v.setAlpha(1); }
    /** A small bounce of the Controls button as the controls land in it or leave it. */
    private void pop() {
        tuck.animate().cancel();
        tuck.animate().scaleX(1.18f).scaleY(1.18f).setStartDelay(0).setDuration(90).setInterpolator(null)
            .withEndAction(() -> tuck.animate().scaleX(1).scaleY(1).setDuration(160).setInterpolator(new OvershootInterpolator(2f)).withEndAction(null));
    }
    /** Windows input mode is provided by the runtime; Linux keeps its own input. */
    public void addPointerToggle(Runnable toggle,boolean cursorMode){
        pointerToggle=new ModeSwitch();pointerToggle.setOnClickListener(v->{tick(v);toggle.run();});setCursorMode(cursorMode);
        addView(pointerToggle,new LayoutParams(1,1));
    }
    /** Windows: a Groups button that opens ToonHQ's group finder. */
    public void addGroupsButton(Runnable open){
        TextView b=new TextView(getContext());b.setTag("Groups");b.setText("Groups");b.setContentDescription("ToonHQ groups");
        b.setTextColor(Color.WHITE);b.setTextSize(13);b.setTypeface(null,Typeface.BOLD);b.setGravity(Gravity.CENTER);b.setSingleLine(true);
        b.setBackground(pill(0xb5705ca0));b.setClickable(true);b.setOnClickListener(v->{tick(v);open.run();});
        addView(b,new LayoutParams(1,1));
    }
    public void setCursorMode(boolean cursorMode){
        this.cursorMode=cursorMode;
        if(pointerToggle==null)return;
        pointerToggle.cursor=cursorMode;pointerToggle.invalidate();
        pointerToggle.setContentDescription((cursorMode?"Cursor":"Tap")+" mode. Switch to "+(cursorMode?"tap":"cursor")+" mode");
    }
    private GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(color & 0xffffff | FILL_ALPHA << 24);
        d.setStroke(Math.max(1, dp(1)), 0x99e8f5ef); return d;
    }
    /** Vibrate on button press: off unless turned on in Settings. */
    static boolean haptics(Context c) { return c.getSharedPreferences("ttr-input", 0).getBoolean("haptics", false); }
    static void setHaptics(Context c, boolean on) { c.getSharedPreferences("ttr-input", 0).edit().putBoolean("haptics", on).apply(); }
    /** A short tick when a control is pressed (when enabled; portrait only, landscape play stays quiet). */
    static void tick(View v) {
        if (haptics(v.getContext()) && v.getResources().getConfiguration().orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void button(String label, String key, int color) {
        TextView b = new TextView(getContext()); b.setTag(label); b.setText(label); b.setContentDescription(label);
        b.setTextColor(Color.WHITE); b.setTextSize(label.equals("Jump") ? 20 : 12); b.setTypeface(null, Typeface.BOLD);
        b.setGravity(Gravity.CENTER); b.setBackground(circle(color)); b.setClickable(true);
        b.setOnTouchListener(new OnTouchListener() {
            boolean held;
            public boolean onTouch(View view, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN && !held) { held = true; output.bind(key, true); view.setAlpha(.6f); tick(view); }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (held) output.bind(key, false); held = false; view.setAlpha(1); if (action == MotionEvent.ACTION_UP) view.performClick();
                }
                return true;
            }
        }); addView(b, new LayoutParams(1, 1));
    }
    /**
     * A finger that lands on the game (not on a button) while another finger is moving or holding a button goes to
     * the game. Its own down and up are kept from the buttons, so they never mistake it for theirs.
     */
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        int action = e.getActionMasked(), index = e.getActionIndex();
        if (action == MotionEvent.ACTION_POINTER_DOWN && gamePointer < 0 && !overControl(e.getX(index), e.getY(index))) {
            if (sendGame(e, index, MotionEvent.ACTION_DOWN)) { gamePointer = e.getPointerId(index); return true; }
        } else if (gamePointer >= 0) {
            int at = e.findPointerIndex(gamePointer);
            if (action == MotionEvent.ACTION_CANCEL || at < 0) { if (at >= 0) sendGame(e, at, MotionEvent.ACTION_CANCEL); gamePointer = -1; }
            else if ((action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP) && index == at) {
                sendGame(e, at, MotionEvent.ACTION_UP); gamePointer = -1; return true;
            } else if (action == MotionEvent.ACTION_MOVE) sendGame(e, at, MotionEvent.ACTION_MOVE);
        }
        return super.dispatchTouchEvent(e);
    }
    /** Over a button or switch, or over the movement area while the joystick is free. */
    private boolean overControl(float x, float y) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() != VISIBLE || x < v.getLeft() || x >= v.getRight() || y < v.getTop() || y >= v.getBottom()) continue;
            if (v != joystick || !joystick.busy()) return true;
        }
        return false;
    }
    /** One pointer of e (x, y in these controls' coordinates) as a single-finger event for the game. */
    private boolean sendGame(MotionEvent e, int index, int action) { return sendGame(e.getDownTime(), e.getEventTime(), action, e.getX(index), e.getY(index)); }
    private boolean sendGame(long down, long time, int action, float x, float y) {
        MotionEvent one = MotionEvent.obtain(down, time, action, x, y, 0);
        try { return output.game(one); } finally { one.recycle(); }
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w=MeasureSpec.getSize(widthSpec), h=MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w,h);
        for(int i=0;i<getChildCount();i++) {
            View child=getChildAt(i); int[] b=TtrControlLayout.bounds(String.valueOf(child.getTag()),w,h,getResources().getDisplayMetrics().density);
            child.measure(MeasureSpec.makeMeasureSpec(b[2],MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(b[3],MeasureSpec.EXACTLY));
        }
    }
    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom) {
        for(int i=0;i<getChildCount();i++) {
            View child=getChildAt(i); int[] b=TtrControlLayout.bounds(String.valueOf(child.getTag()),right-left,bottom-top,getResources().getDisplayMetrics().density);
            child.layout(b[0],b[1],b[0]+b[2],b[1]+b[3]);
        }
    }
    @Override protected void onDraw(Canvas canvas) {
        if (getHeight() > getWidth()) { paint.setStyle(Paint.Style.FILL); paint.setColor(0xff102c3a); canvas.drawRect(0, TtrControlLayout.gameHeight(getWidth(), getHeight()), getWidth(), getHeight(), paint); }
        super.onDraw(canvas);
    }
    private GradientDrawable pill(int color) {
        GradientDrawable d = new GradientDrawable(); d.setCornerRadius(dp(40)); d.setColor(color & 0xffffff | FILL_ALPHA << 24);
        d.setStroke(Math.max(1, dp(1)), 0x99e8f5ef); return d;
    }
    /** Wide keyboard button: icon and label. */
    private final class KeyboardIcon extends View {
        KeyboardIcon() { super(TtrTouchControls.this.getContext()); setBackground(pill(0xb52a6860)); setClickable(true); }
        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), s = h * .62f, top = (h - s) / 2;
            paint.setTextSize(h * .38f); paint.setTypeface(Typeface.DEFAULT_BOLD); paint.setTextAlign(Paint.Align.LEFT);
            float left = (getWidth() - (s * 1.3f + paint.measureText("Keyboard"))) / 2;
            paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1));
            c.drawRoundRect(left, top + s * .18f, left + s * 1.1f, top + s * .82f, dp(2), dp(2), paint);
            paint.setStyle(Paint.Style.FILL);
            for (int row = 0; row < 2; row++) for (int col = 0; col < 5; col++) c.drawRect(left + s * (.12f + col * .19f), top + s * (.3f + row * .16f), left + s * (.2f + col * .19f), top + s * (.37f + row * .16f), paint);
            c.drawRect(left + s * .3f, top + s * .64f, left + s * .8f, top + s * .7f, paint);
            c.drawText("Keyboard", left + s * 1.3f, h / 2 - (paint.descent() + paint.ascent()) / 2, paint);
        }
    }
    /**
     * Controls button: four dots in a diamond (like a controller's face buttons). Hollow while the touch controls
     * are out; filled, on a brighter button, while they are tucked away inside it.
     */
    private final class Tuck extends View {
        boolean tucked;
        Tuck() { super(TtrTouchControls.this.getContext()); setTag("Controls"); setBackground(pill(0xb52a6860)); setClickable(true); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, step = Math.min(w, h) * .2f, r = Math.min(w, h) * .085f;
            if (tucked) { paint.setStyle(Paint.Style.FILL); paint.setColor(0x802a9d8f); c.drawCircle(cx, cy, Math.min(w, h) / 2f - dp(2), paint); }
            paint.setColor(Color.WHITE); paint.setStrokeWidth(Math.max(1, dp(1) * 1.4f));
            paint.setStyle(tucked ? Paint.Style.FILL : Paint.Style.STROKE);
            c.drawCircle(cx, cy - step, r, paint); c.drawCircle(cx, cy + step, r, paint);
            c.drawCircle(cx - step, cy, r, paint); c.drawCircle(cx + step, cy, r, paint);
        }
    }
    /** Tap / Cursor switch: both choices are shown and the active one is filled. */
    private final class ModeSwitch extends View {
        boolean cursor;
        ModeSwitch() { super(TtrTouchControls.this.getContext()); setTag("Pointer"); setBackground(pill(0x99102c3a)); setClickable(true); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), inset = dp(3), half = w / 2;
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xc02a9d8f);
            float l = cursor ? half : inset, r = cursor ? w - inset : half;
            c.drawRoundRect(l, inset, r, h - inset, h / 2, h / 2, paint);
            paint.setTextSize(Math.min(h * .42f, w * .11f)); paint.setTypeface(Typeface.DEFAULT_BOLD); paint.setTextAlign(Paint.Align.CENTER);
            float baseline = h / 2 - (paint.descent() + paint.ascent()) / 2;
            paint.setColor(cursor ? 0xaae8f5ef : Color.WHITE); c.drawText("Tap", w / 4, baseline, paint);
            paint.setColor(cursor ? Color.WHITE : 0xaae8f5ef); c.drawText("Cursor", w * 3 / 4, baseline, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }
    }
    /**
     * Fixed joystick: the base stays in one place, but a touch anywhere in the movement area steers it
     * (direction from the base to the finger). Direction maps to the arrow keys (eight ways), which is how TTR moves.
     */
    private final class Joystick extends View {
        private final Set<String> held = new HashSet<>();
        private int pointer = -1;
        private float baseX, baseY, knobX, knobY, downX, downY;
        private boolean pending; // tap mode: touched but not yet moved far enough to steer
        private long downAt;
        boolean busy() { return pointer >= 0; }
        Joystick() { super(TtrTouchControls.this.getContext()); setTag("Move"); setContentDescription("Movement joystick"); }
        /** Resting {x, y, radius} in this view's coordinates. */
        private int[] rest() {
            int[] j = TtrControlLayout.joystick(TtrTouchControls.this.getWidth(), TtrTouchControls.this.getHeight(), getResources().getDisplayMetrics().density);
            return new int[]{j[0] - getLeft(), j[1] - getTop(), j[2]};
        }
        @Override protected void onDraw(Canvas c) {
            int[] r = rest(); boolean active = pointer >= 0 && !pending; float radius = r[2];
            float bx = active ? baseX : r[0], by = active ? baseY : r[1], kx = active ? knobX : bx, ky = active ? knobY : by;
            paint.setStyle(Paint.Style.FILL); paint.setColor(active ? 0x556373b8 : 0x386373b8); c.drawCircle(bx, by, radius, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(active ? 0xaae8f5ef : 0x60e8f5ef); c.drawCircle(bx, by, radius, paint);
            paint.setStyle(Paint.Style.FILL); paint.setColor(active ? 0xddf3f6ff : 0x80f3f6ff); c.drawCircle(kx, ky, radius * .42f, paint);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked(), index = e.getActionIndex();
            if (action == MotionEvent.ACTION_DOWN || (action == MotionEvent.ACTION_POINTER_DOWN && pointer < 0)) {
                pointer = e.getPointerId(index); int[] r = rest(); baseX = r[0]; baseY = r[1];
                // Tap mode: wait to see whether this is a tap (for the game) or a swipe (movement).
                pending = !cursorMode; downX = e.getX(index); downY = e.getY(index); downAt = e.getEventTime();
                if (!pending) tick(this);
            }
            int at = pointer < 0 ? -1 : e.findPointerIndex(pointer);
            boolean lifted = action == MotionEvent.ACTION_CANCEL || at < 0
                || ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && e.getPointerId(index) == pointer);
            if (pending && !lifted && Math.hypot(e.getX(at) - downX, e.getY(at) - downY) > dp(12)) { pending = false; tick(this); }
            Set<String> next = new HashSet<>();
            if (lifted) {
                if (pending && action != MotionEvent.ACTION_CANCEL && e.getEventTime() - downAt < 500) {
                    float x = downX + getLeft(), y = downY + getTop(); long t = e.getEventTime();
                    if (sendGame(downAt, downAt, MotionEvent.ACTION_DOWN, x, y)) sendGame(downAt, t, MotionEvent.ACTION_UP, x, y);
                }
                pointer = -1; pending = false;
            } else if (!pending) {
                float radius = rest()[2], dx = e.getX(at) - baseX, dy = e.getY(at) - baseY, distance = (float) Math.hypot(dx, dy);
                // The base stays put; the knob stops at its edge while the finger can roam the whole area.
                if (distance > radius) { dx *= radius / distance; dy *= radius / distance; distance = radius; }
                knobX = baseX + dx; knobY = baseY + dy;
                if (distance > radius * .2f) {
                    double angle = Math.atan2(dy, dx), sector = Math.PI / 8;
                    if (Math.abs(angle) < 3 * sector) next.add("KEY_RIGHT");
                    if (Math.abs(angle) > 5 * sector) next.add("KEY_LEFT");
                    if (angle < -sector && angle > -7 * sector) next.add("KEY_UP");
                    if (angle > sector && angle < 7 * sector) next.add("KEY_DOWN");
                }
            }
            for (String key : held) if (!next.contains(key)) output.bind(key, false);
            for (String key : next) if (!held.contains(key)) output.bind(key, true);
            held.clear(); held.addAll(next); invalidate(); return true;
        }
    }
}
