package com.g12.core;

/** Our future adapter contract, NOT an assertion about any vendor SDK API. */
public interface VendorAdapter extends AutoCloseable {
    interface InputListener {
        void sample(long generation, long sourceMonotonicMillis, int pitch, int roll,
                    int throttle, int yaw, boolean deadman, boolean sourceValid);
        void disconnected();
    }
    void observe(InputListener listener);
    @Override void close();

    static VendorAdapter hardware() {
        throw new UnsupportedOperationException("G12 SDK/version/license and hardware arbitration not verified");
    }
}
