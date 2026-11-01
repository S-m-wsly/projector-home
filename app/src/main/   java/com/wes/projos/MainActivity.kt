package com.wes.projos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Calendar
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class AppItem(val label: String, val icon: Drawable, val intent: Intent, val pkg: String)

class MenuItem(val label: String, val action: () -> Unit)

class MainActivity : Activity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var bg: ImageView
    private lateinit var root: FrameLayout
    private lateinit var overlay: FrameLayout
    private lateinit var cursor: LiquidLens
    private lateinit var content: FrameLayout
    private lateinit var prefs: SharedPreferences
    private val h = Handler(Looper.getMainLooper())

    private val userName = "Wes"
    private var screen = "start"
    private var wallIndex = 0
    private var currentWall: Drawable? = null
    private var weather = "Loading weather..."
    private var weatherView: TextView? = null

    // home screen: dock-driven app row and liquid glass lenses
    private val catNames = listOf("For you", "Movies", "Music", "Browsers", "Downloader", "Others")
    private var curCat = 0
    private var allApps: List<AppItem> = emptyList()
    private var dockLens: LiquidLens? = null
    private var dockBox: FrameLayout? = null
    private var dockItems: List<TextView> = emptyList()
    private var appRow: LinearLayout? = null
    private var appScroll: HorizontalScrollView? = null
    private var catLabel: TextView? = null
    private var zone: String? = null
    private var cursorOn = false
    private var dockSnap = false
    private val revealRun = Runnable {
        val n = currentFocus
        if (n != null && n.tag is String && n.tag != "dock") {
            val pad = dp(if (n.tag == "apps") 4 else 3).toFloat()
            cursor.radius = dp(14) + pad
            cursor.provider = { rectIn(n, root, pad) }
            whenLaidOut(n) {
                cursor.reveal()
                cursorOn = true
            }
        }
    }

    private val walls: List<Pair<String, IntArray>> = listOf(
        Pair("Midnight", intArrayOf(Color.rgb(15, 32, 39), Color.rgb(32, 58, 67), Color.rgb(44, 83, 100))),
        Pair("Sunset", intArrayOf(Color.rgb(60, 20, 60), Color.rgb(190, 70, 80), Color.rgb(245, 160, 90))),
        Pair("Forest", intArrayOf(Color.rgb(10, 40, 30), Color.rgb(25, 90, 70), Color.rgb(60, 140, 110))),
        Pair("Aurora", intArrayOf(Color.rgb(20, 20, 60), Color.rgb(60, 50, 140), Color.rgb(40, 150, 160))),
        Pair("Ember", intArrayOf(Color.rgb(40, 10, 20), Color.rgb(120, 30, 50), Color.rgb(200, 90, 60))),
        Pair("Ocean", intArrayOf(Color.rgb(5, 30, 70), Color.rgb(10, 90, 150), Color.rgb(80, 170, 200)))
    )

    private val autoWall: Runnable = object : Runnable {
        override fun run() {
            if (prefs.getInt("wall", -1) == -1) {
                wallIndex = (wallIndex + 1) % walls.size
                setWall(wallIndex)
            }
            h.postDelayed(this, 30000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("projos", Context.MODE_PRIVATE)
        root = FrameLayout(this)
        bg = ImageView(this)
        bg.scaleType = ImageView.ScaleType.FIT_XY
        content = FrameLayout(this)
        root.addView(bg, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = FrameLayout(this)
        cursor = LiquidLens(this, true)
        overlay.addView(cursor, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, n -> onFocusMoved(n) }
        val saved = prefs.getInt("wall", -1)
        wallIndex = if (saved >= 0) saved else 0
        setWall(wallIndex)
        startScreen()
        loadWeather()
    }

    override fun onResume() {
        super.onResume()
        h.removeCallbacks(autoWall)
        h.postDelayed(autoWall, 30000)
        if (screen == "home") homeScreen()
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(autoWall)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (screen != "start") homeScreen()
    }

    override fun onBackPressed() {
        when (screen) {
            "home" -> startScreen()
            "settings" -> homeScreen()
            "sources", "custom" -> settingsScreen()
            "wallpaper" -> customizationScreen()
            else -> {}
        }
    }

    // ---------- helpers ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun lp(w: Int, ht: Int, top: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, ht)
        p.topMargin = dp(top)
        return p
    }

    private fun glass(radius: Int, fill: Int, stroke: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(radius).toFloat()
        g.setStroke(dp(1), stroke)
        return g
    }

    // the liquid glass cursor draws the focus highlight, so items look the same focused or not
    private fun focusBg(radius: Int): GradientDrawable =
        glass(radius, Color.argb(50, 255, 255, 255), Color.argb(90, 255, 255, 255))

    private fun glassButton(text: String, size: Float, grp: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(20), dp(10), dp(20), dp(10))
        t.background = focusBg(14)
        t.tag = grp
        t.isFocusable = true
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    private fun show(v: View, name: String) {
        h.removeCallbacks(revealRun)
        cursor.hide()
        cursorOn = false
        zone = null
        if (name != "home") {
            dockLens = null
            dockBox = null
            appRow = null
            appScroll = null
            dockItems = emptyList()
        }
        content.removeAllViews()
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        screen = name
    }

    // ---------- wallpaper ----------

    private fun setWall(i: Int) {
        val next: Drawable = GradientDrawable(GradientDrawable.Orientation.TL_BR, walls[i].second)
        val old = currentWall
        if (old == null) {
            bg.setImageDrawable(next)
        } else {
            val t = TransitionDrawable(arrayOf(old, next))
            bg.setImageDrawable(t)
            t.startTransition(1500)
        }
        currentWall = next
    }

    // ---------- weather ----------

    private fun weatherIcon(code: Int): String = when (code) {
        0 -> "☀️"
        1, 2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..67 -> "🌧️"
        in 71..77 -> "❄️"
        in 80..82 -> "🌦️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }

    private fun loadWeather() {
        thread {
            var text = "Weather unavailable"
            try {
                val u = URL("https://api.open-meteo.com/v1/forecast?latitude=-22.56&longitude=17.08&current=temperature_2m,weather_code")
                val c = u.openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val body = c.inputStream.bufferedReader().readText()
                c.disconnect()
                val cur = JSONObject(body).getJSONObject("current")
                val t = Math.round(cur.getDouble("temperature_2m")).toInt()
                text = weatherIcon(cur.getInt("weather_code")) + "  " + t + "°C  Windhoek"
            } catch (e: Exception) {
            }
            h.post {
                weather = text
                weatherView?.text = text
            }
        }
        h.postDelayed({ loadWeather() }, 900000)
    }

    // ---------- screens ----------

    private fun startScreen() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER

        val clock = TextClock(this)
        clock.format12Hour = "h:mm"
        clock.format24Hour = "HH:mm"
        clock.textSize = 96f
        clock.setTextColor(Color.WHITE)
        clock.gravity = Gravity.CENTER

        val date = TextClock(this)
        date.format12Hour = "EEEE, d MMMM"
        date.format24Hour = "EEEE, d MMMM"
        date.textSize = 24f
        date.setTextColor(Color.argb(220, 255, 255, 255))
        date.gravity = Gravity.CENTER

        val who = glassButton(userName, 28f, "start") { homeScreen() }

        val hint = TextView(this)
        hint.text = "Press OK to continue"
        hint.textSize = 16f
        hint.setTextColor(Color.argb(190, 255, 255, 255))
        hint.gravity = Gravity.CENTER

        col.addView(clock, lp(WRAP, WRAP))
        col.addView(date, lp(WRAP, WRAP, 4))
        col.addView(who, lp(dp(220), WRAP, 48))
        col.addView(hint, lp(WRAP, WRAP, 12))
        show(col, "start")
        who.requestFocus()
    }

    private fun wifiLabel(): String {
        return try {
            val w = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val s: String = w.connectionInfo?.ssid ?: ""
            val n = s.trim('"')
            if (n.isEmpty() || n.startsWith("<unknown")) "Wi-Fi" else "Wi-Fi: " + n
        } catch (e: Exception) {
            "Wi-Fi"
        }
    }

    private fun openWifi() {
        try {
            startActivity(Intent("android.settings.panel.action.INTERNET_CONNECTIVITY"))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (e2: Exception) {
                toast("Could not open Wi-Fi")
            }
        }
    }

    private fun loadApps(): List<AppItem> {
        val pm = packageManager
        val seen = HashSet<String>()
        val out = ArrayList<AppItem>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                val i = Intent(Intent.ACTION_MAIN)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                out.add(AppItem(ri.loadLabel(pm).toString(), ri.loadIcon(pm), i, pkg))
            }
        }
        out.sortBy { it.label.lowercase() }
        return out
    }

    private fun appTile(a: AppItem): View {
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.gravity = Gravity.CENTER_HORIZONTAL
        t.setPadding(dp(12), dp(10), dp(12), dp(8))
        val iv = ImageView(this)
        iv.setImageDrawable(a.icon)
        t.addView(iv, LinearLayout.LayoutParams(dp(64), dp(64)))
        val tv = TextView(this)
        tv.text = a.label
        tv.textSize = 13f
        tv.setTextColor(Color.WHITE)
        tv.gravity = Gravity.CENTER
        tv.maxLines = 1
        tv.ellipsize = TextUtils.TruncateAt.END
        t.addView(tv, lp(dp(96), WRAP, 6))
        t.background = focusBg(14)
        t.tag = "apps"
        t.id = View.generateViewId()
        t.isFocusable = true
        t.isClickable = true
        t.setOnClickListener {
            prefs.edit().putInt("use_" + a.pkg, prefs.getInt("use_" + a.pkg, 0) + 1).apply()
            try {
                startActivity(a.intent)
            } catch (e: Exception) {
                toast("Could not open " + a.label)
            }
        }
        return t
    }

    // ---------- home: dock-driven suggestions + liquid glass ----------

    private fun greeting(): String {
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = if (hr < 12) "morning" else if (hr < 18) "afternoon" else "evening"
        return "Good " + part + ", " + userName
    }

    private fun catOf(a: AppItem): Int {
        val s = (a.pkg + " " + a.label).lowercase()
        fun has(vararg k: String): Boolean = k.any { s.contains(it) }
        return when {
            has("downloader", "apkpure", "aptoide") -> 4
            has("spotify", "music", "deezer", "tidal", "audio", "podcast") -> 2
            has("chrome", "brave", "firefox", "opera", "browser", "puffin", "tvbro", "bromite") -> 3
            has("netflix", "youtube", "video", "hbo", "disney", "hulu", "plex", "kodi", "vlc",
                "showmax", "movie", "twitch", "iptv", "stremio", "lftv") -> 1
            else -> 5
        }
    }

    private fun uses(a: AppItem): Int = prefs.getInt("use_" + a.pkg, 0)

    private fun appsFor(cat: Int): List<AppItem> {
        if (cat == 0) {
            return allApps.sortedWith(compareByDescending<AppItem> { uses(it) }.thenBy { it.label.lowercase() })
        }
        return allApps.filter { catOf(it) == cat }
    }

    private fun fillApps() {
        val row = appRow ?: return
        row.removeAllViews()
        catLabel?.text = if (curCat == 0) "Suggested" else catNames[curCat]
        dockItems.forEachIndexed { i, t ->
            t.alpha = if (i == curCat) 1f else 0.8f
            t.setTypeface(null, if (i == curCat) Typeface.BOLD else Typeface.NORMAL)
        }
        val list = appsFor(curCat)
        val up = dockItems.getOrNull(curCat)
        if (list.isEmpty()) {
            val e = TextView(this)
            e.text = "No apps here yet"
            e.textSize = 16f
            e.setTextColor(Color.argb(200, 255, 255, 255))
            e.setPadding(dp(16), dp(30), dp(16), dp(30))
            row.addView(e, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        for (a in list) {
            val tile = appTile(a)
            if (up != null) tile.nextFocusUpId = up.id
            val p = LinearLayout.LayoutParams(WRAP, WRAP)
            p.rightMargin = dp(8)
            row.addView(tile, p)
        }
        val first = if (list.isEmpty()) null else row.getChildAt(0)
        for (t in dockItems) t.nextFocusDownId = first?.id ?: View.NO_ID
        appScroll?.scrollTo(0, 0)
        row.alpha = 0f
        row.translationY = dp(10).toFloat()
        row.animate().alpha(1f).translationY(0f).setDuration(240).start()
    }

    private fun setCategory(k: Int) {
        if (k < 0 || k == curCat) return
        curCat = k
        fillApps()
    }

    private fun dockItem(name: String, k: Int): TextView {
        val t = TextView(this)
        t.text = name
        t.textSize = 15f
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(20), dp(12), dp(20), dp(12))
        t.tag = "dock"
        t.id = View.generateViewId()
        t.isFocusable = true
        t.isClickable = true
        t.setOnClickListener {
            setCategory(k)
            appRow?.getChildAt(0)?.requestFocus()
        }
        return t
    }

    private fun homeScreen() {
        allApps = loadApps()

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(32), dp(24), dp(32), dp(28))

        // top bar: clock | category dock | wifi, weather, settings
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val clock = TextClock(this)
        clock.format12Hour = "h:mm"
        clock.format24Hour = "HH:mm"
        clock.textSize = 28f
        clock.setTextColor(Color.WHITE)
        val date = TextClock(this)
        date.format12Hour = "EEEE, d MMMM"
        date.format24Hour = "EEEE, d MMMM"
        date.textSize = 13f
        date.setTextColor(Color.argb(220, 255, 255, 255))
        left.addView(clock, lp(WRAP, WRAP))
        left.addView(date, lp(WRAP, WRAP))

        val box = FrameLayout(this)
        box.background = glass(32, Color.argb(46, 255, 255, 255), Color.argb(120, 255, 255, 255))
        val lens = LiquidLens(this, false)
        box.addView(lens, FrameLayout.LayoutParams(MATCH, MATCH))
        val itemsRow = LinearLayout(this)
        itemsRow.orientation = LinearLayout.HORIZONTAL
        itemsRow.setPadding(dp(6), dp(6), dp(6), dp(6))
        val items = ArrayList<TextView>()
        for ((k, name) in catNames.withIndex()) {
            val di = dockItem(name, k)
            items.add(di)
            itemsRow.addView(di, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        box.addView(itemsRow, FrameLayout.LayoutParams(WRAP, WRAP))

        val right = LinearLayout(this)
        right.orientation = LinearLayout.HORIZONTAL
        right.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        val wifi = glassButton(wifiLabel(), 16f, "top") { openWifi() }
        val wx = TextView(this)
        wx.text = weather
        wx.textSize = 16f
        wx.setTextColor(Color.WHITE)
        wx.gravity = Gravity.CENTER
        wx.setPadding(dp(12), 0, dp(12), 0)
        weatherView = wx
        val set = glassButton("Settings", 16f, "top") { settingsScreen() }
        right.addView(wifi)
        right.addView(wx)
        right.addView(set)

        bar.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(box, LinearLayout.LayoutParams(WRAP, WRAP))
        bar.addView(right, LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(bar, lp(MATCH, WRAP))

        // greeting
        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        mid.gravity = Gravity.CENTER_VERTICAL
        val hi = TextView(this)
        hi.text = greeting()
        hi.textSize = 44f
        hi.setTypeface(null, Typeface.BOLD)
        hi.setTextColor(Color.WHITE)
        val sub = TextView(this)
        sub.text = "Pick up where you left off"
        sub.textSize = 18f
        sub.setTextColor(Color.argb(230, 255, 255, 255))
        mid.addView(hi, lp(WRAP, WRAP))
        mid.addView(sub, lp(WRAP, WRAP, 4))
        col.addView(mid, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // category label + glass dock with the apps of the chosen category
        val lbl = TextView(this)
        lbl.textSize = 18f
        lbl.setTypeface(null, Typeface.BOLD)
        lbl.setTextColor(Color.WHITE)
        col.addView(lbl, lp(WRAP, WRAP))

        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.background = glass(28, Color.argb(46, 255, 255, 255), Color.argb(120, 255, 255, 255))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(14), dp(14), dp(14), dp(14))
        scroll.addView(row)
        col.addView(scroll, lp(WRAP, WRAP, 8))

        dockItems = items
        dockBox = box
        dockLens = lens
        appRow = row
        appScroll = scroll
        catLabel = lbl
        fillApps()

        show(col, "home")
        dockSnap = true
        items[curCat].requestFocus()
    }

    // ---------- liquid glass focus handling ----------

    private fun rectIn(v: View, host: ViewGroup, pad: Float): RectF? {
        return try {
            val r = Rect(0, 0, v.width, v.height)
            host.offsetDescendantRectToMyCoords(v, r)
            RectF(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
        } catch (e: Exception) {
            null
        }
    }

    private fun whenLaidOut(v: View, run: () -> Unit) {
        if (v.width > 0 && v.height > 0) {
            run()
            return
        }
        val vto = v.viewTreeObserver
        vto.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (v.width > 0) {
                    if (vto.isAlive) vto.removeOnGlobalLayoutListener(this)
                    run()
                }
            }
        })
    }

    private fun onFocusMoved(n: View?) {
        if (n == null) {
            h.removeCallbacks(revealRun)
            cursor.hide()
            cursorOn = false
            dockLens?.active = false
            zone = null
            return
        }
        val g = n.tag as? String ?: return

        if (g == "dock") {
            h.removeCallbacks(revealRun)
            cursor.hide()
            cursorOn = false
            zone = "dock"
            val dl = dockLens
            val box = dockBox
            if (dl != null && box != null) {
                dl.active = true
                val snap = dockSnap
                dockSnap = false
                whenLaidOut(n) {
                    dl.provider = { rectIn(n, box, 0f) }
                    if (snap) dl.reveal() else dl.glide()
                }
            }
            setCategory(dockItems.indexOfFirst { it === n })
            return
        }

        dockLens?.active = false
        val changed = g != zone
        zone = g
        val pad = dp(if (g == "apps") 4 else 3).toFloat()
        cursor.radius = dp(14) + pad
        cursor.provider = { rectIn(n, root, pad) }
        if (changed || !cursorOn) {
            // moving to a different group: no travel, the cursor appears fresh on the new item
            cursor.hide()
            cursorOn = false
            h.removeCallbacks(revealRun)
            h.postDelayed(revealRun, 90)
        } else {
            whenLaidOut(n) { cursor.glide() }
        }
    }

    private fun menu(title: String, name: String, items: List<MenuItem>, back: () -> Unit) {
        val outer = FrameLayout(this)
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(28), dp(24), dp(28), dp(24))
        panel.background = glass(24, Color.argb(70, 255, 255, 255), Color.argb(110, 255, 255, 255))

        val tt = TextView(this)
        tt.text = title
        tt.textSize = 30f
        tt.setTextColor(Color.WHITE)
        tt.setTypeface(null, Typeface.BOLD)
        panel.addView(tt, lp(WRAP, WRAP))

        val sv = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        var first: View? = null
        for (m in items) {
            val b = glassButton(m.label, 20f, "menu") { m.action() }
            if (first == null) first = b
            list.addView(b, lp(MATCH, WRAP, 10))
        }
        val bk = glassButton("‹ Back", 20f, "menu") { back() }
        list.addView(bk, lp(MATCH, WRAP, 22))
        sv.addView(list)
        panel.addView(sv, lp(MATCH, WRAP, 8))

        val fl = FrameLayout.LayoutParams(dp(560), WRAP)
        fl.gravity = Gravity.CENTER
        outer.addView(panel, fl)
        show(outer, name)
        val target: View = first ?: bk
        target.requestFocus()
    }

    private fun settingsScreen() {
        val items = ArrayList<MenuItem>()
        items.add(MenuItem("Sources") { sourcesScreen() })
        items.add(MenuItem("Customization") { customizationScreen() })
        items.add(MenuItem("Wi-Fi") { openWifi() })
        items.add(MenuItem("Picture & Sound") { openSys("PictureSettingsActivity") })
        items.add(MenuItem("Focus") { openSys("FocusSettingsActivity") })
        items.add(MenuItem("All system settings") { openSys("MainSettings") })
        menu("Settings", "settings", items) { homeScreen() }
    }

    private fun sourcesScreen() {
        val items = ArrayList<MenuItem>()
        try {
            val tm = getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
            val all: List<TvInputInfo> = tm.tvInputList
            val hdmi: TvInputInfo? = all.filter { it.type == TvInputInfo.TYPE_HDMI }.minByOrNull { it.id }
            val av: TvInputInfo? = all.firstOrNull { it.type == TvInputInfo.TYPE_COMPOSITE }
            if (hdmi != null) {
                val id = hdmi.id
                items.add(MenuItem("HDMI") { openInput(id) })
            }
            if (av != null) {
                val id = av.id
                items.add(MenuItem("AV") { openInput(id) })
            }
        } catch (e: Exception) {
            toast("Could not read sources")
        }
        menu("Sources", "sources", items) { settingsScreen() }
    }

    private fun customizationScreen() {
        val items = ArrayList<MenuItem>()
        items.add(MenuItem("Wallpaper") { wallpaperScreen() })
        menu("Customization", "custom", items) { settingsScreen() }
    }

    private fun wallpaperScreen() {
        val sel = prefs.getInt("wall", -1)
        val items = ArrayList<MenuItem>()
        val autoLabel = (if (sel == -1) "✓ " else "") + "Auto-change (every 30 s)"
        items.add(MenuItem(autoLabel) {
            prefs.edit().putInt("wall", -1).apply()
            wallpaperScreen()
        })
        for (i in walls.indices) {
            val label = (if (sel == i) "✓ " else "") + walls[i].first
            items.add(MenuItem(label) {
                prefs.edit().putInt("wall", i).apply()
                wallIndex = i
                setWall(i)
                wallpaperScreen()
            })
        }
        menu("Wallpaper", "wallpaper", items) { customizationScreen() }
    }

    private fun openInput(id: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id)))
        } catch (e: Exception) {
            toast("Could not open input")
        }
    }

    private fun openSys(suffix: String) {
        try {
            val pkg = "com.android.tv.settings"
            val info = packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            val a = info.activities?.firstOrNull { it.name.endsWith(suffix) }
            if (a == null) {
                toast("Screen not found")
                return
            }
            startActivity(Intent().setClassName(pkg, a.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Could not open screen")
        }
    }
}
