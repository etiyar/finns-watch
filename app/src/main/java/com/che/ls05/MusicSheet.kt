package com.che.ls05

import android.app.AlertDialog
import android.view.KeyEvent

/** Ana > Müzik: telefonda çalan müziği yönetir (önceki, oynat/duraklat, sonraki, ses). Saate komut gitmez. */
object MusicSheet {
    fun show(act: MainActivity) {
        val ui = act.ui
        val box = ui.dialogBox()
        ui.add(box, ui.caption("Telefonda çalan müziği yönetir. Saatteki müzik tuşları da bu telefonu yönetir."), topDp = 8)
        val keys = ui.horizontal()
        ui.add(keys, ui.secondary("Önceki") { PhoneRemote.mediaKey(act, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, width = 0, weight = 1f)
        ui.add(keys, ui.filled("Oynat/Duraklat") { PhoneRemote.mediaKey(act, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }, width = 0, weight = 1.4f, leftDp = 8)
        ui.add(keys, ui.secondary("Sonraki") { PhoneRemote.mediaKey(act, KeyEvent.KEYCODE_MEDIA_NEXT) }, width = 0, weight = 1f, leftDp = 8)
        ui.add(box, keys, topDp = 16)
        val vol = ui.horizontal()
        ui.add(vol, ui.secondary("Ses azalt") { PhoneRemote.adjustVolume(act, false) }, width = 0, weight = 1f)
        ui.add(vol, ui.secondary("Ses artır") { PhoneRemote.adjustVolume(act, true) }, width = 0, weight = 1f, leftDp = 8)
        ui.add(box, vol, topDp = 8)
        val d = AlertDialog.Builder(act).setTitle("Müzik").setView(box).setPositiveButton("Kapat", null).create()
        act.track(d)
        d.show()
    }
}
