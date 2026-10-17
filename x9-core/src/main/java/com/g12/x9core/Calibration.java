package com.g12.x9core;

/** Real captured G12 ranges, ordered roll/pitch/throttle/yaw, never fixture defaults. */
public final class Calibration {
    public static final class Axis {
        public final int channel,min,center,max; public final boolean reverse;
        public Axis(int ch,int lo,int mid,int hi,boolean rev) {
            if(ch<0||ch>=64||lo>=mid||mid>=hi||lo< -100000||hi>100000)throw new IllegalArgumentException("轴量程/通道无效");
            channel=ch;min=lo;center=mid;max=hi;reverse=rev;
        }
        public int value(int[] raw,boolean throttle) {
            int tolerance=Math.max(2,(max-min)/100);
            if(channel>=raw.length||raw[channel]<min-tolerance||raw[channel]>max+tolerance)throw new IllegalArgumentException("摇杆超出已校准范围，请重新设置");
            double x=Math.max(min,Math.min(max,raw[channel]));double n=throttle?(x-min)*1000/(max-min):(x>=center?(x-center)*1000/(max-center):(x-center)*1000/(center-min));
            if(reverse)n=throttle?1000-n:-n;return (int)Math.round(n);
        }
    }
    public final int count,switchChannel,switchOff,switchOn,baud;
    private final Axis[] axes;
    public Calibration(int count,Axis[] axes,int ch,int off,int on,int baud) {
        if(count<5||count>64||axes.length!=4||ch<0||ch>=count||Math.abs((long)on-off)<10||off< -100000||off>100000||on< -100000||on>100000||baud<9600||baud>2000000)
            throw new IllegalArgumentException("通道、开关或波特率无效");
        for(int i=0;i<4;i++) {if(axes[i]==null||axes[i].channel>=count||axes[i].channel==ch)throw new IllegalArgumentException("轴与开关不得共用通道");
            for(int j=0;j<i;j++)if(axes[j].channel==axes[i].channel)throw new IllegalArgumentException("四轴不得共用通道");}
        this.count=count;this.axes=axes.clone();switchChannel=ch;switchOff=off;switchOn=on;this.baud=baud;
    }
    public Axis axis(int i) {return axes[i];}
    public int[] values(int[] raw) {if(raw==null||raw.length!=count)throw new IllegalArgumentException("SDK通道数量变化，须重新设置");int[] v=new int[4];for(int i=0;i<4;i++)v[i]=axes[i].value(raw,i==2);return v;}
    public int switchPosition(int[] raw) {
        if(raw.length!=count)throw new IllegalArgumentException("通道数量变化");
        double n=(raw[switchChannel]-(double)switchOff)/(switchOn-(double)switchOff);
        if(n< -.15||n>1.15)throw new IllegalArgumentException("接管开关超出校准范围");
        return n<=.25?0:n>=.75?1:-1;
    }
    public static int changed(int[] from,int[] to) {
        if(from==null||to==null||from.length!=to.length||from.length<5||from.length>64)throw new IllegalArgumentException("请先读取稳定的真实通道");
        int best=-1;long largest=0,second=0;
        for(int i=0;i<from.length;i++){long d=Math.abs((long)to[i]-from[i]);if(d>largest){second=largest;largest=d;best=i;}else second=Math.max(second,d);}
        if(largest<10||second*2>=largest)throw new IllegalArgumentException("无法分辨单一通道：请只移动指定摇杆/开关");return best;
    }
    public static Axis capture(int[] negative,int[] positive,int[] center) {
        int ch=changed(negative,positive);if(center==null||center.length!=negative.length)throw new IllegalArgumentException("中心样本无效");
        return new Axis(ch,Math.min(negative[ch],positive[ch]),center[ch],Math.max(negative[ch],positive[ch]),positive[ch]<negative[ch]);
    }
}
