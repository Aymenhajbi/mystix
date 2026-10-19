package ma.mystix.mobile.devicehub

// Test doubles. Every event they emit is marked simulated: they never stand for a hardware test.

fun device(
    manufacturer: String = "Honeywell",
    model: String = "CK67",
    sdkInt: Int = 34,
    installationId: String = "install-1",
    buildId: String = "B1",
) = DeviceDescriptor(installationId, manufacturer, manufacturer.lowercase(), model, model.lowercase(), "14", sdkInt,
    buildId, "0.1.0")

fun snapshot(
    device: DeviceDescriptor = device(),
    capabilities: List<String> = listOf(CapabilityIds.KEY_EVENTS),
    availability: ScannerAvailability = ScannerAvailability.UNKNOWN,
) = DeviceSnapshot(device, capabilities.map { DeviceCapability(it, CapabilitySource.DETECTED, "test") }, availability, 0)

class FakeSession(override val adapterId: String, val listener: ScanListener) : ScannerSession {
    var closed = false
        private set

    override fun close() {
        closed = true
    }
}

class FakeAdapter(
    override val id: String,
    private val status: CompatibilityStatus = CompatibilityStatus.COMPATIBLE,
    private val confidence: Confidence = Confidence.HIGH,
    var failOpen: ScannerErrorCode? = null,
) : ScannerAdapter {
    override val support = AdapterSupport(id, emptySet(), null, emptyList(), confidence)
    val sessions = mutableListOf<FakeSession>()

    override fun checkCompatibility(snapshot: DeviceSnapshot) =
        CompatibilityCheck(status, confidence, emptyList(), physicalTestRequired = status != CompatibilityStatus.INCOMPATIBLE)

    override fun open(listener: ScanListener): ScannerSession {
        failOpen?.let { throw ScannerException(it, "fake failure") }
        return FakeSession(id, listener).also { sessions += it }
    }

    fun emit(data: String, at: Long = 0, deliveryId: String? = null, session: FakeSession = sessions.last()) =
        session.listener.onScan(ScanEvent(data, "CODE128", id, at, deliveryId, simulated = true))
}

class TestWedgeAdapter : KeyboardWedgeAdapterBase() {
    override fun open(listener: ScanListener): ScannerSession = FakeSession(id, listener)
}

class TestHoneywellIntentAdapter : HoneywellDataIntentAdapterBase() {
    override fun open(listener: ScanListener): ScannerSession = FakeSession(id, listener)
}

class RecordingConsumer : ScanConsumer {
    val scans = mutableListOf<ScanEvent>()
    val errors = mutableListOf<ScannerException>()

    override fun onScan(event: ScanEvent) {
        scans += event
    }

    override fun onScannerError(error: ScannerException) {
        errors += error
    }
}

class MutableGuard(var busy: Boolean = false) : TransactionGuard {
    override fun transactionInProgress() = busy
}

fun validated(adapterId: String, device: DeviceDescriptor = device()) = ValidatedConfiguration(device.installationId,
    device.fingerprint, adapterId, ConfiguredBy.OPERATOR, PhysicalScanTest(true, false, 1, "CODE128"))

fun profile(
    adapter: String,
    status: ProfileStatus = ProfileStatus.VALIDATED,
    scope: ProfileScope = ProfileScope.MODEL,
    version: Int = 1,
    id: String = "p-$adapter",
) = CompatibilityProfile(id, version, scope, "Honeywell", models = setOf("CK67"), recommendedAdapterId = adapter,
    confidence = Confidence.HIGH, status = status)
