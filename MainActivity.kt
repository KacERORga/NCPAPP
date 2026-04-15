package com.ncpbank.app

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {
    
    private lateinit var webView: WebView
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var nfcIntentFilters: Array<IntentFilter>? = null
    
    private var currentPaymentAmount: Double = 0.0
    private var currentPaymentCallback: String = ""
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        setupWebView()
        setupNFC()
    }
    
    private fun setupWebView() {
        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Инжектим JavaScript для NFC
                injectNfcInterface()
            }
            
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                url?.let {
                    if (it.startsWith("ncpbank://")) {
                        handleDeepLink(it)
                        return true
                    }
                }
                return super.shouldOverrideUrlLoading(view, url)
            }
        }
        
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                // Можно показать прогресс загрузки
            }
        }
        
        // Загрузка твоего сайта
        webView.loadUrl("https://ncpapp.vercel.app/")
    }
    
    private fun injectNfcInterface() {
        // Добавляем JavaScript интерфейс для вызова из WebView
        webView.addJavascriptInterface(NfcBridge(), "NfcPay")
    }
    
    private fun setupNFC() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC не поддерживается на этом устройстве", Toast.LENGTH_LONG).show()
            return
        }
        
        if (!nfcAdapter!!.isEnabled) {
            Toast.makeText(this, "Пожалуйста, включите NFC в настройках", Toast.LENGTH_LONG).show()
        }
        
        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
        
        val techLists = arrayOf(
            arrayOf("android.nfc.tech.IsoDep"),
            arrayOf("android.nfc.tech.NfcA"),
            arrayOf("android.nfc.tech.NfcB"),
            arrayOf("android.nfc.tech.NfcF"),
            arrayOf("android.nfc.tech.NfcV"),
            arrayOf("android.nfc.tech.MifareClassic"),
            arrayOf("android.nfc.tech.MifareUltralight"),
            arrayOf("android.nfc.tech.Ndef")
        )
        
        nfcIntentFilters = arrayOf(
            IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        )
    }
    
    override fun onResume() {
        super.onResume()
        nfcAdapter?.let {
            if (it.isEnabled) {
                pendingIntent?.let { intent ->
                    it.enableForegroundDispatch(this, intent, nfcIntentFilters, null)
                }
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }
    
    private fun handleNfcIntent(intent: Intent) {
        when (intent.action) {
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_NDEF_DISCOVERED,
            NfcAdapter.ACTION_TAG_DISCOVERED -> {
                val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
                tag?.let {
                    processNfcPayment(it)
                }
            }
        }
    }
    
    private fun processNfcPayment(tag: Tag) {
        if (currentPaymentAmount <= 0) {
            sendNfcCallback(false, "Сумма не указана")
            return
        }
        
        // Здесь должна быть логика реальной оплаты через NFC
        // Например, чтение данных с карты и списание средств
        // Для демонстрации имитируем успешную оплату
        
        Toast.makeText(this, "Обработка NFC оплаты...", Toast.LENGTH_SHORT).show()
        
        // Имитация задержки оплаты
        Handler(Looper.getMainLooper()).postDelayed({
            // Генерируем ID транзакции
            val transactionId = "NCP_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
            
            // Успешная оплата
            sendNfcCallback(true, transactionId)
            Toast.makeText(this, "✅ Оплата прошла успешно! Сумма: ${currentPaymentAmount} ₽", Toast.LENGTH_LONG).show()
            
            currentPaymentAmount = 0.0
        }, 1500)
        
        // Реальная реализация потребовала бы:
        // 1. Установление соединения с картой через IsoDep
        // 2. Аутентификация
        // 3. Чтение/запись данных
        // 4. Списание средств через платёжный шлюз
    }
    
    private fun sendNfcCallback(success: Boolean, result: String) {
        val jsonResult = JSONObject().apply {
            put("success", success)
            if (success) {
                put("transactionId", result)
            } else {
                put("error", result)
            }
        }
        
        // Вызываем JavaScript колбэк в WebView
        webView.evaluateJavascript(
            "window.NfcPayCallback && window.NfcPayCallback($jsonResult)",
            null
        )
    }
    
    private fun handleDeepLink(url: String) {
        // Обработка глубоких ссылок ncpbank://...
        Log.d("NCPBank", "Deep link: $url")
    }
    
    // JavaScript интерфейс для вызова из WebView
    inner class NfcBridge {
        @JavascriptInterface
        fun startPayment(amount: String, data: String) {
            runOnUiThread {
                try {
                    currentPaymentAmount = amount.toDouble()
                    Log.d("NCPBank", "NFC оплата на сумму: $currentPaymentAmount")
                    
                    Toast.makeText(
                        this@MainActivity,
                        "💳 Приложите карту к телефону для оплаты $amount ₽",
                        Toast.LENGTH_LONG
                    ).show()
                    
                    // Отправляем сообщение в WebView о готовности
                    webView.evaluateJavascript(
                        "window.updateNfcStatus && window.updateNfcStatus('ready', 'Приложите карту')",
                        null
                    )
                } catch (e: Exception) {
                    sendNfcCallback(false, "Ошибка: ${e.message}")
                }
            }
        }
        
        @JavascriptInterface
        fun cancelPayment() {
            runOnUiThread {
                currentPaymentAmount = 0.0
                Toast.makeText(this@MainActivity, "Оплата отменена", Toast.LENGTH_SHORT).show()
            }
        }
        
        @JavascriptInterface
        fun isNfcAvailable(): Boolean {
            return nfcAdapter != null && nfcAdapter!!.isEnabled
        }
    }
}
