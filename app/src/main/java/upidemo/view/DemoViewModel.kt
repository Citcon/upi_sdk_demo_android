package upidemo.view

import android.app.Application
import android.widget.RadioGroup
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import citcon.cpay.R
import com.citconpay.sdk.data.model.*
import com.citconpay.sdk.data.model.CPayRequest.CPayUPIBuilder.accessToken
import com.citconpay.sdk.data.repository.CPayENVMode
import com.google.gson.GsonBuilder
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.apache.commons.lang3.RandomStringUtils
import org.json.JSONObject
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import upidemo.model.*
import java.util.*
import java.util.concurrent.TimeUnit


class DemoViewModel(application: Application) : AndroidViewModel(application) {
    val mAccessToken by lazy { MutableLiveData<String>() }
    val mChargeToken by lazy { MutableLiveData<String>() }
    private lateinit var mReference: String
    internal var mConsumerID: String = MainActivity.DEFAULT_CONSUMER_ID
    private var mChosePaymentMethod = CPayMethodType.PAYPAL
    internal var mIs3DS: Boolean = false
    var mAmount = "1"
    lateinit var mCallback : String
    val mCurrencyIndex = MutableLiveData(0)
    val mErrorMessage by lazy { MutableLiveData<ErrorMessage>() }
    var mCurrency = application.resources.getStringArray(R.array.currency_list)[0]!!

    object RetrofitClient {

        private const val BASE_URL = "https://api.qa01.citconpay.com/v1/"

        private val okHttpClient = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
            .build()

        private val retrofit: Retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(okHttpClient)
            .build()

        val apiService: CitconUPIAPIService = retrofit.create(CitconUPIAPIService::class.java)

    }

    fun getReference(): String {
        return mReference
    }

    fun setPaymentMethod(type: CPayMethodType) {
        mChosePaymentMethod = type
    }

    fun getPaymentMethod(): CPayMethodType {
        return mChosePaymentMethod
    }

    private fun handleErrorMsg(exception: HttpException): ErrorMessage {
        lateinit var errorMessage: ErrorMessage
        exception.response()?.let { response ->
            response.errorBody()?.let { errorMsg ->
                JSONObject(errorMsg.string()).let {
                    errorMessage = GsonBuilder().create().fromJson(
                        it.getJSONObject("data").toString(),
                        ErrorMessage::class.java
                    )
                }
            }
        }
        return errorMessage
    }

    fun getAccessToken(authType: String) {
        viewModelScope.launch {
            try {
                val responseAccessToken = RetrofitClient.apiService.getAccessToken(
                    "Bearer $authType", "application/json",
                    RequestAccessToken().setTokenType("client")
                )
                mAccessToken.postValue(responseAccessToken.data.access_token)
                mReference = RandomStringUtils.randomAlphanumeric(10)
            } catch (e: HttpException) {
                val errorMsg = handleErrorMsg(e)
                mAccessToken.postValue("Error: ${errorMsg.message} ( ${errorMsg.debug} )")
            }
        }
    }

    fun getChargeToken(accessToken: String) {
        viewModelScope.launch {
            try {
                val responseChargeToken = RetrofitClient.apiService.getChargeToken(
                    "Bearer $accessToken", "application/json",
                    RequestChargeToken(
                        Transaction(
                            mReference,
                            mAmount.toInt(),
                            mCurrency,
                            "US",
                            false,
                            "braintree test"
                        ), Urls(
                            "http://ipn.com",
                            mCallback,
                            "http://fail.com",
                            "http//mobile.com",
                            "http://cancel.com"
                        ), Ext(Device("", "172.0.0.1", ""))
                    )
                )
                mReference = responseChargeToken.data.reference
                mChargeToken.postValue(responseChargeToken.data.charge_token)
            } catch (e: HttpException) {
                mErrorMessage.postValue(handleErrorMsg(e))
            }
        }
    }

    /**
     * access token , charge token and consumer id are the mandatory parameters:
     * access token and charge token have to be downloaded from merchant Backend
     * consumer id is unique identity of this merchant for the consumer who are going to pay
     *
     * @param type is payment method type which was selected by user want to pay with
     */
    internal fun buildDropInRequest(type: CPayMethodType): CPayRequest {
        when (type) {
            CPayMethodType.ALI,CPayMethodType.UNIONPAY, CPayMethodType.WECHAT -> {
                return accessToken(mAccessToken.value!!)
                    .reference(mReference)
                    .customerID(mConsumerID)
                    .currency(mCurrency)
                    .amount(mAmount)
                    .callbackURL(mCallback)
                    .ipnURL("https://exampe.com/ipn")
                    .mobileURL("https://exampe.com/mobile")
                    .cancelURL("https://exampe.com/cancel")
                    .failURL("https://exampe.com/fail")
                    .setAllowDuplicate(true)
                    .paymentMethod(type)
                    .country(Locale.CANADA)
                    .build(CPayENVMode.QA)
            }
            else -> {
                return CPayRequest.PaymentBuilder
                .accessToken(mAccessToken.value!!)
                .chargeToken(mChargeToken.value!!)
                .reference(mReference)
                .customerID(mConsumerID)
                .request3DSecureVerification(mIs3DS)
                .threeDSecureRequest(demoThreeDSecureRequest())
                .paymentMethod(type)
                .build(CPayENVMode.QA)
            }
        }
    }

    private fun demoThreeDSecureRequest(): Citcon3DSecureRequest {
        val billingAddress = CPay3DSecurePostalAddress()
            .givenName("Jill")
            .surname("Doe")
            .phoneNumber("5551234567")
            .streetAddress("555 Smith St")
            .extendedAddress("#2")
            .locality("Chicago")
            .region("IL")
            .postalCode("12345")
            .countryCodeAlpha2("US")
        val additionalInformation = CPay3DSecureAdditionalInfo()
            .accountId("account-id")
        return Citcon3DSecureRequest()
            .amount("1.00")
            .versionRequested(Citcon3DSecureRequest.VERSION_2)
            .email("test@email.com")
            .mobilePhoneNumber("3125551234")
            .billingAddress(billingAddress)
            .additionalInformation(additionalInformation)
    }

    fun onPaymentTypeChanged(radioGroup: RadioGroup?, id: Int) {
        when (id) {
            R.id.radioButton_upop -> mChosePaymentMethod = CPayMethodType.UNIONPAY
            R.id.radioButton_wechat -> mChosePaymentMethod = CPayMethodType.WECHAT
            R.id.radioButton_alipay -> mChosePaymentMethod = CPayMethodType.ALI
            R.id.radioButton_paypal -> mChosePaymentMethod = CPayMethodType.PAYPAL
            R.id.radioButton_venmo -> mChosePaymentMethod = CPayMethodType.PAY_WITH_VENMO
            R.id.radioButton_credit -> mChosePaymentMethod = CPayMethodType.UNKNOWN
        }

    }

}