package com.dante.macros

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MacrosSession(ctx: Context) : VoiceInteractionSession(ctx) {

    private val ui = Handler(Looper.getMainLooper())
    private var editMode = false
    private var armedId: String? = null

    private val cText = 0xFFE2E8F0.toInt()
    private val cRow = 0xFF0F172A.toInt()
    private val cCard = 0xFF1E293B.toInt()
    private val cAmber = 0xFFFBBF24.toInt()
    private val cDanger = 0xFF7F1D1D.toInt()

    private fun dp(x: Int) = (x * context.resources.displayMetrics.density).toInt()

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        editMode = false
        armedId = null

        // Touch block active? Power-hold removes it instead of opening the picker.
        val svc = MacrosAccessibility.instance
        if (svc != null && svc.isBlocking) {
            svc.perform("BLOCK_TOUCHES")
            ui.post { hide() }
            return
        }

        val list = Store.shortcuts(context)
        val primary = list.firstOrNull { it.id == Store.primaryId(context) }
        if (Store.mode(context) == "primary" && primary != null) {
            setContentView(FrameLayout(context))
            pick(primary)
            return
        }
        showPicker()
    }

    private fun pick(s: Shortcut) {
        if (s.kind == Kind.ACTION) {
            hide()
            ui.postDelayed({ Runner.run(context, s) }, 350)
        } else {
            Runner.run(context, s)
            ui.post { hide() }
        }
    }

    // ---------- UI building blocks ----------
    private fun row(
        t: String,
        bg: Int = cRow,
        color: Int = cText,
        click: () -> Unit
    ) = TextView(context).apply {
        text = t
        textSize = 17f
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(16), 0)
        background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(8) }
        setOnClickListener { click() }
    }

    private fun chip(t: String, click: () -> Unit) = TextView(context).apply {
        text = t
        textSize = 14f
        setTextColor(cAmber)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(0x00000000)
            setStroke(dp(1), cAmber)
            cornerRadius = dp(20).toFloat()
        }
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { click() }
    }

    /** Bottom-sheet screen: title, optional right-hand chip, scrollable content. */
    private fun screen(title: String, right: View?, fill: (LinearLayout) -> Unit) {
        val d = context.resources.displayMetrics
        val dim = FrameLayout(context).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { hide() }
        }
        val r = dp(24).toFloat()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cCard)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(16), dp(16), dp(16), dp(36))
            isClickable = true
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, dp(8))
        }
        head.addView(TextView(context).apply {
            text = title
            textSize = 18f
            setTextColor(cAmber)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        if (right != null) head.addView(right)
        card.addView(head)

        val maxH = (d.heightPixels * 0.6f).toInt()
        val sv = object : ScrollView(context) {
            override fun onMeasure(w: Int, h: Int) {
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
            }
        }
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fill(rows)
        sv.addView(rows)
        card.addView(sv)
        dim.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(dim)
    }

    // ---------- Main picker (with edit mode) ----------
    private fun showPicker() {
        val list = Store.shortcuts(context)
        val edit = chip(if (editMode) "✔ Done" else "✏️ Edit") {
            editMode = !editMode
            armedId = null
            showPicker()
        }
        screen("🔧 Macros", edit) { rows ->
            list.forEach { s ->
                if (!editMode) {
                    rows.addView(row("${s.emoji}  ${s.label}") { pick(s) })
                } else if (armedId == s.id) {
                    rows.addView(row("🗑  Tap again to delete ${s.label}", cDanger) {
                        Store.save(context, Store.shortcuts(context).filter { it.id != s.id })
                        armedId = null
                        showPicker()
                    })
                } else {
                    rows.addView(row("🗑  ${s.emoji}  ${s.label}") {
                        armedId = s.id
                        showPicker()
                    })
                }
            }
            rows.addView(row("＋  Add shortcut", color = cAmber) { addMenu() })
            rows.addView(row("⚙️  Macros settings") {
                hide()
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            })
        }
    }

    // ---------- Generic list screen ----------
    private fun listScreen(title: String, items: List<Pair<String, () -> Unit>>, back: () -> Unit) {
        screen(title, chip("← Back") { back() }) { rows ->
            items.forEach { (label, action) -> rows.addView(row(label) { action() }) }
        }
    }

    private fun commit(emoji: String, label: String, kind: Kind, pkg: String = "", cls: String = "", data: String = "") {
        val l = Store.shortcuts(context).toMutableList()
        l.add(Shortcut(Store.newId(), emoji, label, kind, pkg, cls, data))
        Store.save(context, l)
        Toast.makeText(context, "Added $label", Toast.LENGTH_SHORT).show()
        editMode = false
        armedId = null
        showPicker()
    }

    // ---------- Add flow ----------
    private fun addMenu() {
        listScreen(
            "＋ Add shortcut",
            listOf(
                "📱  App" to { pickApp { n, p -> commit("📱", n, Kind.APP, p) } },
                "🧩  App activity" to { pickApp { n, p -> pickActivity(n, p) } },
                "⚙️  Action" to { pickAction() },
                "🌐  URL" to { askUrl() }
            )
        ) { showPicker() }
    }

    @Suppress("DEPRECATION")
    private fun pickApp(cb: (String, String) -> Unit) {
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        listScreen(
            "Pick app",
            apps.map { (name, pkg) -> name to { cb(name, pkg) } }
        ) { addMenu() }
    }

    @Suppress("DEPRECATION")
    private fun pickActivity(appName: String, pkg: String) {
        val acts = try {
            context.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities
                ?.filter { it.exported && it.enabled }?.map { it.name } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        if (acts.isEmpty()) {
            Toast.makeText(context, "No launchable activities found", Toast.LENGTH_LONG).show()
            addMenu()
            return
        }
        listScreen(
            "$appName activities",
            acts.map { a ->
                a.removePrefix("$pkg.") to {
                    commit("🧩", "$appName · ${a.substringAfterLast('.')}", Kind.ACTIVITY, pkg, a)
                }
            }
        ) { addMenu() }
    }

    private fun pickAction() {
        val a = listOf(
            Triple("🖐", "Touch block", "BLOCK_TOUCHES"),
            Triple("🔒", "Lock screen", "LOCK"),
            Triple("📸", "Screenshot", "SCREENSHOT"),
            Triple("⏻", "Power menu", "POWER_MENU"),
            Triple("🔔", "Notifications", "NOTIFICATIONS"),
            Triple("⚡", "Quick settings", "QUICK_SETTINGS"),
            Triple("🏠", "Home", "HOME"),
            Triple("◀", "Back", "BACK"),
            Triple("🗂", "Recents", "RECENTS")
        )
        listScreen(
            "Action",
            a.map { "${it.first}  ${it.second}" to { commit(it.first, it.second, Kind.ACTION, data = it.third) } }
        ) { addMenu() }
    }

    private fun askUrl() {
        try {
            // let the keyboard appear inside the assistant window
            window.window?.clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            )
            window.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        } catch (e: Exception) {
        }
        val e = EditText(context).apply {
            setText("https://")
            setTextColor(cText)
            setHintTextColor(0xFF94A3B8.toInt())
            setSingleLine(true)
        }
        screen("🌐 URL", chip("← Back") { addMenu() }) { rows ->
            rows.addView(e)
            rows.addView(row("✔  Add", color = cAmber) {
                val u = e.text.toString().trim()
                if (u.length > 8) commit("🌐", u.removePrefix("https://").removePrefix("http://"), Kind.URL, data = u)
            })
        }
        e.requestFocus()
    }
}
