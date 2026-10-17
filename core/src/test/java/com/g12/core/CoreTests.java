package com.g12.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public final class CoreTests {
    private static int checks;
    private static void ok(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static void rejects(Runnable action, String message) {
        boolean failed = false;
        try { action.run(); } catch (IllegalArgumentException | UnsupportedOperationException expected) { failed = true; }
        ok(failed, message);
    }
    private static ControlSession ready(long now) {
        ControlSession s = new ControlSession(); long e = s.beginSimulation(now);
        s.receive(e, ControlSession.simulatedHeartbeat(0), now);
        s.receive(e, ControlSession.simulatedVersion(1), now);
        s.sample(e, now, now, 0, 0, 0, 0, true, true); return s;
    }
    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(); for (byte v : b) s.append(String.format("%02x", v & 255)); return s.toString();
    }
    private static void codec() throws Exception {
        byte[] frame = Mavlink.manual(255, 190, 7, 1, -1000, 1000, 500, -250);
        Mavlink.Frame decoded = Mavlink.decode(frame); ByteBuffer p = decoded.fields();
        ok(decoded.message == 69 && decoded.system == 255 && decoded.component == 190 && decoded.sequence == 7, "header");
        ok(p.getShort(0) == -1000 && p.getShort(2) == 1000 && p.getShort(4) == 500 && p.getShort(6) == -250 && p.get(10) == 1, "manual mapping");
        for (int i = 1; i < frame.length; i++) {
            byte[] bad = frame.clone(); bad[i] ^= 4;
            rejects(() -> Mavlink.decode(bad), "single-bit mutation " + i);
        }
        rejects(() -> Mavlink.manual(255, 190, 0, 0, 0, 0, 0, 0), "broadcast target");
        rejects(() -> Mavlink.manual(255, 190, 0, 1, Integer.MIN_VALUE, 0, 0, 0), "integer overflow axis");
        rejects(() -> Mavlink.manual(255, 190, 0, 1, 0, 0, -1, 0), "negative throttle");
        byte[] release = Mavlink.release(255, 190, 8, 1, 1);
        p = Mavlink.decode(release).fields();
        for (int i = 0; i < 8; i++) ok((p.getShort(i * 2) & 65535) == (i < 4 ? 0 : 65535), "release/ignore " + i);
        ok(p.get(16) == 1 && p.get(17) == 1 && p.getShort(18) == 0, "release target and absent extensions");
        for (int split = 0; split <= frame.length; split++) {
            Mavlink.Parser parser = new Mavlink.Parser();
            int count = parser.feed(Arrays.copyOf(frame, split), 10).size();
            count += parser.feed(Arrays.copyOfRange(frame, split, frame.length), 11).size();
            ok(count == 1, "all split positions " + split);
        }
        Mavlink.Parser parser = new Mavlink.Parser();
        byte[] joined = new byte[frame.length + release.length + 3]; joined[0] = 42;
        System.arraycopy(frame, 0, joined, 3, frame.length); System.arraycopy(release, 0, joined, 3 + frame.length, release.length);
        ok(parser.feed(joined, 20).size() == 2, "noise and coalesced frames");
        parser = new Mavlink.Parser(); parser.feed(Arrays.copyOf(frame, 9), 0);
        ok(parser.feed(frame, 251).size() == 1, "stalled partial expiry");
        byte[] signed = Arrays.copyOf(frame, frame.length + 13); signed[2] = 1;
        parser = new Mavlink.Parser(); ok(parser.feed(signed, 1).isEmpty(), "signed frame rejected");
        ok(parser.feed(frame, 2).size() == 1, "parser recovers after signed frame");
        Random random = new Random(12);
        for (int i = 0; i < 500; i++) {
            int x = random.nextInt(2001) - 1000, y = random.nextInt(2001) - 1000, z = random.nextInt(1001), r = random.nextInt(2001) - 1000;
            ByteBuffer v = Mavlink.decode(Mavlink.manual(255, 190, i, 1, x, y, z, r)).fields();
            ok(v.getShort(0) == x && v.getShort(2) == y && v.getShort(4) == z && v.getShort(6) == r, "random axis roundtrip");
        }
        Path out = Path.of("artifacts"); Files.createDirectories(out);
        Files.writeString(out.resolve("vectors.json"), "{\"manual\":\"" + hex(frame) + "\",\"release\":\"" + hex(release)
                + "\",\"heartbeat\":\"" + hex(ControlSession.simulatedHeartbeat(0)) + "\",\"version\":\"" + hex(ControlSession.simulatedVersion(1)) + "\"}\n");
    }
    private static void safety() {
        ControlSession s = new ControlSession(); s.tick(0);
        ok(s.state() == ControlSession.State.READ_ONLY && s.frameCount() == 0 && !s.takeOver(0), "read-only boot");
        rejects(VendorAdapter::hardware, "hardware factory blocked");
        s = ready(1000); ok(s.takeOver(1000), "takeover requires health");
        ok(!s.takeOver(1000), "duplicate takeover refused");
        s.tick(1000); s.tick(1049); ok(s.frameCount() == 1, "20Hz cap");
        s.tick(1050); ok(s.frameCount() == 2, "next allowed frame");
        s.tick(1150); ok(s.state() == ControlSession.State.ACTIVE, "freshness inclusive boundary");
        int before = s.frameCount(); s.tick(1151);
        ok(s.state() == ControlSession.State.STOPPED && s.frameCount() == before, "stale input emits no fallback control");
        s.sample(s.generation(), 1152, 1152, 0, 0, 0, 0, true, true); s.tick(1152);
        ok(s.state() == ControlSession.State.STOPPED && !s.takeOver(1152), "no auto-recovery");
        s = ready(2000); ControlSession other = ready(2000);
        ok(s.takeOver(2000) && !other.takeOver(2000), "single process lease");
        s.stop("pause"); ok(other.takeOver(2000), "lease released on stop"); other.stop("done");
        s = ready(3000); s.takeOver(3000); long epoch = s.generation();
        ok(!s.sample(epoch, 3000, 3001, 0, 0, 0, 0, true, true), "replayed source timestamp");
        ok(s.state() == ControlSession.State.STOPPED, "replay latches stop");
        s = ready(4000); epoch = s.generation(); s.takeOver(4000); s.tick(4000);
        s.sample(epoch, 4001, 4001, 0, 0, 0, 0, false, true);
        ok(s.state() == ControlSession.State.STOPPED, "deadman stops");
        s = ready(5000); epoch = s.generation(); s.takeOver(5000); s.tick(4999);
        ok(s.state() == ControlSession.State.STOPPED, "clock rollback");
        s = ready(6000); epoch = s.generation(); s.takeOver(6000);
        s.sample(epoch, 6001, 6001, 0, 0, 1001, 0, true, true);
        ok(s.state() == ControlSession.State.STOPPED, "out of range input");
        s = ready(7000); epoch = s.generation(); s.takeOver(7000);
        for (long now = 7050; now <= 8500; now += 50) { s.sample(epoch, now, now, 0, 0, 0, 0, true, true); s.tick(now); }
        ok(s.state() == ControlSession.State.ACTIVE, "heartbeat boundary");
        before = s.frameCount(); s.sample(epoch, 8501, 8501, 0, 0, 0, 0, true, true); s.tick(8501);
        ok(s.state() == ControlSession.State.STOPPED && before == s.frameCount(), "heartbeat loss");
        s = ready(9000); epoch = s.generation(); s.takeOver(9000);
        s.receive(epoch, Mavlink.encode(2, 1, 0, 0, Mavlink.decode(ControlSession.simulatedHeartbeat(0)).payload()), 9001);
        ok(s.state() == ControlSession.State.STOPPED, "target conflict");
        s = new ControlSession(); epoch = s.beginSimulation(10000);
        byte[] wrongVersion = Mavlink.decode(ControlSession.simulatedVersion(0)).payload(); wrongVersion[16] = 0;
        s.receive(epoch, Mavlink.encode(1, 1, 0, 148, wrongVersion), 10000);
        ok(s.state() == ControlSession.State.STOPPED, "wrong firmware");
        s = new ControlSession(); epoch = s.beginSimulation(11000);
        byte[] wrongUid = Mavlink.decode(ControlSession.simulatedVersion(0)).payload(); wrongUid[8] ^= 1;
        s.receive(epoch, Mavlink.encode(1, 1, 0, 148, wrongUid), 11000);
        ok(s.state() == ControlSession.State.STOPPED, "wrong UID");
        s = ready(12000); epoch = s.generation(); s.beginSimulation(12001);
        ok(!s.sample(epoch, 12002, 12002, 0, 0, 0, 0, true, true), "old generation ignored");
        ok(!s.takeOver(12002), "new epoch needs fresh identity and input"); s.stop("done");
        s = ready(13000); s.takeOver(13000); s.simulateWriteFailure(); s.tick(13000);
        ok(s.state() == ControlSession.State.STOPPED && s.frameCount() == 0, "write fault");
        s = ready(14000); epoch = s.generation(); s.takeOver(14000); s.tick(14000);
        ok(s.requestHandBack(14001), "handback requested"); before = s.frameCount(); s.tick(14050);
        ok(s.frameCount() == before, "no manual traffic during handback");
        ok(s.confirmPrimary(epoch, 14050, 14050, true), "fresh primary confirmation");
        ok(Mavlink.decode(s.lastFrame()).message == Mavlink.OVERRIDE && s.state() == ControlSession.State.SIMULATION_READY, "release then ready");
        ok(!s.confirmPrimary(epoch, 14050, 14050, true) && !s.takeOver(14050), "no repeated release, fresh input needed"); s.stop("done");
        s = ready(15000); epoch = s.generation(); s.takeOver(15000); s.requestHandBack(15050);
        ok(!s.confirmPrimary(epoch, 15049, 15050, true) && s.state() == ControlSession.State.STOPPED, "pre-request confirmation denied");
        s = ready(16000); s.takeOver(16000); s.requestHandBack(16000); s.tick(16151);
        ok(s.state() == ControlSession.State.STOPPED, "handback timeout stops and frees lease");
        s = ready(17000); epoch = s.generation();
        s.sample(epoch, 17001, 17001, 0, 0, 500, 0, true, true);
        ok(!s.takeOver(17001), "high throttle transition blocked"); s.stop("done");
        s = ready(18000); epoch = s.generation(); s.takeOver(18000);
        s.sample(epoch, 18001, 18001, 0, 0, 0, 0, true, false);
        ok(s.state() == ControlSession.State.STOPPED, "source invalid");
    }
    public static void main(String[] args) throws Exception {
        codec(); safety(); System.out.println("PASS: " + checks + " assertions; protocol, fault injection and simulation safety. No hardware accessed.");
    }
}
