package com.simats.criticall

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object AssistantBarController {

    private const val TAG = "AssistantBarController"

    private const val CONTAINER_TAG = "assistant_overlay_container"
    private const val BAR_TAG = "assistant_bar_view"
    private const val EDGE_TAG = "assistant_edge_view"
    private const val OVERLAY_TAG = "assistant_overlay_view"
    private const val WINDOW_FAB_TAG = "assistant_window_fab_view"

    private const val EDGE_HOLD_MS = 1000L

    // Keep edge strip small (doesn't block UI) but exclusion a bit wider (beats back gesture)
    // Make edge area wider to beat OEM back-gesture reliably
    private const val EDGE_STRIP_DP = 72
    private const val EXCLUSION_DP = 88

    fun attach(activity: Activity) {
        if (!shouldShow(activity)) return

        val container = ensureOverlayContainer(activity)
        val existingBar = container.findViewWithTag<View>(BAR_TAG)
        if (existingBar != null) {
            ensureWindowFab(activity, container, existingBar)
            setBarVisible(container, existingBar, false, animate = false)
            return
        }

        val bar = activity.layoutInflater.inflate(R.layout.view_assistant_bar, container, false).apply {
            tag = BAR_TAG
            visibility = View.GONE
            alpha = 0f
        }

        val overlay = View(activity).apply {
            tag = OVERLAY_TAG
            setBackgroundColor(0x00000000)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener { setBarVisible(container, bar, false, animate = true) }
        }

        // ---- Layout params for bar (your original sizing logic kept)
        val baseBottom = dp(activity, 6)
        val screenW = activity.resources.displayMetrics.widthPixels
        val targetW = min(screenW - dp(activity, 32), dp(activity, 340))
        val lp = FrameLayout.LayoutParams(targetW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = baseBottom
        }
        bar.layoutParams = lp

        // Wire UI (safe even if some IDs differ; no crash)
        val btnMic = bar.findViewById<MaterialButton?>(R.id.btnMic)
        val btnOpen = bar.findViewById<View?>(R.id.assistantBar)
        val tvSub = bar.findViewById<TextView?>(R.id.tvGreeting)
        val tvTitle = bar.findViewById<TextView?>(R.id.tvAsk)
        val btnCamera = bar.findViewById<View?>(R.id.btnCamera)
        val btnExpand = bar.findViewById<View?>(R.id.btnExpand)

        btnOpen?.setOnClickListener { btnMic?.performClick() }
        btnCamera?.setOnClickListener { openAssistant(activity, startListening = false) }
        btnExpand?.setOnClickListener { openAssistant(activity, startListening = false) }

        AssistantUiBridge.addListener(object : AssistantUiBridge.Listener {
            override fun onAssistantSpeakingChanged(speaking: Boolean) {
                if (speaking) {
                    tvSub?.visibility = View.INVISIBLE
                    btnMic?.animate()?.scaleX(1.06f)?.scaleY(1.06f)?.setDuration(160)?.start()
                } else {
                    tvSub?.visibility = View.VISIBLE
                    btnMic?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(160)?.start()
                }
            }
        })

        // Insets-aware (nav bar + your bottom bar)
        ViewCompat.setOnApplyWindowInsetsListener(bar) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val lp2 = v.layoutParams as? FrameLayout.LayoutParams
            if (lp2 != null) {
                lp2.bottomMargin = baseBottom + nav + extraBottomOffset(activity)
                v.layoutParams = lp2
            }
            insets
        }

        // Add overlay then bar
        if (container.findViewWithTag<View>(OVERLAY_TAG) == null) {
            container.addView(
                overlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        container.addView(bar)

        // Always-visible floating mic (window-level so it never gets clipped/hidden)
        ensureWindowFab(activity, container, bar)

        // Ensure correct initial visibility state (and hide on home if needed)
        setBarVisible(container, bar, false, animate = false)

        // Edge reveal (topmost)
        setupLeftEdgeReveal(activity, container, bar)

        // Mini assistant voice
        setupMiniAssistant(activity, tvTitle, tvSub, btnMic)

        // Keep everything on top
        container.bringToFront()
    }

    fun detach(activity: Activity) {
        val root = getWindowRoot(activity)
        val container = root.findViewWithTag<View>(CONTAINER_TAG) as? ViewGroup
        if (container != null) {
            container.removeAllViews()
            root.removeView(container)
        }
        val fab = root.findViewWithTag<View>(WINDOW_FAB_TAG)
        if (fab != null) root.removeView(fab)
    }

    fun updateVisibility(activity: Activity) {
        val root = getWindowRoot(activity)
        val container = root.findViewWithTag<View>(CONTAINER_TAG) as? ViewGroup ?: return
        val bar = container.findViewWithTag<View>(BAR_TAG) ?: return
        setBarVisible(container, bar, false, animate = true)
    }

    /** Debug helper: call this once to confirm the bar can render on-device. */
    fun forceShow(activity: Activity) {
        val root = getWindowRoot(activity)
        val container = root.findViewWithTag<View>(CONTAINER_TAG) as? ViewGroup ?: return
        val bar = container.findViewWithTag<View>(BAR_TAG) ?: return
        setBarVisible(container, bar, true, animate = true)
    }

    // ---------------------- Core UI helpers ----------------------

    private fun ensureOverlayContainer(activity: Activity): FrameLayout {
        val root = getWindowRoot(activity)
        val existing = root.findViewWithTag<View>(CONTAINER_TAG)
        if (existing is FrameLayout) return existing

        val container = FrameLayout(activity).apply {
            tag = CONTAINER_TAG
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = false
            isFocusable = false
            // ensure drawn above content
            elevation = dp(activity, 8).toFloat()
            clipChildren = false
            clipToPadding = false
        }

        // Attach to window root so it always sits on top of app content
        root.addView(container)
        container.bringToFront()
        return container
    }

    private fun getWindowRoot(activity: Activity): ViewGroup =
        (activity.window.decorView as? ViewGroup)
            ?: (activity.findViewById<View>(android.R.id.content) as ViewGroup)

    private fun setBarVisible(container: ViewGroup, bar: View, visible: Boolean, animate: Boolean) {
        val overlay = container.findViewWithTag<View>(OVERLAY_TAG)
        val windowRoot = (bar.context as? Activity)?.let { getWindowRoot(it) }
        val fab = windowRoot?.findViewWithTag<View>(WINDOW_FAB_TAG)
        if (visible) {
            overlay?.visibility = View.VISIBLE
            bar.visibility = View.VISIBLE
            fab?.visibility = View.GONE
            overlay?.bringToFront()
            bar.bringToFront()

            if (animate) {
                bar.alpha = 0f
                bar.translationY = dp(bar.context, 18).toFloat()
                bar.animate().alpha(1f).translationY(0f).setDuration(220).start()
            } else {
                bar.alpha = 1f
                bar.translationY = 0f
            }
        } else {
            overlay?.visibility = View.GONE
            fab?.visibility = if (shouldShowFab(bar.context)) View.VISIBLE else View.GONE
            if (animate) {
                bar.animate()
                    .alpha(0f)
                    .translationY(dp(bar.context, 14).toFloat())
                    .setDuration(160)
                    .withEndAction { bar.visibility = View.GONE }
                    .start()
            } else {
                bar.alpha = 0f
                bar.translationY = dp(bar.context, 14).toFloat()
                bar.visibility = View.GONE
            }
        }
    }

    /**
     * This is the reliable part:
     * - Edge strip is added to decor overlay container (always topmost).
     * - systemGestureExclusionRects applied correctly for API 29+.
     * - Hold for 1s reveals bar, movement does NOT cancel (so “drag + hold” still works).
     */
    private fun setupLeftEdgeReveal(activity: Activity, container: FrameLayout, bar: View) {
        container.findViewWithTag<View>(EDGE_TAG)?.let { container.removeView(it) }

        val screenW = activity.resources.displayMetrics.widthPixels
        val edgeW = max(dp(activity, EDGE_STRIP_DP), (screenW * 0.30f).toInt())
        val exclusionW = max(dp(activity, EXCLUSION_DP), (screenW * 0.32f).toInt())

        val edge = View(activity).apply {
            tag = EDGE_TAG
            setBackgroundColor(0x00000000)
            isClickable = true
            isFocusable = true
        }

        edge.layoutParams = FrameLayout.LayoutParams(edgeW, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START
        }

        // Apply gesture exclusion after layout so height is known.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            edge.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    edge.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    try {
                        val r = Rect(0, 0, exclusionW, edge.height)
                        edge.systemGestureExclusionRects = listOf(r)
                        container.systemGestureExclusionRects = listOf(r)
                        // Extra safety: apply on decorView as well
                        (activity.window.decorView as? View)?.systemGestureExclusionRects = listOf(r)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Gesture exclusion failed: ${t.message}")
                    }
                }
            })
        }

        val handler = Handler(Looper.getMainLooper())
        var downTime = 0L
        var holding = false

        val reveal = Runnable {
            if (!holding) return@Runnable
            setBarVisible(container, bar, true, animate = true)
        }

        edge.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    holding = true
                    downTime = SystemClock.uptimeMillis()
                    handler.removeCallbacks(reveal)
                    handler.postDelayed(reveal, EDGE_HOLD_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Do NOT cancel on move. This is key: user can “drag” and still reveal.
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holding = false
                    handler.removeCallbacks(reveal)
                    true
                }
                else -> true
            }
        }

        container.addView(edge)
        edge.bringToFront()
        // Fallback: also listen on container for left-edge holds (some OEMs ignore child edge)
        container.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                if (ev.rawX <= edgeW) {
                    holding = true
                    downTime = SystemClock.uptimeMillis()
                    handler.removeCallbacks(reveal)
                    handler.postDelayed(reveal, EDGE_HOLD_MS)
                    return@setOnTouchListener true
                }
                return@setOnTouchListener false
            }
            if (ev.actionMasked == MotionEvent.ACTION_MOVE && ev.rawX <= edgeW) {
                return@setOnTouchListener true
            }
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                holding = false
                handler.removeCallbacks(reveal)
                if (ev.rawX <= edgeW) return@setOnTouchListener true
            }
            false
        }
        bar.bringToFront()
    }

    private fun ensureWindowFab(activity: Activity, container: FrameLayout, bar: View) {
        val root = getWindowRoot(activity)
        val existing = root.findViewWithTag<View>(WINDOW_FAB_TAG)
        if (existing != null) {
            existing.visibility = if (shouldShowFab(activity)) View.VISIBLE else View.GONE
            return
        }

        val fab = android.widget.ImageButton(activity).apply {
            tag = WINDOW_FAB_TAG
            setImageResource(R.drawable.ic_mic_24)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            background = activity.getDrawable(R.drawable.bg_assistant_circle_button)
            contentDescription = "Ask Assistant"
            scaleType = android.widget.ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener {
                // If bar is available, show it; otherwise open full assistant
                val c = root.findViewWithTag<View>(CONTAINER_TAG) as? ViewGroup
                val b = c?.findViewWithTag<View>(BAR_TAG)
                if (c != null && b != null) {
                    setBarVisible(c, b, true, animate = true)
                } else {
                    openAssistant(activity, startListening = true)
                }
            }
        }

        val size = dp(activity, 52)
        val baseBottomFab = dp(activity, 14)
        val fabLp = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            marginEnd = dp(activity, 16)
            bottomMargin = baseBottomFab
        }
        fab.layoutParams = fabLp

        ViewCompat.setOnApplyWindowInsetsListener(fab) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val lp2 = v.layoutParams as? FrameLayout.LayoutParams
            if (lp2 != null) {
                lp2.bottomMargin = baseBottomFab + nav + extraBottomOffset(activity)
                v.layoutParams = lp2
            }
            insets
        }

        // addContentView ensures it sits above content on all screens
        activity.addContentView(fab, fabLp)
        fab.bringToFront()
        // Initial visibility
        fab.visibility = if (shouldShowFab(activity)) View.VISIBLE else View.GONE
    }

    // ---------------------- Mini assistant (unchanged logic) ----------------------

    private fun setupMiniAssistant(
        activity: Activity,
        tvTitle: TextView?,
        tvSub: TextView?,
        btnMic: MaterialButton?
    ) {
        val appCtx = activity.applicationContext
        var speech: SpeechRecognizer? = null
        var listening = false

        fun updateText(title: String, sub: String) {
            tvTitle?.text = title
            tvSub?.text = sub
        }

        fun stopListeningUi() {
            listening = false
            AssistantUiBridge.setSpeaking(false)
            updateText("Ask Assistant", "Tap mic and speak")
        }

        fun ensureRecognizer(): SpeechRecognizer? {
            if (speech != null) return speech
            if (!SpeechRecognizer.isRecognitionAvailable(appCtx)) return null
            speech = SpeechRecognizer.createSpeechRecognizer(appCtx)
            return speech
        }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = updateText("Listening…", "Speak now")
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) = stopListeningUi()

            override fun onResults(results: Bundle) {
                val list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull().orEmpty()
                if (text.isBlank()) {
                    stopListeningUi()
                    return
                }
                updateText("You said", text)
                sendQuickQuery(activity, text, tvTitle, tvSub)
            }

            override fun onPartialResults(partialResults: Bundle) {
                val list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull().orEmpty()
                if (text.isNotBlank()) updateText("Listening…", text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        btnMic?.setOnClickListener {
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), 7001)
                return@setOnClickListener
            }

            val sr = ensureRecognizer() ?: return@setOnClickListener

            if (listening) {
                try { sr.stopListening() } catch (_: Throwable) {}
                stopListeningUi()
                return@setOnClickListener
            }

            listening = true
            AssistantUiBridge.setSpeaking(true)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                val lang = TranslationManager.currentLang(activity)
                val locale = when (lang) {
                    "hi" -> "hi-IN"
                    "ta" -> "ta-IN"
                    "te" -> "te-IN"
                    "kn" -> "kn-IN"
                    "ml" -> "ml-IN"
                    else -> "en-IN"
                }
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            }

            sr.setRecognitionListener(listener)
            try { sr.startListening(intent) } catch (_: Throwable) { stopListeningUi() }
        }
    }

    private fun sendQuickQuery(activity: Activity, text: String, tvTitle: TextView?, tvSub: TextView?) {
        val lab = LabClient(activity)
        val lang = TranslationManager.currentLang(activity)
        val system = buildQuickSystemPrompt(lang)
        val payload = "Q=$text"

        tvTitle?.text = "Thinking…"
        tvSub?.text = "Generating reply"
        AssistantUiBridge.setSpeaking(true)

        lab.sendText(system, payload, 140, object : LabClient.Listener {
            override fun onSuccess(replyText: String) {
                activity.runOnUiThread {
                    AssistantUiBridge.setSpeaking(false)
                    tvTitle?.text = "Assistant"
                    tvSub?.text = replyText.replace("\n", " ").trim()
                }
            }

            override fun onError(message: String) {
                activity.runOnUiThread {
                    AssistantUiBridge.setSpeaking(false)
                    tvTitle?.text = "Assistant"
                    tvSub?.text = "Sorry, I couldn’t connect."
                }
            }
        })
    }

    private fun buildQuickSystemPrompt(lang: String): String {
        val langLine = when (lang) {
            "hi" -> "Respond ONLY in Hindi."
            "ta" -> "Respond ONLY in Tamil."
            "te" -> "Respond ONLY in Telugu."
            "kn" -> "Respond ONLY in Kannada."
            "ml" -> "Respond ONLY in Malayalam."
            else -> "Respond in English."
        }
        return "You are a health assistant. Be concise (1-2 lines). No diagnosis. " +
                "If urgent symptoms, suggest contacting a doctor. " + langLine
    }

    private fun extraBottomOffset(activity: Activity): Int {
        val bottomBar = activity.findViewById<View>(R.id.bottomBar) ?: return 0
        val h = bottomBar.height
        return if (h > 0) max(0, h - dp(activity, 12)) else dp(activity, 64)
    }

    private fun shouldShow(activity: Activity): Boolean {
        val role = Role.fromId(AppPrefs.getRole(activity))
        if (role != Role.PATIENT) {
            // Fallback: allow patient screens even if role isn't stored correctly
            val fullName = activity::class.java.name
            val isPatientScreen = fullName.contains(".roles.patient.") || fullName.contains("Patient")
            if (!isPatientScreen) return false
        }
        val name = activity::class.java.simpleName
        val blocked = setOf(
            "SplashActivity",
            "RoleSelectActivity",
            "LanguageSelectActivity",
            "LanguageChangeActivity",
            "LoginActivity",
            "CreateAccountActivity",
            "OnboardingActivity",
            "VerifyOtpActivity"
        )
        return !blocked.contains(name)
    }

    private fun shouldShowFab(ctx: android.content.Context): Boolean {
        val activity = ctx as? Activity ?: return true
        // Hide floating button on PatientHomeFragment (home already has assistant card)
        if (activity is androidx.fragment.app.FragmentActivity) {
            val frags = activity.supportFragmentManager.fragments
            val homeVisible = frags.any { it != null && it.isVisible && it.javaClass.simpleName == "PatientHomeFragment" }
            if (homeVisible) return false
        }
        return true
    }

    private fun openAssistant(activity: Activity, startListening: Boolean) {
        if (activity is androidx.fragment.app.FragmentActivity) {
            val fm = activity.supportFragmentManager
            PatientOfflineChatBottomSheet.show(
                fm,
                AppPrefs.getLastUid(activity).takeIf { it > 0 }?.toString().orEmpty(),
                "",
                "General",
                "LOW",
                arrayListOf(),
                hashMapOf(),
                "",
                startListening
            )
        } else {
            activity.startActivity(Intent(activity, MainActivity::class.java))
        }
    }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (ctx.resources.displayMetrics.density * v + 0.5f).toInt()
}
