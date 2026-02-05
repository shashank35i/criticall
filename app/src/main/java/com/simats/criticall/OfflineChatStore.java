package com.simats.criticall;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class OfflineChatStore {
    private static final String PREF_NAME = "PatientOfflineChatPrefs";
    private static final String KEY_PREFIX = "patient_chat_";
    private static final int MAX_MESSAGES = 10;
    private final SharedPreferences prefs;
    private final com.google.gson.Gson gson = new com.google.gson.Gson();

    public OfflineChatStore(Context ctx) {
        this.prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public List<PatientChatMessage> load(String patientId) {
        String raw = prefs.getString(KEY_PREFIX + patientId, null);
        if (raw == null) return new ArrayList<>();
        try {
            Type type = new com.google.gson.reflect.TypeToken<List<PatientChatMessage>>() {}.getType();
            List<PatientChatMessage> list = gson.fromJson(raw, type);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            Log.w("OfflineChatStore", "Failed to parse chat history", e);
            return new ArrayList<>();
        }
    }

    public void save(String patientId, List<PatientChatMessage> messages) {
        if (messages == null) return;
        List<PatientChatMessage> trimmed = new ArrayList<>();
        int start = Math.max(0, messages.size() - MAX_MESSAGES);
        for (int i = start; i < messages.size(); i++) trimmed.add(messages.get(i));
        prefs.edit().putString(KEY_PREFIX + patientId, gson.toJson(trimmed)).apply();
    }
}
