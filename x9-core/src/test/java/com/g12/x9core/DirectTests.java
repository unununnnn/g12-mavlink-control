package com.g12.x9core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DirectTests {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    // Generated independently by pymavlink2.4.49, rechecked in lab.test_x9_wire.
    public static final String VERSION_FIXTURE="fd2c000000070194000002000000000000008967452301000000ff010704000000000000000000000000000000000102030405060708a7a7";
    private static byte[] unhex(String h){byte[] b=new byte[h.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(h.substring(i*2,i*2+2),16);return b;}
    private static Calibration calibration(){Calibration.Axis[] a=new Calibration.Axis[4];for(int i=0;i<4;i++)a[i]=new Calibration.Axis(i,1000,1500,2000,false);return new Calibration(5,a,4,1000,2000,115200);}
    private static Map<String,Float> parameters(){Map<String,Float> p=new LinkedHashMap<>();for(String k:Profile.BASE)p.put(k,0f);
        p.put("MAV_GCS_SYSID",255f);p.put("RC_OVERRIDE_TIME",3f);p.put("FS_THR_ENABLE",1f);p.put("FS_THR_VALUE",975f);p.put("FS_GCS_ENABLE",1f);p.put("FENCE_ENABLE",1f);
        for(int i=0;i<4;i++){p.put("RCMAP_"+Profile.ROLES[i],(float)(i+1));String k="RC"+(i+1)+"_";p.put(k+"MIN",1000f);p.put(k+"MAX",2000f);p.put(k+"TRIM",i==2?1000f:1500f);p.put(k+"REVERSED",0f);}return p;}
    private static final class Rig implements DirectSession.Clock,DirectSession.Link {
        long now=1000,id;int seq,wireVersion=2,controls,closes;boolean connected=true,heartbeats=true,feedback=true,adopt=true,inputs=true,armed=true,holdWrite,oracleVersion;
        DirectSession s=new DirectSession(this);DirectSession.Done pending;
        int[] raw={1500,1500,1000,1500,1000},rc=new int[18];Map<String,Float> params=parameters();List<byte[]> sent=new ArrayList<>();
        Rig(){Arrays.fill(rc,1500);rc[2]=1000;}
        @Override public long now(){return now;}
        @Override public boolean connected(){return connected;}
        @Override public void close(){connected=false;closes++;}
        @Override public boolean offer(byte[] bytes,long deadline,DirectSession.Done done){sent.add(bytes.clone());Wire.Frame f=Wire.decode(bytes);ByteBuffer b=f.fields();
            check(f.message==20||f.message==76||f.message==70,"only read requests and four-axis overrides");
            if(holdWrite){pending=done;return true;}done.returned(true);
            if(f.message==76){check((b.getShort(28)&65535)==512,"no arm/mode/stream/parameter command");if(b.getFloat(0)==148)version();else if(feedback)rc();}
            if(f.message==20){byte[] d=f.data();int end=4;while(end<20&&d[end]!=0)end++;String key=new String(d,4,end-4,StandardCharsets.US_ASCII);if(params.containsKey(key))param(key,params.get(key));}
            if(f.message==70){controls++;if(adopt)for(int i=0;i<18;i++){int at=i<8?i*2:18+(i-8)*2;int value=b.getShort(at)&65535;if(value!=65535&&value!=0)rc[i]=value;}}
            return true;
        }
        void message(int message,byte[] p){s.receive(Wire.encode(wireVersion,7,1,seq++,message,p));}
        void heartbeat(){byte[] p=new byte[9];p[4]=2;p[5]=3;p[6]=(byte)(armed?128:0);p[7]=4;p[8]=3;message(0,p);}
        void version(){if(oracleVersion){s.receive(unhex(VERSION_FIXTURE));return;}byte[] p=new byte[78];ByteBuffer b=Wire.le(p);b.putLong(0,2);b.putLong(8,0x123456789L);b.putInt(16,0x040701ff);p[36]=1;message(148,p);}
        void rc(){byte[] p=new byte[42];ByteBuffer b=Wire.le(p);b.putInt((int)now);for(int i=0;i<18;i++)b.putShort(4+i*2,(short)rc[i]);p[40]=18;message(65,p);}
        void param(String name,float value){byte[] p=new byte[25];Wire.le(p).putFloat(value).putShort((short)30).putShort((short)0);byte[] n=name.getBytes(StandardCharsets.US_ASCII);System.arraycopy(n,0,p,8,n.length);p[24]=9;message(22,p);}
        void begin(Profile p){connected=true;s.connect(calibration(),p,this,0);heartbeat();}
        void step(){now+=25;if(inputs)s.input(raw,now,++id);if(heartbeats&&now%1000==0)heartbeat();if(feedback&&now%200==0)rc();s.tick();}
        void run(long ms){for(long end=now+ms;now<end;)step();}
        Profile discover(){begin(null);run(2000);check(s.candidate()!=null,"read-only discovery completes");check(controls==0,"binding never sends control");Profile p=s.candidate();s.stop("save");return p;}
        void ready(){Profile p=discover();begin(p);run(2000);check(s.state()==DirectSession.State.STANDBY,"bound connection ready");}
        void take(){raw[4]=2000;run(300);check(s.state()==DirectSession.State.TRANSMITTING,"physical edge starts transmission even armed");}
    }
    public static void main(String[] args)throws Exception {
        for(int order=0;order<2;order++)for(int repeat=0;repeat<20;repeat++){
            PipelineLease lease=PipelineLease.acquire();check(lease.beginWrite(),"SDK write lease acquired");lease.closing();check(!lease.beginWrite(),"closing cannot queue another write");
            if(order==0)lease.disconnected(true);else lease.endWrite();
            boolean busy=false;try{PipelineLease.acquire();}catch(IllegalStateException expected){busy=true;}check(busy,"reconnect waits for BOTH old write and disconnect");
            if(order==0)lease.endWrite();else lease.disconnected(true);
            PipelineLease next=PipelineLease.acquire();lease.endWrite();lease.disconnected(true);
            boolean stillBusy=false;try{PipelineLease.acquire();}catch(IllegalStateException expected){stillBusy=true;}check(stillBusy,"late old callbacks cannot release new owner");next.closing();next.disconnected(true);
        }
        Calibration c=calibration();check(Arrays.equals(c.values(new int[]{1000,2000,1500,1500,1000}),new int[]{-1000,1000,500,0}),"calibrated axes");
        check(c.values(new int[]{995,2005,999,1500,1000})[0]==-1000,"small endpoint jitter clamps within one percent");
        boolean outOfRange=false;try{c.values(new int[]{950,1500,1000,1500,1000});}catch(IllegalArgumentException expected){outOfRange=true;}check(outOfRange,"substantial out-of-range input faults");
        Rig oracle=new Rig();oracle.oracleVersion=true;Profile independent=oracle.discover();check(independent.custom.equals("0102030405060708"),"independent AUTOPILOT_VERSION custom-build offset");
        Rig changedBuild=new Rig();changedBuild.ready();changedBuild.s.receive(unhex(VERSION_FIXTURE));check(changedBuild.s.state()==DirectSession.State.FAULT,"same semantic version but changed firmware build rejected");
        check(Calibration.capture(new int[]{1000,1500,1000,1500,1000},new int[]{2000,1500,1000,1500,1000},new int[]{1500,1500,1000,1500,1000}).channel==0,"learn physical axis");
        Profile pp=new Profile(c,7,1,"0000000123456789",0x040701ff,"0100000000000000",parameters());
        Profile roundtrip=ProfileCodec.decode(ProfileCodec.encode(pp));check(roundtrip.same(pp)&&roundtrip.calibration.axis(2).min==1000&&roundtrip.calibration.switchOn==2000,"profile roundtrip preserves real calibration and identity");
        for(String broken:List.of(ProfileCodec.encode(pp)+"armed=1\n",ProfileCodec.encode(pp)+"schema=1\n",ProfileCodec.encode(pp).replace("RC_OVERRIDE_TIME=3.0","RC_OVERRIDE_TIME=NaN"))){boolean refused=false;try{ProfileCodec.decode(broken);}catch(IllegalArgumentException e){refused=true;}check(refused,"corrupt/activation fields cannot restore state");}
        check(Arrays.equals(pp.pwm(new int[]{1000,1000,0,-1000}),new int[]{2000,1000,1000,1000}),"pitch forward negative, throttle min");
        Map<String,Float> reverse=parameters();reverse.put("RC3_REVERSED",1f);Profile pr=new Profile(c,7,1,pp.uid,pp.firmware,pp.custom,reverse);
        check(pr.pwm(new int[]{0,0,0,0})[2]==2000,"reversed FC throttle correctly compensated");
        Rig continuous=new Rig();continuous.ready();continuous.take();continuous.raw[0]=1800;continuous.run(65000);
        check(continuous.s.state()==DirectSession.State.TRANSMITTING&&continuous.controls>500,"continuous beyond old 10s; armed not a gate");
        continuous.raw[4]=1000;continuous.run(200);int stopped=continuous.controls;continuous.run(1000);
        check(continuous.controls==stopped&&continuous.s.state()==DirectSession.State.STANDBY,"switch off stops refresh without release or neutral");continuous.s.stop("done");
        Rig onAtConnect=new Rig();Profile saved=onAtConnect.discover();onAtConnect.raw[4]=2000;onAtConnect.begin(saved);onAtConnect.run(2500);
        Rig retiredPipe=new Rig();long hbAge=onAtConnect.s.heartbeatAge();onAtConnect.s.faultFrom(retiredPipe,"late old disconnect");
        byte[] otherHb=new byte[9];otherHb[4]=2;otherHb[5]=3;otherHb[8]=3;onAtConnect.s.receiveFrom(retiredPipe,Wire.encode(2,8,1,1,0,otherHb));
        check(onAtConnect.s.state()==DirectSession.State.STANDBY&&onAtConnect.s.heartbeatAge()==hbAge,"old transport callbacks cannot fault or refresh a new connection");
        check(onAtConnect.controls==0,"held ON cannot reconnect into transmission");onAtConnect.raw[4]=1000;onAtConnect.run(200);onAtConnect.take();onAtConnect.s.stop("done");
        Rig inputLoss=new Rig();inputLoss.ready();inputLoss.take();inputLoss.inputs=false;inputLoss.run(300);check(inputLoss.s.state()==DirectSession.State.FAULT,"stale local input stops");
        int count=inputLoss.controls;inputLoss.inputs=true;inputLoss.run(1000);check(inputLoss.controls==count,"no automatic recovery after fault");
        Rig loss=new Rig();loss.ready();loss.take();loss.heartbeats=false;loss.run(3100);check(loss.s.state()==DirectSession.State.FAULT,"heartbeat loss stops");
        Rig config=new Rig();config.ready();config.take();config.param("FENCE_ENABLE",0);check(config.s.state()==DirectSession.State.FAULT,"configuration drift faults, no rewrite");
        Rig ignored=new Rig();ignored.ready();ignored.adopt=false;ignored.raw[0]=2000;ignored.take();ignored.run(1600);check(ignored.s.state()==DirectSession.State.FAULT,"FC not following requested PWM stops");
        Rig paused=new Rig();paused.ready();paused.take();paused.now+=250;paused.s.tick();check(paused.s.state()==DirectSession.State.FAULT,"watchdog scheduling gap stops");
        Rig busy=new Rig();busy.ready();busy.holdWrite=true;busy.take();busy.run(250);check(busy.s.state()==DirectSession.State.FAULT,"blocked SDK write stops");busy.pending.returned(true);check(busy.s.state()==DirectSession.State.FAULT,"late callback cannot revive");
        Rig first=new Rig();first.ready();Rig duplicate=new Rig();boolean rejected=false;try{duplicate.begin(saved);}catch(IllegalStateException expected){rejected=true;}check(rejected,"process-level owner only");first.s.stop("done");
        Rig mismatch=new Rig();mismatch.params.put("FS_OPTIONS",1f);mismatch.begin(saved);mismatch.run(2000);check(mismatch.s.state()==DirectSession.State.FAULT,"saved parameter fingerprint mismatch");
        Rig v1=new Rig();v1.wireVersion=1;v1.ready();v1.take();v1.run(12000);check(v1.s.state()==DirectSession.State.TRANSMITTING,"MAVLink1 also continuous");v1.s.stop("done");
        byte[] v2=Wire.override(2,255,191,17,7,1,new int[]{1,2,3,4},new int[]{1500,1300,1200,1700});Wire.Parser parser=new Wire.Parser();List<Wire.Frame> frames=new ArrayList<>();for(byte b:v2)frames.addAll(parser.feed(new byte[]{b},0));check(frames.size()==1&&frames.get(0).message==70,"fragmented frame");
        byte[] corrupt=v2.clone();corrupt[11]^=1;check(new Wire.Parser().feed(corrupt,0).isEmpty(),"CRC corruption rejected");
        Files.createDirectories(Path.of("artifacts"));StringBuilder json=new StringBuilder("[");
        byte[][] vectors={v2,Wire.override(1,255,191,18,7,1,new int[]{1,2,3,4},new int[]{1500,1300,1200,1700}),Wire.request(2,255,191,19,7,1,148),Wire.parameter(2,255,191,20,7,1,"RC_OVERRIDE_TIME")};
        for(int i=0;i<vectors.length;i++){if(i>0)json.append(',');json.append('"');for(byte b:vectors[i])json.append(String.format("%02x",b&255));json.append('"');}Files.writeString(Path.of("artifacts/x9-vectors.json"),json.append(']').toString());
        System.out.println("PASS: "+checks+" X9 direct assertions; synthetic FC, no hardware; continuous armed stream >65s verified");
    }
}
