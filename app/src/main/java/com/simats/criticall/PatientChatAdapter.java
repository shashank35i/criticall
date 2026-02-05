package com.simats.criticall;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.simats.criticall.ai.StreamingTextRenderer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class PatientChatAdapter extends RecyclerView.Adapter<PatientChatAdapter.VH> {

    public interface OnSpeakClickListener {
        void onSpeak(@NonNull String text);
    }

    public interface OnActionClickListener {
        void onAction(@NonNull String label);
    }

    private final StreamingTextRenderer renderer;
    private final List<PatientChatMessage> messages = new ArrayList<>();
    private OnSpeakClickListener speakClickListener;
    private OnActionClickListener actionClickListener;

    public PatientChatAdapter(StreamingTextRenderer renderer) {
        this.renderer = renderer;
    }

    public void setOnSpeakClickListener(OnSpeakClickListener l) {
        this.speakClickListener = l;
    }

    public void setOnActionClickListener(OnActionClickListener l) {
        this.actionClickListener = l;
    }

    public void setMessages(List<PatientChatMessage> list) {
        messages.clear();
        if (list != null) messages.addAll(list);
        notifyDataSetChanged();
    }

    public void addMessage(PatientChatMessage msg) {
        if (msg == null) return;
        messages.add(msg);
        notifyItemInserted(messages.size() - 1);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_patient_chat_message, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        PatientChatMessage m = messages.get(position);
        if (m == null) return;

        PatientChatMessage.Sender sender = readSender(m);
        String text = readString(m, "getText", "text");
        String badge = readString(m, "getBadge", "badge", "getMeta", "meta", "getSource", "source");

        boolean isPatient = sender == PatientChatMessage.Sender.PATIENT;
        boolean isAiLike = (sender == PatientChatMessage.Sender.AI || sender == PatientChatMessage.Sender.OFFLINE);

        h.leftWrap.setVisibility(isPatient ? View.GONE : View.VISIBLE);
        h.rightWrap.setVisibility(isPatient ? View.VISIBLE : View.GONE);

        if (isPatient) {
            h.tvRight.setText(text);
        } else {
            h.tvLeft.setText(text);
        }

        if (h.btnSpeakMessage != null) {
            h.btnSpeakMessage.setVisibility(isAiLike && !TextUtils.isEmpty(text) ? View.VISIBLE : View.GONE);
            h.btnSpeakMessage.setOnClickListener(v -> {
                if (speakClickListener != null && !TextUtils.isEmpty(text)) {
                    speakClickListener.onSpeak(text);
                }
            });
        }

        if (h.tvMetaLeft != null) {
            if (sender == PatientChatMessage.Sender.OFFLINE && !TextUtils.isEmpty(badge)) {
                h.tvMetaLeft.setVisibility(View.VISIBLE);
                h.tvMetaLeft.setText(badge);
            } else {
                h.tvMetaLeft.setVisibility(View.GONE);
            }
        }

        if (h.rowActionsLeft != null) {
            h.rowActionsLeft.removeAllViews();
            if (isAiLike) {
                List<String> actions = readActions(m);
                if (actions != null && !actions.isEmpty()) {
                    h.rowActionsLeft.setVisibility(View.VISIBLE);
                    for (String a : actions) {
                        if (TextUtils.isEmpty(a)) continue;
                        TextView chip = (TextView) LayoutInflater.from(h.itemView.getContext())
                                .inflate(R.layout.item_quick_chip, h.rowActionsLeft, false);
                        chip.setText(a);
                        chip.setOnClickListener(v -> {
                            if (actionClickListener != null) actionClickListener.onAction(a);
                        });
                        h.rowActionsLeft.addView(chip);
                    }
                } else {
                    h.rowActionsLeft.setVisibility(View.GONE);
                }
            } else {
                h.rowActionsLeft.setVisibility(View.GONE);
            }
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        View leftWrap, rightWrap;
        TextView tvLeft, tvRight;
        TextView tvMetaLeft;
        MaterialButton btnSpeakMessage;
        ViewGroup rowActionsLeft;

        VH(@NonNull View itemView) {
            super(itemView);
            leftWrap = itemView.findViewById(R.id.leftWrap);
            rightWrap = itemView.findViewById(R.id.rightWrap);
            tvLeft = itemView.findViewById(R.id.tvMsgLeft);
            tvRight = itemView.findViewById(R.id.tvMsgRight);
            tvMetaLeft = itemView.findViewById(R.id.tvMetaLeft);
            btnSpeakMessage = itemView.findViewById(R.id.btnSpeakMessage);
            rowActionsLeft = itemView.findViewById(R.id.rowActionsLeft);
        }
    }

    private PatientChatMessage.Sender readSender(PatientChatMessage m) {
        Object v = readValue(m, "getSender", "sender");
        if (v instanceof PatientChatMessage.Sender) return (PatientChatMessage.Sender) v;
        if (v instanceof String) {
            try { return PatientChatMessage.Sender.valueOf(((String) v).toUpperCase()); }
            catch (Exception ignore) { }
        }
        return PatientChatMessage.Sender.AI;
    }

    private String readString(PatientChatMessage m, String... candidates) {
        for (String c : candidates) {
            Object v = readValue(m, c, c);
            if (v != null) return v.toString();
        }
        return "";
    }

    private Object readValue(Object obj, String getterName, String fieldName) {
        try {
            Method meth = obj.getClass().getMethod(getterName);
            meth.setAccessible(true);
            return meth.invoke(obj);
        } catch (Exception ignore) { }

        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Exception ignore) { }

        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> readActions(PatientChatMessage m) {
        Object v = readValue(m, "getActions", "actions");
        if (v instanceof List) return (List<String>) v;
        return null;
    }
}
