package com.gathin.tvhome;

import java.util.Arrays;
import java.util.List;

public final class AppOrderTest {
    public static void main(String[] args) {
        List<String> defaults = Arrays.asList("video.default", "tv.live");
        check(AppOrder.compare("video.new", "New", 2, "video.default", "Default", 1, defaults) < 0,
                "frequency must override defaults");
        check(AppOrder.compare("video.default", "Z", 0, "video.new", "A", 0, defaults) < 0,
                "zero-use apps keep initial priorities");
        check(AppOrder.compare("video.b", "B", 3, "video.a", "A", 3, defaults) > 0,
                "equal counts use stable labels");
        check(AppOrder.compare("video.a", "Same", 3, "video.b", "Same", 3, defaults) < 0,
                "duplicate labels have a deterministic tie-break");
        check(AppOrder.compare("a", "A", Long.MAX_VALUE, "b", "B", 0, defaults) < 0,
                "large counts must not overflow comparator");
        for (String pkg : Arrays.asList("com.android.tv.settings",
                "com.android.packageinstaller", "com.android.systemui", "com.vendor.appstore",
                "com.heytap.tv.livetv")) {
            check(AppOrder.isSystemTool(pkg), "system tool leaked: " + pkg);
        }
        for (String pkg : Arrays.asList("com.ktcp.video", "com.heytap.tv.filebrowser", "com.bilibili.tv")) {
            check(!AppOrder.isSystemTool(pkg), "media app hidden: " + pkg);
        }
        System.out.println("Usage ordering and system-tool filtering checks passed");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
