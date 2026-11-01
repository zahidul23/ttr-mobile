package com.termux.x11;
import android.os.ParcelFileDescriptor;
/** JNI contract only; no external Termux installation or command-line loader is used. */
public final class CmdEntryPoint {
    static { System.loadLibrary("Xlorie"); }
    public native boolean start(String[] args);
    public native ParcelFileDescriptor getXConnection();
    public native ParcelFileDescriptor getLogcatOutput();
    public native void reportFatalError(String message);
    private static native boolean connected();
    private void sendBroadcast() { }
    private void sendBroadcastDelayed() { }
    private void setDocumentTag(String tag) { }
}
