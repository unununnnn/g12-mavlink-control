package com.g12.x9core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/** Saved identity/configuration comparison, not cryptographic authentication or an ownership lease. */
public final class Profile {
    public static final List<String> BASE=List.of("MAV_GCS_SYSID","MAV_GCS_SYSID_HI","RC_OPTIONS","RC_OVERRIDE_TIME",
        "RCMAP_ROLL","RCMAP_PITCH","RCMAP_THROTTLE","RCMAP_YAW","FS_THR_ENABLE","FS_THR_VALUE","FS_GCS_ENABLE","FS_OPTIONS","FENCE_ENABLE","ARMING_SKIPCHK");
    public static final String[] ROLES={"ROLL","PITCH","THROTTLE","YAW"};
    public final Calibration calibration;
    public final int system,component,firmware;
    public final String uid,custom;
    public final Map<String,Float> parameters;
    public Profile(Calibration c,int sys,int comp,String uid,int fw,String custom,Map<String,Float> p) {
        if(c==null||sys<1||sys>255||comp<1||comp>255||uid==null||!uid.matches("[0-9a-f]{16}|[0-9a-f]{36}")||uid.matches("0+")||custom==null||!custom.matches("[0-9a-f]{16}"))throw new IllegalArgumentException("飞控身份无效");
        if((fw>>>8)!=0x040701)throw new IllegalArgumentException("当前仅核对过ArduCopter4.7.1协议，请核实实际固件");
        calibration=c;system=sys;component=comp;this.uid=uid;firmware=fw;this.custom=custom;
        parameters=Collections.unmodifiableMap(new LinkedHashMap<>(p));validate();
    }
    public static List<String> needed(Map<String,Float> p) {
        List<String> keys=new ArrayList<>(BASE);
        for(String role:ROLES) {Float value=p.get("RCMAP_"+role);if(value==null)continue;int ch=integer(value,1,18);
            for(String name:List.of("MIN","MAX","TRIM","REVERSED"))keys.add("RC"+ch+"_"+name);}
        return keys;
    }
    public static int integer(float f,int lo,int hi) {if(!Float.isFinite(f)||f!=Math.rint(f)||f<lo||f>hi)throw new IllegalArgumentException("飞控整数参数范围无效");return (int)f;}
    public float value(String key) {Float v=parameters.get(key);if(v==null||!Float.isFinite(v))throw new IllegalArgumentException("缺少飞控参数："+key);return v;}
    public int[] channels() {int[] r=new int[4];for(int i=0;i<4;i++)r[i]=integer(value("RCMAP_"+ROLES[i]),1,18);return r;}
    public int sourceSystem() {return integer(value("MAV_GCS_SYSID"),1,255);}
    private void validate() {
        for(String k:needed(parameters))value(k);
        if(sourceSystem()==system)throw new IllegalArgumentException("GCS与飞控sysid冲突；App不自动改参数");
        int high=integer(value("MAV_GCS_SYSID_HI"),0,255);if(high!=0&&high<sourceSystem())throw new IllegalArgumentException("GCS sysid范围不一致");
        if((integer(value("RC_OPTIONS"),0,16777215)&3)!=0)throw new IllegalArgumentException("RC_OPTIONS忽略主接收机或MAVLink输入，须核对现有配置");
        if(value("RC_OVERRIDE_TIME")<=0)throw new IllegalArgumentException("RC覆盖超时未配置为有限正值；停发无法保证按时退出覆盖");
        int[] channels=channels();
        for(int i=0;i<4;i++){for(int j=0;j<i;j++)if(channels[i]==channels[j])throw new IllegalArgumentException("飞控轴映射重复");
            String k="RC"+channels[i]+"_";int lo=integer(value(k+"MIN"),800,2200),hi=integer(value(k+"MAX"),800,2200),mid=integer(value(k+"TRIM"),800,2200);
            if(lo>=hi||mid<lo||mid>hi||(i!=2&&(lo==mid||mid==hi)))throw new IllegalArgumentException("飞控RC范围或中位无效");integer(value(k+"REVERSED"),0,1);}
    }
    public int[] pwm(int[] logical) {
        int[] ch=channels(),out=new int[4];for(int i=0;i<4;i++){
            String k="RC"+ch[i]+"_";float lo=value(k+"MIN"),hi=value(k+"MAX"),mid=value(k+"TRIM");boolean reversed=value(k+"REVERSED")==1;
            int n=logical[i];if(i==2){if(n<0||n>1000)throw new IllegalArgumentException("油门范围");if(reversed)n=1000-n;out[i]=Math.round(lo+(hi-lo)*n/1000);}
            else {if(Math.abs(n)>1000)throw new IllegalArgumentException("轴范围");if(i==1)n=-n;if(reversed)n=-n;out[i]=Math.round(mid+(n>=0?hi-mid:mid-lo)*n/1000);}
        }return out;
    }
    public boolean same(Profile p) {return system==p.system&&component==p.component&&firmware==p.firmware&&uid.equals(p.uid)&&custom.equals(p.custom)&&parameters.equals(p.parameters);}
    public int rateHz() {return Math.max(1,Math.min(10,calibration.baud/10/4/50));}
}
