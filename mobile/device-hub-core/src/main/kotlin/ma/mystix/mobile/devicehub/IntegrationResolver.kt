package ma.mystix.mobile.devicehub

enum class ResolutionState {
    /** A configuration validated by a physical scan on this installation is still compatible: it is reused. */
    AUTO_SELECTED,

    /** Several integrations are possible and nothing decides between them: the operator chooses, then tests. */
    USER_SELECTION_REQUIRED,

    /** The only possible integrations need a setting on the terminal (for example a scanner profile). */
    CONFIGURATION_REQUIRED,

    /** No integration of this build can run on this terminal. */
    DEVICE_UNSUPPORTED,

    /** The terminal reports that its scan service does not answer. */
    SCANNER_UNAVAILABLE,

    /** One integration is clearly recommended, but it has not been proven by a scan on this installation. */
    DIAGNOSTIC_REQUIRED,
}

data class Resolution(
    val state: ResolutionState,
    val recommendedAdapterId: String?,
    val confidence: Confidence,
    val reasons: List<Reason>,
    val missingPrerequisites: List<String>,
    val alternatives: List<String>,
    val physicalTestRequired: Boolean,
    val profile: CompatibilityProfile? = null,
    /** A newer recommendation that differs from the validated configuration; it is shown, never applied silently. */
    val pendingRecommendation: String? = null,
)

data class ResolverPolicy(val minSupportedSdk: Int? = null)

/**
 * Compares the terminal with the adapters of this build, the cached profiles and the validated configuration.
 * Deterministic and side-effect free: the same inputs always give the same [Resolution].
 */
class IntegrationResolver(
    private val registry: ScannerAdapterRegistry,
    private val policy: ResolverPolicy = ResolverPolicy(),
) {
    fun resolve(
        snapshot: DeviceSnapshot,
        saved: ValidatedConfiguration?,
        profiles: List<CompatibilityProfile>,
    ): Resolution {
        val device = snapshot.device
        val reasons = mutableListOf<Reason>()
        val minSdk = policy.minSupportedSdk
        if (minSdk != null && device.sdkInt < minSdk) {
            return Resolution(ResolutionState.DEVICE_UNSUPPORTED, null, Confidence.HIGH,
                listOf(Reason(ReasonCode.SDK_BELOW_MINIMUM, "API ${device.sdkInt} < $minSdk")), emptyList(), emptyList(),
                physicalTestRequired = false)
        }

        val checks = registry.all().associateWith { it.checkCompatibility(snapshot) }
        val profile = selectProfile(device, profiles, reasons)

        if (saved != null) {
            val reuse = reuseSaved(saved, device, checks, reasons)
            if (reuse != null) {
                val pending = profile?.recommendedAdapterId?.takeIf { it != reuse.id && profile.status == ProfileStatus.VALIDATED }
                if (pending != null) reasons += Reason(ReasonCode.PROFILE_RECOMMENDS_OTHER_ADAPTER, pending)
                return Resolution(ResolutionState.AUTO_SELECTED, reuse.id, Confidence.HIGH, reasons, emptyList(),
                    alternatives(checks, reuse.id), physicalTestRequired = false, profile = profile,
                    pendingRecommendation = pending)
            }
        }

        if (snapshot.scannerAvailability == ScannerAvailability.UNAVAILABLE) {
            reasons += Reason(ReasonCode.SCANNER_SERVICE_UNAVAILABLE)
            return Resolution(ResolutionState.SCANNER_UNAVAILABLE, null, Confidence.HIGH, reasons, emptyList(),
                alternatives(checks, null), physicalTestRequired = true, profile = profile)
        }

        val candidates = checks.filterValues { it.status != CompatibilityStatus.INCOMPATIBLE }
            .entries.sortedWith(
                compareByDescending<Map.Entry<ScannerAdapter, CompatibilityCheck>> { it.key.id == profile?.recommendedAdapterId }
                    .thenByDescending { it.value.status == CompatibilityStatus.COMPATIBLE }
                    .thenByDescending { it.value.confidence }
                    .thenBy { it.key.id })
        if (candidates.isEmpty()) {
            reasons += Reason(ReasonCode.NO_COMPATIBLE_ADAPTER)
            checks.values.forEach { reasons += it.reasons }
            return Resolution(ResolutionState.DEVICE_UNSUPPORTED, null, Confidence.HIGH, reasons, emptyList(),
                emptyList(), physicalTestRequired = false, profile = profile)
        }

        val best = candidates.first()
        val others = candidates.drop(1).map { it.key.id }
        reasons += best.value.reasons
        if (profile != null && profile.recommendedAdapterId != best.key.id) {
            reasons += Reason(ReasonCode.PROFILE_ADAPTER_UNAVAILABLE, profile.recommendedAdapterId)
        }
        val profileDecides = profile != null && profile.status == ProfileStatus.VALIDATED &&
            profile.recommendedAdapterId == best.key.id
        val state = when {
            profileDecides && best.value.status == CompatibilityStatus.NEEDS_CONFIGURATION ->
                ResolutionState.CONFIGURATION_REQUIRED
            profileDecides -> ResolutionState.DIAGNOSTIC_REQUIRED
            candidates.all { it.value.status == CompatibilityStatus.NEEDS_CONFIGURATION } ->
                ResolutionState.CONFIGURATION_REQUIRED
            else -> {
                reasons += if (others.isNotEmpty()) Reason(ReasonCode.SEVERAL_CANDIDATES, others.joinToString())
                else Reason(ReasonCode.SINGLE_CANDIDATE_LOW_CONFIDENCE, best.key.id)
                ResolutionState.USER_SELECTION_REQUIRED
            }
        }
        val prerequisites = (best.value.missingPrerequisites + (profile?.prerequisites.takeIf { profileDecides } ?: emptyList()))
            .distinct()
        val confidence = if (profileDecides) minOf(profile!!.confidence, maxOf(best.value.confidence, Confidence.MEDIUM))
        else best.value.confidence
        return Resolution(state, best.key.id, confidence, reasons, prerequisites, others, physicalTestRequired = true,
            profile = profile)
    }

    private fun selectProfile(device: DeviceDescriptor, profiles: List<CompatibilityProfile>, reasons: MutableList<Reason>): CompatibilityProfile? {
        val matching = profiles.filter { it.matches(device) }
        matching.filter { it.status == ProfileStatus.RETIRED }.forEach { reasons += Reason(ReasonCode.PROFILE_RETIRED, "${it.id} v${it.version}") }
        val best = matching.filter { it.status != ProfileStatus.RETIRED }
            .sortedWith(compareByDescending<CompatibilityProfile> { it.specificity }
                .thenByDescending { it.status == ProfileStatus.VALIDATED }
                .thenByDescending { it.version }
                .thenBy { it.id })
            .firstOrNull()
        reasons += when {
            best == null -> Reason(ReasonCode.NO_PROFILE)
            best.status == ProfileStatus.VALIDATED -> Reason(ReasonCode.PROFILE_VALIDATED, "${best.id} v${best.version}")
            else -> Reason(ReasonCode.PROFILE_EXPERIMENTAL, "${best.id} v${best.version}")
        }
        return best
    }

    private fun reuseSaved(
        saved: ValidatedConfiguration,
        device: DeviceDescriptor,
        checks: Map<ScannerAdapter, CompatibilityCheck>,
        reasons: MutableList<Reason>,
    ): ScannerAdapter? {
        if (saved.installationId != device.installationId) {
            reasons += Reason(ReasonCode.SAVED_CONFIGURATION_OTHER_INSTALLATION)
            return null
        }
        if (saved.deviceFingerprint != device.fingerprint) {
            reasons += Reason(ReasonCode.SAVED_CONFIGURATION_OTHER_DEVICE, saved.deviceFingerprint)
            return null
        }
        val adapter = registry.find(saved.adapterId)
        if (adapter == null) {
            reasons += Reason(ReasonCode.SAVED_CONFIGURATION_ADAPTER_MISSING, saved.adapterId)
            return null
        }
        val check = checks.getValue(adapter)
        if (check.status == CompatibilityStatus.INCOMPATIBLE) {
            reasons += Reason(ReasonCode.SAVED_CONFIGURATION_INCOMPATIBLE, saved.adapterId)
            reasons += check.reasons
            return null
        }
        reasons += Reason(ReasonCode.SAVED_CONFIGURATION_VALID, saved.adapterId)
        return adapter
    }

    private fun alternatives(checks: Map<ScannerAdapter, CompatibilityCheck>, except: String?): List<String> =
        checks.filter { it.value.status != CompatibilityStatus.INCOMPATIBLE && it.key.id != except }.keys.map { it.id }.sorted()
}
