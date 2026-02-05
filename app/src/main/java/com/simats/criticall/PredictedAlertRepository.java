package com.simats.criticall;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public final class PredictedAlertRepository {
    public static final String NODE = "predictedAlerts";
    public static final String ARCHIVE_NODE = "predictedAlertsArchived";
    private static final SimpleDateFormat TIMESTAMP_FORMATTER =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    static {
        TIMESTAMP_FORMATTER.setTimeZone(TimeZone.getDefault());
    }

    private PredictedAlertRepository() {}

    public static class PredictedItem {
        public final String name;
        public final float lastValue;
        public final float predictedValue;
        public final String thresholdRule;
        public final String direction;
        public final float confidence;
        public final String reason;
        public final Float timeToThresholdHours;

        PredictedItem(String name, float lastValue, float predictedValue, String thresholdRule,
                      String direction, float confidence, String reason, Float timeToThresholdHours) {
            this.name = name;
            this.lastValue = lastValue;
            this.predictedValue = predictedValue;
            this.thresholdRule = thresholdRule;
            this.direction = direction;
            this.confidence = confidence;
            this.reason = reason;
            this.timeToThresholdHours = timeToThresholdHours;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("name", name);
            map.put("lastValue", lastValue);
            map.put("predictedValue", predictedValue);
            map.put("thresholdRule", thresholdRule);
            map.put("direction", direction);
            map.put("confidence", confidence);
            map.put("reason", reason);
            if (timeToThresholdHours != null && !Float.isNaN(timeToThresholdHours)) {
                map.put("timeToThresholdHours", timeToThresholdHours);
            }
            return map;
        }

        @Nullable
        public static PredictedItem fromMap(@Nullable Map<String, Object> map) {
            if (map == null) return null;
            Object nameObj = map.get("name");
            if (!(nameObj instanceof String)) return null;
            String name = (String) nameObj;
            float lastValue = toFloat(map.get("lastValue"));
            float predictedValue = toFloat(map.get("predictedValue"));
            String thresholdRule = stringOrEmpty(map.get("thresholdRule"));
            String direction = stringOrEmpty(map.get("direction"));
            float confidence = toFloat(map.get("confidence"));
            String reason = stringOrEmpty(map.get("reason"));
            Float timeToThreshold = toNullableFloat(map.get("timeToThresholdHours"));
            return new PredictedItem(name, lastValue, predictedValue, thresholdRule, direction, confidence, reason, timeToThreshold);
        }

        private static float toFloat(Object o) {
            if (o instanceof Number) return ((Number) o).floatValue();
            try {
                return Float.parseFloat(String.valueOf(o));
            } catch (Exception e) {
                return Float.NaN;
            }
        }

        private static Float toNullableFloat(Object o) {
            float value = toFloat(o);
            return Float.isNaN(value) ? null : value;
        }

        private static String stringOrEmpty(Object o) {
            return o instanceof String ? (String) o : "";
        }
    }

    public static class PredictedAlert {
        public final String patientId;
        public final String patientName;
        public final String doctorId;
        public final String category;
        public final String generatedAt;
        public final long generatedAtMs;
        public final String riskLevel;
        public final float predictedWindowHours;
        public final List<PredictedItem> predictedItems;
        public final String modelVersion;

        PredictedAlert(String patientId, String patientName, String doctorId, String category,
                       String generatedAt, long generatedAtMs, String riskLevel,
                       float predictedWindowHours, List<PredictedItem> predictedItems, String modelVersion) {
            this.patientId = patientId;
            this.patientName = patientName;
            this.doctorId = doctorId;
            this.category = category;
            this.generatedAt = generatedAt;
            this.generatedAtMs = generatedAtMs;
            this.riskLevel = riskLevel;
            this.predictedWindowHours = predictedWindowHours;
            this.predictedItems = predictedItems;
            this.modelVersion = modelVersion;
        }
    }

    /**
     * Build predicted alert.
     *
     * NOTE:
     * Previously depended on LabTestEntryFragment.TestParameter.
     * That class may not exist anymore, so we accept Object and read criticalHigh/criticalLow
     * via reflection safely (compile-safe).
     */
    public static PredictedAlert buildPredictedAlert(String patientId,
                                                     String patientName,
                                                     String doctorId,
                                                     String category,
                                                     Map<String, TrendPredictor.Prediction> predictions,
                                                     Map<String, Object> parameterMap) {

        List<PredictedItem> items = new ArrayList<>();
        float minWindowHours = Float.MAX_VALUE;

        for (Map.Entry<String, TrendPredictor.Prediction> entry : predictions.entrySet()) {
            String name = entry.getKey();
            TrendPredictor.Prediction prediction = entry.getValue();
            if (prediction == null || !prediction.hasNumeric || Float.isNaN(prediction.predictedValue)) continue;

            // ✅ No hard dependency; read thresholds reflectively if present
            Object paramObj = parameterMap != null ? parameterMap.get(name) : null;

            String criticalHigh = reflectThreshold(paramObj, "getCriticalHigh", "criticalHigh", "getHighCritical", "highCritical");
            String criticalLow  = reflectThreshold(paramObj, "getCriticalLow",  "criticalLow",  "getLowCritical",  "lowCritical");

            String thresholdRule = "";
            float thresholdValue = Float.NaN;

            if (!TextUtils.isEmpty(criticalHigh) || !TextUtils.isEmpty(criticalLow)) {
                ThresholdUtils.Threshold high = ThresholdUtils.parseThreshold(criticalHigh);
                ThresholdUtils.Threshold low  = ThresholdUtils.parseThreshold(criticalLow);

                boolean trendingUp = prediction.slopePerHour > 0;
                boolean trendingDown = prediction.slopePerHour < 0;

                if (trendingUp && !Float.isNaN(high.value)) {
                    thresholdValue = high.value;
                    thresholdRule = ThresholdUtils.formatThreshold(high);
                } else if (trendingDown && !Float.isNaN(low.value)) {
                    thresholdValue = low.value;
                    thresholdRule = ThresholdUtils.formatThreshold(low);
                } else if (!Float.isNaN(high.value)) {
                    thresholdValue = high.value;
                    thresholdRule = ThresholdUtils.formatThreshold(high);
                } else if (!Float.isNaN(low.value)) {
                    thresholdValue = low.value;
                    thresholdRule = ThresholdUtils.formatThreshold(low);
                }
            }

            Float timeToThreshold = null;
            if (!Float.isNaN(thresholdValue) && prediction.slopePerHour != 0) {
                float delta = thresholdValue - prediction.lastValue;
                float hours = delta / prediction.slopePerHour;
                if (hours > 0 && !Float.isNaN(hours)) {
                    timeToThreshold = hours;
                    minWindowHours = Math.min(minWindowHours, hours);
                }
            }

            String direction = prediction.predictedValue > prediction.lastValue ? "UP" :
                    (prediction.predictedValue < prediction.lastValue ? "DOWN" : "STABLE");

            String reason;
            if (timeToThreshold != null) {
                reason = String.format(Locale.getDefault(), "Projected to reach %s in %.1fh",
                        !TextUtils.isEmpty(thresholdRule) ? thresholdRule : "target range", timeToThreshold);
            } else if (!TextUtils.isEmpty(thresholdRule)) {
                reason = "Trend moving toward " + thresholdRule;
            } else {
                reason = "Trend steady";
            }

            items.add(new PredictedItem(
                    name,
                    prediction.lastValue,
                    prediction.predictedValue,
                    thresholdRule,
                    direction,
                    prediction.confidence,
                    reason,
                    timeToThreshold
            ));
        }

        String riskLevel = "LOW";
        for (PredictedItem item : items) {
            if (item.timeToThresholdHours != null && item.timeToThresholdHours <= 24f && item.confidence >= 0.55f) {
                riskLevel = "HIGH";
                break;
            }
            if (item.confidence >= 0.4f && !"HIGH".equals(riskLevel)) {
                riskLevel = "MEDIUM";
            }
        }

        float windowHours = minWindowHours == Float.MAX_VALUE ? 24f : Math.min(24f, minWindowHours);

        String generatedAt = TIMESTAMP_FORMATTER.format(new Date());
        long generatedAtMs = System.currentTimeMillis();

        return new PredictedAlert(
                patientId,
                patientName,
                doctorId,
                category,
                generatedAt,
                generatedAtMs,
                riskLevel,
                windowHours,
                items,
                TrendPredictor.MODEL_VERSION
        );
    }

    /**
     * Tries to read a threshold string from an arbitrary object without hard dependency.
     * We try:
     * 1) method names like getCriticalHigh()/getCriticalLow()
     * 2) public field names like criticalHigh/criticalLow
     */
    @Nullable
    private static String reflectThreshold(@Nullable Object obj, @NonNull String... candidates) {
        if (obj == null) return null;

        // 1) try getters / methods
        for (String c : candidates) {
            try {
                Method m = obj.getClass().getMethod(c);
                Object v = m.invoke(obj);
                if (v instanceof String && !TextUtils.isEmpty((String) v)) return (String) v;
            } catch (Exception ignored) {}
        }

        // 2) try public fields
        for (String c : candidates) {
            try {
                java.lang.reflect.Field f = obj.getClass().getField(c);
                Object v = f.get(obj);
                if (v instanceof String && !TextUtils.isEmpty((String) v)) return (String) v;
            } catch (Exception ignored) {}
        }

        return null;
    }

    public static Map<String, Object> toMap(PredictedAlert alert) {
        Map<String, Object> map = new HashMap<>();
        map.put("patientId", alert.patientId);
        map.put("patientName", alert.patientName);
        map.put("doctorId", alert.doctorId);
        map.put("category", alert.category);
        map.put("generatedAt", alert.generatedAt);
        map.put("generatedAtMs", alert.generatedAtMs);
        map.put("riskLevel", alert.riskLevel);
        map.put("predictedWindowHours", alert.predictedWindowHours);
        map.put("modelVersion", alert.modelVersion);

        List<Map<String, Object>> itemMaps = new ArrayList<>();
        for (PredictedItem item : alert.predictedItems) {
            itemMaps.add(item.toMap());
        }
        map.put("predictedItems", itemMaps);

        return map;
    }

    @Nullable
    public static PredictedAlert fromSnapshot(@Nullable DataSnapshot snapshot) {
        if (snapshot == null || !snapshot.exists()) return null;

        String patientId = snapshot.child("patientId").getValue(String.class);
        String patientName = snapshot.child("patientName").getValue(String.class);
        String doctorId = snapshot.child("doctorId").getValue(String.class);
        String category = snapshot.child("category").getValue(String.class);
        String generatedAt = snapshot.child("generatedAt").getValue(String.class);
        Long generatedAtMs = snapshot.child("generatedAtMs").getValue(Long.class);
        String riskLevel = snapshot.child("riskLevel").getValue(String.class);
        Double window = snapshot.child("predictedWindowHours").getValue(Double.class);
        String modelVersion = snapshot.child("modelVersion").getValue(String.class);

        List<PredictedItem> items = new ArrayList<>();
        DataSnapshot itemsSnapshot = snapshot.child("predictedItems");
        for (DataSnapshot child : itemsSnapshot.getChildren()) {
            Object rawValue = child.getValue();
            if (rawValue instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = (Map<String, Object>) rawValue;
                PredictedItem item = PredictedItem.fromMap(raw);
                if (item != null) items.add(item);
            }
        }

        return new PredictedAlert(
                patientId,
                patientName,
                doctorId,
                category,
                generatedAt == null ? "" : generatedAt,
                generatedAtMs != null ? generatedAtMs : 0L,
                riskLevel == null ? "LOW" : riskLevel,
                window != null ? window.floatValue() : 24f,
                items,
                modelVersion == null ? TrendPredictor.MODEL_VERSION : modelVersion
        );
    }

    public static void savePredictedAlert(String doctorId, String patientId, PredictedAlert alert) {
        if (doctorId == null || patientId == null || alert == null) return;
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference(NODE)
                .child(doctorId)
                .child(patientId);
        ref.setValue(toMap(alert));
    }

    public static void archivePredictedAlert(String doctorId, PredictedAlert alert) {
        if (doctorId == null || alert == null || alert.patientId == null) return;

        DatabaseReference ref = FirebaseDatabase.getInstance().getReference(NODE)
                .child(doctorId)
                .child(alert.patientId);

        DatabaseReference archiveRef = FirebaseDatabase.getInstance().getReference(ARCHIVE_NODE)
                .child(doctorId)
                .child(alert.patientId)
                .child(String.valueOf(System.currentTimeMillis()));

        Map<String, Object> snapshotMap = toMap(alert);
        archiveRef.setValue(snapshotMap, (error, ignored) -> {
            if (error == null) {
                ref.removeValue();
            }
        });
    }
}
