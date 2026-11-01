package org.ttrmobile;

public final class TtrControlLayoutTest {
    public static void main(String[] args) {
        String[] labels={"Move","Jump","Throw","Gags","Tasks","Map","Camera","Keyboard","Pointer","Groups","Controls"};
        for(int[] screen:new int[][]{{360,800,100},{1080,2400,300},{2400,1080,300},{1280,800,150},{600,960,150},{320,568,100}}){
            int w=screen[0],h=screen[1];float density=screen[2]/100f;int[][] bounds=new int[labels.length][];
            for(int i=0;i<labels.length;i++){
                int[] b=bounds[i]=TtrControlLayout.bounds(labels[i],w,h,density);
                if(b[0]<0||b[1]<0||b[0]+b[2]>w||b[1]+b[3]>h)throw new AssertionError("Control outside display: "+labels[i]);
                if(h>w && b[1]<TtrControlLayout.gameHeight(w,h))throw new AssertionError("Portrait control over game: "+labels[i]);
                for(int j=0;j<i;j++){int[] a=bounds[j];if(b[0]<a[0]+a[2]&&b[0]+b[2]>a[0]&&b[1]<a[1]+a[3]&&b[1]+b[3]>a[1])throw new AssertionError("Overlapping targets: "+labels[i]+"/"+labels[j]+" at "+w+"x"+h);}
            }
            int[] move=bounds[0],jump=bounds[1],toss=bounds[2],keys=bounds[7],toggle=bounds[8],pad=TtrControlLayout.bounds("Pad",w,h,density);
            if(move[2]<=jump[2]||jump[2]<=toss[2])throw new AssertionError("Movement/jump/throw sizing");
            if(pad[0]<move[0]||pad[1]<move[1]||pad[0]+pad[2]>move[0]+move[2]||pad[1]+pad[3]>move[1]+move[3])throw new AssertionError("Joystick outside its touch area at "+w+"x"+h);
            if(jump[0]-(toss[0]+toss[2])>12*density)throw new AssertionError("Throw and Jump too far apart at "+w+"x"+h);
            if(keys[2]<=2*keys[3]||Math.abs(keys[0]+keys[2]/2-w/2)>1||keys[1]+keys[3]<h-12*density)throw new AssertionError("Keyboard not a wide bottom-centre button");
            if(toggle[2]<=2*toggle[3])throw new AssertionError("Tap/Cursor switch too narrow for both labels");
            int[] map=bounds[5],camera=bounds[6];
            if(h>w){
                if(toggle[1]>map[1])throw new AssertionError("Portrait switch not at the top of the controls");
                int pairTop=Math.min(jump[1],toss[1]),pairBottom=Math.max(jump[1]+jump[3],toss[1]+toss[3]);
                int g1=pairTop-(toggle[1]+toggle[3]),g2=map[1]-pairBottom,g3=keys[1]-(map[1]+map[3]);
                if(Math.abs(g1-g2)>2||Math.abs(g2-g3)>2)throw new AssertionError("Switch, Jump/Throw, row and Keyboard not evenly spaced: "+g1+"/"+g2+"/"+g3+" at "+w+"x"+h);
                int pairCenterY=(Math.min(jump[1],toss[1])+Math.max(jump[1]+jump[3],toss[1]+toss[3]))/2;
                if(Math.abs(pairCenterY-(pad[1]+pad[3]/2))>2)throw new AssertionError("Jump/Throw not level with the joystick");
                int pairCenterX=(toss[0]+jump[0]+jump[2])/2;
                if(Math.abs(pairCenterX-w*3/4)>12*density&&jump[0]+jump[2]<w-10*density)throw new AssertionError("Jump/Throw not centred on the right half at "+w+"x"+h);
                for(int k=3;k<=6;k++)if(Math.abs(bounds[k][1]+bounds[k][3]/2-(map[1]+map[3]/2))>1)throw new AssertionError("Portrait top row not aligned: "+labels[k]);
            }else{
                for(int k=3;k<=6;k++)if(Math.abs(bounds[k][1]+bounds[k][3]/2-(map[1]+map[3]/2))>1)throw new AssertionError("Landscape Map/Gags/Tasks/Camera not in a row");
                if(Math.abs(toggle[0]+toggle[2]/2-w/2)>1||toggle[1]>12*density)throw new AssertionError("Landscape switch not at the top centre");
                int rowCenter=(map[0]+camera[0]+camera[2])/2,gap=keys[1]-(map[1]+map[3]);
                if(Math.abs(rowCenter-w/2)>2||gap<0||gap>12*density)throw new AssertionError("Landscape row not just above Keyboard at "+w+"x"+h);
                int[] tuck=bounds[10];
                if(tuck[2]!=tuck[3]||tuck[0]<keys[0]+keys[2]||tuck[0]-(keys[0]+keys[2])>12*density||Math.abs(tuck[1]+tuck[3]/2-(keys[1]+keys[3]/2))>1)throw new AssertionError("Controls button not a small round button right of Keyboard at "+w+"x"+h);
            }
        }
        System.out.println("PASS: controls inside the display and below the game in portrait, no overlaps, fixed joystick inside its touch area, A/B pair, wide keyboard and switch, Map-Camera row just above Keyboard, Controls button right of Keyboard");
    }
}
