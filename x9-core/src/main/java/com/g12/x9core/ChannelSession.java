package com.g12.x9core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/** Connect-to-send raw channel bridge: no calibration, UID, firmware pin or saved profile. */
public final class ChannelSession {
    public interface Clock { long now(); }
    public interface Done { void returned(boolean ok); }
    public interface Link { boolean connected(); boolean offer(byte[] bytes,long deadline,Done done); void close(); }
    public enum State { DISCONNECTED, CONNECTING, PREPARING, TRANSMITTING, FAULT }
    public static final long INPUT_MS=250,WRITE_MS=200,TELEMETRY_MS=3000;
    private static final String[] KEYS={"MAV_GCS_SYSID","RC_OPTIONS","RC_OVERRIDE_TIME"};
    private static ChannelSession owner;
    private final Clock clock;
    private final Wire.Parser parser=new Wire.Parser();
    private final Map<String,Float> params=new LinkedHashMap<>();
    private final ArrayDeque<int[]> expected=new ArrayDeque<>();
    private Link link;
    private State state=State.DISCONNECTED;
    private String status="未连接",pending;
    private long epoch,writeId,started,lastTick,hbAt=-1,rcAt=-1,rcBoot=-1,inputAt=-1,inputId=-1,used=-1;
    private long sentAt,lastControl=-1,lastRcRequest=-1,paramAt=-1,lastParamPoll=-1,transmitAt,lastMatch=-1;
    private int target,component,version,source,seq,retries,pollIndex,offered,returned,matched,rcCount;
    private int[] raw,rc;
    private boolean writing,everLinked,armed,ready;
    public ChannelSession(Clock clock){this.clock=clock;}
    public synchronized void connect(Link transport,int sequence){
        if(active())throw new IllegalStateException("请先断开");if(transport==null)throw new IllegalArgumentException("管道缺失");
        synchronized(ChannelSession.class){if(owner!=null&&owner!=this)throw new IllegalStateException("本进程已有通道发送连接");owner=this;}
        epoch++;writeId++;link=transport;state=State.CONNECTING;status="连接中：等待飞控心跳";
        started=lastTick=clock.now();hbAt=rcAt=rcBoot=inputAt=inputId=used=lastControl=lastRcRequest=paramAt=lastParamPoll=lastMatch=-1;
        target=component=source=retries=pollIndex=offered=returned=matched=rcCount=0;version=2;seq=sequence&255;
        writing=everLinked=armed=ready=false;raw=rc=null;pending=null;params.clear();expected.clear();parser.clear();
    }
    public synchronized void input(int[] data,long received,long id){
        if(!active())return;long now=clock.now();
        if(received<0||received>now||id<1||id<=inputId||(inputAt>=0&&received<inputAt)){fault("输入序号/时钟无效");return;}
        try{Wire.validateChannels(data);if(raw!=null&&raw.length!=data.length)throw new IllegalArgumentException("SDK通道数变化，请重新连接");
            raw=data.clone();inputAt=received;inputId=id;
        }catch(IllegalArgumentException bad){fault(bad.getMessage());}
    }
    public synchronized void receiveFrom(Link origin,byte[] bytes){if(origin==link&&active())receive(bytes);}
    public synchronized void faultFrom(Link origin,String reason){if(origin==link&&active())fault(reason);}
    public synchronized void receive(byte[] bytes){
        if(!active()||bytes==null||bytes.length==0)return;if(bytes.length>8192){fault("接收数据异常");return;}
        long now=clock.now();
        for(Wire.Frame frame:parser.feed(bytes,now)){
            if(!active())break;ByteBuffer p=frame.fields();
            if(ready&&frame.system==source&&frame.component==191){fault("检测到相同发送者ID，已停止");break;}
            if(frame.message==0){
                int type=p.get(4)&255;if((p.get(5)&255)!=3||!(type==2||type==3||type==4||type==13||type==14||type==15||type==29||type==35))continue;
                if(frame.system<1||frame.component<1){fault("飞控ID无效");break;}
                if(target!=0&&(target!=frame.system||component!=frame.component)){fault("链路出现另一飞控，请保留单一目标后重连");break;}
                if(target==0){target=frame.system;component=frame.component;version=frame.version;state=State.PREPARING;status="已识别飞控，自动读取接收配置";}
                hbAt=now;armed=(p.get(6)&128)!=0;continue;
            }
            if(target==0||frame.system!=target||frame.component!=component)continue;
            try{
                if(frame.message==22){
                    byte[] d=frame.data();int end=8;while(end<24&&d[end]!=0)end++;String key=new String(d,8,end-8,StandardCharsets.US_ASCII);
                    boolean wanted=false;for(String k:KEYS)wanted|=k.equals(key);if(!wanted)continue;
                    float value=p.getFloat(0);if(!Float.isFinite(value))throw new IllegalArgumentException("飞控参数无效："+key);
                    if(ready&&Float.compare(params.get(key),value)!=0)throw new IllegalArgumentException("飞控接收配置变化，请重连："+key);
                    params.put(key,value);if(key.equals(pending)){pending=null;retries=0;}
                    if(!ready&&params.size()==KEYS.length){
                        source=integer(params.get(KEYS[0]),1,255);if(source==target)throw new IllegalArgumentException("飞控/GCS源ID冲突");
                        if((integer(params.get(KEYS[1]),0,16777215)&3)!=0)throw new IllegalArgumentException("RC_OPTIONS配置忽略接收机或MAVLink输入，App不自动改参数");
                        if(params.get(KEYS[2])<=0)throw new IllegalArgumentException("RC_OVERRIDE_TIME不是有限正值，停止后不能按时退出覆盖");
                        ready=true;status="准备发送：等待近期RC遥测";
                    }
                }else if(frame.message==65){
                    long boot=Integer.toUnsignedLong(p.getInt(0));
                    if(rcBoot>=0&&boot<rcBoot&&rcBoot-boot<0x80000000L)throw new IllegalArgumentException("飞控重启/迟到遥测，请重连");
                    if(boot==rcBoot)continue;rcBoot=boot;rcAt=now;rcCount=p.get(40)&255;rc=new int[18];for(int i=0;i<18;i++)rc[i]=p.getShort(4+i*2)&65535;
                    if(state==State.TRANSMITTING)for(int[] values:expected)if(matches(values)){lastMatch=now;matched++;break;}
                }
            }catch(IllegalArgumentException bad){fault(bad.getMessage());}
        }
        if(parser.signedSeen&&active())fault("链路需要MAVLink签名；本版未配置签名，不绕过认证");
    }
    private static int integer(float value,int min,int max){if(value!=Math.rint(value)||value<min||value>max)throw new IllegalArgumentException("飞控整数参数无效");return (int)value;}
    public synchronized void tick(){
        if(!active())return;long now=clock.now();
        if(now<lastTick||now-lastTick>=INPUT_MS){fault("调度暂停/时钟异常");return;}lastTick=now;
        if(!link.connected()){if(everLinked||now-started>=5000)fault("SDK数传未连接/已断开");return;}everLinked=true;
        if(target==0){if(now-started>=10000)fault("未收到飞控心跳，检查现有透传接线/波特率/MAVLink配置");return;}
        if(now-hbAt>=TELEMETRY_MS){fault("飞控心跳过期");return;}
        if(inputAt<0){if(now-started>=INPUT_MS)fault("等待首个摇杆样本超时");return;}
        if(now<inputAt||now-inputAt>=INPUT_MS){fault("摇杆输入过期，已停止");return;}
        if(writing){if(now-sentAt>=WRITE_MS)fault("SDK写调用超时");return;}
        if(version==1&&raw.length>8){fault("SDK有"+raw.length+"通道；MAVLink1仅8通道，完整发送需要飞控串口使用MAVLink2");return;}
        if(!ready){requestParameter(now);return;}
        if(rcAt<0||now-rcAt>=TELEMETRY_MS){
            if(state==State.TRANSMITTING||now-started>=15000){fault("飞控RC遥测过期");return;}
            requestRc(now);return;
        }
        if(state!=State.TRANSMITTING){state=State.TRANSMITTING;transmitAt=now;status="全通道原值发送中";}
        if(now-(lastMatch>=transmitAt?lastMatch:transmitAt)>=1500){fault("飞控RC反馈未跟随通道数据，已停止");return;}
        if(used!=inputId&&(lastControl<0||now-lastControl>=100)){
            expected.addLast(raw.clone());while(expected.size()>12)expected.removeFirst();used=inputId;lastControl=now;offered++;
            send(Wire.channels(version,source,191,seq++,target,component,raw),now,true);return;
        }
        if(lastRcRequest<0||now-lastRcRequest>=500){requestRc(now);return;}
        if(lastParamPoll<0||now-lastParamPoll>=1000){lastParamPoll=now;send(Wire.parameter(version,source,191,seq++,target,component,KEYS[pollIndex++%KEYS.length]),now,false);}
    }
    // Fixed Copter4.7.1 handles only override channels1..16. Wire still carries all SDK channels.
    // RC_CHANNELS.chancount reflects the physical receiver count, not override adoption capacity.
    private boolean matches(int[] values){for(int i=0;i<Math.min(16,values.length);i++)if(Math.abs(rc[i]-values[i])>3)return false;return true;}
    private int probeSource(){return ready?source:target==255?254:255;}
    private void requestRc(long now){if(lastRcRequest>=0&&now-lastRcRequest<500)return;lastRcRequest=now;send(Wire.request(version,probeSource(),191,seq++,target,component,65),now,false);}
    private void requestParameter(long now){
        if(pending==null)for(String k:KEYS)if(!params.containsKey(k)){pending=k;paramAt=-1;retries=0;break;}
        if(pending!=null&&(paramAt<0||now-paramAt>=800)){
            if(retries>=3){fault("飞控参数未响应："+pending+"；不会猜测源ID或改参数");return;}
            paramAt=now;retries++;status="自动读取："+pending;send(Wire.parameter(version,probeSource(),191,seq++,target,component,pending),now,false);
        }
    }
    private void send(byte[] bytes,long now,boolean control){
        final long generation=epoch,id=++writeId;writing=true;sentAt=now;boolean accepted;
        try{accepted=link.offer(bytes,control?Math.min(inputAt+INPUT_MS,now+WRITE_MS):now+WRITE_MS,ok->{synchronized(ChannelSession.this){
            if(!active()||generation!=epoch||id!=writeId||!writing)return;writing=false;
            if(!ok||clock.now()<sentAt||clock.now()-sentAt>=WRITE_MS){fault("SDK写调用失败/迟到");return;}if(control)returned++;
        }});}catch(RuntimeException bad){accepted=false;}
        if(!accepted&&generation==epoch)fault("SDK发送线程忙，禁止积压");
    }
    public synchronized void stop(String why){close(State.DISCONNECTED,why);}
    public synchronized void fault(String why){close(State.FAULT,why);}
    private void close(State next,String why){epoch++;writeId++;state=next;status=why;writing=false;raw=null;inputAt=inputId=-1;expected.clear();parser.clear();Link old=link;link=null;
        synchronized(ChannelSession.class){if(owner==this)owner=null;}if(old!=null)try{old.close();}catch(RuntimeException bad){status+="；关闭请求异常";}
    }
    public synchronized boolean active(){return state!=State.DISCONNECTED&&state!=State.FAULT;}
    public synchronized State state(){return state;}
    public synchronized String status(){return status;}
    public synchronized int[] channels(){return raw==null?null:raw.clone();}
    public synchronized int[] feedback(){return rc==null?null:rc.clone();}
    public synchronized int offered(){return offered;}
    public synchronized int returned(){return returned;}
    public synchronized int matched(){return matched;}
    public synchronized int targetSystem(){return target;}
    public synchronized int targetComponent(){return component;}
    public synchronized int sourceSystem(){return source;}
    public synchronized int wireVersion(){return version;}
    public synchronized int feedbackCount(){return rcCount;}
    public synchronized boolean armed(){return armed;}
    public synchronized long heartbeatAge(){return hbAt<0?-1:Math.max(0,clock.now()-hbAt);}
    public synchronized long rcAge(){return rcAt<0?-1:Math.max(0,clock.now()-rcAt);}
    public synchronized Float overrideTimeout(){return params.get("RC_OVERRIDE_TIME");}
}
