package com.che.ls05

import android.app.Dialog
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView

/** Kadran döşemesi: yuvarlak önizleme (ya da içerik) ve altında ad. Saat ekranı ve galeri ortak kullanır. */
object FaceTiles {
    fun tile(ui: Ui, label: String, content: View, sizeDp: Int, onClick: () -> Unit): LinearLayout {
        val col = ui.vertical()
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.background = ui.pressable(16)
        col.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        col.isClickable = true
        col.isFocusable = true
        col.setOnClickListener { onClick() }
        col.addView(content, LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp)))
        val t = ui.text(label, 12f, R.color.text2)
        t.gravity = Gravity.CENTER
        t.maxLines = 2
        t.ellipsize = TextUtils.TruncateAt.END
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = ui.dp(6)
        col.addView(t, lp)
        col.contentDescription = label.replace('\n', ' ')
        return col
    }

    /** "Tümü" döşemesinin yuvarlağı: kadran sayısı. */
    fun countCircle(ui: Ui, count: Int): TextView {
        val t = ui.text(count.toString(), 22f, R.color.text, bold = true)
        t.gravity = Gravity.CENTER
        t.background = ui.round(ui.color(R.color.raised), 60)
        return t
    }
}

/**
 * Tüm kadranlar (v0.11): tam ekran pencere, 3 sütunlu ızgara, üstte Ekle. Dokununca kadran penceresi açılır.
 * Ekleme, silme ve yükleme sonrası Saat ekranı refresh() çağırır.
 */
class FaceGallery(
    private val act: MainActivity,
    private val onOpen: (FaceLibrary.Entry) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val ui = act.ui
    private val dialog = Dialog(act, R.style.AppTheme)
    private val grid = ui.vertical()
    private val status: TextView = ui.caption("")
    private val title: TextView = ui.text("Kadranlarım", 22f, R.color.text, bold = true)

    /** Galerideki önizlemeler (kimlik, görünüm); yükleme halkası için. */
    val thumbs = ArrayList<Pair<String, FaceThumbView>>()

    val isShowing: Boolean get() = dialog.isShowing

    init {
        val root = ui.vertical()
        root.setBackgroundColor(ui.color(R.color.bg))

        val top = ui.horizontal()
        top.minimumHeight = ui.dp(56)
        top.setPadding(ui.dp(20), ui.dp(8), ui.dp(8), ui.dp(4))
        title.includeFontPadding = false
        ui.add(top, title, width = 0, weight = 1f)
        ui.add(top, ui.tonal("Ekle") { act.pickFace() }, width = Ui.WRAP)
        ui.add(top, ui.textButton("Kapat") { dialog.dismiss() }, width = Ui.WRAP, leftDp = 4)
        ui.add(root, top)

        val body = ui.vertical()
        body.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(16))
        status.visibility = View.GONE
        status.setPadding(ui.dp(4), 0, ui.dp(4), 0)
        ui.add(body, status, topDp = 4)
        val card = ui.card(padDp = 8, padVDp = 12)
        ui.add(card, grid)
        ui.add(body, card, topDp = 8)
        ui.add(root, ui.scroll(body), height = 0, weight = 1f)

        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener { onClosed() }
    }

    fun show() {
        act.track(dialog)
        dialog.show()
    }

    /** Izgarayı yeniden kurar. current: saatteki (en son yüklenen) kadranın kimliği. */
    fun refresh(current: String) {
        grid.removeAllViews()
        thumbs.clear()
        val all = FaceLibrary.list(act).reversed() // en yeni üstte
        title.text = "Kadranlarım (${all.size})"
        if (all.isEmpty()) {
            val empty = ui.caption("Henüz kadran yok. Ekle ile bir kadran dosyası seç.")
            empty.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            ui.add(grid, empty)
        }
        var row: LinearLayout? = null
        all.forEachIndexed { i, e ->
            if (i % 3 == 0) {
                row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                ui.add(grid, row!!, topDp = if (i == 0) 0 else 8)
            }
            val th = FaceThumbView(act)
            th.set(FaceLibrary.preview(e), e.id == current)
            thumbs += e.id to th
            val label = if (e.id == current) "${e.name}\nSaatte" else e.name
            ui.add(row!!, FaceTiles.tile(ui, label, th, 88) { onOpen(e) }, width = 0, weight = 1f)
        }
        // Son satır hizalı kalsın.
        val rest = all.size % 3
        if (rest != 0) repeat(3 - rest) { ui.add(row!!, ui.space(), width = 0, height = ui.dp(1), weight = 1f) }
    }

    /** Yükleme durum satırı (Saat ekranındakiyle aynı metin). */
    fun setStatus(text: String, colorId: Int) {
        status.text = text
        status.setTextColor(ui.color(colorId))
        status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }
}
