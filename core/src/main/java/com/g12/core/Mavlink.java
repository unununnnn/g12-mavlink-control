package com.g12.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Restricted, unsigned MAVLink 2 codec. Callers enforce simulation or PC-bench transport policy. */
public final class Mavlink {
    private Mavlink() { }
    public static final int HEARTBEAT = 0, MANUAL = 69, OVERRIDE = 70, VERSION = 148;

    public static final class Frame {
        public final int system, component, sequence, message;
        private final byte[] payload;
        private final byte[] wire;
        Frame(int s, int c, int q, int m, byte[] p, byte[] original) {
            system = s; component = c; sequence = q; message = m; payload = p.clone(); wire = original.clone();
        }
        public byte[] payload() { return payload.clone(); }
        public byte[] wire() { return wire.clone(); }
        public ByteBuffer fields() { return little(payload.clone()); }
    }

    public static ByteBuffer little(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
    private static int extra(int id) {
        switch (id) {
            case HEARTBEAT: return 50;
            case MANUAL: return 243;
            case OVERRIDE: return 124;
            case VERSION: return 178;
            default: throw new IllegalArgumentException("Unsupported message " + id);
        }
    }
    private static int size(int id) {
        switch (id) {
            case HEARTBEAT: return 9;
            case MANUAL: return 30;
            case OVERRIDE: return 38;
            case VERSION: return 78;
            default: return 0;
        }
    }
    private static void id(int value) {
        if (value < 1 || value > 255) throw new IllegalArgumentException("Nonzero MAVLink ID required");
    }
    private static int accumulate(int crc, int value) {
        int t = (value ^ crc) & 255;
        t = (t ^ (t << 4)) & 255;
        return ((crc >>> 8) ^ (t << 8) ^ (t << 3) ^ (t >>> 4)) & 65535;
    }
    private static int crc(byte[] b, int length, int extra) {
        int crc = 65535;
        for (int i = 1; i < length; i++) crc = accumulate(crc, b[i] & 255);
        return accumulate(crc, extra);
    }
    public static byte[] encode(int sys, int comp, int sequence, int message, byte[] payload) {
        id(sys); id(comp);
        int e = extra(message);
        if (payload.length == 0 || payload.length > size(message))
            throw new IllegalArgumentException("Payload length");
        int n = payload.length;
        while (n > 1 && payload[n - 1] == 0) n--;
        byte[] out = new byte[n + 12];
        out[0] = (byte) 0xfd; out[1] = (byte) n; out[4] = (byte) sequence;
        out[5] = (byte) sys; out[6] = (byte) comp;
        out[7] = (byte) message; out[8] = (byte) (message >>> 8);
        out[9] = (byte) (message >>> 16);
        System.arraycopy(payload, 0, out, 10, n);
        int check = crc(out, n + 10, e);
        out[n + 10] = (byte) check; out[n + 11] = (byte) (check >>> 8);
        return out;
    }
    public static byte[] manual(int sys, int comp, int seq, int target,
                                int pitch, int roll, int throttle, int yaw) {
        id(target);
        if (pitch < -1000 || pitch > 1000 || roll < -1000 || roll > 1000 || yaw < -1000 || yaw > 1000
                || throttle < 0 || throttle > 1000)
            throw new IllegalArgumentException("Axis range");
        byte[] p = new byte[11]; ByteBuffer b = little(p);
        b.putShort((short) pitch).putShort((short) roll).putShort((short) throttle)
                .putShort((short) yaw).putShort((short) 0).put((byte) target);
        return encode(sys, comp, seq, MANUAL, p);
    }
    public static byte[] release(int sys, int comp, int seq, int target, int targetComp) {
        id(target); id(targetComp);
        byte[] p = new byte[18]; ByteBuffer b = little(p);
        for (int i = 0; i < 8; i++) b.putShort((short) (i < 4 ? 0 : 65535));
        b.put((byte) target).put((byte) targetComp);
        return encode(sys, comp, seq, OVERRIDE, p);
    }
    public static Frame decode(byte[] bytes) {
        if (bytes.length < 13 || (bytes[0] & 255) != 253 || bytes[2] != 0 || bytes[3] != 0)
            throw new IllegalArgumentException("Unsigned MAVLink 2 only");
        int n = bytes[1] & 255;
        int m = (bytes[7] & 255) | ((bytes[8] & 255) << 8) | ((bytes[9] & 255) << 16);
        if (n < 1 || bytes.length != n + 12 || n > size(m))
            throw new IllegalArgumentException("Frame size/message");
        int wanted = (bytes[n + 10] & 255) | ((bytes[n + 11] & 255) << 8);
        if (wanted != crc(bytes, n + 10, extra(m))) throw new IllegalArgumentException("CRC");
        byte[] p = new byte[size(m)];
        System.arraycopy(bytes, 10, p, 0, n);
        return new Frame(bytes[5] & 255, bytes[6] & 255, bytes[4] & 255, m, p, bytes);
    }

    /** Bounded parser. A partial frame expires after 250ms of receive silence. */
    public static final class Parser {
        private byte[] buffer = new byte[0];
        private long last = -1;
        public List<Frame> feed(byte[] chunk, long now) {
            if (now < 0) throw new IllegalArgumentException("Monotonic timestamp required");
            if (last >= 0 && (now < last || now - last > 250)) buffer = new byte[0];
            last = now;
            List<Frame> result = new ArrayList<>();
            // Process incrementally: retained state never exceeds maximum signed frame length.
            for (byte value : chunk) {
                if (buffer.length == 0 && (value & 255) != 253) continue;
                int old = buffer.length;
                buffer = Arrays.copyOf(buffer, old + 1); buffer[old] = value;
                drain(result);
            }
            return result;
        }
        private void drain(List<Frame> result) {
            while (buffer.length > 0) {
                if ((buffer[0] & 255) != 253) { buffer = Arrays.copyOfRange(buffer, 1, buffer.length); continue; }
                if (buffer.length < 3) return;
                int length = (buffer[1] & 255) + 12 + ((buffer[2] & 1) != 0 ? 13 : 0);
                if (buffer.length < length) return;
                try {
                    result.add(decode(Arrays.copyOf(buffer, length)));
                    buffer = Arrays.copyOfRange(buffer, length, buffer.length);
                } catch (IllegalArgumentException invalid) {
                    // Drop the whole unsupported/signed frame; never parse its payload as traffic.
                    if (buffer[2] != 0 || buffer[3] != 0 || size((buffer[7] & 255)
                            | ((buffer[8] & 255) << 8) | ((buffer[9] & 255) << 16)) == 0)
                        buffer = Arrays.copyOfRange(buffer, length, buffer.length);
                    else buffer = Arrays.copyOfRange(buffer, 1, buffer.length);
                }
            }
        }
        public void clear() { buffer = new byte[0]; last = -1; }
    }
}
