package com.g12.bench;

/** Single-threaded read-only session. Android backend marshals every event to the UI thread.
 * Callback receipt freshness is diagnostic only: no claim about source hardware sample age.
 */
public final class ReadOnlySession {
    public interface Clock { long millis(); }
    public interface Result { void success(int[] values); void failure(String reason); }
    public interface Events {
        void connected(String deviceType);
        void disconnected(String reason);
        void signal(String raw);
        void quality(Integer value);
        void warning(String text);
    }
    public interface Backend {
        void start(Events events);
        void read(Result result);
        void stop();
    }
    public enum State { IDLE, CONNECTING, READING, STOPPED }
    private final Backend backend;
    private final Clock clock;
    private long epoch, started, pendingAt, nextRead;
    private boolean pending;
    private State state = State.IDLE;
    private String message = "尚未启动真实 SDK", device = "未识别", signal, warning = "";
    private Integer quality;
    private int[] channels;
    private long receivedAt = -1, signalAt = -1, qualityAt = -1, samples;
    public static final long POLL_MS = 100, STALE_MS = 500, REQUEST_TIMEOUT_MS = 1000,
            CONNECT_TIMEOUT_MS = 5000, SIGNAL_STALE_MS = 2500;

    public ReadOnlySession(Backend backend, Clock clock) { this.backend = backend; this.clock = clock; }
    public void start() {
        if (state == State.CONNECTING || state == State.READING) return;
        final long token = ++epoch;
        clear(); device = "未识别"; started = clock.millis(); state = State.CONNECTING;
        message = "正在连接遥控器 SDK";
        try {
            backend.start(new Events() {
                @Override public void connected(String type) {
                    if (token != epoch || state != State.CONNECTING) return;
                    device = type;
                    if (!"G12".equals(type)) { stop("SDK 识别为 " + type + "，仅允许 G12 只读采集"); return; }
                    state = State.READING; nextRead = clock.millis(); message = "G12 SDK 已连接，等待真实通道";
                }
                @Override public void disconnected(String reason) { if (token == epoch) stop(reason); }
                @Override public void signal(String raw) {
                    if (token != epoch || state != State.READING) return;
                    signal = raw == null ? null : raw.substring(0, Math.min(4096, raw.length()));
                    signalAt = clock.millis();
                }
                @Override public void quality(Integer value) {
                    if (token != epoch || state != State.READING) return;
                    quality = value; qualityAt = clock.millis();
                }
                @Override public void warning(String text) { if (token == epoch) warning = text; }
            });
        } catch (RuntimeException | LinkageError e) { stop("SDK 启动失败: " + e.getClass().getSimpleName()); }
    }
    public void tick() {
        long now = clock.millis();
        if (state == State.CONNECTING && now - started >= CONNECT_TIMEOUT_MS) { stop("SDK 连接超时，请检查系统/端口占用后手动重试"); return; }
        if (state != State.READING) return;
        if (pending && now - pendingAt >= REQUEST_TIMEOUT_MS) { stop("通道请求超时，采集已停止；请手动重试"); return; }
        if (pending || now < nextRead) return;
        final long token = epoch;
        pending = true; pendingAt = now;
        try {
            backend.read(new Result() {
                private boolean completed;
                @Override public void success(int[] values) {
                    if (completed || token != epoch || state != State.READING || !pending) return;
                    completed = true; pending = false;
                    if (values == null || values.length == 0 || values.length > 64) { stop("SDK 返回无效通道数组"); return; }
                    channels = values.clone(); receivedAt = clock.millis(); samples++;
                    nextRead = receivedAt + POLL_MS; message = "正在读取真实通道（只读）";
                }
                @Override public void failure(String reason) {
                    if (completed || token != epoch || state != State.READING || !pending) return;
                    completed = true; stop("通道读取失败: " + reason);
                }
            });
        } catch (RuntimeException | LinkageError e) { stop("通道读取异常: " + e.getClass().getSimpleName()); }
    }
    public void stop(String reason) {
        epoch++; state = State.STOPPED; clear(); message = reason;
        try { backend.stop(); } catch (RuntimeException | LinkageError e) { warning = "SDK 断开异常: " + e.getClass().getSimpleName(); }
    }
    private void clear() {
        pending = false; channels = null; signal = null; quality = null; warning = "";
        receivedAt = signalAt = qualityAt = -1; samples = 0;
    }
    public State state() { return state; }
    public String message() { return message; }
    public String device() { return device; }
    public String warning() { return warning; }
    public int[] channels() { return channels == null ? null : channels.clone(); }
    public long age() { return receivedAt < 0 ? -1 : Math.max(0, clock.millis() - receivedAt); }
    public boolean stale() { return age() < 0 || age() >= STALE_MS; }
    public long samples() { return samples; }
    public long receiptMillis() { return receivedAt; }
    public String signal() { return signal; }
    public long signalAge() { return signalAt < 0 ? -1 : Math.max(0, clock.millis() - signalAt); }
    public Integer quality() { return quality; }
    public long qualityAge() { return qualityAt < 0 ? -1 : Math.max(0, clock.millis() - qualityAt); }
}
