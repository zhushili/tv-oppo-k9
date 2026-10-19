package com.gathin.tvhome;

import java.util.List;
import java.util.Locale;

/** Stable ranking and filtering rules, independent of Android. */
final class AppOrder {
    static boolean isSystemTool(String pkg) {
        String p = pkg.toLowerCase(Locale.ROOT);
        for (String part : p.split("\\.")) {
            if (part.equals("settings") || part.endsWith("settings")
                    || part.equals("packageinstaller") || part.equals("permissioncontroller")
                    || part.equals("systemui") || part.equals("launcher")
                    || part.equals("launcher2") || part.equals("updater")
                    || part.equals("appstore") || part.equals("livetv")
                    || part.equals("tvinput") || part.equals("inputsource")) return true;
        }
        return false;
    }

    static int compare(String a, String aLabel, long aCount,
                       String b, String bLabel, long bCount, List<String> defaults) {
        int count = Long.compare(bCount, aCount);
        if (count != 0) return count;
        int ai = defaults.indexOf(a), bi = defaults.indexOf(b);
        int priority = Integer.compare(ai < 0 ? Integer.MAX_VALUE : ai,
                bi < 0 ? Integer.MAX_VALUE : bi);
        if (priority != 0) return priority;
        int label = aLabel.compareToIgnoreCase(bLabel);
        return label != 0 ? label : a.compareTo(b);
    }
}
