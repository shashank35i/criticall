package com.simats.criticall

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Patterns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CreateAccountActivity : BaseActivity() {

    private lateinit var role: Role

    private lateinit var tvRolePill: TextView
    private lateinit var etFullname: EditText
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var etConfirm: EditText
    private lateinit var btnCreate: AppCompatButton
    private lateinit var tvSignin: TextView
    private lateinit var btnBack: ImageView

    private lateinit var progressOverlay: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_account)
        supportActionBar?.hide()

        role = RoleResolver.resolve(this, savedInstanceState)
        RoleResolver.persist(this, role)

        tvRolePill = findViewById(R.id.tv_role_pill)
        etFullname = findViewById(R.id.et_fullname)
        etEmail = findViewById(R.id.et_email)
        etPassword = findViewById(R.id.et_password)
        etConfirm = findViewById(R.id.et_confirm_password)
        btnCreate = findViewById(R.id.btn_create_account)
        tvSignin = findViewById(R.id.tv_signin)
        btnBack = findViewById(R.id.btn_back)

        tvRolePill.text = getString(R.string.signup_role_pill_format, roleLabel(role))

        makeReadable(etFullname)
        makeReadable(etEmail)
        makeReadable(etPassword)
        makeReadable(etConfirm)

        setupLoadingOverlay()

        btnBack.setOnClickListener { goLoginSameRole() }
        tvSignin.setOnClickListener { goLoginSameRole() }

        btnCreate.isEnabled = true
        btnCreate.alpha = 1f
        btnCreate.setOnClickListener { createAccount() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(NavKeys.EXTRA_ROLE, role.id)
        super.onSaveInstanceState(outState)
    }

    private fun validateSignupFieldsOrShow(): Triple<String, String, String>? {
        val fullName = etFullname.text?.toString()?.trim().orEmpty()
        val pass = etPassword.text?.toString()?.trim().orEmpty()
        val confirm = etConfirm.text?.toString()?.trim().orEmpty()

        if (fullName.isBlank()) {
            toastBL(getString(R.string.err_fullname_required), isError = true)
            return null
        }
        if (pass.length < 6) {
            toastBL(getString(R.string.err_password_min), isError = true)
            return null
        }
        if (pass != confirm) {
            toastBL(getString(R.string.err_password_mismatch), isError = true)
            return null
        }
        return Triple(fullName, pass, confirm)
    }

    private fun setupLoadingOverlay() {
        val root = findViewById<View>(android.R.id.content) as ViewGroup

        progressOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true

            val spinner = CircularProgressIndicator(this@CreateAccountActivity).apply {
                isIndeterminate = true
                indicatorSize = dp(36)
                trackThickness = dp(3)
                setIndicatorColor(Color.parseColor("#059669"))
                trackColor = 0xFFE2E8F0.toInt()
            }

            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }

            addView(spinner, lp)
        }

        root.addView(
            progressOverlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun setLoading(loading: Boolean) {
        if (loading) {
            progressOverlay.visibility = View.VISIBLE
            progressOverlay.animate().cancel()
            progressOverlay.animate().alpha(1f).setDuration(120).start()
        } else {
            progressOverlay.animate().cancel()
            progressOverlay.animate().alpha(0f).setDuration(120).withEndAction {
                progressOverlay.visibility = View.GONE
            }.start()
        }

        etFullname.isEnabled = !loading
        etEmail.isEnabled = !loading
        etPassword.isEnabled = !loading
        etConfirm.isEnabled = !loading
        tvSignin.isEnabled = !loading
        btnBack.isEnabled = !loading

        btnCreate.isEnabled = !loading
        btnCreate.alpha = if (btnCreate.isEnabled) 1f else 0.6f
    }

    private fun toastBL(message: String, isError: Boolean = false) {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (isError) 0xFF111827.toInt() else 0xFF0F172A.toInt())
        }

        val tv = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = bg
            maxLines = 3
            gravity = Gravity.CENTER
        }

        Toast(this).apply {
            duration = Toast.LENGTH_SHORT
            view = tv
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(90))
        }.show()
    }

    private fun createAccount() {
        val email = etEmail.text?.toString()?.trim().orEmpty()

        if (email.isBlank()) {
            toastBL(getString(R.string.err_email_required), isError = true)
            return
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            toastBL(getString(R.string.err_email_invalid), isError = true)
            return
        }

        val fields = validateSignupFieldsOrShow() ?: return
        val (fullName, pass, _) = fields

        setLoading(true)
        lifecycleScope.launch {

            // 1) Create user directly (OTP handled via email flow, no in-app verification)
            val createRes = withContext(Dispatchers.IO) {
                AuthApi.registerCreateAccount(fullName, email, pass, role)
            }

            val createJson = createRes.json
            val serverMessage = createJson?.optString("message")?.orEmpty() ?: createJson?.optString("error")
            val otpBypass = serverMessage?.contains("otp", ignoreCase = true) == true
            val createdOk = createRes.ok && (createJson?.optBoolean("ok", false) == true) || otpBypass
            if (!createdOk) {
                setLoading(false)
                val msg =
                    createJson?.optString("error")?.takeIf { it.isNotBlank() }
                        ?: createJson?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: createRes.errorMessage
                        ?: getString(R.string.err_create_account_failed)
                toastBL(msg, isError = true)
                return@launch
            }

            // 2) Auto-login
            val loginRes = withContext(Dispatchers.IO) { AuthApi.login(email, pass, role) }
            val token = loginRes.json?.optString("token")?.trim().orEmpty()

            if (!(loginRes.ok && token.isNotBlank())) {
                setLoading(false)
                toastBL(getString(R.string.account_created_signin), isError = false)
                goLoginSameRole(prefillEmail = email)
                return@launch
            }

            //  Save token/session
            AppPrefs.setToken(this@CreateAccountActivity, token)
            RoleResolver.persist(this@CreateAccountActivity, role)

            //  At signup everyone starts PENDING + profile not completed
            AppPrefs.setAdminVerificationStatus(this@CreateAccountActivity, "PENDING")
            AppPrefs.setAdminVerificationReason(this@CreateAccountActivity, null)
            AppPrefs.setProfileCompleted(this@CreateAccountActivity, false)

            AppPrefs.clearDoctorApplicationNo(this@CreateAccountActivity)
            AppPrefs.clearPharmacistApplicationNo(this@CreateAccountActivity)

            //  Navigate: PATIENT goes to PatientRegistration
            val next = when (role) {
                Role.DOCTOR -> Intent(this@CreateAccountActivity, DoctorRegistrationActivity::class.java)
                Role.PHARMACIST -> Intent(this@CreateAccountActivity, PharmacistRegistrationActivity::class.java)
                Role.PATIENT -> Intent(this@CreateAccountActivity, PatientRegistrationActivity::class.java)
                Role.ADMIN -> Intent(this@CreateAccountActivity, com.simats.criticall.roles.admin.AdminActivity::class.java)
            }.also { RoleResolver.putRole(it, role) }

            setLoading(false)
            goNext(next, finishThis = true)
        }
    }


    private fun goLoginSameRole(prefillEmail: String? = null) {
        val i = RoleResolver.putRole(Intent(this, LoginActivity::class.java), role).apply {
            if (!prefillEmail.isNullOrBlank()) putExtra(LoginActivity.EXTRA_EMAIL_PREFILL, prefillEmail)
        }
        goNext(i, finishThis = true)
    }

    private fun roleLabel(role: Role): String = when (role) {
        Role.PATIENT -> getString(R.string.role_patient)
        Role.DOCTOR -> getString(R.string.role_doctor)
        Role.PHARMACIST -> getString(R.string.role_pharmacist)
        Role.ADMIN -> getString(R.string.role_admin)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeReadable(et: EditText) {
        et.setTextColor(0xFF0F172A.toInt())
        et.setHintTextColor(0xFF94A3B8.toInt())
    }
}
