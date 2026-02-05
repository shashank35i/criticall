package com.simats.criticall;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;

public final class AssistantUiBridge {
    public interface Listener {
        void onAssistantSpeakingChanged(boolean speaking);
    }

    private static final CopyOnWriteArrayList<WeakReference<Listener>> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean speaking = false;

    private AssistantUiBridge() {}

    public static void addListener(Listener l) {
        if (l == null) return;
        LISTENERS.addIfAbsent(new WeakReference<>(l));
        l.onAssistantSpeakingChanged(speaking);
    }

    public static void removeListener(Listener l) {
        if (l == null) return;
        Iterator<WeakReference<Listener>> it = LISTENERS.iterator();
        while (it.hasNext()) {
            Listener x = it.next().get();
            if (x == null || x == l) it.remove();
        }
    }

    public static void setSpeaking(boolean value) {
        speaking = value;
        for (WeakReference<Listener> ref : LISTENERS) {
            Listener l = ref.get();
            if (l != null) l.onAssistantSpeakingChanged(value);
        }
    }
}
