package com.aganaliz.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

class MainActivity : Activity() {

    private lateinit var wifi: WifiManager
    private lateinit var listBox: LinearLayout
    private lateinit var statusText: TextView
    private var receiver: BroadcastReceiver? = null
    private val ui = Handler(Looper.getMainLooper())

    private val cBg = Color.parseColor("#EEF2F7")
    private val cCard = Color.WHITE
    private val cText = Color.parseColor("#2B3A4B")
    private val cMuted = Color.parseColor("#8A97A8")
    private val cBrand = Color.parseColor("#1D6FE0")
    private val cGood = Color.parseColor("#2E9E5B")
    private val cWarn = Color.parseColor("#E8812E")
    private val cBad = Color.parseColor("#D6412B")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        setContentView(buildUi())
        ensurePermissions()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            setPadding(dp(16), dp(20), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "Ağ Analiz"
            setTextColor(cText); textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(8))
        }
        btnRow.addView(primaryButton("Ağları Tara") { startScan() })
        btnRow.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        btnRow.addView(primaryButton("Bağlı Cihazlar") { scanDevices() })
        root.addView(btnRow)

        statusText = TextView(this).apply {
            setTextColor(cMuted); textSize = 13f
            setPadding(dp(2), 0, 0, dp(8))
            text = "Başlamak için “Ağları Tara”ya dokun."
        }
        root.addView(statusText)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(listBox) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun primaryButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(cBrand)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    private fun ensurePermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        val missing = perms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (r.any { it == PackageManager.PERMISSION_GRANTED }) startScan()
    }

    private fun startScan() {
        if (!wifi.isWifiEnabled) { statusText.text = "Wi-Fi kapalı. Lütfen açın."; return }
        statusText.text = "Taranıyor…"
        registerScanReceiver()
        @Suppress("DEPRECATION")
        val ok = try { wifi.startScan() } catch (e: Exception) { false }
        if (!ok) showNetworks()
    }

    private fun registerScanReceiver() {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { showNetworks() }
        }
        val f = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, f)
    }

    private fun showNetworks() {
        val results = try { wifi.scanResults } catch (e: Exception) { emptyList<ScanResult>() }
        listBox.removeAllViews()
        if (results.isEmpty()) {
            statusText.text = "Ağ bulunamadı. Konum servisinin açık olduğundan emin olun."
            return
        }
        val sorted = results.sortedByDescending { it.level }
        val chanCount = HashMap<Int, Int>()
        sorted.forEach { val ch = channelOf(it.frequency); chanCount[ch] = (chanCount[ch] ?: 0) + 1 }

        val riskli = sorted.count { securityRank(it.capabilities) <= 2 }
        statusText.text = "${sorted.size} ağ • $riskli risk altında • ${sorted.size - riskli} güvenli"

        sorted.forEach { r -> listBox.addView(networkCard(r, (chanCount[channelOf(r.frequency)] ?: 1) - 1)) }
    }

    private fun networkCard(r: ScanResult, overlap: Int): View {
        val name = ssidOf(r)
        val sc = score(r.level)
        val secLabel = securityLabel(r.capabilities)
        val secRank = securityRank(r.capabilities)
        val wps = r.capabilities.contains("WPS", true)
        val dist = distance(r.level, r.frequency)
        val vendor = vendorOf(r.BSSID)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cCard)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(10)) }
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = if (name.isBlank()) "*Gizli Ağ*" else "\"$name\""
            setTextColor(cText); textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(TextView(this).apply {
            text = sc.toString()
            setTextColor(scoreColor(sc)); textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(top)

        val secColor = when { secRank <= 1 -> cBad; secRank == 2 -> cWarn; else -> cGood }
        card.addView(TextView(this).apply {
            text = "$secLabel  •  ${bandOf(r.frequency)}  •  Kanal ${channelOf(r.frequency)}" +
                   (if (wps) "  •  WPS" else "")
            setTextColor(secColor); textSize = 13f
            setPadding(0, dp(5), 0, 0)
        })

        val info = buildString {
            append("${r.level} dBm")
            if (dist > 0) append("  •  ~%.1f m".format(dist))
            if (overlap >= 2) append("  •  $overlap ağ aynı kanalda")
        }
        card.addView(TextView(this).apply {
            text = info; setTextColor(cMuted); textSize = 12f; setPadding(0, dp(3), 0, 0)
        })
        if (vendor.isNotBlank()) card.addView(TextView(this).apply {
            text = vendor; setTextColor(cMuted); textSize = 12f; setPadding(0, dp(2), 0, 0)
        })
        if (secRank == 0) card.addView(TextView(this).apply {
            text = "⚠ Şifresiz ağ — trafiğiniz görülebilir."
            setTextColor(cBad); textSize = 12f; setPadding(0, dp(4), 0, 0)
        })
        return card
    }

    private fun scanDevices() {
        statusText.text = "Cihazlar taranıyor… (yaklaşık 20 sn)"
        listBox.removeAllViews()
        val self = selfIp() ?: run { statusText.text = "Önce bir Wi-Fi ağına bağlanın."; return }
        val prefix = self.substringBeforeLast('.')
        val pool = Executors.newFixedThreadPool(32)
        val done = AtomicInteger(0)
        val found = java.util.Collections.synchronizedList(ArrayList<String>())
        for (h in 1..254) {
            pool.execute {
                val ip = "$prefix.$h"
                val ok = try { InetAddress.getByName(ip).isReachable(600) } catch (e: Exception) { false }
                val n = done.incrementAndGet()
                if (ok) found.add(ip)
                if (n == 254) ui.post { finishDevices(found, self) }
            }
        }
        pool.shutdown()
    }

    private fun finishDevices(found: List<String>, self: String) {
        val arp = readArp()
        val all = (found + self).distinct().sortedBy { it.substringAfterLast('.').toIntOrNull() ?: 0 }
        statusText.text = "${all.size} cihaz bulundu"
        listBox.removeAllViews()
        for (ip in all) {
            val mac = arp[ip] ?: "-"
            val vendor = if (mac != "-") vendorOf(mac) else ""
            val me = ip == self
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(cCard)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(10)) }
            }
            card.addView(TextView(this).apply {
                text = ip + if (me) "  (Bu cihaz)" else ""
                setTextColor(cText); textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            card.addView(TextView(this).apply {
                text = "MAC: $mac"; setTextColor(cMuted); textSize = 12f; setPadding(0, dp(3), 0, 0)
            })
            if (vendor.isNotBlank()) card.addView(TextView(this).apply {
                text = vendor; setTextColor(cMuted); textSize = 12f; setPadding(0, dp(2), 0, 0)
            })
            listBox.addView(card)
        }
        if (all.size <= 1) listBox.addView(TextView(this).apply {
            text = "Not: Bazı telefonlarda diğer cihazlar gizli kalabilir (sistem kısıtı)."
            setTextColor(cMuted); textSize = 12f
        })
    }

    private fun selfIp(): String? {
        return try {
            @Suppress("DEPRECATION")
            val ipInt = wifi.connectionInfo?.ipAddress ?: 0
            if (ipInt == 0) null
            else "${ipInt and 0xFF}.${ipInt shr 8 and 0xFF}.${ipInt shr 16 and 0xFF}.${ipInt shr 24 and 0xFF}"
        } catch (e: Exception) { null }
    }

    private fun readArp(): Map<String, String> {
        val m = HashMap<String, String>()
        try {
            val f = File("/proc/net/arp")
            if (!f.exists()) return m
            BufferedReader(FileReader(f)).use { br ->
                br.readLine()
                var line = br.readLine()
                while (line != null) {
                    val p = line.split(Regex("\\s+"))
                    if (p.size >= 4 && p[3].matches(Regex("..:..:..:..:..:.."))
                        && p[3] != "00:00:00:00:00:00") m[p[0]] = p[3].uppercase()
                    line = br.readLine()
                }
            }
        } catch (e: Exception) {}
        return m
    }

    private fun score(rssi: Int): Int {
        val c = rssi.coerceIn(-90, -30)
        return ((c + 90) * 100 / 60).coerceIn(0, 100)
    }
    private fun scoreColor(s: Int) = when { s >= 75 -> cGood; s >= 50 -> cWarn; else -> cBad }
    private fun distance(rssi: Int, freq: Int): Double {
        if (rssi == 0) return -1.0
        return 10.0.pow((27.55 - 20.0 * log10(freq.toDouble()) + abs(rssi)) / 20.0)
    }
    private fun channelOf(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2407) / 5
        freq in 5160..5885 -> (freq - 5000) / 5
        freq in 5955..7115 -> (freq - 5950) / 5
        else -> -1
    }
    private fun bandOf(freq: Int): String = when {
        freq in 2400..2500 -> "2.4 GHz"
        freq in 4900..5900 -> "5 GHz"
        freq in 5925..7125 -> "6 GHz"
        else -> "-"
    }
    private fun securityRank(cap: String): Int {
        val c = cap.uppercase()
        return when {
            c.contains("SAE") || c.contains("WPA3") -> 5
            c.contains("RSN") || c.contains("WPA2") -> 3
            c.contains("WPA") -> 2
            c.contains("WEP") -> 1
            c.contains("ESS") -> 0
            else -> 2
        }
    }
    private fun securityLabel(cap: String): String = when (securityRank(cap)) {
        5 -> "WPA3"; 3 -> "WPA2"; 2 -> "WPA"; 1 -> "WEP"; 0 -> "Açık (şifresiz)"; else -> "Bilinmiyor"
    }
    private fun ssidOf(r: ScanResult): String {
        return try {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                r.wifiSsid?.toString()?.trim('"') ?: r.SSID ?: ""
            else r.SSID ?: ""
        } catch (e: Exception) { r.SSID ?: "" }
    }
    private fun vendorOf(mac: String?): String {
        if (mac.isNullOrBlank()) return ""
        val oui = mac.replace(":", "").uppercase().take(6)
        return OUI[oui] ?: ""
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        receiver?.let { runCatching { unregisterReceiver(it) } }
    }

    companion object {
        val OUI = mapOf(
            "5478F0" to "ZTE Corporation", "D871F0" to "ZTE Corporation",
            "0C12FB" to "Zyxel", "E8D8D1" to "Zyxel",
            "AC9E17" to "TP-Link", "50C7BF" to "TP-Link", "98DED0" to "TP-Link",
            "C006C3" to "TP-Link", "14CC20" to "TP-Link", "B0BE76" to "TP-Link",
            "3C0754" to "Apple", "F0DBF8" to "Apple", "A4C361" to "Apple",
            "5CF6DC" to "Samsung", "8425DB" to "Samsung",
            "286C07" to "Xiaomi", "640980" to "Xiaomi", "F8A45F" to "Xiaomi",
            "286ED4" to "Huawei", "48DB50" to "Huawei",
            "D4619D" to "Intel", "602232" to "Ubiquiti", "D46E0E" to "Arcadyan"
        )
    }
}
