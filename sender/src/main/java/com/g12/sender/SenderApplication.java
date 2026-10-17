package com.g12.sender;
import android.app.Application;
import com.g12.bench.SkydroidBackend;
import com.g12.bench.ReadOnlySession;
import com.g12.bench.RetainedReadBackend;
public final class SenderApplication extends Application {
    private ReadOnlySession.Backend backend;
    public ReadOnlySession.Backend backend() {
        if (backend == null) backend = new RetainedReadBackend(new SkydroidBackend(this));
        return backend;
    }
}
