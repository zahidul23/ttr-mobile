package com.termux.x11;
/** Small JNI host for the pinned Termux:X11 native renderer. */
public class MainActivity extends android.app.Activity {
    public long lastServerHeartbeat;
    public void clientConnectedStateChanged() { }
    public static native void nativeSetLaunchWindowingModeFreeform(Object options);
}
