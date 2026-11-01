package org.ttrmobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.*;
import org.json.JSONObject;

/** Account authentication is independent of game updates and never launches during verification. */
public final class TtrLauncher {
    public interface Listener { void changed(); }
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static WeakReference<Listener> listener = new WeakReference<>(null);
    private static Thread worker;
    private static String submitted;
    public static volatile boolean busy, launchReady, playing;
    public static volatile String selected = "", message = "", challenge;
    public static volatile int challengeId;
    private TtrLauncher() { }
    public static synchronized void attach(Listener view) { listener = new WeakReference<>(view); }
    public static synchronized void detach(Listener view) { if (listener.get() == view) listener.clear(); }
    public static void refresh() { ui.post(() -> { Listener view = listener.get(); if (view != null) view.changed(); }); }
    private static void progress(String text) { message = text; refresh(); }
    public static synchronized boolean consumeLaunch() { if (!launchReady) return false; launchReady = false; return true; }
    public static synchronized void submit(String code) { submitted = code; TtrLauncher.class.notifyAll(); }
    public static synchronized void cancel(Context c) {
        if (worker != null) worker.interrupt(); challenge = null; submitted = null; launchReady = false; TtrSession.clear(c); TtrLauncher.class.notifyAll();
    }
    public static synchronized void verify(Context c, String account) { start(c, account, false); }
    public static synchronized void play(Context c, String account) { start(c, account, true); }
    private static synchronized void start(Context context, String account, boolean play) {
        if (busy || account == null || account.isEmpty()) return;
        Context c = context.getApplicationContext();
        if (play && !TtrGameFiles.ready) { progress("Game files are still preparing."); return; }
        if (TtrRuntime.isSessionRunning(c) && play) { progress("A game session is already open."); return; }
        busy = true; playing = play; launchReady = false; challenge = null;
        worker = new Thread(() -> {
            try {
                String password = new TtrAccounts(c).password(account);
                if (password.isEmpty()) throw new java.io.IOException("Select a saved account.");
                progress(play ? "Signing in…" : "Verifying account…");
                TtrLoginFlow.Session session = TtrLoginFlow.login(account, password, fields -> {
                    JSONObject json = TtrHttp.login(fields); Map<String, String> values = new HashMap<>();
                    Iterator<String> keys = json.keys(); while (keys.hasNext()) { String key = keys.next(); values.put(key, json.optString(key)); } return values;
                }, new TtrLoginFlow.Prompts() {
                    public String code(String banner) throws Exception {
                        synchronized (TtrLauncher.class) {
                            submitted = null; challenge = banner == null ? "Enter your ToonGuard or authenticator code." : banner; challengeId++;
                            progress("Verification required."); while (submitted == null) TtrLauncher.class.wait();
                            String result = submitted; submitted = null; challenge = null; return result;
                        }
                    }
                    public void queue(String position) { progress("Login queue" + (position != null && position.matches("[0-9]{1,6}") ? " · position " + position : "") + ". Checking in 30 seconds…"); }
                    public void waitForQueue() throws Exception { Thread.sleep(30000); }
                });
                TtrHttp.checkCancelled(); new TtrAccounts(c).markVerified(account, password);
                if (play) {
                    String manifest = "https://cdn.toontownrewritten.com" + session.manifest;
                    File game = manifest.equals(TtrHttp.MANIFEST) ? TtrUpdater.gameDirectory(c) : TtrUpdater.update(c, manifest, TtrLauncher::progress);
                    TtrHttp.checkCancelled(); TtrGameSettings.prepare(c, game); TtrSession.write(c, session, game);
                    synchronized (TtrLauncher.class) { TtrHttp.checkCancelled(); launchReady = true; }
                    progress("Starting game…");
                } else progress("Account verified."); // The temporary session is discarded; Play obtains a fresh one.
            } catch (Exception e) {
                if (play) TtrSession.clear(c);
                if (Thread.currentThread().isInterrupted() || e instanceof InterruptedException) progress("Cancelled.");
                else if (e instanceof TtrLoginFlow.Rejected || e.getClass() == java.io.IOException.class) progress(e.getMessage());
                else progress("Unable to verify the account. Check your connection and try again.");
            } finally { synchronized (TtrLauncher.class) { busy = false; playing = false; worker = null; challenge = null; submitted = null; } refresh(); }
        }, "ttr-account-auth"); worker.start(); refresh();
    }
}
