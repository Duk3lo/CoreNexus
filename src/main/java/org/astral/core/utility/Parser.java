package org.astral.core.utility;

public final class Parser {
    public static long parseTime(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) return 3600000; // 1h default
        timeStr = timeStr.trim().toUpperCase();
        try {
            long multiplier = 1;
            String numberPart = timeStr;
            if (timeStr.endsWith("D")) { multiplier = 24L * 60 * 60 * 1000; numberPart = timeStr.replace("D", ""); }
            else if (timeStr.endsWith("H")) { multiplier = 60L * 60 * 1000; numberPart = timeStr.replace("H", ""); }
            else if (timeStr.endsWith("M")) { multiplier = 60L * 1000; numberPart = timeStr.replace("M", ""); }
            else if (timeStr.endsWith("S")) { multiplier = 1000L; numberPart = timeStr.replace("S", ""); }
            return Long.parseLong(numberPart) * multiplier;
        } catch (Exception e) { return 3600000; }
    }
}
