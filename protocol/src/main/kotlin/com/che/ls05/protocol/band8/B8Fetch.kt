package com.che.ls05.protocol.band8

/**
 * Etkinlik dosyası çekme sırası (belge 7.2). Android'e bağlı değil, zamanı çağıran verir.
 *
 * 1. 8/1 (bugün), yanıt gelince 8/2 (geçmiş).
 * 2. Her kimlik için **ayrı** 8/3. Bölümler arasında [partTimeoutMs] beklenir, aşılırsa dosya atlanır.
 * 3. Band istenmeden dosya gönderebilir (elle ölçüm). Bunlar da [Out.File] olarak verilir.
 * 4. 8/5 (bandan silme) yalnızca [allowDelete] true ise ve çağıran [confirmStored] ile
 *    dosyanın kaydedildiğini bildirirse gönderilir. Varsayılan: hiç gönderilmez.
 */
class B8Fetch(
    private val allowDelete: Boolean = false,
    private val partTimeoutMs: Long = 5_000,
    private val listTimeoutMs: Long = 10_000,
) {
    sealed class Out {
        /** Şifreli gönderilecek Command. */
        class Send(val plain: ByteArray) : Out()
        class File(val file: B8ActivityFile, val requested: Boolean) : Out()
        /** Dosya alınamadı. [reason] kişisel veri içermez. */
        class Failed(val id: B8FileId?, val reason: String) : Out()
        /** Sıra bitti. */
        object Done : Out()
    }

    private enum class Phase { IDLE, WAIT_TODAY, WAIT_PAST, FETCHING, DONE }

    private var phase = Phase.IDLE
    private var deadline = 0L
    private val todo = ArrayDeque<B8FileId>()
    private val seen = HashSet<B8FileId>()
    private var current: B8FileId? = null
    private val assembler = B8PartAssembler()

    val isRunning: Boolean get() = phase != Phase.IDLE && phase != Phase.DONE
    val currentFile: B8FileId? get() = current

    fun start(nowMs: Long): List<Out> {
        todo.clear(); seen.clear(); current = null; assembler.reset()
        phase = Phase.WAIT_TODAY
        deadline = nowMs + listTimeoutMs
        return listOf(Out.Send(B8Messages.filesToday()))
    }

    /** 8/1 ve 8/2 yanıtları. Diğer komutlar için boş liste. */
    fun onCommand(c: B8Command, nowMs: Long): List<Out> {
        if (c.type != B8Type.HEALTH) return emptyList()
        return when {
            c.subtype == B8Sub.HEALTH_FILES_TODAY && phase == Phase.WAIT_TODAY -> {
                enqueue(B8Messages.parseFileList(c))
                askPast(nowMs)
            }
            c.subtype == B8Sub.HEALTH_FILES_PAST && phase == Phase.WAIT_PAST -> {
                enqueue(B8Messages.parseFileList(c))
                next(nowMs)
            }
            else -> emptyList()
        }
    }

    private fun enqueue(ids: List<B8FileId>) {
        for (id in ids) if (seen.add(id)) todo.addLast(id)
    }

    private fun askPast(nowMs: Long): List<Out> {
        phase = Phase.WAIT_PAST
        deadline = nowMs + listTimeoutMs
        return listOf(Out.Send(B8Messages.filesPast()))
    }

    private fun next(nowMs: Long): List<Out> {
        assembler.reset()
        val id = todo.removeFirstOrNull()
        if (id == null) {
            current = null
            phase = Phase.DONE
            return listOf(Out.Done)
        }
        current = id
        phase = Phase.FETCHING
        deadline = nowMs + partTimeoutMs
        return listOf(Out.Send(B8Messages.fileRequest(id)))
    }

    fun onActivityPart(part: ByteArray, nowMs: Long): List<Out> {
        if (phase == Phase.FETCHING) deadline = nowMs + partTimeoutMs
        return when (val r = assembler.add(part)) {
            is B8PartAssembler.Result.NeedMore -> emptyList()
            is B8PartAssembler.Result.Failed -> {
                val out = mutableListOf<Out>(Out.Failed(current, r.reason))
                if (phase == Phase.FETCHING) out += next(nowMs)
                out
            }
            is B8PartAssembler.Result.Complete -> {
                val f = r.file
                val wasCurrent = phase == Phase.FETCHING && f.id == current
                val out = mutableListOf<Out>(Out.File(f, wasCurrent))
                if (wasCurrent) out += next(nowMs)
                out
            }
        }
    }

    /** Komut gerçekten gönderildi: bekleme süresi şimdiden başlar (kapı komutu bekletmiş olabilir). */
    fun restartTimer(nowMs: Long) {
        deadline = nowMs + when (phase) {
            Phase.FETCHING -> partTimeoutMs
            Phase.WAIT_TODAY, Phase.WAIT_PAST -> listTimeoutMs
            else -> return
        }
    }

    /** Zaman aşımı denetimi. Çağıran düzenli aralıklarla (örnek saniyede bir) çağırır. */
    fun onTick(nowMs: Long): List<Out> {
        if (!isRunning || nowMs < deadline) return emptyList()
        return when (phase) {
            Phase.WAIT_TODAY -> askPast(nowMs)
            Phase.WAIT_PAST -> next(nowMs)
            Phase.FETCHING -> listOf<Out>(Out.Failed(current, "zaman aşımı")) + next(nowMs)
            else -> emptyList()
        }
    }

    /**
     * Çağıran dosyayı veritabanına yazdıktan sonra çağırır. Yalnızca [allowDelete] açıksa
     * 8/5 döner. Aksi hâlde boş liste: dosya bandda kalır.
     */
    fun confirmStored(id: B8FileId): List<Out> =
        if (allowDelete) listOf(Out.Send(B8Messages.fileDelete(id))) else emptyList()
}
