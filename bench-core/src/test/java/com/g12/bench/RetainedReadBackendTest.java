package com.g12.bench;

/** Drives the actual session + connection wrapper, with asynchronous vendor events.
 * This reproduces our disconnect-on-stop call sequence, not the physical G12 timeout.
 */
public final class RetainedReadBackendTest {
    private static int checks;
    private static final class Fake implements ReadOnlySession.Backend, ReadOnlySession.Clock {
        long now; int connects, disconnects, reads;
        ReadOnlySession.Events events; ReadOnlySession.Result result;
        @Override public long millis() { return now; }
        @Override public void start(ReadOnlySession.Events e) { connects++; events = e; }
        @Override public void read(ReadOnlySession.Result r) { reads++; result = r; }
        @Override public void stop() { disconnects++; }
    }
    private static void check(boolean yes, String why) { checks++; if (!yes) throw new AssertionError(why); }
    public static void main(String[] args) {
        Fake f = new Fake(); var backend = new RetainedReadBackend(f); var s = new ReadOnlySession(backend, f);
        s.start(); f.events.connected("G12"); s.tick(); f.result.success(new int[]{1500});
        check(s.samples() == 1, "first capture works");
        s.pause("停止采集");
        check(f.disconnects == 0, "stop/start must retain SDK: old path calls disconnectRC then reconnects");
        check(s.channels() == null && s.samples() == 0, "pause invalidates every displayed sample");
        for (int i = 0; i < 20; i++) {
            s.start(); s.tick();
            check(f.connects == 1 && s.channels() == null, "restart keeps connection, never reuses channel values");
            f.result.success(new int[]{i}); check(s.samples() == 1 && s.channels()[0] == i, "new GET supplies each restart");
            s.pause("停止采集");
        }
        s.start(); s.tick(); var old = f.result; int reads = f.reads;
        s.pause("pending GET"); s.start(); s.tick();
        check(s.state() == ReadOnlySession.State.CONNECTING && f.reads == reads, "restart drains old GET before a new request");
        old.success(new int[]{42});
        check(s.state() == ReadOnlySession.State.READING && s.channels() == null, "old callback cannot populate new capture");
        s.tick(); check(f.reads == reads + 1, "new request after old callback drains");
        old.failure("code=101 Timeout");
        check(s.state() == ReadOnlySession.State.READING, "duplicate old completion cannot abort new read");
        f.result.success(new int[]{77}); check(s.channels()[0] == 77, "fresh completion accepted");
        s.pause("paused signal"); f.events.signal("old"); f.events.quality(10);
        s.start(); check(s.signal() == null && s.quality() == null, "paused signal not replayed");
        s.tick(); var pending = f.result; var events = f.events;
        s.stop("background/full release");
        check(f.disconnects == 1 && s.channels() == null, "background/full release still disconnects");
        s.start(); events.connected("G12"); pending.success(new int[]{88});
        check(s.state() == ReadOnlySession.State.CONNECTING && s.channels() == null, "old connection generation ignored");
        f.events.connected("G12"); s.tick(); f.result.failure("code=101 Timeout");
        check(s.state() == ReadOnlySession.State.STOPPED && s.message().contains("code=101"), "real SDK failure still stops, never swallowed");
        check(f.disconnects == 2, "failure releases SDK");
        s.start(); f.events.connected("G12"); s.tick(); var orphan = f.result;
        s.pause("pause pending"); s.start(); f.now += ReadOnlySession.CONNECT_TIMEOUT_MS; s.tick();
        check(s.state() == ReadOnlySession.State.STOPPED && f.disconnects == 3, "missing old callback bounds restart wait");
        orphan.success(new int[]{99}); check(s.channels() == null, "late timeout cannot restore input");
        s.start(); f.events.connected("G12"); s.pause("pause"); f.events.disconnected("link lost");
        check(f.disconnects == 4 && s.state() == ReadOnlySession.State.STOPPED, "paused link loss closes SDK without auto capture");
        s.start(); check(f.connects == 5, "manual restart after loss creates connection");
        System.out.println("PASS: " + checks + " capture restart assertions (mock SDK, no hardware)");
    }
}
