package com.simats.criticall;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.LruCache;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;
import com.facebook.shimmer.ShimmerFrameLayout;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.FirebaseApp;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.FirebaseDatabase;

import com.simats.criticall.ai.AiMotionKit;
import com.simats.criticall.ai.StreamingTextRenderer;
import com.simats.criticall.ai.TypingDotsView;
import com.simats.criticall.ai.VoiceWaveView;
import com.simats.criticall.ai.ListeningController;
import com.simats.criticall.roles.patient.PatientApi;
import com.simats.criticall.roles.patient.PatientDoctorListActivity;
import com.simats.criticall.roles.patient.PatientHomeFragment;
import com.simats.criticall.roles.patient.PatientAppointmentDetailsActivity;
import com.simats.criticall.roles.patient.SelectSpecialityActivity;
import com.simats.criticall.LocalCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("unchecked")
public class PatientOfflineChatBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_PATIENT = "arg_patient";
    private static final String ARG_DOCTOR = "arg_doctor";
    private static final String ARG_CATEGORY = "arg_category";
    private static final String ARG_RISK = "arg_risk";
    private static final String ARG_ITEMS = "arg_items";
    private static final String ARG_LAST_LABS = "arg_last_labs";
    private static final String ARG_INITIAL_PROMPT = "arg_initial_prompt";
    private static final String ARG_START_LISTENING = "arg_start_listening";

    private static final int REQ_RECORD_AUDIO = 7001;

    private String patientId;
    private String doctorId;
    private String category;
    private String riskLevel;
    private ArrayList<PredictedAlertRepository.PredictedItem> predictedItems;
    private HashMap<String, String> lastLabs;

    private PatientChatAdapter chatAdapter;
    private OfflineChatStore chatStore;
    private LabClient labClient;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private StreamingTextRenderer streamingRenderer;
    private ValueAnimator headerGlowAnimator;

    private View aiStateRow;
    private VoiceWaveView voiceWaveView;
    private TypingDotsView typingDotsView;
    private ShimmerFrameLayout shimmerAnalyzing;
    private LottieAnimationView lottieListening;
    private LottieAnimationView lottieThinking;
    private TextView listeningLabel;
    private TextView tvDisclaimer;
    private TextView tvAnalyzing;

    private EditText etInput;
    private LinearLayout chipsContainer;
    private MaterialButton sendButton;
    private MaterialButton btnMic;
    private MaterialButton btnClear;

    private RecyclerView rvChat;

    private ListeningController listeningController;

    private String initialPrompt = "";
    private String patientSummary = "";
    private String prescriptionsSummary = "";
    private String specialitiesSummary = "";
    private final AtomicBoolean prescriptionsLoading = new AtomicBoolean(false);
    private final AtomicBoolean specialitiesLoading = new AtomicBoolean(false);
    private volatile boolean hasUpcomingBooking = false;

    private static final String CONSULT_TYPE_PHYSICAL = "PHYSICAL";

    // Booking agent state
    private String pendingSpecialityKey = "";
    private String pendingSpecialityLabel = "";
    private String pendingSpecialityReason = "";
    private String pendingSymptomsText = "";
    private boolean awaitingSymptoms = false;
    private boolean awaitingBookingConfirm = false;
    private long pendingDoctorId = 0L;
    private String pendingDoctorName = "";
    private int pendingDoctorFee = 0;
    private String pendingConsultType = "";
    private String pendingDateIso = "";
    private String pendingTime24 = "";
    private boolean awaitingDateTime = false;
    private boolean awaitingSuggestedTimeConfirm = false;
    private String suggestedDateIso = "";
    private String suggestedTime24 = "";
    private boolean pendingBookIntent = false;
    private String pendingIntentDateIso = "";
    private String pendingIntentTime24 = "";

    // Busy state: true while AI thinking OR voice listening
    private boolean isBusy = false;

    // Composer watcher
    private android.text.TextWatcher composerWatcher;

    // SpeechRecognizer
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean voiceListening = false;
    private boolean voiceStopRequested = false;
    private String lastPartialVoice = "";
    private boolean lastInputWasVoice = false;
    private boolean awaitingRecordsConfirm = false;
    private boolean awaitingMedicationInfo = false;
    private boolean pendingBookingAfterMedication = false;
    private boolean autoSpeakNextAi = false;
    private String pendingLangOverrideTag = "";
    private String activeLangOverrideTag = "";
    private String ttsLangOverrideTag = "";
    private String sessionLangOverrideTag = "";

    // TTS (on-device)
    private TextToSpeech tts;
    private boolean ttsReady = false;

    // Lang listeners
    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener langListener;
    private TranslationManager.LangListener tmLangListener;
    private final List<TextView> chipViews = new ArrayList<>();

    // Cheap Gemini: cache recent responses (avoid repeat credits)
    private final LruCache<String, String> geminiCache = new LruCache<>(60);

    private final java.util.concurrent.atomic.AtomicBoolean upcomingBookingLoading = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile long lastUpcomingBookingFetchMs = 0L;
    private AppointmentSnapshot lastUpcomingSnapshot = null;
    private JSONArray cachedAvailabilityDays = null;
    private List<JSONObject> cachedAvailabilityList = null;
    private int availabilityStartIndex = 0;
    private String lastAssistantTextForChips = "";
    private long lastChipAiAtMs = 0L;
    private String lastChipAiSource = "";
    private boolean chipAiInFlight = false;

    private final Runnable voiceFinalizeFallback = new Runnable() {
        @Override public void run() {
            if (!isAdded()) return;
            if (!voiceStopRequested) return;
            if (!TextUtils.isEmpty(lastPartialVoice)) {
                finalizeVoiceAndSend(lastPartialVoice);
            } else {
                stopVoiceUiOnly();
            }
        }
    };

    private static final String[] CHIP_TEXTS = new String[]{
            "Book an appointment",
            "Find a specialist",
            "Show my prescriptions",
            "Emergency help"
    };

    private static final String[] CHIP_TEXTS_WITH_UPCOMING = new String[]{
            "View my appointment",
            "Find a specialist",
            "Show my prescriptions",
            "Emergency help"
    };

    private static final java.util.Set<String> ALLOWED_SPECIALITY_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "GENERAL_PHYSICIAN",
            "CARDIOLOGY",
            "NEUROLOGY",
            "ORTHOPEDICS",
            "OPHTHALMOLOGY",
            "PEDIATRICS",
            "DERMATOLOGY",
            "PULMONOLOGY",
            "DIABETOLOGY",
            "FEVER_CLINIC",
            "GENERAL_MEDICINE",
            "EMERGENCY"
    ));

    public static void show(FragmentManager fm,
                            String patientId,
                            String doctorId,
                            String category,
                            String riskLevel,
                            ArrayList<PredictedAlertRepository.PredictedItem> items,
                            HashMap<String, String> lastLabs) {
        show(fm, patientId, doctorId, category, riskLevel, items, lastLabs, "");
    }

    public static void show(FragmentManager fm,
                            String patientId,
                            String doctorId,
                            String category,
                            String riskLevel,
                            ArrayList<PredictedAlertRepository.PredictedItem> items,
                            HashMap<String, String> lastLabs,
                            String initialPrompt) {
        show(fm, patientId, doctorId, category, riskLevel, items, lastLabs, initialPrompt, false);
    }

    public static void show(FragmentManager fm,
                            String patientId,
                            String doctorId,
                            String category,
                            String riskLevel,
                            ArrayList<PredictedAlertRepository.PredictedItem> items,
                            HashMap<String, String> lastLabs,
                            String initialPrompt,
                            boolean startListening) {

        PatientOfflineChatBottomSheet sheet = new PatientOfflineChatBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_PATIENT, patientId);
        args.putString(ARG_DOCTOR, doctorId);
        args.putString(ARG_CATEGORY, category);
        args.putString(ARG_RISK, riskLevel);
        args.putSerializable(ARG_ITEMS, items);
        args.putSerializable(ARG_LAST_LABS, lastLabs);
        args.putString(ARG_INITIAL_PROMPT, initialPrompt);
        args.putBoolean(ARG_START_LISTENING, startListening);
        sheet.setArguments(args);
        sheet.show(fm, "PatientOfflineChat");
    }

    @NonNull
    @Override
    public BottomSheetDialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        return new BottomSheetDialog(requireContext(), getTheme());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottomsheet_patient_offline_chat, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle a = getArguments();
        patientId = a != null ? a.getString(ARG_PATIENT, "") : "";
        doctorId = a != null ? a.getString(ARG_DOCTOR, "") : "";
        category = a != null ? a.getString(ARG_CATEGORY, "") : "";
        riskLevel = a != null ? a.getString(ARG_RISK, "LOW") : "LOW";
        predictedItems = (ArrayList<PredictedAlertRepository.PredictedItem>) (a != null ? a.getSerializable(ARG_ITEMS) : new ArrayList<>());
        lastLabs = (HashMap<String, String>) (a != null ? a.getSerializable(ARG_LAST_LABS) : new HashMap<>());
        initialPrompt = a != null ? a.getString(ARG_INITIAL_PROMPT, "") : "";
        final boolean startListening = a != null && a.getBoolean(ARG_START_LISTENING, false);

        chatStore = new OfflineChatStore(requireContext());
        labClient = new LabClient(requireContext());
        streamingRenderer = new StreamingTextRenderer();

        rvChat = view.findViewById(R.id.rvChatMessages);
        etInput = view.findViewById(R.id.etChatInput);
        sendButton = view.findViewById(R.id.btnSendChat);
        btnMic = view.findViewById(R.id.btnMic);
        chipsContainer = view.findViewById(R.id.chipSuggestions);
        btnClear = view.findViewById(R.id.btnClearChat);

        tvDisclaimer = view.findViewById(R.id.tvDisclaimer);
        View headerGlow = view.findViewById(R.id.aiHeaderGlow);
        View sheetRoot = view.findViewById(R.id.aiSheetRoot);

        aiStateRow = view.findViewById(R.id.aiStateRow);
        voiceWaveView = view.findViewById(R.id.voiceWaveView);
        typingDotsView = view.findViewById(R.id.typingDotsView);
        shimmerAnalyzing = view.findViewById(R.id.shimmerAnalyzing);
        listeningLabel = view.findViewById(R.id.listeningLabel);
        tvAnalyzing = view.findViewById(R.id.tvAnalyzing);

        // Hide placeholder speaker near input (we use per-message speaker)
        MaterialButton placeholderSpeaker = view.findViewById(R.id.btnSpeak);
        if (placeholderSpeaker != null) placeholderSpeaker.setVisibility(View.GONE);

        attachLottie(aiStateRow);

        listeningController = new ListeningController(voiceWaveView, listeningLabel, headerGlow);

        chatAdapter = new PatientChatAdapter(streamingRenderer);
        chatAdapter.setOnSpeakClickListener(this::speakMessageText);
        chatAdapter.setOnActionClickListener(label -> {
            if (isBusy) return;
            if (label == null) return;
            etInput.setText("");
            triggerPrompt(label, rvChat);
        });

        rvChat.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvChat.setAdapter(chatAdapter);

        List<PatientChatMessage> history = chatStore.load(patientId);
        chatAdapter.setMessages(history);
        scrollToBottom(rvChat);

        initTts();
        setupComposerBehavior();

        applyTranslations();

        fetchPatientContext();
        fetchLatestLabsIfMissing();
        fetchPrescriptionsSummary();
        fetchSpecialitiesSummary();
        fetchUpcomingBookingFlag();

        btnClear.setOnClickListener(v -> {
            chatStore.save(patientId, new ArrayList<>());
            chatAdapter.setMessages(new ArrayList<>());
            resetConversationState();
            setupChips(chipsContainer, etInput, rvChat);
            lastUpcomingBookingFetchMs = 0L;
            fetchUpcomingBookingFlag();
            syncComposerUi();
        });

        // SEND button:
        // - when busy -> STOP (stop voice or stop thinking UI)
        // - when typing -> send typed
        sendButton.setOnClickListener(v -> {
            if (isBusy) {
                if (voiceListening) stopVoiceListening();
                else stopThinkingUiOnly();
                return;
            }

            String msg = etInput.getText() != null ? etInput.getText().toString().trim() : "";
            if (msg.isEmpty()) return;
            etInput.setText("");
            lastInputWasVoice = false;
            autoSpeakNextAi = false;
            triggerPrompt(msg, rvChat);
        });

        // MIC button:
        // shows only when input empty; starts voice recognition
        btnMic.setOnClickListener(v -> {
            if (isBusy) return;
            String current = etInput.getText() != null ? etInput.getText().toString().trim() : "";
            if (!current.isEmpty()) return;
            startVoiceListening();
        });

        setupChips(chipsContainer, etInput, rvChat);
        animateSheetIn(sheetRoot);
        startHeaderGlow(headerGlow);

        if (!TextUtils.isEmpty(initialPrompt)) {
            handler.postDelayed(() -> triggerPrompt(initialPrompt, rvChat), 160);
        }

        if (startListening) {
            handler.postDelayed(this::startVoiceListening, 260);
        }

        syncComposerUi();
    }

    // -------------------------
    // Gemini (main responses + translation)
    // -------------------------

    private void triggerPrompt(String msg, RecyclerView rv) {
        activeLangOverrideTag = pendingLangOverrideTag;
        pendingLangOverrideTag = "";
        String detected = detectLangFromText(msg);
        String pref = extractLanguagePreference(msg);
        if (!TextUtils.isEmpty(pref)) {
            sessionLangOverrideTag = pref;
            activeLangOverrideTag = pref;
        } else {
            String normActive = normalizeLangTag(activeLangOverrideTag);
            if ("en".equals(normActive) && !TextUtils.isEmpty(sessionLangOverrideTag)) {
                // Do not override an explicit session language with English from a short message
                activeLangOverrideTag = "";
            }
            if (TextUtils.isEmpty(activeLangOverrideTag)) {
                if (!TextUtils.isEmpty(detected) && (!"en".equals(detected) || TextUtils.isEmpty(sessionLangOverrideTag))) {
                    activeLangOverrideTag = detected;
                }
            }
        }

        boolean needGeminiLangDetect = false;
        if (TextUtils.isEmpty(pref) && TextUtils.isEmpty(sessionLangOverrideTag)) {
            String normActive = normalizeLangTag(activeLangOverrideTag);
            if (TextUtils.isEmpty(normActive) || "en".equals(normActive)) {
                if (lastInputWasVoice || TextUtils.isEmpty(detected)) {
                    needGeminiLangDetect = true;
                }
            }
        }

        boolean isFood = isFoodQuestion(msg);
        boolean bookingFlowActive = isBookingFlowActive();
        if (isFood && !bookingFlowActive) {
            // Clear booking state to avoid yes/no prompts for unrelated questions
            awaitingBookingConfirm = false;
            awaitingDateTime = false;
            awaitingSuggestedTimeConfirm = false;
        }

        if (isOtherDoctorsQuery(msg)) {
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.PATIENT, msg, System.currentTimeMillis(), null), rv);
            showOtherDoctors(rv);
            return;
        }
        if (isCancelBookingQuery(msg)) {
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.PATIENT, msg, System.currentTimeMillis(), null), rv);
            cancelBookingFlow(rv);
            return;
        }
        if (isViewAppointmentQuery(msg)) {
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.PATIENT, msg, System.currentTimeMillis(), null), rv);
            showUpcomingAppointment(rv);
            return;
        }
        if (isViewMoreAvailabilityQuery(msg)) {
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.PATIENT, msg, System.currentTimeMillis(), null), rv);
            showMoreAvailability(rv);
            return;
        }
        if (awaitingRecordsConfirm) {
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.PATIENT, msg, System.currentTimeMillis(), null), rv);
            if (isYes(msg)) {
                awaitingRecordsConfirm = false;
                openRecordsTab();
                return;
            }
            if (isNo(msg)) {
                awaitingRecordsConfirm = false;
                addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, translate("Okay."), System.currentTimeMillis(), null), rv);
                return;
            }
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, translate("Please choose Yes or No."), System.currentTimeMillis(), null), rv);
            return;
        }
        if (awaitingMedicationInfo) {
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.PATIENT, msg, System.currentTimeMillis(), null), rv);
            if (isYes(msg)) {
                awaitingMedicationInfo = false;
                showMedicationInfo(rv);
                if (pendingBookingAfterMedication) {
                    pendingBookingAfterMedication = false;
                    addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, askBookText(), System.currentTimeMillis(), null), rv);
                }
                return;
            }
            if (isNo(msg)) {
                awaitingMedicationInfo = false;
                addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, translate("Okay."), System.currentTimeMillis(), null), rv);
                if (pendingBookingAfterMedication) {
                    pendingBookingAfterMedication = false;
                    addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, askBookText(), System.currentTimeMillis(), null), rv);
                }
                return;
            }
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, translate("Please choose Yes or No."), System.currentTimeMillis(), null), rv);
            return;
        }
        if (isPrescriptionsQuery(msg)) {
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.PATIENT, msg, System.currentTimeMillis(), null), rv);
            showPrescriptionsSummary(rv);
            return;
        }
        if (awaitingSymptoms || awaitingBookingConfirm || awaitingDateTime) {
            handleBookingFlow(msg, rv);
            return;
        }

        if (needGeminiLangDetect) {
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.PATIENT, msg, System.currentTimeMillis(), null), rv);
            showThinkingUi();
            detectLanguageWithGemini(msg, () -> {
                hideBusyUi();
                proceedAfterLangDetection(msg, rv, true);
            });
            return;
        }

        proceedAfterLangDetection(msg, rv, false);
    }

    private void proceedAfterLangDetection(String msg, RecyclerView rv, boolean patientAlreadyAdded) {
        BookingIntent bi = parseBookingIntent(msg);
        pendingBookIntent = bi.requested;
        pendingIntentDateIso = bi.dateIso;
        pendingIntentTime24 = bi.time24;

        if (!patientAlreadyAdded) {
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.PATIENT, msg, System.currentTimeMillis(), null), rv);
        }

        if (bi.requested && TextUtils.isEmpty(pendingSymptomsText) && !isLikelyProblemDescription(msg)) {
            awaitingSymptoms = true;
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, askProblemText(), System.currentTimeMillis(), null), rv);
            return;
        }

        showThinkingUi();

        boolean needBooking = lastUpcomingBookingFetchMs == 0;
        boolean needRx = TextUtils.isEmpty(prescriptionsSummary);

        if (needBooking || needRx) {
            new Thread(() -> {
                if (needBooking) ensureUpcomingBookingFlagSync();
                if (needRx) ensurePrescriptionsSummarySync();
                handler.post(() -> runGemini(msg, rv));
            }).start();
            return;
        }

        runGemini(msg, rv);
    }
    
    private boolean isBookingFlowActive() {
        return pendingBookIntent || awaitingBookingConfirm
                || awaitingDateTime || awaitingSuggestedTimeConfirm;
    }

    private void runGemini(String msg, RecyclerView rv) {
        String cacheKey = buildCacheKey(msg);
        String cached = geminiCache.get(cacheKey);
        if (!TextUtils.isEmpty(cached)) {
            handler.post(() -> {
                addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, cached, System.currentTimeMillis(), null), rv);
                hideBusyUi();
            });
            return;
        }

        // cheap prompt: minimal context; keeps Gemini tokens low
        String system = buildSystemPromptCheap();
        String payload = buildUserPayloadCheap(msg);

        labClient.sendText(system, payload, 220, new LabClient.Listener() {
            @Override public void onSuccess(@NonNull String replyText) {
                handler.post(() -> {
                    geminiCache.put(cacheKey, replyText);
                    final SpecialitySuggestion suggestion = parseSpecialitySuggestion(replyText);
                    String cleanReply = suggestion.cleanedText;
                    boolean doctorIntent = isBookingFlowActive() || isDoctorIntentQuestion(msg);
                    if (!doctorIntent) {
                        cleanReply = stripDoctorSuggestionLines(cleanReply);
                    }
                    boolean asksMedication = isMedicationInfoQuestion(cleanReply);
                    if (asksMedication && !doctorIntent) {
                        awaitingMedicationInfo = true;
                        pendingBookingAfterMedication = false;
                        awaitingBookingConfirm = false;
                    } else if (asksMedication) {
                        awaitingMedicationInfo = false;
                    }

                    String safeKey = doctorIntent ? sanitizeSpecialityKey(suggestion.key) : "";
                    String reason = doctorIntent ? suggestion.reason : "";

                    if (!TextUtils.isEmpty(safeKey) && doctorIntent) {
                        pendingSpecialityKey = safeKey;
                        pendingSpecialityLabel = specialityLabelForKey(safeKey);
                        pendingSpecialityReason = suggestion.reason;
                        pendingSymptomsText = msg;
                        awaitingBookingConfirm = true;

                        if (pendingBookIntent) {
                            addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI,
                                    translate("Okay. I will help you book an appointment."), System.currentTimeMillis(), null), rv);
                        } else {
                            addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, cleanReply, System.currentTimeMillis(), null), rv);
                        }

                        fetchTopDoctorAndPrompt(rv);
                    } else {
                        addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, cleanReply, System.currentTimeMillis(), null), rv);
                    }
                    hideBusyUi();
                });
            }

            @Override public void onError(@NonNull String message) {
                handler.post(() -> {
                    addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI,
                            translate("Sorry, I can't reach the medical assistant right now. Please try again shortly."), System.currentTimeMillis(), null), rv);
                    hideBusyUi();
                });
            }
        });
    }

    private void detectLanguageWithGemini(String msg, Runnable onDone) {
        if (labClient == null) {
            if (onDone != null) onDone.run();
            return;
        }
        String system = "Detect the language of the user text. Reply with ONLY one code: en,hi,ta,te,kn,ml. If unsure, reply en.";
        String payload = "Text=" + msg;
        labClient.sendText(system, payload, 6, new LabClient.Listener() {
            @Override public void onSuccess(@NonNull String replyText) {
                String tag = "";
                String lower = replyText == null ? "" : replyText.toLowerCase(Locale.ROOT);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(en|hi|ta|te|kn|ml)\\b").matcher(lower);
                if (m.find()) tag = normalizeLangTag(m.group(1));
                if (TextUtils.isEmpty(tag)) tag = normalizeLangTag(replyText);
                if (!TextUtils.isEmpty(tag)) {
                    sessionLangOverrideTag = tag;
                    activeLangOverrideTag = tag;
                    ttsLangOverrideTag = tag;
                }
                if (onDone != null) handler.post(onDone);
            }

            @Override public void onError(@NonNull String message) {
                if (onDone != null) handler.post(onDone);
            }
        });
    }

    private String buildSystemPromptCheap() {
        return "You are an inpatient bedside assistant inside a hospital app. "
                + "All consultations are IN-PERSON only. Never mention online/video/audio calls. "
                + "Be kind, direct, and specific. No diagnosis; give safe guidance. "
                + "Answer the user's exact question first. Avoid generic or repetitive scripts. "
                + "Keep replies short (2-4 short sentences). "
                + "Ask AT MOST one follow-up question ONLY if required info is missing. "
                + "If the user already provided a symptom/body part (including via UI chips), do NOT ask 'what are your symptoms' again. "
                + "If the user asks to book an appointment or contact a doctor AND a symptom is provided, pick the best specialty immediately. "
                + "Use prescriptions/diagnosis notes if provided; never invent lab values. "
                + "If risk is HIGH: advise urgent in-person doctor evaluation. "
                + "If you cannot confidently map to a specialty, leave it blank. "
                + "At the end include TWO lines:\n"
                + "SPECIALITY_KEY=<one of GENERAL_PHYSICIAN,CARDIOLOGY,NEUROLOGY,ORTHOPEDICS,OPHTHALMOLOGY,PEDIATRICS,DERMATOLOGY,PULMONOLOGY,DIABETOLOGY,FEVER_CLINIC,GENERAL_MEDICINE,EMERGENCY>\n"
                + "SPECIALITY_REASON=<short reason in the user's language>\n"
                + languageDirectiveStrict();
    }



    private String buildUserPayloadCheap(String msg) {
        // Keep payload small (cheap):
        // - risk+category
        // - top labs (few)
        // - user question
        StringBuilder sb = new StringBuilder();

        sb.append("Risk=").append(TextUtils.isEmpty(riskLevel) ? "LOW" : riskLevel).append("\n");
        sb.append("Category=").append(TextUtils.isEmpty(category) ? "General" : category).append("\n");

        if (lastLabs != null && !lastLabs.isEmpty()) {
            sb.append("Labs:\n");
            int i = 0;
            for (Map.Entry<String, String> e : lastLabs.entrySet()) {
                if (i++ >= 8) break;
                sb.append(e.getKey()).append("=").append(e.getValue()).append("\n");
            }
        } else {
            sb.append("Labs=none\n");
        }

        if (!TextUtils.isEmpty(prescriptionsSummary)) {
            sb.append("Prescriptions:\n").append(prescriptionsSummary).append("\n");
        } else {
            sb.append("Prescriptions=none\n");
        }

        // Extract medicines list explicitly for FOOD guidance
        String meds = extractMedicinesFromSummary(prescriptionsSummary);
        if (!TextUtils.isEmpty(meds)) {
            sb.append("Medicines=").append(meds).append("\n");
        } else {
            sb.append("Medicines=none\n");
        }

        sb.append("AllowedSpecialties=")
                .append(TextUtils.join(", ", ALLOWED_SPECIALITY_KEYS))
                .append("\n");

        if (!TextUtils.isEmpty(specialitiesSummary)) {
            sb.append("AvailableSpecialties=").append(specialitiesSummary).append("\n");
        } else {
            sb.append("AvailableSpecialties=unknown\n");
        }

        sb.append("UpcomingAppointments=").append(hasUpcomingBooking ? "exists" : "none").append("\n");

        if (isFoodQuestion(msg)) {
            sb.append("QuestionType=FOOD\n");
        }

        // Only include patientSummary when question clearly asks about "me"/context
        String q = msg.toLowerCase(Locale.ROOT);
        boolean needsProfile = q.contains("my") || q.contains("me") || q.contains("i ");
        if (needsProfile && !TextUtils.isEmpty(patientSummary)) {
            sb.append("Profile=").append(patientSummary).append("\n");
        }

        sb.append("Q=").append(msg);
        return sb.toString();
    }

    private String languageDirectiveStrict() {
        String lang = currentLangForAi();
        if ("te".equals(lang)) return "Respond ONLY in Telugu (Ã Â°Â¤Ã Â±â€ Ã Â°Â²Ã Â±ÂÃ Â°â€”Ã Â±Â) script.";
        if ("hi".equals(lang)) return "Respond ONLY in Hindi (Ã Â¤Â¹Ã Â¤Â¿Ã Â¤Â¨Ã Â¥ÂÃ Â¤Â¦Ã Â¥â‚¬).";
        if ("ta".equals(lang)) return "Respond ONLY in Tamil (Ã Â®Â¤Ã Â®Â®Ã Â®Â¿Ã Â®Â´Ã Â¯Â).";
        if ("kn".equals(lang)) return "Respond ONLY in Kannada (Ã Â²â€¢Ã Â²Â¨Ã Â³ÂÃ Â²Â¨Ã Â²Â¡).";
        if ("ml".equals(lang)) return "Respond ONLY in Malayalam (Ã Â´Â®Ã Â´Â²Ã Â´Â¯Ã Â´Â¾Ã Â´Â³Ã Â´â€š).";
        return "Respond in English.";
    }

    private String buildCacheKey(String msg) {
        String lang = currentLangForAi();
        // Add small context key so cache doesnÃ¢â‚¬â„¢t give wrong answers after risk/labs change
        String labsKey = (lastLabs != null ? String.valueOf(lastLabs.hashCode()) : "0");
        String rxKey = !TextUtils.isEmpty(prescriptionsSummary) ? String.valueOf(prescriptionsSummary.hashCode()) : "0";
        String specKey = !TextUtils.isEmpty(specialitiesSummary) ? String.valueOf(specialitiesSummary.hashCode()) : "0";
        String riskKey = TextUtils.isEmpty(riskLevel) ? "LOW" : riskLevel;
        String catKey = TextUtils.isEmpty(category) ? "General" : category;
        return lang + "|" + riskKey + "|" + catKey + "|" + labsKey + "|" + rxKey + "|" + specKey + "|" + msg.trim();
    }

    private String currentLangForAi() {
        String tag = normalizeLangTag(activeLangOverrideTag);
        if (!TextUtils.isEmpty(tag)) return tag;
        String session = normalizeLangTag(sessionLangOverrideTag);
        if (!TextUtils.isEmpty(session)) return session;
        return TranslationManager.currentLang(requireContext());
    }

    private String normalizeLangTag(String tag) {
        if (TextUtils.isEmpty(tag)) return "";
        String t = tag.trim().toLowerCase(Locale.ROOT);
        if (t.contains("-")) t = t.substring(0, t.indexOf("-"));
        if (t.contains("_")) t = t.substring(0, t.indexOf("_"));
        if (t.startsWith("hi")) return "hi";
        if (t.startsWith("ta")) return "ta";
        if (t.startsWith("te")) return "te";
        if (t.startsWith("kn")) return "kn";
        if (t.startsWith("ml")) return "ml";
        if (t.startsWith("en")) return "en";
        return "";
    }

    private String detectLangFromText(String msg) {
        if (TextUtils.isEmpty(msg)) return "";
        for (int i = 0; i < msg.length(); i++) {
            char c = msg.charAt(i);
            // Telugu
            if (c >= 0x0C00 && c <= 0x0C7F) return "te";
            // Tamil
            if (c >= 0x0B80 && c <= 0x0BFF) return "ta";
            // Kannada
            if (c >= 0x0C80 && c <= 0x0CFF) return "kn";
            // Malayalam
            if (c >= 0x0D00 && c <= 0x0D7F) return "ml";
            // Devanagari (Hindi)
            if (c >= 0x0900 && c <= 0x097F) return "hi";
        }
        String m = msg.toLowerCase(Locale.ROOT);
        if (m.contains("telugu")) return "te";
        if (m.contains("tamil")) return "ta";
        if (m.contains("kannada")) return "kn";
        if (m.contains("malayalam")) return "ml";
        if (m.contains("hindi")) return "hi";
        if (m.contains("english")) return "en";
        return "";
    }

    private String extractLanguagePreference(String msg) {
        if (TextUtils.isEmpty(msg)) return "";
        String m = msg.toLowerCase(Locale.ROOT);
        if (m.contains("speak in telugu") || m.contains("reply in telugu") || m.contains("respond in telugu") || m.contains("speak with me in telugu")) return "te";
        if (m.contains("speak in tamil") || m.contains("reply in tamil") || m.contains("respond in tamil") || m.contains("speak with me in tamil")) return "ta";
        if (m.contains("speak in kannada") || m.contains("reply in kannada") || m.contains("respond in kannada") || m.contains("speak with me in kannada")) return "kn";
        if (m.contains("speak in malayalam") || m.contains("reply in malayalam") || m.contains("respond in malayalam") || m.contains("speak with me in malayalam")) return "ml";
        if (m.contains("speak in hindi") || m.contains("reply in hindi") || m.contains("respond in hindi") || m.contains("speak with me in hindi")) return "hi";
        if (m.contains("speak in english") || m.contains("reply in english") || m.contains("respond in english") || m.contains("speak with me in english")) return "en";
        return "";
    }

    private boolean isFoodQuestion(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase(Locale.ROOT);
        return m.contains("food") || m.contains("diet") || m.contains("eat") || m.contains("eating")
                || m.contains("meal") || m.contains("nutrition")
                || m.contains("khana") || m.contains("khana") || m.contains("aahar") || m.contains("aahaar")
                || m.contains("ÃƒÂ Ã‚Â¤Ã¢â‚¬â€œÃƒÂ Ã‚Â¤Ã‚Â¾ÃƒÂ Ã‚Â¤Ã‚Â¨") || m.contains("ÃƒÂ Ã‚Â¤Ã¢â‚¬â€œÃƒÂ Ã‚Â¤Ã‚Â¾ÃƒÂ Ã‚Â¤Ã‚Â¨ÃƒÂ Ã‚Â¤Ã‚Â¾") || m.contains("ÃƒÂ Ã‚Â¤Ã¢â‚¬Â ÃƒÂ Ã‚Â¤Ã‚Â¹ÃƒÂ Ã‚Â¤Ã‚Â¾ÃƒÂ Ã‚Â¤Ã‚Â°")
                || m.contains("ÃƒÂ Ã‚Â®Ã¢â‚¬Â°ÃƒÂ Ã‚Â®Ã‚Â£ÃƒÂ Ã‚Â®Ã‚Âµ") || m.contains("ÃƒÂ Ã‚Â®Ã¢â‚¬Â°ÃƒÂ Ã‚Â®Ã‚Â£ÃƒÂ Ã‚Â®Ã‚ÂµÃƒÂ Ã‚Â¯Ã‚Â") || m.contains("ÃƒÂ Ã‚Â®Ã‚Â¡ÃƒÂ Ã‚Â¯Ã‹â€ ÃƒÂ Ã‚Â®Ã…Â¸ÃƒÂ Ã‚Â¯Ã‚Â")
                || m.contains("ÃƒÂ Ã‚Â°Ã¢â‚¬Â ÃƒÂ Ã‚Â°Ã‚Â¹ÃƒÂ Ã‚Â°Ã‚Â¾ÃƒÂ Ã‚Â°Ã‚Â°") || m.contains("ÃƒÂ Ã‚Â°Ã‚Â­ÃƒÂ Ã‚Â±Ã¢â‚¬Â¹ÃƒÂ Ã‚Â°Ã…â€œÃƒÂ Ã‚Â°Ã‚Â¨") || m.contains("ÃƒÂ Ã‚Â°Ã‚Â¡ÃƒÂ Ã‚Â±Ã‹â€ ÃƒÂ Ã‚Â°Ã…Â¸ÃƒÂ Ã‚Â±Ã‚Â")
                || m.contains("ÃƒÂ Ã‚Â²Ã¢â‚¬Â ÃƒÂ Ã‚Â²Ã‚Â¹ÃƒÂ Ã‚Â²Ã‚Â¾ÃƒÂ Ã‚Â²Ã‚Â°") || m.contains("ÃƒÂ Ã‚Â²Ã‚Â­ÃƒÂ Ã‚Â³Ã¢â‚¬Â¹ÃƒÂ Ã‚Â²Ã…â€œÃƒÂ Ã‚Â²Ã‚Â¨") || m.contains("ÃƒÂ Ã‚Â²Ã‚Â¡ÃƒÂ Ã‚Â³Ã‹â€ ÃƒÂ Ã‚Â²Ã…Â¸ÃƒÂ Ã‚Â³Ã‚Â")
                || m.contains("ÃƒÂ Ã‚Â´Ã¢â‚¬Â ÃƒÂ Ã‚Â´Ã‚Â¹ÃƒÂ Ã‚Â´Ã‚Â¾ÃƒÂ Ã‚Â´Ã‚Â°") || m.contains("ÃƒÂ Ã‚Â´Ã‚Â­ÃƒÂ Ã‚Â´Ã¢â‚¬Â¢ÃƒÂ Ã‚ÂµÃ‚ÂÃƒÂ Ã‚Â´Ã‚Â·ÃƒÂ Ã‚ÂµÃ‚ÂÃƒÂ Ã‚Â´Ã‚Â¯") || m.contains("ÃƒÂ Ã‚Â´Ã‚Â¡ÃƒÂ Ã‚Â´Ã‚Â¯ÃƒÂ Ã‚Â´Ã‚Â±ÃƒÂ Ã‚ÂµÃ‚ÂÃƒÂ Ã‚Â´Ã‚Â±");
    }

    // -------------------------
    // Composer behavior (ChatGPT-like)
    // -------------------------

    private void setupComposerBehavior() {
        if (composerWatcher != null) return;

        composerWatcher = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!isAdded()) return;
                syncComposerUi();
            }
            @Override public void afterTextChanged(android.text.Editable s) { }
        };
        if (etInput != null) etInput.addTextChangedListener(composerWatcher);
    }

    private void syncComposerUi() {
        if (etInput == null || btnMic == null || sendButton == null) return;

        boolean hasText = etInput.getText() != null && !TextUtils.isEmpty(etInput.getText().toString().trim());

        // GPT-like lock: disable input + chips while assistant is responding
        etInput.setEnabled(!isBusy);
        if (chipsContainer != null) chipsContainer.setEnabled(!isBusy);
        for (TextView chip : chipViews) {
            if (chip != null) chip.setEnabled(!isBusy);
        }

        if (isBusy) {
            // busy => show STOP (send button), hide mic
            btnMic.setVisibility(View.GONE);
            sendButton.setVisibility(View.VISIBLE);
            setSendModeStop(true);
            return;
        }

        // empty => show mic, hide send
        // typing => show send, hide mic
        btnMic.setVisibility(hasText ? View.GONE : View.VISIBLE);
        sendButton.setVisibility(hasText ? View.VISIBLE : View.GONE);
        setSendModeStop(false);
    }

    private void resetConversationState() {
        pendingSpecialityKey = "";
        pendingSpecialityLabel = "";
        pendingSpecialityReason = "";
        pendingSymptomsText = "";
        awaitingSymptoms = false;
        awaitingBookingConfirm = false;
        pendingDoctorId = 0L;
        pendingDoctorName = "";
        pendingDoctorFee = 0;
        pendingConsultType = "";
        pendingDateIso = "";
        pendingTime24 = "";
        awaitingDateTime = false;
        awaitingSuggestedTimeConfirm = false;
        suggestedDateIso = "";
        suggestedTime24 = "";
        pendingBookIntent = false;
        pendingIntentDateIso = "";
        pendingIntentTime24 = "";
        awaitingMedicationInfo = false;
        pendingBookingAfterMedication = false;
        activeLangOverrideTag = "";
        pendingLangOverrideTag = "";
        ttsLangOverrideTag = "";
        sessionLangOverrideTag = "";
        lastInputWasVoice = false;
        autoSpeakNextAi = false;
        hideBusyUi();
    }

    private void setSendModeStop(boolean stop) {
        if (sendButton == null) return;
        sendButton.setText(""); // icon-only
        sendButton.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);

        if (stop) {
            sendButton.setIconResource(R.drawable.ic_stop_24);
            sendButton.setContentDescription(translate("Stop"));
        } else {
            sendButton.setIconResource(R.drawable.ic_send_24);
            sendButton.setContentDescription(translate("Send"));
        }
    }

    // -------------------------
    // Busy UI
    // -------------------------

    private void showThinkingUi() {
        isBusy = true;
        AssistantUiBridge.setSpeaking(true);

        if (aiStateRow != null) aiStateRow.setVisibility(View.VISIBLE);
        if (voiceWaveView != null) voiceWaveView.setVisibility(View.GONE);
        if (listeningLabel != null) listeningLabel.setVisibility(View.GONE);

        if (typingDotsView != null) typingDotsView.setVisibility(View.VISIBLE);

        if (shimmerAnalyzing != null) {
            shimmerAnalyzing.setVisibility(View.VISIBLE);
            shimmerAnalyzing.startShimmer();
        }

        playLottie(lottieThinking, "lottie/assistant_thinking.json");
        stopLottie(lottieListening);

        AiMotionKit.startThinking(aiStateRow);

        syncComposerUi();
    }

    private void showListeningUi() {
        isBusy = true;
        AssistantUiBridge.setSpeaking(true);

        if (aiStateRow != null) aiStateRow.setVisibility(View.VISIBLE);
        if (voiceWaveView != null) voiceWaveView.setVisibility(View.VISIBLE);
        if (listeningLabel != null) {
            listeningLabel.setVisibility(View.VISIBLE);
            listeningLabel.setText(translate("Listening..."));
        }

        if (typingDotsView != null) typingDotsView.setVisibility(View.GONE);
        if (shimmerAnalyzing != null) {
            shimmerAnalyzing.stopShimmer();
            shimmerAnalyzing.setVisibility(View.GONE);
        }

        playLottie(lottieListening, "lottie/assistant_listening.json");
        stopLottie(lottieThinking);

        syncComposerUi();
    }

    private void hideBusyUi() {
        isBusy = false;
        voiceListening = false;
        voiceStopRequested = false;
        AssistantUiBridge.setSpeaking(false);

        if (aiStateRow != null) aiStateRow.setVisibility(View.GONE);
        if (shimmerAnalyzing != null) shimmerAnalyzing.stopShimmer();

        stopLottie(lottieListening);
        stopLottie(lottieThinking);

        AiMotionKit.stopAllIn(aiStateRow);

        syncComposerUi();
    }

    private void stopThinkingUiOnly() {
        // Stop UI only; keeps your current architecture minimal
        if (streamingRenderer != null) streamingRenderer.cancel();
        hideBusyUi();
    }

    private void stopVoiceUiOnly() {
        handler.removeCallbacks(voiceFinalizeFallback);
        voiceListening = false;
        voiceStopRequested = false;
        hideBusyUi();
    }

    // -------------------------
    // Voice input (on-device only)
    // -------------------------

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioPermission() {
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) startVoiceListening();
        }
    }

    private void ensureSpeechRecognizer() {
        if (!isAdded()) return;
        if (speechRecognizer != null) return;
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) return;

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext());

        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);

        // Let recognizer auto-detect spoken language
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "und");
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "und");
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }

            @Override public void onError(int error) {
                if (!isAdded()) return;
                // If user pressed stop, use partial; else just stop UI
                if (voiceStopRequested && !TextUtils.isEmpty(lastPartialVoice)) {
                    finalizeVoiceAndSend(lastPartialVoice);
                } else {
                    stopVoiceUiOnly();
                }
            }

            @Override public void onResults(Bundle results) {
                if (!isAdded()) return;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String best = (matches != null && !matches.isEmpty()) ? matches.get(0) : "";

                String langTag = "";
                try {
                    String tag1 = results.getString(RecognizerIntent.EXTRA_LANGUAGE);
                    String tag2 = results.getString("android.speech.extra.LANGUAGE");
                    langTag = !TextUtils.isEmpty(tag1) ? tag1 : tag2;
                } catch (Throwable ignored) {}
                if (!TextUtils.isEmpty(langTag)) {
                    pendingLangOverrideTag = langTag;
                }

                if (!TextUtils.isEmpty(best)) finalizeVoiceAndSend(best);
                else if (!TextUtils.isEmpty(lastPartialVoice)) finalizeVoiceAndSend(lastPartialVoice);
                else stopVoiceUiOnly();
            }

            @Override public void onPartialResults(Bundle partialResults) {
                ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String best = (matches != null && !matches.isEmpty()) ? matches.get(0) : "";
                if (!TextUtils.isEmpty(best)) {
                    lastPartialVoice = best;
                    String lang = detectLangFromText(best);
                    if (!TextUtils.isEmpty(lang)) {
                        pendingLangOverrideTag = lang;
                    }
                }
            }

            @Override public void onEvent(int eventType, Bundle params) { }
        });
    }

    private void startVoiceListening() {
        if (!isAdded()) return;

        if (!hasAudioPermission()) {
            requestAudioPermission();
            return;
        }

        ensureSpeechRecognizer();
        if (speechRecognizer == null || speechIntent == null) return;

        lastPartialVoice = "";
        voiceStopRequested = false;

        showListeningUi();
        voiceListening = true;

        handler.removeCallbacks(voiceFinalizeFallback);

        try {
            speechRecognizer.cancel();
            speechRecognizer.startListening(speechIntent);
        } catch (Exception ignore) {
            stopVoiceUiOnly();
        }
    }

    private void stopVoiceListening() {
        if (!isAdded()) return;
        if (speechRecognizer == null) {
            stopVoiceUiOnly();
            return;
        }

        voiceStopRequested = true;
        if (listeningLabel != null) listeningLabel.setText(translate("Processing..."));

        try {
            speechRecognizer.stopListening();
        } catch (Exception ignore) {
            finalizeVoiceAndSend(lastPartialVoice);
            return;
        }

        // If final results donÃ¢â‚¬â„¢t arrive, use partial in 1200ms
        handler.removeCallbacks(voiceFinalizeFallback);
        handler.postDelayed(voiceFinalizeFallback, 1200);
    }

    private void finalizeVoiceAndSend(String text) {
        handler.removeCallbacks(voiceFinalizeFallback);

        String cleaned = text != null ? text.trim() : "";
        if (cleaned.isEmpty()) {
            stopVoiceUiOnly();
            return;
        }

        voiceListening = false;
        voiceStopRequested = false;

        hideBusyUi();

        // Auto-send recognized text as prompt to Gemini (Gemini handles translations)
        lastInputWasVoice = true;
        autoSpeakNextAi = true;
        triggerPrompt(cleaned, rvChat);
    }

    // -------------------------
    // TTS (on-device read aloud only)
    // -------------------------

    private void initTts() {
        if (!isAdded()) return;
        if (tts != null) return;

        ttsReady = false;
        tts = new TextToSpeech(requireContext().getApplicationContext(), status -> {
            if (!isAdded()) return;
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                applyTtsLanguage();
            } else {
                ttsReady = false;
            }
        });
    }

    private void applyTtsLanguage() {
        if (tts == null) return;

        Locale locale = localeForAppLanguage();
        try {
            int res = tts.setLanguage(locale);
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US);
            }
        } catch (Exception ignore) {
            try { tts.setLanguage(Locale.US); } catch (Exception ignored2) {}
        }
    }

    private void speakMessageText(@NonNull String text) {
        if (!isAdded()) return;
        if (TextUtils.isEmpty(text)) return;

        if (tts == null || !ttsReady) {
            initTts();
            return;
        }

        applyTtsLanguageForOverride();
        try { tts.stop(); } catch (Exception ignore) {}

        try {
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "msg_" + System.currentTimeMillis());
        } catch (Exception ignore) { }
    }

    private void applyTtsLanguageForOverride() {
        if (tts == null) return;
        String tag = normalizeLangTag(ttsLangOverrideTag);
        if (TextUtils.isEmpty(tag)) {
            applyTtsLanguage();
            return;
        }
        Locale loc = localeForLangCode(tag);
        try {
            int res = tts.setLanguage(loc);
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                applyTtsLanguage();
            }
        } catch (Exception ignore) {
            applyTtsLanguage();
        }
    }

    private Locale localeForLangCode(String code) {
        switch (code) {
            case "hi": return new Locale("hi", "IN");
            case "ta": return new Locale("ta", "IN");
            case "te": return new Locale("te", "IN");
            case "kn": return new Locale("kn", "IN");
            case "ml": return new Locale("ml", "IN");
            default: return Locale.US;
        }
    }

    private void shutdownTts() {
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Exception ignore) {}
        tts = null;
        ttsReady = false;
    }

    // -------------------------
    // Chips
    // -------------------------

    private void setupChips(LinearLayout container, EditText input, RecyclerView rv) {
        if (container == null || input == null) return;
        chipViews.clear();
        container.removeAllViews();

        String[] base = hasUpcomingBooking ? CHIP_TEXTS_WITH_UPCOMING : CHIP_TEXTS;
        for (String text : base) {
            TextView chip = (TextView) LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_quick_chip, container, false);
            chip.setTag(text);
            String label = translate(text);
            chip.setText(label);
            chip.setOnClickListener(v -> {
                if (isBusy) return;
                String raw = (String) v.getTag();
                if (handleQuickAction(raw, rv)) return;
                input.setText("");
                triggerPrompt(label, rv);
            });
            container.addView(chip);
            chipViews.add(chip);
        }
        AiMotionKit.animateChips(container);
    }

    private void setChips(List<String> labels, RecyclerView rv) {
        if (chipsContainer == null) return;
        chipsContainer.removeAllViews();
        chipViews.clear();
        if (labels == null || labels.isEmpty()) {
            setupChips(chipsContainer, etInput, rv);
            return;
        }
        for (String raw : labels) {
            TextView chip = (TextView) LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_quick_chip, chipsContainer, false);
            String label = translate(raw);
            chip.setTag(raw);
            chip.setText(label);
            chip.setOnClickListener(v -> {
                if (isBusy) return;
                String tag = (String) v.getTag();
                if (handleQuickAction(tag, rv)) return;
                etInput.setText("");
                triggerPrompt(label, rv);
            });
            chipsContainer.addView(chip);
            chipViews.add(chip);
        }
        AiMotionKit.animateChips(chipsContainer);
    }

    private void updateChipsForContext(RecyclerView rv) {
        if (!isAdded()) return;
        if (awaitingSymptoms) {
            setChips(appendCancelChip(symptomChipList()), rv);
            return;
        }
        if (awaitingMedicationInfo) {
            setChips(yesNoChipList(), rv);
            return;
        }
        if (awaitingBookingConfirm || awaitingSuggestedTimeConfirm) {
            setChips(appendCancelChip(yesNoChipList()), rv);
            return;
        }
        if (awaitingDateTime) {
            List<String> list = new ArrayList<>();
            list.add("Cancel booking");
            setChips(list, rv);
            return;
        }
        if (requestAiChips(rv)) return;
        setupChips(chipsContainer, etInput, rv);
    }

    private boolean requestAiChips(RecyclerView rv) {
        if (!isAdded() || chipsContainer == null) return false;
        if (chipAiInFlight) return true;
        String source = lastAssistantTextForChips != null ? lastAssistantTextForChips.trim() : "";
        if (TextUtils.isEmpty(source)) return false;
        // Avoid AI chips for inline-action messages like availability
        String lower = source.toLowerCase(Locale.ROOT);
        if (lower.contains("availability") || lower.contains("slots")) return false;
        boolean looksLikeQuestion = source.contains("?") || lower.contains("do you") || lower.contains("would you")
                || lower.contains("please choose") || lower.contains("which") || lower.contains("select");
        if (!looksLikeQuestion) return false;

        long now = SystemClock.elapsedRealtime();
        if (source.equals(lastChipAiSource) && (now - lastChipAiAtMs) < 1500L) return true;

        chipAiInFlight = true;
        lastChipAiSource = source;
        lastChipAiAtMs = now;

        String lang = currentLangForAi();
        String system =
                "You generate 3-4 short suggestion chips for a medical assistant. " +
                "Return ONLY a JSON array of strings. " +
                "Chips must be 2-4 words each and actionable. " +
                "If the assistant asked a question with choices, include the best choices. " +
                "If the assistant asked Yes/No, include Yes and No. " +
                "Use the same language as the conversation language tag: " + lang + ".";
        String payload = "ASSISTANT_TEXT=" + source;

        LabClient lab = new LabClient(requireContext());
        lab.sendText(system, payload, 120, new LabClient.Listener() {
            @Override public void onSuccess(String replyText) {
                handler.post(() -> {
                    chipAiInFlight = false;
                    List<String> labels = parseChipList(replyText);
                    if (labels == null || labels.isEmpty()) {
                        setupChips(chipsContainer, etInput, rv);
                        return;
                    }
                    setChips(labels, rv);
                });
            }

            @Override public void onError(String message) {
                handler.post(() -> {
                    chipAiInFlight = false;
                    setupChips(chipsContainer, etInput, rv);
                });
            }
        });
        return true;
    }

    private List<String> parseChipList(String reply) {
        if (TextUtils.isEmpty(reply)) return null;
        String text = reply.trim();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(text);
            List<String> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.optString(i, "");
                String clean = sanitizeChipText(v);
                if (!TextUtils.isEmpty(clean)) out.add(clean);
            }
            return out;
        } catch (Throwable ignored) {}
        // Fallback: try to extract lines
        String[] lines = text.split("\\r?\\n");
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String s = line.trim();
            if (s.startsWith("-")) s = s.substring(1).trim();
            s = sanitizeChipText(s);
            if (!TextUtils.isEmpty(s)) out.add(s);
            if (out.size() >= 4) break;
        }
        return out;
    }

    private boolean isMedicationInfoQuestion(String text) {
        if (TextUtils.isEmpty(text)) return false;
        String t = text.toLowerCase(Locale.ROOT);
        boolean hasMed = t.contains("medication") || t.contains("medicine") || t.contains("medicines") || t.contains("dose") || t.contains("dosage");
        boolean ask = t.contains("?") || t.contains("would you like") || t.contains("do you want") || t.contains("know more");
        return hasMed && ask;
    }

    private String stripDoctorSuggestionLines(String text) {
        if (TextUtils.isEmpty(text)) return text;
        String[] lines = text.split("\\r?\\n");
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String l = line.trim().toLowerCase(Locale.ROOT);
            if (l.startsWith("suitable specialist") || l.startsWith("recommended doctor")
                    || l.startsWith("consultation fee") || l.startsWith("specialist:")
                    || l.contains("suitable specialist") || l.contains("recommended doctor")) {
                continue;
            }
            if (out.length() > 0) out.append("\n");
            out.append(line);
        }
        return out.toString().trim();
    }

    private boolean isDoctorIntentQuestion(String msg) {
        if (TextUtils.isEmpty(msg)) return false;
        String m = msg.toLowerCase(Locale.ROOT);
        return m.contains("doctor") || m.contains("specialist") || m.contains("appointment")
                || m.contains("book") || m.contains("consult") || m.contains("clinic") || m.contains("visit");
    }

    private String sanitizeSpecialityKey(String key) {
        if (TextUtils.isEmpty(key)) return "";
        String k = key.trim().toUpperCase(Locale.ROOT);
        return ALLOWED_SPECIALITY_KEYS.contains(k) ? k : "";
    }

    private String sanitizeChipText(String s) {
        if (TextUtils.isEmpty(s)) return "";
        String t = s.trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() > 1) {
            t = t.substring(1, t.length() - 1).trim();
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.contains("json") || lower.contains("```") || lower.contains("{") || lower.contains("}")
                || lower.contains("[") || lower.contains("]") || lower.contains(":")) {
            return "";
        }
        if (t.equalsIgnoreCase("yes") || t.equalsIgnoreCase("no")) return t;
        if (t.length() > 32) return "";
        return t;
    }

    private List<String> appendCancelChip(List<String> base) {
        List<String> out = new ArrayList<>(base);
        out.add("Cancel booking");
        return out;
    }

    private boolean handleQuickAction(String raw, RecyclerView rv) {
        if (raw == null) return false;
        if ("View my appointment".equalsIgnoreCase(raw)) {
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.PATIENT, translate(raw), System.currentTimeMillis(), null), rv);
            showUpcomingAppointment(rv);
            return true;
        }
        if ("View in detail".equalsIgnoreCase(raw)) {
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.PATIENT, translate(raw), System.currentTimeMillis(), null), rv);
            if (lastUpcomingSnapshot != null) {
                openAppointmentDetails(lastUpcomingSnapshot);
            } else {
                showUpcomingAppointment(rv);
            }
            return true;
        }
        if ("Go to bookings".equalsIgnoreCase(raw)) {
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.PATIENT, translate(raw), System.currentTimeMillis(), null), rv);
            openBookingsTab();
            return true;
        }
        if ("Show my prescriptions".equalsIgnoreCase(raw)) {
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.PATIENT, translate(raw), System.currentTimeMillis(), null), rv);
            showPrescriptionsSummary(rv);
            return true;
        }
        if ("Emergency help".equalsIgnoreCase(raw)) {
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.PATIENT, translate(raw), System.currentTimeMillis(), null), rv);
            triggerEmergencyCall();
            return true;
        }
        if ("Cancel booking".equalsIgnoreCase(raw)) {
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.PATIENT, translate(raw), System.currentTimeMillis(), null), rv);
            cancelBookingFlow(rv);
            return true;
        }
        return false;
    }

    private List<String> appointmentDetailChipList() {
        List<String> list = new ArrayList<>();
        list.add("View in detail");
        list.add("Go to bookings");
        list.add("Show my prescriptions");
        return list;
    }

    private void openBookingsTab() {
        if (!isAdded()) return;
        if (getActivity() instanceof PatientHomeFragment.HomeNav) {
            ((PatientHomeFragment.HomeNav) getActivity()).openBookingsTab();
            closeAssistantAfterNav();
        }
    }

    private void openRecordsTab() {
        if (!isAdded()) return;
        if (getActivity() instanceof PatientHomeFragment.HomeNav) {
            ((PatientHomeFragment.HomeNav) getActivity()).openRecordsTab();
            closeAssistantAfterNav();
        }
    }

    private void triggerEmergencyCall() {
        if (!isAdded()) return;
        try {
            Intent itn = new Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:108"));
            startActivity(itn);
            closeAssistantAfterNav();
        } catch (Throwable ignored) {}
    }

    private void closeAssistantAfterNav() {
        try {
            if (isAdded()) dismiss();
        } catch (Throwable ignored) {}
    }

    private void cancelBookingFlow(RecyclerView rv) {
        awaitingSymptoms = false;
        awaitingBookingConfirm = false;
        awaitingDateTime = false;
        awaitingSuggestedTimeConfirm = false;
        awaitingRecordsConfirm = false;
        awaitingMedicationInfo = false;
        pendingBookingAfterMedication = false;
        pendingBookIntent = false;
        pendingIntentDateIso = "";
        pendingIntentTime24 = "";
        pendingConsultType = "";
        pendingDateIso = "";
        pendingTime24 = "";
        suggestedDateIso = "";
        suggestedTime24 = "";
        addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI,
                translate("Okay, cancelled. Let me know if you want to book later."),
                System.currentTimeMillis(), null), rv);
        updateChipsForContext(rv);
    }

    private void showPrescriptionsSummary(RecyclerView rv) {
        showThinkingUi();
        new Thread(() -> {
            ensurePrescriptionsSummarySync();
            String summary = prescriptionsSummary;
            handler.post(() -> {
                hideBusyUi();
                if (TextUtils.isEmpty(summary)) {
                    addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI,
                            translate("I could not find any prescriptions yet."), System.currentTimeMillis(), null), rv);
                } else {
                    addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, summary, System.currentTimeMillis(), null), rv);
                    awaitingRecordsConfirm = true;
                    java.util.ArrayList<String> actions = new java.util.ArrayList<>();
                    actions.add(translate("Yes"));
                    actions.add(translate("No"));
                    addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI,
                            translate("If you want, I can open full records."), System.currentTimeMillis(), null, actions), rv);
                }
            });
        }).start();
    }

    private void showOtherDoctors(RecyclerView rv) {
        if (TextUtils.isEmpty(pendingSpecialityKey)) {
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI,
                    translate("Please tell me your symptoms so I can suggest the right doctors."),
                    System.currentTimeMillis(), null), rv);
            awaitingSymptoms = true;
            updateChipsForContext(rv);
            return;
        }

        showThinkingUi();
        new Thread(() -> {
            JSONArray docs = null;
            try {
                docs = PatientApi.INSTANCE.getDoctors(requireContext(), pendingSpecialityKey);
            } catch (Throwable ignored) {}

            JSONArray finalDocs = docs;
            handler.post(() -> {
                hideBusyUi();
                if (finalDocs == null || finalDocs.length() == 0) {
                    addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI,
                            translate("No other doctors are available right now."), System.currentTimeMillis(), null), rv);
                    updateChipsForContext(rv);
                    return;
                }

                addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI,
                        translate("Showing other available doctors."), System.currentTimeMillis(), null), rv);
                openBookingFlow(pendingSpecialityKey, pendingSpecialityLabel, pendingSymptomsText, false);
            });
        }).start();
    }

    private void showUpcomingAppointment(RecyclerView rv) {
        showThinkingUi();
        new Thread(() -> {
            AppointmentSnapshot snap = null;
            try {
                JSONArray arr = PatientApi.INSTANCE.listAppointments(requireContext(), "ALL", 200, 0);
                snap = pickLatestUpcoming(arr);
            } catch (Throwable ignored) {}

            AppointmentSnapshot finalSnap = snap;
            handler.post(() -> {
                hideBusyUi();
                if (finalSnap == null) {
                    addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI,
                            translate("You have no upcoming appointments. Do you want me to book one?"),
                            System.currentTimeMillis(), null), rv);
                    return;
                }
                lastUpcomingSnapshot = finalSnap;
                addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI,
                        buildUpcomingSummary(finalSnap),
                        System.currentTimeMillis(), null), rv);
                setChips(appointmentDetailChipList(), rv);
            });
        }).start();
    }

    private void openAppointmentDetails(AppointmentSnapshot ap) {
        if (!isAdded()) return;
        Intent itn = new Intent(requireContext(), PatientAppointmentDetailsActivity.class);
        if (!TextUtils.isEmpty(ap.appointmentId)) {
            itn.putExtra("appointment_id", ap.appointmentId);
            itn.putExtra("appointmentId", ap.appointmentId);
            itn.putExtra("id", ap.appointmentId);
        }
        if (!TextUtils.isEmpty(ap.publicCode)) {
            itn.putExtra("public_code", ap.publicCode);
            itn.putExtra("publicCode", ap.publicCode);
        }
        if (ap.doctorId > 0) {
            itn.putExtra("doctorId", ap.doctorId);
            itn.putExtra("doctor_id", ap.doctorId);
        }
        startActivity(itn);
        closeAssistantAfterNav();
    }

    private static class AppointmentSnapshot {
        final String appointmentId;
        final String publicCode;
        final int doctorId;
        final String doctorName;
        final String consultType;
        final String scheduledAt;
        final String status;
        final String consultLink;

        AppointmentSnapshot(String appointmentId, String publicCode, int doctorId, String doctorName,
                            String consultType, String scheduledAt, String status, String consultLink) {
            this.appointmentId = appointmentId;
            this.publicCode = publicCode;
            this.doctorId = doctorId;
            this.doctorName = doctorName;
            this.consultType = consultType;
            this.scheduledAt = scheduledAt;
            this.status = status;
            this.consultLink = consultLink;
        }
    }

    private AppointmentSnapshot pickLatestUpcoming(JSONArray arr) {
        if (arr == null || arr.length() == 0) return null;
        AppointmentSnapshot best = null;
        long bestTime = Long.MAX_VALUE;
        long now = System.currentTimeMillis();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            AppointmentSnapshot snap = snapshotFrom(o);
            if (snap == null) continue;
            if (!isUpcomingStatus(snap)) continue;

            Long t = parseTimeMs(snap.scheduledAt);
            long time = t != null ? t : (now + 10_000_000L);
            if (time < bestTime) {
                bestTime = time;
                best = snap;
            }
        }
        return best;
    }

    private AppointmentSnapshot snapshotFrom(JSONObject o) {
        String appointmentId = parseIdAny(o, "appointmentId", "appointment_id", "id");
        String publicCode = o.optString("public_code", o.optString("publicCode", ""));
        int doctorId = o.optInt("doctor_id", o.optInt("doctorId", 0));
        String doctorName = o.optString("doctor_name", o.optString("doctorName", ""));
        String consultType = o.optString("consult_type", o.optString("consultType", ""));
        String scheduledAt = o.optString("scheduled_at", o.optString("scheduledAt", ""));
        String status = o.optString("status", "BOOKED");
        String link = o.optString("meeting_link",
                o.optString("meet_link",
                        o.optString("call_link",
                                o.optString("consult_link", ""))));

        if (TextUtils.isEmpty(appointmentId) && TextUtils.isEmpty(publicCode)) return null;
        return new AppointmentSnapshot(appointmentId, publicCode, doctorId, doctorName, consultType, scheduledAt, status, link);
    }

    private String parseIdAny(JSONObject o, String... keys) {
        if (o == null) return "";
        for (String k : keys) {
            Object v = o.opt(k);
            if (v instanceof Number) {
                long n = ((Number) v).longValue();
                if (n > 0) return Long.toString(n);
            } else if (v instanceof String) {
                String s = ((String) v).trim();
                if (!s.isEmpty()) return s;
            }
        }
        return "";
    }

    private boolean isUpcomingStatus(AppointmentSnapshot r) {
        String st = r.status == null ? "" : r.status.trim().toUpperCase(Locale.US);
        if ("IN_PROGRESS".equals(st) || "ONGOING".equals(st) || "STARTED".equals(st)) return true;
        if ("COMPLETED".equals(st) || "DONE".equals(st) || "CANCELLED".equals(st)
                || "CANCELED".equals(st) || "REJECTED".equals(st)) return false;

        Long t = parseTimeMs(r.scheduledAt);
        if (t != null) return t >= (System.currentTimeMillis() - 5 * 60 * 1000L);
        return "BOOKED".equals(st) || "CONFIRMED".equals(st);
    }

    private Long parseTimeMs(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isBlank() || s.equalsIgnoreCase("null")) return null;
        String[] patterns = new String[]{
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        };
        for (String p : patterns) {
            try {
                java.text.SimpleDateFormat df = new java.text.SimpleDateFormat(p, Locale.US);
                java.util.Date d = df.parse(s);
                if (d != null) return d.getTime();
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private String buildUpcomingSummary(AppointmentSnapshot ap) {
        String type = ap.consultType == null ? "" : ap.consultType.trim().toUpperCase(Locale.US);
        String when = ap.scheduledAt;
        Long t = parseTimeMs(ap.scheduledAt);
        if (t != null) {
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("EEE, dd MMM - hh:mm a", Locale.US);
            when = df.format(new java.util.Date(t));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(translate("Your upcoming appointment")).append(": ");
        if (!TextUtils.isEmpty(ap.doctorName)) sb.append(ap.doctorName).append(". ");
        if (!TextUtils.isEmpty(when)) sb.append(when).append(". ");

        if ("PHYSICAL".equals(type) || "IN_PERSON".equals(type) || "INPERSON".equals(type) || "CLINIC".equals(type) || "VISIT".equals(type)) {
            sb.append(translate("Please reach the clinic 10Ã¢â‚¬â€œ15 minutes early."));
        } else if ("VIDEO".equals(type) || "AUDIO".equals(type)) {
            sb.append(translate("Please join 5 minutes before the start time."));
        }
        if (!TextUtils.isEmpty(ap.publicCode)) {
            sb.append(" ").append(translate("Booking code")).append(": ").append(ap.publicCode).append(".");
        }
        if ("VIDEO".equals(type) || "AUDIO".equals(type)) {
            String linkStatus = TextUtils.isEmpty(ap.consultLink) ? translate("Consult link not available yet") : translate("Consult link available");
            sb.append(" ").append(linkStatus).append(".");
        }
        return sb.toString();
    }

    private List<String> symptomChipList() {
        List<String> list = new ArrayList<>();
        list.add("Fever");
        list.add("Cough");
        list.add("Cold/Flu");
        list.add("Headache");
        list.add("Stomach pain");
        list.add("Skin issue");
        list.add("Body pain");
        list.add("Breathing issue");
        return list;
    }

    private List<String> yesNoChipList() {
        List<String> list = new ArrayList<>();
        list.add("Yes");
        list.add("No");
        return list;
    }

    private List<String> dateTimeChipList() {
        List<String> list = new ArrayList<>();
        list.add("Today");
        list.add("Tomorrow");
        list.add("Today 6 pm");
        list.add("Tomorrow 10:30 am");
        return list;
    }

    // -------------------------
    // Messages/store
    // -------------------------

    private void addMessage(PatientChatMessage message, RecyclerView rv) {
        chatAdapter.addMessage(message);
        List<PatientChatMessage> msgs = chatStore.load(patientId);
        msgs.add(message);
        chatStore.save(patientId, msgs);
        scrollToBottom(rv);

        try {
            if (message != null) {
                PatientChatMessage.Sender s = message.getSender();
                if ((s == PatientChatMessage.Sender.AI || s == PatientChatMessage.Sender.OFFLINE)
                        && autoSpeakNextAi) {
                    autoSpeakNextAi = false;
                    ttsLangOverrideTag = !TextUtils.isEmpty(activeLangOverrideTag) ? activeLangOverrideTag : sessionLangOverrideTag;
                    speakMessageText(message.getText());
                }
            }
        } catch (Throwable ignored) {}

        if (message != null) {
            PatientChatMessage.Sender s = message.getSender();
            if (s == PatientChatMessage.Sender.AI || s == PatientChatMessage.Sender.OFFLINE) {
                if (!TextUtils.isEmpty(message.getText())) {
                    lastAssistantTextForChips = message.getText();
                }
                if (message.getActions() != null && !message.getActions().isEmpty()) {
                    clearBottomChips();
                    return;
                }
                updateChipsForContext(rv);
            }
        }
    }

    private void clearBottomChips() {
        if (chipsContainer == null) return;
        chipsContainer.removeAllViews();
        chipViews.clear();
    }

    private void scrollToBottom(RecyclerView rv) {
        if (rv == null || rv.getAdapter() == null) return;
        rv.post(() -> {
            int n = rv.getAdapter().getItemCount();
            if (n > 0) rv.scrollToPosition(n - 1);
        });
    }

    // -------------------------
    // Firebase patient context/labs
    // -------------------------

    private void fetchLatestLabsIfMissing() {
        if (!isAdded()) return;
        if (!hasFirebase()) return;
        if (lastLabs != null && !lastLabs.isEmpty()) return;

        FirebaseDatabase.getInstance()
                .getReference("lab_latest")
                .child(patientId)
                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                        long bestTs = -1;
                        DataSnapshot best = null;

                        for (DataSnapshot cat : snapshot.getChildren()) {
                            Long ts = cat.child("timestampMs").getValue(Long.class);
                            long t = ts != null ? ts : 0L;
                            if (t > bestTs) {
                                bestTs = t;
                                best = cat;
                            }
                        }

                        HashMap<String, String> labs = new HashMap<>();
                        if (best != null) {
                            DataSnapshot results = best.child("results");
                            for (DataSnapshot r : results.getChildren()) {
                                Object v = r.getValue();
                                if (r.getKey() != null && v != null) labs.put(r.getKey(), v.toString());
                            }
                            if (TextUtils.isEmpty(category)) {
                                category = best.getKey() != null ? best.getKey() : "";
                            }
                        }

                        if (lastLabs == null) lastLabs = new HashMap<>();
                        lastLabs.clear();
                        lastLabs.putAll(labs);
                    }

                    @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) { }
                });
    }

    private void fetchPatientContext() {
        if (TextUtils.isEmpty(patientId) || TextUtils.isEmpty(doctorId)) return;
        if (!hasFirebase()) return;

        FirebaseDatabase.getInstance()
                .getReference("patients")
                .child(doctorId)
                .child(patientId)
                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                        String name = valueAsString(snapshot.child("name"));
                        String age = valueAsString(snapshot.child("age"));
                        String gender = valueAsString(snapshot.child("gender"));
                        String status = valueAsString(snapshot.child("status"));

                        StringBuilder sb = new StringBuilder();
                        if (name != null) sb.append("Patient ").append(name).append(". ");
                        if (age != null) sb.append("Age ").append(age).append(". ");
                        if (gender != null) sb.append("Gender ").append(gender).append(". ");
                        if (status != null) sb.append("Status ").append(status).append(". ");
                        patientSummary = sb.toString().trim();
                    }

                    @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) { }
                });
    }

    private String valueAsString(DataSnapshot snap) {
        if (snap == null || !snap.exists()) return null;
        Object v = snap.getValue();
        return v != null ? v.toString() : null;
    }

    private boolean hasFirebase() {
        try {
            return !FirebaseApp.getApps(requireContext()).isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // -------------------------
    // Prescriptions context (from existing list API)
    // -------------------------

    private void fetchPrescriptionsSummary() {
        if (prescriptionsLoading.getAndSet(true)) return;
        new Thread(() -> {
            String summary = loadPrescriptionsSummaryBlocking();
            prescriptionsLoading.set(false);
            if (!TextUtils.isEmpty(summary)) prescriptionsSummary = summary;
        }).start();
    }

    private void ensurePrescriptionsSummarySync() {
        if (!TextUtils.isEmpty(prescriptionsSummary)) return;
        if (prescriptionsLoading.getAndSet(true)) {
            // Wait briefly for ongoing fetch (max ~1.5s)
            long end = SystemClock.uptimeMillis() + 1500L;
            while (prescriptionsLoading.get() && SystemClock.uptimeMillis() < end) {
                try { Thread.sleep(120); } catch (InterruptedException ignored) {}
            }
            return;
        }
        try {
            String summary = loadPrescriptionsSummaryBlocking();
            if (!TextUtils.isEmpty(summary)) prescriptionsSummary = summary;
        } finally {
            prescriptionsLoading.set(false);
        }
    }

    private String loadPrescriptionsSummaryBlocking() {
        final String token = AppPrefs.INSTANCE.getAuthToken(requireContext());
        if (TextUtils.isEmpty(token)) return "";

        String summary = "";
        try {
            String urlStr = ApiConfig.BASE_URL
                    + "patient/prescriptions_list.php?limit=8&offset=0&_ts="
                    + System.currentTimeMillis();
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);

            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String raw = stream != null ? readAll(stream) : "";
            JSONObject json = new JSONObject(raw);

            JSONArray arr = null;
            if (json.optBoolean("ok", false)) {
                JSONObject data = json.optJSONObject("data");
                arr = data != null ? data.optJSONArray("items") : null;
                if (arr == null && data != null) arr = data.optJSONArray("prescriptions");
                if (arr == null) arr = json.optJSONArray("items");
                if (arr == null) arr = json.optJSONArray("prescriptions");
            }

            long latestId = extractLatestPrescriptionId(arr);
            if (latestId > 0L) {
                String detail = fetchPrescriptionDetailSummary(token, latestId);
                if (!TextUtils.isEmpty(detail)) {
                    summary = detail;
                }
            }

            if (TextUtils.isEmpty(summary) && arr != null && arr.length() > 0) {
                summary = buildRxSummary(arr);
            }

            conn.disconnect();
        } catch (Throwable ignored) {
        }

        if (TextUtils.isEmpty(summary)) {
            String cached = com.simats.criticall.LocalCache.INSTANCE.getString(requireContext(), "assistant_rx_summary");
            if (!TextUtils.isEmpty(cached)) summary = cached;
        }

        return summary;
    }

    private long extractLatestPrescriptionId(@Nullable JSONArray arr) {
        if (arr == null || arr.length() == 0) return 0L;
        JSONObject o = arr.optJSONObject(0);
        if (o == null) return 0L;
        long id = o.optLong("prescription_id", 0L);
        if (id > 0L) return id;
        id = o.optLong("id", 0L);
        if (id > 0L) return id;
        id = o.optLong("prescriptionId", 0L);
        if (id > 0L) return id;
        try {
            return Long.parseLong(o.optString("prescription_id", "0"));
        } catch (Throwable ignored) {}
        return 0L;
    }

    private String extractMedicinesFromSummary(String summary) {
        if (TextUtils.isEmpty(summary)) return "";
        String[] lines = summary.split("\\r?\\n");
        List<String> meds = new ArrayList<>();
        boolean inMeds = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("Medicines:")) {
                inMeds = true;
                continue;
            }
            if (line.startsWith("Diagnosis=") || line.startsWith("DoctorNotes=") || line.startsWith("FollowUp=")) {
                inMeds = false;
            }
            if (inMeds && line.startsWith("-")) {
                String v = line.replace("-","").trim();
                if (!v.isEmpty()) meds.add(v);
            }
        }
        if (meds.isEmpty()) return "";
        return TextUtils.join(", ", meds);
    }

    private String fetchPrescriptionDetailSummary(String token, long prescriptionId) {
        try {
            String urlStr = ApiConfig.BASE_URL
                    + "patient/prescription_detail.php?prescription_id=" + prescriptionId
                    + "&_ts=" + System.currentTimeMillis();
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);

            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String raw = stream != null ? readAll(stream) : "";
            JSONObject json = new JSONObject(raw);
            if (!json.optBoolean("ok", false)) {
                conn.disconnect();
                return "";
            }

            JSONObject data = json.optJSONObject("data") != null ? json.optJSONObject("data") : json;
            JSONObject p = data.optJSONObject("prescription") != null ? data.optJSONObject("prescription") : new JSONObject();
            JSONArray items = data.optJSONArray("items") != null ? data.optJSONArray("items") : new JSONArray();

            StringBuilder sb = new StringBuilder();
            String date = p.optString("created_at", p.optString("issued_at", "")).trim();
            String diagnosis = p.optString("diagnosis", "").trim();
            String notes = p.optString("doctor_notes", "").trim();
            String follow = p.optString("followup_note", "").trim();

            sb.append("LatestPrescriptionId=").append(prescriptionId).append("\n");
            if (!TextUtils.isEmpty(date)) sb.append("Date=").append(date).append("\n");
            if (!TextUtils.isEmpty(diagnosis)) sb.append("Diagnosis=").append(diagnosis).append("\n");
            if (!TextUtils.isEmpty(notes)) sb.append("DoctorNotes=").append(notes).append("\n");
            if (!TextUtils.isEmpty(follow)) sb.append("FollowUp=").append(follow).append("\n");

            if (items.length() > 0) {
                sb.append("Medicines:\n");
                int n = Math.min(items.length(), 8);
                for (int i = 0; i < n; i++) {
                    JSONObject m = items.optJSONObject(i);
                    if (m == null) continue;
                    String name = m.optString("name", "").trim();
                    String dosage = m.optString("dosage", "").trim();
                    String freq = m.optString("frequency", "").trim();
                    String dur = m.optString("duration", m.optString("days", "")).trim();
                    if (TextUtils.isEmpty(name)) continue;
                    sb.append("- ").append(name);
                    if (!TextUtils.isEmpty(dosage)) sb.append(" ").append(dosage);
                    if (!TextUtils.isEmpty(freq)) sb.append(" ").append(freq);
                    if (!TextUtils.isEmpty(dur)) sb.append(" ").append(dur);
                    sb.append("\n");
                }
            }

            conn.disconnect();
            return sb.toString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String buildRxSummary(JSONArray arr) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(arr.length(), 6);
        for (int i = 0; i < n; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;

            String title = o.optString("title", "").trim();
            String doctor = o.optString("doctor_name", o.optString("doctor_full_name", "")).trim();
            String spec = o.optString("specialization", o.optString("doctor_specialization", "")).trim();
            String date = o.optString("date", o.optString("created_at", o.optString("issued_at", ""))).trim();
            int medsCount = 0;
            if (o.has("medicines_count")) medsCount = o.optInt("medicines_count", 0);
            else if (o.has("meds_count")) medsCount = o.optInt("meds_count", 0);
            else if (o.has("items_count")) medsCount = o.optInt("items_count", 0);

            sb.append("- ");
            if (!TextUtils.isEmpty(title)) sb.append(title);
            if (!TextUtils.isEmpty(doctor)) sb.append(" (Dr. ").append(doctor).append(")");
            if (!TextUtils.isEmpty(spec)) sb.append(" ").append(spec);
            if (!TextUtils.isEmpty(date)) sb.append(" ").append(date);
            if (medsCount > 0) sb.append(" meds=").append(medsCount);
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String readAll(InputStream stream) {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(stream));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // -------------------------
    // Booking agent helpers
    // -------------------------

    private static class SpecialitySuggestion {
        final String key;
        final String reason;
        final String cleanedText;

        SpecialitySuggestion(String key, String reason, String cleanedText) {
            this.key = key;
            this.reason = reason;
            this.cleanedText = cleanedText;
        }
    }

    private SpecialitySuggestion parseSpecialitySuggestion(String raw) {
        if (TextUtils.isEmpty(raw)) return new SpecialitySuggestion("", "", "");
        String key = "";
        String reason = "";
        StringBuilder clean = new StringBuilder();
        String[] lines = raw.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("SPECIALITY_KEY=")) {
                key = trimmed.replace("SPECIALITY_KEY=", "").trim();
                continue;
            }
            if (trimmed.startsWith("SPECIALITY_REASON=")) {
                reason = trimmed.replace("SPECIALITY_REASON=", "").trim();
                continue;
            }
            clean.append(line).append("\n");
        }
        String cleanedText = clean.toString().trim();
        return new SpecialitySuggestion(key, reason, cleanedText);
    }

    private void fetchTopDoctorAndPrompt(RecyclerView rv) {
        new Thread(() -> {
            String name = "";
            int fee = 0;
            long id = 0L;
            try {
                JSONArray arr = PatientApi.INSTANCE.getDoctors(requireContext(), pendingSpecialityKey);
                if (arr != null && arr.length() > 0) {
                    JSONObject best = arr.optJSONObject(0);
                    double bestRating = -1;
                    int bestExp = -1;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        double rating = o.optDouble("rating", 0.0);
                        int exp = o.optInt("experienceYears", o.optInt("experience", 0));
                        if (rating > bestRating || (rating == bestRating && exp > bestExp)) {
                            bestRating = rating;
                            bestExp = exp;
                            best = o;
                        }
                    }
                    if (best != null) {
                        name = best.optString("name", best.optString("full_name", "")).trim();
                        fee = best.optInt("fee", 0);
                        id = parseDoctorIdAny(best);
                    }
                }
            } catch (Throwable ignored) {
            }

            final String fName = name;
            final int fFee = fee;
            final long fId = id;
            handler.post(() -> {
                pendingDoctorName = fName;
                pendingDoctorFee = fFee;
                pendingDoctorId = fId;

                StringBuilder sb = new StringBuilder();
                if (!TextUtils.isEmpty(pendingSpecialityLabel)) {
                    sb.append(whyFitPrefix()).append(" ").append(pendingSpecialityLabel);
                    if (!TextUtils.isEmpty(pendingSpecialityReason)) {
                    sb.append(" - ").append(pendingSpecialityReason);
                    }
                }
                if (!TextUtils.isEmpty(fName)) {
                    sb.append("\n").append(translate("Recommended doctor: ")).append(fName);
                }
                if (fFee > 0) {
                    sb.append("\n").append(translate("Consultation fee: ")).append("\u20B9").append(fFee);
                }
                addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, sb.toString(), System.currentTimeMillis(), null), rv);

                if (awaitingMedicationInfo) {
                    return;
                }
                if (pendingBookIntent || awaitingBookingConfirm || awaitingDateTime) {
                    maybeAutoStartBooking(rv);
                } else {
                    addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, askBookText(), System.currentTimeMillis(), null), rv);
                }
            });
        }).start();
    }

    private void showMedicationInfo(RecyclerView rv) {
        showThinkingUi();
        new Thread(() -> {
            ensurePrescriptionsSummarySync();
            String summary = prescriptionsSummary;
            String meds = extractMedicinesFromSummary(summary);
            handler.post(() -> {
                hideBusyUi();
                if (TextUtils.isEmpty(summary) || TextUtils.isEmpty(meds)) {
                    addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI,
                            translate("I couldn't find your medication details yet. You can open prescriptions to view them."),
                            System.currentTimeMillis(), null), rv);
                } else {
                    addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI,
                            translate("Here are your current medicines: ") + meds,
                            System.currentTimeMillis(), null), rv);
                }
            });
        }).start();
    }

    private void maybeAutoStartBooking(RecyclerView rv) {
        pendingBookIntent = false;

        if (!TextUtils.isEmpty(pendingIntentDateIso)) {
            pendingDateIso = pendingIntentDateIso;
        }
        if (!TextUtils.isEmpty(pendingIntentTime24)) {
            pendingTime24 = pendingIntentTime24;
        }

        pendingConsultType = CONSULT_TYPE_PHYSICAL;

        if (TextUtils.isEmpty(pendingDateIso) || TextUtils.isEmpty(pendingTime24)) {
            awaitingBookingConfirm = false;
            awaitingDateTime = true;
            showAvailabilityThenAskDateTime(rv);
            return;
        }

        awaitingBookingConfirm = false;
        awaitingDateTime = false;
        startBookingWithValidation(rv);
    }

    private void handleBookingFlow(String msg, RecyclerView rv) {
        addMessage(new PatientChatMessage(PatientChatMessage.Sender.PATIENT, msg, System.currentTimeMillis(), null), rv);

        if (isOtherDoctorsQuery(msg)) {
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, translate("Showing other available doctors."), System.currentTimeMillis(), null), rv);
            showOtherDoctors(rv);
            return;
        }

        if (awaitingSymptoms) {
            awaitingSymptoms = false;
            pendingSymptomsText = msg;
            pendingBookIntent = true;
            showThinkingUi();
            runGemini(msg, rv);
            return;
        }

        if (awaitingSuggestedTimeConfirm) {
            if (isYes(msg)) {
                awaitingSuggestedTimeConfirm = false;
                if (!TextUtils.isEmpty(suggestedDateIso)) pendingDateIso = suggestedDateIso;
                if (!TextUtils.isEmpty(suggestedTime24)) pendingTime24 = suggestedTime24;
                startBookingWithValidation(rv);
                return;
            }
            if (isNo(msg)) {
                awaitingSuggestedTimeConfirm = false;
                showAvailabilityThenAskDateTime(rv);
                return;
            }
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, askConfirmSuggestedText(), System.currentTimeMillis(), null), rv);
            return;
        }

        if (awaitingBookingConfirm) {
            if (isYes(msg)) {
                awaitingBookingConfirm = false;
                pendingConsultType = CONSULT_TYPE_PHYSICAL;

                DateTimeSelection dt = parseDateTimeFromText(msg);
                if (dt.hasDate && dt.hasTime) {
                    pendingDateIso = dt.dateIso;
                    pendingTime24 = dt.time24;
                    startBookingWithValidation(rv);
                    return;
                }
                if (dt.hasDate && !dt.hasTime) {
                    pendingDateIso = dt.dateIso;
                    suggestFirstAvailableTimeForDate(rv);
                    return;
                }
                if (!dt.hasDate && dt.hasTime) {
                    pendingTime24 = dt.time24;
                    suggestFirstAvailableDateForTime(rv);
                    return;
                }

                awaitingDateTime = true;
                showAvailabilityThenAskDateTime(rv);
                return;
            }

            if (isNo(msg)) {
                awaitingBookingConfirm = false;
                addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, translate("Okay. If you want, I can suggest another specialist."), System.currentTimeMillis(), null), rv);
                return;
            }

            addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, askBookText(), System.currentTimeMillis(), null), rv);
            return;
        }

        if (awaitingDateTime) {
            DateTimeSelection dt = parseDateTimeFromText(msg);
            if (!dt.hasDate && TextUtils.isEmpty(pendingDateIso)) {
                if (dt.hasTime) pendingTime24 = dt.time24;
                suggestFirstAvailableDateForTime(rv);
                return;
            }
            if (!dt.hasTime && TextUtils.isEmpty(pendingTime24)) {
                if (dt.hasDate) pendingDateIso = dt.dateIso;
                suggestFirstAvailableTimeForDate(rv);
                return;
            }

            if (dt.hasDate) pendingDateIso = dt.dateIso;
            if (dt.hasTime) pendingTime24 = dt.time24;

            if (TextUtils.isEmpty(pendingDateIso) || TextUtils.isEmpty(pendingTime24)) {
                showAvailabilityThenAskDateTime(rv);
                return;
            }

            awaitingDateTime = false;
            startBookingWithValidation(rv);
        }
    }

    private void suggestFirstAvailableTimeForDate(RecyclerView rv) {
        if (pendingDoctorId <= 0L || TextUtils.isEmpty(pendingDateIso)) {
            awaitingDateTime = true;
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, askTimeOnlyText(), System.currentTimeMillis(), null), rv);
            return;
        }
        showThinkingUi();
        new Thread(() -> {
            String time = "";
            try {
                JSONArray days = PatientApi.INSTANCE.getSlots(requireContext(), (int) Math.min(Integer.MAX_VALUE, pendingDoctorId), 7);
                time = findFirstTimeForDate(days, pendingDateIso);
            } catch (Throwable ignored) {}

            final String fTime = time;
            handler.post(() -> {
                hideBusyUi();
                if (!TextUtils.isEmpty(fTime)) {
                    suggestedDateIso = pendingDateIso;
                    suggestedTime24 = fTime;
                    awaitingSuggestedTimeConfirm = true;
                    addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, askConfirmSuggestedText(), System.currentTimeMillis(), null), rv);
                } else {
                    awaitingDateTime = true;
                    addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, askTimeOnlyText(), System.currentTimeMillis(), null), rv);
                }
            });
        }).start();
    }

    private void suggestFirstAvailableDateForTime(RecyclerView rv) {
        if (pendingDoctorId <= 0L || TextUtils.isEmpty(pendingTime24)) {
            awaitingDateTime = true;
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, askDateOnlyText(), System.currentTimeMillis(), null), rv);
            return;
        }
        showThinkingUi();
        new Thread(() -> {
            String date = "";
            try {
                JSONArray days = PatientApi.INSTANCE.getSlots(requireContext(), (int) Math.min(Integer.MAX_VALUE, pendingDoctorId), 7);
                date = findFirstDateForTime(days, pendingTime24);
            } catch (Throwable ignored) {}

            final String fDate = date;
            handler.post(() -> {
                hideBusyUi();
                if (!TextUtils.isEmpty(fDate)) {
                    suggestedDateIso = fDate;
                    suggestedTime24 = pendingTime24;
                    awaitingSuggestedTimeConfirm = true;
                    addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, askConfirmSuggestedText(), System.currentTimeMillis(), null), rv);
                } else {
                    awaitingDateTime = true;
                    addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, askDateOnlyText(), System.currentTimeMillis(), null), rv);
                }
            });
        }).start();
    }

    private String findFirstTimeForDate(JSONArray days, String dateIso) {
        if (days == null || TextUtils.isEmpty(dateIso)) return "";
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            if (day == null) continue;
            if (!dateIso.equals(day.optString("date", ""))) continue;
            if (!day.optBoolean("enabled", true)) return "";
            JSONArray sections = day.optJSONArray("sections");
            if (sections == null) return "";
            for (int s = 0; s < sections.length(); s++) {
                JSONObject sec = sections.optJSONObject(s);
                if (sec == null) continue;
                JSONArray slots = sec.optJSONArray("slots");
                if (slots == null) continue;
                for (int k = 0; k < slots.length(); k++) {
                    JSONObject slot = slots.optJSONObject(k);
                    if (slot == null || slot.optBoolean("disabled", false)) continue;
                    String value = slot.optString("value", "");
                    String label = slot.optString("label", "");
                    String norm = normalizeSlotToTime24(value, label);
                    if (!TextUtils.isEmpty(norm)) return norm;
                }
            }
        }
        return "";
    }

    private String findFirstDateForTime(JSONArray days, String time24) {
        if (days == null || TextUtils.isEmpty(time24)) return "";
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            if (day == null) continue;
            if (!day.optBoolean("enabled", true)) continue;
            String dateIso = day.optString("date", "");
            JSONArray sections = day.optJSONArray("sections");
            if (sections == null) continue;
            for (int s = 0; s < sections.length(); s++) {
                JSONObject sec = sections.optJSONObject(s);
                if (sec == null) continue;
                JSONArray slots = sec.optJSONArray("slots");
                if (slots == null) continue;
                for (int k = 0; k < slots.length(); k++) {
                    JSONObject slot = slots.optJSONObject(k);
                    if (slot == null || slot.optBoolean("disabled", false)) continue;
                    String value = slot.optString("value", "");
                    String label = slot.optString("label", "");
                    String norm = normalizeSlotToTime24(value, label);
                    if (time24.equals(norm)) return dateIso;
                }
            }
        }
        return "";
    }

    private void startBookingWithValidation(RecyclerView rv) {
        if (pendingDoctorId <= 0L) {
            openBookingFlow(pendingSpecialityKey, pendingSpecialityLabel, pendingSymptomsText);
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, bookingOpeningText(), System.currentTimeMillis(), null), rv);
            return;
        }

        showThinkingUi();
        new Thread(() -> {
            boolean ok = false;
            String suggestion = "";
            String[] closest = null;
            try {
                JSONArray days = PatientApi.INSTANCE.getSlots(requireContext(), (int) Math.min(Integer.MAX_VALUE, pendingDoctorId), 7);
                ok = isSlotAvailable(days, pendingDateIso, pendingTime24);
                if (!ok) {
                    closest = findClosestSlot(days, pendingDateIso, pendingTime24);
                    if (closest != null && closest.length == 2) {
                        suggestion = ""; // use confirm prompt instead of list
                    } else {
                        suggestion = buildTimeSuggestions(days, pendingDateIso, 3);
                    }
                }
            } catch (Throwable ignored) {
            }

            final boolean available = ok;
            final String suggestText = suggestion;
            final String[] closestSlot = closest;
            handler.post(() -> {
                hideBusyUi();
                if (available) {
                    openTimeSlotFlow(pendingSpecialityKey, pendingSpecialityLabel, pendingSymptomsText);
                    addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, bookingOpeningText(), System.currentTimeMillis(), null), rv);
                } else {
                    if (closestSlot != null && closestSlot.length == 2 && !TextUtils.isEmpty(closestSlot[0]) && !TextUtils.isEmpty(closestSlot[1])) {
                        suggestedDateIso = closestSlot[0];
                        suggestedTime24 = closestSlot[1];
                        awaitingSuggestedTimeConfirm = true;
                        addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, timeNotAvailableText() + " " + askConfirmSuggestedText(), System.currentTimeMillis(), null), rv);
                    } else {
                        pendingTime24 = "";
                        awaitingDateTime = true;
                        if (!TextUtils.isEmpty(suggestText)) {
                            addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, timeNotAvailableText() + " " + suggestText, System.currentTimeMillis(), null), rv);
                        } else {
                            addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, timeNotAvailableText(), System.currentTimeMillis(), null), rv);
                        }
                    }
                }
            });
        }).start();
    }

    private String[] findClosestSlot(JSONArray days, String dateIso, String time24) {
        if (days == null || TextUtils.isEmpty(time24)) return null;
        long targetMs = -1L;
        try {
            java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            df.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
            if (!TextUtils.isEmpty(dateIso)) {
                targetMs = df.parse(dateIso + " " + time24).getTime();
            }
        } catch (Throwable ignored) {}

        long bestDiff = Long.MAX_VALUE;
        String bestDate = "";
        String bestTime = "";

        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            if (day == null) continue;
            if (!day.optBoolean("enabled", true)) continue;
            String dIso = day.optString("date", "");
            JSONArray sections = day.optJSONArray("sections");
            if (sections == null) continue;
            for (int s = 0; s < sections.length(); s++) {
                JSONObject sec = sections.optJSONObject(s);
                if (sec == null) continue;
                JSONArray slots = sec.optJSONArray("slots");
                if (slots == null) continue;
                for (int k = 0; k < slots.length(); k++) {
                    JSONObject slot = slots.optJSONObject(k);
                    if (slot == null || slot.optBoolean("disabled", false)) continue;
                    String value = slot.optString("value", "");
                    String label = slot.optString("label", "");
                    String norm = normalizeSlotToTime24(value, label);
                    if (TextUtils.isEmpty(norm) || TextUtils.isEmpty(dIso)) continue;

                    long slotMs = -1L;
                    try {
                        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
                        df.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
                        slotMs = df.parse(dIso + " " + norm).getTime();
                    } catch (Throwable ignored) {}

                    long diff = (targetMs > 0 && slotMs > 0) ? Math.abs(slotMs - targetMs) : 0L;
                    if (targetMs <= 0) diff = 0L;
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        bestDate = dIso;
                        bestTime = norm;
                    }
                }
            }
        }

        if (TextUtils.isEmpty(bestDate) || TextUtils.isEmpty(bestTime)) return null;
        return new String[]{bestDate, bestTime};
    }

    private String buildTimeSuggestions(JSONArray days, String dateIso, int max) {
        if (days == null || TextUtils.isEmpty(dateIso)) return "";
        List<String> out = new ArrayList<>();
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            if (day == null) continue;
            if (!dateIso.equals(day.optString("date", ""))) continue;
            if (!day.optBoolean("enabled", true)) continue;
            JSONArray sections = day.optJSONArray("sections");
            if (sections == null) break;
            for (int s = 0; s < sections.length(); s++) {
                JSONObject sec = sections.optJSONObject(s);
                if (sec == null) continue;
                JSONArray slots = sec.optJSONArray("slots");
                if (slots == null) continue;
                for (int k = 0; k < slots.length(); k++) {
                    JSONObject slot = slots.optJSONObject(k);
                    if (slot == null) continue;
                    if (slot.optBoolean("disabled", false)) continue;
                    String label = slot.optString("label", "");
                    String value = slot.optString("value", "");
                    String display = !TextUtils.isEmpty(label) ? label : value;
                    if (!TextUtils.isEmpty(display)) {
                        out.add(display);
                        if (out.size() >= max) {
                            return suggestPrefix(out);
                        }
                    }
                }
            }
        }
        return out.isEmpty() ? "" : suggestPrefix(out);
    }

    private String suggestPrefix(List<String> times) {
        String lang = TranslationManager.currentLang(requireContext());
        String joined = TextUtils.join(", ", times);
        if ("hi".equals(lang)) return "Ã Â¤â€°Ã Â¤ÂªÃ Â¤Â²Ã Â¤Â¬Ã Â¥ÂÃ Â¤Â§ Ã Â¤Â¸Ã Â¤Â®Ã Â¤Â¯: " + joined;
        if ("ta".equals(lang)) return "Ã Â®â€¢Ã Â®Â¿Ã Â®Å¸Ã Â¯Ë†Ã Â®â€¢Ã Â¯ÂÃ Â®â€¢Ã Â¯ÂÃ Â®Â®Ã Â¯Â Ã Â®Â¨Ã Â¯â€¡Ã Â®Â°Ã Â®Â®Ã Â¯Â: " + joined;
        if ("te".equals(lang)) return "Ã Â°Â²Ã Â°Â­Ã Â±ÂÃ Â°Â¯Ã Â°Â®Ã Â±Ë†Ã Â°Â¨ Ã Â°Â¸Ã Â°Â®Ã Â°Â¯Ã Â°â€š: " + joined;
        if ("kn".equals(lang)) return "Ã Â²Â²Ã Â²Â­Ã Â³ÂÃ Â²Â¯ Ã Â²Â¸Ã Â²Â®Ã Â²Â¯Ã Â²â€”Ã Â²Â³Ã Â³Â: " + joined;
        if ("ml".equals(lang)) return "Ã Â´Â²Ã Â´Â­Ã ÂµÂÃ Â´Â¯Ã Â´Â®Ã Â´Â¾Ã Â´Â¯ Ã Â´Â¸Ã Â´Â®Ã Â´Â¯Ã Â´â€š: " + joined;
        return "Available times: " + joined;
    }

    private boolean isSlotAvailable(JSONArray days, String dateIso, String time24) {
        if (days == null || TextUtils.isEmpty(dateIso) || TextUtils.isEmpty(time24)) return false;
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            if (day == null) continue;
            if (!dateIso.equals(day.optString("date", ""))) continue;
            if (!day.optBoolean("enabled", true)) return false;

            JSONArray sections = day.optJSONArray("sections");
            if (sections == null) return false;
            for (int s = 0; s < sections.length(); s++) {
                JSONObject sec = sections.optJSONObject(s);
                if (sec == null) continue;
                JSONArray slots = sec.optJSONArray("slots");
                if (slots == null) continue;
                for (int k = 0; k < slots.length(); k++) {
                    JSONObject slot = slots.optJSONObject(k);
                    if (slot == null) continue;
                    if (slot.optBoolean("disabled", false)) continue;
                    String value = slot.optString("value", "");
                    String label = slot.optString("label", "");
                    String normalized = normalizeSlotToTime24(value, label);
                    if (time24.equals(normalized)) return true;
                }
            }
        }
        return false;
    }

    private String normalizeSlotToTime24(String value, String label) {
        String v = value == null ? "" : value.trim();
        if (v.matches("\\d{1,2}:\\d{2}")) {
            return String.format(Locale.US, "%02d:%02d",
                    safeParseInt(v.substring(0, v.indexOf(':'))),
                    safeParseInt(v.substring(v.indexOf(':') + 1)));
        }
        String l = label == null ? "" : label.trim().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(\\d{1,2})(?::(\\d{2}))?\\s*([ap]m)\\b").matcher(l);
        if (m.find()) {
            int hh = safeParseInt(m.group(1));
            int mm = safeParseInt(m.group(2) != null ? m.group(2) : "00");
            String ap = m.group(3);
            if ("pm".equals(ap) && hh < 12) hh += 12;
            if ("am".equals(ap) && hh == 12) hh = 0;
            if (hh >= 0 && hh <= 23 && mm >= 0 && mm <= 59) {
                return String.format(Locale.US, "%02d:%02d", hh, mm);
            }
        }
        return "";
    }

    private void openBookingFlow(String key, String label, String symptoms) {
        openBookingFlow(key, label, symptoms, true);
    }

    private void openBookingFlow(String key, String label, String symptoms, boolean autoOpenFirst) {
        if (!isAdded()) return;
        if (TextUtils.isEmpty(key) || TextUtils.isEmpty(label)) return;

        Intent itn = new Intent(requireContext(), SelectSpecialityActivity.class);
        itn.putExtra(SelectSpecialityActivity.EXTRA_SPECIALITY_KEY, key);
        itn.putExtra(SelectSpecialityActivity.EXTRA_SPECIALITY_LABEL, label);
        itn.putExtra(PatientDoctorListActivity.EXTRA_SYMPTOMS_TEXT, symptoms);
        itn.putExtra(SelectSpecialityActivity.EXTRA_AUTO_SELECT_KEY, key);
        itn.putExtra(SelectSpecialityActivity.EXTRA_AUTO_CONTINUE, true);
        itn.putExtra(PatientDoctorListActivity.EXTRA_AUTO_OPEN_FIRST, autoOpenFirst);
        if (autoOpenFirst) {
            if (pendingDoctorId > 0L) itn.putExtra(PatientDoctorListActivity.EXTRA_AUTO_DOCTOR_ID, pendingDoctorId);
            if (!TextUtils.isEmpty(pendingConsultType)) itn.putExtra(PatientDoctorListActivity.EXTRA_AUTO_CONSULT_TYPE, pendingConsultType);
            if (!TextUtils.isEmpty(pendingDateIso)) itn.putExtra(PatientDoctorListActivity.EXTRA_PREF_DATE, pendingDateIso);
            if (!TextUtils.isEmpty(pendingTime24)) itn.putExtra(PatientDoctorListActivity.EXTRA_PREF_TIME, pendingTime24);
            itn.putExtra(PatientDoctorListActivity.EXTRA_AUTO_CONFIRM, true);
        }
        startActivity(itn);
        if (getActivity() != null) getActivity().overridePendingTransition(R.anim.ai_enter, R.anim.ai_exit);
        closeAssistantAfterNav();
    }

    private void openTimeSlotFlow(String key, String label, String symptoms) {
        if (!isAdded()) return;
        if (TextUtils.isEmpty(key) || TextUtils.isEmpty(label)) return;
        awaitingBookingConfirm = false;
        awaitingDateTime = false;

        // Agent flow: go through each page like a human
        openBookingFlow(key, label, symptoms);
    }

    private long parseDoctorIdAny(JSONObject o) {
        String[] keys = new String[]{"doctor_id", "doctorId", "user_id", "userId", "id"};
        for (String k : keys) {
            Object v = o.opt(k);
            if (v instanceof Number) {
                long n = ((Number) v).longValue();
                if (n > 0) return n;
            } else if (v instanceof String) {
                try {
                    long n = Long.parseLong(((String) v).trim());
                    if (n > 0) return n;
                } catch (Exception ignored) {}
            }
        }
        return 0L;
    }

    private boolean isYes(String msg) {
        String m = msg.toLowerCase(Locale.ROOT).trim();
        return m.contains("yes") || m.contains("okay") || m.contains("ok") || m.contains("book")
                || m.contains("sure") || m.contains("Ã Â¤Â¹Ã Â¤Â¾Ã Â¤Â") || m.contains("Ã Â¤Â¹Ã Â¤Â¾") || m.contains("Ã Â²Â¹Ã Â³Å’Ã Â²Â¦Ã Â³Â")
                || m.contains("Ã Â®â€ Ã Â®Â®Ã Â¯Â") || m.contains("Ã Â°â€¦Ã Â°ÂµÃ Â±ÂÃ Â°Â¨Ã Â±Â") || m.contains("Ã Â´â€¦Ã Â´Â¤Ã Âµâ€ ");
    }

    private boolean isNo(String msg) {
        String m = msg.toLowerCase(Locale.ROOT).trim();
        return m.contains("no") || m.contains("not now") || m.contains("don't") || m.contains("dont")
                || m.contains("Ã Â¤Â¨Ã Â¤Â¾") || m.contains("Ã Â²â€¡Ã Â²Â²Ã Â³ÂÃ Â²Â²") || m.contains("Ã Â®â€¡Ã Â®Â²Ã Â¯ÂÃ Â®Â²") || m.contains("Ã Â°â€¢Ã Â°Â¾Ã Â°Â¦Ã Â±Â")
                || m.contains("Ã Â´â€¡Ã Â´Â²Ã ÂµÂÃ Â´Â²");
    }

    private boolean isOtherDoctorsQuery(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase(Locale.ROOT).trim();
        return m.contains("other doctor") || m.contains("other doctors") || m.contains("another doctor")
                || m.contains("different doctor") || m.contains("any other doctor") || m.contains("other specialist")
                || m.contains("other specialists") || m.contains("more doctors") || m.contains("show doctors")
                || m.contains("find another doctor") || m.contains("change doctor")
                || m.contains("dusra doctor") || m.contains("doosra doctor") || m.contains("aur doctor")
                || m.contains("vera doctor") || m.contains("vere doctor") || m.contains("maro doctor")
                || m.contains("inko doctor") || m.contains("bere doctor");
    }

    private boolean isViewAppointmentQuery(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase(Locale.ROOT).trim();
        return m.contains("view my appointment") || m.contains("my appointment") || m.contains("show appointment")
                || m.contains("upcoming appointment") || m.contains("booking details")
                || m.contains("Ã Â¤Â®Ã Â¥â€¡Ã Â¤Â°Ã Â¥â‚¬ Ã Â¤â€¦Ã Â¤ÂªÃ Â¥â€°Ã Â¤â€¡Ã Â¤â€šÃ Â¤Å¸Ã Â¤Â®Ã Â¥â€¡Ã Â¤â€šÃ Â¤Å¸") || m.contains("Ã Â¤Â®Ã Â¥â€¡Ã Â¤Â°Ã Â¤Â¾ Ã Â¤â€¦Ã Â¤ÂªÃ Â¥â€°Ã Â¤â€¡Ã Â¤â€šÃ Â¤Å¸Ã Â¤Â®Ã Â¥â€¡Ã Â¤â€šÃ Â¤Å¸")
                || m.contains("Ã Â®Å½Ã Â®Â©Ã Â¯Â Ã Â®â€¦Ã Â®ÂªÃ Â¯ÂÃ Â®ÂªÃ Â®Â¾Ã Â®Â¯Ã Â®Â¿Ã Â®Â£Ã Â¯ÂÃ Â®Å¸Ã Â¯ÂÃ Â®Â®Ã Â¯â€ Ã Â®Â£Ã Â¯ÂÃ Â®Å¸Ã Â¯Â") || m.contains("Ã Â°Â¨Ã Â°Â¾ Ã Â°â€¦Ã Â°ÂªÃ Â°Â¾Ã Â°Â¯Ã Â°Â¿Ã Â°â€šÃ Â°Å¸Ã Â±ÂÃ¢â‚¬Å’Ã Â°Â®Ã Â±â€ Ã Â°â€šÃ Â°Å¸Ã Â±Â")
                || m.contains("Ã Â²Â¨Ã Â²Â¨Ã Â³ÂÃ Â²Â¨ Ã Â²â€¦Ã Â²ÂªÃ Â²Â¾Ã Â²Â¯Ã Â²Â¿Ã Â²â€šÃ Â²Å¸Ã Â³ÂÃ¢â‚¬Å’Ã Â²Â®Ã Â³â€ Ã Â²â€šÃ Â²Å¸Ã Â³Â") || m.contains("Ã Â´Å½Ã Â´Â¨Ã ÂµÂÃ Â´Â±Ã Âµâ€  Ã Â´â€¦Ã Â´ÂªÃ Âµâ€¹Ã Â´Â¯Ã Â´Â¿Ã Â´Â¨Ã ÂµÂÃ Â´Â±Ã ÂµÂÃ Â´Â®Ã Âµâ€ Ã Â´Â¨Ã ÂµÂÃ Â´Â±Ã ÂµÂ");
    }

    private boolean isCancelBookingQuery(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase(Locale.ROOT).trim();
        return m.contains("cancel booking") || m.contains("cancel appointment") || m.contains("stop booking")
                || m.contains("not now") || m.contains("don't book")
                || m.contains("ÃƒÂ Ã‚Â¤Ã‚Â¬ÃƒÂ Ã‚Â¥Ã‚ÂÃƒÂ Ã‚Â¤Ã¢â‚¬Â¢ÃƒÂ Ã‚Â¤Ã‚Â¿ÃƒÂ Ã‚Â¤Ã¢â‚¬Å¡ÃƒÂ Ã‚Â¤Ã¢â‚¬â€ ÃƒÂ Ã‚Â¤Ã‚Â°ÃƒÂ Ã‚Â¤Ã‚Â¦ÃƒÂ Ã‚Â¥Ã‚ÂÃƒÂ Ã‚Â¤Ã‚Â¦") || m.contains("ÃƒÂ Ã‚Â¤Ã¢â‚¬Â¦ÃƒÂ Ã‚Â¤Ã‚ÂªÃƒÂ Ã‚Â¥Ã¢â‚¬Â°ÃƒÂ Ã‚Â¤Ã¢â‚¬Â¡ÃƒÂ Ã‚Â¤Ã¢â‚¬Å¡ÃƒÂ Ã‚Â¤Ã…Â¸ÃƒÂ Ã‚Â¤Ã‚Â®ÃƒÂ Ã‚Â¥Ã¢â‚¬Â¡ÃƒÂ Ã‚Â¤Ã¢â‚¬Å¡ÃƒÂ Ã‚Â¤Ã…Â¸ ÃƒÂ Ã‚Â¤Ã‚Â°ÃƒÂ Ã‚Â¤Ã‚Â¦ÃƒÂ Ã‚Â¥Ã‚ÂÃƒÂ Ã‚Â¤Ã‚Â¦")
                || m.contains("ÃƒÂ Ã‚Â®Ã‚Â°ÃƒÂ Ã‚Â®Ã‚Â¤ÃƒÂ Ã‚Â¯Ã‚ÂÃƒÂ Ã‚Â®Ã‚Â¤ÃƒÂ Ã‚Â¯Ã‚Â") || m.contains("ÃƒÂ Ã‚Â®Ã‚Â¨ÃƒÂ Ã‚Â®Ã‚Â¿ÃƒÂ Ã‚Â®Ã‚Â±ÃƒÂ Ã‚Â¯Ã‚ÂÃƒÂ Ã‚Â®Ã‚Â¤ÃƒÂ Ã‚Â¯Ã‚ÂÃƒÂ Ã‚Â®Ã‚Â¤ÃƒÂ Ã‚Â¯Ã‚Â")
                || m.contains("ÃƒÂ Ã‚Â°Ã‚Â°ÃƒÂ Ã‚Â°Ã‚Â¦ÃƒÂ Ã‚Â±Ã‚ÂÃƒÂ Ã‚Â°Ã‚Â¦ÃƒÂ Ã‚Â±Ã‚Â") || m.contains("ÃƒÂ Ã‚Â²Ã‚Â¬ÃƒÂ Ã‚Â³Ã‚ÂÃƒÂ Ã‚Â²Ã¢â‚¬Â¢ÃƒÂ Ã‚Â³Ã‚ÂÃƒÂ Ã‚Â²Ã¢â‚¬Â¢ÃƒÂ Ã‚Â²Ã‚Â¿ÃƒÂ Ã‚Â²Ã¢â‚¬Å¡ÃƒÂ Ã‚Â²Ã¢â‚¬â€ÃƒÂ Ã‚Â³Ã‚Â ÃƒÂ Ã‚Â²Ã‚Â°ÃƒÂ Ã‚Â²Ã‚Â¦ÃƒÂ Ã‚Â±Ã‚ÂÃƒÂ Ã‚Â²Ã‚Â¦")
                || m.contains("ÃƒÂ Ã‚Â´Ã‚Â°ÃƒÂ Ã‚Â´Ã‚Â¦ÃƒÂ Ã‚ÂµÃ‚ÂÃƒÂ Ã‚Â´Ã‚Â¦ÃƒÂ Ã‚Â´Ã‚Â¾ÃƒÂ Ã‚Â´Ã¢â‚¬Â¢ÃƒÂ Ã‚ÂµÃ‚ÂÃƒÂ Ã‚Â´Ã¢â‚¬Â¢");
    }

    private boolean isPrescriptionsQuery(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase(Locale.ROOT).trim();
        return m.contains("prescription") || m.contains("prescriptions") || m.contains("my prescriptions")
                || m.contains("report") || m.contains("reports");
    }
    private boolean isViewMoreAvailabilityQuery(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase(java.util.Locale.ROOT).trim();
        String local = "";
        try { local = translate("View more availability").toLowerCase(java.util.Locale.ROOT); } catch (Throwable ignored) {}
        return m.contains("view more availability") || m.contains("more availability") || m.contains("show more slots")
                || m.contains("next days") || m.contains("next 2 days")
                || (!TextUtils.isEmpty(local) && m.contains(local));
    }

    private String askBookText() {
        String lang = TranslationManager.currentLang(requireContext());
        if ("hi".equals(lang)) return "Ã Â¤â€¢Ã Â¥ÂÃ Â¤Â¯Ã Â¤Â¾ Ã Â¤â€ Ã Â¤Âª Ã Â¤Å¡Ã Â¤Â¾Ã Â¤Â¹Ã Â¥â€¡Ã Â¤â€šÃ Â¤â€”Ã Â¥â€¡ Ã Â¤â€¢Ã Â¤Â¿ Ã Â¤Â®Ã Â¥Ë†Ã Â¤â€š Ã Â¤â€¦Ã Â¤ÂªÃ Â¥â€°Ã Â¤â€¡Ã Â¤â€šÃ Â¤Å¸Ã Â¤Â®Ã Â¥â€¡Ã Â¤â€šÃ Â¤Å¸ Ã Â¤Â¬Ã Â¥ÂÃ Â¤â€¢ Ã Â¤â€¢Ã Â¤Â° Ã Â¤Â¦Ã Â¥â€šÃ Â¤â€š?";
        if ("ta".equals(lang)) return "Ã Â®Â¨Ã Â®Â¾Ã Â®Â©Ã Â¯Â Ã Â®â€¦Ã Â®ÂªÃ Â¯ÂÃ Â®ÂªÃ Â®Â¾Ã Â®Â¯Ã Â®Â¿Ã Â®Â©Ã Â¯ÂÃ Â®Â®Ã Â¯â€ Ã Â®Â£Ã Â¯ÂÃ Â®Å¸Ã Â¯Â Ã Â®ÂªÃ Â¯ÂÃ Â®â€¢Ã Â¯Â Ã Â®Å¡Ã Â¯â€ Ã Â®Â¯Ã Â¯ÂÃ Â®Â¯Ã Â®Â²Ã Â®Â¾Ã Â®Â®Ã Â®Â¾?";
        if ("te".equals(lang)) return "Ã Â°Â¨Ã Â±â€¡Ã Â°Â¨Ã Â±Â Ã Â°â€¦Ã Â°ÂªÃ Â°Â¾Ã Â°Â¯Ã Â°Â¿Ã Â°â€šÃ Â°Å¸Ã Â±ÂÃ Â°Â®Ã Â±â€ Ã Â°â€šÃ Â°Å¸Ã Â±Â Ã Â°Â¬Ã Â±ÂÃ Â°â€¢Ã Â±Â Ã Â°Å¡Ã Â±â€¡Ã Â°Â¯Ã Â°Â¾Ã Â°Â²Ã Â°Â¾?";
        if ("kn".equals(lang)) return "Ã Â²Â¨Ã Â²Â¾Ã Â²Â¨Ã Â³Â Ã Â²â€¦Ã Â²ÂªÃ Â²Â¾Ã Â²Â¯Ã Â²Â¿Ã Â²â€šÃ Â²Å¸Ã Â³ÂÃ¢â‚¬Å’Ã Â²Â®Ã Â³â€ Ã Â²â€šÃ Â²Å¸Ã Â³Â Ã Â²Â¬Ã Â³ÂÃ Â²â€¢Ã Â³Â Ã Â²Â®Ã Â²Â¾Ã Â²Â¡Ã Â²Â¬Ã Â³â€¡Ã Â²â€¢Ã Â²Â¾?";
        if ("ml".equals(lang)) return "Ã Â´Å¾Ã Â´Â¾Ã ÂµÂ» Ã Â´â€¦Ã Â´ÂªÃ Âµâ€¹Ã Â´Â¯Ã Â´Â¿Ã Â´Â¨Ã ÂµÂÃ Â´Â±Ã ÂµÂÃ Â´Â®Ã Âµâ€ Ã Â´Â¨Ã ÂµÂÃ Â´Â±Ã ÂµÂ Ã Â´Â¬Ã ÂµÂÃ Â´â€¢Ã ÂµÂÃ Â´â€¢Ã ÂµÂ Ã Â´Å¡Ã Âµâ€ Ã Â´Â¯Ã ÂµÂÃ Â´Â¯Ã Â´Å¸Ã ÂµÂÃ Â´Å¸Ã Âµâ€¡?";
        return "Do you want me to book the appointment?";
    }

    private String askDateTimeText() {
        return "Please select a slot below.";
    }

    private void showAvailabilityThenAskDateTime(RecyclerView rv) {
        showThinkingUi();
        new Thread(() -> {
            AvailabilityWindow window = null;
            long doctorIdForSlots = pendingDoctorId;

            if (doctorIdForSlots <= 0L && !TextUtils.isEmpty(pendingSpecialityKey)) {
                try {
                    JSONArray arr = PatientApi.INSTANCE.getDoctors(requireContext(), pendingSpecialityKey);
                    if (arr != null && arr.length() > 0) {
                        JSONObject best = arr.optJSONObject(0);
                        if (best != null) {
                            doctorIdForSlots = parseDoctorIdAny(best);
                        }
                    }
                } catch (Throwable ignored) {}
            }

        if (doctorIdForSlots > 0L) {
            try {
                JSONArray days = PatientApi.INSTANCE.getSlots(requireContext(), (int) Math.min(Integer.MAX_VALUE, doctorIdForSlots), 7);
                cachedAvailabilityDays = days;
                cachedAvailabilityList = buildSortedAvailabilityList(days);
                availabilityStartIndex = 0;
                window = buildAvailabilityWindow(cachedAvailabilityList, availabilityStartIndex, 2);
            } catch (Throwable ignored) {}
        }

        final AvailabilityWindow w = window;
        handler.post(() -> {
            hideBusyUi();
            if (w != null && !TextUtils.isEmpty(w.text)) {
                addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, w.text, System.currentTimeMillis(), null, w.actions), rv);
            } else {
                String label = TextUtils.isEmpty(pendingSpecialityLabel) ? specialityLabelForKey(pendingSpecialityKey) : pendingSpecialityLabel;
                if (TextUtils.isEmpty(label)) label = translate("this specialty");
                addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI,
                        translate("No doctors are available for ") + label + ".", System.currentTimeMillis(), null), rv);
            }
        });
    }).start();
}

    private static class AvailabilityWindow {
        final String text;
        final java.util.List<String> actions;
        AvailabilityWindow(String text, java.util.List<String> actions) {
            this.text = text;
            this.actions = actions;
        }
    }

    private List<JSONObject> buildSortedAvailabilityList(JSONArray days) {
        if (days == null || days.length() == 0) return new ArrayList<>();
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.optJSONObject(i);
            if (d != null) list.add(d);
        }
        java.text.SimpleDateFormat dfIso = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        java.util.Collections.sort(list, (a, b) -> {
            String da = a != null ? a.optString("date", "") : "";
            String db = b != null ? b.optString("date", "") : "";
            try {
                java.util.Date ta = dfIso.parse(da);
                java.util.Date tb = dfIso.parse(db);
                if (ta == null || tb == null) return da.compareTo(db);
                return ta.compareTo(tb);
            } catch (Exception e) {
                return da.compareTo(db);
            }
        });
        return list;
    }

    private AvailabilityWindow buildAvailabilityWindow(List<JSONObject> days, int startIndex, int dayCount) {
        if (days == null || days.isEmpty()) return null;
        List<String> lines = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        int added = 0;

        java.text.SimpleDateFormat dfIso = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String todayIso = dfIso.format(new java.util.Date());
        String tomorrowIso = dfIso.format(new java.util.Date(System.currentTimeMillis() + 24L * 60L * 60L * 1000L));

        for (int i = startIndex; i < days.size() && added < dayCount; i++) {
            JSONObject day = days.get(i);
            if (day == null) continue;
            if (!day.optBoolean("enabled", true)) continue;
            String dateIso = day.optString("date", "");
            JSONArray sections = day.optJSONArray("sections");
            if (sections == null) continue;

            List<SlotItem> slotItems = new ArrayList<>();
            int available = 0;
            for (int s = 0; s < sections.length(); s++) {
                JSONObject sec = sections.optJSONObject(s);
                if (sec == null) continue;
                JSONArray slots = sec.optJSONArray("slots");
                if (slots == null) continue;
                for (int k = 0; k < slots.length(); k++) {
                    JSONObject slot = slots.optJSONObject(k);
                    if (slot == null || slot.optBoolean("disabled", false)) continue;
                    String label = slot.optString("label", "");
                    String value = slot.optString("value", "");
                    String display = !TextUtils.isEmpty(label) ? label : value;
                    if (!TextUtils.isEmpty(display)) {
                        available++;
                        String time24 = normalizeSlotToTime24(value, label);
                        slotItems.add(new SlotItem(display, time24));
                    }
                }
            }
            if (available == 0) continue;

            java.util.Collections.sort(slotItems, (a, b) -> Integer.compare(a.sortKey, b.sortKey));

            String dayLabel = dateIso;
            if (dateIso.equals(todayIso)) dayLabel = translate("Today");
            else if (dateIso.equals(tomorrowIso)) dayLabel = translate("Tomorrow");

            String timesText = buildTimesText(slotItems);
            String line = dayLabel + ": " + available + " " + translate("slots");
            lines.add(line);

            for (SlotItem si : slotItems) {
                if (!TextUtils.isEmpty(si.display)) {
                    actions.add(dayLabel + " " + si.display);
                }
            }
            added++;
        }

        if (lines.isEmpty()) return null;
        String lang = TranslationManager.currentLang(requireContext());
        String header;
        if ("hi".equals(lang)) header = "à¤‰à¤ªà¤²à¤¬à¥à¤§ à¤¸à¥à¤²à¥‰à¤Ÿ:";
        else if ("ta".equals(lang)) header = "à®•à®¿à®Ÿà¯ˆà®•à¯à®•à¯à®®à¯ à®¨à¯‡à®°à®™à¯à®•à®³à¯:";
        else if ("te".equals(lang)) header = "à°²à°­à±à°¯à°®à±ˆà°¨ à°¸à±à°²à°¾à°Ÿà±à°²à±:";
        else if ("kn".equals(lang)) header = "à²²à²­à³à²¯ à²¸à²®à²¯à²—à²³à³:";
        else if ("ml".equals(lang)) header = "à´²à´­àµà´¯à´®à´¾à´¯ à´¸à´®à´¯à´‚:";
        else header = "Available slots:";
        if (startIndex + dayCount < days.size()) {
            actions.add(translate("View more availability"));
        }
        return new AvailabilityWindow(header + "\n" + TextUtils.join("\n", lines), actions);
    }

    private String buildTimesText(List<SlotItem> slotItems) {
        if (slotItems == null || slotItems.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (SlotItem si : slotItems) {
            if (TextUtils.isEmpty(si.display)) continue;
            if (count > 0) {
                sb.append(", ");
                if (count % 4 == 0) sb.append("\n");
            }
            sb.append(si.display);
            count++;
        }
        return sb.toString();
    }

    private static class SlotItem {
        final String display;
        final int sortKey;
        SlotItem(String display, String time24) {
            this.display = display;
            int key = 9999;
            if (!TextUtils.isEmpty(time24) && time24.matches("\\d{2}:\\d{2}")) {
                key = safeInt(time24.substring(0, 2)) * 60 + safeInt(time24.substring(3, 5));
            }
            this.sortKey = key;
        }
        private int safeInt(String s) {
            try { return Integer.parseInt(s); } catch (Exception e) { return 9999; }
        }
    }

    private void showMoreAvailability(RecyclerView rv) {
        if (cachedAvailabilityList == null || cachedAvailabilityList.isEmpty()) {
            showAvailabilityThenAskDateTime(rv);
            return;
        }
        availabilityStartIndex = availabilityStartIndex + 2;
        AvailabilityWindow w = buildAvailabilityWindow(cachedAvailabilityList, availabilityStartIndex, 2);
        if (w == null) {
            addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, translate("No more availability."), System.currentTimeMillis(), null), rv);
            return;
        }
        addMessage(new PatientChatMessage(PatientChatMessage.Sender.AI, w.text, System.currentTimeMillis(), null, w.actions), rv);
    }
    private String askProblemText() {
        String lang = TranslationManager.currentLang(requireContext());
        if ("hi".equals(lang)) return "Ã Â¤â€¢Ã Â¥Æ’Ã Â¤ÂªÃ Â¤Â¯Ã Â¤Â¾ Ã Â¤Â¬Ã Â¤Â¤Ã Â¤Â¾Ã Â¤ÂÃ Â¤â€š Ã Â¤Â¸Ã Â¤Â®Ã Â¤Â¸Ã Â¥ÂÃ Â¤Â¯Ã Â¤Â¾ Ã Â¤â€¢Ã Â¥ÂÃ Â¤Â¯Ã Â¤Â¾ Ã Â¤Â¹Ã Â¥Ë† (Ã Â¤Â²Ã Â¤â€¢Ã Â¥ÂÃ Â¤Â·Ã Â¤Â£)?";
        if ("ta".equals(lang)) return "Ã Â®â€°Ã Â®â„¢Ã Â¯ÂÃ Â®â€¢Ã Â®Â³Ã Â¯ÂÃ Â®â€¢Ã Â¯ÂÃ Â®â€¢Ã Â¯Â Ã Â®Å½Ã Â®Â©Ã Â¯ÂÃ Â®Â© Ã Â®ÂªÃ Â®Â¿Ã Â®Â°Ã Â®Å¡Ã Â¯ÂÃ Â®Å¡Ã Â®Â©Ã Â¯Ë†/Ã Â®â€¦Ã Â®Â±Ã Â®Â¿Ã Â®â€¢Ã Â¯ÂÃ Â®Â±Ã Â®Â¿Ã Â®â€¢Ã Â®Â³Ã Â¯Â?";
        if ("te".equals(lang)) return "Ã Â°Â®Ã Â±â‚¬ Ã Â°Â¸Ã Â°Â®Ã Â°Â¸Ã Â±ÂÃ Â°Â¯/Ã Â°Â²Ã Â°â€¢Ã Â±ÂÃ Â°Â·Ã Â°Â£Ã Â°Â¾Ã Â°Â²Ã Â±Â Ã Â°ÂÃ Â°Â®Ã Â°Â¿Ã Â°Å¸Ã Â°Â¿?";
        if ("kn".equals(lang)) return "Ã Â²Â¨Ã Â²Â¿Ã Â²Â®Ã Â³ÂÃ Â²Â® Ã Â²Â¸Ã Â²Â®Ã Â²Â¸Ã Â³ÂÃ Â²Â¯Ã Â³â€ /Ã Â²Â²Ã Â²â€¢Ã Â³ÂÃ Â²Â·Ã Â²Â£Ã Â²â€”Ã Â²Â³Ã Â³Â Ã Â²ÂÃ Â²Â¨Ã Â³Â?";
        if ("ml".equals(lang)) return "Ã Â´Â¨Ã Â´Â¿Ã Â´â„¢Ã ÂµÂÃ Â´â„¢Ã ÂµÂ¾Ã Â´â€¢Ã ÂµÂÃ Â´â€¢Ã ÂµÂÃ Â´Â³Ã ÂµÂÃ Â´Â³ Ã Â´ÂªÃ ÂµÂÃ Â´Â°Ã Â´Â¶Ã ÂµÂÃ Â´Â¨Ã Â´â€š/Ã Â´Â²Ã Â´â€¢Ã ÂµÂÃ Â´Â·Ã Â´Â£Ã Â´â„¢Ã ÂµÂÃ Â´â„¢Ã ÂµÂ¾ Ã Â´Å½Ã Â´Â¨Ã ÂµÂÃ Â´Â¤Ã Â´Â¾Ã Â´Â£Ã ÂµÂ?";
        return "What problem are you facing? Please share your symptoms.";
    }

    private boolean isLikelyProblemDescription(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase(Locale.ROOT).trim();
        if (m.length() < 3) return false;
        String[] keywords = new String[]{
                "fever","cold","cough","pain","headache","migraine","vomit","nausea","stomach","gas",
                "diarrhea","loose","breath","breathing","asthma","chest","bp","sugar","diabetes",
                "allergy","rash","itch","itching","back","knee","joint","infection","injury",
                "head","throat","ear","eye","vision","skin","period","pregnant","anxiety","stress",
                "Ã Â¤Â¬Ã Â¥ÂÃ Â¤â€“Ã Â¤Â¾Ã Â¤Â°","Ã Â¤â€“Ã Â¤Â¾Ã Â¤ÂÃ Â¤Â¸Ã Â¥â‚¬","Ã Â¤Â¦Ã Â¤Â°Ã Â¥ÂÃ Â¤Â¦","Ã Â¤Â¸Ã Â¤Â°","Ã Â¤â€°Ã Â¤Â²Ã Â¥ÂÃ Â¤Å¸Ã Â¥â‚¬","Ã Â¤â€“Ã Â¥ÂÃ Â¤Å“Ã Â¤Â²Ã Â¥â‚¬","Ã Â¤Â¸Ã Â¤Â°Ã Â¥ÂÃ Â¤Â¦Ã Â¥â‚¬","Ã Â¤â€¢Ã Â¤Â®Ã Â¤Å“Ã Â¤Â¼Ã Â¥â€¹Ã Â¤Â°Ã Â¥â‚¬",
                "Ã Â®Å“Ã Â¯ÂÃ Â®ÂµÃ Â®Â°","Ã Â®â€¢Ã Â®Â¾Ã Â®Â¯Ã Â¯ÂÃ Â®Å¡Ã Â¯ÂÃ Â®Å¡Ã Â®Â²Ã Â¯Â","Ã Â®â€¡Ã Â®Â°Ã Â¯ÂÃ Â®Â®Ã Â®Â²Ã Â¯Â","Ã Â®ÂµÃ Â®Â²Ã Â®Â¿","Ã Â®Â¤Ã Â®Â²Ã Â¯Ë†Ã Â®ÂµÃ Â®Â²Ã Â®Â¿",
                "Ã Â°Å“Ã Â±ÂÃ Â°ÂµÃ Â°Â°Ã Â°â€š","Ã Â°Å“Ã Â°Â²Ã Â±ÂÃ Â°Â¬Ã Â±Â","Ã Â°Â¦Ã Â°â€”Ã Â±ÂÃ Â°â€”Ã Â±Â","Ã Â°Â¨Ã Â±Å Ã Â°ÂªÃ Â±ÂÃ Â°ÂªÃ Â°Â¿","Ã Â°Â¤Ã Â°Â²Ã Â°Â¨Ã Â±Å Ã Â°ÂªÃ Â±ÂÃ Â°ÂªÃ Â°Â¿",
                "Ã Â²Å“Ã Â³ÂÃ Â²ÂµÃ Â²Â°","Ã Â²Â¸Ã Â²Â°Ã Â³ÂÃ Â²Â¡Ã Â²Â¿","Ã Â²â€¢Ã Â³â€ Ã Â²Â®Ã Â³ÂÃ Â²Â®Ã Â³Â","Ã Â²Â¨Ã Â³â€¹Ã Â²ÂµÃ Â³Â",
                "Ã Â´Å“Ã ÂµÂÃ Â´ÂµÃ Â´Â°Ã Â´â€š","Ã Â´Å¡Ã ÂµÂÃ Â´Â®","Ã Â´ÂµÃ Âµâ€¡Ã Â´Â¦Ã Â´Â¨","Ã Â´Â¤Ã Â´Â²Ã Â´ÂµÃ Âµâ€¡Ã Â´Â¦Ã Â´Â¨"
        };
        for (String k : keywords) {
            if (m.contains(k)) return true;
        }
        int words = m.split("\\s+").length;
        return words >= 4;
    }

    private String bookingOpeningText() {
        String label = TextUtils.isEmpty(pendingSpecialityLabel) ? specialityLabelForKey(pendingSpecialityKey) : pendingSpecialityLabel;
        return translate("Opening booking for ") + label + ". " + translate("I've prefilled your slot. Please confirm and pay to book.");
    }

    private String timeNotAvailableText() {
        String lang = TranslationManager.currentLang(requireContext());
        if ("hi".equals(lang)) return "Ã Â¤Â¯Ã Â¤Â¹ Ã Â¤Â¸Ã Â¤Â®Ã Â¤Â¯ Ã Â¤â€°Ã Â¤ÂªÃ Â¤Â²Ã Â¤Â¬Ã Â¥ÂÃ Â¤Â§ Ã Â¤Â¨Ã Â¤Â¹Ã Â¥â‚¬Ã Â¤â€š Ã Â¤Â¹Ã Â¥Ë†Ã Â¥Â¤ Ã Â¤â€¢Ã Â¥Æ’Ã Â¤ÂªÃ Â¤Â¯Ã Â¤Â¾ Ã Â¤â€¢Ã Â¥â€¹Ã Â¤Ë† Ã Â¤â€Ã Â¤Â° Ã Â¤Â¸Ã Â¤Â®Ã Â¤Â¯ Ã Â¤Â¬Ã Â¤Â¤Ã Â¤Â¾Ã Â¤ÂÃ Â¤â€šÃ Â¥Â¤";
        if ("ta".equals(lang)) return "Ã Â®â€¡Ã Â®Â¨Ã Â¯ÂÃ Â®Â¤ Ã Â®Â¨Ã Â¯â€¡Ã Â®Â°Ã Â®Â®Ã Â¯Â Ã Â®â€¢Ã Â®Â¿Ã Â®Å¸Ã Â¯Ë†Ã Â®â€¢Ã Â¯ÂÃ Â®â€¢Ã Â®ÂµÃ Â®Â¿Ã Â®Â²Ã Â¯ÂÃ Â®Â²Ã Â¯Ë†. Ã Â®ÂµÃ Â¯â€¡Ã Â®Â±Ã Â¯Â Ã Â®Â¨Ã Â¯â€¡Ã Â®Â°Ã Â®Â®Ã Â¯Â Ã Â®Å¡Ã Â¯Å Ã Â®Â²Ã Â¯ÂÃ Â®Â²Ã Â¯ÂÃ Â®â„¢Ã Â¯ÂÃ Â®â€¢Ã Â®Â³Ã Â¯Â.";
        if ("te".equals(lang)) return "Ã Â°Ë† Ã Â°Â¸Ã Â°Â®Ã Â°Â¯Ã Â°â€š Ã Â°â€¦Ã Â°â€šÃ Â°Â¦Ã Â±ÂÃ Â°Â¬Ã Â°Â¾Ã Â°Å¸Ã Â±ÂÃ Â°Â²Ã Â±â€¹ Ã Â°Â²Ã Â±â€¡Ã Â°Â¦Ã Â±Â. Ã Â°â€¡Ã Â°â€šÃ Â°â€¢Ã Â±â€¹ Ã Â°Â¸Ã Â°Â®Ã Â°Â¯Ã Â°â€š Ã Â°Å¡Ã Â±â€ Ã Â°ÂªÃ Â±ÂÃ Â°ÂªÃ Â°â€šÃ Â°Â¡Ã Â°Â¿.";
        if ("kn".equals(lang)) return "Ã Â²Ë† Ã Â²Â¸Ã Â²Â®Ã Â²Â¯ Ã Â²Â²Ã Â²Â­Ã Â³ÂÃ Â²Â¯Ã Â²ÂµÃ Â²Â¿Ã Â²Â²Ã Â³ÂÃ Â²Â². Ã Â²Â¦Ã Â²Â¯Ã Â²ÂµÃ Â²Â¿Ã Â²Å¸Ã Â³ÂÃ Â²Å¸Ã Â³Â Ã Â²Â®Ã Â²Â¤Ã Â³ÂÃ Â²Â¤Ã Â³Å Ã Â²â€šÃ Â²Â¦Ã Â³Â Ã Â²Â¸Ã Â²Â®Ã Â²Â¯ Ã Â²Â¹Ã Â³â€¡Ã Â²Â³Ã Â²Â¿.";
        if ("ml".equals(lang)) return "Ã Â´Ë† Ã Â´Â¸Ã Â´Â®Ã Â´Â¯Ã Â´â€š Ã Â´Â²Ã Â´Â­Ã ÂµÂÃ Â´Â¯Ã Â´Â®Ã Â´Â²Ã ÂµÂÃ Â´Â². Ã Â´Â®Ã Â´Â±Ã ÂµÂÃ Â´Â±Ã ÂµÅ Ã Â´Â°Ã ÂµÂ Ã Â´Â¸Ã Â´Â®Ã Â´Â¯Ã Â´â€š Ã Â´ÂªÃ Â´Â±Ã Â´Â¯Ã Âµâ€š.";
        return "That time isn't available. Please tell another time.";
    }

    private String askTimeOnlyText() {
        String lang = TranslationManager.currentLang(requireContext());
        if ("hi".equals(lang)) return "Ã Â¤â€¢Ã Â¥Å’Ã Â¤Â¨ Ã Â¤Â¸Ã Â¤Â¾ Ã Â¤Â¸Ã Â¤Â®Ã Â¤Â¯ Ã Â¤Å¡Ã Â¤Â¾Ã Â¤Â¹Ã Â¤Â¿Ã Â¤Â? Ã Â¤Å“Ã Â¥Ë†Ã Â¤Â¸Ã Â¥â€¡: Ã Â¤Â¶Ã Â¤Â¾Ã Â¤Â® 6 Ã Â¤Â¬Ã Â¤Å“Ã Â¥â€¡";
        if ("ta".equals(lang)) return "Ã Â®Å½Ã Â®Â¨Ã Â¯ÂÃ Â®Â¤ Ã Â®Â¨Ã Â¯â€¡Ã Â®Â°Ã Â®Â®Ã Â¯Â Ã Â®ÂµÃ Â¯â€¡Ã Â®Â£Ã Â¯ÂÃ Â®Å¸Ã Â¯ÂÃ Â®Â®Ã Â¯Â? Ã Â®â€°Ã Â®Â¤Ã Â®Â¾: Ã Â®Â®Ã Â®Â¾Ã Â®Â²Ã Â¯Ë† 6";
        if ("te".equals(lang)) return "Ã Â°Â Ã Â°Â¸Ã Â°Â®Ã Â°Â¯Ã Â°â€š Ã Â°â€¢Ã Â°Â¾Ã Â°ÂµÃ Â°Â¾Ã Â°Â²Ã Â°Â¿? Ã Â°â€°Ã Â°Â¦Ã Â°Â¾: Ã Â°Â¸Ã Â°Â¾Ã Â°Â¯Ã Â°â€šÃ Â°Â¤Ã Â±ÂÃ Â°Â°Ã Â°â€š 6";
        if ("kn".equals(lang)) return "Ã Â²Â¯Ã Â²Â¾Ã Â²Âµ Ã Â²Â¸Ã Â²Â®Ã Â²Â¯ Ã Â²Â¬Ã Â³â€¡Ã Â²â€¢Ã Â³Â? Ã Â²â€°Ã Â²Â¦Ã Â²Â¾: Ã Â²Â¸Ã Â²â€šÃ Â²Å“Ã Â³â€  6";
        if ("ml".equals(lang)) return "Ã Â´ÂÃ Â´Â¤Ã ÂµÂ Ã Â´Â¸Ã Â´Â®Ã Â´Â¯Ã Â´â€š Ã Â´ÂµÃ Âµâ€¡Ã Â´Â£Ã Â´â€š? Ã Â´â€°Ã Â´Â¦Ã Â´Â¾: Ã Â´ÂµÃ ÂµË†Ã Â´â€¢Ã Â´Â¿Ã Â´Å¸Ã ÂµÂÃ Â´Å¸Ã ÂµÂ 6";
        return "What time works? Example: 6 pm";
    }

    private String askDateOnlyText() {
        String lang = TranslationManager.currentLang(requireContext());
        if ("hi".equals(lang)) return "Ã Â¤â€¢Ã Â¤Â¿Ã Â¤Â¸ Ã Â¤Â¤Ã Â¤Â¾Ã Â¤Â°Ã Â¥â‚¬Ã Â¤â€“ Ã Â¤ÂªÃ Â¤Â°? Ã Â¤Å“Ã Â¥Ë†Ã Â¤Â¸Ã Â¥â€¡: Ã Â¤â€ Ã Â¤Å“ Ã Â¤Â¯Ã Â¤Â¾ Ã Â¤â€¢Ã Â¤Â²";
        if ("ta".equals(lang)) return "Ã Â®Å½Ã Â®Â¨Ã Â¯ÂÃ Â®Â¤ Ã Â®Â¤Ã Â¯â€¡Ã Â®Â¤Ã Â®Â¿Ã Â®Â¯Ã Â®Â¿Ã Â®Â²Ã Â¯Â? Ã Â®â€°Ã Â®Â¤Ã Â®Â¾: Ã Â®â€¡Ã Â®Â©Ã Â¯ÂÃ Â®Â±Ã Â¯Â Ã Â®â€¦Ã Â®Â²Ã Â¯ÂÃ Â®Â²Ã Â®Â¤Ã Â¯Â Ã Â®Â¨Ã Â®Â¾Ã Â®Â³Ã Â¯Ë†";
        if ("te".equals(lang)) return "Ã Â°Â Ã Â°Â¤Ã Â±â€¡Ã Â°Â¦Ã Â±â‚¬Ã Â°Â¨? Ã Â°â€°Ã Â°Â¦Ã Â°Â¾: Ã Â°Ë† Ã Â°Â°Ã Â±â€¹Ã Â°Å“Ã Â±Â Ã Â°Â²Ã Â±â€¡Ã Â°Â¦Ã Â°Â¾ Ã Â°Â°Ã Â±â€¡Ã Â°ÂªÃ Â±Â";
        if ("kn".equals(lang)) return "Ã Â²Â¯Ã Â²Â¾Ã Â²Âµ Ã Â²Â¦Ã Â²Â¿Ã Â²Â¨Ã Â²Â¾Ã Â²â€šÃ Â²â€¢Ã Â²â€¢Ã Â³ÂÃ Â²â€¢Ã Â³â€ ? Ã Â²â€°Ã Â²Â¦Ã Â²Â¾: Ã Â²â€¡Ã Â²â€šÃ Â²Â¦Ã Â³Â Ã Â²â€¦Ã Â²Â¥Ã Â²ÂµÃ Â²Â¾ Ã Â²Â¨Ã Â²Â¾Ã Â²Â³Ã Â³â€ ";
        if ("ml".equals(lang)) return "Ã Â´ÂÃ Â´Â¤Ã ÂµÂ Ã Â´Â¤Ã Âµâ‚¬Ã Â´Â¯Ã Â´Â¤Ã Â´Â¿? Ã Â´â€°Ã Â´Â¦Ã Â´Â¾: Ã Â´â€¡Ã Â´Â¨Ã ÂµÂÃ Â´Â¨Ã ÂµÂ Ã Â´â€¦Ã Â´Â²Ã ÂµÂÃ Â´Â²Ã Âµâ€ Ã Â´â„¢Ã ÂµÂÃ Â´â€¢Ã Â´Â¿Ã ÂµÂ½ Ã Â´Â¨Ã Â´Â¾Ã Â´Â³Ã Âµâ€ ";
        return "Which date? Example: today or tomorrow";
    }

    private String askConfirmSuggestedText() {
        if (TextUtils.isEmpty(suggestedDateIso) || TextUtils.isEmpty(suggestedTime24)) {
            return askDateTimeText();
        }
        String lang = TranslationManager.currentLang(requireContext());
        if ("hi".equals(lang)) return "Ã Â¤Â®Ã Â¥Ë†Ã Â¤â€š " + suggestedDateIso + " Ã Â¤â€¢Ã Â¥â€¹ " + suggestedTime24 + " Ã Â¤ÂªÃ Â¤Â° Ã Â¤Â¬Ã Â¥ÂÃ Â¤â€¢ Ã Â¤â€¢Ã Â¤Â° Ã Â¤Â¦Ã Â¥â€šÃ Â¤Â?";
        if ("ta".equals(lang)) return suggestedDateIso + " Ã Â®â€¦Ã Â®Â©Ã Â¯ÂÃ Â®Â±Ã Â¯Â " + suggestedTime24 + "Ã Â®â€¢Ã Â¯ÂÃ Â®â€¢Ã Â¯Â Ã Â®ÂªÃ Â¯ÂÃ Â®â€¢Ã Â¯Â Ã Â®Å¡Ã Â¯â€ Ã Â®Â¯Ã Â¯ÂÃ Â®Â¯Ã Â®Â²Ã Â®Â¾Ã Â®Â®Ã Â®Â¾?";
        if ("te".equals(lang)) return suggestedDateIso + " Ã Â°Â²Ã Â±â€¹ " + suggestedTime24 + "Ã Â°â€¢Ã Â°Â¿ Ã Â°Â¬Ã Â±ÂÃ Â°â€¢Ã Â±Â Ã Â°Å¡Ã Â±â€¡Ã Â°Â¯Ã Â°Â¾Ã Â°Â²Ã Â°Â¾?";
        if ("kn".equals(lang)) return suggestedDateIso + " Ã Â²Â°Ã Â²â€šÃ Â²Â¦Ã Â³Â " + suggestedTime24 + " Ã Â²â€¢Ã Â³ÂÃ Â²â€¢Ã Â³â€  Ã Â²Â¬Ã Â³ÂÃ Â²â€¢Ã Â³Â Ã Â²Â®Ã Â²Â¾Ã Â²Â¡Ã Â²Â¬Ã Â²Â¹Ã Â³ÂÃ Â²Â¦Ã Â²Â¾?";
        if ("ml".equals(lang)) return suggestedDateIso + " Ã Â´Â¨Ã ÂµÂ " + suggestedTime24 + " Ã Â´Â¨Ã ÂµÂ Ã Â´Â¬Ã ÂµÂÃ Â´â€¢Ã ÂµÂÃ Â´â€¢Ã ÂµÂ Ã Â´Å¡Ã Âµâ€ Ã Â´Â¯Ã ÂµÂÃ Â´Â¯Ã Â´Â¾Ã Â´Â®Ã Âµâ€¹?";
        return "I can book " + suggestedDateIso + " at " + suggestedTime24 + ". Should I proceed?";
    }

    private static class BookingIntent {
        final boolean requested;
        final String consultType;
        final String dateIso;
        final String time24;

        BookingIntent(boolean requested, String consultType, String dateIso, String time24) {
            this.requested = requested;
            this.consultType = consultType;
            this.dateIso = dateIso;
            this.time24 = time24;
        }
    }

    private BookingIntent parseBookingIntent(String msg) {
        String m = msg == null ? "" : msg.toLowerCase(Locale.ROOT);
        boolean requested = m.contains("book") || m.contains("appointment") || m.contains("consult")
                || m.contains("schedule") || m.contains("book it") || m.contains("book now")
                || m.contains("Ã Â¤Â¬Ã Â¥ÂÃ Â¤â€¢") || m.contains("Ã Â¤â€¦Ã Â¤ÂªÃ Â¥â€°Ã Â¤â€¡Ã Â¤â€šÃ Â¤Å¸Ã Â¤Â®Ã Â¥â€¡Ã Â¤â€šÃ Â¤Å¸") || m.contains("Ã Â¤Â¨Ã Â¤Â¿Ã Â¤Â¯Ã Â¥ÂÃ Â¤â€¢Ã Â¥ÂÃ Â¤Â¤Ã Â¤Â¿")
                || m.contains("Ã Â®ÂªÃ Â¯ÂÃ Â®â€¢Ã Â¯Â") || m.contains("Ã Â®â€¦Ã Â®ÂªÃ Â¯ÂÃ Â®ÂªÃ Â®Â¾Ã Â®Â¯Ã Â®Â¿Ã Â®Â£Ã Â¯ÂÃ Â®Å¸Ã Â¯Â") || m.contains("Ã Â°Â¬Ã Â±ÂÃ Â°â€¢Ã Â±Â")
                || m.contains("Ã Â°â€¦Ã Â°ÂªÃ Â°Â¾Ã Â°Â¯Ã Â°Â¿Ã Â°â€šÃ Â°Å¸Ã Â±Â") || m.contains("Ã Â²Â¬Ã Â³ÂÃ Â²â€¢Ã Â³Â") || m.contains("Ã Â´â€¦Ã Â´ÂªÃ ÂµÂÃ Â´ÂªÃ Âµâ€¹Ã Â´Â¯Ã Â´Â¿Ã Â´Â¨Ã ÂµÂÃ Â´Â±");

        String consult = consultTypeFromText(msg);
        DateTimeSelection dt = parseDateTimeFromText(msg);
        return new BookingIntent(requested, consult, dt.dateIso, dt.time24);
    }

    private String consultTypeFromText(String msg) {
        return CONSULT_TYPE_PHYSICAL;
    }

    private static class DateTimeSelection {
        final String dateIso;
        final String time24;
        final boolean hasDate;
        final boolean hasTime;

        DateTimeSelection(String dateIso, String time24, boolean hasDate, boolean hasTime) {
            this.dateIso = dateIso;
            this.time24 = time24;
            this.hasDate = hasDate;
            this.hasTime = hasTime;
        }
    }

    private DateTimeSelection parseDateTimeFromText(String msg) {
        String m = msg.trim();
        String dateIso = "";
        String time24 = "";
        boolean hasDate = false;
        boolean hasTime = false;

        // Date: today/tomorrow keywords (multi-language)
        String lower = m.toLowerCase(Locale.ROOT);
        boolean isToday = lower.contains("today") || lower.contains("Ã Â¤â€ Ã Â¤Å“") || lower.contains("Ã Â®â€¡Ã Â®Â©Ã Â¯ÂÃ Â®Â±Ã Â¯Â") || lower.contains("Ã Â°Ë† Ã Â°Â°Ã Â±â€¹Ã Â°Å“Ã Â±Â") || lower.contains("Ã Â²â€¡Ã Â²â€šÃ Â²Â¦Ã Â³Â") || lower.contains("Ã Â´â€¡Ã Â´Â¨Ã ÂµÂÃ Â´Â¨Ã ÂµÂ");
        boolean isTomorrow = lower.contains("tomorrow") || lower.contains("Ã Â¤â€¢Ã Â¤Â²") || lower.contains("Ã Â®Â¨Ã Â®Â¾Ã Â®Â³Ã Â¯Ë†") || lower.contains("Ã Â°Â°Ã Â±â€¡Ã Â°ÂªÃ Â±Â") || lower.contains("Ã Â²Â¨Ã Â²Â¾Ã Â²Â³Ã Â³â€ ") || lower.contains("Ã Â´Â¨Ã Â´Â¾Ã Â´Â³Ã Âµâ€ ");

        java.text.SimpleDateFormat dfIso = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        if (isToday) {
            dateIso = dfIso.format(new java.util.Date());
            hasDate = true;
        } else if (isTomorrow) {
            dateIso = dfIso.format(new java.util.Date(System.currentTimeMillis() + 24L * 60L * 60L * 1000L));
            hasDate = true;
        }

        // Date: yyyy-MM-dd
        java.util.regex.Matcher mIso = java.util.regex.Pattern.compile("\\b(20\\d{2})-(\\d{2})-(\\d{2})\\b").matcher(lower);
        if (mIso.find()) {
            dateIso = mIso.group(1) + "-" + mIso.group(2) + "-" + mIso.group(3);
            hasDate = true;
        }

        // Date: dd/MM/yyyy or dd-MM-yyyy
        java.util.regex.Matcher mDmy = java.util.regex.Pattern.compile("\\b(\\d{1,2})[/-](\\d{1,2})[/-](20\\d{2})\\b").matcher(lower);
        if (!hasDate && mDmy.find()) {
            String dd = mDmy.group(1);
            String mm = mDmy.group(2);
            String yy = mDmy.group(3);
            dateIso = String.format(Locale.US, "%s-%02d-%02d", yy, Integer.parseInt(mm), Integer.parseInt(dd));
            hasDate = true;
        }

        // Time: HH:mm or H:mm (with optional am/pm)
        java.util.regex.Matcher mTime = java.util.regex.Pattern.compile("\\b(\\d{1,2})[:.](\\d{2})\\s*([ap]m)?\\b").matcher(lower);
        if (mTime.find()) {
            int hh = safeParseInt(mTime.group(1));
            int mm = safeParseInt(mTime.group(2));
            String ap = mTime.group(3);
            if (ap != null) {
                if ("pm".equals(ap) && hh < 12) hh += 12;
                if ("am".equals(ap) && hh == 12) hh = 0;
            }
            if (hh >= 0 && hh <= 23 && mm >= 0 && mm <= 59) {
                time24 = String.format(Locale.US, "%02d:%02d", hh, mm);
                hasTime = true;
            }
        }

        // Time: H am/pm (6pm)
        if (!hasTime) {
            java.util.regex.Matcher mHm = java.util.regex.Pattern.compile("\\b(\\d{1,2})\\s*([ap]m)\\b").matcher(lower);
            if (mHm.find()) {
                int hh = safeParseInt(mHm.group(1));
                String ap = mHm.group(2);
                if ("pm".equals(ap) && hh < 12) hh += 12;
                if ("am".equals(ap) && hh == 12) hh = 0;
                if (hh >= 0 && hh <= 23) {
                    time24 = String.format(Locale.US, "%02d:00", hh);
                    hasTime = true;
                }
            }
        }

        // Time: "at 6" (only when context suggests a time)
        if (!hasTime) {
            boolean hasTimeHint = lower.contains("at ") || lower.contains("@")
                    || lower.contains("morning") || lower.contains("afternoon") || lower.contains("evening") || lower.contains("night")
                    || lower.contains("Ã Â¤Â¸Ã Â¥ÂÃ Â¤Â¬Ã Â¤Â¹") || lower.contains("Ã Â¤Â¦Ã Â¥â€¹Ã Â¤ÂªÃ Â¤Â¹Ã Â¤Â°") || lower.contains("Ã Â¤Â¶Ã Â¤Â¾Ã Â¤Â®") || lower.contains("Ã Â¤Â°Ã Â¤Â¾Ã Â¤Â¤")
                    || lower.contains("Ã Â®â€¢Ã Â®Â¾Ã Â®Â²Ã Â¯Ë†") || lower.contains("Ã Â®Â®Ã Â®Â¾Ã Â®Â²Ã Â¯Ë†") || lower.contains("Ã Â®â€¡Ã Â®Â°Ã Â®ÂµÃ Â¯Â")
                    || lower.contains("Ã Â°â€°Ã Â°Â¦Ã Â°Â¯Ã Â°â€š") || lower.contains("Ã Â°Â¸Ã Â°Â¾Ã Â°Â¯Ã Â°â€šÃ Â°Â¤Ã Â±ÂÃ Â°Â°Ã Â°â€š") || lower.contains("Ã Â°Â°Ã Â°Â¾Ã Â°Â¤Ã Â±ÂÃ Â°Â°Ã Â°Â¿")
                    || lower.contains("Ã Â²Â¬Ã Â³â€ Ã Â²Â³Ã Â²â€”Ã Â³ÂÃ Â²â€”Ã Â³â€ ") || lower.contains("Ã Â²Â¸Ã Â²â€šÃ Â²Å“Ã Â³â€ ") || lower.contains("Ã Â²Â°Ã Â²Â¾Ã Â²Â¤Ã Â³ÂÃ Â²Â°Ã Â²Â¿")
                    || lower.contains("Ã Â´Â°Ã Â´Â¾Ã Â´ÂµÃ Â´Â¿Ã Â´Â²Ã Âµâ€ ") || lower.contains("Ã Â´ÂµÃ ÂµË†Ã Â´â€¢Ã Â´Â¿Ã Â´Å¸Ã ÂµÂÃ Â´Å¸Ã ÂµÂ") || lower.contains("Ã Â´Â°Ã Â´Â¾Ã Â´Â¤Ã ÂµÂÃ Â´Â°Ã Â´Â¿");

            if (hasTimeHint) {
                java.util.regex.Matcher mHour = java.util.regex.Pattern.compile("\\b(\\d{1,2})\\b").matcher(lower);
                if (mHour.find()) {
                    int hh = safeParseInt(mHour.group(1));
                    if (lower.contains("evening") || lower.contains("afternoon") || lower.contains("night")
                            || lower.contains("Ã Â¤Â¶Ã Â¤Â¾Ã Â¤Â®") || lower.contains("Ã Â¤Â°Ã Â¤Â¾Ã Â¤Â¤")
                            || lower.contains("Ã Â®Â®Ã Â®Â¾Ã Â®Â²Ã Â¯Ë†") || lower.contains("Ã Â®â€¡Ã Â®Â°Ã Â®ÂµÃ Â¯Â")
                            || lower.contains("Ã Â°Â¸Ã Â°Â¾Ã Â°Â¯Ã Â°â€šÃ Â°Â¤Ã Â±ÂÃ Â°Â°Ã Â°â€š") || lower.contains("Ã Â°Â°Ã Â°Â¾Ã Â°Â¤Ã Â±ÂÃ Â°Â°Ã Â°Â¿")
                            || lower.contains("Ã Â²Â¸Ã Â²â€šÃ Â²Å“Ã Â³â€ ") || lower.contains("Ã Â²Â°Ã Â²Â¾Ã Â²Â¤Ã Â³ÂÃ Â²Â°Ã Â²Â¿")
                            || lower.contains("Ã Â´ÂµÃ ÂµË†Ã Â´â€¢Ã Â´Â¿Ã Â´Å¸Ã ÂµÂÃ Â´Å¸Ã ÂµÂ") || lower.contains("Ã Â´Â°Ã Â´Â¾Ã Â´Â¤Ã ÂµÂÃ Â´Â°Ã Â´Â¿")) {
                        if (hh < 12) hh += 12;
                    }
                    if (hh >= 0 && hh <= 23) {
                        time24 = String.format(Locale.US, "%02d:00", hh);
                        hasTime = true;
                    }
                }
            }
        }

        return new DateTimeSelection(dateIso, time24, hasDate, hasTime);
    }

    private int safeParseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private String whyFitPrefix() {
        String lang = TranslationManager.currentLang(requireContext());
        if ("hi".equals(lang)) return "Ã Â¤â€°Ã Â¤ÂªÃ Â¤Â¯Ã Â¥ÂÃ Â¤â€¢Ã Â¥ÂÃ Â¤Â¤ Ã Â¤ÂµÃ Â¤Â¿Ã Â¤Â¶Ã Â¥â€¡Ã Â¤Â·Ã Â¤Å“Ã Â¥ÂÃ Â¤Å¾:";
        if ("ta".equals(lang)) return "Ã Â®â€°Ã Â®â€¢Ã Â®Â¨Ã Â¯ÂÃ Â®Â¤ Ã Â®Â¨Ã Â®Â¿Ã Â®ÂªÃ Â¯ÂÃ Â®Â£Ã Â®Â°Ã Â¯Â:";
        if ("te".equals(lang)) return "Ã Â°Â¸Ã Â°Â°Ã Â±Ë†Ã Â°Â¨ Ã Â°Â¨Ã Â°Â¿Ã Â°ÂªÃ Â±ÂÃ Â°Â£Ã Â±ÂÃ Â°Â¡Ã Â±Â:";
        if ("kn".equals(lang)) return "Ã Â²Â¸Ã Â³â€šÃ Â²â€¢Ã Â³ÂÃ Â²Â¤ Ã Â²Â¤Ã Â²Å“Ã Â³ÂÃ Â²Å¾Ã Â²Â°Ã Â³Â:";
        if ("ml".equals(lang)) return "Ã Â´Â¯Ã Âµâ€¹Ã Â´â€”Ã ÂµÂÃ Â´Â¯Ã Â´Â¨Ã Â´Â¾Ã Â´Â¯ Ã Â´ÂµÃ Â´Â¿Ã Â´Â¦Ã Â´â€”Ã ÂµÂÃ Â´Â§Ã ÂµÂ»:";
        return "Suitable specialist:";
    }

    private String specialityLabelForKey(String key) {
        if (TextUtils.isEmpty(key)) return "";
        String k = key.trim().toUpperCase(Locale.ROOT);
        switch (k) {
            case "CARDIOLOGY": return getString(R.string.speciality_heart);
            case "NEUROLOGY": return getString(R.string.speciality_brain);
            case "ORTHOPEDICS": return getString(R.string.speciality_bones);
            case "OPHTHALMOLOGY": return getString(R.string.speciality_eyes);
            case "PEDIATRICS": return getString(R.string.speciality_child);
            case "DERMATOLOGY": return getString(R.string.speciality_skin);
            case "PULMONOLOGY": return getString(R.string.speciality_lungs);
            case "DIABETOLOGY": return getString(R.string.speciality_diabetes);
            case "FEVER_CLINIC": return getString(R.string.speciality_fever);
            case "GENERAL_MEDICINE": return getString(R.string.speciality_medicine);
            case "EMERGENCY": return getString(R.string.speciality_emergency);
            default: return getString(R.string.speciality_general);
        }
    }

    // -------------------------
    // Specialities context (doctor types)
    // -------------------------

    private void fetchSpecialitiesSummary() {
        if (specialitiesLoading.getAndSet(true)) return;

        new Thread(() -> {
            try {
                JSONArray arr = PatientApi.INSTANCE.getSpecialities(requireContext());
                if (arr != null && arr.length() > 0) {
                    String summary = buildSpecialitiesSummary(arr);
                    if (!TextUtils.isEmpty(summary)) specialitiesSummary = summary;
                }
            } catch (Throwable ignored) {
            } finally {
                specialitiesLoading.set(false);
            }
        }).start();
    }

    private void fetchUpcomingBookingFlag() {
        if (upcomingBookingLoading.getAndSet(true)) return;
        new Thread(() -> {
            boolean has = false;
            try {
                JSONArray arr = PatientApi.INSTANCE.listAppointments(requireContext(), "UPCOMING", 1, 0);
                has = (arr != null && arr.length() > 0);
            } catch (Throwable ignored) {}
            hasUpcomingBooking = has;
            lastUpcomingBookingFetchMs = System.currentTimeMillis();
            upcomingBookingLoading.set(false);
            handler.post(() -> updateChipsForContext(rvChat));
        }).start();
    }

    private void ensureUpcomingBookingFlagSync() {
        if (lastUpcomingBookingFetchMs > 0) return;
        if (!upcomingBookingLoading.get()) {
            fetchUpcomingBookingFlag();
        }
        long end = SystemClock.uptimeMillis() + 700L;
        while (upcomingBookingLoading.get() && SystemClock.uptimeMillis() < end) {
            try { Thread.sleep(40); } catch (Exception ignored) {}
        }
    }

    private String buildSpecialitiesSummary(JSONArray arr) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        int n = Math.min(arr.length(), 30);
        for (int i = 0; i < n; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String label = o.optString("label", o.optString("name", o.optString("title", ""))).trim();
            if (label.isBlank()) {
                label = o.optString("speciality", o.optString("specialty", o.optString("specialization", ""))).trim();
            }
            if (!label.isBlank()) names.add(label);
        }
        if (names.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String s : names) {
            if (i++ > 0) sb.append(", ");
            sb.append(s);
            if (i >= 12) break;
        }
        return sb.toString();
    }

    // -------------------------
    // Translations
    // -------------------------

    private void applyTranslations() {
        if (!isAdded()) return;

        if (tvDisclaimer != null) tvDisclaimer.setText(translate("Not medical advice. Contact doctor for HIGH risk."));
        if (tvAnalyzing != null) tvAnalyzing.setText(translate("Analyzing..."));
        if (btnClear != null) btnClear.setText(translate("Clear chat"));
        if (etInput != null) etInput.setHint(translate("Ask about risk or precautions"));

        applyTtsLanguage();
        syncComposerUi();
    }

    private String translate(String text) {
        return TranslationManager.t(requireContext(), text);
    }

    // -------------------------
    // Lottie / Animations
    // -------------------------

    private void animateSheetIn(View root) {
        if (root == null) return;
        float dy = 24f * root.getResources().getDisplayMetrics().density;
        root.setTranslationY(dy);
        root.setAlpha(0f);
        root.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(240L)
                .setInterpolator(android.view.animation.AnimationUtils.loadInterpolator(
                        requireContext(), android.R.interpolator.fast_out_slow_in))
                .start();
    }

    private void attachLottie(View stateRow) {
        if (!(stateRow instanceof ViewGroup)) return;
        ViewGroup vg = (ViewGroup) stateRow;
        lottieListening = createLottie(vg);
        lottieThinking = createLottie(vg);
        if (lottieListening != null) vg.addView(lottieListening, 0);
        if (lottieThinking != null) vg.addView(lottieThinking, 0);
        if (lottieListening != null) lottieListening.setVisibility(View.GONE);
        if (lottieThinking != null) lottieThinking.setVisibility(View.GONE);
    }

    private LottieAnimationView createLottie(ViewGroup parent) {
        try {
            LottieAnimationView v = new LottieAnimationView(parent.getContext());
            int size = (int) (parent.getResources().getDisplayMetrics().density * 120);
            ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(size, size);
            v.setLayoutParams(lp);
            v.setRepeatCount(LottieDrawable.INFINITE);
            v.setRepeatMode(LottieDrawable.RESTART);
            v.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            v.setVisibility(View.GONE);
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private void playLottie(LottieAnimationView view, String asset) {
        if (view == null) return;
        try {
            view.setAnimation(asset);
            view.setVisibility(View.VISIBLE);
            view.playAnimation();
        } catch (Exception e) {
            view.setVisibility(View.GONE);
        }
    }

    private void stopLottie(LottieAnimationView view) {
        if (view == null) return;
        view.cancelAnimation();
        view.setVisibility(View.GONE);
    }

    private void startHeaderGlow(View headerGlow) {
        if (headerGlow == null) return;
        headerGlowAnimator = ValueAnimator.ofFloat(0.08f, 0.35f);
        headerGlowAnimator.setDuration(1800);
        headerGlowAnimator.setRepeatMode(ValueAnimator.REVERSE);
        headerGlowAnimator.setRepeatCount(ValueAnimator.INFINITE);
        headerGlowAnimator.addUpdateListener(animation ->
                headerGlow.setAlpha((float) animation.getAnimatedValue()));
        headerGlowAnimator.start();
    }

    // -------------------------
    // Cleanup
    // -------------------------

    private void cleanup() {
        handler.removeCallbacks(voiceFinalizeFallback);

        try {
            if (speechRecognizer != null) {
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            }
        } catch (Exception ignore) {}
        speechRecognizer = null;
        speechIntent = null;

        if (headerGlowAnimator != null) headerGlowAnimator.cancel();
        if (streamingRenderer != null) streamingRenderer.cancel();
        if (listeningController != null) listeningController.cancel();

        if (etInput != null && composerWatcher != null) {
            try { etInput.removeTextChangedListener(composerWatcher); } catch (Exception ignore) {}
        }
        composerWatcher = null;

        shutdownTts();
        hideBusyUi();
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        super.onDismiss(dialog);
        cleanup();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (prefs != null && langListener != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(langListener);
        }
        if (tmLangListener != null) {
            TranslationManager.removeLangListener(tmLangListener);
        }
        cleanup();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (prefs == null) {
            prefs = requireContext().getSharedPreferences("UserPrefs", android.content.Context.MODE_PRIVATE);
        }
        if (langListener == null) {
            langListener = (sp, key) -> {
                if ("patient_lang".equals(key)) {
                    if (getView() != null) TranslationManager.apply(getView(), requireContext());
                }
            };
        }
        prefs.registerOnSharedPreferenceChangeListener(langListener);

        if (tmLangListener == null) {
            tmLangListener = lang -> {
                if (!isAdded()) return;
                applyTranslations();
                if (getView() != null) TranslationManager.apply(getView(), requireContext());
            };
        }
        TranslationManager.addLangListener(tmLangListener);
    }

    // -------------------------
    // Locale helpers for voice + TTS
    // -------------------------

    private Locale localeForAppLanguage() {
        String lang = TranslationManager.currentLang(requireContext());
        if ("te".equals(lang)) return new Locale("te", "IN");
        if ("hi".equals(lang)) return new Locale("hi", "IN");
        if ("ta".equals(lang)) return new Locale("ta", "IN");
        if ("kn".equals(lang)) return new Locale("kn", "IN");
        if ("ml".equals(lang)) return new Locale("ml", "IN");
        return Locale.US;
    }

    private String toTag(Locale loc) {
        try { return loc.toLanguageTag(); } catch (Exception ignore) { }
        return loc.getLanguage() + (TextUtils.isEmpty(loc.getCountry()) ? "" : "-" + loc.getCountry());
    }
}



