package com.g12.simulator;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import com.g12.core.ControlSession;
import java.util.Locale;

/** Touch sliders are simulation fixtures, never presented as G12 joystick readings. */
public final class MainActivity extends Activity {
    private final ControlSession session = new ControlSession();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private LinearLayout layout;
    private CheckBox deadman, freeze, linkLoss;
    private SeekBar pitch, roll, throttle, yaw;
    private boolean running;
    private long epoch, lastHeartbeat = -1;
    private int telemetrySequence;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            if (!linkLoss.isChecked() && (lastHeartbeat < 0 || now - lastHeartbeat >= 500)) {
                session.receive(epoch, ControlSession.simulatedHeartbeat(telemetrySequence++), now);
                lastHeartbeat = now;
            }
            if (!freeze.isChecked()) session.sample(epoch, now, now,
                    pitch.getProgress() - 1000, roll.getProgress() - 1000,
                    throttle.getProgress(), yaw.getProgress() - 1000, deadman.isChecked(), true);
            session.tick(now);
            refresh();
            if (session.state() != ControlSession.State.STOPPED) handler.postDelayed(this, 50);
            else running = false;
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(28, 60, 28, 40); scroll.addView(layout); setContentView(scroll);
        text("G12 · 双链路开发台", 26);
        TextView warning = text("仅模拟 / 硬件写出禁用\nSDK 未接入；滑块不是 G12 真摇杆。禁止用于飞行。", 18);
        warning.setTextColor(Color.rgb(155, 30, 30));
        status = text("", 16);
        pitch = axis("模拟俯仰 x", 2000, 1000);
        roll = axis("模拟横滚 y", 2000, 1000);
        throttle = axis("模拟油门 z", 1000, 0);
        yaw = axis("模拟偏航 r", 2000, 1000);
        deadman = check("模拟持续使能（松开即停止）");
        freeze = check("故障：冻结输入（150ms 后停止）");
        linkLoss = check("故障：停止模拟遥测（1500ms 后停止）");
        button("1. 新开模拟会话", view -> {
            handler.removeCallbacks(loop); running = false;
            pitch.setProgress(1000); roll.setProgress(1000); throttle.setProgress(0); yaw.setProgress(1000);
            deadman.setChecked(true); freeze.setChecked(false); linkLoss.setChecked(false);
            long now = SystemClock.elapsedRealtime(); epoch = session.beginSimulation(now);
            session.receive(epoch, ControlSession.simulatedVersion(telemetrySequence++), now);
            lastHeartbeat = -1; running = true; handler.post(loop); refresh();
        });
        button("2. 请求模拟接管", view -> { session.takeOver(SystemClock.elapsedRealtime()); refresh(); });
        button("3. 请求交还", view -> { session.requestHandBack(SystemClock.elapsedRealtime()); refresh(); });
        button("4. 模拟主控已恢复并确认", view -> {
            long now = SystemClock.elapsedRealtime(); session.confirmPrimary(epoch, now, now, true); refresh();
        });
        button("立即停止", view -> halt("Operator stop"));
        text("主：SBUS RC + 串口数据\n备：GR00 串口 MAVLink\n输出仅保存在内存。不会解锁、改参数、切模式或发送心跳。", 15);
        refresh();
    }
    private TextView text(String value, int sp) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setPadding(0, 12, 0, 12);
        layout.addView(t); return t;
    }
    private SeekBar axis(String name, int max, int initial) {
        TextView label = text(name + ": " + (initial == 1000 ? 0 : initial), 16);
        SeekBar bar = new SeekBar(this); bar.setContentDescription(name); bar.setMax(max); bar.setProgress(initial);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean user) { label.setText(name + ": " + (max == 2000 ? p - 1000 : p)); }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { }
        });
        layout.addView(bar); return bar;
    }
    private CheckBox check(String name) { CheckBox c = new CheckBox(this); c.setText(name); layout.addView(c); return c; }
    private void button(String name, View.OnClickListener action) {
        Button b = new Button(this); b.setText(name); b.setOnClickListener(action); layout.addView(b);
    }
    private void refresh() {
        status.setText(String.format(Locale.ROOT, "状态：%s\n%s\n模拟帧：%d · 会话：%d",
                session.state(), session.reason(), session.frameCount(), session.generation()));
    }
    private void halt(String why) { running = false; handler.removeCallbacks(loop); session.stop(why); refresh(); }
    @Override protected void onPause() { halt("Activity paused; explicit restart required"); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); session.stop("Activity destroyed"); super.onDestroy(); }
}
