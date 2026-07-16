package com.omran.caption

import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClient.SkuType

/**
 * Wraps Google Play Billing for the two subscription products:
 *  - omran_caption_monthly  ($7.99/month)
 *  - omran_caption_yearly   ($59.99/year)
 *
 * NOTE: these product IDs must be created in Play Console → Monetize → Products → Subscriptions
 * before purchases will succeed. Until then, launchBilling will fail gracefully with a toast.
 */
class BillingManager(
    private val context: Context,
    private val onPremiumStatusChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit
) : PurchasesUpdatedListener {

    companion object {
        const val SKU_MONTHLY = "omran_caption_monthly"
        const val SKU_YEARLY = "omran_caption_yearly"
        private const val TAG = "BillingManager"
    }

    private var monthlyProduct: ProductDetails? = null
    private var yearlyProduct: ProductDetails? = null

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    fun start(onReady: () -> Unit) {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProducts()
                    restorePurchases()
                    onReady()
                } else {
                    onError("تعذّر الاتصال بمتجر Google Play")
                }
            }
            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected")
            }
        })
    }

    private fun queryProducts() {
        val products = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SKU_MONTHLY).setProductType(ProductType.SUBS).build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SKU_YEARLY).setProductType(ProductType.SUBS).build()
        )
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        billingClient.queryProductDetailsAsync(params) { result, list ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                for (pd in list) {
                    if (pd.productId == SKU_MONTHLY) monthlyProduct = pd
                    if (pd.productId == SKU_YEARLY) yearlyProduct = pd
                }
            }
        }
    }

    fun getMonthlyPriceText(): String? =
        monthlyProduct?.subscriptionOfferDetails?.firstOrNull()
            ?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice

    fun getYearlyPriceText(): String? =
        yearlyProduct?.subscriptionOfferDetails?.firstOrNull()
            ?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice

    fun launchPurchase(activity: android.app.Activity, sku: String) {
        val product = if (sku == SKU_MONTHLY) monthlyProduct else yearlyProduct
        if (product == null) {
            onError("خطة الاشتراك غير متوفرة حاليًا، حاول لاحقًا")
            return
        }
        val offerToken = product.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (offerToken == null) {
            onError("لا يوجد عرض متاح لهذه الخطة")
            return
        }
        val paramsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(product)
                .setOfferToken(offerToken)
                .build()
        )
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(paramsList)
            .build()
        billingClient.launchBillingFlow(activity, flowParams)
    }

    fun restorePurchases() {
        val params = QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val active = purchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                PremiumManager.setPremium(context, active)
                onPremiumStatusChanged(active)
                for (purchase in purchases) {
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
                        acknowledge(purchase)
                    }
                }
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    PremiumManager.setPremium(context, true)
                    onPremiumStatusChanged(true)
                    if (!purchase.isAcknowledged) acknowledge(purchase)
                }
            }
        } else if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            // silent
        } else {
            onError("تعذّر إتمام عملية الشراء")
        }
    }

    private fun acknowledge(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { }
    }

    fun endConnection() {
        billingClient.endConnection()
    }
}
