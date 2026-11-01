package org.ttrmobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.Locale;

/**
 * Staged progress with time-remaining estimates for runtime preparation and game launch.
 *
 * Runtime hooks publish observable facts (setup percent, download bytes, files trimmed, runtime
 * phase). Each stage's remaining time comes from its live rate when one exists, otherwise from
 * durations measured on earlier runs on this device (learned per stage, with conservative
 * defaults). Shared by the Windows and Linux apps; platform hooks that do not exist simply leave
 * their fields unset.
 */
public final class TtrStartupProgress {
    // Published by runtime hooks.
    public static volatile int setup = -1;
    public static volatile String runtimeStage = "Preparing runtime…";
    public static volatile String setupMessage;
    public static volatile String setupSpeed;
    public static volatile long setupBytes = -1, setupTotalBytes = -1;
    public static volatile int setupItems = -1, setupTotalItems = -1;
    public static volatile int trimDone = -1, trimTotal = -1;
    /** Set when upstream setup reports an error; setupAttempt is the attempt about to run. */
    public static volatile boolean setupFailed;
    public static volatile int setupAttempt = 1;

    public static final class Stage {
        public final String label, detail, eta;
        public final int percent, step, steps;
        /** Estimated seconds remaining, or -1 when unknown. */
        public final double seconds;
        public Stage(String label, int percent) { this(label, percent, "", -1, 0, 0); }
        Stage(String label, int percent, String detail, double seconds, int step, int steps) {
            this.label = label; this.percent = percent; this.detail = detail; this.seconds = seconds; this.step = step; this.steps = steps;
            this.eta = percent == 100 || steps == 0 ? "" : seconds == OVERRUN ? eta(OVERRUN) : seconds < 0 ? (label.startsWith("Allow") ? "Waiting for you" : eta(-1)) : eta(seconds);
        }
    }

    /** One tracked flow (runtime preparation or game launch). */
    private static final class Flow {
        final String name;
        String stage = ""; long flowStart, stageStart, wallStart; int shown; boolean trimmed;
        Flow(String name) { this.name = name; }
        /** Records a natural stage transition and returns seconds spent in the current stage. */
        double enter(SharedPreferences timings, String next, boolean learnPrevious) {
            long now = SystemClock.elapsedRealtime();
            if (!next.equals(stage)) {
                if (learnPrevious && !stage.isEmpty() && stageStart > 0) learn(timings, name + "." + stage, (now - stageStart) / 1000.0);
                if (stage.isEmpty()) { flowStart = now; wallStart = System.currentTimeMillis(); shown = 0; trimmed = false; }
                if (next.equals("trim")) trimmed = true;
                stage = next; stageStart = now;
            }
            return (now - stageStart) / 1000.0;
        }
        double elapsed() { return (SystemClock.elapsedRealtime() - flowStart) / 1000.0; }
        void reset() { stage = ""; flowStart = stageStart = wallStart = 0; shown = 0; }
        /**
         * Overall percent from fixed stage weights: completed stages plus the current stage's
         * fraction. Never moves backwards within a flow, so an early misestimate cannot pin it.
         */
        int percent(String[] stages, int[] weights, String current, double fraction) {
            int total = 0, before = 0, own = 0;
            for (int i = 0; i < stages.length; i++) { total += weights[i]; if (stages[i].equals(current)) own = weights[i]; else if (own == 0) before += weights[i]; }
            if (own == 0) return shown;
            double f = Math.max(0, Math.min(0.97, fraction));
            shown = Math.max(shown, (int) Math.min(99, Math.round(100.0 * (before + own * f) / total))); return shown;
        }
    }
    private static final Flow runtimeFlow = new Flow("runtime"), clientFlow = new Flow("client");
    private static long lastBytes = -1, lastBytesAt; private static double byteRate = -1;

    // Conservative first-run defaults (seconds); replaced by measured durations on this device.
    private static double expected(SharedPreferences t, String key) {
        double fallback;
        switch (key) {
            case "runtime.init": fallback = 3; break;
            case "runtime.trim": fallback = 25; break;
            case "runtime.setup": fallback = 20; break;
            case "runtime.boot": fallback = 12; break;
            case "client.signin": fallback = 2; break;
            case "client.engine": fallback = 3; break;
            case "client.window": fallback = 22; break;
            default: fallback = 10;
        }
        return t == null ? fallback : t.getFloat(key, (float) fallback);
    }
    private static void learn(SharedPreferences t, String key, double seconds) {
        if (t == null || seconds <= 0.2 || seconds > 1800) return;
        double previous = t.getFloat(key, -1f);
        double value = previous < 0 ? seconds : previous * 0.5 + seconds * 0.5;
        t.edit().putFloat(key, (float) value).apply();
    }
    private static SharedPreferences timings(Context c) {
        return c == null ? null : c.getApplicationContext().getSharedPreferences("ttr-progress-timing", Context.MODE_PRIVATE);
    }

    /** Remaining seconds for the current stage: live rate when measurable, else learned duration. */
    private static double remaining(double expected, double spent, int percent) {
        if (percent < 0 && spent > expected * 1.25 + 3) return OVERRUN;
        double byTime = Math.max(expected - spent, Math.min(5, expected * 0.15));
        if (percent >= 5 && percent < 100 && spent >= 1) {
            double byRate = spent * (100 - percent) / percent;
            double weight = Math.min(1, percent / 40.0); // trust the live rate more as it accumulates
            return byRate * weight + byTime * (1 - weight);
        }
        return byTime;
    }

    /** Sentinel: the stage has run well past its usual duration and has no live progress. */
    static final double OVERRUN = -2;
    static String eta(double seconds) {
        if (seconds == OVERRUN) return "Taking longer than usual";
        if (seconds < 0) return "Estimating time remaining…";
        if (seconds < 5) return "Almost done";
        long s = Math.round(seconds);
        if (s < 60) return "About " + s + " s remaining";
        s = Math.round(s / 10.0) * 10;
        return "About " + (s / 60) + " min" + (s % 60 == 0 ? "" : " " + (s % 60) + " s") + " remaining";
    }
    private static String bytes(long value) {
        if (value >= 1_000_000_000L) return String.format(Locale.US, "%.2f GB", value / 1e9);
        if (value >= 1_000_000L) return String.format(Locale.US, "%.0f MB", value / 1e6);
        return String.format(Locale.US, "%.0f KB", value / 1e3);
    }

    /** Runtime preparation: file access → one-time optimization → environment setup → start. */
    public static Stage runtime(Context c, String name, String phase, boolean storageGranted) {
        SharedPreferences t = timings(c);
        boolean ready = phase.equals("windows_ready") || phase.equals("x11_ready") || phase.equals("starting_game") || phase.equals("client_started")
            || phase.equals("game") || phase.equals("closing_game") || phase.equals("closed");
        if (ready) {
            if (!runtimeFlow.stage.isEmpty()) { runtimeFlow.enter(t, "done", true); runtimeFlow.reset(); }
            return new Stage(name + " ready", 100, "", 0, 4, 4);
        }
        if (phase.endsWith("failed") || phase.equals("window_timeout") || (setupFailed && setupAttempt > 3)) {
            runtimeFlow.reset();
            return new Stage(name + " needs attention", -1, "Close and reopen TTR Mobile to retry.", -1, 0, 0);
        }
        String stage; int step; String label, detail = ""; int stagePercent = -1;
        if (!storageGranted) { stage = "permission"; step = 1; label = "Allow file access to continue"; detail = "Android asks once. TTR's game files are stored in shared storage."; }
        else if (trimTotal > 0 && trimDone < trimTotal) {
            stage = "trim"; step = 2; label = "Optimizing " + name + " storage (one time)";
            stagePercent = (int) (100L * Math.max(0, trimDone) / trimTotal); detail = Math.max(0, trimDone) + " of " + trimTotal + " files";
        } else if (setupFailed) {
            stage = "setup"; step = 3; label = "Setup didn't finish · retrying (attempt " + setupAttempt + " of 3)";
            detail = "This can happen on a first install; no action needed.";
        } else if (setup >= 0 && setup < 100) {
            stage = "setup"; step = 3; label = setupMessage == null || setupMessage.trim().isEmpty() ? "Preparing " + name + " environment" : setupMessage.trim();
            stagePercent = setup; detail = setup > 0 ? setup + "%" : "";
            if (setupTotalBytes > 0 && setupBytes >= 0) {
                detail = bytes(setupBytes) + " of " + bytes(setupTotalBytes) + (setupSpeed == null || setupSpeed.isEmpty() ? "" : " · " + setupSpeed);
                long now = SystemClock.elapsedRealtime();
                if (lastBytes >= 0 && setupBytes > lastBytes && now > lastBytesAt) {
                    double rate = (setupBytes - lastBytes) * 1000.0 / (now - lastBytesAt);
                    byteRate = byteRate < 0 ? rate : byteRate * 0.7 + rate * 0.3;
                }
                if (setupBytes != lastBytes) { lastBytes = setupBytes; lastBytesAt = now; }
            } else if (setupTotalItems > 0 && setupItems >= 0) detail = setup + "% · " + setupItems + " of " + setupTotalItems + " items";
        } else if (setup >= 100) { stage = "boot"; step = 4; label = "Starting " + name; detail = "Starting system services"; }
        else { stage = "init"; step = 1; label = runtimeStage; }

        double spent = runtimeFlow.enter(t, stage, !stage.equals("permission"));
        double left, current = -1;
        if (stage.equals("permission")) left = -1;
        else {
            current = stage.equals("setup") && setupTotalBytes > 0 && byteRate > 0
                ? Math.max(0, setupTotalBytes - setupBytes) / byteRate + 3
                : remaining(expected(t, "runtime." + stage), spent, stagePercent);
            left = current;
            String[] order = {"init", "trim", "setup", "boot"};
            boolean after = false;
            for (String s : order) {
                if (after && !(s.equals("trim") && trimTotal <= 0)) left += expected(t, "runtime." + s) * (s.equals("setup") ? 0.5 : 1);
                if (s.equals(stage)) after = true;
            }
            if (current == OVERRUN) left = OVERRUN;
        }
        double fraction = stagePercent >= 0 ? stagePercent / 100.0 : current == OVERRUN ? 0.97 : current < 0 ? 0 : spent / Math.max(0.001, spent + current);
        int overall = runtimeFlow.percent(new String[]{"init", "trim", "setup", "boot"}, new int[]{5, runtimeFlow.trimmed ? 30 : 0, 35, 30}, stage, fraction);
        return new Stage(label, left == -1 ? -1 : overall, detail, left, step, 4);
    }

    /** Game launch: sign in → start engine → open window (waits for the runtime when needed). */
    public static Stage client(Context c, String phase, boolean signingIn, boolean signedIn, Stage runtime) {
        SharedPreferences t = timings(c);
        if (phase.equals("game")) { if (!clientFlow.stage.isEmpty()) { clientFlow.enter(t, "done", true); clientFlow.reset(); } return new Stage("Game window open", 100, "", 0, 3, 3); }
        if (phase.equals("closing_game")) { clientFlow.reset(); return new Stage("Closing client…", -1); }
        if (phase.endsWith("failed") || phase.equals("window_timeout") || phase.equals("session_invalid")) { clientFlow.reset(); return new Stage("Client startup stopped", 0); }
        String stage, label, detail; int step;
        // The game process starts within a second, then unpacks itself for a while before it writes any log:
        // that whole stretch is "Starting Toontown"; the game's own log then drives "Loading Toontown".
        if (phase.equals("client_started")) { stage = "engine"; step = 2; label = "Unpacking the game"; detail = ""; }
        else if (phase.equals("starting_game") || signedIn) {
            boolean waiting = runtime != null && runtime.percent >= 0 && runtime.percent < 100;
            stage = waiting ? "waiting" : "engine"; step = 2;
            label = waiting ? "Signed in · waiting for " + runtimeStageName(runtime) : "Unpacking the game";
            detail = waiting ? runtime.label : "";
        }
        else if (signingIn) { stage = "signin"; step = 1; label = "Signing in"; detail = "Contacting Toontown Rewritten"; }
        else { clientFlow.reset(); return new Stage("Ready to play", 0); }
        // Once TTR's own log appears, follow its milestones up to the game window.
        int stagePercent = -1;
        if ((stage.equals("engine") || stage.equals("window")) && clientFlow.wallStart > 0) {
            int reached = TtrClientLog.reached(c, clientFlow.wallStart);
            if (reached >= 0) {
                int count = TtrClientLog.MARKERS.length;
                stage = "window"; step = 3; stagePercent = (reached + 1) * 100 / (count + 2);
                label = "Loading Toontown"; detail = TtrClientLog.LABELS[Math.min(reached, count - 1)];
            }
        }
        double spent = clientFlow.enter(t, stage, !stage.equals("waiting"));
        double current = stage.equals("waiting") ? (runtime.seconds < 0 ? 15 : runtime.seconds) : remaining(expected(t, "client." + stage), spent, stagePercent);
        double left = current;
        boolean after = false;
        for (String s : new String[]{"signin", "engine", "window"}) {
            if (after) left += expected(t, "client." + s);
            if (s.equals(stage) || (stage.equals("waiting") && s.equals("signin"))) after = true;
        }
        if (current == OVERRUN) left = OVERRUN;
        // Weights follow real time: unpacking is most of the wait. Timed stages stop short of their end so the bar
        // never runs ahead of what the game has actually reached.
        double timed = Math.min(.9, current == OVERRUN ? .9 : spent / Math.max(0.001, spent + Math.max(0, current)));
        int overall = clientFlow.percent(new String[]{"signin", "waiting", "engine", "window"}, new int[]{8, 12, 55, 25}, stage, stagePercent >= 0 ? stagePercent / 100.0 : timed);
        return new Stage(label, overall, detail, left, step, 3);
    }
    private static String runtimeStageName(Stage runtime) { return runtime.label.toLowerCase(Locale.US).contains("windows") ? "Windows" : "the runtime"; }
    private TtrStartupProgress() { }
}
