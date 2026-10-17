package com.g12.bench;

/** Contract regressions with a controllable asynchronous SDK boundary, no device/network. */
public final class ReadOnlySessionTest {
    private static int checks;
    private static final class Fake implements ReadOnlySession.Backend, ReadOnlySession.Clock {
        long now; int reads, stops; ReadOnlySession.Events events; ReadOnlySession.Result result;
        @Override public long millis() { return now; }
        @Override public void start(ReadOnlySession.Events e) { events = e; }
        @Override public void read(ReadOnlySession.Result r) { reads++; result = r; }
        @Override public void stop() { stops++; }
    }
    private static void check(boolean test, String reason) { checks++; if (!test) throw new AssertionError(reason); }
    private static ReadOnlySession connected(Fake f) { var s = new ReadOnlySession(f, f); s.start(); f.events.connected("G12"); return s; }
    public static void main(String[] args) {
        Fake f = new Fake(); var s = connected(f);
        s.tick(); s.tick(); check(f.reads == 1, "only one request in flight");
        int[] values = {1000, 1500, 2000}; f.result.success(values); values[0] = 99;
        check(s.channels()[0] == 1000, "clone SDK memory"); s.channels()[0] = 8;
        check(s.channels()[0] == 1000, "clone public snapshot");
        check(s.samples() == 1 && !s.stale(), "local receipt sample");
        f.result.success(new int[]{7}); check(s.samples() == 1, "duplicate callback ignored");
        f.now = 99; s.tick(); check(f.reads == 1, "bounded rate");
        f.now = 100; s.tick(); check(f.reads == 2, "next request after 100ms");
        var delayed = f.result; var oldEvents = f.events;
        f.now = 500; check(s.stale(), "old values visibly stale");
        f.now = 1100; s.tick(); check(s.state() == ReadOnlySession.State.STOPPED && s.channels() == null, "timeout clears values");
        delayed.success(new int[]{42}); check(s.channels() == null, "late timeout callback ignored");
        s.start(); f.events.connected("G12"); oldEvents.disconnected("old"); oldEvents.signal("old");
        check(s.state() == ReadOnlySession.State.READING && s.signal() == null, "old session events ignored");
        s.tick(); f.result.success(new int[]{1}); s.stop("background");
        check(s.channels() == null && s.signal() == null && f.stops == 2, "lifecycle closes and clears");
        f.events.connected("G12"); s.tick(); check(s.state() == ReadOnlySession.State.STOPPED, "no auto reconnect");
        Fake wrong = new Fake(); var w = new ReadOnlySession(wrong, wrong); w.start(); wrong.events.connected("G20"); w.tick();
        check(w.state() == ReadOnlySession.State.STOPPED && wrong.reads == 0, "wrong device refused");
        Fake never = new Fake(); var n = new ReadOnlySession(never, never); n.start(); never.now = 5000; n.tick();
        check(n.state() == ReadOnlySession.State.STOPPED, "connect timeout");
        Fake empty = new Fake(); var e = connected(empty); e.tick(); empty.result.success(new int[0]);
        check(e.state() == ReadOnlySession.State.STOPPED, "empty data rejected");
        Fake fail = new Fake(); var a = connected(fail); a.tick(); fail.result.failure("bad");
        check(a.state() == ReadOnlySession.State.STOPPED && a.channels() == null, "failure stops");
        Fake sig = new Fake(); var b = connected(sig); sig.events.signal("{\"dev_connect\":true}"); sig.events.quality(7);
        check(b.signalAge() == 0 && b.quality() == 7, "signal independent of channel reads");
        sig.now = 2600; check(b.signalAge() > ReadOnlySession.SIGNAL_STALE_MS, "signal staleness measurable");
        sig.events.disconnected("lost"); check(b.signal() == null && b.quality() == null, "disconnect clears all link values");
        check(b.state() == ReadOnlySession.State.STOPPED, "lost link stops");
        System.out.println("PASS: " + checks + " read-only session assertions (mock SDK, no hardware)");
    }
}
