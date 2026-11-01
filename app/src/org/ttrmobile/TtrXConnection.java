package org.ttrmobile;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Minimal authenticated X11 observer; no dependency on an inherited runtime or shell. */
public final class TtrXConnection implements Closeable {
    private final LocalSocket socket=new LocalSocket();
    private final InputStream in;
    private final OutputStream out;
    public final int root;
    private final int base,mask;
    private int next=1;
    public int fixture;
    private int fixtureGc,fixtureWidth,fixtureHeight;
    private int keys,buttons;
    public TtrXConnection(Context c) throws Exception {
        socket.connect(new LocalSocketAddress(new File(TtrRuntime.directory(c),"tmp/.X11-unix/X77").getAbsolutePath(),LocalSocketAddress.Namespace.FILESYSTEM));
        socket.setSoTimeout(5000);in=socket.getInputStream();out=socket.getOutputStream();
        byte[] cookie;
        try(DataInputStream auth=new DataInputStream(new FileInputStream(new File(TtrRuntime.directory(c),"Xauthority")))){
            auth.readUnsignedShort();for(int i=0;i<3;i++){int n=auth.readUnsignedShort();auth.skipBytes(n);}cookie=new byte[auth.readUnsignedShort()];auth.readFully(cookie);
        }
        byte[] name="MIT-MAGIC-COOKIE-1".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer setup=bytes(12+pad(name.length)+pad(cookie.length));
        setup.put((byte)'l').put((byte)0).putShort((short)11).putShort((short)0).putShort((short)name.length).putShort((short)cookie.length).putShort((short)0);
        setup.put(name);setup.position(12+pad(name.length));setup.put(cookie);send(setup);java.util.Arrays.fill(cookie,(byte)0);
        byte[] prefix=read(8);int size=u16(prefix,6)*4;
        if(size>65536)throw new IOException("Invalid X server response.");
        byte[] data=read(size);if(prefix[0]!=1 || size<32)throw new IOException("X11 authentication failed.");
        base=u32(data,4);mask=u32(data,8);
        int screen=32+pad(u16(data,16))+(data[21]&255)*8;
        if(screen+40>size)throw new IOException("X server has no screen.");root=u32(data,screen);
    }
    private static int pad(int n){return(n+3)&~3;}
    private static ByteBuffer bytes(int n){return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);}
    private static int u16(byte[] b,int at){return (b[at]&255)|((b[at+1]&255)<<8);}
    private static int u32(byte[] b,int at){return ByteBuffer.wrap(b,at,4).order(ByteOrder.LITTLE_ENDIAN).getInt();}
    private byte[] read(int size)throws IOException{byte[] b=new byte[size];int p=0;while(p<size){int n=in.read(b,p,size-p);if(n<0)throw new EOFException();p+=n;}return b;}
    private void send(ByteBuffer b)throws IOException{out.write(b.array());out.flush();}
    private byte[] reply()throws IOException{
        for(int i=0;i<2048;i++){byte[] head=read(32);if(head[0]==0)throw new IOException("X11 request failed ("+(head[1]&255)+").");
            int type=head[0]&127;if(type==2||type==3)keys++;if(type==4||type==5)buttons++;
            if(head[0]==1){int n=u32(head,4)*4;if(n<0||n>1048576)throw new IOException("Invalid X11 reply.");byte[] all=new byte[32+n];System.arraycopy(head,0,all,0,32);System.arraycopy(read(n),0,all,32,n);return all;}}
        throw new IOException("X11 event limit exceeded.");
    }
    /** Viewable top-level windows from the last visibleWindow() call. */
    public final java.util.Set<Integer> topLevel=new java.util.HashSet<>();
    /** The game: the largest viewable top-level window (Wine also maps small helper windows). */
    public synchronized int visibleWindow()throws IOException{
        ByteBuffer b=bytes(8);b.put((byte)15).put((byte)0).putShort((short)2).putInt(root);send(b);byte[] tree=reply();int count=u16(tree,16);
        int best=0;long bestArea=0;topLevel.clear();
        for(int i=0;i<count && 32+i*4+4<=tree.length;i++){
            int id=u32(tree,32+i*4);if(id==fixture)continue;
            b=bytes(8);b.put((byte)3).put((byte)0).putShort((short)2).putInt(id);send(b);byte[] a=reply();
            if(a[26]!=2)continue;
            topLevel.add(id);
            b=bytes(8);b.put((byte)14).put((byte)0).putShort((short)2).putInt(id);send(b);byte[] g=reply();
            long area=(long)u16(g,16)*u16(g,18);
            if(area>=bestArea){best=id;bestArea=area;} // later children are higher in the stack
        }return best;
    }
    public synchronized void resize(int window,int width,int height)throws IOException{
        if(window==fixture && fixtureWidth==width && fixtureHeight==height)return;
        ByteBuffer b=bytes(28);b.put((byte)12).put((byte)0).putShort((short)7).putInt(window).putShort((short)15).putShort((short)0).putInt(0).putInt(0).putInt(width).putInt(height);send(b);
        if(window==fixture)drawFixture(width,height);
    }
    public synchronized void createFixture(int width,int height)throws IOException{
        fixture=base|(next++&mask);
        ByteBuffer b=bytes(40);b.put((byte)1).put((byte)0).putShort((short)10).putInt(fixture).putInt(root).putShort((short)0).putShort((short)0).putShort((short)width).putShort((short)height)
            .putShort((short)0).putShort((short)1).putInt(0).putInt(2|2048).putInt(0x245d76).putInt((1<<15)|(1<<17)|3|12);send(b);
        b=bytes(8);b.put((byte)8).put((byte)0).putShort((short)2).putInt(fixture);send(b);
        b=bytes(12);b.put((byte)42).put((byte)1).putShort((short)3).putInt(fixture).putInt(0);send(b);
        fixtureGc=base|(next++&mask);b=bytes(20);b.put((byte)55).put((byte)0).putShort((short)5).putInt(fixtureGc).putInt(fixture).putInt(4).putInt(0xffd65e);send(b);
        drawFixture(width,height);
    }
    /** Current keyboard focus window (0 = None, 1 = PointerRoot). */
    public synchronized int focused()throws IOException{
        ByteBuffer b=bytes(4);b.put((byte)43).put((byte)0).putShort((short)1);send(b);return u32(reply(),8);
    }
    public synchronized void focus(int window)throws IOException{
        ByteBuffer b=bytes(12);b.put((byte)42).put((byte)1).putShort((short)3).putInt(window).putInt(0);send(b);
    }
    private int xfixes=-1;
    /** Finds the XFixes extension once (QueryVersion is required before other requests). */
    private boolean xfixes()throws IOException{
        if(xfixes>=0)return true;
        byte[] name="XFIXES".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ByteBuffer b=bytes(16);b.put((byte)98).put((byte)0).putShort((short)4).putShort((short)name.length).putShort((short)0).put(name);send(b);
        byte[] r=reply();if(r[8]==0)return false;xfixes=r[9]&255;
        b=bytes(12);b.put((byte)xfixes).put((byte)0).putShort((short)3).putInt(5).putInt(0);send(b);reply();
        return true;
    }
    private boolean cursorHidden;
    /**
     * Shows or hides the pointer (XFixes). A hide lasts while this connection stays open, so tap mode
     * hides it and cursor mode shows the game's own cursor.
     */
    public synchronized boolean setCursorVisible(boolean visible)throws IOException{
        if(visible!=cursorHidden)return true;
        if(!xfixes())return false;
        ByteBuffer b=bytes(8);b.put((byte)xfixes).put((byte)(visible?30:29)).putShort((short)2).putInt(root);send(b); // ShowCursor / HideCursor
        b=bytes(8);b.put((byte)14).put((byte)0).putShort((short)2).putInt(root);send(b);reply();                  // sync
        cursorHidden=!visible;return true;
    }
    /** Diagnostics: a summary of the current cursor image held by the X server (XFixes GetCursorImage). */
    public synchronized String cursorImage()throws IOException{
        if(!xfixes())return "no xfixes";
        ByteBuffer b=bytes(4);b.put((byte)xfixes).put((byte)4).putShort((short)1);send(b);byte[] r=reply();
        int w=u16(r,12),h=u16(r,14),hx=u16(r,16),hy=u16(r,18),serial=u32(r,20);
        int opaque=0,clear=0,black=0,partial=0;
        for(int i=0;i<w*h&&32+i*4+4<=r.length;i++){int p=u32(r,32+i*4),a=p>>>24;
            if(a==0)clear++;else if(a==255)opaque++;else partial++;
            if(a!=0&&(p&0xffffff)==0)black++;}
        return "cursor serial "+serial+" "+w+"x"+h+" hot "+hx+","+hy+": opaque "+opaque+", partial "+partial+", clear "+clear+", black "+black;
    }
    /** {x, y, width, height} of a window (or the root when id is 0). */
    public synchronized int[] geometry(int id)throws IOException{
        ByteBuffer b=bytes(8);b.put((byte)14).put((byte)0).putShort((short)2).putInt(id==0?root:id);send(b);byte[] g=reply();
        return new int[]{(short)u16(g,12),(short)u16(g,14),u16(g,16),u16(g,18)};
    }
    public synchronized int[] fixtureStats()throws IOException{
        ByteBuffer b=bytes(8);b.put((byte)14).put((byte)0).putShort((short)2).putInt(root);send(b);byte[] geometry=reply();
        return new int[]{u16(geometry,16),u16(geometry,18),keys,buttons};
    }
    public synchronized void drawFixture(int width,int height)throws IOException{
        fixtureWidth=width;fixtureHeight=height;
        ByteBuffer b=bytes(36);b.put((byte)70).put((byte)0).putShort((short)9).putInt(fixture).putInt(fixtureGc);
        for(int i=0;i<3;i++)b.putShort((short)(width*(i+1)/5)).putShort((short)(height/3)).putShort((short)(width/10)).putShort((short)(height/3));send(b);
        // A synchronous query ensures all drawing requests have been processed.
        b=bytes(8);b.put((byte)14).put((byte)0).putShort((short)2).putInt(fixture);send(b);reply();
    }
    @Override public void close()throws IOException{socket.close();}
}
