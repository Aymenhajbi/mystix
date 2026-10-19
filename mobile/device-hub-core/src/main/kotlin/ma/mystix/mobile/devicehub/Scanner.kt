package ma.mystix.mobile.devicehub

enum class Confidence { LOW, MEDIUM, HIGH }

/**
 * What an adapter declares about itself: where it is meant to run, its known constraints and how reliable its
 * compatibility check is. [testedOnHardware] stays empty until a real terminal test is recorded in ADR-0019.
 */
data class AdapterSupport(
    val displayName: String,
    val manufacturers: Set<String>,
    val minSdk: Int?,
    val knownConstraints: List<String>,
    val detectionConfidence: Confidence,
    val testedOnHardware: List<String> = emptyList(),
) {
    fun acceptsManufacturer(manufacturer: String): Boolean =
        manufacturers.isEmpty() || manufacturers.any { it.equals(manufacturer.trim(), ignoreCase = true) }
}

enum class CompatibilityStatus { COMPATIBLE, NEEDS_CONFIGURATION, INCOMPATIBLE }

data class CompatibilityCheck(
    val status: CompatibilityStatus,
    val confidence: Confidence,
    val reasons: List<Reason>,
    val missingPrerequisites: List<String> = emptyList(),
    val physicalTestRequired: Boolean = true,
)

/** A scanned code. [deliveryId] is set when the source gives one per physical read (it is used to drop duplicates). */
data class ScanEvent(
    val data: String,
    val symbology: String?,
    val adapterId: String,
    val receivedAtMillis: Long,
    val deliveryId: String? = null,
    /** True when the event comes from a test double: it never validates a configuration. */
    val simulated: Boolean = false,
)

/** An adapter failure, with a stable code the UI translates. */
class ScannerException(val code: ScannerErrorCode, message: String, cause: Throwable? = null) : Exception(message, cause)

enum class ScannerErrorCode { NOT_AVAILABLE, PERMISSION_DENIED, INIT_FAILED, CLAIMED_BY_OTHER_APP, CONFIGURATION_MISSING }

interface ScanListener {
    fun onScan(event: ScanEvent)

    fun onError(error: ScannerException)
}

/** Resources held while scanning. [close] releases them and is safe to call twice. */
interface ScannerSession : AutoCloseable {
    val adapterId: String

    override fun close()
}

/**
 * The contract every scan integration implements. The compatibility check is pure; [open] claims the hardware and
 * throws [ScannerException] when it cannot. WMS screens never see an adapter, only [ScannerController].
 */
interface ScannerAdapter {
    val id: String
    val support: AdapterSupport

    fun checkCompatibility(snapshot: DeviceSnapshot): CompatibilityCheck

    fun open(listener: ScanListener): ScannerSession
}

/** The adapters compiled into this build. Adapters are never downloaded: a new one ships in a signed release. */
class ScannerAdapterRegistry(adapters: List<ScannerAdapter>) {
    private val byId: Map<String, ScannerAdapter>

    init {
        val duplicates = adapters.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate adapter ids: $duplicates" }
        byId = adapters.associateBy { it.id }
    }

    fun find(id: String): ScannerAdapter? = byId[id]

    fun all(): List<ScannerAdapter> = byId.values.sortedBy { it.id }
}
