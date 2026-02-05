package com.simats.criticall;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Minimal Gemini API client for text-only generateContent.
 * Model: gemini-2.5-flash-lite
 * maxOutputTokens: configurable (use 256-1024)
 *
 * NOTE: BuildConfig.GEMINI_API_KEY is NOT secure in client APK (demo only).
 */
public class LabClient {

    public interface Listener {
        void onSuccess(@NonNull String replyText);
        void onError(@NonNull String message);
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String MODEL = "gemini-2.5-flash-lite";

    private final OkHttpClient http = new OkHttpClient();
    private final Context appCtx;

    public LabClient(@NonNull Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    public void sendText(
            @NonNull String systemPrompt,
            @NonNull String userText,
            int maxOutputTokens,
            @NonNull Listener listener
    ) {
        String apiKey = BuildConfig.GEMINI_API_KEY;

        if (TextUtils.isEmpty(apiKey)) {
            listener.onError("Missing GEMINI_API_KEY. Add it to gradle.properties and BuildConfig.");
            return;
        }

        if (maxOutputTokens < 64) maxOutputTokens = 64;
        if (maxOutputTokens > 2048) maxOutputTokens = 2048;

        // Endpoint
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + MODEL + ":generateContent?key=" + apiKey;

        // Build request JSON (text-only)
        JsonObject root = new JsonObject();

        // systemInstruction
        JsonObject sys = new JsonObject();
        JsonArray sysParts = new JsonArray();
        JsonObject sysPart = new JsonObject();
        sysPart.addProperty("text", systemPrompt);
        sysParts.add(sysPart);
        sys.add("parts", sysParts);
        root.add("systemInstruction", sys);

        // contents
        JsonArray contents = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        JsonArray userParts = new JsonArray();
        JsonObject up = new JsonObject();
        up.addProperty("text", userText);
        userParts.add(up);
        user.add("parts", userParts);
        contents.add(user);
        root.add("contents", contents);

        // generationConfig
        JsonObject gen = new JsonObject();
        gen.addProperty("maxOutputTokens", maxOutputTokens);
        gen.addProperty("temperature", 0.4);
        gen.addProperty("topP", 0.9);
        root.add("generationConfig", gen);

        RequestBody body = RequestBody.create(root.toString(), JSON);
        Request req = new Request.Builder().url(url).post(body).build();

        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                listener.onError(e.getMessage() != null ? e.getMessage() : "Network error");
            }

            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String raw = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    listener.onError("Gemini error " + response.code() + ": " + raw);
                    return;
                }

                try {
                    // Parse: candidates[0].content.parts[0].text
                    JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
                    JsonArray candidates = obj.getAsJsonArray("candidates");
                    if (candidates == null || candidates.size() == 0) {
                        listener.onError("Empty response from Gemini");
                        return;
                    }
                    JsonObject c0 = candidates.get(0).getAsJsonObject();
                    JsonObject content = c0.getAsJsonObject("content");
                    if (content == null) {
                        listener.onError("No content in response");
                        return;
                    }
                    JsonArray parts = content.getAsJsonArray("parts");
                    if (parts == null || parts.size() == 0) {
                        listener.onError("No parts in response");
                        return;
                    }
                    JsonObject p0 = parts.get(0).getAsJsonObject();
                    String text = p0.has("text") ? p0.get("text").getAsString() : "";
                    if (TextUtils.isEmpty(text)) text = "I couldn't generate a response.";
                    listener.onSuccess(text.trim());
                } catch (Exception ex) {
                    listener.onError("Parse error: " + ex.getMessage());
                }
            }
        });
    }
}
