package org.ttrmobile;

import android.app.Activity;
import android.os.Build;
import android.graphics.Insets;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.RoundedCorner;

/**
 * Symmetric black gutters protect controls and the game from hardware/system edges. In full screen (while
 * the game is shown) the system bars are hidden until the player swipes from an edge, the game fills the
 * whole screen, and only the touch controls keep clear of the camera cutout and rounded corners.
 */
public final class TtrSafeArea {
    private static boolean fullscreen;
    private static View controls;
    /**
     * Set before install: the launcher screen draws behind transparent system bars and pads its own content
     * (TtrLoginScreen), instead of the whole window keeping black gutters.
     */
    public static boolean edgeToEdge;

    @SuppressWarnings("deprecation")
    public static void install(Activity activity,View frame) {
        int bar=edgeToEdge?android.graphics.Color.TRANSPARENT:android.graphics.Color.BLACK;
        activity.getWindow().setStatusBarColor(bar);
        activity.getWindow().setNavigationBarColor(bar);
        if(edgeToEdge&&Build.VERSION.SDK_INT>=29)activity.getWindow().setNavigationBarContrastEnforced(false);
        if(edgeToEdge&&Build.VERSION.SDK_INT>=30)activity.getWindow().setDecorFitsSystemWindows(false);
        else if(edgeToEdge)activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        WindowManager.LayoutParams attrs=activity.getWindow().getAttributes();
        if(Build.VERSION.SDK_INT>=28)attrs.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        activity.getWindow().setAttributes(attrs);
        frame.setOnApplyWindowInsetsListener((v,insets)->{
            int[] bars=edges(activity,insets,true),hardware=edges(activity,insets,false);
            boolean portrait=activity.getResources().getConfiguration().orientation!=2;
            boolean open=fullscreen||edgeToEdge;
            int x=open?0:bars[0],y=open?0:bars[1],top=y;
            // Portrait full screen: the game stays below the status bar / camera area; the rest is edge to edge.
            if(fullscreen&&portrait)top=topEdge(insets);
            if(v.getPaddingLeft()!=x || v.getPaddingTop()!=top || v.getPaddingBottom()!=y)v.setPadding(x,top,x,y);
            if(controls!=null&&controls.getLayoutParams() instanceof ViewGroup.MarginLayoutParams){
                ViewGroup.MarginLayoutParams p=(ViewGroup.MarginLayoutParams)controls.getLayoutParams();
                // Landscape only: side margins clear the cutout and corners. In portrait the controls sit below the
                // game and must match its height exactly, so they get none.
                boolean landscape=activity.getResources().getConfiguration().orientation==2;
                int mx=fullscreen&&landscape?hardware[0]:0,my=0;
                if(p.leftMargin!=mx||p.topMargin!=my){p.setMargins(mx,my,mx,my);controls.setLayoutParams(p);}
            }
            return insets;
        });
    }

    /** Full screen on or off; controls (a child of frame's content) get margins clear of cutouts and corners. */
    @SuppressWarnings("deprecation")
    public static void setFullscreen(Activity activity,View frame,View controlsView,boolean on){
        fullscreen=on;controls=controlsView;
        if(Build.VERSION.SDK_INT>=30){
            activity.getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c=activity.getWindow().getInsetsController();
            if(c!=null){
                if(on){c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);c.hide(WindowInsets.Type.systemBars());}
                else c.show(WindowInsets.Type.systemBars());
            }
        }else{
            View decor=activity.getWindow().getDecorView();
            int stable=View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            decor.setSystemUiVisibility(on?stable|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY:stable);
        }
        frame.requestApplyInsets();
    }

    /** Height of the status bar or camera cutout at the top, whether or not the bar is showing. */
    @SuppressWarnings("deprecation")
    private static int topEdge(WindowInsets insets){
        if(Build.VERSION.SDK_INT>=30)return insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()|WindowInsets.Type.displayCutout()).top;
        int top=insets.getStableInsetTop();
        if(Build.VERSION.SDK_INT>=28&&insets.getDisplayCutout()!=null)top=Math.max(top,insets.getDisplayCutout().getSafeInsetTop());
        return top;
    }
    /** Symmetric {x, y} insets: with bars, the system bars as well as cutout and corners; without, only the hardware. */
    @SuppressWarnings("deprecation")
    private static int[] edges(Activity activity,WindowInsets insets,boolean bars){
        int x=0,y=0;
        if(Build.VERSION.SDK_INT>=30){
            Insets edges=insets.getInsetsIgnoringVisibility((bars?WindowInsets.Type.systemBars():0)|WindowInsets.Type.displayCutout());
            x=Math.max(edges.left,edges.right); y=Math.max(edges.top,edges.bottom);
        }else{
            if(bars){x=Math.max(insets.getStableInsetLeft(),insets.getStableInsetRight()); y=Math.max(insets.getStableInsetTop(),insets.getStableInsetBottom());}
            if(Build.VERSION.SDK_INT>=28 && insets.getDisplayCutout()!=null){
                android.view.DisplayCutout cut=insets.getDisplayCutout();
                x=Math.max(x,Math.max(cut.getSafeInsetLeft(),cut.getSafeInsetRight())); y=Math.max(y,Math.max(cut.getSafeInsetTop(),cut.getSafeInsetBottom()));
            }
        }
        if(Build.VERSION.SDK_INT>=31){
            int radius=0;
            for(int i=0;i<4;i++){RoundedCorner corner=insets.getRoundedCorner(i);if(corner!=null)radius=Math.max(radius,corner.getRadius());}
            // Excluding one radius along the long axis clears every rounded corner.
            if(activity.getResources().getConfiguration().orientation==2)x=Math.max(x,radius);else y=Math.max(y,radius);
        }
        return new int[]{x,y};
    }
}
