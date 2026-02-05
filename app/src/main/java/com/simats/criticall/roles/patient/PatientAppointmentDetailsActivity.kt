package com.simats.criticall.roles.patient

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.simats.criticall.ApiConfig.JITSI_BASE_URL
import com.simats.criticall.BaseActivity
import com.simats.criticall.ExternalCallLauncher
import com.simats.criticall.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.max

class PatientAppointmentDetailsActivity : BaseActivity() {

    private val tz by lazy { TimeZone.getTimeZone("Asia/Kolkata") }
    private val dfDate by lazy {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply { timeZone = tz }
    }
    private val dfTime12 by lazy {
        SimpleDateFormat("h:mm a", Locale.getDefault()).apply { timeZone = tz }
    }
    private val dfDateShort by lazy {
        SimpleDateFormat("MMM d", Locale.getDefault()).apply { timeZone = tz }
    }
    private var consultType: String = ""   // AUDIO / VIDEO / PHYSICAL
    private var doctorPhone: String = ""
    private var room: String = ""
    private var jitsiServer: String = JITSI_BASE_URL; //  default LAN (can be overridden by API)
    private var externalVideoLink: String = ""
    private var externalAudioLink: String = ""

    private var apptKey: String = ""

    private var scheduledAtMs: Long = 0L
    private var durationMin: Int = 30

    private var lastServerNowMs: Long = 0L
    private var lastServerNowClientMs: Long = 0L

    private var tickJob: Job? = null
    private var refreshJob: Job? = null

    private val START_EARLY_SECONDS = 120L
    private var lastForceRefreshAtMs: Long = 0L

    //  appointment status from API/DB
    private var apptStatus: String = "" // IN_PROGRESS / COMPLETED / BOOKED / CANCELLED / NO_SHOW ...

    //  Anti double-tap guard (prevents glitches / opening twice)
    private var lastStartTapMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patient_appointment_details)
        supportActionBar?.hide()

        findViewById<View>(R.id.ivBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        apptKey = resolveApptKey(intent)
        if (apptKey.isBlank()) {
            Toast.makeText(this, getString(R.string.failed_to_load_appointment_details), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val btnStart = findViewById<View>(R.id.btnStartNow)
        btnStart.isEnabled = false
        btnStart.alpha = 0.55f

        btnStart.setOnClickListener {
            //  prevent double click / double activity launch
            val nowTap = System.currentTimeMillis()
            if (nowTap - lastStartTapMs < 1200L) return@setOnClickListener
            lastStartTapMs = nowTap

            val st = normalizeStatus(apptStatus)

            if (st == "COMPLETED") {
                Toast.makeText(this, getString(R.string.consultation_finished), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (st == "CANCELLED" || st == "NO_SHOW") {
                Toast.makeText(this, getString(R.string.consultation_unavailable), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (consultType.equals("VIDEO", true)) {
                if (room.isBlank()) {
                    val code = findViewById<TextView>(R.id.tvApptId).text?.toString().orEmpty().trim()
                    if (code.isNotBlank()) room = roomFromPublicCode(code)
                }
                if (room.isBlank()) {
                    Toast.makeText(this, getString(R.string.failed), Toast.LENGTH_SHORT).show()
                    forceRefreshOnce("video_room_missing")
                    return@setOnClickListener
                }
                startVideo()
            } else if (consultType.equals("AUDIO", true)) {
                if (doctorPhone.isBlank()) {
                    Toast.makeText(this, getString(R.string.doctor_phone_missing), Toast.LENGTH_SHORT).show()
                    forceRefreshOnce("doctor_phone_missing")
                    return@setOnClickListener
                }
                //  NEW: open summary + dialer
                startAudioWithSummary()
            } else {
                // PHYSICAL: open summary (no call)
                startActivity(buildSummaryIntent("PHYSICAL"))
                Toast.makeText(this, getString(R.string.physical_visit), Toast.LENGTH_SHORT).show()
            }
        }

        load(apptKey)
    }

    override fun onStart() {
        super.onStart()
        startTicker()
        startRefresher()
    }

    override fun onStop() {
        stopTicker()
        stopRefresher()
        super.onStop()
    }

    private fun resolveApptKey(itn: Intent): String {
        // robust: check strings first
        val s = listOf(
            itn.getStringExtra("appointment_id"),
            itn.getStringExtra("appointmentId"),
            itn.getStringExtra("id"),
            itn.getStringExtra("public_code"),
            itn.getStringExtra("publicCode"),
            itn.getStringExtra("appointmentKey")
        ).firstOrNull { !it.isNullOrBlank() }?.trim()
        if (!s.isNullOrBlank()) return s

        // robust: sometimes passed as long/int
        val any = listOf("appointment_id", "appointmentId", "id").mapNotNull { k ->
            itn.extras?.get(k)
        }.firstOrNull()

        return when (any) {
            is Long -> if (any > 0L) any.toString() else ""
            is Int -> if (any > 0) any.toString() else ""
            else -> ""
        }
    }

    private fun load(apptKey: String) {
        lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) {
                PatientApi.getAppointmentDetail(this@PatientAppointmentDetailsActivity, apptKey)
            }
            if (d == null) {
                Toast.makeText(
                    this@PatientAppointmentDetailsActivity,
                    PatientApi.lastError ?: getString(R.string.failed_to_load_appointment_details),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            bind(d)
            updateCountdownAndButton()
        }
    }

    private fun bind(d: JSONObject) {
        // ---- Public code (single source of truth for room) ----
        val publicCode = pickStr(d, "public_code", "publicCode", "appointmentId", "appointment_id", "id").trim()
        set(R.id.tvApptId, publicCode)

        set(R.id.tvDocName, pickStr(d, "doctorName", "doctor_name", "doctor", "doc_name").ifBlank { "Doctor" })
        set(R.id.tvDocSpec, pickStr(d, "specialization", "speciality", "specialty", "doctor_speciality"))
        set(R.id.tvPlace, pickStr(d, "worksAt", "works_at", "place", "clinic_name"))
        set(R.id.tvDateTime, pickStr(d, "dateLabel", "date_label", "when", "scheduled_label"))

        durationMin = pickInt(d, 30, "durationMinutes", "durationMin", "duration_min", "duration").coerceAtLeast(5)

        val timeLabel = pickStr(d, "timeLabel", "time_label", "time", "slot_label")
        set(R.id.tvTimeMeta, if (timeLabel.isNotBlank()) "$timeLabel   ($durationMin minutes)" else "")

        consultType = pickStr(d, "consultType", "consult_type", "type", "mode")
            .uppercase(Locale.US)
            .let {
                when {
                    it.contains("VIDEO") -> "VIDEO"
                    it.contains("AUDIO") -> "AUDIO"
                    it.contains("PHYSICAL") || it.contains("IN_PERSON") || it.contains("INPERSON") || it.contains("CLINIC") -> "PHYSICAL"
                    else -> it
                }
            }

        set(
            R.id.tvType,
            when (consultType) {
                "VIDEO" -> getString(R.string.video_call)
                "PHYSICAL" -> getString(R.string.physical_visit)
                else -> getString(R.string.audio_call)
            }
        )

        val fee = pickInt(d, 0, "fee", "fee_amount", "amount")
        set(R.id.tvFee, "₹$fee")

        doctorPhone = pickStr(d, "doctorPhone", "doctor_phone", "doctor_phone_no", "phone", "mobile").trim()

        //  STATUS (for banner)
        apptStatus = pickStr(d, "status", "appointment_status", "appt_status", "state").trim()

        //  Jitsi server
        val apiServer = pickStr(d, "jitsi_server", "jitsiServer", "meet_server", "server").trim()
        jitsiServer = normalizeJitsiServer(if (apiServer.isNotBlank()) apiServer else jitsiServer)

        //  Room is derived only from public_code (same for doctor+patient)
        room = if (publicCode.isNotBlank()) roomFromPublicCode(publicCode) else ""

        //  Optional external links (Zoom / WhatsApp / custom)
        externalVideoLink = pickStr(
            d,
            "video_link",
            "meet_link",
            "zoom_link",
            "meeting_link",
            "call_link",
            "link"
        )
        externalAudioLink = pickStr(
            d,
            "audio_link",
            "call_link",
            "phone_link",
            "whatsapp_link"
        )

        captureServerNowIfPresent(d)
        scheduledAtMs = parseScheduledAtMs(d)

        android.util.Log.d("JITSI", "PATIENT public=$publicCode room=$room server=$jitsiServer status=$apptStatus")
    }

    // ---------------- Banner + Status ----------------

    private fun normalizeStatus(raw: String): String {
        val s = raw.trim().uppercase(Locale.US)
        if (s.isBlank() || s == "NULL") return ""
        return when {
            s == "IN_PROGRESS" || s == "INPROGRESS" || s == "ONGOING" -> "IN_PROGRESS"
            s == "COMPLETED" || s == "COMPLETE" || s == "DONE" -> "COMPLETED"
            s == "CANCELLED" || s == "CANCELED" || s == "CANCEL" -> "CANCELLED"
            s == "NO_SHOW" || s == "NOSHOW" || s == "MISSED" -> "NO_SHOW"
            else -> s
        }
    }

    private fun setBanner(title: String, sub: String, right: String) {
        findViewById<TextView>(R.id.tvStartSoonTitle)?.text = title
        findViewById<TextView>(R.id.tvStartSoonSub)?.text = sub
        findViewById<TextView>(R.id.tvStartSoonRight)?.text = right
    }

    private fun updateCountdownAndButton() {
        val btnStart = findViewById<View>(R.id.btnStartNow)
        val st = normalizeStatus(apptStatus)

        //  If DB says IN_PROGRESS → show Ongoing and enable start
        if (st == "IN_PROGRESS") {
            setBanner(
                getString(R.string.ongoing),
                getString(R.string.consultation_is_live),
                getString(R.string.ongoing)
            )
            btnStart.isEnabled = true
            btnStart.alpha = 1f
            return
        }

        //  If completed → show Finished and disable
        if (st == "COMPLETED") {
            setBanner(
                getString(R.string.finished),
                getString(R.string.consultation_finished),
                getString(R.string.finished)
            )
            btnStart.isEnabled = false
            btnStart.alpha = 0.55f
            return
        }

        if (st == "CANCELLED") {
            setBanner(
                getString(R.string.cancelled),
                getString(R.string.appointment_was_cancelled),
                getString(R.string.cancelled)
            )
            btnStart.isEnabled = false
            btnStart.alpha = 0.55f
            return
        }

        if (st == "NO_SHOW") {
            setBanner(
                getString(R.string.missed),
                getString(R.string.appointment_was_missed),
                getString(R.string.missed)
            )
            btnStart.isEnabled = false
            btnStart.alpha = 0.55f
            return
        }

        //  Default: Upcoming countdown
        if (scheduledAtMs <= 0L) {
            setBanner(
                getString(R.string.starting_soon),
                getString(R.string.consultation_begins_in),
                getString(R.string.starting_soon)
            )
            btnStart.isEnabled = false
            btnStart.alpha = 0.55f
            return
        }

        val now = nowMsReliable()
        val secLeft = ((scheduledAtMs - now) / 1000L)

        val rightText = friendlyStartLabel(scheduledAtMs)

        setBanner(
            getString(R.string.starting_soon),
            getString(R.string.consultation_begins_in),
            rightText
        )

        val durSec = (durationMin.coerceAtLeast(5) * 60L)
        val inTimeWindow = secLeft <= START_EARLY_SECONDS && secLeft >= -durSec

        btnStart.isEnabled = inTimeWindow
        btnStart.alpha = if (inTimeWindow) 1f else 0.55f
    }

    // ---------------- Time helpers ----------------

    private fun nowMsReliable(): Long {
        if (lastServerNowMs > 0L && lastServerNowClientMs > 0L) {
            val delta = System.currentTimeMillis() - lastServerNowClientMs
            return lastServerNowMs + max(0L, delta)
        }
        return System.currentTimeMillis()
    }

    private fun friendlyStartLabel(targetMs: Long): String {
        if (targetMs <= 0L) return getString(R.string.starting_soon)
        val now = nowMsReliable()
        if (targetMs <= now) return getString(R.string.starting_now)

        val today = dfDate.format(Date(now))
        val tomorrow = dfDate.format(Date(now + 24 * 60 * 60 * 1000L))
        val date = dfDate.format(Date(targetMs))
        val time = dfTime12.format(Date(targetMs))

        return when {
            date == today -> "${getString(R.string.today)}, $time"
            date == tomorrow -> "${getString(R.string.tomorrow)}, $time"
            else -> "${dfDateShort.format(Date(targetMs))}, $time"
        }
    }

    private fun captureServerNowIfPresent(d: JSONObject) {
        val serverNowMs =
            d.optLong("server_now_ms", 0L).takeIf { it > 0 }
                ?: d.optLong("serverNowMs", 0L).takeIf { it > 0 }
                ?: 0L

        if (serverNowMs > 0) {
            lastServerNowMs = serverNowMs
            lastServerNowClientMs = System.currentTimeMillis()
            return
        }

        val serverNowStr = pickStr(d, "server_now", "serverNow", "now", "server_time")
        if (serverNowStr.isNotBlank()) {
            val parsed = parseDateTimeToMillis(serverNowStr)
            if (parsed > 0L) {
                lastServerNowMs = parsed
                lastServerNowClientMs = System.currentTimeMillis()
            }
        }
    }

    private fun parseScheduledAtMs(d: JSONObject): Long {
        val candidates = listOf(
            pickStr(d, "scheduled_at", "scheduledAt", "scheduled_at_str", "scheduledAtStr"),
            pickStr(d, "scheduled", "scheduled_time", "start_at", "startAt")
        ).filter { it.isNotBlank() }

        for (c in candidates) {
            val ms = parseDateTimeToMillis(c)
            if (ms > 0L) return ms
        }

        val date = pickStr(d, "date", "appointment_date")
        val time = pickStr(d, "time", "appointment_time")
        if (date.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$")) && time.matches(Regex("^\\d{2}:\\d{2}(:\\d{2})?$"))) {
            val hhmm = time.substring(0, 5)
            val ms = parseDateTimeToMillis("$date $hhmm:00")
            if (ms > 0L) return ms
        }
        return 0L
    }

    private fun parseDateTimeToMillis(s: String): Long {
        val t = s.trim()
        if (t.isBlank()) return 0L

        val fmts = arrayOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )

        for (f in fmts) {
            try {
                val sdf = SimpleDateFormat(f, Locale.US)
                sdf.timeZone = if (t.endsWith("Z")) TimeZone.getTimeZone("UTC") else TimeZone.getTimeZone("Asia/Kolkata")
                val dt = sdf.parse(t) ?: continue
                return dt.time
            } catch (_: ParseException) {
            } catch (_: Throwable) {
            }
        }
        return 0L
    }

    // ---------------- Ticker / Refresher ----------------

    private fun startTicker() {
        if (tickJob?.isActive == true) return
        tickJob = lifecycleScope.launch {
            while (isActive) {
                updateCountdownAndButton()
                delay(1000L)
            }
        }
    }

    private fun stopTicker() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun startRefresher() {
        if (refreshJob?.isActive == true) return
        refreshJob = lifecycleScope.launch {
            delay(500L)
            while (isActive) {
                load(apptKey)
                delay(20_000L)
            }
        }
    }

    private fun stopRefresher() {
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun forceRefreshOnce(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastForceRefreshAtMs < 5000L) return
        lastForceRefreshAtMs = now
        load(apptKey)
    }

    // ---------------- Call actions ----------------

    private fun startVideo() {
        val code = findViewById<TextView>(R.id.tvApptId).text?.toString().orEmpty().trim()

        if (room.isBlank() && code.isNotBlank()) {
            room = roomFromPublicCode(code)
        }

        if (room.isBlank()) {
            Toast.makeText(this, getString(R.string.failed), Toast.LENGTH_SHORT).show()
            return
        }

        val callIntent = ExternalCallLauncher.buildIntent(
            this,
            "VIDEO",
            room,
            jitsiServer,
            doctorPhone,
            externalVideoLink
        )
        if (callIntent == null) {
            Toast.makeText(this, getString(R.string.failed), Toast.LENGTH_SHORT).show()
            return
        }

        //  Next workflow: summary stays behind external app
        startActivity(buildSummaryIntent("VIDEO"))
        ExternalCallLauncher.start(this, callIntent)
    }

    /**
     *  NEW AUDIO behavior:
     * 1) Open PatientConsultationSummaryActivity (so it's ready after call ends)
     * 2) Open Dialer on top
     */
    private fun startAudioWithSummary() {
        val phone = doctorPhone.trim()
        if (phone.isBlank()) {
            Toast.makeText(this, getString(R.string.doctor_phone_missing), Toast.LENGTH_SHORT).show()
            return
        }

        val callIntent = ExternalCallLauncher.buildIntent(
            this,
            "AUDIO",
            room,
            jitsiServer,
            phone,
            externalAudioLink
        )
        if (callIntent == null) {
            Toast.makeText(this, getString(R.string.failed), Toast.LENGTH_SHORT).show()
            return
        }

        // 1) Start summary first (so it stays beneath external app in back stack)
        startActivity(buildSummaryIntent("AUDIO"))
        // 2) Then open external call
        ExternalCallLauncher.start(this, callIntent)
    }

    private fun buildSummaryIntent(consult: String): Intent {
        // Build summary intent with robust extras (doesn't break even if some are unused)
        val publicCode = findViewById<TextView>(R.id.tvApptId)?.text?.toString().orEmpty().trim()
        val docName = findViewById<TextView>(R.id.tvDocName)?.text?.toString().orEmpty().trim()
        val docSpec = findViewById<TextView>(R.id.tvDocSpec)?.text?.toString().orEmpty().trim()
        val place = findViewById<TextView>(R.id.tvPlace)?.text?.toString().orEmpty().trim()
        val whenLabel = findViewById<TextView>(R.id.tvDateTime)?.text?.toString().orEmpty().trim()
        val feeText = findViewById<TextView>(R.id.tvFee)?.text?.toString().orEmpty().trim()

        return Intent(this, PatientConsultationSummaryActivity::class.java).apply {
            // appointment keys
            if (apptKey.isNotBlank()) {
                putExtra("appointmentKey", apptKey)
                putExtra("public_code", apptKey)
                putExtra("publicCode", apptKey)
                putExtra("appointment_id", apptKey)
                putExtra("appointmentId", apptKey)
                putExtra("id", apptKey)
            }
            if (publicCode.isNotBlank()) {
                putExtra("public_code", publicCode)
                putExtra("publicCode", publicCode)
                putExtra("appointmentKey", publicCode)
            }

            // context
            putExtra("consult_type", consult)
            if (docName.isNotBlank()) putExtra("doctorName", docName)
            if (docSpec.isNotBlank()) putExtra("doctorSpecialization", docSpec)
            if (place.isNotBlank()) putExtra("worksAt", place)
            if (whenLabel.isNotBlank()) putExtra("scheduled_label", whenLabel)
            if (feeText.isNotBlank()) putExtra("fee_text", feeText)

            if (apptStatus.isNotBlank()) putExtra("status", apptStatus)
            if (doctorPhone.isNotBlank()) putExtra("doctor_phone", doctorPhone)

            // optional time data
            if (scheduledAtMs > 0L) putExtra("scheduled_at_ms", scheduledAtMs)
            putExtra("duration_min", durationMin)
        }
    }

    // ---------------- Utilities ----------------

    private fun normalizeJitsiServer(raw: String): String {
        val s0 = raw.trim().trimEnd('/')
        if (s0.isBlank()) return JITSI_BASE_URL;

        if (s0.startsWith("http://") && s0.contains(":8000")) {
            return s0.replace("http://", "https://").replace(":8000", ":8443").trimEnd('/')
        }
        if (s0.startsWith("http://")) {
            return s0.replace("http://", "https://").trimEnd('/')
        }
        return s0
    }

    private fun roomFromPublicCode(code: String): String {
        val clean = code.trim().replace(Regex("[^a-zA-Z0-9_]"), "_")
        return if (clean.isNotBlank()) "ss_appt_$clean" else ""
    }

    private fun pickStr(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.optString(k, "").trim()
            if (v.isNotBlank() && !v.equals("null", true)) return v
        }
        return ""
    }

    private fun pickInt(o: JSONObject, def: Int, vararg keys: String): Int {
        for (k in keys) {
            if (!o.has(k)) continue
            val v = o.opt(k)
            val n = when (v) {
                is Int -> v
                is Long -> v.toInt()
                is Double -> v.toInt()
                is String -> v.trim().toIntOrNull()
                else -> null
            }
            if (n != null) return n
        }
        return def
    }

    private fun set(id: Int, v: String) {
        findViewById<TextView>(id)?.text = v
    }
}
