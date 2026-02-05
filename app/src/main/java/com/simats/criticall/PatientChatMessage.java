package com.simats.criticall;

public class PatientChatMessage {
    public enum Sender { PATIENT, AI, OFFLINE }

    private final Sender sender;
    private final String text;
    private final long timestampMs;
    private final String confidenceTag;
    private final java.util.List<String> actions;

    public PatientChatMessage(Sender sender, String text, long timestampMs, String confidenceTag) {
        this(sender, text, timestampMs, confidenceTag, null);
    }

    public PatientChatMessage(Sender sender, String text, long timestampMs, String confidenceTag, java.util.List<String> actions) {
        this.sender = sender;
        this.text = text;
        this.timestampMs = timestampMs;
        this.confidenceTag = confidenceTag;
        this.actions = actions;
    }

    public Sender getSender() { return sender; }
    public String getText() { return text; }
    public long getTimestampMs() { return timestampMs; }
    public String getConfidenceTag() { return confidenceTag; }
    public java.util.List<String> getActions() { return actions; }
}
