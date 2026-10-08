package com.echotrace.app

import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.activity.ComponentActivity

/** Local management only. No photo view, export, remote account listing or logs. */
class AdminActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { refreshStatus(); ui.postDelayed(this, 2000L) }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
            setPadding(dp(24), dp(20), dp(24), dp(28))
            setBackgroundColor(android.graphics.Color.rgb(29,27,25))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        text("ניהול אקוטרייס", 23)
        text("חלון מקומי, ללא PIN. עשר לחיצות הן דרך כניסה בלבד, לא מחסום אבטחה. אין כאן תמונות או מידע ממכשירים אחרים.", 13)
        status = text("", 14)
        button("בדיקת שרת וקבלת תמונה") { WorkScheduler.pollNow(this) }
        button("רענון הווידג׳ט המקומי") { WidgetRenderer.updateAll(this, force = true); refreshStatus() }
        text("תזמון", 19)
        choice("בדיקה באפליקציה פתוחה", listOf("30 שניות", "60 שניות", "120 שניות"), listOf(30,60,120), with(Prefs) { foregroundSeconds }) { with(Prefs) { foregroundSeconds = it } }
        choice("בדיקה ברקע", listOf("15 דקות", "30 דקות", "60 דקות"), listOf(15,30,60), with(Prefs) { backgroundMinutes }) { with(Prefs) { backgroundMinutes = it }; WorkScheduler.ensure(this) }
        text("Android עשוי לדחות בדיקות רקע מעבר לזמן שנבחר. שינוי תזמון אינו מבטיח מסירה מיידית.", 12)
        text("תמונות חדשות", 19)
        choice("רוחב מרבי לשליחה", listOf("800 פיקסלים", "1280 פיקסלים", "1600 פיקסלים"), listOf(800,1280,1600), with(Prefs) { photoWidth }) { with(Prefs) { photoWidth = it } }
        choice("איכות JPEG", listOf("65% - פחות נתונים", "85% - מאוזנת", "90% - גבוהה"), listOf(65,85,90), with(Prefs) { jpegQuality }) { with(Prefs) { jpegQuality = it } }
        toggle("העלאה ברשת לא מדודה בלבד (לרוב Wi-Fi)", with(Prefs) { wifiOnly }) { with(Prefs) { wifiOnly = it } }
        text("חל רק על שליחות חדשות. בתמונה שכבר בתור נשמרים תנאי הרשת המקוריים.", 12)
        text("תצוגה", 19)
        toggle("הנפשה עדינה בפתיחת המסך", with(Prefs) { animations }) { with(Prefs) { animations = it } }
        text("התמונה, הכיתוב ושעון הדעיכה לא משתנים. אין אפשרות לעקוף את כלל האדם האחד או לשמור היסטוריה.", 12)
        button("ניקוי אבחון מקומי בלבד") {
            AlertDialog.Builder(this).setMessage("לנקות הודעות אבחון? התמונה והחיבור לא יימחקו.")
                .setNegativeButton("ביטול", null).setPositiveButton("ניקוי") { _, _ ->
                    with(Prefs) { lastUploadDiag = null; syncError = null }
                    getSharedPreferences("echotrace", MODE_PRIVATE).edit().remove("lastWidgetError").remove("lastWidgetPushOk").apply()
                    refreshStatus()
                }.show()
        }
        button("איפוס הגדרות בלבד") {
            AlertDialog.Builder(this).setMessage("לחזור להגדרות ברירת המחדל? החיבור והתמונה נשמרים.")
                .setNegativeButton("ביטול", null).setPositiveButton("איפוס") { _, _ ->
                    with(Prefs) { foregroundSeconds=30; backgroundMinutes=15; photoWidth=1280; jpegQuality=85; wifiOnly=false; animations=true }
                    WorkScheduler.ensure(this); recreate()
                }.show()
        }
        button("סגירה") { finish() }
    }
    private fun text(value: String, size: Int): TextView = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(android.graphics.Color.rgb(228,219,208))
        setPadding(0,dp(10),0,dp(8)); root.addView(this)
    }
    private fun button(label: String, action: () -> Unit) {
        root.addView(Button(this).apply { text=label; isAllCaps=false; minHeight=dp(48); setOnClickListener { action() } })
    }
    private fun toggle(label: String, current: Boolean, change: (Boolean) -> Unit) {
        root.addView(Switch(this).apply { text=label; isChecked=current; minHeight=dp(48); setOnCheckedChangeListener { _, value -> change(value) } })
    }
    private fun choice(label: String, labels: List<String>, values: List<Int>, current: Int, change: (Int) -> Unit) {
        text(label,14)
        root.addView(Spinner(this).apply {
            adapter=ArrayAdapter(this@AdminActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            minimumHeight=dp(48); setSelection(values.indexOf(current).coerceAtLeast(0))
            onItemSelectedListener=object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { change(values[position]) }
            }
        })
    }
    private fun refreshStatus() {
        val ids=AppWidgetManager.getInstance(this).getAppWidgetIds(ComponentName(this, TraceWidgetProvider::class.java))
        val last=with(Prefs) { lastSyncAt }
        val time=if(last==0L) "טרם נבדק" else java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(last))
        val upload=with(Prefs) { lastUploadDiag } ?: "אין ניסיון שליחה"
        status.text="גרסה ${BuildConfig.VERSION_NAME}\nבדיקת שרת: $time\nבדיקה אחרונה: ${with(Prefs) { syncError } ?: "ללא שגיאה מתועדת"}\nוידג׳טים: ${ids.size}\nדחיפה מקומית: ${WidgetRenderer.lastPush(this) ?: "אין"}\nשגיאת וידג׳ט: ${WidgetRenderer.lastError(this) ?: "אין"}\nשליחה: $upload\nאישור מחיקה מהשרת: ${if(with(Prefs) { pendingConfirmation } == null) "אין אישור ממתין" else "ממתין לניסיון חוזר"}"
    }
    override fun onResume() { super.onResume(); refresh.run() }
    override fun onPause() { ui.removeCallbacks(refresh); super.onPause() }
}
