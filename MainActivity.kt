package com.ncpbank.app

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset

class MainActivity : AppCompatActivity() {
    
    private lateinit var webView: WebView
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    
    // Данные для P2P перевода
    private var isWaitingForTransfer = false
    private var pendingAmount = 0.0
    private var pendingNote = ""
    private var currentUserNickname = ""
    private var currentUserBalance = 0.0
    
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
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectNfcInterface()
            }
        }
        
        webView.loadUrl("https://ncpapp.vercel.app/")
    }
    
    private fun injectNfcInterface() {
        webView.addJavascriptInterface(NfcP2PBridge(), "NfcP2P")
    }
    
    private fun setupNFC() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC не поддерживается", Toast.LENGTH_LONG).show()
            return
        }
        
        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
    }
    
    override fun onResume() {
        super.onResume()
        nfcAdapter?.let {
            if (it.isEnabled) {
                it.enableForegroundDispatch(this, pendingIntent, null, null)
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
        
        when (intent.action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED -> {
                processNdefMessage(intent)
            }
            NfcAdapter.ACTION_TAG_DISCOVERED -> {
                // Если тег обнаружен, но не NDEF
                processTagDiscovered(intent)
            }
        }
    }
    
    private fun processNdefMessage(intent: Intent) {
        val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        if (rawMessages != null) {
            val messages = rawMessages.map { it as NdefMessage }
            for (message in messages) {
                for (record in message.records) {
                    val payload = String(record.payload, Charset.forName("UTF-8"))
                    handleIncomingTransfer(payload)
                }
            }
        }
    }
    
    private fun processTagDiscovered(intent: Intent) {
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        if (tag != null && isWaitingForTransfer) {
            // Отправляем данные на другой телефон
            sendTransferData(tag)
        } else {
            // Просто читаем тег
            readTagData(tag)
        }
    }
    
    private fun sendTransferData(tag: Tag) {
        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                if (ndef.isWritable) {
                    // Создаём сообщение с данными перевода
                    val transferData = JSONObject().apply {
                        put("type", "transfer")
                        put("from", currentUserNickname)
                        put("amount", pendingAmount)
                        put("note", pendingNote)
                        put("timestamp", System.currentTimeMillis())
                    }.toString()
                    
                    val message = createNdefMessage(transferData)
                    ndef.writeNdefMessage(message)
                    
                    Toast.makeText(this, "✅ Данные перевода отправлены!", Toast.LENGTH_SHORT).show()
                    
                    // Отправляем подтверждение в WebView
                    sendToWebView("transferSent", true)
                    
                    isWaitingForTransfer = false
                }
                ndef.close()
            } else {
                // Форматируем тег если нужно
                val ndefFormatable = NdefFormatable.get(tag)
                if (ndefFormatable != null) {
                    ndefFormatable.connect()
                    val message = createNdefMessage(
                        JSONObject().apply {
                            put("type", "handshake")
                            put("from", currentUserNickname)
                        }.toString()
                    )
                    ndefFormatable.format(message)
                    ndefFormatable.close()
                    Toast.makeText(this, "📱 Тег отформатирован", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: IOException) {
            Log.e("NCP", "Ошибка отправки", e)
            Toast.makeText(this, "❌ Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun readTagData(tag: Tag?) {
        if (tag == null) return
        
        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                val message = ndef.ndefMessage
                if (message != null) {
                    for (record in message.records) {
                        val payload = String(record.payload, Charset.forName("UTF-8"))
                        handleIncomingTransfer(payload)
                    }
                }
                ndef.close()
            }
        } catch (e: IOException) {
            Log.e("NCP", "Ошибка чтения", e)
        }
    }
    
    private fun handleIncomingTransfer(data: String) {
        try {
            val json = JSONObject(data)
            val type = json.optString("type")
            
            when (type) {
                "transfer" -> {
                    val from = json.optString("from")
                    val amount = json.optDouble("amount")
                    val note = json.optString("note")
                    
                    // Показываем диалог подтверждения
                    runOnUiThread {
                        showTransferConfirmDialog(from, amount, note)
                    }
                }
                "handshake" -> {
                    val from = json.optString("from")
                    runOnUiThread {
                        Toast.makeText(this, "📱 Обнаружен пользователь: $from", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("NCP", "Ошибка парсинга", e)
        }
    }
    
    private fun showTransferConfirmDialog(from: String, amount: Double, note: String) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("💰 Входящий перевод")
        builder.setMessage("""
            От: $from
            Сумма: $amount -_-
            Назначение: ${if (note.isNotEmpty()) note else "Без назначения"}
            
            Принять перевод?
        """.trimIndent())
        builder.setPositiveButton("✅ Принять") { _, _ ->
            confirmTransfer(from, amount, note)
        }
        builder.setNegativeButton("❌ Отказать") { _, _ ->
            Toast.makeText(this, "Перевод отклонён", Toast.LENGTH_SHORT).show()
        }
        builder.show()
    }
    
    private fun confirmTransfer(from: String, amount: Double, note: String) {
        // Отправляем подтверждение в WebView для обработки перевода
        val data = JSONObject().apply {
            put("action", "receiveTransfer")
            put("from", from)
            put("amount", amount)
            put("note", note)
        }.toString()
        
        sendToWebView("nfcTransfer", data)
        Toast.makeText(this, "🔄 Обработка перевода...", Toast.LENGTH_SHORT).show()
    }
    
    private fun createNdefMessage(data: String): NdefMessage {
        val record = NdefRecord(
            NdefRecord.TNF_MIME_MEDIA,
            "application/com.ncpbank.p2p".toByteArray(),
            ByteArray(0),
            data.toByteArray(Charset.forName("UTF-8"))
        )
        return NdefMessage(arrayOf(record))
    }
    
    private fun sendToWebView(action: String, data: String) {
        val jsCode = "window.handleNfcEvent && window.handleNfcEvent('$action', $data)"
        webView.evaluateJavascript(jsCode, null)
    }
    
    // JavaScript интерфейс для WebView
    inner class NfcP2PBridge {
        
        @JavascriptInterface
        fun startTransfer(amount: String, note: String, fromUser: String) {
            runOnUiThread {
                pendingAmount = amount.toDoubleOrNull() ?: 0.0
                pendingNote = note
                currentUserNickname = fromUser
                isWaitingForTransfer = true
                
                Toast.makeText(
                    this@MainActivity,
                    "💫 Приложите телефоны друг к другу для перевода $amount -_-",
                    Toast.LENGTH_LONG
                ).show()
                
                // Таймаут через 30 секунд
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isWaitingForTransfer) {
                        isWaitingForTransfer = false
                        Toast.makeText(this@MainActivity, "⏰ Время ожидания истекло", Toast.LENGTH_SHORT).show()
                        sendToWebView("transferTimeout", "{}")
                    }
                }, 30000)
            }
        }
        
        @JavascriptInterface
        fun updateUserInfo(nickname: String, balance: String) {
            currentUserNickname = nickname
            currentUserBalance = balance.toDoubleOrNull() ?: 0.0
        }
        
        @JavascriptInterface
        fun isNfcAvailable(): Boolean {
            return nfcAdapter != null && nfcAdapter!!.isEnabled
        }
        
        @JavascriptInterface
        fun cancelTransfer() {
            isWaitingForTransfer = false
            pendingAmount = 0.0
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Перевод отменён", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
