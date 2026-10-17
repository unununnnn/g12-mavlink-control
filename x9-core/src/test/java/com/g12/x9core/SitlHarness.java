package com.g12.x9core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Test-only stdio transport. No network/device access; lab.x9_sitl owns the isolated FC. */
public final class SitlHarness {
    private SitlHarness() { }
    private static String hex(byte[] data){StringBuilder b=new StringBuilder();for(byte v:data)b.append(String.format("%02x",v&255));return b.toString();}
    private static byte[] unhex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
    public static void main(String[] args) throws Exception {
        DirectSession.Clock clock=()->System.nanoTime()/1_000_000;
        DirectSession s=new DirectSession(clock);
        DirectSession.Link link=new DirectSession.Link(){
            public boolean connected(){return true;}
            public void close(){ }
            public boolean offer(byte[] bytes,long deadline,DirectSession.Done done){
                System.out.println("TX "+hex(bytes));done.returned(true);return true;
            }
        };
        Thread reader=new Thread(()->{
            try(BufferedReader in=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.US_ASCII))){
                String line;while((line=in.readLine())!=null)s.receive(unhex(line));
            }catch(Exception bad){s.fault("stdio: "+bad.getMessage());}
        });reader.setDaemon(true);reader.start();
        Calibration.Axis[] axes=new Calibration.Axis[4];for(int i=0;i<4;i++)axes[i]=new Calibration.Axis(i,1000,1500,2000,false);
        Calibration cal=new Calibration(5,axes,4,1000,2000,115200);
        Profile profile=null;int[] raw={1800,1300,1000,1650,1000};long id=0,phaseAt=clock.now(),started=phaseAt;int phase=0,firstFrames=0;
        boolean feed=true;DirectSession.State previous=null;
        s.connect(cal,null,link,0);
        while(clock.now()-started<75000){
            long now=clock.now();if(feed)s.input(raw,now,++id);s.tick();
            DirectSession.State state=s.state();if(state!=previous){System.out.println("STATE "+state+" "+s.status());previous=state;}
            if(state==DirectSession.State.FAULT&&phase!=6)throw new AssertionError(s.status());
            if(phase==0&&s.candidate()!=null&&s.rcAge()>=0){
                if(s.offered()!=0)throw new AssertionError("Binding sent controls");
                profile=ProfileCodec.decode(ProfileCodec.encode(s.candidate()));s.stop("test binding saved");s.connect(cal,profile,link,30);phase=1;
            }else if(phase==1&&state==DirectSession.State.STANDBY){phaseAt=now;phase=2;}
            else if(phase==2&&now-phaseAt>400){raw[4]=2000;if(state==DirectSession.State.TRANSMITTING){phaseAt=now;phase=3;}}
            else if(phase==3&&now-phaseAt>12500){firstFrames=s.offered();if(firstFrames<100||s.matched()==0)throw new AssertionError("Continuous output/feedback absent");raw[4]=1000;phaseAt=now;phase=4;}
            else if(phase==4&&now-phaseAt>4000){
                if(state!=DirectSession.State.STANDBY||s.offered()!=firstFrames||!Arrays.equals(Arrays.copyOf(s.feedback(),4),new int[]{1450,1550,1000,1500}))throw new AssertionError("Stop did not fall back to independent simulated primary");
                s.stop("manual reconnect");raw[4]=2000;s.connect(cal,profile,link,90);phase=5;phaseAt=-1;
            }else if(phase==5&&state==DirectSession.State.STANDBY){
                if(phaseAt<0)phaseAt=now;if(s.offered()!=0)throw new AssertionError("Held ON reactivated after reconnect");
                if(now-phaseAt>1000){feed=false;phaseAt=now;phase=6;}
            }else if(phase==6&&now-phaseAt>700){
                if(state!=DirectSession.State.FAULT||s.offered()!=0)throw new AssertionError("Stale input did not latch");
                System.out.println("PASS continuous="+firstFrames+" binding_readonly=true switch_off=true primary_timeout=true reconnect_held_on_blocked=true stale_latched=true");s.stop("test done");return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Harness deadline; phase="+phase+" "+s.status());
    }
}
