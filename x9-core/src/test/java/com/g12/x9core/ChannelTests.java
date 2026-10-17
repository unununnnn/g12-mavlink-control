package com.g12.x9core;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

public final class ChannelTests {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static int[] sample(int n){int[] v=new int[n];for(int i=0;i<n;i++)v[i]=1050+(i*47)%901;return v;}
    private static final class Rig implements ChannelSession.Clock,ChannelSession.Link {
        long now=1000,id;int sys=7,seq,wire=2,controls,requests;boolean connected=true,inputs=true,heartbeats=true,feedback=true,adopt=true,hold;
        String missing="";int[] raw=sample(18),rc=sample(18),delivered;ChannelSession s=new ChannelSession(this);ChannelSession.Done pending;
        Map<String,Float> params=Map.of("MAV_GCS_SYSID",245f,"RC_OPTIONS",0f,"RC_OVERRIDE_TIME",3f);
        public long now(){return now;}public boolean connected(){return connected;}public void close(){connected=false;}
        public boolean offer(byte[] bytes,long deadline,ChannelSession.Done done){Wire.Frame f=Wire.decode(bytes);ByteBuffer b=f.fields();
            check(f.message==20||f.message==76||f.message==70,"only channel/read requests");
            if(hold){pending=done;return true;}done.returned(true);
            if(f.message==76){requests++;check(b.getShort(28)==512&&b.getFloat(0)==65,"no UID/version requirement or write/arm command");if(feedback)rc();}
            if(f.message==20){byte[] d=f.data();int end=4;while(end<20&&d[end]!=0)end++;String key=new String(d,4,end-4,StandardCharsets.US_ASCII);if(params.containsKey(key)&&!missing.equals(key))param(key,params.get(key));}
            if(f.message==70){controls++;check(f.system==245&&f.component==191,"actual FC configured source ID");check((b.get(16)&255)==sys&&(b.get(17)&255)==1,"discovered target ID");
                for(int i=0;i<(wire==1?8:18);i++){int value=b.getShort(i<8?i*2:18+(i-8)*2)&65535;check(value==(i<delivered.length?delivered[i]:65535),"raw order, including auxiliaries, exact; no calibration or mapping");if(adopt&&value!=65535)rc[i]=value;}}
            return true;
        }
        void msg(int message,byte[] p){s.receive(Wire.encode(wire,sys,1,seq++,message,p));}
        void heartbeat(){byte[] p=new byte[9];p[4]=2;p[5]=3;p[6]=(byte)128;p[7]=4;p[8]=3;msg(0,p);}
        void rc(){byte[] p=new byte[42];Wire.le(p).putInt((int)now);for(int i=0;i<18;i++)Wire.le(p).putShort(4+i*2,(short)rc[i]);p[40]=18;msg(65,p);}
        void param(String key,float value){byte[] p=new byte[25];Wire.le(p).putFloat(value);byte[] n=key.getBytes(StandardCharsets.US_ASCII);System.arraycopy(n,0,p,8,n.length);p[24]=9;msg(22,p);}
        void begin(){connected=true;s.connect(this,0);heartbeat();}
        void step(){now+=25;if(inputs&&(now%100==0||id==0)){delivered=raw.clone();s.input(delivered,now,++id);}if(heartbeats&&now%1000==0)heartbeat();if(feedback&&now%200==0)rc();s.tick();}
        void run(long ms){for(long end=now+ms;now<end;)step();}
        void ready(){begin();run(500);check(s.state()==ChannelSession.State.TRANSMITTING&&controls>0,"one connect starts without setup/UID/version/switch");}
    }
    public static void main(String[] args)throws Exception{
        Rig continuous=new Rig();check(!continuous.s.active()&&continuous.controls==0,"launch never auto connects");continuous.ready();continuous.run(65000);
        check(continuous.controls>600&&continuous.s.state()==ChannelSession.State.TRANSMITTING,"armed continuous raw stream beyond65seconds");
        for(int i=0;i<18;i++)continuous.raw[i]=2000-i*37;continuous.run(700);check(continuous.s.matched()>0,"all auxiliary channels observed");
        continuous.s.stop("user disconnect");int count=continuous.controls;continuous.run(500);check(continuous.controls==count,"stop does not release, neutralize or keep sending");
        continuous.sys=9;continuous.begin();continuous.run(500);check(continuous.s.targetSystem()==9&&continuous.s.state()==ChannelSession.State.TRANSMITTING,"fresh connect may target another FC; no binding or stored authorization");
        Rig oldPipe=new Rig();continuous.s.faultFrom(oldPipe,"late disconnect");byte[] otherHb=new byte[9];otherHb[4]=2;otherHb[5]=3;continuous.s.receiveFrom(oldPipe,Wire.encode(2,99,1,1,0,otherHb));
        check(continuous.s.state()==ChannelSession.State.TRANSMITTING,"retired pipeline ignored");continuous.s.stop("done");
        Rig stale=new Rig();stale.ready();stale.inputs=false;stale.run(300);check(stale.s.state()==ChannelSession.State.FAULT,"stale input stops");count=stale.controls;stale.inputs=true;stale.run(500);check(stale.controls==count,"no automatic recovery");
        Rig lost=new Rig();lost.ready();lost.heartbeats=false;lost.run(3100);check(lost.s.state()==ChannelSession.State.FAULT,"heartbeat loss stops");
        Rig rcLoss=new Rig();rcLoss.ready();rcLoss.feedback=false;rcLoss.run(3100);check(rcLoss.s.state()==ChannelSession.State.FAULT,"feedback loss stops");
        Rig changed=new Rig();changed.ready();changed.param("RC_OPTIONS",2);check(changed.s.state()==ChannelSession.State.FAULT,"receive configuration changed");
        Rig refused=new Rig();refused.ready();refused.adopt=false;refused.raw[15]=2200;refused.run(2800);check(refused.s.state()==ChannelSession.State.FAULT,"auxiliary channel16 not adopted is visible fault");
        Rig missing=new Rig();missing.missing="MAV_GCS_SYSID";missing.begin();missing.run(3000);check(missing.s.state()==ChannelSession.State.FAULT&&missing.controls==0,"missing source ID is not guessed");
        Rig drift=new Rig();drift.ready();drift.sys=8;drift.heartbeat();check(drift.s.state()==ChannelSession.State.FAULT,"conflicting target during connection stops");
        Rig busy=new Rig();busy.ready();busy.hold=true;busy.run(350);check(busy.s.state()==ChannelSession.State.FAULT,"write timeout stops");busy.pending.returned(true);check(busy.s.state()==ChannelSession.State.FAULT,"late write cannot revive");
        Rig paused=new Rig();paused.ready();paused.now+=250;paused.s.tick();check(paused.s.state()==ChannelSession.State.FAULT,"scheduling gap stops");
        Rig bad=new Rig();bad.raw[8]=65535;bad.begin();bad.run(200);check(bad.s.state()==ChannelSession.State.FAULT&&bad.controls==0,"reserved value is not sent as ignore data");
        Rig tooMany=new Rig();tooMany.raw=sample(19);tooMany.begin();tooMany.run(200);check(tooMany.s.state()==ChannelSession.State.FAULT&&tooMany.controls==0,"no silent truncation beyond18");
        Rig v1=new Rig();v1.wire=1;v1.raw=sample(8);v1.ready();v1.run(12000);check(v1.s.state()==ChannelSession.State.TRANSMITTING,"all8 channels on MAVLink1");v1.s.stop("done");
        Rig v1full=new Rig();v1full.wire=1;v1full.begin();v1full.run(500);check(v1full.s.state()==ChannelSession.State.FAULT&&v1full.controls==0,"MAVLink1 cannot silently lose auxiliaries");
        Rig signed=new Rig();signed.ready();byte[] sign=Arrays.copyOf(Wire.channels(2,245,191,0,7,1,sample(18)),63);sign[2]=1;signed.s.receive(sign);check(signed.s.state()==ChannelSession.State.FAULT,"signed stream not bypassed");
        StringBuilder json=new StringBuilder("[");int[] counts={16,18,8};for(int k=0;k<counts.length;k++){if(k>0)json.append(',');json.append('"');for(byte b:Wire.channels(k==2?1:2,245,191,30+k,7,1,sample(counts[k])))json.append(String.format("%02x",b&255));json.append('"');}
        Files.createDirectories(Path.of("artifacts"));
        Files.writeString(Path.of("artifacts/channel-vectors.json"),json.append(']').toString());
        System.out.println("PASS: "+checks+" raw-channel assertions; no setup/binding, all18 channels, synthetic FC only");
    }
}
