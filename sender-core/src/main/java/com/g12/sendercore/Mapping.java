package com.g12.sendercore;

import com.g12.core.Mavlink;
import java.nio.ByteBuffer;
import java.util.HashSet;

/** Explicit, immutable bench mapping. No inferred transmitter stick mode or calibration. */
public final class Mapping {
    public enum Message { MANUAL_CONTROL, RC_CHANNELS_OVERRIDE }
    public static final class Axis {
        public final int channel, min, center, max, outputChannel;
        public final boolean reversed;
        public Axis(int channel, int min, int center, int max, boolean reversed, int outputChannel) {
            if (channel < 1 || channel > 64 || min < -100000 || max > 100000 || !(min < center && center < max))
                throw new IllegalArgumentException("CH必须1..64，原始量程必须 min < center < max（±100000内）");
            if (outputChannel < 1 || outputChannel > 8) throw new IllegalArgumentException("目标RC通道必须1..8");
            this.channel = channel; this.min = min; this.center = center; this.max = max;
            this.reversed = reversed; this.outputChannel = outputChannel;
        }
        int normalized(int[] raw, boolean throttle) {
            if (raw == null || channel > raw.length) throw new IllegalArgumentException("输入通道数量不匹配");
            int value = raw[channel - 1];
            if (value < min || value > max) throw new IllegalArgumentException("CH" + channel + "超出确认的原始量程");
            double x = throttle ? 1000.0 * (value - min) / (max - min)
                    : (value >= center ? 1000.0 * (value - center) / (max - center) : 1000.0 * (value - center) / (center - min));
            int result = (int) Math.round(x);
            return reversed ? (throttle ? 1000 - result : -result) : result;
        }
    }
    public final Message message;
    public final int sourceSystem, sourceComponent, targetSystem, targetComponent, baud, rateHz;
    private final Axis[] axes; // roll, pitch, throttle, yaw
    public Mapping(Message message, int sourceSystem, int sourceComponent, int targetSystem, int targetComponent,
                   int baud, int rateHz, Axis... axes) {
        for (int id : new int[]{sourceSystem, sourceComponent, targetSystem, targetComponent})
            if (id < 1 || id > 255) throw new IllegalArgumentException("MAVLink ID必须1..255，禁止广播");
        if (message == null || axes.length != 4 || baud < 9600 || baud > 2000000 || rateHz < 1 || rateHz > 10)
            throw new IllegalArgumentException("须选四个轴、明确波特率9600..2000000与速率1..10Hz");
        var input = new HashSet<Integer>(); var output = new HashSet<Integer>();
        for (Axis axis : axes) {
            if (axis == null || !input.add(axis.channel)) throw new IllegalArgumentException("四个轴不能重复使用输入通道");
            if (message == Message.RC_CHANNELS_OVERRIDE && !output.add(axis.outputChannel))
                throw new IllegalArgumentException("RC输出通道不能重复");
        }
        // Worst frame 30 bytes each way, 8N1: <=25% of declared UART capacity.
        if (rateHz * 30 * 10 * 2 > baud / 4) throw new IllegalArgumentException("发送速率超过保守串口预算，请降低Hz");
        this.message = message; this.sourceSystem = sourceSystem; this.sourceComponent = sourceComponent;
        this.targetSystem = targetSystem; this.targetComponent = targetComponent; this.baud = baud; this.rateHz = rateHz;
        this.axes = axes.clone();
    }
    public int[] values(int[] raw) {
        int[] values = new int[4];
        for (int i = 0; i < 4; i++) values[i] = axes[i].normalized(raw, i == 2);
        return values;
    }
    public boolean neutral(int[] raw) {
        int[] v = values(raw);
        return Math.abs(v[0]) <= 50 && Math.abs(v[1]) <= 50 && v[2] <= 50 && Math.abs(v[3]) <= 50;
    }
    public byte[] frame(int[] raw, int sequence) {
        int[] v = values(raw);
        if (message == Message.MANUAL_CONTROL)
            return Mavlink.manual(sourceSystem, sourceComponent, sequence, targetSystem, v[1], v[0], v[2], v[3]);
        int[] pwm = {1500 + v[0] / 2, 1500 + v[1] / 2, 1000 + v[2], 1500 + v[3] / 2};
        return override(sequence, pwm);
    }
    public byte[] release(int sequence) {
        if (message != Message.RC_CHANNELS_OVERRIDE) throw new IllegalArgumentException("MANUAL_CONTROL只停止，不猜测RC释放映射");
        return override(sequence, new int[4]);
    }
    private byte[] override(int sequence, int[] values) {
        byte[] payload = new byte[18]; ByteBuffer b = Mavlink.little(payload);
        for (int i = 0; i < 8; i++) b.putShort(i * 2, (short) 65535);
        for (int i = 0; i < 4; i++) b.putShort((axes[i].outputChannel - 1) * 2, (short) values[i]);
        b.put(16, (byte) targetSystem).put(17, (byte) targetComponent);
        return Mavlink.encode(sourceSystem, sourceComponent, sequence, Mavlink.OVERRIDE, payload);
    }
}
