package com.g12.bench;

/** Foreground capture lifetime, separate from the physical SDK connection.
 * All calls run on the UI thread. Paused signal values are discarded. A pending
 * GET must finish before a later capture can request the same key again.
 */
public final class RetainedReadBackend implements ReadOnlySession.Backend {
    private final ReadOnlySession.Backend sdk;
    private ReadOnlySession.Events active;
    private String device;
    private boolean started, announced, pending;
    private long connection, request;
    public RetainedReadBackend(ReadOnlySession.Backend sdk) { this.sdk = sdk; }
    @Override public void start(ReadOnlySession.Events events) {
        if (active != null) throw new IllegalStateException("capture already active");
        active = events; announced = false;
        if (started) { ready(); return; }
        started = true;
        final long generation = ++connection;
        sdk.start(new ReadOnlySession.Events() {
            private boolean current() { return started && generation == connection; }
            @Override public void connected(String type) {
                if (!current()) return;
                device = type; ready();
            }
            @Override public void disconnected(String reason) {
                if (!current()) return;
                ReadOnlySession.Events target = active;
                try { stop(); } finally { if (target != null) target.disconnected(reason); }
            }
            @Override public void signal(String raw) { if (current() && announced && active != null) active.signal(raw); }
            @Override public void quality(Integer value) { if (current() && announced && active != null) active.quality(value); }
            @Override public void warning(String text) { if (current() && active != null) active.warning(text); }
        });
    }
    private void ready() {
        if (active == null || announced || device == null) return;
        if (pending) { active.warning("等待上一轮读取请求结束；不会沿用旧通道值"); return; }
        announced = true; active.connected(device);
    }
    @Override public void read(ReadOnlySession.Result result) {
        if (active == null || !announced || pending) throw new IllegalStateException("capture not ready");
        pending = true;
        final long generation = connection, id = ++request;
        final ReadOnlySession.Events recipient = active;
        sdk.read(new ReadOnlySession.Result() {
            private boolean complete() {
                if (!started || generation != connection || id != request || !pending) return false;
                pending = false;
                return true;
            }
            @Override public void success(int[] values) {
                if (!complete()) return;
                if (active == recipient && announced) result.success(values);
                ready();
            }
            @Override public void failure(String reason) {
                if (!complete()) return;
                if (active == recipient && announced) result.failure(reason);
                ready();
            }
        });
    }
    @Override public void pause() {
        active = null; announced = false;
        // Keep foreground SDK transport. Pending result belongs to its old recipient.
    }
    @Override public void stop() {
        boolean release = started;
        connection++; request++;
        active = null; device = null; announced = false; pending = false; started = false;
        if (release) sdk.stop();
    }
}
