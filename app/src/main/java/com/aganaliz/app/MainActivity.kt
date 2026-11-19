package com.aganaliz.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.MotionEvent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

class MainActivity : Activity() {

    private lateinit var wifi: WifiManager
    private lateinit var prefs: SharedPreferences
    private val uiH = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private var receiver: BroadcastReceiver? = null

    private var nets: List<ScanResult> = emptyList()
    private var myBssid: String? = null
    private var gatewayPing: Long = -2L

    private enum class Mode { LIST, DETAIL, DEVICES, DEVDETAIL, TOOLS, CONN, HEATMAP }
    private var mode = Mode.LIST
    private var selected: ScanResult? = null
    private var selectedDev: Dev? = null
    private var sortBySignal = true
    private var bandFilter = 0

    private var devScanning = false
    private var devProgress = 0f
    private var devices: List<Dev> = emptyList()
    private var devPorts: String = ""

    // Araçlar durumları
    private var toolHost = "google.com"
    private var toolMac = ""
    private var toolCidr = "192.168.1.0/24"
    private var outDiag = ""
    private var outPing = ""
    private var outDns = ""
    private var outPort = ""
    private var outSpeed = ""
    private var outWol = ""
    private var outSubnet = ""

    // Bağlantı
    private var connPublic = "alınıyor…"

    // Isı haritası
    private val heatSamples = ArrayList<FloatArray>()  // [nx, ny, rssi]
    private var heatBitmap: Bitmap? = null
    private var heatInfo: TextView? = null
    private val REQ_IMG = 77

    private data class Dev(val ip: String, val mac: String, val vendor: String, val me: Boolean, val gw: Boolean, val ping: Long)

    private var dark = false
    private val cBg get() = if (dark) 0xFF0B1220.toInt() else 0xFFEEF2F7.toInt()
    private val cCard get() = if (dark) 0xFF161F30.toInt() else Color.WHITE
    private val cCard2 get() = if (dark) 0xFF1E293B.toInt() else 0xFFF4F8FD.toInt()
    private val cText get() = if (dark) 0xFFE6ECF5.toInt() else 0xFF2B3A4B.toInt()
    private val cMuted get() = if (dark) 0xFF8FA0B6.toInt() else 0xFF8A97A8.toInt()
    private val cBrand = 0xFF1D6FE0.toInt()
    private val cBrand2 = 0xFF3B9EFF.toInt()
    private val cGood = 0xFF2E9E5B.toInt()
    private val cWarn = 0xFFE8812E.toInt()
    private val cBad = 0xFFD6412B.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        prefs = getSharedPreferences("aganaliz", Context.MODE_PRIVATE)
        render()
        ensurePermissions()
    }

    private fun render() {
        setContentView(when (mode) {
            Mode.LIST -> buildList()
            Mode.DETAIL -> buildDetail()
            Mode.DEVICES -> buildDevices()
            Mode.DEVDETAIL -> buildDevDetail()
            Mode.TOOLS -> buildTools()
            Mode.CONN -> buildConn()
            Mode.HEATMAP -> buildHeatmap()
        })
    }

    private fun ui(f: () -> Unit) = uiH.post(f)
    private fun bg(f: () -> Unit) { io.execute(f) }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when (mode) {
            Mode.DETAIL -> { mode = Mode.LIST; render() }
            Mode.DEVDETAIL -> { mode = Mode.DEVICES; render() }
            Mode.DEVICES, Mode.TOOLS, Mode.CONN, Mode.HEATMAP -> { mode = Mode.LIST; render() }
            else -> super.onBackPressed()
        }
    }

    private fun ensurePermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        val miss = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (miss.isNotEmpty()) requestPermissions(miss.toTypedArray(), 1) else startScan()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (r.any { it == PackageManager.PERMISSION_GRANTED }) startScan()
    }

    // ================= WIFI TARAMA =================
    private fun startScan() {
        if (!wifi.isWifiEnabled) { render(); return }
        registerScan()
        @Suppress("DEPRECATION")
        val ok = try { wifi.startScan() } catch (e: Exception) { false }
        if (!ok) refreshData()
        pingGateway()
    }

    private fun registerScan() {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { refreshData() }
        }
        val f = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, f)
    }

    private fun refreshData() {
        nets = try { wifi.scanResults } catch (e: Exception) { emptyList() }
        myBssid = try {
            @Suppress("DEPRECATION", "HardwareIds")
            wifi.connectionInfo?.bssid?.takeIf { it != "02:00:00:00:00:00" }
        } catch (e: Exception) { null }
        if (mode == Mode.LIST) render()
    }

    private fun pingGateway() {
        val gw = gatewayIp() ?: return
        bg {
            val t0 = System.currentTimeMillis()
            val ok = try { InetAddress.getByName(gw).isReachable(1200) } catch (e: Exception) { false }
            gatewayPing = if (ok) (System.currentTimeMillis() - t0) else -1L
            ui { if (mode == Mode.LIST) render() }
        }
    }

    // ================= LİSTE =================
    private fun buildList(): View {
        val root = col().apply { setBackgroundColor(cBg) }
        root.addView(header())
        val content = col().apply { setPadding(dp(14), dp(12), dp(14), dp(90)) }
        content.addView(tabRow())
        if (nets.isNotEmpty()) content.addView(controlsRow())
        if (!wifi.isWifiEnabled) content.addView(banner("Wi-Fi kapalı", "Taramak için Wi-Fi'yi açın.", cBad))

        val shown = filtered()
        val mine = nets.firstOrNull { it.BSSID.equals(myBssid, true) }
        if (mine != null) {
            content.addView(sectionLabel("BENİM AĞIM"))
            content.addView(netCard(mine, true))
            content.addView(myNetworkInfo(mine))
        }
        channelAdvice()?.let { content.addView(it) }
        content.addView(sectionLabel("YAKINDAKİ AĞLAR (${shown.size})"))
        if (shown.isEmpty()) content.addView(banner("Ağ bulunamadı", "“Tara”ya dokun. Konum açık olmalı.", cWarn))
        else shown.filter { !it.BSSID.equals(myBssid, true) }.forEach { content.addView(netCard(it, false)) }

        root.addView(ScrollView(this).apply { addView(content); isFillViewport = true }, lp(MATCH, 0, 1f))
        val b = row().apply { layoutParams = lp(MATCH, WRAP); setPadding(dp(14), dp(8), dp(14), dp(14)); setBackgroundColor(cBg) }
        b.addView(flatBtn("⟳  Tara", cBrand) { startScan() }, lp(0, WRAP, 1f))
        b.addView(View(this), lp(dp(8), 1))
        b.addView(flatBtn("CSV Paylaş", cCard2, cText) { shareCsv() }, lp(0, WRAP, 1f))
        root.addView(b)
        return root
    }

    private fun header(): View {
        val h = col().apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cBrand, cBrand2))
            setPadding(dp(18), dp(20), dp(18), dp(16)); layoutParams = lp(MATCH, WRAP)
        }
        h.addView(TextView(this).apply {
            text = "Ağ Analiz"; setTextColor(Color.WHITE); textSize = 25f; setTypeface(typeface, Typeface.BOLD)
        })
        val total = nets.size
        val risky = nets.count { secRank(it.capabilities) <= 2 }
        h.addView(TextView(this).apply {
            text = if (total == 0) "Taramak için aşağıdaki düğmeye dokun"
            else "$total ağ  •  $risky risk altında  •  ${total - risky} güvenli"
            setTextColor(0xFFDCEBFF.toInt()); textSize = 13f; setPadding(0, dp(4), 0, 0)
        })
        return h
    }

    private fun tabRow(): View {
        val bar = row().apply { setPadding(0, 0, 0, dp(10)) }
        bar.addView(tabBtn("Ağlar", mode == Mode.LIST || mode == Mode.DETAIL) { mode = Mode.LIST; render() })
        bar.addView(tabBtn("Cihazlar", mode == Mode.DEVICES || mode == Mode.DEVDETAIL) {
            mode = Mode.DEVICES; if (!devScanning && devices.isEmpty()) scanDevices() else render()
        })
        bar.addView(tabBtn("Araçlar", mode == Mode.TOOLS) { mode = Mode.TOOLS; render() })
        bar.addView(tabBtn("Bağlantı", mode == Mode.CONN) { mode = Mode.CONN; if (connPublic == "alınıyor…") fetchPublic(); render() })
        bar.addView(tabBtn("Isı Haritası", mode == Mode.HEATMAP) { mode = Mode.HEATMAP; render() })
        val sv = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar); layoutParams = lp(MATCH, WRAP) }
        return sv
    }

    private fun tabBtn(text: String, active: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; gravity = Gravity.CENTER; textSize = 14f
        setTextColor(if (active) Color.WHITE else cMuted); setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply { setColor(if (active) cBrand else cCard); cornerRadius = dp(12).toFloat() }
        setPadding(dp(16), dp(10), dp(16), dp(10))
        val m = LinearLayout.LayoutParams(WRAP, WRAP); m.setMargins(0, 0, dp(8), 0); layoutParams = m
        setOnClickListener { onClick() }
    }

    private fun controlsRow(): View {
        val bar = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(6)) }
        bar.addView(TextView(this).apply { text = "Sırala:"; setTextColor(cMuted); textSize = 12f; setPadding(0, 0, dp(6), 0) })
        bar.addView(toggleChip("Sinyal", sortBySignal) { sortBySignal = true; render() })
        bar.addView(toggleChip("Güvenlik", !sortBySignal) { sortBySignal = false; render() })
        bar.addView(View(this), lp(dp(10), 1))
        bar.addView(toggleChip("Tümü", bandFilter == 0) { bandFilter = 0; render() })
        bar.addView(toggleChip("2.4", bandFilter == 24) { bandFilter = 24; render() })
        bar.addView(toggleChip("5G", bandFilter == 5) { bandFilter = 5; render() })
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar); layoutParams = lp(MATCH, WRAP) }
    }

    private fun myNetworkInfo(mine: ScanResult): View {
        val c = card()
        val r = row().apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(MATCH, WRAP) }
        val ping = when { gatewayPing == -2L -> "ölçülüyor…"; gatewayPing == -1L -> "yanıt yok"; else -> "$gatewayPing ms" }
        r.addView(TextView(this).apply { text = "Modem gecikmesi"; setTextColor(cText); textSize = 14f; layoutParams = lp(0, WRAP, 1f) })
        r.addView(chip(ping, if (gatewayPing in 0..60) cGood else if (gatewayPing in 61..150) cWarn else cMuted))
        c.addView(r)
        return c
    }

    // ================= AĞ KARTI =================
    private fun netCard(r: ScanResult, mine: Boolean): View {
        val name = ssidOf(r)
        val sc = score(r.level)
        val rank = secRank(r.capabilities)
        val wps = r.capabilities.contains("WPS", true)
        val c = card()
        if (mine) c.setElevation(dp(4).toFloat())
        val top = row().apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(MATCH, WRAP) }
        val left = col().apply { layoutParams = lp(0, WRAP, 1f) }
        left.addView(TextView(this).apply {
            text = if (name.isBlank()) "Gizli Ağ" else name
            setTextColor(cText); textSize = 17f; setTypeface(typeface, Typeface.BOLD); maxLines = 1
        })
        val chips = flow()
        chips.addView(chip(secLabel(r.capabilities), secColor(rank)))
        chips.addView(chip(bandOf(r.frequency), cBrand))
        stdOf(r)?.let { chips.addView(chip(it, cMuted)) }
        if (wps) chips.addView(chip("WPS", cWarn))
        left.addView(chips)
        val vend = vendorOf(r.BSSID)
        if (vend.isNotBlank()) left.addView(TextView(this).apply {
            text = vend; setTextColor(cMuted); textSize = 12f; setPadding(0, dp(3), 0, 0)
        })
        top.addView(left)
        top.addView(GaugeView(this, sc, scoreColor(sc), cMuted).also { it.layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)) })
        c.addView(top)
        val dist = distance(r.level, r.frequency)
        c.addView(TextView(this).apply {
            text = "${r.level} dBm (%${sigPct(r.level)})" + (if (dist > 0) "  •  ~%.1f m".format(dist) else "") +
                   "  •  K${channelOf(r.frequency)} ${widthOf(r)}"
            setTextColor(cMuted); textSize = 12f; setPadding(0, dp(6), 0, 0)
        })
        c.setOnClickListener { selected = r; mode = Mode.DETAIL; render() }
        return c
    }

    // ================= AĞ DETAY =================
    private fun buildDetail(): View {
        val r = selected ?: run { mode = Mode.LIST; return buildList() }
        val root = col().apply { setBackgroundColor(cBg) }
        root.addView(topBar(if (ssidOf(r).isBlank()) "Gizli Ağ" else ssidOf(r)))
        val content = col().apply { setPadding(dp(14), dp(12), dp(14), dp(24)) }
        val sc = score(r.level); val rank = secRank(r.capabilities)

        val hero = card()
        val hr = row().apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(MATCH, WRAP) }
        hr.addView(GaugeView(this, sc, scoreColor(sc), cMuted).also { it.layoutParams = LinearLayout.LayoutParams(dp(78), dp(78)) })
        val hc = col().apply { setPadding(dp(14), 0, 0, 0); layoutParams = lp(0, WRAP, 1f) }
        hc.addView(TextView(this).apply { text = qualityWord(sc); setTextColor(cText); textSize = 20f; setTypeface(typeface, Typeface.BOLD) })
        hc.addView(TextView(this).apply { text = "${r.level} dBm  •  %${sigPct(r.level)} sinyal"; setTextColor(cMuted); textSize = 13f })
        hr.addView(hc); hero.addView(hr); content.addView(hero)

        val sameSsid = nets.count { ssidOf(it) == ssidOf(r) && ssidOf(r).isNotBlank() }
        val overlap = nets.count { channelOf(it.frequency) == channelOf(r.frequency) && bandOf(it.frequency) == bandOf(r.frequency) } - 1
        val bad = overlap >= 2
        val chStat = card()
        val cr = row().apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(MATCH, WRAP) }
        cr.addView(dot(if (bad) cBad else cGood, if (bad) "!" else "✓"))
        val cc = col().apply { setPadding(dp(12), 0, 0, 0) }
        cc.addView(TextView(this).apply { text = "Kanal ${channelOf(r.frequency)} • ${bandOf(r.frequency)} • ${widthOf(r)}"; setTextColor(cText); textSize = 15f; setTypeface(typeface, Typeface.BOLD) })
        cc.addView(TextView(this).apply { text = if (bad) "$overlap ağ bu kanalda — sıkışık" else "Kanal nispeten boş"; setTextColor(cMuted); textSize = 13f })
        cr.addView(cc); chStat.addView(cr); content.addView(chStat)

        content.addView(group("KİMLİK", listOf(
            "Üretici" to vendorOf(r.BSSID).ifBlank { "Bilinmiyor" },
            "MAC (BSSID)" to (r.BSSID ?: "-"),
            "Wi-Fi standardı" to (stdOf(r) ?: "Bilinmiyor"),
            "Aynı isimli AP" to if (sameSsid > 1) "$sameSsid (mesh/çoklu)" else "1"
        )))
        content.addView(group("PERFORMANS", listOf(
            "Mesafe (tahmini)" to (distance(r.level, r.frequency).let { if (it > 0) "~%.1f m".format(it) else "-" }),
            "Sinyal" to "${r.level} dBm (%${sigPct(r.level)})",
            "Skor" to "$sc / 100",
            "Frekans" to "${r.frequency} MHz",
            "Kanal genişliği" to widthOf(r)
        )))
        content.addView(group("GÜVENLİK", listOf(
            "Şifreleme" to secLabel(r.capabilities),
            "WPS" to if (r.capabilities.contains("WPS", true)) "Açık (önerilmez)" else "Kapalı",
            "Güvenlik puanı" to "${secScore(r)}/100"
        )))

        // evil twin sezgisi
        val variants = nets.filter { ssidOf(it) == ssidOf(r) && ssidOf(r).isNotBlank() }.map { secLabel(it.capabilities) }.distinct()
        if (variants.size > 1) content.addView(banner("Dikkat: aynı ada farklı güvenlik", "“${ssidOf(r)}” hem ${variants.joinToString(", ")} olarak görünüyor. Sahte AP (evil twin) olabilir.", cWarn))
        if (rank <= 1 || r.capabilities.contains("WPS", true)) content.addView(banner(
            if (rank == 0) "Şifresiz ağ" else "Zayıf güvenlik",
            if (rank == 0) "Trafiğiniz görülebilir. Hassas işlem yapmayın." else "WPA2/WPA3 kullanın, WPS'i kapatın.", cBad))

        root.addView(ScrollView(this).apply { addView(content) }, lp(MATCH, 0, 1f))
        return root
    }

    // ================= CİHAZLAR =================
    private fun buildDevices(): View {
        val root = col().apply { setBackgroundColor(cBg) }
        root.addView(header())
        val content = col().apply { setPadding(dp(14), dp(12), dp(14), dp(90)) }
        content.addView(tabRow())
        content.addView(TextView(this).apply {
            text = if (devScanning) "Ağ taranıyor…" else "${devices.size} cihaz bulundu"
            setTextColor(cText); textSize = 16f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(2), 0, 0, dp(8))
        })
        if (devScanning) content.addView(progressBar(devProgress))
        devices.forEach { content.addView(devCard(it)) }
        if (!devScanning && devices.isEmpty()) content.addView(banner("Cihaz yok", "Wi-Fi'ye bağlanıp yeniden tarayın.", cMuted))
        root.addView(ScrollView(this).apply { addView(content); isFillViewport = true }, lp(MATCH, 0, 1f))
        root.addView(bottomBar("⟳  Cihazları Tara") { scanDevices() })
        return root
    }

    private fun devCard(d: Dev): View {
        val c = card()
        val r = row().apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(MATCH, WRAP) }
        r.addView(TextView(this).apply { text = devIcon(d); textSize = 22f; setPadding(0, 0, dp(12), 0) })
        val cl = col().apply { layoutParams = lp(0, WRAP, 1f) }
        val nm = prefs.getString("name_" + d.mac, null)
        cl.addView(TextView(this).apply { text = nm ?: d.ip; setTextColor(cText); textSize = 16f; setTypeface(typeface, Typeface.BOLD) })
        cl.addView(TextView(this).apply {
            text = (if (nm != null) d.ip + "  •  " else "") + "MAC: ${d.mac}" + (if (d.ping >= 0) "  •  ${d.ping} ms" else "")
            setTextColor(cMuted); textSize = 12f
        })
        if (d.vendor.isNotBlank()) cl.addView(TextView(this).apply { text = devType(d); setTextColor(cMuted); textSize = 12f })
        r.addView(cl)
        if (d.me) r.addView(chip("Siz", cGood)) else if (d.gw) r.addView(chip("Modem", cBrand))
        c.addView(r)
        c.setOnClickListener { selectedDev = d; devPorts = ""; mode = Mode.DEVDETAIL; render() }
        return c
    }

    private fun buildDevDetail(): View {
        val d = selectedDev ?: run { mode = Mode.DEVICES; return buildDevices() }
        val root = col().apply { setBackgroundColor(cBg) }
        val nm = prefs.getString("name_" + d.mac, null)
        root.addView(topBar(nm ?: d.ip))
        val content = col().apply { setPadding(dp(14), dp(12), dp(14), dp(24)) }
        content.addView(group("CİHAZ", listOf(
            "IP" to d.ip, "MAC" to d.mac, "Üretici" to d.vendor.ifBlank { "Bilinmiyor" },
            "Tür (tahmin)" to devType(d), "Gecikme" to if (d.ping >= 0) "${d.ping} ms" else "-"
        )))
        // isim ver
        val nameBox = card()
        nameBox.addView(TextView(this).apply { text = "İsim ver"; setTextColor(cText); textSize = 14f; setTypeface(typeface, Typeface.BOLD) })
        val et = input(nm ?: "", "ör. Salon TV")
        nameBox.addView(et)
        nameBox.addView(flatBtn("Kaydet", cBrand) {
            prefs.edit().putString("name_" + d.mac, et.text.toString().trim()).apply(); render()
        }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8) })
        content.addView(nameBox)
        // port kontrol
        val portBox = card()
        portBox.addView(TextView(this).apply { text = "Açık port kontrolü (yaygın portlar)"; setTextColor(cText); textSize = 14f; setTypeface(typeface, Typeface.BOLD) })
        portBox.addView(flatBtn("Portları tara", cBrand) { scanCommonPorts(d.ip) }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8) })
        if (devPorts.isNotBlank()) portBox.addView(TextView(this).apply { text = devPorts; setTextColor(cMuted); textSize = 13f; setPadding(0, dp(8), 0, 0) })
        content.addView(portBox)
        root.addView(ScrollView(this).apply { addView(content) }, lp(MATCH, 0, 1f))
        return root
    }

    private fun scanDevices() {
        if (devScanning) return
        devScanning = true; devProgress = 0f; devices = emptyList(); mode = Mode.DEVICES; render()
        val self = selfIp() ?: run { devScanning = false; render(); return }
        val prefix = self.substringBeforeLast('.'); val gw = gatewayIp()
        val pool = Executors.newFixedThreadPool(32)
        val done = AtomicInteger(0)
        val found = java.util.Collections.synchronizedList(ArrayList<Pair<String, Long>>())
        for (h in 1..254) {
            pool.execute {
                val ip = "$prefix.$h"; val t0 = System.currentTimeMillis()
                val ok = try { InetAddress.getByName(ip).isReachable(600) } catch (e: Exception) { false }
                if (ok) found.add(ip to (System.currentTimeMillis() - t0))
                val n = done.incrementAndGet(); devProgress = n / 254f
                if (n % 16 == 0) ui { if (mode == Mode.DEVICES) render() }
                if (n == 254) ui { finishDevices(found, self, gw) }
            }
        }
        pool.shutdown()
    }

    private fun finishDevices(found: List<Pair<String, Long>>, self: String, gw: String?) {
        val arp = readArp()
        val map = HashMap<String, Long>(); found.forEach { map[it.first] = it.second }
        map[self] = 0L; if (gw != null) map.putIfAbsent(gw, -1L)
        devices = map.entries.sortedBy { it.key.substringAfterLast('.').toIntOrNull() ?: 0 }.map { e ->
            val mac = arp[e.key] ?: "-"
            Dev(e.key, mac, if (mac != "-") vendorOf(mac) else "", e.key == self, e.key == gw, e.value)
        }
        devScanning = false; devProgress = 1f; render()
    }

    private fun scanCommonPorts(ip: String) {
        devPorts = "Taranıyor…"; render()
        bg {
            val ports = linkedMapOf(21 to "FTP", 22 to "SSH", 23 to "Telnet", 53 to "DNS", 80 to "HTTP",
                139 to "NetBIOS", 443 to "HTTPS", 445 to "SMB", 554 to "RTSP", 1883 to "MQTT",
                3389 to "RDP", 8080 to "HTTP-alt", 8443 to "HTTPS-alt", 9100 to "Yazıcı")
            val open = ArrayList<String>()
            for ((p, nmv) in ports) { if (tcpOpen(ip, p, 500)) open.add("$p ($nmv)") }
            devPorts = if (open.isEmpty()) "Açık yaygın port bulunamadı." else "Açık: " + open.joinToString(", ")
            ui { if (mode == Mode.DEVDETAIL) render() }
        }
    }

    // ================= ARAÇLAR =================
    private fun buildTools(): View {
        val root = col().apply { setBackgroundColor(cBg) }
        root.addView(header())
        val content = col().apply { setPadding(dp(14), dp(12), dp(14), dp(24)) }
        content.addView(tabRow())

        // Teşhis
        val diag = card()
        diag.addView(toolTitle("Tek-tık teşhis"))
        diag.addView(flatBtn("Teşhis et", cBrand) { runDiagnosis() }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8) })
        if (outDiag.isNotBlank()) diag.addView(mono(outDiag))
        content.addView(diag)

        // Hız testi
        val sp = card()
        sp.addView(toolTitle("Hız testi (indirme)"))
        sp.addView(flatBtn("Başlat", cBrand) { speedTest() }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8) })
        if (outSpeed.isNotBlank()) sp.addView(mono(outSpeed))
        content.addView(sp)

        // Ping
        val pg = card()
        pg.addView(toolTitle("Ping / gecikme"))
        val hostEt = input(toolHost, "ör. google.com")
        pg.addView(hostEt)
        pg.addView(flatBtn("Ping at", cBrand) { toolHost = hostEt.text.toString().trim(); pingTest(toolHost) }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8) })
        if (outPing.isNotBlank()) pg.addView(mono(outPing))
        content.addView(pg)

        // DNS
        val dn = card()
        dn.addView(toolTitle("DNS sorgulama (A/AAAA)"))
        val dnsEt = input(toolHost, "ör. cloudflare.com")
        dn.addView(dnsEt)
        dn.addView(flatBtn("Sorgula", cBrand) { toolHost = dnsEt.text.toString().trim(); dnsLookup(toolHost) }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8) })
        if (outDns.isNotBlank()) dn.addView(mono(outDns))
        content.addView(dn)

        // Port tarayıcı
        val pt = card()
        pt.addView(toolTitle("Port tarayıcı (yaygın)"))
        val ptEt = input(toolHost, "IP ya da alan adı")
        pt.addView(ptEt)
        pt.addView(flatBtn("Tara", cBrand) { toolHost = ptEt.text.toString().trim(); portScan(toolHost) }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8) })
        if (outPort.isNotBlank()) pt.addView(mono(outPort))
        content.addView(pt)

        // Wake on LAN
        val wl = card()
        wl.addView(toolTitle("Wake-on-LAN"))
        val macEt = input(toolMac, "AA:BB:CC:DD:EE:FF")
        wl.addView(macEt)
        wl.addView(flatBtn("Uyandır", cBrand) { toolMac = macEt.text.toString().trim(); wol(toolMac) }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8) })
        if (outWol.isNotBlank()) wl.addView(mono(outWol))
        content.addView(wl)

        // Subnet
        val sn = card()
        sn.addView(toolTitle("Subnet hesaplayıcı"))
        val snEt = input(toolCidr, "192.168.1.0/24")
        sn.addView(snEt)
        sn.addView(flatBtn("Hesapla", cBrand) { toolCidr = snEt.text.toString().trim(); subnet(toolCidr) }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8) })
        if (outSubnet.isNotBlank()) sn.addView(mono(outSubnet))
        content.addView(sn)

        root.addView(ScrollView(this).apply { addView(content) }, lp(MATCH, 0, 1f))
        return root
    }

    private fun runDiagnosis() {
        outDiag = "Çalışıyor…"; render()
        bg {
            val sb = StringBuilder()
            sb.append(if (wifi.isWifiEnabled) "✓ Wi-Fi açık\n" else "✗ Wi-Fi kapalı\n")
            val ip = selfIp()
            sb.append(if (ip != null) "✓ IP alındı: $ip\n" else "✗ IP yok\n")
            val gw = gatewayIp()
            val gwOk = gw != null && try { InetAddress.getByName(gw).isReachable(1000) } catch (e: Exception) { false }
            sb.append(if (gwOk) "✓ Ağ geçidine ulaşılıyor ($gw)\n" else "✗ Ağ geçidine ulaşılamıyor\n")
            val dnsOk = try { InetAddress.getByName("google.com"); true } catch (e: Exception) { false }
            sb.append(if (dnsOk) "✓ DNS çözülüyor\n" else "✗ DNS çözülemiyor\n")
            val netOk = httpOk("https://clients3.google.com/generate_204")
            sb.append(if (netOk) "✓ İnternet erişimi var" else "✗ İnternet erişimi yok")
            outDiag = sb.toString(); ui { if (mode == Mode.TOOLS) render() }
        }
    }

    private fun speedTest() {
        outSpeed = "İndiriliyor…"; render()
        bg {
            try {
                val url = URL("https://speed.cloudflare.com/__down?bytes=20000000")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 20000
                val t0 = System.currentTimeMillis()
                var total = 0L
                conn.inputStream.use { ins ->
                    val buf = ByteArray(32768); var n = ins.read(buf)
                    while (n >= 0) { total += n; n = ins.read(buf) }
                }
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                val mbps = if (secs > 0) (total * 8 / 1_000_000.0) / secs else 0.0
                outSpeed = "İndirme: %.1f Mbps\n(%.1f MB, %.1f sn)".format(mbps, total / 1_000_000.0, secs)
            } catch (e: Exception) { outSpeed = "Hata: ${e.message}" }
            ui { if (mode == Mode.TOOLS) render() }
        }
    }

    private fun pingTest(host: String) {
        outPing = "Ölçülüyor…"; render()
        bg {
            try {
                val addr = InetAddress.getByName(host)
                val times = ArrayList<Long>(); var loss = 0
                repeat(8) {
                    val t0 = System.currentTimeMillis()
                    val ok = try { addr.isReachable(1500) } catch (e: Exception) { false }
                    if (ok) times.add(System.currentTimeMillis() - t0) else loss++
                }
                if (times.isEmpty()) outPing = "$host: yanıt yok (%100 kayıp)"
                else {
                    val avg = times.average(); val mn = times.minOrNull() ?: 0L; val mx = times.maxOrNull() ?: 0L
                    val jit = times.map { abs(it - avg) }.average()
                    outPing = "$host\nort %.0f ms  •  min $mn  •  maks $mx\njitter %.0f ms  •  kayıp %%%d".format(avg, jit, loss * 100 / 8)
                }
            } catch (e: Exception) { outPing = "Hata: ${e.message}" }
            ui { if (mode == Mode.TOOLS) render() }
        }
    }

    private fun dnsLookup(host: String) {
        outDns = "Sorgulanıyor…"; render()
        bg {
            try {
                val addrs = InetAddress.getAllByName(host)
                val v4 = addrs.filterIsInstance<Inet4Address>().map { it.hostAddress }
                val v6 = addrs.filter { it !is Inet4Address }.map { it.hostAddress }
                val sb = StringBuilder("$host\n")
                if (v4.isNotEmpty()) sb.append("A: " + v4.joinToString(", ") + "\n")
                if (v6.isNotEmpty()) sb.append("AAAA: " + v6.joinToString(", "))
                outDns = sb.toString().trim()
            } catch (e: Exception) { outDns = "Çözülemedi: ${e.message}" }
            ui { if (mode == Mode.TOOLS) render() }
        }
    }

    private fun portScan(host: String) {
        outPort = "Taranıyor…"; render()
        bg {
            val ports = linkedMapOf(21 to "FTP", 22 to "SSH", 23 to "Telnet", 53 to "DNS", 80 to "HTTP",
                443 to "HTTPS", 445 to "SMB", 3306 to "MySQL", 3389 to "RDP", 8080 to "HTTP-alt", 8443 to "HTTPS-alt")
            try {
                val ip = InetAddress.getByName(host).hostAddress ?: host
                val open = ArrayList<String>()
                for ((p, nmv) in ports) if (tcpOpen(ip, p, 600)) open.add("$p ($nmv)")
                outPort = "$host ($ip)\n" + if (open.isEmpty()) "Açık yaygın port yok." else "Açık: " + open.joinToString(", ")
            } catch (e: Exception) { outPort = "Hata: ${e.message}" }
            ui { if (mode == Mode.TOOLS) render() }
        }
    }

    private fun wol(mac: String) {
        bg {
            try {
                val clean = mac.replace(":", "").replace("-", "")
                if (clean.length != 12) { outWol = "Geçersiz MAC"; ui { render() }; return@bg }
                val bytes = ByteArray(6) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                val packet = ByteArray(102)
                for (i in 0..5) packet[i] = 0xFF.toByte()
                for (i in 1..16) System.arraycopy(bytes, 0, packet, i * 6, 6)
                DatagramSocket().use { s ->
                    s.broadcast = true
                    s.send(DatagramPacket(packet, packet.size, InetAddress.getByName("255.255.255.255"), 9))
                }
                outWol = "Uyandırma paketi gönderildi: $mac"
            } catch (e: Exception) { outWol = "Hata: ${e.message}" }
            ui { if (mode == Mode.TOOLS) render() }
        }
    }

    private fun subnet(cidr: String) {
        try {
            val parts = cidr.split("/")
            val ipParts = parts[0].trim().split(".").map { it.toInt() }
            val bits = parts[1].trim().toInt()
            val ipInt = (ipParts[0] shl 24) or (ipParts[1] shl 16) or (ipParts[2] shl 8) or ipParts[3]
            val mask = if (bits == 0) 0 else (-1 shl (32 - bits))
            val net = ipInt and mask; val bcast = net or mask.inv()
            val hosts = if (bits >= 31) 0 else (1L shl (32 - bits)) - 2
            outSubnet = "Ağ: ${ipStr(net)}\nYayın: ${ipStr(bcast)}\nMaske: ${ipStr(mask)}\nİlk host: ${ipStr(net + 1)}\nSon host: ${ipStr(bcast - 1)}\nHost sayısı: $hosts"
        } catch (e: Exception) { outSubnet = "Geçersiz giriş (ör. 192.168.1.0/24)" }
        render()
    }

    private fun ipStr(v: Int) = "${v ushr 24 and 0xFF}.${v ushr 16 and 0xFF}.${v ushr 8 and 0xFF}.${v and 0xFF}"

    // ================= BAĞLANTI =================
    private fun buildConn(): View {
        val root = col().apply { setBackgroundColor(cBg) }
        root.addView(header())
        val content = col().apply { setPadding(dp(14), dp(12), dp(14), dp(24)) }
        content.addView(tabRow())

        @Suppress("DEPRECATION") val info = try { wifi.connectionInfo } catch (e: Exception) { null }
        @Suppress("DEPRECATION") val dhcp = try { wifi.dhcpInfo } catch (e: Exception) { null }
        val ssid = info?.ssid?.trim('"') ?: "-"
        @Suppress("DEPRECATION", "HardwareIds") val bssid = info?.bssid ?: "-"
        @Suppress("DEPRECATION") val link = info?.linkSpeed ?: -1
        @Suppress("DEPRECATION") val rssi = info?.rssi ?: 0

        content.addView(group("Wi-Fi", listOf(
            "SSID" to ssid, "BSSID" to bssid,
            "Link hızı" to if (link >= 0) "$link Mbps" else "-",
            "Sinyal" to if (rssi != 0) "$rssi dBm (%${sigPct(rssi)})" else "-",
            "Standart" to connStd(info)
        )))
        content.addView(group("IP", listOf(
            "Yerel IP" to (selfIp() ?: "-"),
            "Ağ geçidi" to (gatewayIp() ?: "-"),
            "Alt ağ maskesi" to (dhcp?.let { ipStr2(it.netmask) } ?: "-"),
            "DNS" to dnsServers(),
            "DHCP sunucusu" to (dhcp?.let { ipStr2(it.serverAddress) } ?: "-"),
            "Kira süresi" to (dhcp?.leaseDuration?.let { "$it sn" } ?: "-")
        )))
        content.addView(group("İnternet", listOf("Genel IP / ISS" to connPublic)))
        content.addView(flatBtn("Modem paneli (${gatewayIp() ?: "?"})", cBrand) { openGateway() }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(4) })

        root.addView(ScrollView(this).apply { addView(content) }, lp(MATCH, 0, 1f))
        return root
    }

    private fun fetchPublic() {
        bg {
            connPublic = try {
                val conn = URL("https://ipapi.co/json/").openConnection() as HttpURLConnection
                conn.connectTimeout = 6000; conn.readTimeout = 6000
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                val j = JSONObject(txt)
                "${j.optString("ip")}\n${j.optString("org")}\n${j.optString("city")}, ${j.optString("country_name")}"
            } catch (e: Exception) { "Alınamadı" }
            ui { if (mode == Mode.CONN) render() }
        }
    }

    private fun openGateway() {
        val gw = gatewayIp() ?: return
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://$gw"))) } catch (e: Exception) {}
    }

    private fun dnsServers(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val lp: LinkProperties? = cm.getLinkProperties(cm.activeNetwork)
            lp?.dnsServers?.joinToString(", ") { it.hostAddress ?: "" }?.ifBlank { "-" } ?: "-"
        } catch (e: Exception) { "-" }
    }

    private fun ipStr2(v: Int) = "${v and 0xFF}.${v shr 8 and 0xFF}.${v shr 16 and 0xFF}.${v shr 24 and 0xFF}"

    private fun shareCsv() {
        val sb = StringBuilder("SSID,BSSID,dBm,%,Band,Kanal,Guvenlik,WPS,Uretici\n")
        nets.sortedByDescending { it.level }.forEach { r ->
            sb.append("\"${ssidOf(r)}\",${r.BSSID},${r.level},${sigPct(r.level)},${bandOf(r.frequency)},${channelOf(r.frequency)},${secLabel(r.capabilities)},${if (r.capabilities.contains("WPS", true)) "Acik" else "Kapali"},${vendorOf(r.BSSID)}\n")
        }
        try {
            val i = Intent(Intent.ACTION_SEND); i.type = "text/csv"
            i.putExtra(Intent.EXTRA_SUBJECT, "Ağ listesi (CSV)")
            i.putExtra(Intent.EXTRA_TEXT, sb.toString())
            startActivity(Intent.createChooser(i, "CSV paylaş"))
        } catch (e: Exception) {}
    }

    // ================= ISI HARİTASI =================
    private fun buildHeatmap(): View {
        val root = col().apply { setBackgroundColor(cBg) }
        root.addView(header())
        root.addView(col().apply { setPadding(dp(14), dp(10), dp(14), 0); layoutParams = lp(MATCH, WRAP) }.also { it.addView(tabRow()) })

        val connected = try { (curRssi() != 0) && wifi.isWifiEnabled } catch (e: Exception) { false }
        heatInfo = TextView(this).apply {
            text = heatStatus(); setTextColor(cText); textSize = 13f
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        root.addView(heatInfo)

        if (!connected) {
            root.addView(TextView(this).apply {
                text = "Isı haritası için önce bir Wi-Fi ağına bağlan.\nSonra bu ekranda bulunduğun yere dokun, yürü, başka noktalara dokun."
                setTextColor(cMuted); textSize = 13f; setPadding(dp(16), dp(8), dp(16), dp(8))
            })
        }

        val hv = HeatmapView(this, heatSamples, heatBitmap, cCard2, cText,
            { val r = curRssi(); if (r != 0) r else -100 },
            { heatInfo?.text = heatStatus() })
        hv.layoutParams = lp(MATCH, 0, 1f)
        root.addView(hv)

        val b = row().apply { layoutParams = lp(MATCH, WRAP); setPadding(dp(14), dp(8), dp(14), dp(14)); setBackgroundColor(cBg) }
        b.addView(flatBtn("Zemin planı", cCard2, cText) { pickImage() }, lp(0, WRAP, 1f))
        b.addView(View(this), lp(dp(8), 1))
        b.addView(flatBtn("Geri al", cCard2, cText) { if (heatSamples.isNotEmpty()) { heatSamples.removeAt(heatSamples.size - 1); render() } }, lp(0, WRAP, 1f))
        b.addView(View(this), lp(dp(8), 1))
        b.addView(flatBtn("Temizle", cBrand) { heatSamples.clear(); heatBitmap = null; render() }, lp(0, WRAP, 1f))
        root.addView(b)
        return root
    }

    private fun heatStatus(): String {
        val rssi = curRssi()
        val cur = if (rssi != 0) "şu an $rssi dBm (%${sigPct(rssi)})" else "bağlı değil"
        return "${heatSamples.size} ölçüm noktası  •  $cur  •  haritaya dokunarak ekle"
    }

    @Suppress("DEPRECATION")
    private fun curRssi(): Int = try { wifi.connectionInfo?.rssi ?: 0 } catch (e: Exception) { 0 }

    private fun pickImage() {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT); i.type = "image/*"
            startActivityForResult(Intent.createChooser(i, "Zemin planı seç"), REQ_IMG)
        } catch (e: Exception) {}
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMG && resultCode == RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) try {
                contentResolver.openInputStream(uri)?.use { ins ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    heatBitmap = BitmapFactory.decodeStream(ins, null, opts)
                }
            } catch (e: Exception) {}
            if (mode == Mode.HEATMAP) render()
        }
    }

    private class HeatmapView(
        ctx: Context,
        val samples: MutableList<FloatArray>,
        val bmp: Bitmap?,
        val gridCol: Int,
        val textCol: Int,
        val rssi: () -> Int,
        val onSample: () -> Unit
    ) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 24f; isFakeBoldText = true }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_DOWN) {
                val nx = (e.x / width).coerceIn(0f, 1f)
                val ny = (e.y / height).coerceIn(0f, 1f)
                samples.add(floatArrayOf(nx, ny, rssi().toFloat()))
                onSample(); invalidate(); return true
            }
            return super.onTouchEvent(e)
        }

        private fun heatColor(r: Float): Int {
            val t = ((r.coerceIn(-90f, -40f)) + 90f) / 50f  // 0 zayıf .. 1 güçlü
            val rr: Int; val gg: Int
            if (t < 0.5f) { rr = 230; gg = (230 * (t / 0.5f)).toInt() }
            else { rr = (230 * (1 - (t - 0.5f) / 0.5f)).toInt(); gg = 200 }
            return Color.argb(130, rr, gg, 60)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width; val h = height
            // arka plan
            if (bmp != null) canvas.drawBitmap(bmp, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), null)
            else { p.color = gridCol; canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p) }

            if (samples.isNotEmpty()) {
                val step = (w / 46).coerceAtLeast(14)
                var gx = 0
                while (gx < w) {
                    var gy = 0
                    while (gy < h) {
                        val cx = gx + step / 2f; val cy = gy + step / 2f
                        var num = 0.0; var den = 0.0
                        for (s in samples) {
                            val sx = s[0] * w; val sy = s[1] * h
                            val d2 = ((cx - sx) * (cx - sx) + (cy - sy) * (cy - sy)).toDouble() + 1.0
                            val wg = 1.0 / (d2 * d2 / 1e6 + 1.0)
                            num += wg * s[2]; den += wg
                        }
                        if (den > 0) { p.color = heatColor((num / den).toFloat()); canvas.drawRect(gx.toFloat(), gy.toFloat(), (gx + step).toFloat(), (gy + step).toFloat(), p) }
                        gy += step
                    }
                    gx += step
                }
            }
            // ölçüm noktaları
            for (s in samples) {
                val x = s[0] * w; val y = s[1] * h
                p.color = Color.WHITE; canvas.drawCircle(x, y, 16f, p)
                p.color = Color.argb(255, 40, 60, 90); canvas.drawCircle(x, y, 12f, p)
                pt.color = textCol
                canvas.drawText("${s[2].toInt()}", x, y - 24f, pt)
            }
            if (samples.isEmpty()) {
                pt.color = textCol
                canvas.drawText("Bulunduğun yere dokun", w / 2f, h / 2f, pt)
            }
        }
    }

    // ================= ÖZEL GÖSTERGE =================
    private class GaugeView(ctx: Context, val value: Int, val col: Int, val track: Int) : View(ctx) {
        private val pBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val pFg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val pTx = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val sw = w * 0.11f
            pBg.strokeWidth = sw; pBg.color = (track and 0x00FFFFFF) or 0x33000000
            pFg.strokeWidth = sw; pFg.color = col
            val pad = sw / 2 + 1; val rect = RectF(pad, pad, w - pad, h - pad)
            canvas.drawArc(rect, 135f, 270f, false, pBg)
            canvas.drawArc(rect, 135f, 270f * (value / 100f), false, pFg)
            pTx.color = col; pTx.textSize = h * 0.32f
            val fm = pTx.fontMetrics
            canvas.drawText(value.toString(), w / 2, h / 2 - (fm.ascent + fm.descent) / 2, pTx)
        }
    }

    // ================= UI YARDIMCILARI =================
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun flow() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(5), 0, 0) }

    private fun card(bg: Int = cCard): LinearLayout = col().apply {
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(16).toFloat() }
        setPadding(dp(14), dp(13), dp(14), dp(13)); setElevation(dp(1).toFloat())
        layoutParams = lp(MATCH, WRAP).apply { setMargins(0, 0, 0, dp(10)) }
    }

    private fun chip(text: String, color: Int) = TextView(this).apply {
        this.text = text; textSize = 11.5f; setTextColor(color); setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply { setColor((color and 0x00FFFFFF) or 0x22000000); cornerRadius = dp(7).toFloat() }
        setPadding(dp(8), dp(3), dp(8), dp(3))
        val m = LinearLayout.LayoutParams(WRAP, WRAP); m.setMargins(0, 0, dp(6), 0); layoutParams = m
    }

    private fun toggleChip(text: String, active: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 12.5f; setTextColor(if (active) Color.WHITE else cMuted); setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply { setColor(if (active) cBrand else cCard); cornerRadius = dp(9).toFloat() }
        setPadding(dp(12), dp(6), dp(12), dp(6))
        val m = LinearLayout.LayoutParams(WRAP, WRAP); m.setMargins(0, 0, dp(6), 0); layoutParams = m
        setOnClickListener { onClick() }
    }

    private fun flatBtn(text: String, bg: Int, fg: Int = Color.WHITE, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 15f; setTextColor(fg); setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(22).toFloat() }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setOnClickListener { onClick() }
        layoutParams = lp(MATCH, WRAP)
    }

    private fun input(value: String, hint: String) = EditText(this).apply {
        setText(value); this.hint = hint; setTextColor(cText); setHintTextColor(cMuted); textSize = 15f
        inputType = InputType.TYPE_CLASS_TEXT
        background = GradientDrawable().apply { setColor(cCard2); cornerRadius = dp(10).toFloat() }
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = lp(MATCH, WRAP).apply { topMargin = dp(8) }
    }

    private fun toolTitle(t: String) = TextView(this).apply { text = t; setTextColor(cText); textSize = 15f; setTypeface(typeface, Typeface.BOLD) }
    private fun mono(t: String) = TextView(this).apply {
        text = t; setTextColor(cMuted); textSize = 13f; setPadding(0, dp(8), 0, 0); typeface = Typeface.MONOSPACE
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t; setTextColor(cMuted); textSize = 12f; setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.06f; setPadding(dp(4), dp(10), 0, dp(6))
    }

    private fun group(title: String, rows: List<Pair<String, String>>): View {
        val wrap = col(); wrap.addView(sectionLabel(title)); wrap.layoutParams = lp(MATCH, WRAP)
        val box = col().apply {
            background = GradientDrawable().apply { setColor(cCard); cornerRadius = dp(16).toFloat() }
            layoutParams = lp(MATCH, WRAP).apply { setMargins(0, 0, 0, dp(2)) }
        }
        rows.forEachIndexed { i, (k, v) ->
            val r = row().apply {
                setBackgroundColor(if (i % 2 == 0) cCard2 else Color.TRANSPARENT)
                setPadding(dp(14), dp(12), dp(14), dp(12)); layoutParams = lp(MATCH, WRAP)
            }
            r.addView(TextView(this).apply { text = k; setTextColor(cText); textSize = 14f; layoutParams = lp(0, WRAP, 1f) })
            r.addView(TextView(this).apply { text = v; setTextColor(cText); textSize = 14f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.END })
            box.addView(r)
        }
        wrap.addView(box); return wrap
    }

    private fun banner(title: String, body: String, color: Int): View {
        val c = card()
        c.background = GradientDrawable().apply { setColor((color and 0x00FFFFFF) or 0x1A000000); cornerRadius = dp(16).toFloat() }
        c.addView(TextView(this).apply { text = title; setTextColor(color); textSize = 15f; setTypeface(typeface, Typeface.BOLD) })
        c.addView(TextView(this).apply { text = body; setTextColor(cMuted); textSize = 13f; setPadding(0, dp(2), 0, 0) })
        return c
    }

    private fun dot(color: Int, glyph: String) = TextView(this).apply {
        text = glyph; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply { setColor(color); shape = GradientDrawable.OVAL }
        val s = dp(34); layoutParams = LinearLayout.LayoutParams(s, s); setPadding(0, dp(6), 0, 0)
    }

    private fun progressBar(p: Float): View {
        val track = col().apply {
            background = GradientDrawable().apply { setColor(cCard2); cornerRadius = dp(6).toFloat() }
            layoutParams = lp(MATCH, dp(8)).apply { setMargins(0, 0, 0, dp(12)) }
        }
        track.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(cBrand); cornerRadius = dp(6).toFloat() }
            layoutParams = LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.82f * p).toInt().coerceAtLeast(dp(4)), dp(8))
        })
        return track
    }

    private fun topBar(title: String): View {
        val bar = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cBrand, cBrand2))
            setPadding(dp(10), dp(16), dp(16), dp(14)); layoutParams = lp(MATCH, WRAP)
        }
        bar.addView(TextView(this).apply {
            text = "‹"; setTextColor(Color.WHITE); textSize = 30f; setPadding(dp(8), 0, dp(12), dp(4))
            setOnClickListener { onBackPressed() }
        })
        bar.addView(TextView(this).apply { text = title; setTextColor(Color.WHITE); textSize = 20f; setTypeface(typeface, Typeface.BOLD); maxLines = 1 })
        return bar
    }

    private fun bottomBar(text: String, onClick: () -> Unit): View {
        val wrap = col().apply { setPadding(dp(14), dp(8), dp(14), dp(14)); setBackgroundColor(cBg); layoutParams = lp(MATCH, WRAP) }
        wrap.addView(flatBtn(text, cBrand, Color.WHITE, onClick))
        return wrap
    }

    private fun channelAdvice(): View? {
        val b24 = nets.filter { bandOf(it.frequency) == "2.4 GHz" }
        if (b24.size < 3) return null
        val use = listOf(1, 6, 11).associateWith { ch -> b24.count { channelOf(it.frequency) == ch } }
        val best = use.minByOrNull { it.value } ?: return null
        return banner("Kanal önerisi", "2.4 GHz'de en boş kanal: ${best.key} (${best.value} ağ). Modeminden değiştirebilirsin.", cBrand)
    }

    // ================= HESAP/YARDIMCI =================
    private fun filtered(): List<ScanResult> {
        var l = nets
        if (bandFilter == 24) l = l.filter { bandOf(it.frequency) == "2.4 GHz" }
        if (bandFilter == 5) l = l.filter { bandOf(it.frequency) == "5 GHz" }
        return if (sortBySignal) l.sortedByDescending { it.level } else l.sortedByDescending { secRank(it.capabilities) }
    }

    private fun score(rssi: Int): Int = ((rssi.coerceIn(-90, -30) + 90) * 100 / 60).coerceIn(0, 100)
    private fun sigPct(rssi: Int): Int = score(rssi)
    private fun scoreColor(s: Int) = if (s >= 75) cGood else if (s >= 50) cWarn else cBad
    private fun qualityWord(s: Int) = when { s >= 80 -> "Mükemmel"; s >= 60 -> "İyi"; s >= 40 -> "Orta"; else -> "Zayıf" }
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
        freq in 2400..2500 -> "2.4 GHz"; freq in 4900..5900 -> "5 GHz"
        freq in 5925..7125 -> "6 GHz"; else -> "-"
    }
    private fun widthOf(r: ScanResult): String = when (r.channelWidth) {
        ScanResult.CHANNEL_WIDTH_20MHZ -> "20MHz"
        ScanResult.CHANNEL_WIDTH_40MHZ -> "40MHz"
        ScanResult.CHANNEL_WIDTH_80MHZ -> "80MHz"
        ScanResult.CHANNEL_WIDTH_160MHZ -> "160MHz"
        ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> "80+80MHz"
        else -> ""
    }
    private fun stdOf(r: ScanResult): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return when (r.wifiStandard) {
            ScanResult.WIFI_STANDARD_LEGACY -> "a/b/g"
            ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (n)"
            ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (ac)"
            ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6 (ax)"
            else -> null
        }
    }
    private fun connStd(info: android.net.wifi.WifiInfo?): String {
        if (info == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "-"
        return when (info.wifiStandard) {
            ScanResult.WIFI_STANDARD_LEGACY -> "a/b/g"
            ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (n)"
            ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (ac)"
            ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6 (ax)"
            else -> "-"
        }
    }
    private fun secRank(cap: String): Int {
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
    private fun secLabel(cap: String) = when (secRank(cap)) {
        5 -> "WPA3"; 3 -> "WPA2"; 2 -> "WPA"; 1 -> "WEP"; 0 -> "Açık"; else -> "?"
    }
    private fun secColor(rank: Int) = if (rank <= 1) cBad else if (rank == 2) cWarn else cGood
    private fun secScore(r: ScanResult): Int {
        var s = when (secRank(r.capabilities)) { 5 -> 100; 3 -> 80; 2 -> 55; 1 -> 25; 0 -> 5; else -> 50 }
        if (r.capabilities.contains("WPS", true)) s -= 20
        return s.coerceIn(0, 100)
    }
    private fun ssidOf(r: ScanResult): String = try {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            r.wifiSsid?.toString()?.trim('"') ?: r.SSID ?: "" else r.SSID ?: ""
    } catch (e: Exception) { r.SSID ?: "" }
    private fun vendorOf(mac: String?): String {
        if (mac.isNullOrBlank()) return ""
        return OUI[mac.replace(":", "").uppercase().take(6)] ?: ""
    }
    private fun devType(d: Dev): String {
        if (d.gw) return "Modem / yönlendirici"
        val v = d.vendor.lowercase()
        return when {
            d.me -> "Bu telefon"
            v.contains("apple") -> "Apple cihaz (iPhone/Mac)"
            v.contains("samsung") || v.contains("xiaomi") || v.contains("huawei") -> "Telefon / tablet"
            v.contains("tp-link") || v.contains("zte") || v.contains("zyxel") || v.contains("ubiquiti") -> "Ağ cihazı"
            v.contains("intel") -> "Bilgisayar"
            d.vendor.isNotBlank() -> d.vendor
            else -> "Bilinmeyen cihaz"
        }
    }
    private fun devIcon(d: Dev): String = when {
        d.gw -> "📶"; d.me -> "📱"
        d.vendor.lowercase().let { it.contains("apple") || it.contains("samsung") || it.contains("xiaomi") || it.contains("huawei") } -> "📱"
        else -> "💻"
    }

    private fun selfIp(): String? = try {
        @Suppress("DEPRECATION")
        val i = wifi.connectionInfo?.ipAddress ?: 0
        if (i == 0) null else "${i and 0xFF}.${i shr 8 and 0xFF}.${i shr 16 and 0xFF}.${i shr 24 and 0xFF}"
    } catch (e: Exception) { null }

    private fun gatewayIp(): String? = try {
        @Suppress("DEPRECATION")
        val g = wifi.dhcpInfo?.gateway ?: 0
        if (g == 0) null else "${g and 0xFF}.${g shr 8 and 0xFF}.${g shr 16 and 0xFF}.${g shr 24 and 0xFF}"
    } catch (e: Exception) { null }

    private fun tcpOpen(ip: String, port: Int, timeout: Int): Boolean = try {
        Socket().use { s -> s.connect(InetSocketAddress(ip, port), timeout); true }
    } catch (e: Exception) { false }

    private fun httpOk(url: String): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000; c.requestMethod = "GET"
        val code = c.responseCode; c.disconnect(); code in 200..399
    } catch (e: Exception) { false }

    private fun readArp(): Map<String, String> {
        val m = HashMap<String, String>()
        try {
            val f = File("/proc/net/arp"); if (!f.exists()) return m
            BufferedReader(FileReader(f)).use { br ->
                br.readLine(); var line = br.readLine()
                while (line != null) {
                    val p = line.split(Regex("\\s+"))
                    if (p.size >= 4 && p[3].matches(Regex("..:..:..:..:..:..")) && p[3] != "00:00:00:00:00:00")
                        m[p[0]] = p[3].uppercase()
                    line = br.readLine()
                }
            }
        } catch (e: Exception) {}
        return m
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        receiver?.let { runCatching { unregisterReceiver(it) } }
        io.shutdownNow()
    }

    companion object {
        val OUI = mapOf(
            "5478F0" to "ZTE", "D871F0" to "ZTE", "0C12FB" to "Zyxel", "E8D8D1" to "Zyxel",
            "AC9E17" to "TP-Link", "50C7BF" to "TP-Link", "98DED0" to "TP-Link", "C006C3" to "TP-Link",
            "14CC20" to "TP-Link", "B0BE76" to "TP-Link", "3C0754" to "Apple", "F0DBF8" to "Apple",
            "A4C361" to "Apple", "5CF6DC" to "Samsung", "8425DB" to "Samsung", "286C07" to "Xiaomi",
            "640980" to "Xiaomi", "F8A45F" to "Xiaomi", "286ED4" to "Huawei", "48DB50" to "Huawei",
            "D4619D" to "Intel", "602232" to "Ubiquiti", "D46E0E" to "Arcadyan"
        )
    }
}
