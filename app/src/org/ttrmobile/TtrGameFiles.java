package org.ttrmobile;

import android.content.Context;

/**
 * Independent automatic preparation of the chosen game's files; account operations never wait for extraction.
 * Only one game is prepared at a time, and a game that has never been downloaded waits until the player asks
 * (download()), so picking a game never downloads the other one.
 */
public final class TtrGameFiles {
    public static volatile boolean busy, ready, attempted;
    public static volatile int percent=-1;
    public static volatile String message = "Preparing game files…", release = "TTR release information unavailable";
    /** Game the fields above describe. */
    public static volatile String game = "";
    private static Thread worker;
    private TtrGameFiles() { }
    /** Release line for a game's files. */
    public static String release(Context c, String game) { return TtrGame.CLASH.equals(game) ? TtrClashUpdater.release(c) : TtrUpdater.release(c); }
    /** Follows the game picked on the launcher screen; a download for another game stops. */
    public static synchronized void follow(Context context) {
        Context c = context.getApplicationContext(); String chosen = TtrGame.current(c);
        if (chosen.equals(game)) return;
        if (worker != null) worker.interrupt();
        game = chosen; ready = false; attempted = false; percent = -1;
        message = chosen.isEmpty() ? "" : TtrGame.started(c, chosen) ? "Preparing game files…" : TtrGame.name(chosen) + " isn't downloaded yet.";
        release = chosen.isEmpty() ? "" : release(c, chosen);
        TtrLauncher.refresh();
    }
    /** Checks (and updates) the chosen game's files when it is installed. */
    public static synchronized void ensure(Context context, boolean force) {
        follow(context);
        if (game.isEmpty() || (!force && !TtrGame.started(context.getApplicationContext(), game))) return;
        start(context, force);
    }
    /** First download of the chosen game, at the player's request. */
    public static synchronized void download(Context context) { follow(context); if (!game.isEmpty()) start(context, false); }
    private static synchronized void start(Context context, boolean force) {
        if (busy || TtrLauncher.playing || TtrLauncher.launchReady || (!force && attempted)) return;
        Context c = context.getApplicationContext(); final String target = game;
        if (TtrRuntime.isSessionRunning(c)) { message = "Game session open. Files will be checked after exit."; TtrLauncher.refresh(); return; }
        attempted = true; busy = true; ready = false; percent=-1; release = release(c, target);
        message = TtrGame.installed(c, target) ? "Checking game files…" : "Starting the " + TtrGame.name(target) + " download…";
        worker = new Thread(() -> {
            try {
                TtrUpdater.Progress progress = new TtrUpdater.Progress(){
                // One line per file being worked on (two run in parallel), then the overall count.
                final java.util.Map<String, String> lines = new java.util.TreeMap<>(); final java.util.Map<String, Double> parts = new java.util.HashMap<>();
                int done, total; String overall = "";
                public synchronized void show(String text){overall=text;update();}
                public synchronized void amount(int complete,int count){done=complete;total=count;update();}
                public synchronized void file(String name,String line,double fraction){if(line==null){lines.remove(name);parts.remove(name);}else{lines.put(name,line);parts.put(name,fraction);}update();}
                private void update(){
                    if(!target.equals(game))return;
                    if(lines.isEmpty())message=overall;
                    else{StringBuilder b=new StringBuilder(total==1?"Updating 1 file":"Updating "+total+" files · "+done+" done");
                        for(String l:lines.values())b.append('\n').append(l);message=b.toString();}
                    double sum=done;for(double f:parts.values())sum+=f;
                    percent=total>0?Math.max(percent,(int)(100*sum/total)):lines.isEmpty()?-1:percent;
                    TtrLauncher.refresh();
                }};
                java.io.File dir = TtrGame.CLASH.equals(target) ? TtrClashUpdater.update(c, force, progress) : TtrUpdater.update(c, force, progress);
                if (TtrGame.TTR.equals(target)) TtrGameSettings.prepare(c, dir);
                if (target.equals(game)) { ready = true; release = release(c, target); }
            }
            catch (Exception e) { if (target.equals(game)) message = Thread.currentThread().isInterrupted() ? "Game preparation cancelled." : "Game preparation failed. Check your connection and storage, then retry."; }
            finally { synchronized (TtrGameFiles.class) { busy = false; worker = null; } TtrLauncher.refresh(); }
        }, "ttr-game-files"); worker.start(); TtrLauncher.refresh();
    }
    public static synchronized void cancel() { if (worker != null) worker.interrupt(); }
    /** After a game's files were removed: it shows as not downloaded. */
    public static synchronized void forget(Context context, String which) {
        if (!which.equals(game)) return;
        Context c = context.getApplicationContext();
        ready = false; attempted = false; percent = -1; release = release(c, which);
        message = TtrGame.name(which) + " isn't downloaded yet.";
        TtrLauncher.refresh();
    }
    /** True while files for this game are being downloaded or checked. */
    public static boolean working(String which) { return busy && which.equals(game); }
}
