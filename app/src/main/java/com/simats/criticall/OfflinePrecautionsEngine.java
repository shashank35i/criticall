package com.simats.criticall;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class OfflinePrecautionsEngine {

    public static String generateReply(String riskLevel,
                                       String category,
                                       List<PredictedAlertRepository.PredictedItem> items,
                                       Map<String, String> lastLabs,
                                       String userMessage) {
        String intent = classify(userMessage);
        if ("GREETING".equals(intent)) {
            return "Hello! How can I help you today? You can ask about risk, precautions, diet, or when to contact your doctor.";
        }
        if ("FAREWELL".equals(intent)) {
            return "Take care. If you have more questions, come back anytime. If you feel worse, contact your doctor.";
        }
        if ("THANKS".equals(intent)) {
            return "You're welcome. Is there anything else you want to know about your risk or precautions?";
        }
        StringBuilder sb = new StringBuilder();

        String risk = riskLevel != null ? riskLevel.toUpperCase(Locale.ROOT) : "LOW";
        if (!TextUtils.isEmpty(category)) {
            sb.append("Your risk (").append(category).append("): ").append(risk).append("\n");
        } else {
            sb.append("Your risk: ").append(risk).append("\n");
        }

        if ("CONTACT".equals(intent)) {
            sb.append("• If you feel worse, call your doctor or care team.\n");
        } else if ("ACTION".equals(intent)) {
            sb.append("• Today: take medicines, rest, drink water, and note any new symptoms.\n");
        } else if ("DIET".equals(intent)) {
            sb.append("• Diet/Hydration: simple meals, easy on salt/sugar, steady fluids (unless doctor limits).\n");
        } else {
            sb.append("• Summary: ").append(riskSummary(riskLevel)).append("\n");
        }

        String confidence = confidenceLine(items);
        if (!confidence.isEmpty()) {
            sb.append(confidence).append("\n");
        }

        String itemLine = itemHighlights(items);
        if (!itemLine.isEmpty()) {
            sb.append(itemLine).append("\n");
        }

        String labsLine = labLine(lastLabs);
        if (!labsLine.isEmpty()) {
            sb.append(labsLine).append("\n");
        }

        if ("HIGH".equalsIgnoreCase(riskLevel)) {
            sb.append("• Contact your doctor for HIGH risk or if you feel worse.\n");
        }

        sb.append("• Not medical advice. Follow your clinician's plan.");
        return normalizeRiskTokens(sb.toString());
    }

    public static String[] todaysActions(String riskLevel) {
        return new com.simats.criticall.patient.TodayPlanEngine().generate(riskLevel).actions.toArray(new String[0]);
    }

    public static String[] contactTriggers(String riskLevel) {
        return new com.simats.criticall.patient.TodayPlanEngine().generate(riskLevel).triggers.toArray(new String[0]);
    }

    private static String classify(String msg) {
        if (msg == null) return "EXPLAIN";
        String m = msg.toLowerCase(Locale.ROOT);
        if (containsWord(m, "hello") || containsWord(m, "hi") || containsWord(m, "hey")
                || m.contains("good morning") || m.contains("good afternoon") || m.contains("good evening")) {
            return "GREETING";
        }
        if (containsWord(m, "bye") || containsWord(m, "goodbye") || m.contains("see you") || containsWord(m, "later")) {
            return "FAREWELL";
        }
        if (containsWord(m, "thanks") || m.contains("thank you") || containsWord(m, "thx")) return "THANKS";
        if (m.contains("contact") || m.contains("doctor") || m.contains("call")) return "CONTACT";
        if (m.contains("diet") || m.contains("food") || m.contains("hydration") || m.contains("drink")) return "DIET";
        if (m.contains("today") || m.contains("do") || m.contains("action")) return "ACTION";
        return "EXPLAIN";
    }

    private static boolean containsWord(String text, String word) {
        if (text == null || word == null) return false;
        String[] parts = text.split("[^a-z]+");
        for (String p : parts) {
            if (word.equals(p)) return true;
        }
        return false;
    }

    private static String riskSummary(String risk) {
        if ("HIGH".equalsIgnoreCase(risk)) return "Higher risk: watch symptoms, rest, and reach out to your doctor.";
        if ("MEDIUM".equalsIgnoreCase(risk)) return "Moderate risk: stay on your plan and repeat labs as scheduled.";
        return "Low risk: stay on your routine and hydrate.";
    }

    private static String confidenceLine(List<PredictedAlertRepository.PredictedItem> items) {
        if (items == null || items.isEmpty()) return "";
        float max = 0f;
        for (PredictedAlertRepository.PredictedItem i : items) {
            if (i != null) max = Math.max(max, i.confidence);
        }
        int pct = Math.round(max * 100);
        String label = pct >= 75 ? "high" : pct >= 50 ? "moderate" : "low";
        return pct > 0 ? "• Confidence: " + (pct / 100f) + " (" + label + ")" : "";
    }

    private static String itemHighlights(List<PredictedAlertRepository.PredictedItem> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("• Key items: ");
        int count = 0;
        for (PredictedAlertRepository.PredictedItem i : items) {
            if (i == null) continue;
            if (count++ >= 3) break;
            sb.append(i.name).append(" ");
            if (!Float.isNaN(i.predictedValue)) {
                sb.append(String.format(Locale.getDefault(), "(pred %.2f)", i.predictedValue));
            }
            if (i.thresholdRule != null && !i.thresholdRule.isEmpty()) {
                sb.append(" vs ").append(i.thresholdRule);
            }
            sb.append("; ");
        }
        return sb.toString();
    }

    private static String labLine(Map<String, String> labs) {
        if (labs == null || labs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("• Latest labs: ");
        int count = 0;
        for (Map.Entry<String, String> e : labs.entrySet()) {
            if (count++ >= 4) break;
            sb.append(e.getKey()).append("=").append(e.getValue()).append("; ");
        }
        return sb.toString();
    }

    private static String normalizeRiskTokens(String input) {
        if (input == null) return null;
        return input.replaceAll("(?i)\\b(low)\\b", "LOW")
                .replaceAll("(?i)\\b(medium)\\b", "MEDIUM")
                .replaceAll("(?i)\\b(high)\\b", "HIGH")
                .replaceAll("(?i)\\b(critical)\\b", "CRITICAL");
    }
}
