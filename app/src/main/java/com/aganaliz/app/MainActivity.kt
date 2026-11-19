package com.aganaliz.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.HorizontalScrollView
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
    private val uiH = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null

    private var nets: List<ScanResult> = emptyList()
    private var myBssid: String? = null
    private var gatewayPing: Long = -2L

    private enum class Mode { LIST, DETAIL, DEVICES }
    private var mode = Mode.LIST
    private var selected: ScanResult? = null
    private var sortBySignal = true
    private var bandFilter = 0

    private var devScanning = false
    private var devProgress = 0f
    private var devices: List<Dev> = emptyList()

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
        render()
        ensurePermissions()
    }

    private fun render() {
        when (mode) {
            Mode.LIST -> setContentView(buildList())
            Mode.DETAIL -> setContentView(buildDetail())
            Mode.DEVICES -> setContentView(buildDevices())
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (mode != Mode.LIST) { mode = Mode.LIST; render() } else super.onBackPressed()
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

    private fun startScan() {
        if (!wifi.isWifiEnabled) { toastStatus(); return }
        registerScan()
        @Suppress("DEPRECATION")
        val ok = try { wifi.startScan() } catch (e: Exception) { false }
        if (!ok) refreshData()
        pingGateway()
    }

    private fun toastStatus() { render() }

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
        Executors.newSingleThreadExecutor().execute {
            val t0 = System.currentTimeMillis()
            val ok = try { InetAddress.getByName(gw).isReachable(1200) } catch (e: Exception) { false }
            gatewayPing = if (ok) (System.currentTimeMillis() - t0) else -1L
            uiH.post { if (mode == Mode.LIST) render() }
        }
    }

    private fun buildList(): View {
        val root = col().apply { setBackgroundColor(cBg) }
        root.addView(header())
        val content = col().apply { setPadding(dp(14), dp(12), dp(14), dp(90)) }
        content.addView(tabRow())
        if (nets.isNotEmpty()) content.addView(controlsRow())
        if (!wifi.isWifiEnabled) {
            content.addView(banner("Wi-Fi kapalı", "Taramak için Wi-Fi'yi açın.", cBad))
        }
        val shown = filtered()
        val mine = nets.firstOrNull { it.BSSID.equals(myBssid, true) }
        if (mine != null) {
            content.addView(sectionLabel("BENİM AĞIM"))
            content.addView(netCard(mine, true))
            content.addView(myNetworkInfo(mine))
        }
        channelAdvice()?.let { content.addView(it) }
        content.addView(sectionLabel("YAKINDAKİ AĞLAR (${shown.size})"))
        if (shown.isEmpty()) {
            content.addView(banner("Ağ bulunamadı", "“Tara”ya dokun. Konum servisi açık olmalı.", cWarn))
        } else {
            shown.filter { !it.BSSID.equals(myBssid, true) }.forEach { content.addView(netCard(it, false)) }
        }
        val scroll = ScrollView(this).apply { addView(content); isFillViewport = true }
        root.addView(scroll, lp(MATCH, 0, 1f))
        root.addView(bottomBar("⟳  Yeniden Tara") { startScan() })
        return root
    }

    private fun header(): View {
        val h = col().apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cBrand, cBrand2)
            )
            setPadding(dp(18), dp(22), dp(18), dp(18))
            layoutParams = lp(MATCH, WRAP)
        }
        h.addView(TextView(this).apply {
            text = "Ağ Analiz"; setTextColor(Color.WHITE); textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        val total = nets.size
        val risky = nets.count { secRank(it.capabilities) <= 2 }
        h.addView(TextView(this).apply {
            text = if (total == 0) "Taramak için aşağıdaki düğmeye dokun"
            else "$total ağ  •  $risky risk altında  •  ${total - risky} güvenli"
            setTextColor(0xFFDCEBFF.toInt()); textSize = 13f
            setPadding(0, dp(4), 0, 0)
        })
        return h
    }

    private fun tabRow(): View {
        val row = row().apply { setPadding(0, 0, 0, dp(10)); layoutParams = lp(MATCH, WRAP) }
        row.addView(segBtn("Ağlar", mode != Mode.DEVICES) { mode = Mode.LIST; render() }, lp(0, WRAP, 1f))
        row.addView(View(this), lp(dp(8), 1))
        row.addView(segBtn("Cihazlar", mode == Mode.DEVICES) {
            mode = Mode.DEVICES; if (!devScanning && devices.isEmpty()) scanDevices() else render()
        }, lp(0, WRAP, 1f))
        return row
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
        val sv = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(bar); layoutParams = lp(MATCH, WRAP)
        }
        return sv
    }

    private fun toggleChip(text: String, active: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text; textSize = 12.5f
            setTextColor(if (active) Color.WHITE else cMuted); setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(if (active) cBrand else cCard); cornerRadius = dp(9).toFloat()
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            val m = LinearLayout.LayoutParams(WRAP, WRAP); m.setMargins(0, 0, dp(6), 0); layoutParams = m
            setOnClickListener { onClick() }
        }
    }

    private fun myNetworkInfo(mine: ScanResult): View {
        val c = card()
        val r = row().apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(MATCH, WRAP) }
        val ping = when {
            gatewayPing == -2L -> "ölçülüyor…"
            gatewayPing == -1L -> "yanıt yok"
            else -> "${gatewayPing} ms"
        }
        r.addView(TextView(this).apply {
            text = "🛈  Modem gecikmesi"; setTextColor(cText); textSize = 14f
            layoutParams = lp(0, WRAP, 1f)
        })
        r.addView(chip(ping, if (gatewayPing in 0..60) cGood else if (gatewayPing in 61..150) cWarn else cMuted))
        c.addView(r)
        return c
    }

    private fun netCard(r: ScanResult, mine: Boolean): View {
        val name = ssidOf(r)
        val sc = score(r.level)
        val rank = secRank(r.capabilities)
        val wps = r.capabilities.contains("WPS", true)
        val c = card(cCard)
        if (mine) c.setElevation(dp(4).toFloat())
        val top = row().apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(MATCH, WRAP) }
        val left = col().apply { layoutParams = lp(0, WRAP, 1f) }
        left.addView(TextView(this).apply {
            text = if (name.isBlank()) "Gizli Ağ" else name
            setTextColor(cText); textSize = 17f; setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        })
        val chips = flow()
        chips.addView(chip(secLabel(r.capabilities), secColor(rank)))
        chips.addView(chip(bandOf(r.frequency), cBrand))
        if (wps) chips.addView(chip("WPS", cWarn))
        left.addView(chips)
        val vend = vendorOf(r.BSSID)
        if (vend.isNotBlank()) left.addView(TextView(this).apply {
            text = vend; setTextColor(cMuted); textSize = 12f; setPadding(0, dp(3), 0, 0)
        })
        top.addView(left)
        top.addView(GaugeView(this, sc, scoreColor(sc), cMuted).also {
            it.layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
        })
        c.addView(top)
        val dist = distance(r.level, r.frequency)
        c.addView(TextView(this).apply {
            text = "${r.level} dBm" + (if (dist > 0) "   •   ~%.1f m".format(dist) else "") +
                   "   •   Kanal ${channelOf(r.frequency)}"
            setTextColor(cMuted); textSize = 12f; setPadding(0, dp(6), 0, 0)
        })
        c.setOnClickListener { selected = r; mode = Mode.DETAIL; render() }
        return c
    }

    private fun buildDetail(): View {
        val r = selected ?: run { mode = Mode.LIST; return buildList() }
        val root = col().apply { setBackgroundColor(cBg) }
        root.addView(topBar(if (ssidOf(r).isBlank()) "Gizli Ağ" else ssidOf(r)))
        val content = col().apply { setPadding(dp(14), dp(12), dp(14), dp(24)) }
        val sc = score(r.level); val rank = secRank(r.capabilities)
        val hero = card()
        val hr = row().apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(MATCH, WRAP) }
        hr.addView(GaugeView(this, sc, scoreColor(sc), cMuted).also {
            it.layoutParams = LinearLayout.LayoutParams(dp(78), dp(78))
        })
        val hc = col().apply { setPadding(dp(14), 0, 0, 0); layoutParams = lp(0, WRAP, 1f) }
        hc.addView(TextView(this).apply {
            text = qualityWord(sc); setTextColor(cText); textSize = 20f; setTypeface(typeface, Typeface.BOLD)
        })
        hc.addView(TextView(this).apply {
            text = "${r.level} dBm sinyal gücü"; setTextColor(cMuted); textSize = 13f
        })
        hr.addView(hc)
        hero.addView(hr)
        content.addView(hero)
        val overlap = nets.count { channelOf(it.frequency) == channelOf(r.frequency) && bandOf(it.frequency) == bandOf(r.frequency) } - 1
        val bad = overlap >= 2
        val chStat = card()
        val cr = row().apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(MATCH, WRAP) }
        cr.addView(dot(if (bad) cBad else cGood, if (bad) "!" else "✓"))
        val cc = col().apply { setPadding(dp(12), 0, 0, 0) }
        cc.addView(TextView(this).apply {
            text = "Kanal ${channelOf(r.frequency)} • ${bandOf(r.frequency)}"
            setTextColor(cText); textSize = 15f; setTypeface(typeface, Typeface.BOLD)
        })
        cc.addView(TextView(this).apply {
            text = if (bad) "$overlap ağ bu kanalda — sıkışık" else "Kanal nispeten boş"
            setTextColor(cMuted); textSize = 13f
        })
        cr.addView(cc); chStat.addView(cr); content.addView(chStat)
        content.addView(group("KİMLİK", listOf(
            "Üretici" to vendorOf(r.BSSID).ifBlank { "Bilinmiyor" },
            "MAC Adresi" to (r.BSSID ?: "-")
        )))
        content.addView(group("PERFORMANS", listOf(
            "Mesafe (tahmini)" to (distance(r.level, r.frequency).let { if (it > 0) "~%.1f m".format(it) else "-" }),
            "Sinyal" to "${r.level} dBm",
            "Skor" to "$sc / 100",
            "Frekans" to "${r.frequency} MHz"
        )))
        content.addView(group("GÜVENLİK", listOf(
            "Şifreleme" to secLabel(r.capabilities),
            "WPS" to if (r.capabilities.contains("WPS", true)) "Açık (önerilmez)" else "Kapalı"
        )))
        if (rank <= 1 || r.capabilities.contains("WPS", true)) {
            content.addView(banner(
                if (rank == 0) "Şifresiz ağ" else "Zayıf güvenlik",
                if (rank == 0) "Trafiğiniz görülebilir. Hassas işlem yapmayın."
                else "WPA2/WPA3 kullanın, WPS'i kapatın.", cBad
            ))
        }
        root.addView(ScrollView(this).apply { addView(content) }, lp(MATCH, 0, 1f))
        return root
    }

    private fun buildDevices(): View {
        val root = col().apply { setBackgroundColor(cBg) }
        root.addView(header())
        val content = col().apply { setPadding(dp(14), dp(12), dp(14), dp(90)) }
        content.addView(tabRow())
        content.addView(TextView(this).apply {
            text = if (devScanning) "Ağ taranıyor…" else "${devices.size} cihaz bulundu"
            setTextColor(cText); textSize = 16f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), 0, 0, dp(8))
        })
        if (devScanning) content.addView(progressBar(devProgress))
        devices.forEach { content.addView(devCard(it)) }
        if (!devScanning && devices.isEmpty())
            content.addView(banner("Cihaz yok", "Wi-Fi'ye bağlanıp yeniden tarayın.", cMuted))
        root.addView(ScrollView(this).apply { addView(content); isFillViewport = true }, lp(MATCH, 0, 1f))
        root.addView(bottomBar("⟳  Cihazları Tara") { scanDevices() })
        return root
    }

    private fun devCard(d: Dev): View {
        val c = card()
        val r = row().apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(MATCH, WRAP) }
        r.addView(TextView(this).apply {
            text = if (d.gw) "📶" else if (d.me) "📱" else "💻"
            textSize = 22f; setPadding(0, 0, dp(12), 0)
        })
        val col = col().apply { layoutParams = lp(0, WRAP, 1f) }
        col.addView(TextView(this).apply {
            text = d.ip; setTextColor(cText); textSize = 16f; setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "MAC: ${d.mac}" + (if (d.ping >= 0) "   •   ${d.ping} ms" else "")
            setTextColor(cMuted); textSize = 12f
        })
        if (d.vendor.isNotBlank()) col.addView(TextView(this).apply {
            text = d.vendor; setTextColor(cMuted); textSize = 12f
        })
        r.addView(col)
        if (d.me) r.addView(chip("Siz", cGood))
        else if (d.gw) r.addView(chip("Modem", cBrand))
        c.addView(r)
        return c
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
                val n = done.incrementAndGet()
                devProgress = n / 254f
                if (n % 16 == 0) uiH.post { if (mode == Mode.DEVICES) render() }
                if (n == 254) uiH.post { finishDevices(found, self, gw) }
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

    private class GaugeView(ctx: Context, val value: Int, val col: Int, val track: Int) : View(ctx) {
        private val pBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val pFg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val pTx = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val sw = w * 0.11f
            pBg.strokeWidth = sw; pBg.color = (track and 0x00FFFFFF) or 0x33000000
            pFg.strokeWidth = sw; pFg.color = col
            val pad = sw / 2 + 1
            val rect = RectF(pad, pad, w - pad, h - pad)
            canvas.drawArc(rect, 135f, 270f, false, pBg)
            canvas.drawArc(rect, 135f, 270f * (value / 100f), false, pFg)
            pTx.color = col; pTx.textSize = h * 0.32f
            val fm = pTx.fontMetrics
            canvas.drawText(value.toString(), w / 2, h / 2 - (fm.ascent + fm.descent) / 2, pTx)
        }
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun flow() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(5), 0, 0) }

    private fun card(bg: Int = cCard): LinearLayout {
        return col().apply {
            background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(16).toFloat() }
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setElevation(dp(1).toFloat())
            layoutParams = lp(MATCH, WRAP).apply { setMargins(0, 0, 0, dp(10)) }
        }
    }

    private fun chip(text: String, color: Int): TextView {
        return TextView(this).apply {
            this.text = text; textSize = 11.5f; setTextColor(color)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor((color and 0x00FFFFFF) or 0x22000000); cornerRadius = dp(7).toFloat()
            }
            setPadding(dp(8), dp(3), dp(8), dp(3))
            val m = LinearLayout.LayoutParams(WRAP, WRAP); m.setMargins(0, 0, dp(6), 0); layoutParams = m
        }
    }

    private fun segBtn(text: String, active: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text; gravity = Gravity.CENTER; textSize = 15f
            setTextColor(if (active) Color.WHITE else cMuted)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(if (active) cBrand else cCard); cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(10), dp(11), dp(10), dp(11))
            setOnClickListener { onClick() }
        }
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t; setTextColor(cMuted); textSize = 12f; setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.06f; setPadding(dp(4), dp(10), 0, dp(6))
    }

    private fun group(title: String, rows: List<Pair<String, String>>): View {
        val wrap = col()
        wrap.addView(sectionLabel(title))
        wrap.layoutParams = lp(MATCH, WRAP)
        val box = col().apply {
            background = GradientDrawable().apply { setColor(cCard); cornerRadius = dp(16).toFloat() }
            layoutParams = lp(MATCH, WRAP).apply { setMargins(0, 0, 0, dp(2)) }
        }
        rows.forEachIndexed { i, (k, v) ->
            val r = row().apply {
                setBackgroundColor(if (i % 2 == 0) cCard2 else Color.TRANSPARENT)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = lp(MATCH, WRAP)
            }
            r.addView(TextView(this).apply { text = k; setTextColor(cText); textSize = 14f; layoutParams = lp(0, WRAP, 1f) })
            r.addView(TextView(this).apply { text = v; setTextColor(cText); textSize = 14f; setTypeface(typeface, Typeface.BOLD) })
            box.addView(r)
        }
        wrap.addView(box)
        return wrap
    }

    private fun banner(title: String, body: String, color: Int): View {
        val c = card()
        c.background = GradientDrawable().apply {
            setColor((color and 0x00FFFFFF) or 0x1A000000); cornerRadius = dp(16).toFloat()
        }
        c.addView(TextView(this).apply { text = title; setTextColor(color); textSize = 15f; setTypeface(typeface, Typeface.BOLD) })
        c.addView(TextView(this).apply { text = body; setTextColor(cMuted); textSize = 13f; setPadding(0, dp(2), 0, 0) })
        return c
    }

    private fun dot(color: Int, glyph: String) = TextView(this).apply {
        text = glyph; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply { setColor(color); shape = GradientDrawable.OVAL }
        val s = dp(34); layoutParams = LinearLayout.LayoutParams(s, s)
        setPadding(0, dp(6), 0, 0)
    }

    private fun progressBar(p: Float): View {
        val track = col().apply {
            background = GradientDrawable().apply { setColor(cCard2); cornerRadius = dp(6).toFloat() }
            layoutParams = lp(MATCH, dp(8)).apply { setMargins(0, 0, 0, dp(12)) }
        }
        val fill = View(this).apply {
            background = GradientDrawable().apply { setColor(cBrand); cornerRadius = dp(6).toFloat() }
            layoutParams = LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.82f * p).toInt().coerceAtLeast(dp(4)), dp(8))
        }
        track.addView(fill)
        return track
    }

    private fun topBar(title: String): View {
        val bar = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cBrand, cBrand2))
            setPadding(dp(10), dp(18), dp(16), dp(16))
            layoutParams = lp(MATCH, WRAP)
        }
        bar.addView(TextView(this).apply {
            text = "‹"; setTextColor(Color.WHITE); textSize = 30f; setPadding(dp(8), 0, dp(12), dp(4))
            setOnClickListener { mode = Mode.LIST; render() }
        })
        bar.addView(TextView(this).apply {
            text = title; setTextColor(Color.WHITE); textSize = 20f; setTypeface(typeface, Typeface.BOLD); maxLines = 1
        })
        return bar
    }

    private fun bottomBar(text: String, onClick: () -> Unit): View {
        val wrap = col().apply { setPadding(dp(14), dp(8), dp(14), dp(14)); setBackgroundColor(cBg); layoutParams = lp(MATCH, WRAP) }
        wrap.addView(Button(this).apply {
            this.text = text; isAllCaps = false; textSize = 16f
            setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { setColor(cBrand); cornerRadius = dp(26).toFloat() }
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { onClick() }
            layoutParams = lp(MATCH, WRAP)
        })
        return wrap
    }

    private fun channelAdvice(): View? {
        val b24 = nets.filter { bandOf(it.frequency) == "2.4 GHz" }
        if (b24.size < 3) return null
        val use = listOf(1, 6, 11).associateWith { ch -> b24.count { channelOf(it.frequency) == ch } }
        val best = use.minByOrNull { it.value } ?: return null
        return banner("Kanal önerisi", "2.4 GHz'de en boş kanal: ${best.key} (${best.value} ağ). Modeminden değiştirebilirsin.", cBrand)
    }

    private fun filtered(): List<ScanResult> {
        var l = nets
        if (bandFilter == 24) l = l.filter { bandOf(it.frequency) == "2.4 GHz" }
        if (bandFilter == 5) l = l.filter { bandOf(it.frequency) == "5 GHz" }
        return if (sortBySignal) l.sortedByDescending { it.level } else l.sortedByDescending { secRank(it.capabilities) }
    }

    private fun score(rssi: Int): Int = ((rssi.coerceIn(-90, -30) + 90) * 100 / 60).coerceIn(0, 100)
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
    private fun ssidOf(r: ScanResult): String = try {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            r.wifiSsid?.toString()?.trim('"') ?: r.SSID ?: "" else r.SSID ?: ""
    } catch (e: Exception) { r.SSID ?: "" }
    private fun vendorOf(mac: String?): String {
        if (mac.isNullOrBlank()) return ""
        return OUI[mac.replace(":", "").uppercase().take(6)] ?: ""
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
