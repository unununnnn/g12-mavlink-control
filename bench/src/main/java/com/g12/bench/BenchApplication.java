package com.g12.bench;

import android.app.Application;

/** One SDK initialization per process, with the application context. No connection at launch. */
public final class BenchApplication extends Application {
    private SkydroidBackend backend;
    public SkydroidBackend backend() {
        if (backend == null) backend = new SkydroidBackend(this);
        return backend;
    }
}
