package org.ttrmobile;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.system.Os;
import com.termux.x11.CmdEntryPoint;
import java.io.*;

/** Bound private process isolates X server failures from the account screen. */
public final class TtrX11Service extends Service {
    private CmdEntryPoint server;
    private String failure;
    private final Handler handler=new Handler(Looper.getMainLooper(),message->{
        if(message.what==1)reply(message.replyTo,0);
        return true;
    });
    private final Messenger messenger=new Messenger(handler);
    @Override public void onCreate(){super.onCreate();try{
        File dir=TtrRuntime.directory(this);File tmp=new File(dir,"tmp");tmp.mkdirs();
        Os.setenv("TMPDIR",tmp.getAbsolutePath(),true);
        Os.setenv("XKB_CONFIG_ROOT",new File(dir,"xkb").getAbsolutePath(),true);
        // Developer switches for the Vulkan graphics experiment: swap red/blue in imported DRI3 buffers, and X server debug logging.
        if(new File(dir,"dri3-swap-rb").isFile())Os.setenv("TTR_DRI3_SWAP_RB","1",true);else Os.unsetenv("TTR_DRI3_SWAP_RB");
        if(new File(dir,"x11-debug").isFile())Os.setenv("TERMUX_X11_DEBUG","1",true);else Os.unsetenv("TERMUX_X11_DEBUG");
        server=new CmdEntryPoint();
        java.util.ArrayList<String> options=new java.util.ArrayList<>(java.util.Arrays.asList(":77","-nolisten","tcp","-nolisten","local","-noreset","-auth",new File(dir,"Xauthority").getAbsolutePath()));
        // The optional full-x86 FEX guest uses distro Xlib. Android does not
        // provide its legacy SysV shared-memory calls; advertise image copying.
        options.add("-extension");options.add("MIT-SHM"); // Android has no SysV shared memory; Wine copies images.
        if(!server.start(options.toArray(new String[0])))failure="The X server could not initialize.";
    }catch(Throwable e){failure="The X server could not load ("+e.getClass().getSimpleName()+").";}}
    private void reply(Messenger target,int attempt){try{
        if(failure==null){
            try(TtrXConnection probe=new TtrXConnection(this)) { }
            catch(Exception pending){
                if(attempt<100){handler.postDelayed(()->reply(target,attempt+1),100);return;}
                failure="The X server did not become ready.";
            }
        }
        Message result=Message.obtain(null,2);Bundle data=new Bundle();
        if(failure!=null)data.putString("error",failure);else data.putParcelable("fd",server.getXConnection());
        result.setData(data);target.send(result);
    }catch(Exception e){stopSelf();}}
    @Override public IBinder onBind(Intent intent){return messenger.getBinder();}
    @Override public void onDestroy(){super.onDestroy();android.os.Process.killProcess(android.os.Process.myPid());}
}
