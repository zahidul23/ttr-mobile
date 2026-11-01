package org.ttrmobile;

import java.io.File;

/** Compile-time platform choices; the Linux source build supplies its own implementation. */
public final class TtrPlatform {
    public static final String PATCH = "win64", ENGINE = "TTREngine64.exe", RUNTIME = "Windows";
    public static String gamePath(File game) throws java.io.IOException {
        return "Z:" + game.getCanonicalPath().replace('/', '\\');
    }
    public static void resolutionChanged() { }
    /** True when the runtime asks the launcher screen to start it again (failed first setup). */
    public static boolean consumeRuntimeRetry() { return TtrRuntime.consumeSetupRetry(); }
    private TtrPlatform() { }
}
