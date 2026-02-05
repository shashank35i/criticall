package com.simats.criticall.roles.patient

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simats.criticall.BaseActivity
import com.simats.criticall.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

class PatientDoctorListActivity : BaseActivity() {

    private val uiScope = MainScope()

    private val allDocs = ArrayList<JSONObject>()
    private val visibleDocs = ArrayList<JSONObject>()

    private lateinit var rv: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var tvCount: TextView
    private lateinit var boxFilter: View

    private lateinit var cardEmpty: View
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptyDesc: TextView
    private lateinit var btnEmptyAction: View

    private lateinit var specialityKey: String
    private lateinit var specialityLabel: String

    //  NEW: carry symptoms (AI → list → details → slots → booking)
    private var symptomsText: String = ""

    //  NEW: auto open first doctor (AI flow)
    private var autoOpenFirst: Boolean = false
    private var didAutoOpen: Boolean = false
    private var autoDoctorId: Long = 0L
    private var autoConsultType: String = ""
    private var autoPrefDate: String = ""
    private var autoPrefTime: String = ""
    private var autoConfirm: Boolean = false

    private var searchJob: Job? = null
    private var sort: SortOption = SortOption.RECOMMENDED

    private lateinit var adapter: DoctorAdapter

    private enum class SortOption {
        RECOMMENDED,
        RATING_HIGH,
        FEE_LOW,
        EXPERIENCE_HIGH,
        NAME_AZ
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patient_doctor_list)
        supportActionBar?.hide()

        specialityKey = intent.getStringExtra(SelectSpecialityActivity.EXTRA_SPECIALITY_KEY)
            ?: intent.getStringExtra("speciality").orEmpty()

        specialityLabel = intent.getStringExtra(SelectSpecialityActivity.EXTRA_SPECIALITY_LABEL)
            ?: intent.getStringExtra("speciality").orEmpty()

        //  NEW: read symptoms + autoOpen flags (does NOT affect normal flow)
        symptomsText = intent.getStringExtra(EXTRA_SYMPTOMS_TEXT).orEmpty()
        autoOpenFirst = intent.getBooleanExtra(EXTRA_AUTO_OPEN_FIRST, false)
        didAutoOpen = savedInstanceState?.getBoolean(STATE_DID_AUTO_OPEN, false) ?: false
        autoDoctorId = intent.getLongExtra(EXTRA_AUTO_DOCTOR_ID, 0L)
        autoConsultType = intent.getStringExtra(EXTRA_AUTO_CONSULT_TYPE).orEmpty()
        autoPrefDate = intent.getStringExtra(EXTRA_PREF_DATE).orEmpty()
        autoPrefTime = intent.getStringExtra(EXTRA_PREF_TIME).orEmpty()
        autoConfirm = intent.getBooleanExtra(EXTRA_AUTO_CONFIRM, false)

        findViewById<TextView>(R.id.tvTitle).text = specialityLabel
        findViewById<View>(R.id.ivBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        rv = findViewById(R.id.rvDoctors)
        etSearch = findViewById(R.id.etSearch)
        tvCount = findViewById(R.id.tvCount)
        boxFilter = findViewById(R.id.boxFilter)

        cardEmpty = findViewById(R.id.cardEmpty)
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle)
        tvEmptyDesc = findViewById(R.id.tvEmptyDesc)
        btnEmptyAction = findViewById(R.id.btnEmptyAction)

        adapter = DoctorAdapter(visibleDocs) { doctorIdLong ->
            val doctorIdInt = doctorIdLong.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            if (doctorIdInt <= 0) {
                Toast.makeText(this, getString(R.string.failed), Toast.LENGTH_SHORT).show()
                return@DoctorAdapter
            }

            val itn = Intent(this, PatientDoctorDetailsActivity::class.java)

            //  pass multiple extras (int + long + string)
            itn.putExtra("doctorId", doctorIdInt)
            itn.putExtra("doctor_id", doctorIdLong)
            itn.putExtra("doctorIdLong", doctorIdLong)
            itn.putExtra("doctor_id_str", doctorIdLong.toString())

            itn.putExtra(SelectSpecialityActivity.EXTRA_SPECIALITY_KEY, specialityKey)
            itn.putExtra(SelectSpecialityActivity.EXTRA_SPECIALITY_LABEL, specialityLabel)

            //  NEW: carry symptoms forward (safe even if empty)
            itn.putExtra(EXTRA_SYMPTOMS_TEXT, symptomsText)
            if (autoDoctorId > 0L) itn.putExtra(EXTRA_AUTO_DOCTOR_ID, autoDoctorId)
            if (autoConsultType.isNotBlank()) itn.putExtra(EXTRA_AUTO_CONSULT_TYPE, autoConsultType)
            if (autoPrefDate.isNotBlank()) itn.putExtra(EXTRA_PREF_DATE, autoPrefDate)
            if (autoPrefTime.isNotBlank()) itn.putExtra(EXTRA_PREF_TIME, autoPrefTime)
            if (autoConfirm) itn.putExtra(EXTRA_AUTO_CONFIRM, true)

            startActivity(itn)
        }

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        setupSearch()
        setupFilter()

        btnEmptyAction.setOnClickListener {
            val q = etSearch.text?.toString().orEmpty().trim()
            if (q.isNotBlank()) {
                etSearch.setText("")
                etSearch.requestFocus()
            } else {
                onBackPressedDispatcher.onBackPressed()
            }
        }

        loadDoctors()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_DID_AUTO_OPEN, didAutoOpen)
        super.onSaveInstanceState(outState)
    }

    private fun setupSearch() {
        etSearch.setOnEditorActionListener { _, actionId, event ->
            val isSearch = actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)

            if (isSearch) {
                applySearchAndSort(etSearch.text?.toString().orEmpty())
                true
            } else false
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString().orEmpty()
                searchJob?.cancel()
                searchJob = uiScope.launch {
                    delay(250)
                    applySearchAndSort(q)
                }
            }
        })
    }

    private fun setupFilter() {
        boxFilter.setOnClickListener { showSortDialog() }
    }

    private fun showSortDialog() {
        val items = arrayOf(
            getString(R.string.filter_recommended),
            getString(R.string.filter_rating_high),
            getString(R.string.filter_fee_low),
            getString(R.string.filter_experience_high),
            getString(R.string.filter_name_az)
        )

        val currentIndex = when (sort) {
            SortOption.RECOMMENDED -> 0
            SortOption.RATING_HIGH -> 1
            SortOption.FEE_LOW -> 2
            SortOption.EXPERIENCE_HIGH -> 3
            SortOption.NAME_AZ -> 4
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.filter_title))
            .setSingleChoiceItems(items, currentIndex) { dialog, which ->
                sort = when (which) {
                    1 -> SortOption.RATING_HIGH
                    2 -> SortOption.FEE_LOW
                    3 -> SortOption.EXPERIENCE_HIGH
                    4 -> SortOption.NAME_AZ
                    else -> SortOption.RECOMMENDED
                }
                dialog.dismiss()
                applySearchAndSort(etSearch.text?.toString().orEmpty())
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun loadDoctors() {
        uiScope.launch {
            val arr = withContext(Dispatchers.IO) {
                PatientApi.getDoctors(this@PatientDoctorListActivity, specialityKey)
            }

            allDocs.clear()
            visibleDocs.clear()

            if (arr == null) {
                Toast.makeText(
                    this@PatientDoctorListActivity,
                    PatientApi.lastError ?: getString(R.string.failed),
                    Toast.LENGTH_SHORT
                ).show()
                adapter.notifyDataSetChanged()
                setCount(0)
                showEmptyInitial()
                return@launch
            }

            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { allDocs.add(it) }
            }

            applySearchAndSort(etSearch.text?.toString().orEmpty())

            //  NEW: AI flow - auto open doctor ONCE (does not affect normal usage)
            if (autoOpenFirst && !didAutoOpen) {
                val targetId = if (autoDoctorId > 0L) {
                    visibleDocs.firstOrNull { parseDoctorIdSafe(it) == autoDoctorId }?.let { autoDoctorId } ?: 0L
                } else 0L

                val idLong = when {
                    targetId > 0L -> targetId
                    else -> visibleDocs.firstOrNull()?.let { parseDoctorIdSafe(it) } ?: 0L
                }

                if (idLong > 0L) {
                    didAutoOpen = true
                    adapter.onClick(idLong)
                }
            }
        }
    }

    private fun parseDoctorIdSafe(o: JSONObject): Long {
        val candidates = listOf("doctor_id", "doctorId", "user_id", "userId", "id")
        for (k in candidates) {
            val v = o.opt(k)
            when (v) {
                is Number -> {
                    val n = v.toLong()
                    if (n > 0) return n
                }
                is String -> {
                    val n = v.trim().toLongOrNull()
                    if (n != null && n > 0) return n
                }
            }
        }
        val doc = o.optJSONObject("doctor")
        if (doc != null) return parseDoctorIdSafe(doc)
        return 0L
    }

    private fun applySearchAndSort(queryRaw: String) {
        val q = queryRaw.trim()
        val qLower = q.lowercase(Locale.getDefault())

        val filtered = if (qLower.isBlank()) {
            allDocs.toList()
        } else {
            allDocs.filter { d ->
                val name = d.optString("name", d.optString("full_name", ""))
                val spec = d.optString("specialization", d.optString("speciality", ""))
                val langs = d.optString("languages", "")
                val avail = d.optString("availability", "")
                val hay = "$name $spec $langs $avail".lowercase(Locale.getDefault())
                hay.contains(qLower)
            }
        }

        val sorted = when (sort) {
            SortOption.RECOMMENDED -> filtered
            SortOption.RATING_HIGH -> filtered.sortedByDescending { it.optDouble("rating", 0.0) }
            SortOption.FEE_LOW -> filtered.sortedBy { it.optInt("fee", Int.MAX_VALUE) }
            SortOption.EXPERIENCE_HIGH -> filtered.sortedByDescending { it.optInt("experienceYears", 0) }
            SortOption.NAME_AZ -> filtered.sortedBy {
                it.optString("name", it.optString("full_name", "Doctor"))
                    .lowercase(Locale.getDefault())
            }
        }

        visibleDocs.clear()
        visibleDocs.addAll(sorted)
        adapter.notifyDataSetChanged()

        setCount(visibleDocs.size)

        if (allDocs.isEmpty() && q.isBlank()) {
            showEmptyInitial()
        } else if (visibleDocs.isEmpty() && q.isNotBlank()) {
            showEmptySearch(q)
        } else if (visibleDocs.isEmpty()) {
            showEmptyInitial()
        } else {
            hideEmpty()
        }
    }

    private fun showEmptyInitial() {
        rv.visibility = View.GONE
        cardEmpty.visibility = View.VISIBLE

        tvEmptyTitle.text = getString(R.string.empty_doctors_title)
        tvEmptyDesc.text = getString(R.string.empty_doctors_desc)

        (btnEmptyAction as? com.google.android.material.button.MaterialButton)?.apply {
            text = getString(R.string.go_back)
            setIconResource(R.drawable.ic_back)
        }
    }

    private fun showEmptySearch(query: String) {
        rv.visibility = View.GONE
        cardEmpty.visibility = View.VISIBLE

        tvEmptyTitle.text = getString(R.string.no_results_title)
        tvEmptyDesc.text = getString(R.string.no_results_desc, query)

        (btnEmptyAction as? com.google.android.material.button.MaterialButton)?.apply {
            text = getString(R.string.clear_search)
            setIconResource(R.drawable.ic_refresh)
        }
    }

    private fun hideEmpty() {
        cardEmpty.visibility = View.GONE
        rv.visibility = View.VISIBLE
    }

    private fun setCount(n: Int) {
        tvCount.text = getString(R.string.verified_doctors_available, n)
    }

    override fun onDestroy() {
        searchJob?.cancel()
        uiScope.cancel()
        super.onDestroy()
    }

    // ---------------- Adapter / VH ----------------

    private class DoctorAdapter(
        private val items: List<JSONObject>,
        val onClick: (Long) -> Unit
    ) : RecyclerView.Adapter<DoctorVH>() {
        override fun onCreateViewHolder(p: android.view.ViewGroup, v: Int): DoctorVH {
            val view = LayoutInflater.from(p.context).inflate(R.layout.item_doctor_card, p, false)
            return DoctorVH(view, onClick)
        }
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(h: DoctorVH, i: Int) = h.bind(items[i])
    }

    private class DoctorVH(v: View, private val onClick: (Long) -> Unit) : RecyclerView.ViewHolder(v) {
        private val tvName = v.findViewById<TextView>(R.id.tvName)
        private val tvSub = v.findViewById<TextView>(R.id.tvSub)
        private val tvRating = v.findViewById<TextView>(R.id.tvRating)
        private val tvLang = v.findViewById<TextView>(R.id.tvLang)
        private val tvFee = v.findViewById<TextView>(R.id.tvFee)
        private val tvAvail = v.findViewById<TextView>(R.id.tvAvail)

        fun bind(o: JSONObject) {
            val idLong = parseDoctorId(o)

            val name = o.optString("name", o.optString("full_name", "Doctor"))
            val spec = o.optString("specialization", o.optString("speciality", ""))
            val exp = o.optInt("experienceYears", o.optInt("experience", 0))
            val rating = o.optDouble("rating", 0.0)
            val langs = o.optString("languages", "")
            val fee = o.optInt("fee", 0)
            val avail = o.optString("availability", "Available Now")

            tvName.text = name
            tvSub.text = listOfNotNull(
                spec.takeIf { it.isNotBlank() },
                if (exp > 0) "$exp years" else null
            ).joinToString("  •  ")

            tvRating.text = if (rating > 0) String.format(Locale.getDefault(), "%.1f", rating) else "0.0"
            tvLang.text = langs
            tvFee.text = "₹$fee"
            tvAvail.text = avail

            itemView.isEnabled = idLong > 0
            itemView.alpha = if (idLong > 0) 1f else 0.5f
            itemView.setOnClickListener { if (idLong > 0) onClick(idLong) }
        }

        private fun parseDoctorId(o: JSONObject): Long {
            val candidates = listOf("doctor_id", "doctorId", "user_id", "userId", "id")
            for (k in candidates) {
                val v = o.opt(k)
                when (v) {
                    is Number -> {
                        val n = v.toLong()
                        if (n > 0) return n
                    }
                    is String -> {
                        val n = v.trim().toLongOrNull()
                        if (n != null && n > 0) return n
                    }
                }
            }
            val doc = o.optJSONObject("doctor")
            if (doc != null) return parseDoctorId(doc)
            return 0L
        }
    }

    companion object {
        const val EXTRA_SYMPTOMS_TEXT = "symptoms_text"
        const val EXTRA_AUTO_OPEN_FIRST = "auto_open_first"
        const val EXTRA_AUTO_DOCTOR_ID = "auto_doctor_id"
        const val EXTRA_AUTO_CONSULT_TYPE = "auto_consult_type"
        const val EXTRA_PREF_DATE = "pref_date_iso"
        const val EXTRA_PREF_TIME = "pref_time_24"
        const val EXTRA_AUTO_CONFIRM = "auto_confirm"
        private const val STATE_DID_AUTO_OPEN = "did_auto_open"
    }
}
