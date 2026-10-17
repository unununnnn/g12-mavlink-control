package com.g12.x9core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Small unsigned MAVLink 1/2 dialect. No parameter writes, arming or mode commands. */
public final class Wire {
    private Wire() { }
    public static ByteBuffer le(byte[] p) { return ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN); }
    private static int size(int id) {
        switch (id) { case 0:return 9; case 20:return 20; case 22:return 25; case 65:return 42;
            case 70:return 38; case 76:return 33; case 77:return 10; case 148:return 78; default:return 0; }
    }
    private static int min(int id) { return id==70?18:id==77?3:id==148?60:size(id); }
    private static int extra(int id) {
        switch (id) { case 0:return 50; case 20:return 214; case 22:return 220; case 65:return 118;
            case 70:return 124; case 76:return 152; case 77:return 143; case 148:return 178; default:throw new IllegalArgumentException("message"); }
    }
    private static int acc(int c,int v) { int t=(v^c)&255;t=(t^(t<<4))&255;return ((c>>>8)^(t<<8)^(t<<3)^(t>>>4))&65535; }
    private static int crc(byte[] b,int end,int id) { int c=65535;for(int i=1;i<end;i++)c=acc(c,b[i]&255);return acc(c,extra(id)); }
    public static final class Frame {
        public final int version,system,component,message,sequence;
        private final byte[] data;
        private Frame(int v,int s,int c,int m,int q,byte[] p) {version=v;system=s;component=c;message=m;sequence=q;data=p;}
        public byte[] data() { return data.clone(); }
        public ByteBuffer fields() { return le(data.clone()); }
    }
    public static byte[] encode(int version,int sys,int comp,int seq,int msg,byte[] p) {
        if((version!=1&&version!=2)||sys<1||sys>255||comp<1||comp>255||size(msg)==0||p.length!=size(msg))throw new IllegalArgumentException("frame fields");
        int n=version==1?min(msg):p.length;if(version==2)while(n>1&&p[n-1]==0)n--;
        int head=version==1?6:10;byte[] b=new byte[head+n+2];b[0]=(byte)(version==1?254:253);b[1]=(byte)n;
        int at=version==1?2:4;b[at]=(byte)seq;b[at+1]=(byte)sys;b[at+2]=(byte)comp;b[at+3]=(byte)msg;
        System.arraycopy(p,0,b,head,n);int crc=crc(b,head+n,msg);b[head+n]=(byte)crc;b[head+n+1]=(byte)(crc>>>8);return b;
    }
    public static Frame decode(byte[] b) {
        if(b.length<8)throw new IllegalArgumentException("short");int v=(b[0]&255)==254?1:(b[0]&255)==253?2:0;
        if(v==0 || (v==2 && (b.length<12 || b[2]!=0 || b[3]!=0)))throw new IllegalArgumentException("unsigned only");
        int head=v==1?6:10,n=b[1]&255,at=v==1?2:4,msg=v==1?b[5]&255:(b[7]&255)|((b[8]&255)<<8)|((b[9]&255)<<16);
        if(size(msg)==0||n<1||n>size(msg)||b.length!=head+n+2||(v==1&&n!=min(msg)))throw new IllegalArgumentException("length/message");
        if(((b[head+n]&255)|((b[head+n+1]&255)<<8))!=crc(b,head+n,msg))throw new IllegalArgumentException("CRC");
        byte[] p=new byte[size(msg)];System.arraycopy(b,head,p,0,n);
        return new Frame(v,b[at+1]&255,b[at+2]&255,msg,b[at]&255,p);
    }
    public static byte[] request(int v,int src,int comp,int seq,int target,int targetComp,int message) {
        if(message!=148&&message!=65)throw new IllegalArgumentException("read request only");
        byte[] p=new byte[33];ByteBuffer b=le(p);b.putFloat(0,message);b.putShort(28,(short)512);p[30]=(byte)target;p[31]=(byte)targetComp;
        return encode(v,src,comp,seq,76,p);
    }
    public static byte[] parameter(int v,int src,int comp,int seq,int target,int targetComp,String key) {
        byte[] name=key.getBytes(StandardCharsets.US_ASCII);if(name.length<1||name.length>16)throw new IllegalArgumentException("parameter name");
        byte[] p=new byte[20];le(p).putShort((short)-1);p[2]=(byte)target;p[3]=(byte)targetComp;System.arraycopy(name,0,p,4,name.length);
        return encode(v,src,comp,seq,20,p);
    }
    public static byte[] override(int v,int src,int comp,int seq,int target,int targetComp,int[] channels,int[] pwm) {
        if(channels.length!=4||pwm.length!=4)throw new IllegalArgumentException("four axes");
        byte[] p=new byte[38];ByteBuffer b=le(p);for(int i=0;i<8;i++)b.putShort(i*2,(short)65535);
        p[16]=(byte)target;p[17]=(byte)targetComp;
        for(int i=8;i<18;i++)b.putShort(18+(i-8)*2,(short)65535);
        for(int i=0;i<4;i++) {int ch=channels[i];if(ch<1||ch>(v==1?8:18)||pwm[i]<800||pwm[i]>2200)throw new IllegalArgumentException("RC channel/range");
            for(int j=0;j<i;j++)if(channels[j]==ch)throw new IllegalArgumentException("duplicate channel");
            b.putShort(ch<=8?(ch-1)*2:18+(ch-9)*2,(short)pwm[i]);}
        return encode(v,src,comp,seq,70,p);
    }
    /** SDK channel index maps directly to the same RC channel; no axis scaling or reordering. */
    public static byte[] channels(int v,int src,int comp,int seq,int target,int targetComp,int[] values){
        validateChannels(values);
        if(v==1&&values.length>8)throw new IllegalArgumentException("MAVLink1最多8通道；完整发送需要飞控串口使用MAVLink2");
        byte[] p=new byte[38];ByteBuffer b=le(p);
        for(int i=0;i<18;i++)b.putShort(i<8?i*2:18+(i-8)*2,(short)(i<values.length?values[i]:65535));
        p[16]=(byte)target;p[17]=(byte)targetComp;return encode(v,src,comp,seq,70,p);
    }
    public static void validateChannels(int[] values){
        if(values==null||values.length<1||values.length>18)throw new IllegalArgumentException("MAVLink单帧可传1～18通道；SDK通道数="+(values==null?0:values.length));
        for(int i=0;i<values.length;i++)if(values[i]<800||values[i]>2200)throw new IllegalArgumentException("CH"+(i+1)+"原值="+values[i]+"，不是可直接发送的PWM范围；不猜测换算");
    }
    /** Bounded full-frame consumption: unsupported or signed payloads cannot become nested frames. */
    public static final class Parser {
        private byte[] buffer=new byte[0];private long last=-1;
        public boolean signedSeen;
        public List<Frame> feed(byte[] bytes,long now) {
            if(last>=0&&(now<last||now-last>250))buffer=new byte[0];last=now;List<Frame> out=new ArrayList<>();
            for(byte value:bytes) {
                if(buffer.length==0&&(value&255)!=253&&(value&255)!=254)continue;
                int old=buffer.length;buffer=Arrays.copyOf(buffer,old+1);buffer[old]=value;
                if(buffer.length<3)continue;
                int header=(buffer[0]&255)==254?6:10;
                int total=header+(buffer[1]&255)+2+(header==10&&(buffer[2]&1)!=0?13:0);
                if(buffer.length<total)continue;
                if(header==10&&(buffer[2]&1)!=0)signedSeen=true;
                try {out.add(decode(buffer));}catch(IllegalArgumentException ignored) { }
                buffer=new byte[0];
            }
            return out;
        }
        public void clear() {buffer=new byte[0];last=-1;signedSeen=false;}
    }
}
