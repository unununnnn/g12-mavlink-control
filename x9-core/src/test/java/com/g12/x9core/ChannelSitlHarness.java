package com.g12.x9core;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Test-only stdio bridge, 18-channel data including unchanged stock auxiliary controls. */
public final class ChannelSitlHarness {
    private ChannelSitlHarness() { }
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format("%02x",v&255));return s.toString();}
    private static byte[] unhex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16);return b;}
    public static void main(String[] args)throws Exception{
        ChannelSession.Clock clock=()->System.nanoTime()/1_000_000;ChannelSession s=new ChannelSession(clock);
        ChannelSession.Link link=new ChannelSession.Link(){public boolean connected(){return true;}public void close(){}public boolean offer(byte[] b,long deadline,ChannelSession.Done done){System.out.println("TX "+hex(b));done.returned(true);return true;}};
        Thread reader=new Thread(()->{try(BufferedReader in=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.US_ASCII))){String line;while((line=in.readLine())!=null)s.receive(unhex(line));}catch(Exception bad){s.fault(bad.getMessage());}});reader.setDaemon(true);reader.start();
        int[] raw={1800,1300,1000,1650,1100,1000,1000,1000,1500,1500,1500,1500,1500,1500,1500,1500,1500,1500};
        long start=clock.now(),phaseAt=start,id=0;int phase=0,frames=0;boolean feed=true;ChannelSession.State previous=null;s.connect(link,0);
        while(clock.now()-start<55000){long now=clock.now();if(feed)s.input(raw,now,++id);s.tick();ChannelSession.State state=s.state();
            if(state!=previous){System.out.println("STATE "+state+" "+s.status());previous=state;}
            if(state==ChannelSession.State.FAULT&&phase!=3)throw new AssertionError(s.status()+" observed="+Arrays.toString(s.feedback())+" count="+s.feedbackCount());
            if(phase==0&&state==ChannelSession.State.TRANSMITTING){phase=1;phaseAt=now;}
            else if(phase==1&&now-phaseAt>12500){if(s.offered()<100||s.matched()==0||!Arrays.equals(Arrays.copyOf(s.feedback(),16),Arrays.copyOf(raw,16)))throw new AssertionError("Supported16 feedback channels not adopted exactly");
                if(s.feedback()[16]!=0||s.feedback()[17]!=0)throw new AssertionError("Unexpected stock4.7.1 channel17/18 behavior");frames=s.offered();s.stop("test disconnect");phase=2;phaseAt=now;}
            else if(phase==2&&now-phaseAt>4000){if(s.offered()!=frames)throw new AssertionError("Sent while stopped");s.connect(link,40);phase=3;phaseAt=-1;}
            else if(phase==3&&state==ChannelSession.State.TRANSMITTING){if(phaseAt<0)phaseAt=now;if(now-phaseAt>500)feed=false;}
            else if(phase==3&&state==ChannelSession.State.FAULT){if(feed||s.offered()==0)throw new AssertionError("Reconnect or stale-input behavior wrong");System.out.println("PASS wire_channels=18 adopted_channels=16 no_setup=true no_binding=true reconnect=true stale_latched=true continuous="+frames);s.stop("done");return;}
            Thread.sleep(20);
        }
        throw new AssertionError("Harness deadline: "+s.status());
    }
}
