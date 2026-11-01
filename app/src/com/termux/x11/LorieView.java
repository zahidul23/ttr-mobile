package com.termux.x11;

import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import java.nio.charset.StandardCharsets;

/** Focused surface and input adapter for the pinned upstream JNI ABI. Clipboard is disabled. */
public final class LorieView extends SurfaceView implements SurfaceHolder.Callback {
    public final MainActivity activity;
    private long ptr;
    private int width=720,height=720;
    public LorieView(MainActivity a) {
        super(a); activity=a; ptr=nativeInit(); getHolder().addCallback(this);
        setFiltering(ptr,0x2601 /* GL_LINEAR: the renderer passes this to glTexParameteri; 1 left the cursor texture unusable (a black square) */); setClipboardSyncEnabled(ptr,false,false);
        setOnTouchListener((v,e)->{
            if(cursorMode)return trackpad(e);
            float x=Math.max(0,Math.min(width-1,e.getX()*width/Math.max(1,getWidth())));
            float y=Math.max(0,Math.min(height-1,e.getY()*height/Math.max(1,getHeight())));
            sendMouseEvent(ptr,x,y,0,false,false);
            if(e.getActionMasked()==0)sendMouseEvent(ptr,x,y,1,true,false);
            if(e.getActionMasked()==1 || e.getActionMasked()==3)sendMouseEvent(ptr,x,y,1,false,false);
            return true;
        });
    }
    private boolean cursorMode,dragging,moved;
    private float lastX,lastY,startX,startY;
    private long downAt,lastTapAt;
    private int fingers;
    private final android.os.Handler ui=new android.os.Handler(android.os.Looper.getMainLooper());
    /** Cursor mode: drag moves the pointer, tap clicks, double-tap-and-hold drags, two-finger tap right-clicks. */
    /** Game window size in X pixels: the cursor stays inside it (the X screen is wider in portrait). */
    public int cursorAreaWidth=720,cursorAreaHeight=720;
    private float cursorX=-1,cursorY=-1;
    /** Told where the pointer is (X pixels) so the app can draw its own cursor; the X one is hidden. */
    public interface CursorListener{void moved(float x,float y);}
    public CursorListener cursorListener;
    public void setCursorMode(boolean on){if(dragging)sendMouseEvent(ptr,0,0,1,false,false);cursorMode=on;dragging=false;if(on)moveCursor(0,0);}
    /** Moves the pointer by (dx, dy) X pixels, starting from the centre of the game the first time. */
    private void moveCursor(float dx,float dy){
        if(cursorX<0){cursorX=cursorAreaWidth/2f;cursorY=cursorAreaHeight/2f;}
        cursorX=Math.max(0,Math.min(cursorAreaWidth-1,cursorX+dx));cursorY=Math.max(0,Math.min(cursorAreaHeight-1,cursorY+dy));
        sendMouseEvent(ptr,cursorX,cursorY,0,false,false);
        if(cursorListener!=null)cursorListener.moved(cursorX,cursorY);
    }
    private boolean trackpad(android.view.MotionEvent e){
        float slop=8*getResources().getDisplayMetrics().density,scale=(float)width/Math.max(1,getWidth());
        switch(e.getActionMasked()){
            case android.view.MotionEvent.ACTION_DOWN:
                lastX=startX=e.getX();lastY=startY=e.getY();downAt=e.getEventTime();moved=false;fingers=1;
                // A second tap that stays down holds the button, for dragging.
                if(downAt-lastTapAt<300){dragging=true;sendMouseEvent(ptr,0,0,1,true,false);}
                break;
            case android.view.MotionEvent.ACTION_POINTER_DOWN:fingers=Math.max(fingers,e.getPointerCount());break;
            case android.view.MotionEvent.ACTION_MOVE:
                if(e.getPointerCount()>1)break;
                if(Math.hypot(e.getX()-startX,e.getY()-startY)>slop)moved=true;
                float dx=(e.getX()-lastX)*scale,dy=(e.getY()-lastY)*scale;lastX=e.getX();lastY=e.getY();
                if(moved&&(dx!=0||dy!=0))moveCursor(dx,dy);
                break;
            case android.view.MotionEvent.ACTION_UP:
                if(dragging){sendMouseEvent(ptr,0,0,1,false,false);dragging=false;lastTapAt=0;break;}
                if(!moved&&e.getEventTime()-downAt<250){
                    int button=fingers>1?3:1;sendMouseEvent(ptr,0,0,button,true,false);
                    ui.postDelayed(()->sendMouseEvent(ptr,0,0,button,false,false),40);
                    lastTapAt=fingers>1?0:e.getEventTime();
                }else lastTapAt=0;
                break;
            case android.view.MotionEvent.ACTION_CANCEL:
                if(dragging)sendMouseEvent(ptr,0,0,1,false,false);dragging=false;lastTapAt=0;break;
        }
        return true;
    }
    public void attachFd(int fd) { connect(ptr,fd);setClipboardSyncEnabled(ptr,false,false);resize(width,height); }
    public void resize(int w,int h) {
        width=w;height=h;sendWindowChange(ptr,w,h,60,"TTR Mobile Linux");
        setViewport(ptr,0,0,getWidth(),getHeight(),w,h,0);
        moveCursor(0,0); // keep the pointer inside the (possibly smaller) game window
    }
    public void key(int code,boolean down) {sendKeyEvent(ptr,0,code,down);}
    /** Controller: moves the pointer by (dx, dy) X pixels, and presses or releases a mouse button (1 left, 3 right) where it is. */
    public void nudgeCursor(float dx,float dy){moveCursor(dx,dy);}
    public void mouseButton(int button,boolean down){sendMouseEvent(ptr,0,0,button,down,false);}
    public void text(String s) {sendTextEvent(ptr,s.getBytes(StandardCharsets.UTF_8));}
    public void destroy() {if(ptr!=0){nativeDestroy(ptr);ptr=0;}}
    public void surfaceCreated(SurfaceHolder h){surfaceChanged(ptr,h.getSurface());resize(width,height);}
    public void surfaceChanged(SurfaceHolder h,int f,int w,int n){surfaceChanged(ptr,h.getSurface());resize(width,height);}
    public void surfaceDestroyed(SurfaceHolder h){surfaceChanged(ptr,null);}
    public void resetIme() { }
    public void setClipboardText(String text) { }
    public void requestClipboard() { }
    public void onSyncReply(int serial) { }
    public void setRendererViewport(int x,int y,int w,int h,float sw,float sh,float sx,float sy) { }

    private native long nativeInit();
    private native void nativeDestroy(long ptr);
    private native void surfaceChanged(long ptr,Surface surface);
    private native void setFiltering(long ptr,int filtering);
    private native void setClipboardSyncEnabled(long ptr,boolean enabled,boolean ignored);
    private native void sendClipboardAnnounce(long ptr);
    private native void sendClipboardEvent(long ptr,byte[] text);
    private native void sendWindowChange(long ptr,int width,int height,int framerate,String name);
    private native void setViewport(long ptr,int x,int y,int width,int height,int expectedWidth,int expectedHeight,int hiddenBottom);
    private native void setRendererZoom(long ptr,int percent);
    private native void setZoomAnchor(long ptr,float sourceX,float sourceY,float fracX,float fracY);
    private native void clearZoomAnchor(long ptr);
    private native void setFollowCursorPan(long ptr,boolean enabled);
    private native long getCursorPosition(long ptr);
    private native void sendSync(long ptr,int serial);
    private static native void connect(long ptr,int fd);
    private static native boolean connected(long ptr);
    private static native void startLogcat(long ptr,int fd);
    private native void sendMouseEvent(long ptr,float x,float y,int whichButton,boolean buttonDown,boolean relative);
    private native void sendTouchEvent(long ptr,int action,int id,int x,int y);
    private native void sendStylusEvent(long ptr,float x,float y,int pressure,int tiltX,int tiltY,int orientation,int buttons,boolean eraser,boolean mouseMode);
    private native void requestStylusEnabled(long ptr,boolean enabled);
    private native void sendLockKeysState(long ptr,int state);
    private native boolean sendKeyEvent(long ptr,int scanCode,int keyCode,boolean keyDown);
    private native void sendTextEvent(long ptr,byte[] text);
    private static native boolean requestConnection(long ptr);
    public static native long getLastInputTimestamp();
    public static native void markUserActivity();
    static {System.loadLibrary("Xlorie");}
}
