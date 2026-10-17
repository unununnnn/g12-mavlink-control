package com.g12.core;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/** Simulation-only safety model. There is intentionally no physical transport injection. */
public final class ControlSession {
    public enum State { READ_ONLY, SIMULATION_READY, ACTIVE, HANDING_BACK, STOPPED }
    private static final AtomicReference<ControlSession> OWNER = new AtomicReference<>();
    public static final int TARGET_SYS = 1, TARGET_COMP = 1, SOURCE_SYS = 255, SOURCE_COMP = 190;
    public static final long SIM_UID = 0x12345678L;
    private State state = State.READ_ONLY;
    private String reason = "Read only; hardware disabled";
    private long generation, clock = -1, heartbeat = -1, input = -1, sent = -1, handBackAt = -1;
    private boolean version, deadman, connected;
    private int pitch, roll, throttle, yaw, sequence, count;
    private byte[] lastFrame = new byte[0];
    private boolean failNextWrite;
    private final Mavlink.Parser parser = new Mavlink.Parser();

    public synchronized State state() { return state; }
    public synchronized String reason() { return reason; }
    public synchronized long generation() { return generation; }
    public synchronized int frameCount() { return count; }
    public synchronized byte[] lastFrame() { return lastFrame.clone(); }

    private boolean time(long now) {
        if (now < 0 || (clock >= 0 && now < clock)) { stop("Monotonic clock regressed"); return false; }
        clock = now; return true;
    }
    private static boolean fresh(long timestamp, long now, long max) {
        return timestamp >= 0 && now >= timestamp && now - timestamp <= max;
    }
    public synchronized long beginSimulation(long now) {
        stop("Reset"); generation++; parser.clear();
        clock = -1; heartbeat = input = sent = -1;
        version = deadman = false; connected = true; count = sequence = 0;
        lastFrame = new byte[0]; failNextWrite = false;
        if (time(now)) { state = State.SIMULATION_READY; reason = "Simulation only"; }
        return generation;
    }
    public synchronized void receive(long epoch, byte[] bytes, long now) {
        if (epoch != generation || !time(now) || !connected || state == State.STOPPED
                || state == State.READ_ONLY) return;
        for (Mavlink.Frame frame : parser.feed(bytes, now)) {
            if (frame.message != Mavlink.HEARTBEAT && frame.message != Mavlink.VERSION) continue;
            if (frame.system != TARGET_SYS || frame.component != TARGET_COMP) {
                stop("Unexpected target identity"); return;
            }
            ByteBuffer p = frame.fields();
            if (frame.message == Mavlink.HEARTBEAT) {
                if (p.get(4) != 2 || p.get(5) != 3 || p.get(8) != 3) {
                    stop("Not expected ArduPilot quadrotor"); return;
                }
                heartbeat = now;
            } else {
                long uid = p.getLong(8); int flightVersion = p.getInt(16);
                // 4.7.1 official release (type 255). Custom X9 builds require separate approval.
                if (uid != SIM_UID || flightVersion != 0x040701ff) {
                    stop("UID or firmware mismatch"); return;
                }
                version = true;
            }
        }
    }
    public synchronized boolean sample(long epoch, long sourceTime, long now,
                                       int x, int y, int z, int r, boolean held, boolean valid) {
        if (epoch != generation) return false; // Late callback from a closed adapter/session.
        if (!time(now) || state == State.READ_ONLY || state == State.STOPPED) return false;
        if (!valid || !held || !fresh(sourceTime, now, 150) || sourceTime <= input
                || x < -1000 || x > 1000 || y < -1000 || y > 1000
                || z < 0 || z > 1000 || r < -1000 || r > 1000) {
            stop("Invalid/stale input or deadman released"); return false;
        }
        input = sourceTime; pitch = x; roll = y; throttle = z; yaw = r; deadman = held;
        return true;
    }
    private boolean healthy(long now) {
        return connected && version && fresh(heartbeat, now, 1500)
                && fresh(input, now, 150) && deadman;
    }
    public synchronized boolean takeOver(long now) {
        if (!time(now) || state != State.SIMULATION_READY || !healthy(now)) return false;
        // Neutral simulated axes + low throttle required at the transition.
        if (Math.abs(pitch) > 50 || Math.abs(roll) > 50 || Math.abs(yaw) > 50 || throttle > 50)
            return false;
        if (!OWNER.compareAndSet(null, this)) { reason = "Another simulation owns control"; return false; }
        state = State.ACTIVE; reason = "Simulated control active"; return true;
    }
    public synchronized void tick(long now) {
        if (!time(now) || (state != State.ACTIVE && state != State.HANDING_BACK)) return;
        if (!healthy(now)) { stop("Input/link expired; no automatic recovery"); return; }
        if (OWNER.get() != this) { stop("Ownership lost"); return; }
        if (state == State.HANDING_BACK) return;
        if (sent >= 0 && now - sent < 50) return;
        write(Mavlink.manual(SOURCE_SYS, SOURCE_COMP, sequence++, TARGET_SYS, pitch, roll, throttle, yaw));
        if (state == State.ACTIVE) sent = now;
    }
    private void write(byte[] bytes) {
        if (failNextWrite) { failNextWrite = false; stop("Simulated transport failure"); return; }
        // The only sink: a defensive copy in memory. No socket, serial, SDK or file write.
        lastFrame = bytes.clone(); count++;
    }
    public synchronized boolean requestHandBack(long now) {
        if (!time(now) || state != State.ACTIVE) return false;
        if (!healthy(now)) { stop("Cannot hand back with stale state"); return false; }
        state = State.HANDING_BACK; handBackAt = now;
        reason = "Await current-session primary confirmation"; return true;
    }
    public synchronized boolean confirmPrimary(long epoch, long observedAt, long now, boolean validPrimary) {
        if (epoch != generation || !time(now) || state != State.HANDING_BACK) return false;
        if (!validPrimary || observedAt < handBackAt || !fresh(observedAt, now, 100) || !healthy(now)) {
            stop("Primary confirmation invalid"); return false;
        }
        write(Mavlink.release(SOURCE_SYS, SOURCE_COMP, sequence++, TARGET_SYS, TARGET_COMP));
        OWNER.compareAndSet(this, null);
        if (state == State.STOPPED) return false;
        state = State.SIMULATION_READY; input = -1; deadman = false;
        reason = "Simulated hand-back; real RC recovery not established"; return true;
    }
    public synchronized void stop(String why) {
        OWNER.compareAndSet(this, null);
        state = State.STOPPED; reason = why; connected = version = deadman = false;
        heartbeat = input = -1; parser.clear();
    }
    public synchronized void simulateWriteFailure() { failNextWrite = true; }

    /** Synthetic fixtures only; no receive timestamp or validity is claimed for hardware. */
    public static byte[] simulatedHeartbeat(int seq) {
        byte[] p = new byte[9]; p[4] = 2; p[5] = 3; p[7] = 3; p[8] = 3;
        return Mavlink.encode(TARGET_SYS, TARGET_COMP, seq, Mavlink.HEARTBEAT, p);
    }
    public static byte[] simulatedVersion(int seq) {
        byte[] p = new byte[60]; ByteBuffer b = Mavlink.little(p);
        b.putLong(8, SIM_UID); b.putInt(16, 0x040701ff);
        return Mavlink.encode(TARGET_SYS, TARGET_COMP, seq, Mavlink.VERSION, p);
    }
}
