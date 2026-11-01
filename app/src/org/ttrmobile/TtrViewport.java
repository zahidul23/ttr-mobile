package org.ttrmobile;
/**
 * The X screen keeps one size for the whole session (Wine reads it once at startup), wide enough
 * for landscape. Only the game window changes size; the phone shows the part of the screen it covers.
 */
public final class TtrViewport {
    public static volatile int renderWidth=720, renderHeight=720, rootWidth=0;
    /** Portrait shows the game at 5:4 across the full width; landscape uses the full height. */
    public static int gameHeight(int width,int height) { return TtrControlLayout.gameHeight(width,height); }
    /** X screen width for a render height: the landscape width of a display with this long/short ratio. */
    public static int rootWidth(int renderHeight,int longEdge,int shortEdge) {
        int w=(int)Math.ceil((double)renderHeight*longEdge/Math.max(1,shortEdge));
        // Room for the widest landscape view after screen-edge insets (about 2.6:1), whatever the phone.
        return Math.max(renderHeight*5/4,(Math.max(w,(int)Math.ceil(renderHeight*2.6))+7)/8*8);
    }
}
