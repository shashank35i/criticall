package com.simats.criticall.roles.patient

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.simats.criticall.BaseActivity
import com.simats.criticall.R
import org.json.JSONObject
import kotlin.math.floor

class SelectSpecialityActivity : BaseActivity() {

    private val items = ArrayList<JSONObject>()
    private lateinit var rv: RecyclerView
    private var selectedIndex = 0

    companion object {
        const val EXTRA_SPECIALITY_KEY = "specialityKey"
        const val EXTRA_SPECIALITY_LABEL = "specialityLabel"
        const val EXTRA_AUTO_SELECT_KEY = "auto_select_key"
        const val EXTRA_AUTO_CONTINUE = "auto_continue"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select_speciality)

        findViewById<View>(R.id.ivBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        supportActionBar?.hide()
        // stable key + UI label
        items.clear()
        items.add(spec("GENERAL_PHYSICIAN", getString(R.string.speciality_general), "🩺", "#DFF7EA", "#059669"))
        items.add(spec("CARDIOLOGY", getString(R.string.speciality_heart), "❤️", "#FDE2E2", "#DC2626"))
        items.add(spec("NEUROLOGY", getString(R.string.speciality_brain), "🧠", "#EDE3FF", "#7C3AED"))
        items.add(spec("ORTHOPEDICS", getString(R.string.speciality_bones), "🦴", "#FEF3C7", "#D97706"))
        items.add(spec("OPHTHALMOLOGY", getString(R.string.speciality_eyes), "👁️", "#DBEAFE", "#2563EB"))
        items.add(spec("PEDIATRICS", getString(R.string.speciality_child), "👶", "#FCE7F3", "#DB2777"))
        items.add(spec("DERMATOLOGY", getString(R.string.speciality_skin), "🙂", "#FDEAD7", "#EA580C"))
        items.add(spec("PULMONOLOGY", getString(R.string.speciality_lungs), "🫁", "#D9F9FF", "#0891B2"))
        items.add(spec("DIABETOLOGY", getString(R.string.speciality_diabetes), "💉", "#E0E7FF", "#4F46E5"))
        items.add(spec("FEVER_CLINIC", getString(R.string.speciality_fever), "🌡️", "#FCE7F3", "#E11D48"))
        items.add(spec("GENERAL_MEDICINE", getString(R.string.speciality_medicine), "💊", "#DFF7EA", "#059669"))
        items.add(spec("EMERGENCY", getString(R.string.speciality_emergency), "📈", "#FDE2E2", "#DC2626"))

        rv = findViewById(R.id.rvSpecialities)

        //  Choose span so text NEVER gets cut.
        val span = chooseBestSpanCount(
            maxSpan = 3,
            minSpan = 1,
            itemMinWidthDp = 150f // if card gets narrower than this, go 2 or 1
        )

        rv.layoutManager = GridLayoutManager(this, span)
        rv.addItemDecoration(SpacingDecoration(dp(10)))

        val adapter = SpecAdapter(
            items = items,
            getSelectedIndex = { selectedIndex },
            onSelect = { idx ->
                selectedIndex = idx
                rv.adapter?.notifyDataSetChanged()
            }
        )
        rv.adapter = adapter

        findViewById<View>(R.id.btnFind).setOnClickListener {
            val o = items.getOrNull(selectedIndex) ?: return@setOnClickListener
            val key = o.optString("key").orEmpty()
            val label = o.optString("label").orEmpty()
            if (key.isBlank() || label.isBlank()) return@setOnClickListener

            val itn = Intent(this, PatientDoctorListActivity::class.java)
            itn.putExtra(EXTRA_SPECIALITY_KEY, key)
            itn.putExtra(EXTRA_SPECIALITY_LABEL, label)
            // pass-through auto flow extras if present
            intent.getLongExtra(PatientDoctorListActivity.EXTRA_AUTO_DOCTOR_ID, 0L)
                .takeIf { it > 0 }?.let { itn.putExtra(PatientDoctorListActivity.EXTRA_AUTO_DOCTOR_ID, it) }
            intent.getStringExtra(PatientDoctorListActivity.EXTRA_AUTO_CONSULT_TYPE)
                ?.let { itn.putExtra(PatientDoctorListActivity.EXTRA_AUTO_CONSULT_TYPE, it) }
            intent.getStringExtra(PatientDoctorListActivity.EXTRA_PREF_DATE)
                ?.let { itn.putExtra(PatientDoctorListActivity.EXTRA_PREF_DATE, it) }
            intent.getStringExtra(PatientDoctorListActivity.EXTRA_PREF_TIME)
                ?.let { itn.putExtra(PatientDoctorListActivity.EXTRA_PREF_TIME, it) }
            if (intent.getBooleanExtra(PatientDoctorListActivity.EXTRA_AUTO_CONFIRM, false)) {
                itn.putExtra(PatientDoctorListActivity.EXTRA_AUTO_CONFIRM, true)
            }
            if (intent.getBooleanExtra(PatientDoctorListActivity.EXTRA_AUTO_OPEN_FIRST, false)) {
                itn.putExtra(PatientDoctorListActivity.EXTRA_AUTO_OPEN_FIRST, true)
            }
            startActivity(itn)
        }

        // Auto-select + auto-continue for agent flow
        val autoKey = intent.getStringExtra(EXTRA_AUTO_SELECT_KEY).orEmpty()
        val autoContinue = intent.getBooleanExtra(EXTRA_AUTO_CONTINUE, false)
        if (autoKey.isNotBlank()) {
            val idx = items.indexOfFirst { it.optString("key") == autoKey }
            if (idx >= 0) {
                selectedIndex = idx
                adapter.notifyDataSetChanged()
                if (autoContinue) {
                    rv.postDelayed({
                        findViewById<View>(R.id.btnFind)?.performClick()
                    }, 250)
                }
            }
        }
    }

    /**
     * Ensures each grid item has enough width so the label can be fully displayed in ONE line.
     * We pick 3 / 2 / 1 columns based on actual screen width.
     */
    private fun chooseBestSpanCount(maxSpan: Int, minSpan: Int, itemMinWidthDp: Float): Int {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density

        // Your RecyclerView has start/end margin 18dp + 18dp in activity XML
        val rvSideMarginsDp = 36f

        // SpacingDecoration adds (space/2) left + (space/2) right per item -> total ~space
        val spaceDp = 10f

        // Try from maxSpan down to minSpan and pick first that gives enough item width
        for (span in maxSpan downTo minSpan) {
            val totalSpacing = (span * spaceDp) // rough but safe
            val usable = widthDp - rvSideMarginsDp - totalSpacing
            val itemWidth = usable / span
            if (itemWidth >= itemMinWidthDp) return span
        }
        return minSpan
    }

    private fun spec(key: String, label: String, icon: String, bg: String, fg: String): JSONObject {
        return JSONObject().apply {
            put("key", key)
            put("label", label)
            put("icon", icon)
            put("bg", bg)
            put("fg", fg)
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private class SpacingDecoration(private val space: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            outRect.left = space / 2
            outRect.right = space / 2
            outRect.top = space / 2
            outRect.bottom = space / 2
        }
    }

    private class SpecAdapter(
        private val items: List<JSONObject>,
        private val getSelectedIndex: () -> Int,
        private val onSelect: (Int) -> Unit
    ) : RecyclerView.Adapter<SpecVH>() {

        override fun onCreateViewHolder(p: android.view.ViewGroup, v: Int): SpecVH {
            val view = LayoutInflater.from(p.context).inflate(R.layout.item_speciality, p, false)
            return SpecVH(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: SpecVH, i: Int) {
            val o = items[i]
            val label = o.optString("label", "")
            val icon = o.optString("icon", "✚")
            val bg = o.optString("bg", "#E8F1FF")
            val fg = o.optString("fg", "#2563EB")
            val selected = (i == getSelectedIndex())

            h.tvName.text = label
            h.tvIcon.text = icon

            h.boxIcon.setCardBackgroundColor(Color.parseColor(bg))
            h.tvIcon.setTextColor(Color.parseColor(fg))

            if (selected) {
                h.card.strokeWidth = h.dp(2)
                h.card.setStrokeColor(Color.parseColor("#059669"))
                h.card.setCardBackgroundColor(Color.parseColor("#F2FFFA"))
                h.tvName.setTextColor(Color.parseColor("#0F172A"))
            } else {
                h.card.strokeWidth = h.dp(1)
                h.card.setStrokeColor(Color.parseColor("#E5EAF0"))
                h.card.setCardBackgroundColor(Color.parseColor("#FFFFFF"))
                h.tvName.setTextColor(Color.parseColor("#334155"))
            }

            h.itemView.setOnClickListener { onSelect(i) }
        }
    }

    private class SpecVH(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView = v.findViewById(R.id.cardSpec)
        val boxIcon: MaterialCardView = v.findViewById(R.id.boxIcon)
        val tvIcon: TextView = v.findViewById(R.id.tvIcon)
        val tvName: TextView = v.findViewById(R.id.tvSpecName)

        fun dp(x: Int): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x.toFloat(), itemView.resources.displayMetrics).toInt()
    }
}
