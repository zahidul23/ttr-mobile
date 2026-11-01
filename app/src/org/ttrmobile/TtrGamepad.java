package org.ttrmobile;

import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Turns a game controller into the keyboard and mouse input TTR already understands (Wine here has no way to see
 * the controller itself). Xbox layout, with TTR's default keys:
 * left stick = move (arrows), right stick = cursor, R3 (press the right stick) or either trigger = click,
 * A = Jump (Ctrl), B = Throw (Delete), X = Map (Alt), Y = Shticker Book (F8), Start = Options (Shift+Esc),
 * LB = camera angle (Tab), RB = reverse camera angle (Shift+Tab),
 * D-pad up/down = look up/down (Page Up/Down), D-pad left/right = Gags/Tasks (Home/End, held).
 */
final class TtrGamepad {
    interface Output {
        /** An Android key code for the game, pressed or released. */
        void key(int code, boolean down);
        /** Moves the cursor by (dx, dy) game pixels; the first move switches to Cursor mode so it shows. */
        void cursor(float dx, float dy);
        /** Left mouse button at the cursor. */
        void click(boolean down);
        /** Game height in pixels, for cursor speed. */
        int gameHeight();
    }
    private static final int SHIFT = KeyEvent.KEYCODE_SHIFT_LEFT;
    private final Output out;
    /** Each control's keys while it is held (a combo such as Shift+Tab holds two), by control. */
    private final java.util.Map<String, int[]> held = new java.util.HashMap<>();
    /** What is holding the click down: "R3", "LT", "RT" (several at once are fine). */
    private final java.util.Set<String> clickers = new java.util.HashSet<>();
    private float aimX, aimY;
    private long lastFrame;
    private boolean aiming;

    TtrGamepad(Output out) { this.out = out; }

    static boolean fromController(InputDevice d, int source) {
        int s = d != null ? d.getSources() : source;
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    /** The game keys for a controller button, or null when it does nothing. */
    private static int[] keys(int button) {
        switch (button) {
            case KeyEvent.KEYCODE_BUTTON_A: return new int[]{KeyEvent.KEYCODE_CTRL_LEFT};             // Jump
            case KeyEvent.KEYCODE_BUTTON_B: return new int[]{KeyEvent.KEYCODE_FORWARD_DEL};           // Throw
            case KeyEvent.KEYCODE_BUTTON_X: return new int[]{KeyEvent.KEYCODE_ALT_LEFT};              // Map
            case KeyEvent.KEYCODE_BUTTON_Y: return new int[]{KeyEvent.KEYCODE_F8};                    // Shticker Book
            case KeyEvent.KEYCODE_BUTTON_START: return new int[]{SHIFT, KeyEvent.KEYCODE_ESCAPE};     // Options
            case KeyEvent.KEYCODE_BUTTON_L1: return new int[]{KeyEvent.KEYCODE_TAB};                  // camera angle
            case KeyEvent.KEYCODE_BUTTON_R1: return new int[]{SHIFT, KeyEvent.KEYCODE_TAB};           // reverse camera angle
            case KeyEvent.KEYCODE_DPAD_UP: return new int[]{KeyEvent.KEYCODE_PAGE_UP};                // look up
            case KeyEvent.KEYCODE_DPAD_DOWN: return new int[]{KeyEvent.KEYCODE_PAGE_DOWN};            // look down
            case KeyEvent.KEYCODE_DPAD_LEFT: return new int[]{KeyEvent.KEYCODE_MOVE_HOME};            // Gags
            case KeyEvent.KEYCODE_DPAD_RIGHT: return new int[]{KeyEvent.KEYCODE_MOVE_END};            // Tasks
            default: return null;
        }
    }

    /** A controller button; true when it was handled (Back, Home and volume are left to Android). */
    boolean key(KeyEvent e) {
        if (!fromController(e.getDevice(), e.getSource())) return false;
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) return false;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (e.getRepeatCount() > 0) return true;
        if (code == KeyEvent.KEYCODE_BUTTON_THUMBR) { click("R3", down); return true; }
        if (code == KeyEvent.KEYCODE_BUTTON_L2) { click("LT", down); return true; } // triggers sent as buttons
        if (code == KeyEvent.KEYCODE_BUTTON_R2) { click("RT", down); return true; }
        int[] k = keys(code);
        if (k != null) set("button" + code, k, down);
        return true; // unused buttons (L3, Select) are kept from reaching the game as stray keys
    }

    /** Sticks and the D-pad hat; true when handled (which also stops Android turning them into arrow keys). */
    boolean motion(MotionEvent e) {
        if (!fromController(e.getDevice(), e.getSource()) || e.getActionMasked() != MotionEvent.ACTION_MOVE) return false;
        InputDevice d = e.getDevice();
        // Left stick: eight-way movement, like the touch joystick.
        float x = axis(e, d, MotionEvent.AXIS_X), y = axis(e, d, MotionEvent.AXIS_Y);
        boolean moving = Math.hypot(x, y) > .35;
        double angle = Math.atan2(y, x), sector = Math.PI / 8;
        set("right", new int[]{KeyEvent.KEYCODE_DPAD_RIGHT}, moving && Math.abs(angle) < 3 * sector);
        set("left", new int[]{KeyEvent.KEYCODE_DPAD_LEFT}, moving && Math.abs(angle) > 5 * sector);
        set("up", new int[]{KeyEvent.KEYCODE_DPAD_UP}, moving && angle < -sector && angle > -7 * sector);
        set("down", new int[]{KeyEvent.KEYCODE_DPAD_DOWN}, moving && angle > sector && angle < 7 * sector);
        // D-pad reported as a hat (Xbox controllers): same as the D-pad buttons.
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        set("button" + KeyEvent.KEYCODE_DPAD_UP, keys(KeyEvent.KEYCODE_DPAD_UP), hy < -.5f);
        set("button" + KeyEvent.KEYCODE_DPAD_DOWN, keys(KeyEvent.KEYCODE_DPAD_DOWN), hy > .5f);
        set("button" + KeyEvent.KEYCODE_DPAD_LEFT, keys(KeyEvent.KEYCODE_DPAD_LEFT), hx < -.5f);
        set("button" + KeyEvent.KEYCODE_DPAD_RIGHT, keys(KeyEvent.KEYCODE_DPAD_RIGHT), hx > .5f);
        // Right stick: cursor. Xbox controllers report it on Z/RZ, some others on RX/RY.
        boolean zrz = d != null && d.getMotionRange(MotionEvent.AXIS_RZ, e.getSource()) != null;
        aimX = axis(e, d, zrz ? MotionEvent.AXIS_Z : MotionEvent.AXIS_RX);
        aimY = axis(e, d, zrz ? MotionEvent.AXIS_RZ : MotionEvent.AXIS_RY);
        if (Math.hypot(aimX, aimY) > .15 && !aiming) { aiming = true; lastFrame = 0; Choreographer.getInstance().postFrameCallback(aim); }
        // Triggers (Xbox controllers send them as axes): pressed past halfway clicks, let back under a third releases.
        trigger("LT", Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)));
        trigger("RT", Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)));
        return true;
    }
    /** Stick value with the controller's own dead zone (flat) removed. */
    private static float axis(MotionEvent e, InputDevice d, int which) {
        float v = e.getAxisValue(which);
        InputDevice.MotionRange r = d != null ? d.getMotionRange(which, e.getSource()) : null;
        float flat = r != null ? r.getFlat() : 0;
        return Math.abs(v) > flat ? v : 0;
    }

    /** Moves the cursor every frame while the right stick is held: slow near the centre, fast at the edge. */
    private final Choreographer.FrameCallback aim = new Choreographer.FrameCallback() {
        public void doFrame(long now) {
            float mag = (float) Math.hypot(aimX, aimY);
            if (mag <= .15f) { aiming = false; return; }
            float dt = lastFrame == 0 ? 1 / 60f : Math.min(.05f, (now - lastFrame) / 1e9f); lastFrame = now;
            float t = Math.min(1, (mag - .15f) / .85f), speed = out.gameHeight() * (.25f + 1.35f * t * t); // game heights per second
            out.cursor(aimX / mag * speed * dt, aimY / mag * speed * dt);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void trigger(String which, float value) {
        if (value > .5f) click(which, true); else if (value < .33f) click(which, false);
    }
    /** The click goes down with the first control that presses it and up when the last one lets go. */
    private void click(String which, boolean down) {
        boolean was = !clickers.isEmpty();
        if (down) clickers.add(which); else clickers.remove(which);
        if (was != !clickers.isEmpty()) out.click(!was);
    }
    /** Presses a control's keys in order (Shift first) or releases them in reverse. */
    private void set(String control, int[] keys, boolean down) {
        if (down == held.containsKey(control)) return;
        if (down) { held.put(control, keys); for (int k : keys) out.key(k, true); }
        else { int[] k = held.remove(control); for (int i = k.length - 1; i >= 0; i--) out.key(k[i], false); }
    }
    /** Lets go of everything (the controller was removed, or the game lost focus). */
    void release() {
        for (String c : new java.util.ArrayList<>(held.keySet())) set(c, null, false);
        aimX = aimY = 0; if (!clickers.isEmpty()) { clickers.clear(); out.click(false); }
    }
}
