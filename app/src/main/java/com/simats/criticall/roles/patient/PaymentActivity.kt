package com.simats.criticall.roles.patient

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.razorpay.Checkout
import com.razorpay.PaymentResultListener
import com.simats.criticall.BaseActivity
import com.simats.criticall.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class PaymentActivity : BaseActivity(), PaymentResultListener {

    companion object {
        const val EXTRA_AMOUNT_INR = "amountInr"
        const val EXTRA_DOCTOR_NAME = "doctorName"
        const val EXTRA_DATE = "date"
        const val EXTRA_TIME = "time"
        const val EXTRA_CONSULT_TYPE = "consultTypeLabel"
        const val EXTRA_AUTO_OPEN = "auto_open_razorpay"

        const val EXTRA_PAYMENT_ID = "paymentId"
        const val EXTRA_PAYMENT_METHOD = "paymentMethod"

        // ✅ Razorpay TEST Key (hardcoded)
        private const val RZP_TEST_KEY_ID = "rzp_test_1DP5mmOlF5G5ag"
    }

    private var amountInr: Long = 0L
    private var doctorName: String = ""
    private var date: String = ""
    private var time: String = ""
    private var consultTypeLabel: String = ""

    private var checkout: Checkout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)
        supportActionBar?.hide()

        amountInr = intent.getLongExtra(EXTRA_AMOUNT_INR, 0L).coerceAtLeast(0L)
        doctorName = intent.getStringExtra(EXTRA_DOCTOR_NAME).orEmpty()
        date = intent.getStringExtra(EXTRA_DATE).orEmpty()
        time = intent.getStringExtra(EXTRA_TIME).orEmpty()
        consultTypeLabel = intent.getStringExtra(EXTRA_CONSULT_TYPE).orEmpty()

        // ✅ Razorpay init
        Checkout.preload(applicationContext)
        checkout = Checkout().apply { setKeyID(RZP_TEST_KEY_ID) }

        findViewById<ImageView>(R.id.ivBack).setOnClickListener { finish() }

        findViewById<TextView>(R.id.tvTitle).text = getString(R.string.payment_title)
        findViewById<TextView>(R.id.tvAmount).text = formatInr(amountInr)
        findViewById<TextView>(R.id.tvSecure).text = getString(R.string.secure_payment_note)

        val details = buildString {
            if (doctorName.isNotBlank()) append(doctorName).append(" • ")
            if (consultTypeLabel.isNotBlank()) append(consultTypeLabel).append(" • ")
            if (date.isNotBlank()) append(date).append(" • ")
            if (time.isNotBlank()) append(time)
        }.trim().trimEnd('•').trim()

        findViewById<TextView>(R.id.tvSummary).text =
            if (details.isNotBlank()) details else getString(R.string.appointment_summary)

        // ✅ Bill breakdown (Zomato / pharmacy style)
        val consultFee = amountInr
        val convenienceFee = 0L
        val taxes = 0L
        val total = consultFee + convenienceFee + taxes

        findViewById<TextView>(R.id.tvBillConsultValue).text = formatInr(consultFee)
        findViewById<TextView>(R.id.tvBillConvValue).text = formatInr(convenienceFee)
        findViewById<TextView>(R.id.tvBillTaxValue).text = formatInr(taxes)
        findViewById<TextView>(R.id.tvBillTotalValue).text = formatInr(total)

        val btnPay = findViewById<MaterialButton>(R.id.btnPay)
        btnPay.text = getString(R.string.pay_button_amount, formatInr(total))
        btnPay.setOnClickListener { onPayClicked(total) }

        // Agent flow: auto-open Razorpay after screen renders
        if (intent.getBooleanExtra(EXTRA_AUTO_OPEN, false)) {
            btnPay.postDelayed({ btnPay.performClick() }, 220)
        }
    }

    private fun onPayClicked(totalInr: Long) {
        if (totalInr <= 0L) {
            finishOk("PAY-FREE-${System.currentTimeMillis()}", "RAZORPAY")
            return
        }

        val btnPay = findViewById<MaterialButton>(R.id.btnPay)
        val overlay = findViewById<View>(R.id.loadingOverlay)

        btnPay.isEnabled = false
        overlay.isVisible = true

        lifecycleScope.launch {
            delay(150)

            runCatching {
                overlay.isVisible = false

                val options = JSONObject()
                options.put("key", RZP_TEST_KEY_ID)
                options.put("currency", "INR")
                options.put("amount", totalInr * 100L) // paise
                options.put("name", "criticall")

                val desc = buildString {
                    if (doctorName.isNotBlank()) append(doctorName).append(" ")
                    if (consultTypeLabel.isNotBlank()) append("• ").append(consultTypeLabel).append(" ")
                    if (date.isNotBlank()) append("• ").append(date).append(" ")
                    if (time.isNotBlank()) append("• ").append(time)
                }.trim()

                options.put(
                    "description",
                    if (desc.isNotBlank()) desc else getString(R.string.appointment_summary)
                )

                // ✅ Razorpay will show UPI/Card/Netbanking itself (no manual fields here)
                val prefill = JSONObject()
                options.put("prefill", prefill)

                val retry = JSONObject()
                retry.put("enabled", true)
                retry.put("max_count", 1)
                options.put("retry", retry)

                val theme = JSONObject()
                theme.put("color", "#0F766E")
                options.put("theme", theme)

                checkout?.open(this@PaymentActivity, options)
            }.onFailure { e ->
                overlay.isVisible = false
                btnPay.isEnabled = true
                Toast.makeText(
                    this@PaymentActivity,
                    "${getString(R.string.payment_title)}: ${e.message.orEmpty()}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ✅ Razorpay callbacks
    override fun onPaymentSuccess(razorpayPaymentId: String?) {
        val btnPay = findViewById<MaterialButton>(R.id.btnPay)
        val overlay = findViewById<View>(R.id.loadingOverlay)

        overlay.isVisible = false
        btnPay.isEnabled = true

        vibrateSuccess()
        Toast.makeText(this, getString(R.string.payment_success), Toast.LENGTH_SHORT).show()

        val pid = razorpayPaymentId?.takeIf { it.isNotBlank() } ?: "PAY-${System.currentTimeMillis()}"
        finishOk(pid, "RAZORPAY")
    }

    override fun onPaymentError(code: Int, response: String?) {
        val btnPay = findViewById<MaterialButton>(R.id.btnPay)
        val overlay = findViewById<View>(R.id.loadingOverlay)

        overlay.isVisible = false
        btnPay.isEnabled = true

        val msg = runCatching {
            val r = response.orEmpty()
            if (r.startsWith("{")) {
                val jo = JSONObject(r)
                jo.optJSONObject("error")?.optString("description")
                    ?.takeIf { it.isNotBlank() }
                    ?: jo.optString("message").takeIf { it.isNotBlank() }
                    ?: r
            } else r
        }.getOrElse { response.orEmpty() }

        Toast.makeText(
            this,
            "${getString(R.string.payment_title)}: $msg",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun finishOk(paymentId: String, method: String) {
        val data = Intent()
        data.putExtra(EXTRA_PAYMENT_ID, paymentId)
        data.putExtra(EXTRA_PAYMENT_METHOD, method)
        setResult(RESULT_OK, data)
        finish()
    }

    private fun formatInr(amount: Long): String = "₹$amount"

    private fun vibrateSuccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
                @Suppress("DEPRECATION")
                v.vibrate(60)
            }
        } catch (_: Throwable) { }
    }
}
