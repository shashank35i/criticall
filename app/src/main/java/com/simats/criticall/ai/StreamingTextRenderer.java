package com.simats.criticall.ai;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.widget.TextView;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streams text into a TextView (Gemini-style) and applies subtle keyword highlights on completion.
 */
public class StreamingTextRenderer {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable tick;
    private TextView target;
    private CharSequence full;
    private int index;
    private int delayMs;

    private static final Pattern KEYWORDS = Pattern.compile(
            "\\b(LOW|MEDIUM|HIGH|CRITICAL|Hemoglobin|Platelets|ESR|ANC|WBC|Hematocrit)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final int HIGHLIGHT_BG = Color.parseColor("#222A7BFF"); // subtle indigo tint

    /**
     * Start streaming text into a TextView.
     *
     * @param tv        target TextView
     * @param fullText  full content to stream
     * @param msPerChar speed; clamped to 12..18 ms/char
     */
    public void start(TextView tv, String fullText, int msPerChar) {
        cancel();
        if (tv == null || TextUtils.isEmpty(fullText)) {
            if (tv != null) tv.setText(fullText);
            return;
        }
        this.target = tv;
        this.full = fullText;
        this.index = 0;
        this.delayMs = clamp(msPerChar, 12, 18);
        step();
    }

    public void cancel() {
        if (tick != null) {
            handler.removeCallbacks(tick);
            tick = null;
        }
        target = null;
        full = null;
    }

    public void applyHighlights(TextView tv) {
        if (tv == null || TextUtils.isEmpty(tv.getText())) return;
        SpannableStringBuilder sb = new SpannableStringBuilder(tv.getText());
        Matcher m = KEYWORDS.matcher(tv.getText());
        while (m.find()) {
            sb.setSpan(new StyleSpan(Typeface.BOLD), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new BackgroundColorSpan(HIGHLIGHT_BG), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tv.setText(sb);
    }

    private void step() {
        if (target == null || full == null) return;
        index = Math.min(index + 1, full.length());
        target.setText(full.subSequence(0, index));

        if (index >= full.length()) {
            applyHighlights(target);
            cancel();
            return;
        }

        int nextDelay = delayMs;
        char last = full.charAt(index - 1);
        if (last == '.' || last == ',' || last == '!' || last == '?') {
            nextDelay += 70;
        }

        tick = this::step;
        handler.postDelayed(tick, nextDelay);
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
