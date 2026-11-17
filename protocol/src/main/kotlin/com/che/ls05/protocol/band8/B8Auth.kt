package com.che.ls05.protocol.band8

import java.security.SecureRandom

/**
 * Auth durum makinesi (belge 3.1-3.3). Android'e bağlı değil.
 *
 * Çerçeveleme [B8Channel]'dadır: [start] ve [Step.Send] şifresiz gönderilecek Command baytlarını
 * verir, kanal bunları `00 00 02 02` çerçevesiyle yazar. Bandın şifresiz yanıtı
 * [B8Command] olarak [onCommand]'a verilir.
 */
class B8Auth(
    private val authKey: ByteArray,
    private val phoneInfo: B8PhoneInfo,
    private val random: (Int) -> ByteArray = { n -> ByteArray(n).also { SecureRandom().nextBytes(it) } },
) {
    enum class Reason {
        /** Band HMAC'i tutmadı: anahtar yanlış. */
        WRONG_KEY,
        /** Band nonce yerine hata döndü (örnek: eşleşme silinmiş). */
        BAND_REFUSED,
        /** Band son adımı onaylamadı. */
        NOT_CONFIRMED,
        /** Beklenmeyen ya da bozuk mesaj. */
        MALFORMED,
    }

    sealed class Step {
        class Send(val plain: ByteArray) : Step()
        class Success(val session: B8Session) : Step()
        class Failed(val reason: Reason, val status: Int?) : Step()
    }

    private enum class State { IDLE, WAIT_NONCE, WAIT_RESULT, DONE }

    private var state = State.IDLE
    private var phoneNonce = ByteArray(0)
    private var pending: B8Session? = null

    init {
        require(authKey.size == 16) { "auth key 16 bayt olmalı" }
    }

    /** Yeni auth başlatır. Her çağrıda yeni telefon nonce'u üretilir. */
    fun start(): ByteArray {
        phoneNonce = random(16)
        require(phoneNonce.size == 16)
        pending = null
        state = State.WAIT_NONCE
        return B8Messages.authNonce(phoneNonce)
    }

    /** Auth mesajı değilse null döner. */
    fun onCommand(c: B8Command): Step? {
        if (c.type != B8Type.AUTH) return null
        val auth = c.body(B8Type.F_AUTH)
        return when {
            state == State.WAIT_NONCE && c.subtype == B8Sub.AUTH_NONCE -> onNonce(c, auth)
            state == State.WAIT_RESULT && c.subtype == B8Sub.AUTH_PROOF -> onResult(c, auth)
            else -> null
        }
    }

    private fun fail(reason: Reason, status: Int?): Step {
        state = State.DONE
        pending = null
        return Step.Failed(reason, status)
    }

    private fun onNonce(c: B8Command, auth: PbMessage?): Step {
        val wn = auth?.message(31) ?: return fail(Reason.BAND_REFUSED, auth?.int(3) ?: c.status)
        val bandNonce = wn.bytes(1)
        val bandHmac = wn.bytes(2)
        if (bandNonce == null || bandNonce.size != 16 || bandHmac == null || bandHmac.size != 32) {
            return fail(Reason.MALFORMED, null)
        }
        val s = B8Session(authKey, phoneNonce, bandNonce)
        if (!s.bandHmacMatches(bandHmac)) return fail(Reason.WRONG_KEY, null)
        pending = s
        state = State.WAIT_RESULT
        val info = s.encryptToBand(B8Messages.phoneInfo(phoneInfo), 0)
        return Step.Send(B8Messages.authProof(s.phoneHmac(), info))
    }

    private fun onResult(c: B8Command, auth: PbMessage?): Step {
        val r = auth?.message(33)
        val s = pending
        if (r == null || s == null || r.int(1) != 1) return fail(Reason.NOT_CONFIRMED, r?.int(1) ?: c.status)
        state = State.DONE
        pending = null
        return Step.Success(s)
    }
}
