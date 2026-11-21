package com.che.ls05

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * Saat > Kadranlar ızgarası: 3 sütun, her döşemede önizleme, ad ve durum ("Etkin", "Saatte", "Telefonda").
 * Saattekinin çerçevesi 2 dp yeşil. LS05 (yuvarlak) ve Band 8 (dikdörtgen) galerisi ortak kullanır.
 */
object FaceGrid {

    class Tile(
        val name: String,
        val state: String,
        val active: Boolean,
        val preview: View,
        val previewW: Int,
        val previewH: Int,
        val onClick: () -> Unit,
    )

    fun build(ui: Ui, grid: LinearLayout, tiles: List<Tile>) {
        var row: LinearLayout? = null
        tiles.forEachIndexed { i, t ->
            if (i % 3 == 0) {
                row = LinearLayout(ui.c).apply { orientation = LinearLayout.HORIZONTAL }
                ui.add(grid, row!!, topDp = if (i == 0) 0 else 12)
            }
            ui.add(row!!, tile(ui, t), width = 0, height = Ui.MATCH, weight = 1f, leftDp = if (i % 3 == 0) 0 else 12)
        }
        val rest = tiles.size % 3
        if (rest != 0) repeat(3 - rest) { ui.add(row!!, ui.space(), width = 0, height = ui.dp(1), weight = 1f, leftDp = 12) }
    }

    private fun tile(ui: Ui, t: Tile): View {
        val col = ui.vertical()
        col.gravity = Gravity.CENTER_HORIZONTAL
        val shape = ui.round(ui.color(R.color.card), 18)
        shape.setStroke(ui.dp(if (t.active) 2 else 1), ui.color(if (t.active) R.color.accent else R.color.border))
        col.background = ui.ripple(shape, null)
        col.setPadding(ui.dp(6), ui.dp(12), ui.dp(6), ui.dp(10))
        col.isClickable = true
        col.isFocusable = true
        col.setOnClickListener { t.onClick() }
        col.addView(t.preview, LinearLayout.LayoutParams(t.previewW, t.previewH))
        val name = ui.text(t.name, 12f, R.color.text, bold = true)
        name.gravity = Gravity.CENTER
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        ui.add(col, name, topDp = 6)
        val state = ui.text(t.state, 11f, if (t.active) R.color.accent_text else R.color.text2, bold = true)
        state.gravity = Gravity.CENTER
        ui.add(col, state, topDp = 2)
        col.contentDescription = "${t.name}, ${t.state}"
        return col
    }
}
