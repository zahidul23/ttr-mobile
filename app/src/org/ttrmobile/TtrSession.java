package org.ttrmobile;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Single-use session in app-private storage. No password or username enters Wine. */
public final class TtrSession {
    private TtrSession() { }
    public static void write(Context c, TtrLoginFlow.Session session, File game) throws Exception {
        TtrPaths.server(session.server); TtrPaths.sessionValue(session.cookie);
        String path = TtrPlatform.gamePath(game);
        if (path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) throw new IOException("Invalid game storage path.");
        AtomicFile request = new AtomicFile(new File(TtrRuntime.directory(c), "session.bin"));
        byte[] data = ("TTR4\n" + (System.currentTimeMillis() / 1000 + 300) + "\n" + session.server + "\n" + session.cookie + "\n" + path + "\n").getBytes(StandardCharsets.UTF_8);
        FileOutputStream out = null;
        try { out = request.startWrite(); out.write(data); request.finishWrite(out); }
        catch (Exception e) { if (out != null) request.failWrite(out); throw e; }
        finally { Arrays.fill(data, (byte)0); }
        TtrDiagnostics.log(c, "Play: launch request handed to Windows.");
    }
    public static void clear(Context c) {
        try {
            File file = new File(TtrRuntime.directory(c), "session.bin");
            if (file.exists()) { // Record who withdrew an unused request (helps explain a launch that never starts).
                StackTraceElement caller = new Throwable().getStackTrace()[1];
                TtrDiagnostics.log(c, "Play: unused launch request withdrawn by " + caller.getClassName().replace("org.ttrmobile.", "") + "." + caller.getMethodName() + ":" + caller.getLineNumber());
            }
            new AtomicFile(file).delete();
        } catch (Exception ignored) { }
    }
}
