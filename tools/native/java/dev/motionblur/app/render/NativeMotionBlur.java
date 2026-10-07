package dev.motionblur.app.render;
/** Standalone ART JNI contract test; not packaged into the Android app. */
public final class NativeMotionBlur {
    private native long create(int width,int height,int quality,float maxStrength);
    private native byte[] process(long handle,byte[] previous,byte[] current,byte[] next,double elapsedSeconds);
    private native void destroy(long handle);
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable op){try{op.run();throw new AssertionError("Expected IllegalArgumentException");}catch(IllegalArgumentException expected){}}
    public static void main(String[] ignored){
        System.load("/data/local/tmp/libopenblur_mvtools.so");
        NativeMotionBlur n=new NativeMotionBlur();final int w=128,h=96;
        byte[] p=new byte[w*h*3/2],c=new byte[p.length],f=new byte[p.length];
        java.util.Arrays.fill(p,(byte)128);java.util.Arrays.fill(c,(byte)128);java.util.Arrays.fill(f,(byte)128);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            p[y*w+x]=(byte)(((x/8+y/8)%2)*120+30);
            c[y*w+x]=(byte)((((x+4)/8+y/8)%2)*120+30);
            f[y*w+x]=(byte)((((x+8)/8+y/8)%2)*120+30);
        }
        rejects(()->n.create(127,h,1,100));rejects(()->n.create(w,h,3,100));rejects(()->n.create(w,h,1,Float.NaN));
        for(int q=0;q<3;q++){
            long id=n.create(w,h,q,100);require(id!=0,"create");
            byte[] out=n.process(id,p,c,f,1.0/30);require(out.length==c.length,"length");require(!java.util.Arrays.equals(out,c),"processed not source");
            rejects(()->n.process(id,new byte[1],c,f,1.0/30));rejects(()->n.process(id,p,c,f,Double.NaN));rejects(()->n.process(id,null,c,f,1.0/30));
            n.destroy(id);n.destroy(id);rejects(()->n.process(id,p,c,f,1.0/30));
            System.out.println("ART JNI q="+q+" processing, validation, stale-handle and double-destroy PASS");
        }
        System.out.println("ART JNI ALL PASS");
    }
}
