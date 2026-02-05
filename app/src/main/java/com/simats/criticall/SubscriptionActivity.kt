package com.simats.criticall.ui.subscription

import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.android.billingclient.api.*
import com.google.android.material.button.MaterialButton
import com.simats.criticall.R

class SubscriptionActivity : AppCompatActivity(), PurchasesUpdatedListener {

    private lateinit var ivBack: ImageView
    private lateinit var tvCredits: TextView
    private lateinit var tvCreditsSub: TextView
    private lateinit var progressCredits: ProgressBar
    private lateinit var btnSubscribe: MaterialButton
    private lateinit var btnRestore: MaterialButton
    private lateinit var btnSkip: MaterialButton

    private lateinit var billingClient: BillingClient
    private var productDetails: ProductDetails? = null

    companion object {
        private const val TAG = "SubscriptionActivity"
        private const val REAL_SUBSCRIPTION_ID = "myapp_premium_subscription"
        const val EXTRA_FROM = "from"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)
        supportActionBar?.hide()

        ivBack = findViewById(R.id.ivBack)
        tvCredits = findViewById(R.id.tvCredits)
        tvCreditsSub = findViewById(R.id.tvCreditsSub)
        progressCredits = findViewById(R.id.progressCredits)
        btnSubscribe = findViewById(R.id.btnSubscribe)
        btnRestore = findViewById(R.id.btnRestore)
        btnSkip = findViewById(R.id.btnSkipForNow)

        ivBack.setOnClickListener { finish() }
        btnSkip.setOnClickListener { finish() }

        btnSubscribe.setOnClickListener { launchSubscriptionFlow() }
        btnRestore.setOnClickListener { restorePurchases() }

        renderCredits()
        setupBillingClient()
    }

    private fun renderCredits() {
        val isSub = SubscriptionGate.isSubscribed(this)
        if (isSub) {
            tvCredits.text = getString(R.string.ai_credits_unlimited)
            tvCreditsSub.text = getString(R.string.ai_credits_unlimited_sub)
            progressCredits.max = 100
            progressCredits.progress = 100
            btnSubscribe.isEnabled = false
            btnSubscribe.alpha = 0.7f
            return
        }

        val remaining = SubscriptionGate.remainingUses(this)
        val max = 5
        val used = (max - remaining).coerceAtLeast(0)

        tvCredits.text = getString(R.string.ai_credits_title)
        tvCreditsSub.text = getString(R.string.ai_credits_remaining_fmt, remaining, max)
        progressCredits.max = max
        progressCredits.progress = used.coerceIn(0, max)
    }

    private fun setupBillingClient() {
        billingClient = BillingClient.newBuilder(this)
            .setListener(this)
            .enablePendingPurchases()
            .build()

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing setup completed")
                    querySubscriptionDetails()
                    restorePurchases()
                } else {
                    Log.e(TAG, "Billing setup failed: ${result.debugMessage}")
                    toast(getString(R.string.billing_setup_failed))
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected")
            }
        })
    }

    private fun querySubscriptionDetails() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(REAL_SUBSCRIPTION_ID)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { result, list ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && list.isNotEmpty()) {
                productDetails = list[0]
                btnSubscribe.isEnabled = true
                btnSubscribe.alpha = 1f
            } else {
                productDetails = null
                btnSubscribe.isEnabled = false
                btnSubscribe.alpha = 0.7f
                Log.e(TAG, "Failed to get product details: ${result.debugMessage}")
                toast(getString(R.string.subscription_not_available))
            }
        }
    }

    private fun restorePurchases() {
        if (!::billingClient.isInitialized) return
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val active = purchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                if (active) {
                    SubscriptionGate.setSubscribed(this, true)
                    purchases.forEach { acknowledgeIfNeeded(it) }
                    renderCredits()
                    toast(getString(R.string.restore_success))
                } else {
                    toast(getString(R.string.restore_none))
                }
            } else {
                toast(getString(R.string.restore_failed))
            }
        }
    }

    private fun launchSubscriptionFlow() {
        val details = productDetails ?: run {
            toast(getString(R.string.product_not_loaded))
            return
        }

        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (offerToken.isNullOrBlank()) {
            toast(getString(R.string.no_offer_available))
            return
        }

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()

        billingClient.launchBillingFlow(this, params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases.isNullOrEmpty()) {
                    toast(getString(R.string.purchase_failed_generic))
                    return
                }
                purchases.forEach { handlePurchase(it) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                toast(getString(R.string.purchase_cancelled))
            }
            else -> {
                toast(getString(R.string.purchase_failed_fmt, result.debugMessage))
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        SubscriptionGate.setSubscribed(this, true)
        acknowledgeIfNeeded(purchase)
        renderCredits()

        toast(getString(R.string.subscription_activated))

        val from = intent.getStringExtra(EXTRA_FROM).orEmpty()
        if (from.isNotBlank()) {
            finish()
        } else {
            finish()
        }
    }

    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "Purchase acknowledged")
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
