package com.gathin.tvhome;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** UI-thread coordinator: one background query at a time, no stale or paused results applied. */
final class AppRefresh<T> {
    interface Loader<T> {
        T load(Set<String> packages, boolean fullReload);
    }

    private final Executor worker;
    private final Executor ui;
    private final Loader<T> loader;
    private final Consumer<T> apply;
    private final Consumer<RuntimeException> onError;
    private final Set<String> packages = new HashSet<>();
    private boolean active, closed, loading;
    private boolean pending = true, fullReload = true;
    private int generation;

    AppRefresh(Executor worker, Executor ui, Loader<T> loader, Consumer<T> apply,
               Consumer<RuntimeException> onError) {
        this.worker = worker;
        this.ui = ui;
        this.loader = loader;
        this.apply = apply;
        this.onError = onError;
    }

    void refresh() {
        if (closed) return;
        pending = true;
        generation++;
        pump();
    }

    void resume() {
        active = true;
        pump();
    }

    boolean isActive() {
        return active && !closed;
    }

    void pause() {
        active = false;
    }

    void invalidate(String pkg) {
        if (closed) return;
        if (pkg == null) fullReload = true;
        else packages.add(pkg);
        pending = true;
        generation++;
        pump();
    }

    void close() {
        closed = true;
        active = false;
    }

    private void pump() {
        if (closed || !active || loading || !pending) return;
        final int request = generation;
        final Set<String> changed = new HashSet<>(packages);
        final boolean full = fullReload;
        packages.clear();
        pending = false;
        fullReload = false;
        loading = true;
        worker.execute(() -> {
            try {
                T result = loader.load(changed, full);
                ui.execute(() -> {
                    loading = false;
                    if (closed) return;
                    if (request == generation && active) apply.accept(result);
                    else if (!active) {
                        // Keep a pending refresh for resume; the catalog can reuse its snapshot.
                        pending = true;
                    }
                    pump();
                });
            } catch (RuntimeException error) {
                ui.execute(() -> {
                    loading = false;
                    if (closed) return;
                    packages.addAll(changed);
                    pending = true;
                    fullReload |= full;
                    onError.accept(error); // Retry on the next event/resume, not a tight failure loop.
                });
            }
        });
    }
}
