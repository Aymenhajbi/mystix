package ma.mystix.mobile.devicehub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntegrationResolverTest {
    private val wedge = TestWedgeAdapter()
    private val intent = TestHoneywellIntentAdapter()
    private val resolver = IntegrationResolver(ScannerAdapterRegistry(listOf(wedge, intent)), ResolverPolicy(minSupportedSdk = 26))

    private fun codes(r: Resolution) = r.reasons.map { it.code }

    @Test
    fun `a known Honeywell model with a validated profile asks for the scanner setting, then a test`() {
        val r = resolver.resolve(snapshot(), null, listOf(profile(HoneywellDataIntentAdapterBase.ID)))
        assertEquals(ResolutionState.CONFIGURATION_REQUIRED, r.state)
        assertEquals(HoneywellDataIntentAdapterBase.ID, r.recommendedAdapterId)
        assertEquals(listOf(KeyboardWedgeAdapterBase.ID), r.alternatives)
        assertTrue(r.physicalTestRequired)
        assertTrue(r.missingPrerequisites.single().contains(HoneywellDataIntentAdapterBase.ACTION))
    }

    @Test
    fun `a validated profile and a compatible adapter lead to a diagnostic, never to a silent activation`() {
        val sdk = FakeAdapter("honeywell-aidc-sdk")
        val r = IntegrationResolver(ScannerAdapterRegistry(listOf(sdk, wedge)))
            .resolve(snapshot(), null, listOf(profile(sdk.id)))
        assertEquals(ResolutionState.DIAGNOSTIC_REQUIRED, r.state)
        assertEquals(sdk.id, r.recommendedAdapterId)
        assertTrue(r.physicalTestRequired)
    }

    @Test
    fun `an unknown terminal with keyboard input only lets the operator choose`() {
        val r = resolver.resolve(snapshot(device("Acme", "X1")), null, emptyList())
        assertEquals(ResolutionState.USER_SELECTION_REQUIRED, r.state)
        assertEquals(KeyboardWedgeAdapterBase.ID, r.recommendedAdapterId)
        assertEquals(Confidence.LOW, r.confidence)
        assertTrue(ReasonCode.NO_PROFILE in codes(r))
        assertTrue(ReasonCode.SINGLE_CANDIDATE_LOW_CONFIDENCE in codes(r))
    }

    @Test
    fun `an Android version below the minimum is unsupported`() {
        val r = resolver.resolve(snapshot(device(sdkInt = 23)), validated(wedge.id, device(sdkInt = 23)), emptyList())
        assertEquals(ResolutionState.DEVICE_UNSUPPORTED, r.state)
        assertEquals(listOf(ReasonCode.SDK_BELOW_MINIMUM), codes(r))
    }

    @Test
    fun `several compatible adapters without a profile need a choice`() {
        val a = FakeAdapter("a")
        val b = FakeAdapter("b", confidence = Confidence.MEDIUM)
        val r = IntegrationResolver(ScannerAdapterRegistry(listOf(b, a))).resolve(snapshot(), null, emptyList())
        assertEquals(ResolutionState.USER_SELECTION_REQUIRED, r.state)
        assertEquals("a", r.recommendedAdapterId)
        assertEquals(listOf("b"), r.alternatives)
        assertTrue(ReasonCode.SEVERAL_CANDIDATES in codes(r))
    }

    @Test
    fun `no adapter at all means the terminal is unsupported`() {
        val r = resolver.resolve(snapshot(device("Acme", "X1"), capabilities = emptyList()), null, emptyList())
        assertEquals(ResolutionState.DEVICE_UNSUPPORTED, r.state)
        assertNull(r.recommendedAdapterId)
        assertTrue(ReasonCode.NO_COMPATIBLE_ADAPTER in codes(r))
    }

    @Test
    fun `a configuration validated on this installation is reused without asking`() {
        val r = resolver.resolve(snapshot(), validated(intent.id), emptyList())
        assertEquals(ResolutionState.AUTO_SELECTED, r.state)
        assertEquals(intent.id, r.recommendedAdapterId)
        assertTrue(ReasonCode.SAVED_CONFIGURATION_VALID in codes(r))
    }

    @Test
    fun `an Android update keeps the validated configuration while it stays compatible`() {
        val updated = device(sdkInt = 35, buildId = "B2")
        assertEquals(ResolutionState.AUTO_SELECTED, resolver.resolve(snapshot(updated), validated(intent.id), emptyList()).state)
    }

    @Test
    fun `a configuration saved on another model is not reused`() {
        val r = resolver.resolve(snapshot(device(model = "CK65")), validated(intent.id), emptyList())
        assertTrue(r.state != ResolutionState.AUTO_SELECTED)
        assertTrue(ReasonCode.SAVED_CONFIGURATION_OTHER_DEVICE in codes(r))
    }

    @Test
    fun `a configuration whose adapter became incompatible or left the build is not reused`() {
        val broken = FakeAdapter("sdk", status = CompatibilityStatus.INCOMPATIBLE)
        val registry = ScannerAdapterRegistry(listOf(broken, wedge))
        val r = IntegrationResolver(registry).resolve(snapshot(), validated("sdk"), emptyList())
        assertEquals(ResolutionState.USER_SELECTION_REQUIRED, r.state)
        assertTrue(ReasonCode.SAVED_CONFIGURATION_INCOMPATIBLE in codes(r))

        val gone = IntegrationResolver(ScannerAdapterRegistry(listOf(wedge))).resolve(snapshot(), validated("sdk"), emptyList())
        assertTrue(ReasonCode.SAVED_CONFIGURATION_ADAPTER_MISSING in codes(gone))
    }

    @Test
    fun `a retired profile is ignored and an experimental one does not decide`() {
        val retired = resolver.resolve(snapshot(), null, listOf(profile(intent.id, ProfileStatus.RETIRED)))
        assertTrue(ReasonCode.PROFILE_RETIRED in codes(retired))
        assertTrue(ReasonCode.NO_PROFILE in codes(retired))

        val a = FakeAdapter("a")
        val b = FakeAdapter("b")
        val experimental = IntegrationResolver(ScannerAdapterRegistry(listOf(a, b)))
            .resolve(snapshot(), null, listOf(profile("b", ProfileStatus.EXPERIMENTAL)))
        assertEquals(ResolutionState.USER_SELECTION_REQUIRED, experimental.state)
        assertEquals("b", experimental.recommendedAdapterId)
        assertTrue(ReasonCode.PROFILE_EXPERIMENTAL in codes(experimental))
    }

    @Test
    fun `the most specific and most recent profile wins`() {
        val a = FakeAdapter("a")
        val b = FakeAdapter("b")
        val profiles = listOf(
            profile("a", scope = ProfileScope.MANUFACTURER, id = "family", version = 9),
            profile("a", id = "model", version = 1),
            profile("b", id = "model", version = 2),
        )
        val r = IntegrationResolver(ScannerAdapterRegistry(listOf(a, b))).resolve(snapshot(), null, profiles)
        assertEquals("model", r.profile?.id)
        assertEquals(2, r.profile?.version)
        assertEquals("b", r.recommendedAdapterId)
    }

    @Test
    fun `a newer profile never replaces a working configuration, it is only reported`() {
        val r = resolver.resolve(snapshot(), validated(wedge.id), listOf(profile(intent.id, version = 2)))
        assertEquals(ResolutionState.AUTO_SELECTED, r.state)
        assertEquals(wedge.id, r.recommendedAdapterId)
        assertEquals(intent.id, r.pendingRecommendation)
    }

    @Test
    fun `a scan service reported down is said so, with the alternatives`() {
        val r = resolver.resolve(snapshot(availability = ScannerAvailability.UNAVAILABLE), null, emptyList())
        assertEquals(ResolutionState.SCANNER_UNAVAILABLE, r.state)
        assertEquals(listOf(intent.id, wedge.id), r.alternatives)
    }

    @Test
    fun `without the server, the local configuration still starts the scanner`() {
        val repository = InMemoryDeviceProfileRepository()
        repository.saveConfiguration(validated(intent.id))
        // Profiles never arrived (backend down): the cache is empty.
        val r = resolver.resolve(snapshot(), repository.loadConfiguration("install-1"), repository.profiles())
        assertEquals(ResolutionState.AUTO_SELECTED, r.state)
    }

    @Test
    fun `a configuration cannot be saved from a simulated or failed test`() {
        assertFailsWith<IllegalArgumentException> {
            ValidatedConfiguration("i", "f", "a", ConfiguredBy.OPERATOR, PhysicalScanTest(true, true, 0, null))
        }
        assertFailsWith<IllegalArgumentException> {
            ValidatedConfiguration("i", "f", "a", ConfiguredBy.OPERATOR, PhysicalScanTest(false, false, 0, null))
        }
    }

    @Test
    fun `duplicate adapter ids are refused`() {
        assertFailsWith<IllegalArgumentException> { ScannerAdapterRegistry(listOf(FakeAdapter("x"), FakeAdapter("x"))) }
    }

    @Test
    fun `device changes are listed`() {
        val before = snapshot()
        assertEquals(listOf(DeviceChange.FIRST_DETECTION), before.changesSince(null))
        assertEquals(emptyList(), before.changesSince(before))
        val after = snapshot(device(model = "CK65", sdkInt = 35), availability = ScannerAvailability.AVAILABLE)
        assertEquals(listOf(DeviceChange.MODEL, DeviceChange.SYSTEM, DeviceChange.SCANNER_AVAILABILITY), after.changesSince(before))
    }
}
