package com.g12.bench;

import android.app.Application;

/** One SDK initialization per process, with the application context. No connection at launch. */
public final class BenchApplication extends Application {
    private ReadOnlySession.Backend backend;
    public ReadOnlySession.Backend backend() {
        if (backend == null) backend = new RetainedReadBackend(new SkydroidBackend(this));
        return backend;
    }
}
