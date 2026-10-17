package com.g12.sendercore;

import com.g12.core.Mavlink;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class SenderTests {
    private static int count;
    private static void check(boolean yes, String reason) { count++; if (!yes) throw new AssertionError(reason); }
    private static void rejects(Runnable run, String why) {
        boolean rejected = false;
        try { run.run(); } catch (IllegalArgumentException | IllegalStateException e) { rejected = true; }
        check(rejected, why);
    }
    private static final class Fake implements SendEngine.Clock, SendEngine.Transport {
        long time; int writes, closes; boolean connected = true, accept = true, sync = true;
        byte[] frame; SendEngine.Completion callback;
        @Override public long now() { return time; }
        @Override public boolean connected() { return connected; }
        @Override public boolean offer(byte[] bytes, long deadline, SendEngine.Completion done) {
            check(deadline <= time + SendEngine.WRITE_MS, "bounded write deadline");
            if (!accept) return false;
            writes++; frame = bytes.clone(); callback = done; if (sync) done.returned(true); return true;
        }
        @Override public void close() { closes++; connected = false; }
    }
    private static Mapping config(Mapping.Message message) {
        return new Mapping(message, 245, 191, 7, 2, 115200, 10,
            new Mapping.Axis(3, -400, 0, 600, false, 5),
            new Mapping.Axis(1, 0, 500, 1000, true, 2),
            new Mapping.Axis(4, 800, 1400, 2200, false, 7),
            new Mapping.Axis(2, 100, 400, 1000, false, 4));
    }
    private static final int[] NEUTRAL = {500, 400, 0, 800};
    private static SendEngine started(Fake f, Mapping.Message message) {
        var e = new SendEngine(f); e.sample(NEUTRAL, f.time, 1, true); e.start(config(message), f, true, true, 10); return e;
    }
    private static String hex(byte[] bytes) { var s = new StringBuilder(); for (byte b : bytes) s.append(String.format("%02x", b & 255)); return s.toString(); }
    public static void main(String[] args) throws Exception {
        Mapping manual = config(Mapping.Message.MANUAL_CONTROL), override = config(Mapping.Message.RC_CHANNELS_OVERRIDE);
        check(Arrays.equals(manual.values(NEUTRAL), new int[4]), "explicit non-default mapping and ranges");
        int[] extreme = {0, 1000, 600, 2200};
        check(Arrays.equals(manual.values(extreme), new int[]{1000,1000,1000,1000}), "direction and endpoints");
        byte[] wire=manual.frame(extreme,9);var decoded=Mavlink.decode(wire);wire[0]=0;
        check(decoded.wire()[0]==(byte)0xfd,"exact received wire is immutable");
        rejects(() -> manual.values(new int[]{0,1000,601,2200}), "range rejects, never clamps");
        rejects(() -> manual.values(new int[]{1}), "channel count");
        rejects(() -> new Mapping.Axis(1, 1, 1, 2, false, 1), "zero span");
        rejects(() -> new Mapping(Mapping.Message.MANUAL_CONTROL,0,1,1,1,115200,10), "no broadcast IDs");
        var p = Mavlink.decode(override.frame(extreme, 9)).fields();
        check((p.getShort(0)&65535)==65535 && (p.getShort(8)&65535)==2000 && (p.getShort(12)&65535)==2000, "override only configured channels");
        var release = Mavlink.decode(override.release(10)).fields();
        for (int i=0;i<8;i++) check((release.getShort(i*2)&65535)==(i==4||i==1||i==6||i==3?0:65535), "release preserves unselected "+i);
        rejects(() -> manual.release(0), "no guessed manual release mapping");
        Fake f = new Fake(); SendEngine e = started(f, Mapping.Message.MANUAL_CONTROL);
        e.tick(); var oldDone = f.callback; byte[] first = f.frame.clone();
        check(f.writes==1 && e.sdkReturns()==1 && e.echoed()==0, "SDK return is not wire echo");
        f.time=100; e.sample(NEUTRAL,100,2,true); e.tick(); check(f.writes==1, "one unacknowledged frame only");
        byte[] corrupt=first.clone(); corrupt[10]^=1; e.receive(corrupt); check(e.waiting(), "bad CRC no ack");
        e.receive(Arrays.copyOf(first,5)); e.receive(Arrays.copyOfRange(first,5,first.length));
        check(e.echoed()==1 && !e.waiting(), "fragmented exact echo");
        f.sync=false; e.tick(); check(f.writes==2, "new sample after echo"); oldDone.returned(true);
        check(e.sdkReturns()==1, "prior completion cannot complete current write");
        e.stop("manual"); f.callback.returned(true); e.receive(f.frame); e.tick();
        check(f.writes==2 && e.state()==SendEngine.State.STOPPED, "late callbacks never revive stop");
        Fake stale = new Fake(); var s=started(stale,Mapping.Message.MANUAL_CONTROL); s.tick();
        stale.time=100; s.tick(); stale.time=250; s.tick(); check(s.state()==SendEngine.State.STOPPED && stale.writes==1,"stale input stops, no release");
        Fake blocked=new Fake(); blocked.sync=false; var b=started(blocked,Mapping.Message.MANUAL_CONTROL); b.tick();
        blocked.time=100;b.sample(NEUTRAL,100,2,true);b.tick();blocked.time=200;b.sample(NEUTRAL,200,3,true);b.tick();
        check(b.state()==SendEngine.State.STOPPED,"blocked SDK write deadline");
        Fake timeout=new Fake(); var t=started(timeout,Mapping.Message.MANUAL_CONTROL); t.tick();
        for(int i=1;i<=8;i++){timeout.time=i*100;t.sample(NEUTRAL,timeout.time,i+1,true);t.tick();}
        check(t.state()==SendEngine.State.STOPPED && timeout.writes==1,"silent serial congestion stops without retry");
        Fake busy=new Fake(); busy.accept=false; var q=started(busy,Mapping.Message.MANUAL_CONTROL);q.tick();check(q.state()==SendEngine.State.STOPPED,"offer rejection");
        Fake disconnected=new Fake();var d=started(disconnected,Mapping.Message.MANUAL_CONTROL);disconnected.connected=false;d.tick();check(d.state()==SendEngine.State.STOPPED,"pipeline disconnect");
        Fake frozen=new Fake();var z=started(frozen,Mapping.Message.MANUAL_CONTROL);frozen.time=300;z.tick();check(z.state()==SendEngine.State.STOPPED,"UI/watchdog scheduling gap");
        Fake repeated=new Fake();var r=started(repeated,Mapping.Message.MANUAL_CONTROL);r.sample(NEUTRAL,0,1,true);check(r.state()==SendEngine.State.STOPPED,"duplicate sample fails");
        Fake rcf=new Fake();var rc=started(rcf,Mapping.Message.RC_CHANNELS_OVERRIDE);rc.tick();rc.receive(rcf.frame);rc.releaseAndStop();
        check(rcf.writes==2 && rc.state()==SendEngine.State.RELEASING,"explicit mapped release once");
        rc.receive(rcf.frame);check(rc.state()==SendEngine.State.STOPPED && rc.echoed()==2,"release echo stops, no handback claim");
        Fake one=new Fake();var owner=started(one,Mapping.Message.MANUAL_CONTROL);Fake two=new Fake();var other=new SendEngine(two);other.sample(NEUTRAL,0,1,true);
        rejects(() -> other.start(manual,two,true,true,1),"process duplicate owner");owner.stop("end");
        rejects(() -> other.start(manual,two,false,true,1),"PC isolation confirmation required");
        rejects(() -> other.start(manual,two,true,false,1),"firmware confirmation required");
        Fake high=new Fake();var h=new SendEngine(high);h.sample(extreme,0,1,true);
        rejects(() -> h.start(manual,high,true,true,0),"high throttle/start deflection refused");
        Fake rollback=new Fake();rollback.time=100;var back=started(rollback,Mapping.Message.MANUAL_CONTROL);rollback.time=99;back.tick();
        check(back.state()==SendEngine.State.STOPPED,"clock rollback stops");
        Fake invalid=new Fake();var inv=started(invalid,Mapping.Message.MANUAL_CONTROL);invalid.time=100;inv.sample(new int[]{0,1000,601,2200},100,2,true);inv.tick();
        check(invalid.writes==0 && inv.state()==SendEngine.State.STOPPED,"range violation sends no frame");
        Fake duplicate=new Fake();var dup=started(duplicate,Mapping.Message.MANUAL_CONTROL);dup.tick();
        rejects(() -> dup.start(manual,duplicate,true,true,2),"duplicate start rejected");
        check(dup.state()==SendEngine.State.STOPPED && duplicate.writes==1,"duplicate start stops existing writer");
        Fake releaseBusy=new Fake();var rb=started(releaseBusy,Mapping.Message.RC_CHANNELS_OVERRIDE);rb.tick();
        rejects(rb::releaseAndStop,"busy release rejected");
        check(rb.state()==SendEngine.State.STOPPED && releaseBusy.writes==1,"failed release stops without extra frame");
        Fake manualRelease=new Fake();var mr=started(manualRelease,Mapping.Message.MANUAL_CONTROL);mr.tick();mr.receive(manualRelease.frame);
        rejects(mr::releaseAndStop,"manual release rejected");check(mr.state()==SendEngine.State.STOPPED,"manual release request only stops");
        Fake badTime=new Fake();var bt=started(badTime,Mapping.Message.MANUAL_CONTROL);bt.sample(NEUTRAL,-1,2,true);
        check(bt.state()==SendEngine.State.STOPPED,"negative receipt invalidates session");
        Fake longRun=new Fake();var l=started(longRun,Mapping.Message.MANUAL_CONTROL);
        for(int i=0;i<=100;i++){longRun.time=i*100;if(i>0)l.sample(NEUTRAL,longRun.time,i+1,true);l.tick();if(l.waiting())l.receive(longRun.frame);}
        check(l.state()==SendEngine.State.STOPPED && longRun.writes==100,"10 second finite burst, no seq reuse within session");
        Files.createDirectories(Path.of("artifacts"));
        Files.writeString(Path.of("artifacts/sender-vectors.json"),"{\"manual\":\""+hex(manual.frame(extreme,9))+"\",\"override\":\""+hex(override.frame(extreme,9))+"\",\"release\":\""+hex(override.release(10))+"\"}\n");
        System.out.println("PASS: "+count+" sender assertions (offline mock; no hardware)");
    }
}
