package com.g12.x9core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One-link direct controller. UID/feedback checks are not authentication or cross-device arbitration.
 * No total-duration limit and no armed-state admission gate. All public entrypoints synchronized.
 */
public final class DirectSession {
    public interface Clock { long now(); }
    public interface Done { void returned(boolean ok); }
    public interface Link { boolean connected(); boolean offer(byte[] bytes,long deadline,Done done); void close(); }
    public enum State { DISCONNECTED, CONNECTING, VERIFYING, STANDBY, TRANSMITTING, FAULT }
    public static final long INPUT_MS=250, WRITE_MS=200, TELEMETRY_MS=3000;
    private static DirectSession owner;
    private final Clock clock;
    private final Wire.Parser parser=new Wire.Parser();
    private final Map<String,Float> params=new LinkedHashMap<>();
    private final ArrayDeque<int[]> expected=new ArrayDeque<>();
    private Link link;
    private Calibration cal;
    private Profile bound,candidate;
    private State state=State.DISCONNECTED;
    private String status="未连接",uid,custom,pendingParam;
    private long epoch,writeId,started,lastTick,heartbeat=-1,rcAt=-1,versionAt=-1,inputAt=-1,inputId=-1,usedInput=-1;
    private long sentAt,lastControl=-1,lastRequest=-1,lastVersionRequest=-1,paramAt=-1,lastParamPoll=-1,lastMatch=-1,transmitAt,offAt=-1,onAt=-1;
    private long rcBoot=-1;
    private int target,targetComp,version=2,seq,firmware,paramRetries,pollIndex,offered,returned,matched;
    private boolean writing,everLinked,armed,offObserved;
    private int[] raw,logical,rc;
    public DirectSession(Clock c) {clock=c;}
    public synchronized void connect(Calibration calibration,Profile profile,Link transport,int sequence) {
        if(state!=State.DISCONNECTED&&state!=State.FAULT)throw new IllegalStateException("请先断开当前连接");
        synchronized(DirectSession.class){if(owner!=null&&owner!=this)throw new IllegalStateException("本进程已有X9连接");owner=this;}
        epoch++;writeId++;cal=profile==null?calibration:profile.calibration;bound=profile;link=transport;candidate=null;state=State.CONNECTING;
        if(cal==null||link==null){stop("设备档案或管道缺失");return;}
        started=lastTick=clock.now();heartbeat=rcAt=versionAt=inputAt=inputId=usedInput=-1;
        lastControl=lastRequest=lastVersionRequest=paramAt=lastParamPoll=lastMatch=-1;offAt=onAt=rcBoot=-1;
        uid=custom=pendingParam=null;target=targetComp=firmware=paramRetries=pollIndex=0;seq=sequence&255;
        offered=returned=matched=0;writing=everLinked=armed=offObserved=false;raw=logical=rc=null;
        params.clear();expected.clear();parser.clear();status="正在连接；等待X9真实心跳";
    }
    public synchronized void input(int[] data,long received,long id) {
        if(!active())return;
        long now=clock.now();if(data==null||received<0||received>now||id<1||(inputId>=0&&id<=inputId)||(inputAt>=0&&received<inputAt)){fault("输入序号或本地时钟无效");return;}
        try {logical=cal.values(data);cal.switchPosition(data);raw=data.clone();inputAt=received;inputId=id;}
        catch(IllegalArgumentException bad){fault(bad.getMessage());}
    }
    public synchronized void receive(byte[] bytes) {
        if(!active()||bytes==null||bytes.length==0)return;
        if(bytes.length>8192){fault("接收数据异常");return;}
        long now=clock.now();
        for(Wire.Frame frame:parser.feed(bytes,now)) {
            if(!active())break;
            ByteBuffer p=frame.fields();
            if(candidate!=null&&frame.system==candidate.sourceSystem()&&frame.component==191){fault("检测到相同发送者ID，无法区分来源，已停止");break;}
            if(frame.message==0) {
                int type=p.get(4)&255,autopilot=p.get(5)&255;
                if(autopilot!=3||!(type==2||type==3||type==4||type==13||type==14||type==15||type==29||type==35))continue;
                if(frame.system<1||frame.component<1){fault("飞控ID无效");break;}
                if((target!=0&&(target!=frame.system||targetComp!=frame.component))||(bound!=null&&(bound.system!=frame.system||bound.component!=frame.component))){fault("发现另一飞控或身份冲突，已停止");break;}
                if(target==0){target=frame.system;targetComp=frame.component;version=frame.version;state=State.VERIFYING;status="识别X9并只读核对版本/参数";}
                heartbeat=now;armed=(p.get(6)&128)!=0;
                continue;
            }
            if(target==0||frame.system!=target||frame.component!=targetComp)continue;
            try {
                if(frame.message==148 && lastVersionRequest>=0) {
                    byte[] d=frame.data();String newUid=hexNonzero(d,60,78);if(newUid==null)newUid=hexNonzero(d,8,16);
                    if(newUid==null)throw new IllegalArgumentException("X9未提供可绑定UID，不能自动认领目标");
                    int fw=p.getInt(16);String build=hex(d,36,44);
                    if((fw>>>8)!=0x040701)throw new IllegalArgumentException("目标不是已核对的ArduCopter4.7.1");
                    if(uid!=null&&(!uid.equals(newUid)||firmware!=fw||!custom.equals(build)))throw new IllegalArgumentException("连接中的飞控身份/固件变化");
                    uid=newUid;custom=build;firmware=fw;versionAt=now;
                    if(bound!=null&&(!bound.uid.equals(uid)||bound.firmware!=firmware||!bound.custom.equals(custom)))throw new IllegalArgumentException("X9身份/固件与保存档案不一致");
                } else if(frame.message==22 && uid!=null) {
                    byte[] d=frame.data();int end=8;while(end<24&&d[end]!=0)end++;
                    String name=new String(d,8,end-8,StandardCharsets.US_ASCII);float value=p.getFloat(0);
                    if(!Profile.needed(params).contains(name))continue;
                    if(!Float.isFinite(value))throw new IllegalArgumentException("参数返回非有限值："+name);
                    if(candidate!=null&&(!candidate.parameters.containsKey(name)||Float.compare(candidate.value(name),value)!=0))throw new IllegalArgumentException("飞控参数发生变化："+name);
                    params.put(name,value);
                    if(name.equals(pendingParam)){pendingParam=null;paramRetries=0;}
                    finishVerification();
                } else if(frame.message==65) {
                    long boot=Integer.toUnsignedLong(p.getInt(0));
                    if(rcBoot>=0 && boot<rcBoot && rcBoot-boot<0x80000000L)throw new IllegalArgumentException("飞控重启或迟到RC遥测，须重新连接");
                    if(rcBoot==boot)continue; // repeated cached telemetry cannot refresh local receive health
                    rcBoot=boot;rcAt=now;rc=new int[18];for(int i=0;i<18;i++)rc[i]=p.getShort(4+i*2)&65535;
                    if(candidate!=null){int largest=Arrays.stream(candidate.channels()).max().orElse(18);if((p.get(40)&255)<largest)throw new IllegalArgumentException("飞控RC反馈缺少映射通道");}
                    if(state==State.TRANSMITTING&&candidate!=null)for(int[] wanted:expected)if(matches(wanted)){lastMatch=now;matched++;break;}
                }
            } catch(IllegalArgumentException bad){fault(bad.getMessage());}
        }
        if(parser.signedSeen&&active())fault("检测到MAVLink签名帧；本版未配置签名认证，不绕过保护");
    }
    private static String hexNonzero(byte[] d,int start,int end) {boolean any=false;for(int i=start;i<end;i++)any|=d[i]!=0;return any?hex(d,start,end):null;}
    private static String hex(byte[] d,int start,int end){StringBuilder b=new StringBuilder();for(int i=start;i<end;i++){b.append(Character.forDigit((d[i]>>>4)&15,16));b.append(Character.forDigit(d[i]&15,16));}return b.toString();}
    private void finishVerification() {
        List<String> keys=Profile.needed(params);if(!params.keySet().containsAll(keys))return;
        if(candidate==null){candidate=new Profile(cal,target,targetComp,uid,firmware,custom,params);
            if(version==1&&Arrays.stream(candidate.channels()).anyMatch(c->c>8))throw new IllegalArgumentException("MAVLink1无法覆盖9..18通道，请核对已有串口协议配置");
            if(bound!=null&&!bound.same(candidate))throw new IllegalArgumentException("飞控配置与绑定档案不一致，须重新核对绑定");
            state=State.STANDBY;status=bound==null?"已读取X9；请确认身份并保存绑定":"X9已连接·待命；先将接管开关置关";
        }
    }
    public synchronized void tick() {
        if(!active())return;long now=clock.now();
        if(now<lastTick||now-lastTick>=INPUT_MS){fault("调度暂停/时钟异常");return;}lastTick=now;
        if(!link.connected()){if(everLinked||now-started>=5000)fault("SDK数传未连接/已断开");return;}everLinked=true;
        if(target==0){if(now-started>=10000)fault("未收到X9心跳；检查透传接线、波特率和MAVLink配置");return;}
        if(now-heartbeat>=TELEMETRY_MS){fault("X9心跳过期");return;}
        if(inputAt<0||now<inputAt||now-inputAt>=INPUT_MS){fault("摇杆输入过期，不发送缓存值");return;}
        if(writing){if(now-sentAt>=WRITE_MS)fault("SDK写调用超时；已停止");return;}
        if(uid==null){if(lastVersionRequest<0||now-lastVersionRequest>=1000){if(now-started>=15000){fault("X9版本/UID未响应");return;}request(148,now);}return;}
        if(candidate==null){requestNextParameter(now);return;}
        if(versionAt<0||now-versionAt>=20000){fault("飞控身份复核超时");return;}
        if(rcAt<0||now-rcAt>=TELEMETRY_MS){if(state==State.TRANSMITTING||now-started>=25000){fault("X9 RC遥测过期");return;}}
        if(bound!=null&&rcAt>=0&&now-rcAt<TELEMETRY_MS)updateSwitch(now);
        if(!active())return;
        if(state==State.TRANSMITTING){
            if(now-(lastMatch>=transmitAt?lastMatch:transmitAt)>=1500){fault("X9输入反馈未跟随请求；已停止");return;}
            if(usedInput!=inputId&&(lastControl<0||now-lastControl>=1000/candidate.rateHz())){
                int[] pwm=candidate.pwm(logical);expected.addLast(pwm.clone());while(expected.size()>12)expected.removeFirst();
                usedInput=inputId;lastControl=now;offered++;
                send(Wire.override(version,candidate.sourceSystem(),191,seq++,target,targetComp,candidate.channels(),pwm),now,true);return;
            }
        }
        if(lastRequest<0||now-lastRequest>=500){request(65,now);return;}
        if(lastVersionRequest<0||now-lastVersionRequest>=10000){request(148,now);return;}
        if(lastParamPoll<0||now-lastParamPoll>=1000){List<String> keys=Profile.needed(params);String key=keys.get(pollIndex++%keys.size());lastParamPoll=now;
            send(Wire.parameter(version,source(),191,seq++,target,targetComp,key),now,false);}
    }
    private void updateSwitch(long now) {
        int position=cal.switchPosition(raw);
        if(position==0){if(offAt<0)offAt=now;if(now-offAt>=100)offObserved=true;onAt=-1;
            if(state==State.TRANSMITTING){state=State.STANDBY;expected.clear();status="已停发；X9按覆盖超时处理，未确认主SBUS已接回";}return;}
        offAt=-1;
        if(position<0){onAt=-1;if(state==State.TRANSMITTING){state=State.STANDBY;expected.clear();offObserved=false;status="开关离开接管位置，已停发";}return;}
        if(!offObserved){status="待命：将接管开关置关后，再重新开启";return;}
        if(onAt<0)onAt=now;
        if(state==State.STANDBY&&now-onAt>=100){state=State.TRANSMITTING;transmitAt=now;lastMatch=-1;usedInput=-1;expected.clear();status="持续传输中；RC反馈不代表独占控制权";}
    }
    private boolean matches(int[] wanted){int[] channels=candidate.channels();for(int i=0;i<4;i++)if(Math.abs(rc[channels[i]-1]-wanted[i])>3)return false;return true;}
    private int source(){return candidate==null?255:candidate.sourceSystem();}
    private void request(int message,long now){if(message==148)lastVersionRequest=now;else lastRequest=now;send(Wire.request(version,source(),191,seq++,target,targetComp,message),now,false);}
    private void requestNextParameter(long now){
        if(pendingParam==null){for(String key:Profile.needed(params))if(!params.containsKey(key)){pendingParam=key;paramAt=-1;paramRetries=0;break;}}
        if(pendingParam==null)return;
        if(paramAt<0||now-paramAt>=800){if(paramRetries>=3){fault("参数未响应："+pendingParam+"；App不会猜测或自动改写");return;}
            paramAt=now;paramRetries++;status="核对参数："+pendingParam;
            send(Wire.parameter(version,source(),191,seq++,target,targetComp,pendingParam),now,false);}
    }
    private void send(byte[] bytes,long now,boolean control){
        final long generation=epoch,id=++writeId;writing=true;sentAt=now;
        long deadline=control?Math.min(inputAt+INPUT_MS,now+WRITE_MS):now+WRITE_MS;
        boolean accepted;
        try{accepted=link.offer(bytes,deadline,ok->{synchronized(DirectSession.this){
            if(!active()||generation!=epoch||id!=writeId||!writing)return;
            writing=false;if(!ok||clock.now()<sentAt||clock.now()-sentAt>=WRITE_MS){fault("SDK调用失败/迟到，已停止");return;}
            if(control)returned++;
        }});}catch(RuntimeException error){accepted=false;}
        if(!accepted&&generation==epoch)fault("SDK发送工作线程忙，禁止积压");
    }
    public synchronized void stop(String reason){close(State.DISCONNECTED,reason);}
    public synchronized void fault(String reason){close(State.FAULT,reason);}
    private void close(State next,String reason){epoch++;writeId++;state=next;status=reason;writing=false;raw=logical=null;inputAt=inputId=-1;offObserved=false;expected.clear();parser.clear();Link old=link;link=null;
        synchronized(DirectSession.class){if(owner==this)owner=null;}
        if(old!=null)try{old.close();}catch(RuntimeException error){status+="；关闭请求异常";}
    }
    public synchronized boolean active(){return state!=State.DISCONNECTED&&state!=State.FAULT;}
    public synchronized State state(){return state;}
    public synchronized String status(){return status;}
    public synchronized Profile candidate(){return candidate;}
    public synchronized boolean armed(){return armed;}
    public synchronized int[] logical(){return logical==null?null:logical.clone();}
    public synchronized int[] feedback(){return rc==null?null:rc.clone();}
    public synchronized int offered(){return offered;}
    public synchronized int returned(){return returned;}
    public synchronized int matched(){return matched;}
    public synchronized long heartbeatAge(){return heartbeat<0?-1:Math.max(0,clock.now()-heartbeat);}
    public synchronized long rcAge(){return rcAt<0?-1:Math.max(0,clock.now()-rcAt);}
}
