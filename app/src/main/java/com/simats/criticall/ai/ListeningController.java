package com.simats.criticall.ai;

import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.TextView;

/**
 * Manages LISTENING -> THINKING transitions with wave, label, haptics, and pulse.
 */
public class ListeningController {

    public interface OnListeningDone {
        void onDone();
    }

    private final VoiceWaveView waveView;
    private final TextView labelView;
    private final View pulseTarget;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String listeningText = "Listening...";
    private ValueAnimator pulseAnim;
    private Runnable timeoutRunnable;
    private boolean active = false;

    public ListeningController(VoiceWaveView waveView, TextView labelView, View pulseTarget) {
        this.waveView = waveView;
        this.labelView = labelView;
        this.pulseTarget = pulseTarget;
    }

    public void startListening(OnListeningDone onDone) {
        cancel();
        active = true;
        if (labelView != null) {
            labelView.setText(listeningText != null ? listeningText : "Listening...");
            labelView.setVisibility(View.VISIBLE);
        }
        if (waveView != null) {
            waveView.setVisibility(View.VISIBLE);
            try { waveView.start(); } catch (Throwable ignored) {}
        }
        if (pulseTarget != null) {
            try { pulseTarget.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); } catch (Throwable ignored) {}
            startPulse(pulseTarget);
        }
        timeoutRunnable = () -> {
            if (!active) return;
            stopListening();
            if (onDone != null) onDone.onDone();
        };
        handler.postDelayed(timeoutRunnable, 2000); // simulate listening timeout
    }

    public void setListeningText(String text) {
        listeningText = text;
    }

    public void stopListening() {
        active = false;
        if (waveView != null) {
            try { waveView.stop(); } catch (Throwable ignored) {}
            waveView.setVisibility(View.GONE);
        }
        if (labelView != null) labelView.setVisibility(View.GONE);
        if (pulseTarget != null) {
            try { pulseTarget.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); } catch (Throwable ignored) {}
        }
        stopPulse();
    }

    public void cancel() {
        active = false;
        handler.removeCallbacksAndMessages(null);
        stopPulse();
    }

    private void startPulse(View target) {
        stopPulse();
        pulseAnim = ValueAnimator.ofFloat(1f, 1.08f);
        pulseAnim.setDuration(520);
        pulseAnim.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnim.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnim.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            target.setScaleX(v);
            target.setScaleY(v);
            target.setAlpha(0.9f + (v - 1f) * 1.2f);
        });
        pulseAnim.start();
    }

    private void stopPulse() {
        if (pulseAnim != null) {
            pulseAnim.cancel();
            pulseAnim = null;
        }
        if (pulseTarget != null) {
            pulseTarget.setScaleX(1f);
            pulseTarget.setScaleY(1f);
            pulseTarget.setAlpha(1f);
        }
    }
}
