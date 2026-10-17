package com.g12.sender;
import android.app.Application;
import com.g12.bench.SkydroidBackend;
public final class SenderApplication extends Application {
    private SkydroidBackend backend;
    public SkydroidBackend backend() {
        if (backend == null) backend = new SkydroidBackend(this);
        return backend;
    }
}
