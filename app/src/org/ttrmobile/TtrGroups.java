package org.ttrmobile;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import java.util.List;

/**
 * ToonHQ's group finder in a Chrome Custom Tab. The tab uses the phone's own browser profile, so a ToonHQ login
 * made in Chrome carries over (an in-app WebView has its own, empty cookie store). In portrait, Chrome 107+ shows
 * it as a bottom sheet of the given height over the touch controls, with the game still visible above. In
 * landscape, Chrome 120+ shows it as a slim side sheet at the left or right, beside the game, and turning the
 * screen switches between the two without reloading.
 * Plain intent extras, so no AndroidX library is needed; launched with startActivityForResult, which Chrome
 * accepts in place of a Custom Tabs session.
 */
final class TtrGroups {
    private TtrGroups() { }
    static final String URL = "https://toonhq.org/groups/";
    static final int REQUEST = 41;
    /** Action of the intent the side sheet's swap button sends back to TtrActivity. */
    static final String SWAP = "org.ttrmobile.GROUPS_SWAP";
    private static final String EXTRA = "androidx.browser.customtabs.extra.";
    /** Landscape side sheet on the right (the default) or the left. */
    static boolean right(Activity a) { return a.getSharedPreferences("ttr-groups", 0).getBoolean("right", true); }
    static void setRight(Activity a, boolean right) { a.getSharedPreferences("ttr-groups", 0).edit().putBoolean("right", right).apply(); }
    private static final String SERVICE = "android.support.customtabs.action.CustomTabsService";

    /** Browser that supports Custom Tabs: Chrome first (the user's logins), then the default browser, then any. */
    static String browser(Activity a) {
        PackageManager pm = a.getPackageManager();
        List<ResolveInfo> services = pm.queryIntentServices(new Intent(SERVICE), 0);
        if (services.isEmpty()) return null;
        String fallback = null, preferred = null;
        ResolveInfo def = pm.resolveActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(URL)), PackageManager.MATCH_DEFAULT_ONLY);
        String defaultPkg = def != null && def.activityInfo != null ? def.activityInfo.packageName : null;
        for (ResolveInfo s : services) {
            String pkg = s.serviceInfo.packageName;
            if (pkg.equals("com.android.chrome")) return pkg;
            if (pkg.equals(defaultPkg)) preferred = pkg;
            if (fallback == null) fallback = pkg;
        }
        return preferred != null ? preferred : fallback;
    }

    /**
     * Opens the groups page in one tab that Chrome shapes to the screen: a bottom sheet heightPx tall over the controls
     * while the window is narrower than breakpointDp (portrait), a side sheet widthPx wide beside the game when wider
     * (landscape). Chrome switches between the two itself when the screen turns, keeping the page. The side is fixed
     * when it opens; the toolbar button sends swap, and TtrActivity reopens it on the other side.
     */
    static void open(Activity a, String browser, int heightPx, int widthPx, int breakpointDp, PendingIntent swap) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(URL));
        if (browser != null) {
            common(i, browser);
            i.putExtra(EXTRA + "INITIAL_ACTIVITY_HEIGHT_PX", heightPx);
            i.putExtra(EXTRA + "INITIAL_ACTIVITY_WIDTH_PX", widthPx);
            i.putExtra(EXTRA + "ACTIVITY_SIDE_SHEET_BREAKPOINT_DP", breakpointDp);
            i.putExtra(EXTRA + "ACTIVITY_SIDE_SHEET_POSITION", right(a) ? 2 : 1); // 1 start (left), 2 end (right)
            i.putExtra(EXTRA + "ACTIVITY_SIDE_SHEET_DECORATION_TYPE", 2); // shadow over the game
            i.putExtra(EXTRA + "ACTIVITY_SIDE_SHEET_ROUNDED_CORNERS_POSITION", 2); // rounded top corners
            i.putExtra(EXTRA + "ACTIVITY_SIDE_SHEET_ENABLE_MAXIMIZATION", false);
            i.putExtra(EXTRA + "ACTIVITY_HEIGHT_RESIZE_BEHAVIOR", 0); // the bottom sheet can be dragged taller or down to close
            i.putExtra(EXTRA + "DISABLE_BACKGROUND_INTERACTION", false); // the game stays playable
            i.putExtra(EXTRA + "TOOLBAR_CORNER_RADIUS_DP", 16);
            Bundle button = new Bundle();
            button.putParcelable("android.support.customtabs.customaction.ICON", swapIcon(a));
            button.putString("android.support.customtabs.customaction.DESCRIPTION", right(a) ? "Move to the left" : "Move to the right");
            button.putParcelable("android.support.customtabs.customaction.PENDING_INTENT", swap);
            i.putExtra("android.support.customtabs.extra.ACTION_BUTTON_BUNDLE", button);
            i.putExtra("android.support.customtabs.extra.TINT_ACTION_BUTTON", false);
        }
        a.startActivityForResult(i, REQUEST);
    }

    /** Extras shared by both sheets: Custom Tab session, colours, title. */
    private static void common(Intent i, String browser) {
        i.setPackage(browser);
        Bundle session = new Bundle(); session.putBinder("android.support.customtabs.extra.SESSION", null); i.putExtras(session);
        i.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", 0xff102c3a); // the controls' background
        i.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1);
    }

    /** Swap button: two arrows, one pointing right over one pointing left. */
    private static Bitmap swapIcon(Activity a) {
        float d = a.getResources().getDisplayMetrics().density; int s = Math.round(24 * d);
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(b);
        Paint p = stroke(d); p.setStrokeWidth(2f * d); Path v = new Path();
        v.moveTo(4 * d, 8 * d); v.lineTo(20 * d, 8 * d); v.moveTo(16 * d, 4 * d); v.lineTo(20 * d, 8 * d); v.lineTo(16 * d, 12 * d);
        v.moveTo(20 * d, 16 * d); v.lineTo(4 * d, 16 * d); v.moveTo(8 * d, 12 * d); v.lineTo(4 * d, 16 * d); v.lineTo(8 * d, 20 * d);
        c.drawPath(v, p);
        return b;
    }

    private static Paint stroke(float d) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.WHITE); p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.2f * d); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

}
