package com.simats.criticall;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal language preference manager.
 *
 * ✅ Stores patient language in SharedPreferences (UserPrefs/patient_lang)
 * ✅ Notifies listeners when language changes
 * ✅ Keeps old TranslationManager APIs so older code compiles
 *
 * NOTE: This class does NOT translate app UI anymore.
 * If you want translation in chat, use OnDeviceTranslator (ML Kit) separately.
 */
public final class TranslationManager {

    private TranslationManager() {}

    public static final String LANG_EN = "en";
    public static final String LANG_HI = "hi";
    public static final String LANG_TA = "ta";
    public static final String LANG_TE = "te";
    public static final String LANG_KN = "kn";
    public static final String LANG_ML = "ml";

    public static final String[] SUPPORTED = new String[]{
            LANG_EN, LANG_HI, LANG_TA, LANG_TE, LANG_KN, LANG_ML
    };

    private static final String PREFS = "UserPrefs";
    private static final String PREF_KEY_LANG = "patient_lang";

    // -------------------------
    // Language getters / setters
    // -------------------------

    /** Current language code (default = "en") */
    public static String currentLang(Context ctx) {
        if (ctx == null) return LANG_EN;
        // Prefer app-level language (used across the app)
        String appLang = null;
        try { appLang = AppPrefs.INSTANCE.getLang(ctx); } catch (Throwable ignored) {}
        if (!TextUtils.isEmpty(appLang)) return sanitizeLang(appLang);

        // Fallback to legacy patient_lang in UserPrefs
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String lang = prefs.getString(PREF_KEY_LANG, LANG_EN);
        return sanitizeLang(lang);
    }

    /** ✅ Expected by other code */
    public static String getPatientLang(Context ctx) {
        return currentLang(ctx);
    }

    /** Set patient language (persists + notifies listeners) */
    public static void setLang(Context ctx, String lang) {
        if (ctx == null) return;
        String safe = sanitizeLang(lang);
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_KEY_LANG, safe).apply();
        try { AppPrefs.INSTANCE.setLang(ctx, safe); } catch (Throwable ignored) {}
        notifyLangChanged(safe);
    }

    /** Optional alias */
    public static void setPatientLang(Context ctx, String lang) {
        setLang(ctx, lang);
    }

    public static String displayName(String lang) {
        switch (sanitizeLang(lang)) {
            case LANG_HI: return "Hindi";
            case LANG_TA: return "Tamil";
            case LANG_TE: return "Telugu";
            case LANG_KN: return "Kannada";
            case LANG_ML: return "Malayalam";
            default: return "English";
        }
    }

    // -------------------------
    // Listener support
    // -------------------------

    public interface LangListener {
        void onLangChanged(String lang);
    }

    private static final CopyOnWriteArrayList<WeakReference<LangListener>> LANG_LISTENERS =
            new CopyOnWriteArrayList<>();

    public static void addLangListener(LangListener listener) {
        if (listener == null) return;
        LANG_LISTENERS.addIfAbsent(new WeakReference<>(listener));
    }

    public static void removeLangListener(LangListener listener) {
        if (listener == null) return;
        for (WeakReference<LangListener> ref : LANG_LISTENERS) {
            LangListener l = ref.get();
            if (l == null || l == listener) {
                LANG_LISTENERS.remove(ref);
            }
        }
    }

    private static void notifyLangChanged(String lang) {
        for (WeakReference<LangListener> ref : LANG_LISTENERS) {
            LangListener l = ref.get();
            if (l == null) {
                LANG_LISTENERS.remove(ref);
            } else {
                l.onLangChanged(lang);
            }
        }
    }

    // -------------------------
    // Translation APIs (compatibility)
    // -------------------------

    /**
     * Compatibility method: returns original text.
     * (App UI translations removed intentionally.)
     */
    public static String t(Context ctx, String text) {
        return t(text, currentLang(ctx));
    }

    /**
     * Compatibility overload: returns original text.
     */
    public static String t(String text, String lang) {
        if (text == null) return "";
        String t = text.trim();
        String l = sanitizeLang(lang);

        if (LANG_EN.equals(l)) return text;

        switch (l) {
            case LANG_HI:
                return mapHi(t);
            case LANG_TA:
                return mapTa(t);
            case LANG_TE:
                return mapTe(t);
            case LANG_KN:
                return mapKn(t);
            case LANG_ML:
                return mapMl(t);
            default:
                return text;
        }
    }

    /**
     * ✅ Your project calls this from PatientHomeFragment/PatientRiskFragment.
     * Keep it for compatibility. No translation now; just returns input.
     */
    public static String translateWithContext(Context ctx, String text) {
        return text == null ? "" : text;
    }

    /**
     * Compatibility method: no-op now, kept so call sites compile.
     */
    public static void apply(View root, Context ctx) {
        // no-op intentionally
    }

    // -------------------------
    // Helpers
    // -------------------------

    private static String sanitizeLang(String lang) {
        if (TextUtils.isEmpty(lang)) return LANG_EN;
        String l = lang.trim().toLowerCase().replace('_', '-');

        // Exact match
        for (String s : SUPPORTED) {
            if (s.equals(l)) return l;
        }

        // Match "hi-IN", "te-IN", etc.
        int dash = l.indexOf('-');
        if (dash > 0) {
            String base = l.substring(0, dash);
            for (String s : SUPPORTED) {
                if (s.equals(base)) return base;
            }
        }

        return LANG_EN;
    }

    private static String mapHi(String t) {
        switch (t) {
            case "Book an appointment": return "अपॉइंटमेंट बुक करें";
            case "View my appointment": return "मेरी अपॉइंटमेंट देखें";
            case "Find a specialist": return "विशेषज्ञ खोजें";
            case "Show my prescriptions": return "मेरी प्रिस्क्रिप्शन्स दिखाएं";
            case "Emergency help": return "आपातकालीन मदद";
            case "Cancel booking": return "बुकिंग रद्द करें";
            case "View in detail": return "विस्तार से देखें";
            case "Go to bookings": return "बुकिंग्स पर जाएं";
            case "Booking code": return "बुकिंग कोड";
            case "Consult link available": return "कंसल्ट लिंक उपलब्ध है";
            case "Consult link not available yet": return "कंसल्ट लिंक अभी उपलब्ध नहीं है";
            case "View more availability": return "और समय देखें";
            case "No more availability.": return "और उपलब्ध समय नहीं है।";
            case "Showing other available doctors.": return "अन्य उपलब्ध डॉक्टर दिखा रहा हूँ।";
            case "No other doctors are available right now.": return "अभी कोई अन्य डॉक्टर उपलब्ध नहीं हैं।";
            case "Please tell me your symptoms so I can suggest the right doctors.": return "कृपया अपने लक्षण बताएं, ताकि मैं सही डॉक्टर सुझा सकूं।";
            case "Okay, cancelled. Let me know if you want to book later.": return "ठीक है, रद्द कर दिया। अगर बाद में बुक करना हो तो बताइए।";
            case "I could not find any prescriptions yet.": return "मुझे अभी कोई प्रिस्क्रिप्शन नहीं मिला।";
            case "If you want, I can open full records.": return "यदि आप चाहें, मैं पूरे रिकॉर्ड खोल सकता हूँ।";
            case "Your upcoming appointment": return "आपकी आगामी अपॉइंटमेंट";
            case "Please reach the clinic 10–15 minutes early.": return "कृपया क्लिनिक में 10–15 मिनट पहले पहुंचें।";
            case "Please join 5 minutes before the start time.": return "कृपया शुरू होने से 5 मिनट पहले जुड़ें।";
            case "Opening appointment details now.": return "अपॉइंटमेंट विवरण खोल रहा हूँ।";
            case "Not medical advice. Contact doctor for HIGH risk.": return "यह चिकित्सीय सलाह नहीं है। उच्च जोखिम में डॉक्टर से संपर्क करें।";
            case "Analyzing...": return "विश्लेषण हो रहा है...";
            case "Clear chat": return "चैट साफ़ करें";
            case "Ask about risk or precautions": return "जोखिम या सावधानियों के बारे में पूछें";
            case "Explain my risk": return "मेरा जोखिम समझाएँ";
            case "Explain my latest labs": return "मेरी नवीनतम लैब रिपोर्ट समझाएँ";
            case "What should I do today?": return "मुझे आज क्या करना चाहिए?";
            case "When to contact doctor?": return "डॉक्टर से कब संपर्क करें?";
            case "Okay. I will help you book an appointment.": return "ठीक है। मैं अपॉइंटमेंट बुक करने में मदद करूंगा।";
            case "Recommended doctor: ": return "सुझाए गए डॉक्टर: ";
            case "Consultation fee: ₹": return "परामर्श शुल्क: ₹";
            case "Opening booking for ": return "बुकिंग शुरू की जा रही है: ";
            case "Choose a slot and proceed to payment.": return "समय चुनें और भुगतान करें।";
            case "I’ve prefilled your slot. Please confirm and pay to book.": return "मैंने आपका स्लॉट भर दिया है। कृपया पुष्टि करें और भुगतान करें।";
            case "Stop": return "रोकें";
            case "Send": return "भेजें";
            case "Listening...": return "सुन रहा है...";
            case "Ask Assistant": return "सहायक से पूछें";
            default: return t;
        }
    }

    private static String mapTa(String t) {
        switch (t) {
            case "Book an appointment": return "அப்பாயிண்ட்மெண்ட் பதிவு";
            case "View my appointment": return "என் அப்பாயிண்ட்மெண்டை பார்க்கவும்";
            case "Find a specialist": return "நிபுணரைத் தேடு";
            case "Show my prescriptions": return "என் மருந்துக் குறிப்புகளை காண்பி";
            case "Emergency help": return "அவசர உதவி";
            case "Not medical advice. Contact doctor for HIGH risk.": return "இது மருத்துவ ஆலோசனை அல்ல. அதிக அபாயத்தில் மருத்துவரை தொடர்புகொள்ளுங்கள்.";
            case "Analyzing...": return "ஆய்வு செய்கிறது...";
            case "Clear chat": return "சோட்டை அழி";
            case "Ask about risk or precautions": return "ஆபத்து அல்லது முன்னெச்சரிக்கைகள் பற்றி கேளுங்கள்";
            case "Explain my risk": return "என் ஆபத்தை விளக்கவும்";
            case "Explain my latest labs": return "என் சமீபத்திய லேப்களை விளக்கவும்";
            case "What should I do today?": return "இன்று நான் என்ன செய்ய வேண்டும்?";
            case "When to contact doctor?": return "எப்போது மருத்துவரை தொடர்புகொள்ள வேண்டும்?";
            case "Okay. I will help you book an appointment.": return "சரி. நான் அபாயிண்ட்மெண்ட் பதிவு செய்ய உதவுகிறேன்.";
            case "Recommended doctor: ": return "பரிந்துரைக்கப்பட்ட மருத்துவர்: ";
            case "Consultation fee: ₹": return "ஆலோசனை கட்டணம்: ₹";
            case "Opening booking for ": return "பதிவை தொடங்குகிறது: ";
            case "Choose a slot and proceed to payment.": return "ஒரு நேரத்தை தேர்வு செய்து பணம் செலுத்தவும்.";
            case "I’ve prefilled your slot. Please confirm and pay to book.": return "உங்கள் ஸ்லாட் நிரப்பப்பட்டுள்ளது. உறுதிப்படுத்தி பணம் செலுத்தவும்.";
            case "Your upcoming appointment": return "உங்கள் வரவிருக்கும் அப்பாயிண்ட்மெண்ட்";
            case "Please reach the clinic 10–15 minutes early.": return "கிளினிக்கிற்கு 10–15 நிமிடங்கள் முன்பே செல்லுங்கள்.";
            case "Please join 5 minutes before the start time.": return "தொடங்கும் நேரத்திற்கு 5 நிமிடங்கள் முன்பே சேருங்கள்.";
            case "Opening appointment details now.": return "அப்பாயிண்ட்மெண்ட் விவரங்களை திறக்கிறேன்.";
            case "Stop": return "நிறுத்து";
            case "Send": return "அனுப்பு";
            case "Listening...": return "கேட்கிறது...";
            case "Ask Assistant": return "உதவியாளரிடம் கேளுங்கள்";
            default: return t;
        }
    }

    private static String mapTe(String t) {
        switch (t) {
            case "Book an appointment": return "అపాయింట్‌మెంట్ బుక్ చేయండి";
            case "View my appointment": return "నా అపాయింట్‌మెంట్ చూడండి";
            case "Find a specialist": return "నిపుణుడిని కనుగొనండి";
            case "Show my prescriptions": return "నా ప్రిస్క్రిప్షన్లను చూపించండి";
            case "Emergency help": return "అత్యవసర సహాయం";
            case "Not medical advice. Contact doctor for HIGH risk.": return "ఇది వైద్య సలహా కాదు. అధిక ప్రమాదంలో డాక్టర్‌ను సంప్రదించండి.";
            case "Analyzing...": return "విశ్లేషిస్తోంది...";
            case "Clear chat": return "చాట్ క్లియర్ చేయండి";
            case "Ask about risk or precautions": return "ప్రమాదం లేదా జాగ్రత్తల గురించి అడగండి";
            case "Explain my risk": return "నా ప్రమాదాన్ని వివరించండి";
            case "Explain my latest labs": return "నా తాజా ల్యాబ్‌లను వివరించండి";
            case "What should I do today?": return "నేను ఈ రోజు ఏమి చేయాలి?";
            case "When to contact doctor?": return "డాక్టర్‌ను ఎప్పుడు సంప్రదించాలి?";
            case "Okay. I will help you book an appointment.": return "సరే. అపాయింట్‌మెంట్ బుక్ చేయడంలో సహాయం చేస్తాను.";
            case "Recommended doctor: ": return "సిఫారసు చేసిన డాక్టర్: ";
            case "Consultation fee: ₹": return "కన్సల్టేషన్ ఫీ: ₹";
            case "Opening booking for ": return "బుకింగ్ ప్రారంభిస్తోంది: ";
            case "Choose a slot and proceed to payment.": return "స్లాట్ ఎంచుకుని చెల్లించండి.";
            case "I’ve prefilled your slot. Please confirm and pay to book.": return "మీ స్లాట్ ముందే నింపాను. దయచేసి ధృవీకరించి చెల్లించండి.";
            case "Your upcoming appointment": return "మీ రాబోయే అపాయింట్‌మెంట్";
            case "Please reach the clinic 10–15 minutes early.": return "దయచేసి క్లినిక్‌కు 10–15 నిమిషాలు ముందే రండి.";
            case "Please join 5 minutes before the start time.": return "ప్రారంభ సమయానికి 5 నిమిషాల ముందే జాయిన్ అవ్వండి.";
            case "Opening appointment details now.": return "అపాయింట్‌మెంట్ వివరాలను తెరవుతున్నాను.";
            case "Stop": return "ఆపు";
            case "Send": return "పంపు";
            case "Listening...": return "వింటోంది...";
            case "Ask Assistant": return "అసిస్టెంట్‌ను అడగండి";
            default: return t;
        }
    }

    private static String mapKn(String t) {
        switch (t) {
            case "Book an appointment": return "ಅಪಾಯಿಂಟ್‌ಮೆಂಟ್ ಬುಕ್ ಮಾಡಿ";
            case "View my appointment": return "ನನ್ನ ಅಪಾಯಿಂಟ್‌ಮೆಂಟ್ ನೋಡಿ";
            case "Find a specialist": return "ತಜ್ಞರನ್ನು ಹುಡುಕಿ";
            case "Show my prescriptions": return "ನನ್ನ ಪ್ರಿಸ್ಕ್ರಿಪ್ಶನ್‌ಗಳನ್ನು ತೋರಿಸಿ";
            case "Emergency help": return "ತುರ್ತು ಸಹಾಯ";
            case "Not medical advice. Contact doctor for HIGH risk.": return "ಇದು ವೈದ್ಯಕೀಯ ಸಲಹೆ ಅಲ್ಲ. ಹೆಚ್ಚಿನ ಅಪಾಯದಲ್ಲಿ ವೈದ್ಯರನ್ನು ಸಂಪರ್ಕಿಸಿ.";
            case "Analyzing...": return "ವಿಶ್ಲೇಷಿಸುತ್ತಿದೆ...";
            case "Clear chat": return "ಚಾಟ್ ಕ್ಲಿಯರ್ ಮಾಡಿ";
            case "Ask about risk or precautions": return "ಅಪಾಯ ಅಥವಾ ಮುನ್ನೆಚ್ಚರಿಕೆ ಬಗ್ಗೆ ಕೇಳಿ";
            case "Explain my risk": return "ನನ್ನ ಅಪಾಯವನ್ನು ವಿವರಿಸಿ";
            case "Explain my latest labs": return "ನನ್ನ ಇತ್ತೀಚಿನ ಲ್ಯಾಬ್‌ಗಳನ್ನು ವಿವರಿಸಿ";
            case "What should I do today?": return "ನಾನು ಇಂದು ಏನು ಮಾಡಬೇಕು?";
            case "When to contact doctor?": return "ಡಾಕ್ಟರ್ ಅನ್ನು ಯಾವಾಗ ಸಂಪರ್ಕಿಸಬೇಕು?";
            case "Okay. I will help you book an appointment.": return "ಸರಿ. ಅಪಾಯಿಂಟ್‌ಮೆಂಟ್ ಬುಕ್ ಮಾಡಲು ಸಹಾಯ ಮಾಡುತ್ತೇನೆ.";
            case "Recommended doctor: ": return "ಶಿಫಾರಸು ಮಾಡಿದ ಡಾಕ್ಟರ್: ";
            case "Consultation fee: ₹": return "ಸಲಹೆ ಶುಲ್ಕ: ₹";
            case "Opening booking for ": return "ಬುಕಿಂಗ್ ಆರಂಭಿಸುತ್ತಿದೆ: ";
            case "Choose a slot and proceed to payment.": return "ಸ್ಲಾಟ್ ಆಯ್ಕೆ ಮಾಡಿ ಪಾವತಿಗೆ ಮುಂದುವರಿಯಿರಿ.";
            case "I’ve prefilled your slot. Please confirm and pay to book.": return "ನಿಮ್ಮ ಸ್ಲಾಟ್ ಭರಿಸಲಾಗಿದೆ. ದಯವಿಟ್ಟು ದೃಢೀಕರಿಸಿ ಮತ್ತು ಪಾವತಿಸಿ.";
            case "Your upcoming appointment": return "ನಿಮ್ಮ ಮುಂದಿನ ಅಪಾಯಿಂಟ್‌ಮೆಂಟ್";
            case "Please reach the clinic 10–15 minutes early.": return "ದಯವಿಟ್ಟು ಕ್ಲಿನಿಕ್‌ಗೆ 10–15 ನಿಮಿಷಗಳ ಮುಂಚಿತವಾಗಿ ಬನ್ನಿ.";
            case "Please join 5 minutes before the start time.": return "ಪ್ರಾರಂಭ ಸಮಯಕ್ಕಿಂತ 5 ನಿಮಿಷಗಳು ಮುಂಚಿತವಾಗಿ ಸೇರಿ.";
            case "Opening appointment details now.": return "ಅಪಾಯಿಂಟ್‌ಮೆಂಟ್ ವಿವರಗಳನ್ನು ತೆರೆಯುತ್ತಿದ್ದೇನೆ.";
            case "Stop": return "ನಿಲ್ಲಿಸಿ";
            case "Send": return "ಕಳುಹಿಸಿ";
            case "Listening...": return "ಕೇಳುತ್ತಿದೆ...";
            case "Ask Assistant": return "ಸಹಾಯಕನಿಗೆ ಕೇಳಿ";
            default: return t;
        }
    }

    private static String mapMl(String t) {
        switch (t) {
            case "Book an appointment": return "അപ്പോയിന്റ്മെന്റ് ബുക്ക് ചെയ്യുക";
            case "View my appointment": return "എന്റെ അപ്പോയിന്റ്മെന്റ് കാണുക";
            case "Find a specialist": return "വിദഗ്ധനെ കണ്ടെത്തുക";
            case "Show my prescriptions": return "എന്റെ പ്രിസ്ക്രിപ്ഷനുകൾ കാണിക്കുക";
            case "Emergency help": return "അത്യാവശ്യ സഹായം";
            case "Not medical advice. Contact doctor for HIGH risk.": return "ഇത് മെഡിക്കൽ ഉപദേശം അല്ല. ഉയർന്ന അപകടത്തിൽ ഡോക്ടറെ ബന്ധപ്പെടുക.";
            case "Analyzing...": return "വിശകലനം ചെയ്യുന്നു...";
            case "Clear chat": return "ചാറ്റ് ക്ലിയർ ചെയ്യുക";
            case "Ask about risk or precautions": return "അപായം അല്ലെങ്കിൽ മുൻകരുതൽ കുറിച്ച് ചോദിക്കുക";
            case "Explain my risk": return "എന്റെ അപകടം വിശദീകരിക്കുക";
            case "Explain my latest labs": return "എന്റെ പുതിയ ലാബുകൾ വിശദീകരിക്കുക";
            case "What should I do today?": return "ഞാൻ ഇന്ന് എന്ത് ചെയ്യണം?";
            case "When to contact doctor?": return "ഡോക്ടറെ എപ്പോൾ ബന്ധപ്പെടണം?";
            case "Okay. I will help you book an appointment.": return "ശരി. അപോയിന്റ്മെന്റ് ബുക്ക് ചെയ്യാൻ ഞാൻ സഹായിക്കും.";
            case "Recommended doctor: ": return "ശുപാർശ ചെയ്ത ഡോക്ടർ: ";
            case "Consultation fee: ₹": return "കൺസൾട്ടേഷൻ ഫീസ്: ₹";
            case "Opening booking for ": return "ബുക്കിംഗ് ആരംഭിക്കുന്നു: ";
            case "Choose a slot and proceed to payment.": return "സ്ലോട്ട് തിരഞ്ഞെടുക്കുകയും പേയ്‌മെന്റിലേക്ക് പോകുകയും ചെയ്യുക.";
            case "I’ve prefilled your slot. Please confirm and pay to book.": return "നിങ്ങളുടെ സ്ലോട്ട് മുൻകൂട്ടി നിറച്ചു. സ്ഥിരീകരിച്ച് പേയ്‌മെന്റ് ചെയ്യുക.";
            case "Your upcoming appointment": return "നിങ്ങളുടെ വരാനിരിക്കുന്ന അപോയിന്റ്മെന്റ്";
            case "Please reach the clinic 10–15 minutes early.": return "ക്ലിനിക്കിൽ 10–15 മിനിറ്റ് മുൻപായി എത്തുക.";
            case "Please join 5 minutes before the start time.": return "ആരംഭ സമയം മുതൽ 5 മിനിറ്റ് മുൻപായി ചേരുക.";
            case "Opening appointment details now.": return "അപോയിന്റ്മെന്റ് വിശദാംശങ്ങൾ തുറക്കുന്നു.";
            case "Stop": return "നിറുത്തുക";
            case "Send": return "അയയ്ക്കുക";
            case "Listening...": return "കേൾക്കുന്നു...";
            case "Ask Assistant": return "അസിസ്റ്റന്റിനോട് ചോദിക്കുക";
            default: return t;
        }
    }
}
