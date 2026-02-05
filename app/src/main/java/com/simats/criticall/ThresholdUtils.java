package com.simats.criticall;

public final class ThresholdUtils {
    private ThresholdUtils() {}

    public static class Threshold {
        public final float value;
        public final String op;

        Threshold(float value, String op) {
            this.value = value;
            this.op = op == null ? "" : op.trim();
        }
    }

    public static Threshold parseThreshold(String threshold) {
        if (threshold == null) return new Threshold(Float.NaN, "");
        String t = threshold.trim();
        if (t.isEmpty()) return new Threshold(Float.NaN, "");

        String first = t.split(";")[0].trim();

        String op = "";
        if (first.contains("≤") || first.contains("<=")) op = "<=";
        else if (first.contains("≥") || first.contains(">=")) op = ">=";
        else if (first.contains("<")) op = "<";
        else if (first.contains(">")) op = ">";

        float value = Float.NaN;
        java.util.regex.Pattern numberPattern = java.util.regex.Pattern.compile("(-?\\d+(?:\\.\\d+)?)");
        java.util.regex.Matcher matcher = numberPattern.matcher(first);
        if (matcher.find()) {
            try {
                value = Float.parseFloat(matcher.group(1));
            } catch (Exception ignored) {}
        }

        return new Threshold(value, op);
    }

    public static String formatThreshold(Threshold threshold) {
        if (threshold == null || Float.isNaN(threshold.value)) return "";
        String operator = threshold.op == null ? "" : threshold.op.trim();
        if (operator.isEmpty()) return String.valueOf(threshold.value);
        return operator + " " + threshold.value;
    }

    public static String safeString(String value) {
        return value == null ? "" : value.trim();
    }

    // Convenience helpers for value comparisons
    public static boolean isCriticalHigh(String threshold, float value) {
        Threshold t = parseThreshold(threshold);
        if (Float.isNaN(value) || Float.isNaN(t.value)) return false;
        switch (t.op) {
            case ">":
            case ">=":
                return value >= t.value;
            case "<":
            case "<=":
                return false;
            default:
                return value > t.value; // default treat as high if above single value
        }
    }

    public static boolean isCriticalLow(String threshold, float value) {
        Threshold t = parseThreshold(threshold);
        if (Float.isNaN(value) || Float.isNaN(t.value)) return false;
        switch (t.op) {
            case "<":
            case "<=":
                return value <= t.value;
            case ">":
            case ">=":
                return false;
            default:
                return value < t.value; // default treat as low if below single value
        }
    }
}
