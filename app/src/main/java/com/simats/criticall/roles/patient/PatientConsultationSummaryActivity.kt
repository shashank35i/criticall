package com.simats.criticall.roles.patient

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.simats.criticall.ApiConfig.BASE_URL
import com.simats.criticall.AppPrefs
import com.simats.criticall.BaseActivity
import com.simats.criticall.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PatientConsultationSummaryActivity : BaseActivity() {

    private lateinit var ivBack: ImageView
    private lateinit var tvTitle: TextView

    private lateinit var tvBannerTitle: TextView
    private lateinit var tvBannerSub: TextView

    private lateinit var tvDoctorName: TextView
    private lateinit var tvDoctorSpec: TextView

    private lateinit var tvDiagnosis: TextView
    private lateinit var tvNotes: TextView

    private lateinit var rvMeds: RecyclerView
    private lateinit var tvViewFull: TextView

    private lateinit var cardFollow: View

    private lateinit var btnDownload: MaterialButton
    private lateinit var btnShare: MaterialButton
    private lateinit var btnHome: MaterialButton

    private lateinit var tvClinicLine: TextView
    private lateinit var tvRegNo: TextView
    private lateinit var tvPatientName: TextView
    private lateinit var tvPatientMeta: TextView
    private lateinit var tvRxDateRight: TextView

    private lateinit var tvAdvice1: TextView
    private lateinit var tvAdvice2: TextView
    private lateinit var tvAdvice3: TextView
    private lateinit var adviceBlock: View

    private lateinit var tvSignatureName: TextView

    private val meds = ArrayList<MedRow>()
    private lateinit var adapter: MedAdapter

    private var appointmentId: Long = 0L
    private var appointmentKey: String = ""
    private var prescriptionId: Long = 0L

    private val tz by lazy { TimeZone.getTimeZone("Asia/Kolkata") }
    private val dfServer by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply { timeZone = tz }
    }
    private val dfPretty by lazy {
        SimpleDateFormat("dd MMM, yyyy", Locale.getDefault()).apply { timeZone = tz }
    }

    data class MedRow(
        val name: String,
        val instruction: String,
        val meta: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patient_consultation_summary)
        supportActionBar?.hide()

        appointmentId = readLongAny("appointment_id", "appointmentId", "id", "apptId")
        prescriptionId = readLongAny("prescription_id", "prescriptionId", "pid_rx")
        appointmentKey = intent.getStringExtra("appointmentKey")
            ?: intent.getStringExtra("appointment_key")
                    ?: intent.getStringExtra("public_code")
                    ?: intent.getStringExtra("publicCode")
                    ?: ""

        ivBack = findViewById(R.id.ivBack)
        tvTitle = findViewById(R.id.tvTitle)

        tvBannerTitle = findViewById(R.id.tvBannerTitle)
        tvBannerSub = findViewById(R.id.tvBannerSub)

        tvDoctorName = findViewById(R.id.tvDoctorName)
        tvDoctorSpec = findViewById(R.id.tvDoctorSpec)

        tvDiagnosis = findViewById(R.id.tvDiagnosis)
        tvNotes = findViewById(R.id.tvNotes)

        rvMeds = findViewById(R.id.rvMeds)
        tvViewFull = findViewById(R.id.tvViewFull)

        cardFollow = findViewById(R.id.cardFollow)

        btnDownload = findViewById(R.id.btnDownload)
        btnShare = findViewById(R.id.btnShare)
        btnHome = findViewById(R.id.btnHome)

        tvClinicLine = findViewById(R.id.tvClinicLine)
        tvRegNo = findViewById(R.id.tvRegNo)
        tvPatientName = findViewById(R.id.tvPatientName)
        tvPatientMeta = findViewById(R.id.tvPatientMeta)
        tvRxDateRight = findViewById(R.id.tvRxDateRight)

        adviceBlock = findViewById(R.id.adviceBlock)
        tvAdvice1 = findViewById(R.id.tvAdvice1)
        tvAdvice2 = findViewById(R.id.tvAdvice2)
        tvAdvice3 = findViewById(R.id.tvAdvice3)

        tvSignatureName = findViewById(R.id.tvSignatureName)

        ivBack.setOnClickListener { finishWithAnim() }

        rvMeds.layoutManager = LinearLayoutManager(this)
        adapter = MedAdapter(meds)
        rvMeds.adapter = adapter

        tvViewFull.setOnClickListener {
            Toast.makeText(this, getString(R.string.view_full_prescription), Toast.LENGTH_SHORT).show()
        }

        btnHome.setOnClickListener { finishWithAnim() }
        btnDownload.setOnClickListener { createPdf(andShare = false) }
        btnShare.setOnClickListener { createPdf(andShare = true) }

        loadSummary()
    }

    override fun onBackPressed() {
        finishWithAnim()
    }

    private fun finishWithAnim() {
        finish()
        overridePendingTransition(R.anim.ai_pop_enter, R.anim.ai_pop_exit)
    }

    private fun loadSummary() {
        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank()) {
            toast(getString(R.string.please_login_again))
            finishWithAnim()
            return
        }

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                postJsonAuth(
                    BASE_URL + "patient/consultation_summary.php",
                    token,
                    JSONObject().apply {
                        if (appointmentId > 0L) put("appointment_id", appointmentId)
                        if (appointmentKey.isNotBlank()) put("appointment_key", appointmentKey)
                        if (prescriptionId > 0L) put("prescription_id", prescriptionId)
                    }
                )
            }

            if (!res.optBoolean("ok", false)) {
                toast(res.optString("error", getString(R.string.failed_to_load)))
                finishWithAnim()
                return@launch
            }

            bind(res.optJSONObject("data") ?: JSONObject())
        }
    }

    @SuppressLint("SetTextI18n")
    private fun bind(d: JSONObject) {
        tvTitle.text = getString(R.string.prescription)

        val doctorName = d.optString("doctor_name", "").trim().ifBlank { getString(R.string.doctor) }
        val doctorSpec = d.optString("doctor_speciality", "").trim()
            .ifBlank { getString(R.string.speciality_general_physician) }

        tvDoctorName.text = doctorName
        tvDoctorSpec.text = doctorSpec

        val clinic = d.optString("clinic_name", "").trim()
        val clinicAddr = d.optString("clinic_address", "").trim()
        tvClinicLine.text = when {
            clinic.isNotBlank() && clinicAddr.isNotBlank() -> "$clinic, $clinicAddr"
            clinic.isNotBlank() -> clinic
            clinicAddr.isNotBlank() -> clinicAddr
            else -> getString(R.string.clinic_placeholder)
        }

        val regNo = d.optString("doctor_reg_no", "").trim()
        tvRegNo.text = if (regNo.isNotBlank()) {
            getString(R.string.medical_registration_number_fmt, regNo)
        } else {
            getString(R.string.medical_registration_number_unknown)
        }

        val patientName = d.optString("patient_name", "").trim()
            .ifBlank { intent.getStringExtra("patientName").orEmpty() }
            .ifBlank { getString(R.string.patient) }

        val age = d.optInt("patient_age", 0)
        val gender = d.optString("patient_gender", "").trim()

        val patientMeta = buildString {
            val parts = ArrayList<String>()
            if (age > 0) parts.add(getString(R.string.years_fmt, age))
            if (gender.isNotBlank()) parts.add(gender)
            val loc = d.optString("patient_location", "").trim()
            if (loc.isNotBlank()) parts.add(loc)
            append(parts.joinToString(", "))
        }.ifBlank {
            intent.getStringExtra("patientMeta").orEmpty().ifBlank { getString(R.string.patient_meta_placeholder) }
        }

        tvPatientName.text = patientName
        tvPatientMeta.text = patientMeta

        val completedAt = d.optString("completed_at", "").trim()
        val datePretty = prettyDate(completedAt).ifBlank { getString(R.string.date_unknown) }
        tvRxDateRight.text = datePretty

        val rxId = d.optLong("prescription_id", 0L).let { if (it > 0L) it else prescriptionId }
        tvBannerTitle.text = datePretty
        tvBannerSub.text = if (rxId > 0L) getString(R.string.prescription_id_fmt, rxId)
        else getString(R.string.prescription_id_unknown)

        val diagnosis = d.optString("diagnosis", "").trim()
            .ifBlank { d.optString("title", "").trim() }
        tvDiagnosis.text = if (diagnosis.isNotBlank()) diagnosis else getString(R.string.consultation_summary_unavailable)

        val notes = d.optString("doctor_notes", "").trim()
        tvNotes.text = if (notes.isNotBlank()) notes else getString(R.string.consultation_summary_unavailable)

        val followUp = d.optString("follow_up_text", "").trim()
        if (followUp.isNotBlank()) {
            cardFollow.visibility = View.VISIBLE
            val tvFollowSub = cardFollow.findViewById<TextView>(R.id.tvFollowSub)
            tvFollowSub.text = followUp
        } else {
            cardFollow.visibility = View.GONE
        }

        meds.clear()
        val arr = d.optJSONArray("medicines") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim()
            if (name.isBlank()) continue

            val line1 = o.optString("line1", "").trim()
            val line2 = o.optString("line2", "").trim()

            val dosage = o.optString("dosage", "").trim()
            val frequency = o.optString("frequency", "").trim()
            val duration = o.optString("duration", "").trim()
            val instructions = o.optString("instructions", "").trim()

            val instruction = when {
                line1.isNotBlank() -> line1
                else -> buildInstruction(dosage, frequency, duration, instructions)
            }

            val meta = when {
                line2.isNotBlank() -> line2
                else -> buildMeta(dosage, frequency, duration)
            }

            meds.add(MedRow(name = name, instruction = instruction, meta = meta))
        }
        adapter.notifyDataSetChanged()

        tvViewFull.visibility = if (meds.isNotEmpty()) View.VISIBLE else View.GONE

        val adviceArr = d.optJSONArray("general_advice")
        if (adviceArr != null && adviceArr.length() > 0) {
            val a1 = adviceArr.optString(0, "").trim()
            val a2 = adviceArr.optString(1, "").trim()
            val a3 = adviceArr.optString(2, "").trim()
            adviceBlock.visibility = View.VISIBLE
            tvAdvice1.text = if (a1.isNotBlank()) a1 else getString(R.string.isolate_yourself)
            tvAdvice2.text = if (a2.isNotBlank()) a2 else getString(R.string.monitor_oxygen)
            tvAdvice3.text = if (a3.isNotBlank()) a3 else getString(R.string.consult_covid_center)
        } else {
            adviceBlock.visibility = View.VISIBLE
            tvAdvice1.text = getString(R.string.isolate_yourself)
            tvAdvice2.text = getString(R.string.monitor_oxygen)
            tvAdvice3.text = getString(R.string.consult_covid_center)
        }

        tvSignatureName.text = doctorName
    }

    private fun buildInstruction(dosage: String, frequency: String, duration: String, instructions: String): String {
        val parts = ArrayList<String>()
        if (dosage.isNotBlank()) parts.add(dosage)
        if (frequency.isNotBlank()) parts.add(frequency)
        if (duration.isNotBlank()) parts.add(duration)
        if (instructions.isNotBlank()) parts.add(instructions)
        return parts.joinToString(" • ")
    }

    private fun buildMeta(dosage: String, frequency: String, duration: String): String {
        val parts = ArrayList<String>()
        if (dosage.isNotBlank()) parts.add(dosage)
        if (frequency.isNotBlank()) parts.add(frequency)
        if (duration.isNotBlank()) parts.add(duration)
        return parts.joinToString("  ")
    }

    private fun prettyDate(mysql: String): String {
        if (mysql.isBlank()) return ""
        return try {
            val dt = dfServer.parse(mysql)
            if (dt != null) dfPretty.format(dt) else ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun createPdf(andShare: Boolean) {
        val outDir = File(cacheDir, "summaries").apply { mkdirs() }
        val outFile = File(outDir, "prescription_${System.currentTimeMillis()}.pdf")

        try {
            val doc = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = doc.startPage(pageInfo)
            val c = page.canvas

            val textPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.parseColor("#0F172A")
            }
            val subPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.parseColor("#64748B")
            }
            val blue = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.parseColor("#1E3A8A")
            }
            val line = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.parseColor("#E5E7EB")
                strokeWidth = 1f
            }

            c.drawRect(0f, 0f, 595f, 120f, blue)

            val headerWhite = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.WHITE
                textSize = 18f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            c.drawText(getString(R.string.criticall), 24f, 42f, headerWhite)

            headerWhite.textSize = 13f
            headerWhite.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val docName = tvDoctorName.text.toString()
            c.drawText(docName, 24f, 70f, headerWhite)

            headerWhite.textSize = 11.5f
            headerWhite.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            c.drawText(tvClinicLine.text.toString(), 24f, 90f, headerWhite)
            c.drawText(tvRegNo.text.toString(), 24f, 108f, headerWhite)

            var y = 150f
            textPaint.textSize = 13.5f
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            c.drawText(tvPatientName.text.toString(), 24f, y, textPaint)

            subPaint.textSize = 12f
            subPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            y += 18f
            c.drawText(tvPatientMeta.text.toString(), 24f, y, subPaint)

            textPaint.textSize = 12f
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            val dateRight = tvRxDateRight.text.toString()
            c.drawText(dateRight, 595f - 24f - textPaint.measureText(dateRight), 150f, textPaint)

            val rxIdText = tvBannerSub.text.toString()
            subPaint.textSize = 11.5f
            c.drawText(rxIdText, 595f - 24f - subPaint.measureText(rxIdText), 168f, subPaint)

            y += 22f
            c.drawLine(24f, y, 595f - 24f, y, line)
            y += 26f

            subPaint.color = android.graphics.Color.parseColor("#6B7280")
            subPaint.textSize = 11.5f
            subPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            c.drawText(getString(R.string.provisional_diagnosis).uppercase(Locale.getDefault()), 24f, y, subPaint)
            y += 20f

            textPaint.color = android.graphics.Color.parseColor("#111827")
            textPaint.textSize = 13.5f
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            y = drawWrapped(c, textPaint, tvDiagnosis.text.toString(), 24f, y, 595f - 48f, 18f) + 12f

            c.drawLine(24f, y, 595f - 24f, y, line)
            y += 26f

            subPaint.color = android.graphics.Color.parseColor("#6B7280")
            subPaint.textSize = 11.5f
            subPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            c.drawText(getString(R.string.medicines).uppercase(Locale.getDefault()), 24f, y, subPaint)
            y += 18f

            subPaint.textSize = 10.8f
            subPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            c.drawText("#", 24f, y, subPaint)
            c.drawText(getString(R.string.name_col).uppercase(Locale.getDefault()), 44f, y, subPaint)
            c.drawText(getString(R.string.instruction_col).uppercase(Locale.getDefault()), 330f, y, subPaint)
            y += 10f
            c.drawLine(24f, y, 595f - 24f, y, line)
            y += 18f

            val namePaint = android.graphics.Paint(textPaint).apply {
                textSize = 12.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val instrPaint = android.graphics.Paint(textPaint).apply {
                textSize = 11.8f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                color = android.graphics.Color.parseColor("#111827")
            }

            meds.forEachIndexed { idx, m ->
                c.drawText("${idx + 1}", 24f, y, instrPaint)

                val nmLines = wrapLines(m.name, namePaint, 280f)
                val instrLines = wrapLines(m.instruction.ifBlank { m.meta }, instrPaint, 595f - 24f - 330f)
                val rowLines = maxOf(nmLines.size, instrLines.size).coerceAtLeast(1)

                var yy = y
                for (li in 0 until rowLines) {
                    c.drawText(nmLines.getOrNull(li).orEmpty(), 44f, yy, namePaint)
                    c.drawText(instrLines.getOrNull(li).orEmpty(), 330f, yy, instrPaint)
                    yy += 16f
                }
                y = yy + 8f
            }

            y += 4f
            c.drawLine(24f, y, 595f - 24f, y, line)
            y += 26f

            subPaint.color = android.graphics.Color.parseColor("#6B7280")
            subPaint.textSize = 11.5f
            subPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            c.drawText(getString(R.string.general_advice_notes).uppercase(Locale.getDefault()), 24f, y, subPaint)
            y += 18f

            val advPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                textSize = 12.5f
                color = android.graphics.Color.parseColor("#111827")
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }

            val bullets = listOf(tvAdvice1.text.toString(), tvAdvice2.text.toString(), tvAdvice3.text.toString())
                .filter { it.isNotBlank() }
            bullets.forEach {
                c.drawText("•", 28f, y, advPaint)
                y = drawWrapped(c, advPaint, it, 42f, y, 595f - 66f, 18f) + 6f
            }

            // ---- Signature (BOTTOM RIGHT like prescription) ----
            val sigBaseY = 820f
            val sigLineY = 792f
            val sigRight = 595f - 24f
            val sigLeft = sigRight - 170f

            val sigLabel = getString(R.string.signature).uppercase(Locale.getDefault())
            val sigLabelPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.parseColor("#6B7280")
                textSize = 10.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            c.drawText(sigLabel, sigLeft, sigLineY - 10f, sigLabelPaint)
            c.drawLine(sigLeft, sigLineY, sigRight, sigLineY, line)

            val sigNamePaint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.parseColor("#111827")
                textSize = 16f
                typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            }
            val sigName = tvSignatureName.text.toString().ifBlank { tvDoctorName.text.toString() }
            val sigNameX = sigRight - sigNamePaint.measureText(sigName)
            c.drawText(sigName, sigNameX, sigBaseY, sigNamePaint)

            doc.finishPage(page)
            FileOutputStream(outFile).use { doc.writeTo(it) }
            doc.close()

            if (!andShare) {
                toast(getString(R.string.downloaded))
                return
            }

            val uri = FileProvider.getUriForFile(
                this,
                applicationContext.packageName + ".fileprovider",
                outFile
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, getString(R.string.share)))

        } catch (_: Throwable) {
            toast(getString(R.string.failed))
        }
    }

    private fun wrapLines(text: String, paint: android.graphics.Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return listOf("")
        val words = text.split(Regex("\\s+"))
        val out = ArrayList<String>()
        var line = ""
        for (w in words) {
            val test = if (line.isBlank()) w else "$line $w"
            if (paint.measureText(test) <= maxWidth) {
                line = test
            } else {
                if (line.isNotBlank()) out.add(line)
                line = w
            }
        }
        if (line.isNotBlank()) out.add(line)
        return out.ifEmpty { listOf("") }
    }

    private fun drawWrapped(
        canvas: android.graphics.Canvas,
        paint: android.graphics.Paint,
        text: String,
        x: Float,
        startY: Float,
        maxWidth: Float,
        lineHeight: Float
    ): Float {
        var y = startY
        val lines = wrapLines(text, paint, maxWidth)
        for (ln in lines) {
            canvas.drawText(ln, x, y, paint)
            y += lineHeight
        }
        return y
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun readLongAny(vararg keys: String): Long {
        val b = intent.extras
        if (b != null) {
            for (k in keys) {
                when (val any = b.get(k)) {
                    is Long -> if (any > 0L) return any
                    is Int -> if (any > 0) return any.toLong()
                    is String -> any.trim().toLongOrNull()?.let { if (it > 0L) return it }
                }
            }
        }
        for (k in keys) {
            val v = intent.getLongExtra(k, 0L)
            if (v > 0L) return v
        }
        return 0L
    }

    private fun postJsonAuth(urlStr: String, token: String, body: JSONObject): JSONObject {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 20000
                doInput = true
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            runCatching { JSONObject(text) }.getOrElse {
                JSONObject().put("ok", false).put("error", "Bad response")
            }
        } catch (_: Throwable) {
            JSONObject().put("ok", false).put("error", "Network error")
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private inner class MedAdapter(private val items: List<MedRow>) :
        RecyclerView.Adapter<MedVH>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): MedVH {
            val v = layoutInflater.inflate(R.layout.item_prescribed_medicine, parent, false)
            return MedVH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: MedVH, position: Int) {
            val m = items[position]
            h.tvIndex.text = (position + 1).toString()
            h.tvName.text = m.name
            h.tvLine1.text = m.instruction
            h.tvLine2.text = m.meta
            h.tvLine1.visibility = if (m.instruction.isBlank()) View.GONE else View.VISIBLE
            h.tvLine2.visibility = if (m.meta.isBlank() || m.meta == m.instruction) View.GONE else View.VISIBLE
        }
    }

    private class MedVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvIndex: TextView = v.findViewById(R.id.tvIndex)
        val tvName: TextView = v.findViewById(R.id.tvMedName)
        val tvLine1: TextView = v.findViewById(R.id.tvMedLine1)
        val tvLine2: TextView = v.findViewById(R.id.tvMedLine2)
    }
}
