package com.gathin.tvhome;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;

/** Deterministic lifecycle/race tests, no Android runtime or timing sleeps. */
public class AppRefreshTest {
    static class Tasks implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable task) { tasks.add(task); }
        void run() { tasks.remove().run(); }
    }
    static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }
    public static void main(String[] args) {
        Tasks worker = new Tasks(), ui = new Tasks();
        List<Set<String>> queried = new ArrayList<>();
        List<Boolean> full = new ArrayList<>();
        List<Integer> applied = new ArrayList<>();
        AppRefresh<Integer> refresh = new AppRefresh<>(worker, ui, (packages, all) -> {
            queried.add(packages); full.add(all); return queried.size();
        }, applied::add, error -> { throw error; });
        refresh.resume(); worker.run(); ui.run();
        check(applied.size() == 1 && full.get(0));
        refresh.pause(); refresh.resume();
        check(worker.tasks.isEmpty()); // Unchanged Home resume does no package queries.
        refresh.invalidate("app.a"); worker.run();
        refresh.invalidate("app.b"); // Change arrives before the old result is applied.
        ui.run();
        check(applied.size() == 1); // Stale result discarded.
        worker.run(); ui.run();
        check(applied.size() == 2 && queried.get(2).equals(Set.of("app.b")));
        refresh.pause(); refresh.invalidate("app.c"); refresh.invalidate("app.d");
        check(worker.tasks.isEmpty());
        refresh.resume(); worker.run(); refresh.pause(); ui.run();
        check(applied.size() == 2); // Nothing applied while paused.
        refresh.resume(); worker.run(); ui.run();
        check(applied.size() == 3);
        refresh.invalidate(null); worker.run(); ui.run();
        check(full.get(full.size() - 1)); // Configuration changes invalidate all artwork.
        refresh.invalidate("app.e"); worker.run(); refresh.close(); ui.run();
        check(applied.size() == 4); // No callback to a destroyed Activity.
        refresh.resume(); check(worker.tasks.isEmpty());

        Tasks rankWorker = new Tasks(), rankUi = new Tasks();
        List<Boolean> rankReloads = new ArrayList<>();
        AppRefresh<Integer> ranking = new AppRefresh<>(rankWorker, rankUi, (packages, all) -> {
            rankReloads.add(all);
            check(packages.isEmpty());
            return 1;
        }, ignored -> {}, error -> { throw error; });
        ranking.resume(); rankWorker.run(); rankUi.run();
        ranking.pause(); ranking.refresh();
        check(rankWorker.tasks.isEmpty());
        ranking.resume(); rankWorker.run(); rankUi.run();
        check(rankReloads.equals(List.of(true, false))); // Re-rank without discarding artwork.
        ranking.close();

        Tasks failWorker = new Tasks(), failUi = new Tasks();
        List<RuntimeException> errors = new ArrayList<>();
        boolean[] fail = {true};
        List<Set<String>> retries = new ArrayList<>();
        AppRefresh<Integer> retry = new AppRefresh<>(failWorker, failUi, (packages, all) -> {
            retries.add(packages);
            if (fail[0]) throw new IllegalStateException("package manager unavailable");
            return 1;
        }, ignored -> {}, errors::add);
        retry.invalidate("app.changed"); retry.resume(); failWorker.run(); failUi.run();
        check(errors.size() == 1 && failWorker.tasks.isEmpty());
        fail[0] = false; retry.resume(); failWorker.run(); failUi.run();
        check(retries.get(1).contains("app.changed"));
        System.out.println("AppRefresh lifecycle, coalescing, stale-result and retry checks passed");
    }
}
