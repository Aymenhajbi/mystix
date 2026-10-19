package ma.mystix.mobile.devicehub

enum class DiagnosticOutcome { PASSED, MISMATCH, TIMEOUT, OPEN_FAILED, ADAPTER_ERROR, CANCELLED }

/** The result of one physical test. The scanned value is not kept, only its length and symbology. */
data class DiagnosticResult(
    val adapterId: String,
    val outcome: DiagnosticOutcome,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val simulated: Boolean,
    val symbology: String? = null,
    val dataLength: Int? = null,
    val errorCode: ScannerErrorCode? = null,
)

/**
 * Runs a scan test with one adapter: the operator reads a label within the timeout. The WMS session is released
 * first and the test is refused during a WMS operation. Only a PASSED, non-simulated result can become a
 * [ValidatedConfiguration].
 */
class ScannerDiagnosticService(
    private val controller: ScannerController,
    private val guard: TransactionGuard,
    private val clock: () -> Long,
) {
    inner class Run internal constructor(
        val adapterId: String,
        private val expected: String?,
        private val timeoutMillis: Long,
    ) : ScanListener {
        val startedAtMillis = clock()
        private var session: ScannerSession? = null
        private var result: DiagnosticResult? = null

        internal fun start(adapter: ScannerAdapter) {
            try {
                val opened = adapter.open(this)
                // A read delivered while opening has already finished the run: release at once.
                synchronized(this) { if (result != null) opened.close() else session = opened }
            } catch (e: ScannerException) {
                finish(DiagnosticOutcome.OPEN_FAILED, simulated = false, errorCode = e.code)
            }
        }

        @Synchronized
        override fun onScan(event: ScanEvent) {
            if (result != null || event.adapterId != adapterId) return
            val outcome = if (expected == null || expected == event.data) DiagnosticOutcome.PASSED else DiagnosticOutcome.MISMATCH
            finish(outcome, event.simulated, event.symbology, event.data.length)
        }

        @Synchronized
        override fun onError(error: ScannerException) {
            if (result == null) finish(DiagnosticOutcome.ADAPTER_ERROR, simulated = false, errorCode = error.code)
        }

        /** The result, or null while waiting; turns into TIMEOUT once the delay is over. */
        @Synchronized
        fun poll(): DiagnosticResult? {
            if (result == null && clock() - startedAtMillis > timeoutMillis) finish(DiagnosticOutcome.TIMEOUT, simulated = false)
            return result
        }

        @Synchronized
        fun cancel(): DiagnosticResult = result ?: finish(DiagnosticOutcome.CANCELLED, simulated = false)

        private fun finish(
            outcome: DiagnosticOutcome,
            simulated: Boolean,
            symbology: String? = null,
            length: Int? = null,
            errorCode: ScannerErrorCode? = null,
        ): DiagnosticResult {
            session?.close()
            session = null
            return DiagnosticResult(adapterId, outcome, startedAtMillis, clock(), simulated, symbology, length, errorCode)
                .also { result = it }
        }
    }

    sealed interface StartResult {
        data class Started(val run: Run) : StartResult
        data object TransactionInProgress : StartResult
    }

    fun start(adapter: ScannerAdapter, expected: String? = null, timeoutMillis: Long = 15_000): StartResult {
        if (guard.transactionInProgress()) return StartResult.TransactionInProgress
        controller.release()
        val run = Run(adapter.id, expected, timeoutMillis)
        run.start(adapter)
        return StartResult.Started(run)
    }

    /** The configuration to save after a test, or null when the test does not validate anything. */
    fun toValidatedConfiguration(
        result: DiagnosticResult,
        device: DeviceDescriptor,
        by: ConfiguredBy,
        profile: CompatibilityProfile?,
    ): ValidatedConfiguration? {
        if (result.outcome != DiagnosticOutcome.PASSED || result.simulated) return null
        return ValidatedConfiguration(device.installationId, device.fingerprint, result.adapterId, by,
            PhysicalScanTest(passed = true, simulated = false, testedAtMillis = result.finishedAtMillis, symbology = result.symbology),
            profile?.id, profile?.version)
    }
}
