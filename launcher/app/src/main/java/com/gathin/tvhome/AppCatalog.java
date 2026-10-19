package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Discovers TV apps and caches artwork until an app or resource configuration changes. */
final class AppCatalog {
    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
        }
    }

    private final Context ctx;
    private final List<String> favourites;
    private final android.content.SharedPreferences usage;
    private final Set<String> hidden;
    private List<Entry> items = new ArrayList<>();
    private Configuration loadedConfiguration;

    AppCatalog(Context ctx) {
        this.ctx = ctx;
        usage = ctx.getSharedPreferences("app_usage", Context.MODE_PRIVATE);
        favourites = Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps));
        hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
    }

    List<Entry> load(Set<String> invalidated, boolean fullReload) {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(probe, 0));
        // Some configured TV apps only register a plain LAUNCHER activity.
        // Pull those in only when listed in the initial priorities; everything else stays TV-only
        // (LEANBACK_LAUNCHER) so the grid doesn't fill with phone apps.
        for (String pkg : favourites) {
            boolean present = false;
            for (ResolveInfo ri : found) {
                if (ri.activityInfo != null && pkg.equals(ri.activityInfo.packageName)) {
                    present = true;
                    break;
                }
            }
            if (present) continue;
            Intent fallback = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
            ResolveInfo ri = pm.resolveActivity(fallback, 0);
            if (ri != null && ri.activityInfo != null) found.add(ri);
        }
        // Re-query entry points to notice enabled/disabled apps, but reuse expensive artwork.
        Map<ComponentName, Entry> cached = new HashMap<>();
        Configuration configuration = ctx.getResources().getConfiguration();
        if (!fullReload && configuration.equals(loadedConfiguration)) {
            for (Entry entry : items) {
                if (!invalidated.contains(entry.pkg)) cached.put(entry.component, entry);
            }
        }
        loadedConfiguration = new Configuration(configuration);
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)
                    || AppOrder.isSystemTool(pkg)) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Entry existing = cached.get(component);
            if (existing != null) {
                fresh.add(existing);
                continue;
            }
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            fresh.add(new Entry(label, component, banner, icon));
        }
        // Snapshot counts once so a launch cannot change the comparator during sorting.
        Map<String, ?> counts = usage.getAll();
        Collections.sort(fresh, (a, b) -> AppOrder.compare(
                a.pkg, a.label, launchCount(counts, a.pkg),
                b.pkg, b.label, launchCount(counts, b.pkg), favourites));
        // Only the most-used row is ranked; the remaining library stays easy to scan.
        int dockSize = Math.max(1, Math.min(8, ctx.getResources().getInteger(R.integer.columns)));
        if (fresh.size() > dockSize) fresh.subList(dockSize, fresh.size()).sort(
                (a, b) -> a.label.compareToIgnoreCase(b.label));
        items = fresh;
        return fresh;
    }
    private static long launchCount(Map<String, ?> counts, String pkg) {
        Object count = counts.get(pkg);
        return count instanceof Long ? (Long) count : 0L;
    }
}
