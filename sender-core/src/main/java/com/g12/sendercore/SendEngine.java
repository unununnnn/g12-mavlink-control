package com.g12.sendercore;

import com.g12.core.Mavlink;
import java.util.Arrays;

/** PC-capture-only, single-link bounded sender. A byte echo is NOT a flight-controller ACK.
 * All entrypoints synchronized; transport methods must be nonblocking. One unacknowledged frame.
 */
public final class SendEngine {
    public interface Clock { long now(); }
    public interface Transport {
        boolean connected();
        boolean offer(byte[] frame, long deadlineMillis, Completion completion);
        void close();
    }
    public interface Completion { void returned(boolean ok); }
    public enum State { STOPPED, SENDING, RELEASING }
    public static final long INPUT_MS = 250, WRITE_MS = 200, ECHO_MS = 800, BURST_MS = 10000;
    private static SendEngine owner; // process guard only, never claimed to be cross-device arbitration
    private final Clock clock;
    private final Mavlink.Parser parser = new Mavlink.Parser();
    private Transport link;
    private Mapping mapping;
    private State state = State.STOPPED;
    private long epoch, writeId, started, lastTick = -1, inputAt = -1, sampleId = -1, usedSample = -1, sentAt, lastSend = -1;
    private int[] raw;
    private int sequence, offered, sdkReturns, echoed;
    private boolean writeReturned, echoReceived, inputConnected;
    private byte[] awaiting;
    private String status = "默认停止；仅电脑串口回显台架";
    public SendEngine(Clock clock) { this.clock = clock; }

    /** Timestamp must be the actual local receipt time, never refreshed when reusing cached values. */
    public synchronized void sample(int[] values, long receiptMillis, long id, boolean connected) {
        long now = clock.now();
        if (!connected || values == null || receiptMillis < 0 || id < 1 || receiptMillis > now || (sampleId >= 0 && id <= sampleId)
                || (inputAt >= 0 && receiptMillis < inputAt)) {
            stop("输入断开/重复/时钟无效");
            return;
        }
        raw = values.clone(); inputAt = receiptMillis; sampleId = id; inputConnected = true;
    }
    public synchronized void start(Mapping config, Transport transport, boolean pcDisconnectedConfirmed, boolean firmwareConfirmed, int initialSequence) {
        if (state != State.STOPPED) {
            stop("重复启动请求：已停止，须重新打开管道并手动开始");
            throw new IllegalStateException("禁止重复启动，当前发送已停止");
        }
        long now = clock.now();
        if (!pcDisconnectedConfirmed || !firmwareConfirmed) throw new IllegalArgumentException("须确认仅接电脑、飞控断开和20250110+数传系统");
        if (config == null || transport == null || !transport.connected() || !inputConnected || raw == null
                || now < inputAt || now - inputAt >= INPUT_MS || !config.neutral(raw))
            throw new IllegalArgumentException("连接/原始输入未就绪，或未处于中位低油门");
        synchronized (SendEngine.class) {
            if (owner != null && owner != this) throw new IllegalStateException("本进程已有发送者");
            owner = this;
        }
        mapping = config; link = transport; epoch++; state = State.SENDING; started = lastTick = now;
        usedSample = -1; lastSend = -1; sequence = initialSequence & 255;
        offered = sdkReturns = echoed = 0; awaiting = null; parser.clear();
        status = "发送中：等待电脑逐帧回显；不是飞控接受确认";
    }
    public synchronized void tick() {
        if (state == State.STOPPED) return;
        long now = clock.now();
        if (now < lastTick || now - lastTick >= INPUT_MS) { stop("调度暂停/时钟异常"); return; }
        lastTick = now;
        if (!link.connected()) { stop("管道断开"); return; }
        if (!inputConnected || raw == null || now < inputAt || now - inputAt >= INPUT_MS) { stop("本地输入回调过期；不发送缓存值"); return; }
        if (now - started >= BURST_MS) { stop("10秒台架窗口结束，须手动重新开始"); return; }
        if (awaiting != null) {
            if (!writeReturned && now - sentAt >= WRITE_MS) stop("SDK调用超时/拥塞；已锁存停止");
            else if (now - sentAt >= ECHO_MS) stop("电脑串口回显超时；不重发，不自动恢复");
            return;
        }
        if (state == State.RELEASING || usedSample == sampleId || (lastSend >= 0 && now - lastSend < 1000 / mapping.rateHz)) return;
        try { submit(mapping.frame(raw, sequence++), now); usedSample = sampleId; }
        catch (IllegalArgumentException e) { stop(e.getMessage()); }
    }
    private void submit(byte[] bytes, long now) {
        final long token = epoch;
        final long request = ++writeId;
        awaiting = bytes.clone(); sentAt = lastSend = now; writeReturned = echoReceived = false; offered++;
        boolean accepted;
        try {
            accepted = link.offer(bytes.clone(), Math.min(inputAt + INPUT_MS, now + WRITE_MS), ok -> {
                synchronized (SendEngine.this) {
                    if (token != epoch || request != writeId || state == State.STOPPED || awaiting == null || writeReturned) return;
                    if (!ok || clock.now() - sentAt >= WRITE_MS) { stop("SDK调用失败/超时"); return; }
                    writeReturned = true; sdkReturns++; completeIfBoth();
                }
            });
        } catch (RuntimeException e) { accepted = false; }
        if (!accepted && token == epoch) stop("本地发送工作线程忙/拒绝，禁止积压");
    }
    public synchronized void receive(byte[] bytes) {
        if (state == State.STOPPED || bytes == null || bytes.length == 0 || bytes.length > 4096) return;
        long now = clock.now();
        if (awaiting != null && (now < sentAt || now - sentAt >= ECHO_MS)) { stop("迟到回显不能恢复发送"); return; }
        for (Mavlink.Frame frame : parser.feed(bytes, now)) {
            if (awaiting != null && Arrays.equals(awaiting, frame.wire())) {
                echoReceived = true; completeIfBoth();
            }
        }
    }
    private void completeIfBoth() {
        if (!writeReturned || !echoReceived) return;
        echoed++; awaiting = null;
        if (state == State.RELEASING) stop("仅映射RC通道的释放帧已获电脑回显；不证明主控恢复");
    }
    public synchronized void releaseAndStop() {
        if (state != State.SENDING) throw new IllegalStateException("当前未在发送");
        if (awaiting != null || mapping.message != Mapping.Message.RC_CHANNELS_OVERRIDE) {
            stop("释放条件不满足：仅停止，未发送释放帧");
            throw new IllegalStateException("有在途帧或非RC模式，已停止；未发送释放");
        }
        long now = clock.now();
        if (now < lastTick || now - lastTick >= INPUT_MS || !link.connected() || !inputConnected || raw == null
                || now < inputAt || now - inputAt >= INPUT_MS || now - started >= BURST_MS) {
            stop("释放前条件失效，只停止；未发送释放"); return;
        }
        state = State.RELEASING;
        submit(mapping.release(sequence++), clock.now());
    }
    public synchronized void stop(String reason) {
        epoch++; state = State.STOPPED; awaiting = null; raw = null; inputConnected = false;
        inputAt = sampleId = -1; parser.clear(); status = reason;
        Transport previous = link; link = null;
        synchronized (SendEngine.class) { if (owner == this) owner = null; }
        if (previous != null) {
            try { previous.close(); } catch (RuntimeException e) { status += "；关闭请求异常"; }
        }
    }
    public synchronized State state() { return state; }
    public synchronized String status() { return status; }
    public synchronized int offered() { return offered; }
    public synchronized int sdkReturns() { return sdkReturns; }
    public synchronized int echoed() { return echoed; }
    public synchronized boolean waiting() { return awaiting != null; }
    public synchronized boolean canRelease() { return state == State.SENDING && awaiting == null && mapping.message == Mapping.Message.RC_CHANNELS_OVERRIDE; }
}
