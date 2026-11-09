package org.ttrmobile;

import android.os.*;
import android.content.res.Configuration;
import android.view.*;
import android.widget.*;
import com.termux.x11.LorieView;
import java.util.*;

/** Native account screen stays visible until this private X server has a mapped client. */
public final class TtrActivity extends com.termux.x11.MainActivity implements TtrTouchControls.Output {
    private TtrLoginScreen login;
    private FrameLayout root,safe;
    private LorieView display;
    private TtrTouchControls controls;
    private TtrImeInput ime;
    /** A game controller, as the keyboard and mouse input TTR understands. */
    private final TtrGamepad gamepad=new TtrGamepad(new TtrGamepad.Output(){
        public void key(int code,boolean down){if(down?pressed.add(code):pressed.remove(code))display.key(code,down);}
        public void cursor(float dx,float dy){if(!cursorMode)useCursor();display.nudgeCursor(dx,dy);}
        public void click(boolean down){display.mouseButton(1,down);}
        public int gameHeight(){return display.cursorAreaHeight;}
    });
    private final Set<Integer> pressed=new HashSet<>();
    /** Screens alive in this process (an Android recreate briefly has two); 0 after a close. */
    static volatile int alive;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private boolean visible,resumed,cursorMode;
    private String loggedPhase;
    private Pointer pointer;
    /** The app draws the cursor: the X server's fallback pointer for this game is a black square. */
    /**
     * The app draws the cursor (the X server's fallback pointer for this game is a black square): TTR's own glove,
     * read from the game files, at the game's scale; a plain arrow until the glove is available.
     */
    private final class Pointer extends View{
        private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG|android.graphics.Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.Path arrow=new android.graphics.Path();
        private TtrGlove.Pointer glove;private float scale=1,x,y;
        Pointer(){super(TtrActivity.this);float d=getResources().getDisplayMetrics().density;
            arrow.moveTo(0,0);arrow.lineTo(0,17*d);arrow.lineTo(4.5f*d,13*d);arrow.lineTo(7.5f*d,20*d);arrow.lineTo(10.5f*d,18.7f*d);arrow.lineTo(7.6f*d,12*d);arrow.lineTo(13*d,12*d);arrow.close();
            arrow.offset(2*d,2*d);
            }
        /** Reads the glove from the game files (again if they weren't installed yet). */
        void loadGlove(){if(glove!=null)return;new Thread(()->{TtrGlove.Pointer g=TtrGlove.load(TtrActivity.this);if(g!=null)ui.post(()->{glove=g;requestLayout();place();invalidate();});},"ttr-glove").start();}
        /** Puts the click point at game pixel (x, y); scale is screen pixels per game pixel. */
        void place(float gameX,float gameY,float s){x=gameX;y=gameY;if(s!=scale){scale=s;requestLayout();}place();}
        private void place(){float d=getResources().getDisplayMetrics().density;
            float hotX=glove!=null?glove.hotX*scale:2*d,hotY=glove!=null?glove.hotY*scale:2*d;
            setTranslationX(display.getLeft()+x*scale-hotX);setTranslationY(display.getTop()+y*scale-hotY);}
        @Override protected void onMeasure(int w,int h){float d=getResources().getDisplayMetrics().density;
            if(glove!=null)setMeasuredDimension(Math.max(1,Math.round(glove.image.getWidth()*scale)),Math.max(1,Math.round(glove.image.getHeight()*scale)));
            else setMeasuredDimension((int)(18*d),(int)(24*d));}
        @Override protected void onDraw(android.graphics.Canvas c){float d=getResources().getDisplayMetrics().density;
            if(glove!=null){c.drawBitmap(glove.image,null,new android.graphics.RectF(0,0,getWidth(),getHeight()),paint);return;}
            paint.setStyle(android.graphics.Paint.Style.FILL);paint.setColor(0xffffffff);c.drawPath(arrow,paint);
            paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(1.4f*d);paint.setColor(0xff1a1a1a);c.drawPath(arrow,paint);}
    }
    private final Runnable poll=new Runnable(){public void run(){if(!resumed&&!isInPictureInPictureMode())return;
        String phase=TtrRuntime.phase(TtrActivity.this);if(!phase.equals(loggedPhase)){graphicsCheck(loggedPhase,phase);loggedPhase=phase;TtrDiagnostics.log(TtrActivity.this,"Windows phase: "+phase+(TtrRuntime.lastError!=null?" (error: "+TtrRuntime.lastError+")":""));}

        boolean next=TtrRuntime.clientVisible(TtrActivity.this);if(next!=visible){visible=next;if(next)showClient();else{closeKeyboard();getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);TtrSafeArea.setFullscreen(TtrActivity.this,safe,controls,false);login.clientClosed();login.view.setVisibility(View.VISIBLE);controls.setVisibility(View.GONE);pointer.setVisibility(View.GONE);gamepad.release();release();updatePip();}}
        ui.postDelayed(this,250);
    }};
    private long gameShownAt;
    /**
     * Automatic graphics: if the game closes within 15 s of its window opening while on Vulkan, Windows restarts with
     * compatibility graphics (GL4ES) and stays on it on this phone.
     */
    private void graphicsCheck(String before,String now){
        if("game".equals(now)){gameShownAt=SystemClock.elapsedRealtime();if(TtrRuntime.vulkanSession){ui.postDelayed(graphicsWatch,6000);ui.postDelayed(graphicsWatch,20000);}return;}
        if(!"game".equals(before)||!TtrRuntime.vulkanSession||SystemClock.elapsedRealtime()-gameShownAt>15000)return;
        graphicsFallback();
    }
    /** Also catches a game that stays open but cannot draw (Mesa reports the failure in the Wine log). */
    private final Runnable graphicsWatch=()->{if(TtrRuntime.vulkanSession&&"game".equals(loggedPhase)&&TtrGraphics.logShowsFailure(this))graphicsFallback();};
    private void graphicsFallback(){
        if(!TtrGraphics.recordStartupFailure(this))return;
        ui.removeCallbacks(graphicsWatch);
        TtrLauncher.notice("The faster graphics didn't work on this phone, so TTR Mobile switched to compatibility graphics. Tap Play to try again.");
        TtrRuntime.restartForGraphics(this);
    }
    @Override public void onCreate(Bundle state){super.onCreate(state);TtrDiagnostics.install(this);alive++;
        try{TtrHttp.userAgent="TTR-Mobile/"+getPackageManager().getPackageInfo(getPackageName(),0).versionName+" (unofficial Android launcher)";}catch(Exception ignored){}
        TtrRuntime.prepareStorage(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        safe=new FrameLayout(this);safe.setBackgroundColor(0xff000000);root=new FrameLayout(this);safe.addView(root,new FrameLayout.LayoutParams(-1,-1));TtrSafeArea.edgeToEdge=true;TtrSafeArea.install(this,safe);
        display=new LorieView(this);root.addView(display,new FrameLayout.LayoutParams(1,1));
        pointer=new Pointer();pointer.setVisibility(View.GONE);root.addView(pointer,new FrameLayout.LayoutParams(-2,-2));
        display.cursorListener=(x,y)->ui.post(()->pointer.place(x,y,(float)display.getWidth()/Math.max(1,TtrViewport.rootWidth)));
        controls=new TtrTouchControls(this,this);
        cursorMode=getSharedPreferences("ttr-input",0).getBoolean("cursor-mode",false);display.setCursorMode(cursorMode);TtrRuntime.cursorVisible=cursorMode;
        controls.addPointerToggle(this::togglePointer,cursorMode);controls.addGroupsButton(this::openGroups);controls.setVisibility(View.GONE);root.addView(controls,new FrameLayout.LayoutParams(-1,-1));
        ime=new TtrImeInput(this,new TtrImeInput.Output(){public void text(String s){display.text(s);}public void key(int code){display.key(code,true);display.key(code,false);}});
        root.addView(ime,new FrameLayout.LayoutParams(1,1));
        login=new TtrLoginScreen(this,true);root.addView(login.view,new FrameLayout.LayoutParams(-1,-1));setContentView(safe);setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot){release();closeKeyboard();layoutDisplay();login.resize();}});
        safe.requestApplyInsets();
        if(getIntent().getBooleanExtra("ttr_probe",false))TtrRuntime.begin(this,()->{});
        TtrDevServer.start(this);
    }
    public void attachDisplay(int fd){display.attachFd(fd);layoutDisplay();}
    public void refreshDisplay(){release();closeKeyboard();layoutDisplay();}
    /** In the game the phone keyboard covers the bottom of the screen; nothing moves or resizes. */
    public void showClient(){getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);TtrSafeArea.setFullscreen(this,safe,controls,true);login.view.setVisibility(View.GONE);controls.setVisibility(isInPictureInPictureMode()?View.GONE:View.VISIBLE);showPointer();layoutDisplay();}
    /** Developer capture of the game surface only; never captures account UI. */
    void captureGame(){
        java.io.File cache=getExternalCacheDir();
        if(cache==null){TtrRuntime.snapshotState="capture_failed";return;}
        java.io.File target=new java.io.File(cache,"windows-game.png");target.delete();
        if(!resumed||!visible||!hasWindowFocus()||!display.getHolder().getSurface().isValid()||!"game".equals(TtrRuntime.phase(this))||display.getWidth()<1||display.getHeight()<1){TtrRuntime.snapshotState="game_not_visible";return;}
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(display.getWidth(),display.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
        try{PixelCopy.request(display,bitmap,result->{
            if(result!=PixelCopy.SUCCESS){bitmap.recycle();TtrRuntime.snapshotState="capture_failed";return;}
            new Thread(()->{try(java.io.FileOutputStream out=new java.io.FileOutputStream(target)){
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);TtrRuntime.snapshotState="ready";
            }catch(Exception e){TtrRuntime.snapshotState="capture_failed";}finally{bitmap.recycle();}},"ttr-game-capture").start();
        },ui);}catch(IllegalArgumentException e){bitmap.recycle();TtrRuntime.snapshotState="game_not_visible";}
    }
    /**
     * The X screen keeps one size (the landscape width); rotation only changes the game window. The
     * surface is scaled so the game window's part of the screen exactly fills the game area, and the
     * rest of the X screen runs off the right edge, out of view.
     */
    private void layoutDisplay(){int w=root.getWidth(),h=root.getHeight();if(w<1||h<1)return;int gh=TtrViewport.gameHeight(w,h),rh=TtrResolution.get(this),rw=Math.max(rh,(int)Math.round((double)rh*w/gh));
        rw=(rw+7)/8*8; // Match the X server's CVT horizontal pixel granularity.
        android.util.DisplayMetrics m=new android.util.DisplayMetrics();getWindowManager().getDefaultDisplay().getRealMetrics(m);
        int fixed=TtrViewport.rootWidth(rh,Math.max(m.widthPixels,m.heightPixels),Math.min(m.widthPixels,m.heightPixels));
        // Keep the screen size Wine started with; only a resolution change (before Play) picks a new one.
        // While Windows runs the X screen never changes size (Wine would keep the old one); a wider window is capped.
        int rootW=TtrViewport.rootWidth>0&&TtrViewport.renderHeight==rh&&TtrRuntime.isSessionRunning(this)?TtrViewport.rootWidth:Math.max(fixed,rw);
        rw=Math.min(rw,rootW);
        TtrViewport.renderWidth=rw;TtrViewport.renderHeight=rh;TtrViewport.rootWidth=rootW;
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)display.getLayoutParams();p.height=gh;p.width=(int)Math.round((double)w*rootW/rw);
        display.cursorAreaWidth=rw;display.cursorAreaHeight=rh;display.setLayoutParams(p);display.resize(rootW,rh);TtrRuntime.resize();updatePip();}
    public void bind(String binding,boolean down){int code;
        switch(binding){case "KEY_UP":code=19;break;case "KEY_DOWN":code=20;break;case "KEY_LEFT":code=21;break;case "KEY_RIGHT":code=22;break;case "KEY_CTRL_L":code=113;break;case "KEY_DEL":code=112;break;case "KEY_HOME":code=122;break;case "KEY_END":code=123;break;case "KEY_ALT_L":code=57;break;case "KEY_TAB":code=61;break;default:return;}
        if(down?pressed.add(code):pressed.remove(code))display.key(code,down);
    }
    /** Touches the controls pass on (taps around the joystick, a second finger while moving) go to the game view. */
    @Override public boolean game(MotionEvent e){
        e.offsetLocation(controls.getLeft()-display.getLeft(),controls.getTop()-display.getTop());
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN&&(e.getX()<0||e.getY()<0||e.getX()>=display.getWidth()||e.getY()>=display.getHeight()))return false;
        display.dispatchTouchEvent(e);return true;
    }
    /** The Keyboard button opens or closes the phone's keyboard. */
    public void keyboard(){if(ime.isOpen())ime.hide();else ime.show();}
    /** Tap mode: touches click where they land. Cursor mode: the screen is a trackpad for the game's own cursor. */
    private void togglePointer(){release();cursorMode=!cursorMode;getSharedPreferences("ttr-input",0).edit().putBoolean("cursor-mode",cursorMode).apply();
        display.setCursorMode(cursorMode);controls.setCursorMode(cursorMode);TtrRuntime.cursorVisible=cursorMode;showPointer();}
    /** The right stick moved in Tap mode: switch to Cursor mode (without letting go of held keys) so the cursor shows. */
    private void useCursor(){cursorMode=true;getSharedPreferences("ttr-input",0).edit().putBoolean("cursor-mode",true).apply();
        display.setCursorMode(true);controls.setCursorMode(true);TtrRuntime.cursorVisible=true;showPointer();}
    /** Controller buttons and sticks go to the game as keys and mouse while it is shown. */
    @Override public boolean dispatchKeyEvent(KeyEvent e){if(visible&&gamepad.key(e))return true;return super.dispatchKeyEvent(e);}
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e){if(visible&&gamepad.motion(e))return true;return super.dispatchGenericMotionEvent(e);}
    /** Cursor mode shows TTR's own cursor (Windows sets it; the X server draws it). The app-drawn glove is not needed. */
    private void showPointer(){pointer.setVisibility(View.GONE);}
    private boolean groupsOpen;
    /** The side sheet's swap button was pressed: reopen it on the other side once the old one has closed. */
    private boolean groupsReopen;
    /**
     * ToonHQ groups in the phone's browser (Chrome, with its ToonHQ login). Portrait: a bottom sheet covering exactly
     * the controls, below the game. Landscape: a slim side sheet at the left or right of the game, which keeps
     * running beside it; a toolbar button moves it to the other side. Chrome reshapes the same page when the screen turns.
     */
    private void openGroups(){
        if(groupsOpen)return;
        release();closeKeyboard();
        groupsOpen=true;updatePip();watchHome(true);
        ui.post(launchGroups);
    }
    /**
     * Home (or the app switcher) while the Groups sheet is open closes the sheet. Android only gives the floating
     * window to the top screen, which is then Chrome's sheet, so this first Home just leaves the app; the game comes
     * back full screen, and the next Home shows the floating window.
     */
    private android.content.BroadcastReceiver homeKey;
    private void watchHome(boolean on){
        if(on==(homeKey!=null))return;
        if(!on){try{unregisterReceiver(homeKey);}catch(Exception ignored){}homeKey=null;return;}
        homeKey=new android.content.BroadcastReceiver(){@Override public void onReceive(android.content.Context c,android.content.Intent i){
            String reason=i.getStringExtra("reason");
            if(groupsOpen&&("homekey".equals(reason)||"recentapps".equals(reason))){
                TtrDiagnostics.log(TtrActivity.this,"Closing ToonHQ groups: the app is being left ("+reason+").");
                groupsReopen=false;finishActivity(TtrGroups.REQUEST);groupsClosed();}
        }};
        android.content.IntentFilter f=new android.content.IntentFilter(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(homeKey,f,RECEIVER_EXPORTED);else registerReceiver(homeKey,f);
    }
    private final Runnable launchGroups=()->{
        if(!groupsOpen)return;
        String browser=TtrGroups.browser(this);
        android.util.DisplayMetrics m=new android.util.DisplayMetrics();getWindowManager().getDefaultDisplay().getRealMetrics(m);
        TtrDiagnostics.log(this,"Opening ToonHQ groups in "+(browser!=null?browser:"the default browser (no Custom Tabs support)")+(TtrGroups.right(this)?" (side sheet on the right)":" (side sheet on the left)"));
        try{
            // Both shapes are sized up front, for the portrait and landscape screens.
            int shortSide=Math.min(m.widthPixels,m.heightPixels),longSide=Math.max(m.widthPixels,m.heightPixels);float d=m.density;
            // Portrait: from the bottom of the screen up to the bottom of the game (the game sits below the status bar).
            int top=0;
            if(Build.VERSION.SDK_INT>=30&&root.getRootWindowInsets()!=null)
                top=root.getRootWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()|WindowInsets.Type.displayCutout()).top;
            if(root.getHeight()>root.getWidth()){int[] at=new int[2];root.getLocationOnScreen(at);top=at[1];}
            int height=Math.max(1,longSide-top-TtrViewport.gameHeight(shortSide,longSide-top));
            // Landscape: portrait-shaped, about a third of the screen, 300-380 dp.
            int width=Math.round(Math.max(300*d,Math.min(380*d,longSide*.34f)));
            // Side sheet only when the window is wider than this phone's portrait width.
            int breakpoint=Math.round((shortSide+longSide)/2f/d);
            android.content.Intent swap=new android.content.Intent(this,TtrActivity.class).setAction(TtrGroups.SWAP);
            TtrGroups.open(this,browser,height,width,breakpoint,android.app.PendingIntent.getActivity(this,TtrGroups.REQUEST,swap,android.app.PendingIntent.FLAG_IMMUTABLE|android.app.PendingIntent.FLAG_UPDATE_CURRENT));
        }catch(android.content.ActivityNotFoundException e){groupsClosed();Toast.makeText(this,"No web browser found to open ToonHQ.",Toast.LENGTH_LONG).show();}
    };
    private void groupsClosed(){
        ui.removeCallbacks(launchGroups);groupsOpen=false;watchHome(false);updatePip();
        if(groupsReopen){groupsReopen=false;ui.postDelayed(this::openGroups,150);}
    }
    @Override protected void onActivityResult(int request,int result,android.content.Intent data){
        if(request==TtrGroups.REQUEST)groupsClosed();else super.onActivityResult(request,result,data);
    }
    /**
     * The swap button brings this screen back to the front, which closes the sheet; it then reopens on the other side.
     * (In portrait the button only changes the side used in landscape.)
     */
    @Override protected void onNewIntent(android.content.Intent intent){
        super.onNewIntent(intent);
        if(!TtrGroups.SWAP.equals(intent.getAction()))return;
        TtrGroups.setRight(this,!TtrGroups.right(this));
        if(root.getHeight()>root.getWidth())Toast.makeText(this,"Groups will open on the "+(TtrGroups.right(this)?"right":"left")+" in landscape.",Toast.LENGTH_SHORT).show();
        if(!groupsOpen)ui.postDelayed(this::openGroups,150); // the sheet already reported closing
        else groupsReopen=true;
    }
    /**
     * Home (or switching apps) while the game shows: the game keeps running, with sound, in a small floating window,
     * like a video. It has the game area's shape, so the game window keeps its size; the touch controls hide until
     * it is opened again. Closing the floating window is the same as leaving the app before: the toon stays
     * connected in the background for up to 5 minutes.
     */
    private android.app.PictureInPictureParams pipParams(){
        int w=root.getWidth(),h=TtrViewport.gameHeight(w,root.getHeight());
        if(w<1||h<1){w=16;h=9;}
        // Android allows shapes from 1:2.39 to 2.39:1.
        if(w*100L>h*239L)w=(int)(h*239L/100);else if(h*100L>w*239L)h=(int)(w*239L/100);
        int[] at=new int[2];root.getLocationInWindow(at);
        android.app.PictureInPictureParams.Builder b=new android.app.PictureInPictureParams.Builder()
            .setAspectRatio(new android.util.Rational(w,h)).setSourceRectHint(new android.graphics.Rect(at[0],at[1],at[0]+w,at[1]+h));
        if(Build.VERSION.SDK_INT>=31)b.setAutoEnterEnabled(pipAllowed()).setSeamlessResizeEnabled(false);
        return b.build();
    }
    private boolean pipAllowed(){return visible&&!groupsOpen&&!isFinishing();}
    /** Keeps the floating window's shape in step with the game area (Android 12+ enters it by itself on Home). */
    private void updatePip(){if(isInPictureInPictureMode()||root==null)return;try{setPictureInPictureParams(pipParams());}catch(Exception ignored){}}
    @Override protected void onUserLeaveHint(){super.onUserLeaveHint();
        if(Build.VERSION.SDK_INT<31&&pipAllowed())try{enterPictureInPictureMode(pipParams());}catch(Exception ignored){}}
    @Override public void onPictureInPictureModeChanged(boolean in,Configuration c){super.onPictureInPictureModeChanged(in,c);
        TtrDiagnostics.log(this,in?"Game shown in the floating window.":"Floating window closed or opened full screen.");
        TtrSafeArea.pip=in;
        if(in){release();gamepad.release();closeKeyboard();controls.setVisibility(View.GONE);ui.removeCallbacks(poll);ui.post(poll);}
        else{if(visible){controls.setVisibility(View.VISIBLE);TtrSafeArea.setFullscreen(this,safe,controls,true);}ui.post(this::updatePip);}
        safe.requestApplyInsets();
    }
    private void closeKeyboard(){ime.hide();}
    private void release(){for(int code:pressed)display.key(code,false);pressed.clear();}
    /** Volume keys keep controlling the phone (game audio plays on the media stream). */
    private static boolean isVolumeKey(int code){return code==KeyEvent.KEYCODE_VOLUME_UP||code==KeyEvent.KEYCODE_VOLUME_DOWN||code==KeyEvent.KEYCODE_VOLUME_MUTE;}
    @Override public boolean onKeyDown(int code,KeyEvent e){if(visible&&code!=KeyEvent.KEYCODE_BACK&&!isVolumeKey(code)){display.key(code,true);return true;}return super.onKeyDown(code,e);}
    @Override public boolean onKeyUp(int code,KeyEvent e){if(visible&&code!=KeyEvent.KEYCODE_BACK&&!isVolumeKey(code)){display.key(code,false);return true;}return super.onKeyUp(code,e);}
    /** Back: in the game, back to the launcher (only the game closes) or close the app; on the launcher, close the app. */
    @Override public void onBackPressed(){if(ime.isOpen()){closeKeyboard();return;}if(!visible&&login.back())return;
        if(visible){new TtrDialog(this,"Leave the game?","Your toon will log out.")
            .button("Back to launcher",TtrDialog.PRIMARY,this::closeGame)
            .button("Close TTR Mobile",TtrDialog.SECONDARY,this::finishAndRemoveTask)
            .button("Keep playing",TtrDialog.QUIET,null).show();return;}
        new TtrDialog(this,"Close TTR Mobile?",null)
            .button("Close",TtrDialog.PRIMARY,this::finishAndRemoveTask)
            .button("Cancel",TtrDialog.QUIET,null).show();}
    /** Closes only the game; Windows keeps running, so Play starts it again quickly. */
    private void closeGame(){release();closeKeyboard();gamepad.release();TtrRuntime.closeGame(this);}
    @Override public void onStart(){super.onStart();TtrKeepAlive.setBackground(this,false);TtrRuntime.setAudioPaused(this,false);}
    @Override public void onStop(){TtrRuntime.setAudioPaused(this,true);TtrKeepAlive.setBackground(this,true);super.onStop();}
    @Override public void onResume(){super.onResume();resumed=true;if(visible)TtrSafeArea.setFullscreen(this,safe,controls,true);login.resume();ui.removeCallbacks(poll);ui.post(poll);TtrAppUpdate.start(this);}
    @Override public void onPause(){resumed=false;if(!isInPictureInPictureMode())ui.removeCallbacks(poll);gamepad.release();release();login.pause();super.onPause();}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);layoutDisplay();login.resize();}
    @Override public void onDestroy(){TtrDevServer.stop(this);watchHome(false);resumed=false;ui.removeCallbacksAndMessages(null);release();login.destroy();
        // Only a real close stops Windows; if Android recreates the screen (a system setting changed), the game keeps running.
        // A close also ends the app's process once nothing needs it (a download may finish first).
        alive--;
        if(isFinishing()){TtrRuntime.stop(this);TtrKeepAlive.exitWhenIdle(this);}else TtrDiagnostics.log(this,"Screen recreated by Android; Windows keeps running.");
        display.destroy();super.onDestroy();}
}
