package com.omran.caption

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.omran.caption.databinding.ActivityPaywallBinding

class PaywallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPaywallBinding
    private lateinit var billing: BillingManager
    private var selectedSku: String = BillingManager.SKU_YEARLY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaywallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        highlightSelected()

        binding.cardMonthly.setOnClickListener {
            selectedSku = BillingManager.SKU_MONTHLY
            highlightSelected()
        }
        binding.cardYearly.setOnClickListener {
            selectedSku = BillingManager.SKU_YEARLY
            highlightSelected()
        }

        binding.btnClosePaywall.setOnClickListener { finish() }

        binding.btnSubscribeNow.setOnClickListener {
            billing.launchPurchase(this, selectedSku)
        }

        binding.tvRestore.setOnClickListener {
            billing.restorePurchases()
            Toast.makeText(this, "جاري التحقق من مشترياتك السابقة…", Toast.LENGTH_SHORT).show()
        }

        billing = BillingManager(
            context = this,
            onPremiumStatusChanged = { isPremium ->
                runOnUiThread {
                    if (isPremium) {
                        Toast.makeText(this, "🎉 مبروك! اشتراكك مفعّل الآن", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            },
            onError = { msg ->
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            }
        )
        billing.start {
            runOnUiThread {
                billing.getMonthlyPriceText()?.let { binding.tvMonthlyPrice.text = "$it / شهر" }
                billing.getYearlyPriceText()?.let { binding.tvYearlyPrice.text = "$it / سنة" }
            }
        }
    }

    private fun highlightSelected() {
        if (selectedSku == BillingManager.SKU_MONTHLY) {
            binding.cardMonthly.setBackgroundResource(R.drawable.plan_card_selected_bg)
            binding.cardYearly.setBackgroundResource(R.drawable.plan_card_bg)
        } else {
            binding.cardYearly.setBackgroundResource(R.drawable.plan_card_selected_bg)
            binding.cardMonthly.setBackgroundResource(R.drawable.plan_card_bg)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        billing.endConnection()
    }
}
