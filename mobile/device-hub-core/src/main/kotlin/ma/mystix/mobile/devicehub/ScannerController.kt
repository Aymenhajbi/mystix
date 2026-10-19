package ma.mystix.mobile.devicehub

/** Tells whether a WMS operation is being entered; the adapter is never changed in the middle of one. */
fun interface TransactionGuard {
    fun transactionInProgress(): Boolean
}

/** A screen that consumes scans. One at a time: a scan never lands in a screen that is not in front. */
interface ScanConsumer {
    fun onScan(event: ScanEvent)

    fun onScannerError(error: ScannerException)
}

sealed interface SwitchResult {
    data class Switched(val adapterId: String) : SwitchResult
    data class AlreadyActive(val adapterId: String) : SwitchResult
    data object DeferredTransactionInProgress : SwitchResult
    data class UnknownAdapter(val adapterId: String) : SwitchResult

    /** The new adapter failed to open; [restored] tells whether the previous one was reopened. */
    data class Failed(val adapterId: String, val error: ScannerException, val restored: Boolean) : SwitchResult
}

/**
 * Drops the second delivery of one physical read: the same [ScanEvent.deliveryId] twice, or the same data from two
 * different adapters within [echoWindowMillis] (a scanner left in keyboard mode while the intent is also active).
 * Two reads of the same label on the same adapter are both kept: counting cartons relies on it.
 */
class ScanDeduplicator(private val echoWindowMillis: Long = 300, private val memory: Int = 32) {
    private val recent = ArrayDeque<ScanEvent>()

    @Synchronized
    fun accept(event: ScanEvent): Boolean {
        val duplicate = recent.any { previous ->
            (event.deliveryId != null && previous.adapterId == event.adapterId && previous.deliveryId == event.deliveryId) ||
                (previous.adapterId != event.adapterId && previous.data == event.data &&
                    kotlin.math.abs(event.receivedAtMillis - previous.receivedAtMillis) <= echoWindowMillis)
        }
        recent.addLast(event)
        while (recent.size > memory) recent.removeFirst()
        return !duplicate
    }

    @Synchronized
    fun reset() = recent.clear()
}

/**
 * The single owner of the scan hardware. It keeps at most one open session, so a listener is never registered twice,
 * ignores events from a session it already closed, and changes adapter only outside a WMS operation.
 */
class ScannerController(
    private val registry: ScannerAdapterRegistry,
    private val guard: TransactionGuard,
    private val deduplicator: ScanDeduplicator = ScanDeduplicator(),
) {
    private var session: ScannerSession? = null
    private var generation = 0L
    private var consumer: ScanConsumer? = null

    /** Scans received while no screen consumes them; shown in the diagnostic screen. */
    var droppedWithoutConsumer = 0L
        private set

    val activeAdapterId: String?
        @Synchronized get() = session?.adapterId

    @Synchronized
    fun attach(consumer: ScanConsumer) {
        this.consumer = consumer
    }

    /** Detaches [consumer] only if it is still the attached one (a late detach must not remove the next screen). */
    @Synchronized
    fun detach(consumer: ScanConsumer) {
        if (this.consumer === consumer) this.consumer = null
    }

    @Synchronized
    fun switchTo(adapterId: String): SwitchResult {
        val current = session
        if (current != null && current.adapterId == adapterId) return SwitchResult.AlreadyActive(adapterId)
        val next = registry.find(adapterId) ?: return SwitchResult.UnknownAdapter(adapterId)
        if (current != null && guard.transactionInProgress()) return SwitchResult.DeferredTransactionInProgress
        val previousId = current?.adapterId
        closeCurrent()
        return try {
            open(next)
            SwitchResult.Switched(adapterId)
        } catch (e: ScannerException) {
            val restored = previousId != null && runCatching { open(registry.find(previousId)!!) }.isSuccess
            SwitchResult.Failed(adapterId, e, restored)
        }
    }

    /** Releases the hardware (application stopped, diagnostic about to run). */
    @Synchronized
    fun release() = closeCurrent()

    private fun open(adapter: ScannerAdapter) {
        val myGeneration = ++generation
        session = adapter.open(object : ScanListener {
            override fun onScan(event: ScanEvent) = deliver(myGeneration, event)
            override fun onError(error: ScannerException) = deliverError(myGeneration, error)
        })
        deduplicator.reset()
    }

    private fun closeCurrent() {
        generation++
        val s = session
        session = null
        s?.close()
    }

    private fun deliver(fromGeneration: Long, event: ScanEvent) {
        val target = synchronized(this) {
            if (fromGeneration != generation || !deduplicator.accept(event)) return
            consumer ?: run { droppedWithoutConsumer++; return }
        }
        target.onScan(event)
    }

    private fun deliverError(fromGeneration: Long, error: ScannerException) {
        val target = synchronized(this) { if (fromGeneration != generation) return else consumer } ?: return
        target.onScannerError(error)
    }
}
