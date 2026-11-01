package org.ttrmobile;

import android.content.Context;

/** Keep pixel density consistent while the client window changes shape. */
public final class TtrResolution {
    public static int get(Context c) {
        int value=c.getSharedPreferences("ttr-display",0).getInt("short-edge",720);
        return value==540 || value==1080 ? value : 720;
    }
    public static void set(Context c,int value) {
        if(value!=540 && value!=720 && value!=1080)throw new IllegalArgumentException("Resolution");
        c.getSharedPreferences("ttr-display",0).edit().putInt("short-edge",value).apply();
        TtrPlatform.resolutionChanged();
    }
}
