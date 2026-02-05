package com.simats.criticall.roles.patient

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.simats.criticall.ApiConfig.BASE_URL
import com.simats.criticall.AppPrefs
import com.simats.criticall.BaseActivity
import com.simats.criticall.LocalCache
import com.simats.criticall.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PatientPrescriptionDetailsActivity : BaseActivity() {

    private lateinit var ivBack: View
    private lateinit var tvTitle: TextView

    private lateinit var tvDoctorName: TextView
    private lateinit var tvDoctorSpec: TextView
    private lateinit var tvClinicLine: TextView
    private lateinit var tvRegNo: TextView

    private lateinit var tvPatientName: TextView
    private lateinit var tvRxDateRight: TextView
    private lateinit var tvPatientMeta: TextView
    private lateinit var tvBannerSub: TextView

    private lateinit var tvDiagnosis: TextView
    private lateinit var tvNotes: TextView

    private lateinit var rvMeds: RecyclerView
    private lateinit var tvViewFull: TextView

    private lateinit var cardFollow: View
    private lateinit var tvFollowSub: TextView

    private lateinit var tvSignatureName: TextView

    private lateinit var btnDownload: MaterialButton
    private lateinit var btnShare: MaterialButton
    private lateinit var btnHome: MaterialButton

    private lateinit var vLoading: View

    private var prescriptionId: Long = 0L

    private val meds = ArrayList<MedRow>()
    private lateinit var adapter: MedAdapter

    private val tz by lazy { TimeZone.getTimeZone("Asia/Kolkata") }
    private val dfServer by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply { timeZone = tz }
    }
    private val dfPretty by lazy {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).apply { timeZone = tz }
    }

    private val CACHE_PREFIX = "prescription_detail"

    data class MedRow(
        val name: String,
        val dosage: String,
        val frequency: String,
        val duration: String,
        val instructions: String
    )

    private val saveDocLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(buildShareText().toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                Toast.makeText(this, getString(R.string.download_saved), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patient_prescription_details)
        supportActionBar?.hide()

        prescriptionId = readLongAny("prescription_id", "prescriptionId", "id")
        if (prescriptionId <= 0L) {
            Toast.makeText(this, getString(R.string.no_prescription_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        ivBack = findViewById(R.id.ivBack)
        tvTitle = findViewById(R.id.tvTitle)

        tvDoctorName = findViewById(R.id.tvDoctorName)
        tvDoctorSpec = findViewById(R.id.tvDoctorSpec)
        tvClinicLine = findViewById(R.id.tvClinicLine)
        tvRegNo = findViewById(R.id.tvRegNo)

        tvPatientName = findViewById(R.id.tvPatientName)
        tvRxDateRight = findViewById(R.id.tvRxDateRight)
        tvPatientMeta = findViewById(R.id.tvPatientMeta)
        tvBannerSub = findViewById(R.id.tvBannerSub)

        tvDiagnosis = findViewById(R.id.tvDiagnosis)
        tvNotes = findViewById(R.id.tvNotes)

        rvMeds = findViewById(R.id.rvMeds)
        tvViewFull = findViewById(R.id.tvViewFull)

        cardFollow = findViewById(R.id.cardFollow)
        tvFollowSub = findViewById(R.id.tvFollowSub)

        tvSignatureName = findViewById(R.id.tvSignatureName)

        btnDownload = findViewById(R.id.btnDownload)
        btnShare = findViewById(R.id.btnShare)
        btnHome = findViewById(R.id.btnHome)

        vLoading = findViewById(R.id.vLoading)

        tvTitle.text = getString(R.string.prescription)
        ivBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        rvMeds.layoutManager = LinearLayoutManager(this)
        rvMeds.setHasFixedSize(false)
        adapter = MedAdapter(meds)
        rvMeds.adapter = adapter

        btnShare.isEnabled = false
        btnDownload.isEnabled = false

        btnShare.setOnClickListener {
            runCatching {
                val t = buildShareText()
                val itn = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.prescription))
                    putExtra(Intent.EXTRA_TEXT, t)
                }
                startActivity(Intent.createChooser(itn, getString(R.string.share)))
            }.onFailure {
                Toast.makeText(this, getString(R.string.share_failed), Toast.LENGTH_SHORT).show()
            }
        }

        btnDownload.setOnClickListener {
            val fileName = "Prescription_${prescriptionId}.txt"
            saveDocLauncher.launch(fileName)
        }

        tvViewFull.setOnClickListener {
            runCatching {
                val t = buildShareText()
                val itn = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.prescription))
                    putExtra(Intent.EXTRA_TEXT, t)
                }
                startActivity(Intent.createChooser(itn, getString(R.string.share)))
            }.onFailure {
                Toast.makeText(this, getString(R.string.share_failed), Toast.LENGTH_SHORT).show()
            }
        }

        btnHome.setOnClickListener {
            goHomeSafe()
        }

        if (loadFromCacheIfAvailable()) {
            btnShare.isEnabled = true
            btnDownload.isEnabled = true
        }

        load()
    }

    private fun goHomeSafe() {
        runCatching {
            val cls = Class.forName("com.simats.criticall.roles.patient.PatientHomeActivity")
            startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }.recoverCatching {
            val cls = Class.forName("com.simats.criticall.roles.patient.PatientDashboardActivity")
            startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }.onFailure {
            finish()
        }
    }

    private fun cacheKey(): String = LocalCache.key(CACHE_PREFIX, prescriptionId)

    private fun loadFromCacheIfAvailable(): Boolean {
        val cached = LocalCache.getString(this, cacheKey()).orEmpty()
        if (cached.isBlank()) return false
        val data = runCatching { JSONObject(cached) }.getOrNull() ?: return false
        bind(data)
        return true
    }

    private fun load() {
        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank()) {
            if (!loadFromCacheIfAvailable()) {
                Toast.makeText(this, getString(R.string.please_login_again), Toast.LENGTH_SHORT).show()
            }
            return
        }

        lifecycleScope.launch {
            vLoading.isVisible = true

            val url = BASE_URL +
                    "patient/prescription_detail.php?prescription_id=" + prescriptionId +
                    "&_ts=" + System.currentTimeMillis()

            val res = withContext(Dispatchers.IO) { getJsonAuth(url, token) }

            vLoading.isVisible = false

            if (!res.optBoolean("ok", false)) {
                if (!loadFromCacheIfAvailable()) {
                    val err = res.optString("error", "").trim()
                    Toast.makeText(
                        this@PatientPrescriptionDetailsActivity,
                        if (err.isNotBlank()) err else getString(R.string.failed_to_load),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            val data = res.optJSONObject("data") ?: JSONObject()
            bind(data)

            LocalCache.putString(this@PatientPrescriptionDetailsActivity, cacheKey(), data.toString())
            LocalCache.putLong(this@PatientPrescriptionDetailsActivity, cacheKey() + "_ts", System.currentTimeMillis())

            btnShare.isEnabled = true
            btnDownload.isEnabled = true
        }
    }

    private fun bind(data: JSONObject) {
        val p = data.optJSONObject("prescription") ?: JSONObject()
        val items = data.optJSONArray("items") ?: JSONArray()

        val docName = p.optString("doctor_name", "").trim().ifBlank { getString(R.string.doctor) }
        val spec = p.optString("doctor_specialization", "").trim()
            .ifBlank { getString(R.string.speciality_general_physician) }

        val place = p.optString("works_at", "").trim()
            .ifBlank { p.optString("practice_place", "").trim() }
            .ifBlank { p.optString("doctor_place", "").trim() }

        tvDoctorName.text = docName
        tvDoctorSpec.text = spec
        tvClinicLine.text = if (place.isNotBlank()) place else getString(R.string.clinic_placeholder)

        val regNo = p.optString("registration_no", "").trim()
            .ifBlank { p.optString("medical_registration_no", "").trim() }
        tvRegNo.text = if (regNo.isNotBlank()) regNo else getString(R.string.medical_registration_number_unknown)

        tvSignatureName.text = docName

        tvPatientName.text = getString(R.string.patient)

        val createdAt = p.optString("created_at", "").trim()
        val pretty = prettyDateFromCreatedAt(createdAt)
        tvRxDateRight.text = if (pretty != "—") pretty else getString(R.string.date_unknown)

        tvPatientMeta.text = getString(R.string.patient_meta_placeholder)

        val pidLine = getString(R.string.prescription_id_unknown)
        tvBannerSub.text = "$pidLine  #$prescriptionId"

        val diagnosis = p.optString("diagnosis", "").trim()
        tvDiagnosis.text = if (diagnosis.isNotBlank()) diagnosis else getString(R.string.consultation_summary_unavailable)

        val notes = p.optString("doctor_notes", "").trim()
        tvNotes.text = if (notes.isNotBlank()) notes else getString(R.string.consultation_summary_unavailable)

        val follow = p.optString("followup_note", "").trim()
        if (follow.isNotBlank()) {
            cardFollow.isVisible = true
            tvFollowSub.text = follow
        } else {
            cardFollow.isVisible = false
        }

        meds.clear()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            meds.add(
                MedRow(
                    name = o.optString("name", "").trim(),
                    dosage = o.optString("dosage", "").trim(),
                    frequency = o.optString("frequency", "").trim(),
                    duration = o.optString("duration", "").trim(),
                    instructions = o.optString("instructions", "").trim()
                )
            )
        }
        adapter.notifyDataSetChanged()

        // Cache compact summary for AI assistant (offline-safe)
        cacheAssistantRxSummary(p, items)

        btnShare.isEnabled = true
        btnDownload.isEnabled = true
    }

    private fun prettyDateFromCreatedAt(mysql: String): String {
        if (mysql.isBlank()) return "—"
        return try {
            val d = dfServer.parse(mysql)
            if (d != null) dfPretty.format(d) else "—"
        } catch (_: Throwable) { "—" }
    }

    private fun cacheAssistantRxSummary(p: JSONObject, items: JSONArray) {
        val diagnosis = p.optString("diagnosis", "").trim()
        val notes = p.optString("doctor_notes", "").trim()
        val follow = p.optString("followup_note", "").trim()
        val date = p.optString("created_at", p.optString("issued_at", "")).trim()

        val sb = StringBuilder()
        if (date.isNotBlank()) sb.append("Date=").append(date).append("\n")
        if (diagnosis.isNotBlank()) sb.append("Diagnosis=").append(diagnosis).append("\n")
        if (notes.isNotBlank()) sb.append("DoctorNotes=").append(notes).append("\n")
        if (follow.isNotBlank()) sb.append("FollowUp=").append(follow).append("\n")

        if (items.length() > 0) {
            sb.append("Medicines:\n")
            val n = kotlin.math.min(items.length(), 8)
            for (i in 0 until n) {
                val m = items.optJSONObject(i) ?: continue
                val name = m.optString("name", "").trim()
                val dosage = m.optString("dosage", "").trim()
                val freq = m.optString("frequency", "").trim()
                val dur = m.optString("duration", m.optString("days", "")).trim()
                if (name.isBlank()) continue
                sb.append("- ").append(name)
                if (dosage.isNotBlank()) sb.append(" ").append(dosage)
                if (freq.isNotBlank()) sb.append(" ").append(freq)
                if (dur.isNotBlank()) sb.append(" ").append(dur)
                sb.append("\n")
            }
        }

        val summary = sb.toString().trim()
        if (summary.isNotBlank()) {
            LocalCache.putString(this, "assistant_rx_summary", summary)
            LocalCache.putLong(this, "assistant_rx_summary_ts", System.currentTimeMillis())
        }
    }

    private fun buildShareText(): String {
        val name = tvDoctorName.text?.toString().orEmpty().trim()
        val spec = tvDoctorSpec.text?.toString().orEmpty().trim()
        val clinic = tvClinicLine.text?.toString().orEmpty().trim()
        val reg = tvRegNo.text?.toString().orEmpty().trim()
        val date = tvRxDateRight.text?.toString().orEmpty().trim()

        val diagnosis = tvDiagnosis.text?.toString().orEmpty().trim()
        val notes = tvNotes.text?.toString().orEmpty().trim()
        val follow = if (cardFollow.isVisible) tvFollowSub.text?.toString().orEmpty().trim() else ""

        val sb = StringBuilder()
        sb.appendLine(getString(R.string.prescription))
        sb.appendLine("—")
        if (name.isNotBlank()) sb.appendLine(name)
        if (spec.isNotBlank()) sb.appendLine(spec)
        if (clinic.isNotBlank()) sb.appendLine(clinic)
        if (reg.isNotBlank()) sb.appendLine(reg)
        if (date.isNotBlank()) sb.appendLine(date)
        sb.appendLine()
        sb.appendLine(getString(R.string.provisional_diagnosis))
        sb.appendLine(diagnosis.ifBlank { getString(R.string.consultation_summary_unavailable) })
        sb.appendLine()
        sb.appendLine(getString(R.string.doctors_notes))
        sb.appendLine(notes.ifBlank { getString(R.string.consultation_summary_unavailable) })
        sb.appendLine()
        sb.appendLine(getString(R.string.medicines))
        if (meds.isEmpty()) {
            sb.appendLine("—")
        } else {
            meds.forEachIndexed { idx, m ->
                val inst = listOfNotNull(
                    m.dosage.takeIf { it.isNotBlank() },
                    m.frequency.takeIf { it.isNotBlank() },
                    m.duration.takeIf { it.isNotBlank() },
                    m.instructions.takeIf { it.isNotBlank() }
                ).joinToString(" • ").ifBlank { "—" }

                sb.appendLine("${idx + 1}. ${m.name.ifBlank { "—" }}")
                sb.appendLine("   $inst")
            }
        }
        if (follow.isNotBlank()) {
            sb.appendLine()
            sb.appendLine(getString(R.string.follow_up_recommended))
            sb.appendLine(follow)
        }
        sb.appendLine()
        sb.appendLine(getString(R.string.signature))
        sb.appendLine(name.ifBlank { "—" })
        return sb.toString().trim()
    }

    private inner class MedAdapter(private val items: List<MedRow>) :
        RecyclerView.Adapter<MedVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MedVH {
            val v = layoutInflater.inflate(R.layout.item_patient_prescribed_medicine, parent, false)
            return MedVH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: MedVH, position: Int) {
            val m = items[position]

            h.tvIndex.text = (position + 1).toString()

            h.tvName.text = m.name.ifBlank { "—" }

            val inst = listOfNotNull(
                m.dosage.takeIf { it.isNotBlank() },
                m.frequency.takeIf { it.isNotBlank() },
                m.duration.takeIf { it.isNotBlank() },
                m.instructions.takeIf { it.isNotBlank() }
            ).joinToString(" • ").ifBlank { "—" }

            h.tvInst.text = inst
        }
    }

    private class MedVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvIndex: TextView = v.findViewById(R.id.tvIndex)
        val tvName: TextView = v.findViewById(R.id.tvName)
        val tvInst: TextView = v.findViewById(R.id.tvInst)
    }

    private fun readLongAny(vararg keys: String): Long {
        for (k in keys) {
            val any = intent.extras?.get(k)
            when (any) {
                is Long -> if (any > 0L) return any
                is Int -> if (any > 0) return any.toLong()
                is String -> any.trim().toLongOrNull()?.let { if (it > 0L) return it }
            }
        }
        for (k in keys) {
            val v = intent.getLongExtra(k, 0L)
            if (v > 0L) return v
        }
        return 0L
    }

    private fun getJsonAuth(urlStr: String, token: String): JSONObject {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20000
                readTimeout = 20000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            runCatching { JSONObject(text) }.getOrElse {
                JSONObject().put("ok", false).put("error", "Invalid server response")
            }
        } catch (_: Throwable) {
            JSONObject().put("ok", false).put("error", "Network error")
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }
}
