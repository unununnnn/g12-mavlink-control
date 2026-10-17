package com.g12.x9core;
import java.util.LinkedHashMap;
import java.util.Map;

/** Versioned, strict local profile format; never serializes a transmission/authorization state. */
public final class ProfileCodec {
    private ProfileCodec() { }
    public static String encode(Profile p){StringBuilder s=new StringBuilder("schema=1\n");Calibration c=p.calibration;
        s.append("system=").append(p.system).append("\ncomponent=").append(p.component).append("\nuid=").append(p.uid).append("\nfirmware=").append(p.firmware).append("\ncustom=").append(p.custom)
        .append("\ncount=").append(c.count).append("\nbaud=").append(c.baud).append("\nswitch=").append(c.switchChannel).append(',').append(c.switchOff).append(',').append(c.switchOn).append('\n');
        for(int i=0;i<4;i++){Calibration.Axis a=c.axis(i);s.append("axis").append(i).append('=').append(a.channel).append(',').append(a.min).append(',').append(a.center).append(',').append(a.max).append(',').append(a.reverse?1:0).append('\n');}
        for(Map.Entry<String,Float> e:p.parameters.entrySet())s.append("param.").append(e.getKey()).append('=').append(e.getValue()).append('\n');return s.toString();}
    public static Profile decode(String text){
        if(text==null||text.length()>32768)throw new IllegalArgumentException("档案大小无效");Map<String,String> fields=new LinkedHashMap<>();
        for(String line:text.split("\n")){String[] pair=line.split("=",-1);if(pair.length!=2||fields.put(pair[0],pair[1])!=null)throw new IllegalArgumentException("档案字段重复或损坏");}
        if(!"1".equals(fields.remove("schema")))throw new IllegalArgumentException("不支持的档案版本");
        int sys=num(fields,"system"),comp=num(fields,"component"),fw=num(fields,"firmware"),count=num(fields,"count"),baud=num(fields,"baud");String uid=fields.remove("uid"),custom=fields.remove("custom");
        int[] sw=csv(fields.remove("switch"),3);Calibration.Axis[] axes=new Calibration.Axis[4];
        for(int i=0;i<4;i++){int[] a=csv(fields.remove("axis"+i),5);if(a[4]!=0&&a[4]!=1)throw new IllegalArgumentException("反向字段无效");axes[i]=new Calibration.Axis(a[0],a[1],a[2],a[3],a[4]==1);}
        Map<String,Float> params=new LinkedHashMap<>();for(Map.Entry<String,String> e:fields.entrySet()){if(!e.getKey().startsWith("param."))throw new IllegalArgumentException("档案包含未知字段");params.put(e.getKey().substring(6),Float.parseFloat(e.getValue()));}
        if(!params.keySet().equals(new java.util.LinkedHashSet<>(Profile.needed(params))))throw new IllegalArgumentException("参数档案不完整或多出字段");
        return new Profile(new Calibration(count,axes,sw[0],sw[1],sw[2],baud),sys,comp,uid,fw,custom,params);
    }
    private static int num(Map<String,String> m,String k){return Integer.parseInt(m.remove(k));}
    private static int[] csv(String s,int count){if(s==null)throw new IllegalArgumentException("档案字段缺失");String[] fields=s.split(",",-1);if(fields.length!=count)throw new IllegalArgumentException("档案字段数量错误");int[] out=new int[count];for(int i=0;i<count;i++)out[i]=Integer.parseInt(fields[i]);return out;}
}
