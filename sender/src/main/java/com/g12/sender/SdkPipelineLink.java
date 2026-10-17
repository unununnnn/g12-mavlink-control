package com.g12.sender;

import android.os.SystemClock;
import com.g12.sendercore.SendEngine;
import com.skydroid.rcsdk.PipelineManager;
import com.skydroid.rcsdk.comm.CommListener;
import com.skydroid.rcsdk.common.error.SkyException;
import com.skydroid.rcsdk.common.pipeline.Pipeline;
import com.skydroid.rcsdk.common.pipeline.PipelineType;
import java.io.File;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses the actual vendor G12 pipeline. No custom sockets, shell execution or parameter writes. */
public final class SdkPipelineLink implements SendEngine.Transport {
    private static volatile boolean blockedWorkerSeen;
    private static final AtomicBoolean pipelineOwner = new AtomicBoolean();
    private final SendEngine engine;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>());
    private volatile Pipeline pipeline;
    private volatile boolean active, ready;
    private volatile boolean ownsPipeline;
    private volatile String status = "未打开";
    private long openedAt;
    public SdkPipelineLink(SendEngine engine) { this.engine = engine; }
    public void open() {
        if (active) throw new IllegalStateException("管道已打开");
        if (blockedWorkerSeen) throw new IllegalStateException("上次SDK调用未完成，须结束应用进程后检查硬件");
        // The vendor factory otherwise installs/executes a fallback helper; deliberately refuse it.
        if (!new File("/system/bin/data_stream").isFile()) throw new IllegalStateException("缺少系统data_stream服务文件；本版不安装SDK辅助程序，请核实厂商系统构建");
        if (!pipelineOwner.compareAndSet(false,true)) throw new IllegalStateException("旧SDK管道仍在关闭，等待后手动重试");
        ownsPipeline = true;
        active = true; ready = false; openedAt = SystemClock.elapsedRealtime(); status = "正在连接SDK数据管道";
        try {
            Pipeline created = PipelineManager.INSTANCE.createG12G20Pipeline();
            if (created == null || created.getPipelineType() != PipelineType.UDP) throw new IllegalStateException("SDK返回非预期G12 UDP管道");
            pipeline = created;
            created.setOnCommListener(new CommListener() {
                @Override public void onConnectSuccess() { if (active) { ready = true; status = "SDK管道已连接；串口到达仍需电脑回显"; } }
                @Override public void onConnectFail(SkyException error) { fail("SDK管道连接失败"); }
                @Override public void onDisconnect() { fail("SDK管道断开，禁止自动恢复发送"); }
                @Override public void onReadData(byte[] bytes) { if (active && bytes != null) engine.receive(bytes.clone()); }
            });
            PipelineManager.INSTANCE.connectPipeline(created);
        } catch (RuntimeException | LinkageError e) { close(); throw e; }
    }
    public void checkConnectTimeout() {
        if (active && !ready && SystemClock.elapsedRealtime() - openedAt >= 5000) fail("SDK管道连接超时");
    }
    private void fail(String reason) { status = reason; ready = false; engine.stop(reason); close(); }
    @Override public boolean connected() {
        // The independent watchdog never enters vendor locks. Writer checks SDK state itself.
        return active && ready && pipeline != null;
    }
    @Override public boolean offer(byte[] frame, long deadlineMillis, SendEngine.Completion completion) {
        if (!connected() || !busy.compareAndSet(false,true)) return false;
        byte[] owned = frame.clone(); Pipeline current = pipeline;
        try {
            writer.execute(() -> {
                boolean ok = false;
                try {
                    if (active && current == pipeline && connected() && current.isConnected() && SystemClock.elapsedRealtime() < deadlineMillis) {
                        current.writeData(owned); // void: only SDK return, never a serial/flight-controller acknowledgement
                        ok = active && SystemClock.elapsedRealtime() < deadlineMillis;
                    }
                } catch (RuntimeException | LinkageError e) { ok = false; }
                finally { busy.set(false); completion.returned(ok); }
            });
            return true;
        } catch (RejectedExecutionException e) { busy.set(false); return false; }
    }
    @Override public void close() {
        active = ready = false;
        if (!closeStarted.compareAndSet(false,true)) return;
        if (busy.get()) blockedWorkerSeen = true;
        writer.shutdownNow();
        Pipeline previous = pipeline; pipeline = null;
        if (previous != null) {
            // Teardown may block inside vendor code. Gate is already closed before this thread starts.
            Thread closer = new Thread(() -> {
                try { PipelineManager.INSTANCE.disconnectPipeline(previous); }
                catch (RuntimeException | LinkageError ignored) { blockedWorkerSeen = true; status = "SDK关闭异常，须结束应用进程检查"; }
                finally { if (ownsPipeline) { ownsPipeline = false; pipelineOwner.set(false); } }
            }, "g12-close");
            closer.setDaemon(true); closer.start();
        } else if (ownsPipeline) { ownsPipeline = false; pipelineOwner.set(false); }
    }
    public String status() { return active ? status : "已停止 / " + status; }
}
