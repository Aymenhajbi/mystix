package ma.mystix.device;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ma.mystix.auth.Access;
import ma.mystix.wms.AtelysAudit;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Atelys Device Hub API (ADR-0019, lot D). Terminals check in and send their scan tests; reading is open to the
 * members of the environment; every change made from the portal needs an administrator and is audited.
 */
@RestController
@RequestMapping(value = "/api/v1/atelys", produces = MediaType.APPLICATION_JSON_VALUE)
class DeviceController {

    private static final String COMPANY = "X-Mystix-Company-Id";

    private final MobileDevices devices;
    private final DeviceProfiles profiles;
    private final AtelysAudit audit;
    private final Access access;

    DeviceController(MobileDevices devices, DeviceProfiles profiles, AtelysAudit audit, Access access) {
        this.devices = devices;
        this.profiles = profiles;
        this.audit = audit;
        this.access = access;
    }

    // ---------- terminal side ----------

    record CheckInRequest(@NotBlank @Size(max = 64) String installationId, @Size(max = 200) String manufacturer,
                          @Size(max = 200) String brand, @Size(max = 200) String model, @Size(max = 200) String product,
                          @Size(max = 40) String androidRelease, int sdkInt, @Size(max = 200) String buildId,
                          @Size(max = 80) String appVersion, @Size(max = 50) List<@Size(max = 80) String> capabilities,
                          @Size(max = 30) String resolutionState, @Size(max = 60) String recommendedAdapter,
                          @Size(max = 50) List<@Size(max = 200) String> resolutionReasons,
                          @Size(max = 60) String activeAdapter, OffsetDateTime validatedAt,
                          @Size(max = 40) String lastErrorCode, OffsetDateTime lastErrorAt) {
    }

    @PostMapping(value = "/devices/check-in", consumes = MediaType.APPLICATION_JSON_VALUE)
    MobileDevices.CheckInResult checkIn(@RequestHeader(COMPANY) UUID companyId, @Valid @RequestBody CheckInRequest r,
                                        HttpServletRequest http) {
        return devices.checkIn(companyId, new MobileDevices.CheckIn(r.installationId(), r.manufacturer(), r.brand(),
                r.model(), r.product(), r.androidRelease(), r.sdkInt(), r.buildId(), r.appVersion(), r.capabilities(),
                r.resolutionState(), r.recommendedAdapter(), r.resolutionReasons(), r.activeAdapter(),
                r.validatedAt(), r.lastErrorCode(), r.lastErrorAt()), access.actor(http));
    }

    record DiagnosticRequest(@NotNull UUID id, @NotBlank @Size(max = 60) String adapterId,
                             @NotBlank @Size(max = 20) String outcome, boolean simulated,
                             @Size(max = 40) String symbology, Integer dataLength, @Size(max = 40) String errorCode,
                             @NotNull OffsetDateTime startedAt, @NotNull OffsetDateTime finishedAt) {
    }

    @PostMapping(value = "/devices/{id}/diagnostics", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MobileDevices.Diagnostic> diagnostic(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                                                        @Valid @RequestBody DiagnosticRequest r,
                                                        HttpServletRequest http) {
        return ResponseEntity.status(201).body(devices.recordDiagnostic(companyId, id, new MobileDevices.NewDiagnostic(
                r.id(), r.adapterId(), r.outcome(), r.simulated(), r.symbology(), r.dataLength(), r.errorCode(),
                r.startedAt(), r.finishedAt()), access.actor(http)));
    }

    // ---------- portal side ----------

    @GetMapping("/device-adapters")
    List<MobileDevices.Adapter> adapters() {
        return MobileDevices.ADAPTERS;
    }

    @GetMapping("/devices")
    List<MobileDevices.Device> devices(@RequestHeader(COMPANY) UUID companyId) {
        return devices.list(companyId);
    }

    record DeviceView(MobileDevices.Device device, List<MobileDevices.Diagnostic> diagnostics,
                      List<AtelysAudit.Entry> audit, List<DeviceProfiles.Profile> profiles) {
    }

    @GetMapping("/devices/{id}")
    DeviceView device(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id) {
        return new DeviceView(devices.get(companyId, id), devices.diagnostics(companyId, id, 20),
                audit.entries(companyId, id, 30), profiles.active(companyId));
    }

    record LabelRequest(@Size(max = 80) String label) {
    }

    @PutMapping(value = "/devices/{id}/label", consumes = MediaType.APPLICATION_JSON_VALUE)
    MobileDevices.Device label(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                               @Valid @RequestBody(required = false) LabelRequest r,
                               HttpServletRequest http) {
        access.requireManager(http, companyId);
        // An empty form clears the name.
        return devices.rename(companyId, id, r == null ? null : r.label(), access.actor(http));
    }

    record PreferenceRequest(@NotBlank @Size(max = 60) String adapterId) {
    }

    @PutMapping(value = "/devices/{id}/preference", consumes = MediaType.APPLICATION_JSON_VALUE)
    MobileDevices.Device preference(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                                    @Valid @RequestBody PreferenceRequest r, HttpServletRequest http) {
        access.requireManager(http, companyId);
        return devices.prefer(companyId, id, r.adapterId(), access.actor(http));
    }

    @PostMapping("/devices/{id}/reset")
    MobileDevices.Device reset(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id, HttpServletRequest http) {
        access.requireManager(http, companyId);
        return devices.requestReset(companyId, id, access.actor(http));
    }

    @PostMapping("/devices/{id}/test-request")
    MobileDevices.Device testRequest(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                                     HttpServletRequest http) {
        access.requireManager(http, companyId);
        return devices.requestTest(companyId, id, access.actor(http));
    }

    @GetMapping("/device-profiles")
    List<DeviceProfiles.Profile> profiles(@RequestHeader(COMPANY) UUID companyId) {
        return profiles.list(companyId);
    }

    record ProfileRequest(@Size(max = 80) String name, @Size(max = 20) String scope, @Size(max = 80) String manufacturer,
                          @Size(max = 20) List<@Size(max = 80) String> models, @Size(max = 64) String installationId,
                          Integer minSdk, Integer maxSdk, @Size(max = 60) String recommendedAdapter,
                          @Size(max = 2000) String prerequisites, @Size(max = 10) String confidence,
                          @Size(max = 20) String status, @Size(max = 4000) String testProcedure) {
        DeviceProfiles.ProfileInput input() {
            return new DeviceProfiles.ProfileInput(name, scope, manufacturer, models, installationId, minSdk, maxSdk,
                    recommendedAdapter, prerequisites, confidence, status, testProcedure);
        }
    }

    @PostMapping(value = "/device-profiles", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<DeviceProfiles.Profile> createProfile(@RequestHeader(COMPANY) UUID companyId,
                                                         @Valid @RequestBody ProfileRequest r, HttpServletRequest http) {
        access.requireManager(http, companyId);
        return ResponseEntity.status(201).body(profiles.create(companyId, r.input(), access.actor(http)));
    }

    @PutMapping(value = "/device-profiles/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    DeviceProfiles.Profile updateProfile(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                                         @Valid @RequestBody ProfileRequest r, HttpServletRequest http) {
        access.requireManager(http, companyId);
        return profiles.update(companyId, id, r.input(), access.actor(http));
    }

    @PostMapping("/device-profiles/{id}/retire")
    DeviceProfiles.Profile retireProfile(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                                         HttpServletRequest http) {
        access.requireManager(http, companyId);
        return profiles.retire(companyId, id, access.actor(http));
    }
}
