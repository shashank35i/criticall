package com.simats.criticall.patient;

import java.util.ArrayList;
import java.util.List;

public class TodayPlanEngine {

    public static class Plan {
        public final List<String> actions;
        public final List<String> triggers;

        public Plan(List<String> actions, List<String> triggers) {
            this.actions = actions;
            this.triggers = triggers;
        }
    }

    public Plan generate(String riskLevel) {
        return generate(riskLevel, null);
    }

    public Plan generate(String riskLevel, java.util.Map<String, String> latestLabs) {
        String risk = riskLevel == null ? "LOW" : riskLevel.toUpperCase();
        List<String> actions = new ArrayList<>();
        List<String> triggers = new ArrayList<>();
        String context = contextualTip(latestLabs);
        switch (risk) {
            case "HIGH":
                actions.add("Contact your doctor today and follow their advice.");
                actions.add("Rest, avoid exertion; keep emergency contact ready.");
                actions.add("Track symptoms (breathlessness, chest pain, bleeding).");
                triggers.add("Severe pain, chest discomfort, or shortness of breath.");
                triggers.add("Bleeding that won't stop, sudden dizziness, or fainting.");
                triggers.add("Any rapid worsening of symptoms.");
                break;
            case "MEDIUM":
                actions.add("Monitor symptoms and note any changes");
                actions.add("Pace activities; avoid heavy exertion or missed meals.");
                actions.add("Plan your next follow-up or lab date.");
                triggers.add("New pain, fever, or bleeding that is unusual for you.");
                triggers.add("Feeling faint, very weak, or a racing heartbeat.");
                break;
            default: // LOW
                actions.add("Hydrate through the day; take medicines as prescribed.");
                actions.add("Keep routine activities; don't skip meals or rest");
                actions.add("Stay on schedule for follow-ups or labs.");
                triggers.add("Unusual symptoms (new bleeding, fever, or pain).");
                triggers.add("Feeling dizzy, very weak, or short of breath.");
        }
        // Append context (labs) after translation-friendly bases
        if (!context.isEmpty()) {
            for (int i = 0; i < actions.size(); i++) {
                String a = actions.get(i);
                if (a.contains("exertion; keep emergency contact ready") || a.contains("Keep routine activities")) {
                    actions.set(i, a + context);
                    break;
                }
            }
        }
        return new Plan(actions, triggers);
    }

    private String contextualTip(java.util.Map<String, String> labs) {
        if (labs == null || labs.isEmpty()) return "";
        String anc = valueOf(labs, "ANC");
        if (anc != null) return " (ANC " + anc + ": avoid crowds, wash hands often.)";
        String wbc = valueOf(labs, "WBC");
        if (wbc != null) return " (WBC " + wbc + ": watch fever/infection signs.)";
        String hb = valueOf(labs, "Hemoglobin");
        if (hb != null) return " (Hemoglobin " + hb + ": rest if lightheaded.)";
        return "";
        }

    private String valueOf(java.util.Map<String, String> labs, String key) {
        for (String k : labs.keySet()) {
            if (k == null) continue;
            if (k.equalsIgnoreCase(key)) {
                String v = labs.get(k);
                if (v != null && !v.trim().isEmpty()) return v.trim();
            }
        }
        return null;
    }
}
