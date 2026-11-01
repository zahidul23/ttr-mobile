package org.ttrmobile;

import android.content.Context;

/** Independent automatic preparation; account operations never wait for extraction. */
public final class TtrGameFiles {
    public static volatile boolean busy, ready, attempted;
    public static volatile int percent=-1;
    public static volatile String message = "Preparing game files…", release = "TTR release information unavailable";
    private static Thread worker;
    private TtrGameFiles() { }
    public static synchronized void ensure(Context context, boolean force) {
        if (busy || TtrLauncher.playing || TtrLauncher.launchReady || (!force && attempted)) return;
        Context c = context.getApplicationContext();
        if (TtrRuntime.isSessionRunning(c)) { message = "Game session open. Files will be checked after exit."; TtrLauncher.refresh(); return; }
        attempted = true; busy = true; ready = false; percent=-1; release = TtrUpdater.release(c);
        worker = new Thread(() -> {
            try { java.io.File game = TtrUpdater.update(c, force, new TtrUpdater.Progress(){
                // One line per file being worked on (two run in parallel), then the overall count.
                final java.util.Map<String, String> lines = new java.util.TreeMap<>(); final java.util.Map<String, Double> parts = new java.util.HashMap<>();
                int done, total; String overall = "";
                public synchronized void show(String text){overall=text;update();}
                public synchronized void amount(int complete,int count){done=complete;total=count;update();}
                public synchronized void file(String name,String line,double fraction){if(line==null){lines.remove(name);parts.remove(name);}else{lines.put(name,line);parts.put(name,fraction);}update();}
                private void update(){
                    if(lines.isEmpty())message=overall;
                    else{StringBuilder b=new StringBuilder(total==1?"Updating 1 file":"Updating "+total+" files · "+done+" done");
                        for(String l:lines.values())b.append('\n').append(l);message=b.toString();}
                    double sum=done;for(double f:parts.values())sum+=f;
                    percent=total>0?Math.max(percent,(int)(100*sum/total)):lines.isEmpty()?-1:percent;
                    TtrLauncher.refresh();
                }
            }); TtrGameSettings.prepare(c, game); ready = true; release = TtrUpdater.release(c); }
            catch (Exception e) { message = Thread.currentThread().isInterrupted() ? "Game preparation cancelled." : "Game preparation failed. Check your connection and storage, then retry."; }
            finally { synchronized (TtrGameFiles.class) { busy = false; worker = null; } TtrLauncher.refresh(); }
        }, "ttr-game-files"); worker.start(); TtrLauncher.refresh();
    }
    public static synchronized void cancel() { if (worker != null) worker.interrupt(); }
}
