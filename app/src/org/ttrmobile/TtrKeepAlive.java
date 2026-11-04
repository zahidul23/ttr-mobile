package org.ttrmobile;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Keeps TTR Mobile running for a while after the player leaves it, so an open game stays connected (and a game or
 * Windows download left running carries on). Without it Android freezes the app within seconds and the game server
 * drops the toon. It starts when the player leaves with a game or download running and stops when they come back, so
 * its notification (with a button to close the game) shows only while they are away. Notification permission is never
 * asked for: without it Android lists the app under "Active apps" instead.
 *
 * Off screen the game is slowed right down: a few times a second it runs for a short slice and is then paused. That
 * keeps its connection alive for a fraction of the power. If the screen turns off, the processor is kept awake.
 * (Developer switch: no_backup/ttr/background-full-speed turns the slowing off.)
 *
 * Off screen for 5 minutes, whatever it is doing, TTR Mobile closes completely (a system alarm makes sure, since an
 * idle app off screen is frozen and its own timers do not run). It also ends the app's process after a close once
 * nothing (no game, no download) needs it.
 */
public final class TtrKeepAlive extends Service {
    private static final String CHANNEL = "ttr-running", STOP = "org.ttrmobile.STOP_GAME";
    private static final int NOTIFICATION = 1, RUN_MS = 100, PAUSE_MS = 400;
    /** Longest TTR Mobile stays running off screen. */
    static final long BACKGROUND_LIMIT_MS = 5 * 60 * 1000L; // TTR itself logs out an idle toon after 5 minutes
    /** The limit, or a shorter one for testing (developer switch: no_backup/ttr/background-limit-seconds). */
    private static long limit(Context c) {
        try { File f = new File(TtrRuntime.directory(c), "background-limit-seconds");
            if (f.isFile()) return Math.max(15, Long.parseLong(new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII).trim())) * 1000L;
        } catch (Exception ignored) { }
        return BACKGROUND_LIMIT_MS;
    }
    private static final int SIGCONT = 18, SIGSTOP = 19;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static volatile boolean running, background, closed;
    private static volatile long backgroundSince;
    private static volatile Context app;
    private PowerManager.WakeLock wake;
    private Thread slow;
    private String shown;

    /** What needs the app kept running: "game", "download", or null for nothing. */
    private static String reason(Context c) {
        if (TtrRuntime.isSessionRunning(c)) return "game";
        if (TtrGameFiles.busy || TtrRuntime.installing) return "download";
        return null;
    }
    /** Starts the service when the player leaves with a game or download running. */
    private static void start(Context c) {
        if (running || reason(c) == null) return;
        try { c.startForegroundService(new Intent(c, TtrKeepAlive.class)); running = true; }
        catch (Exception e) { TtrDiagnostics.log(c, "Keep running: could not start (" + e + ")"); }
    }

    /** TTR Mobile left the screen (true) or came back (false). */
    static void setBackground(Context c, boolean off) {
        app = c.getApplicationContext();
        if (off == background) return;
        background = off; backgroundSince = SystemClock.elapsedRealtime();
        // A close ends the game, so it starts nothing for one; a download carries on (within the same 5 minutes).
        boolean closing = c instanceof Activity && ((Activity) c).isFinishing();
        if (off) { if (!closing || TtrGameFiles.busy || TtrRuntime.installing) start(c); schedule(c); }
        else { c.getSystemService(AlarmManager.class).cancel(timeout(c)); c.stopService(new Intent(c, TtrKeepAlive.class)); resumeGame(); }
    }
    private static PendingIntent timeout(Context c) {
        return PendingIntent.getBroadcast(c, 2, new Intent(c, Timeout.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    /**
     * Android may deliver this (inexact) alarm up to 75% of the wait late, so it is aimed early enough that even a late
     * delivery comes by the deadline; one that comes early sets the next for the time left.
     */
    private static void schedule(Context c) {
        long now = SystemClock.elapsedRealtime(), left = backgroundSince + limit(c) - now;
        c.getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + Math.max(1000, (long) (left / 1.75)), timeout(c));
    }

    /** The 5-minute limit off screen (the alarm, or the service's own check while it runs). */
    public static final class Timeout extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent intent) {
            if (background) { if (!closeIfOverdue(c)) schedule(c); }
            else if (TtrActivity.alive == 0 && !running) android.os.Process.killProcess(android.os.Process.myPid()); // left from a closed app
        }
    }
    private static boolean closeIfOverdue(Context c) {
        if (!background || SystemClock.elapsedRealtime() - backgroundSince < limit(c) - 5000) return false;
        String why = reason(c);
        TtrDiagnostics.log(c, "Off screen for " + limit(c) / 1000 + " s" + (why != null ? " (" + why + " running)" : "") + "; closing TTR Mobile.");
        TtrGameFiles.cancel(); TtrRuntime.stop(c);
        for (ActivityManager.AppTask task : c.getSystemService(ActivityManager.class).getAppTasks()) try { task.finishAndRemoveTask(); } catch (Exception ignored) { }
        c.stopService(new Intent(c, TtrKeepAlive.class)); c.getSystemService(AlarmManager.class).cancel(timeout(c));
        closed = true;
        ui.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 500);
        return true;
    }

    /** The app was closed: once nothing needs it, end its process so it holds no memory or processor time. */
    static void exitWhenIdle(Context c) {
        app = c.getApplicationContext(); closed = true;
        ui.removeCallbacks(exitCheck); ui.post(exitCheck);
    }
    private static final Runnable exitCheck = new Runnable() { public void run() {
        Context c = app;
        if (!closed || c == null) return;
        if (TtrActivity.alive > 0) { closed = false; return; } // reopened
        String why = reason(c);
        if (why != null) { ui.postDelayed(this, 2000); return; } // e.g. a download finishing first
        TtrDiagnostics.log(c, "Closed: nothing left running; ending the app process.");
        c.stopService(new Intent(c, TtrKeepAlive.class)); c.getSystemService(AlarmManager.class).cancel(timeout(c));
        ui.postDelayed(() -> { if (closed && TtrActivity.alive == 0) android.os.Process.killProcess(android.os.Process.myPid()); }, 300);
    }};

    @Override public void onCreate() {
        super.onCreate(); app = getApplicationContext(); running = true;
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL, "Game running in the background", NotificationManager.IMPORTANCE_LOW));
        String why = reason(this); show(why == null ? "game" : why);
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ttrmobile:background");
        wake.setReferenceCounted(false);
        slow = new Thread(this::slowLoop, "ttr-background"); slow.setDaemon(true); slow.start();
        ui.post(watch);
        TtrDiagnostics.log(this, "Keep running: on (" + why + ").");
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && STOP.equals(intent.getAction())) {
            TtrDiagnostics.log(this, "Keep running: closed from the notification.");
            TtrGameFiles.cancel(); TtrRuntime.stop(this); stopSelf();
            if (TtrActivity.alive == 0) exitWhenIdle(this);
        }
        return START_NOT_STICKY;
    }

    private void show(String why) {
        if (why.equals(shown)) return;
        shown = why; boolean game = why.equals("game");
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, TtrActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, TtrKeepAlive.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL).setSmallIcon(getApplicationInfo().icon)
            .setContentTitle(game ? "Toontown Rewritten is running" : "Downloading game files")
            .setContentText(game ? "Your toon stays connected for up to 5 minutes while you use other apps." : "TTR Mobile keeps downloading for up to 5 minutes in the background.")
            .setContentIntent(open).setOngoing(true).setShowWhen(false).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, game ? "Close game" : "Stop download", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION, n);
    }

    /** Every 2 s: close after 10 minutes off screen; stop when nothing needs it; keep the processor awake off screen. */
    private final Runnable watch = new Runnable() { public void run() {
        if (!running) return;
        closeIfOverdue(TtrKeepAlive.this);
        String why = reason(TtrKeepAlive.this);
        if (why == null || !background) { stopSelf(); return; }
        show(why);
        long left = limit(TtrKeepAlive.this) - (SystemClock.elapsedRealtime() - backgroundSince);
        if (background && left > 0) { if (!wake.isHeld()) wake.acquire(left); }
        else if (wake.isHeld()) wake.release();
        ui.postDelayed(this, 2000);
    }};

    @Override public void onDestroy() {
        running = false; ui.removeCallbacks(watch);
        if (slow != null) slow.interrupt();
        if (wake != null && wake.isHeld()) wake.release();
        resumeGame();
        TtrDiagnostics.log(this, "Keep running: off.");
        super.onDestroy();
    }

    @Override public void onTaskRemoved(Intent root) { super.onTaskRemoved(root); } // the screen's own close handles it
    @Override public IBinder onBind(Intent intent) { return null; }

    /** Off screen with the game open: run a short slice, pause, repeat. */
    private void slowLoop() {
        int[] pids = new int[0]; long scanned = 0; boolean paused = false;
        try {
            while (running) {
                Context c = app;
                boolean slowDown = background && c != null && TtrRuntime.isSessionRunning(c) && !switchOn(c, "background-full-speed");
                if (!slowDown) { if (paused) { signal(pids, SIGCONT); paused = false; } Thread.sleep(250); continue; }
                long now = SystemClock.elapsedRealtime();
                if (now - scanned > 5000) {
                    int[] found = gamePids(); scanned = now;
                    if (!Arrays.equals(found, pids)) TtrDiagnostics.log(c, "Keep running: slowing the game off screen (" + found.length + " process" + (found.length == 1 ? "" : "es") + ").");
                    pids = found;
                }
                signal(pids, SIGCONT); paused = false; Thread.sleep(RUN_MS);
                if (!background) continue;
                signal(pids, SIGSTOP); paused = true; Thread.sleep(PAUSE_MS);
            }
        } catch (InterruptedException ignored) { }
        finally { signal(pids, SIGCONT); }
    }
    /** Lets the game run at full speed again right away. */
    static void resumeGame() { signal(gamePids(), SIGCONT); }
    private static void signal(int[] pids, int signal) { for (int pid : pids) try { android.os.Process.sendSignal(pid, signal); } catch (Exception ignored) { } }
    private static boolean switchOn(Context c, String name) { try { return new File(TtrRuntime.directory(c), name).isFile(); } catch (Exception e) { return false; } }

    /** The game's Windows processes (TTREngine64.exe, started through the linker). */
    static int[] gamePids() {
        List<Integer> found = new ArrayList<>(); int self = android.os.Process.myPid(); File[] all = new File("/proc").listFiles();
        if (all != null) for (File p : all) {
            if (!p.getName().matches("[0-9]+")) continue;
            int pid = Integer.parseInt(p.getName()); if (pid == self) continue;
            try {
                String cmd = new String(Files.readAllBytes(new File(p, "cmdline").toPath()), StandardCharsets.UTF_8);
                if (cmd.startsWith("/system/bin/linker64\u0000") && cmd.contains(TtrPlatform.ENGINE)) found.add(pid);
            } catch (Exception ignored) { }
        }
        int[] out = new int[found.size()]; for (int i = 0; i < out.length; i++) out[i] = found.get(i);
        Arrays.sort(out); return out;
    }
}
