package com.simats.criticall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

public final class TrendPredictor {
    public static final String MODEL_VERSION = "trend-v1";

    private TrendPredictor() {}

    public static class HistoryEntry {
        public final long timestampMs;
        public final Map<String, String> results;

        public HistoryEntry(long timestampMs, Map<String, String> results) {
            this.timestampMs = timestampMs;
            this.results = results != null ? results : new HashMap<>();
        }
    }

    public static class Prediction {
        public final String parameter;
        public final String suggestedValue;
        public final float predictedValue;
        public final float lastValue;
        public final float confidence;
        public final float slopePerHour;
        public final boolean hasNumeric;

        Prediction(String parameter, String suggestedValue, float predictedValue, float lastValue, float confidence, float slopePerHour, boolean hasNumeric) {
            this.parameter = parameter;
            this.suggestedValue = suggestedValue;
            this.predictedValue = predictedValue;
            this.lastValue = lastValue;
            this.confidence = confidence;
            this.slopePerHour = slopePerHour;
            this.hasNumeric = hasNumeric;
        }
    }

    private static class ValuePoint {
        final float value;
        final long timestampMs;
        ValuePoint(float value, long timestampMs) {
            this.value = value;
            this.timestampMs = timestampMs;
        }
    }

    public static Map<String, Prediction> buildPredictions(List<HistoryEntry> history, Set<String> parameterNames) {
        if (history == null || history.isEmpty() || parameterNames == null || parameterNames.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, List<ValuePoint>> numericHistory = new HashMap<>();
        Map<String, String> lastRawValue = new HashMap<>();

        List<HistoryEntry> sorted = new ArrayList<>(history);
        sorted.sort(Comparator.comparingLong(entry -> entry.timestampMs));

        for (HistoryEntry entry : sorted) {
            if (entry == null || entry.results == null) continue;
            for (Map.Entry<String, String> result : entry.results.entrySet()) {
                String param = result.getKey();
                if (param == null || !parameterNames.contains(param)) continue;
                String value = result.getValue();
                if (value == null || value.trim().isEmpty()) continue;
                String trimmed = value.trim();
                lastRawValue.put(param, trimmed);
                float parsed = parseFloatSafe(trimmed, Float.NaN);
                if (!Float.isNaN(parsed)) {
                    numericHistory.computeIfAbsent(param, k -> new ArrayList<>())
                            .add(new ValuePoint(parsed, entry.timestampMs));
                }
            }
        }

        Map<String, Prediction> predictions = new LinkedHashMap<>();
        for (String param : parameterNames) {
            String raw = lastRawValue.get(param);
            List<ValuePoint> points = numericHistory.get(param);
            Prediction prediction = computePrediction(param, raw, points);
            if (prediction != null) {
                predictions.put(param, prediction);
            }
        }

        return predictions;
    }

    private static Prediction computePrediction(String parameter, String lastRaw, List<ValuePoint> points) {
        if ((points == null || points.isEmpty()) && (lastRaw == null || lastRaw.isEmpty())) {
            return null;
        }

        if (points == null || points.isEmpty()) {
            float lastVal = parseFloatSafe(lastRaw, Float.NaN);
            String suggestion = lastRaw != null ? lastRaw : "";
            return new Prediction(parameter, suggestion, lastVal, lastVal, 0.25f, 0f, !Float.isNaN(lastVal));
        }

        points.sort(Comparator.comparingLong(point -> point.timestampMs));
        ValuePoint first = points.get(0);
        ValuePoint last = points.get(points.size() - 1);
        float timeSpanHours = Math.max(0.1f, (last.timestampMs - first.timestampMs) / 3600000f);
        float slopePerHour = (last.value - first.value) / timeSpanHours;
        float predictedValue = last.value + slopePerHour;
        float mean = computeMean(points);
        float variance = computeVariance(points, mean);
        float normalizedVariance = variance / (Math.abs(mean) + 1f);
        float base = 0.35f + Math.min(0.45f, (points.size() - 1) * 0.05f);
        float confidence = clamp(base - normalizedVariance * 0.5f, 0.1f, 0.95f);
        String suggestion = String.format(Locale.getDefault(), "%.2f", predictedValue);
        return new Prediction(parameter, suggestion, predictedValue, last.value, confidence, slopePerHour, true);
    }

    private static float computeMean(List<ValuePoint> points) {
        if (points.isEmpty()) return 0f;
        float sum = 0f;
        for (ValuePoint point : points) sum += point.value;
        return sum / points.size();
    }

    private static float computeVariance(List<ValuePoint> points, float mean) {
        if (points.isEmpty()) return 0f;
        float sum = 0f;
        for (ValuePoint point : points) {
            float delta = point.value - mean;
            sum += delta * delta;
        }
        return sum / points.size();
    }

    private static float parseFloatSafe(String value, float fallback) {
        try {
            return Float.parseFloat(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }
}
