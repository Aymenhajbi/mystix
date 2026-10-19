package ma.mystix.atelys.mobile

import android.content.Context
import java.time.Instant
import java.util.UUID
import ma.mystix.mobile.devicehub.CompatibilityProfile
import ma.mystix.mobile.devicehub.Confidence
import ma.mystix.mobile.devicehub.DiagnosticResult
import ma.mystix.mobile.devicehub.ProfileScope
import ma.mystix.mobile.devicehub.ProfileStatus
import ma.mystix.mobile.devicehub.Resolution
import org.json.JSONArray
import org.json.JSONObject

/** What the portal asked the last time the terminal reached it. */
data class PortalState(
    val deviceId: String?,
    val label: String?,
    val preferredAdapter: String?,
    val testRequested: Boolean,
    val resetApplied: Boolean,
    val pendingDiagnostics: Int,
    val lastError: String?,
)

/**
 * Check-in with the portal (ADR-0019, lot D): sends what the terminal detected and runs, receives profiles and the
 * administrator's requests, uploads the scan tests. Works offline: scan tests wait in a local queue with their own
 * id, so a resend after a lost answer is stored once by the server. Never changes the active scanner by itself,
 * except a reset asked by an administrator, which only forgets the validated configuration.
 */
class DeviceSync(context: Context, private val app: AtelysMobileApp, val api: ApiClient) {
    private val prefs = context.getSharedPreferences("atelys.sync", Context.MODE_PRIVATE)

    @Synchronized
    fun state(): PortalState = PortalState(
        prefs.getString("deviceId", null), prefs.getString("label", null), prefs.getString("preferred", null),
        prefs.getBoolean("testRequested", false), false, queue().length(), prefs.getString("lastError", null))

    /** Keeps a finished scan test for upload. The id is generated once here and reused on every retry. */
    @Synchronized
    fun queueDiagnostic(result: DiagnosticResult) {
        val q = queue()
        q.put(JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("adapterId", result.adapterId)
            .put("outcome", result.outcome.name)
            .put("simulated", result.simulated)
            .put("symbology", result.symbology ?: JSONObject.NULL)
            .put("dataLength", result.dataLength ?: JSONObject.NULL)
            .put("errorCode", result.errorCode?.name ?: JSONObject.NULL)
            .put("startedAt", Instant.ofEpochMilli(result.startedAtMillis).toString())
            .put("finishedAt", Instant.ofEpochMilli(result.finishedAtMillis).toString()))
        prefs.edit().putString("queue", q.toString()).apply()
    }

    /** One round trip. Call it off the main thread. */
    fun sync(): PortalState {
        var reset = false
        var outcome = checkIn()
        if (outcome is ApiResult.Ok && outcome.body.optBoolean("resetRequested")) {
            val installation = app.detector.installationId
            app.scanner.release()
            app.repository.clearConfiguration(installation)
            reset = true
            outcome = checkIn()
        }
        if (outcome is ApiResult.Ok) {
            val b = outcome.body
            app.repository.replaceProfiles(profiles(b.optJSONArray("profiles") ?: JSONArray()))
            synchronized(this) {
                prefs.edit().putString("deviceId", b.getString("deviceId"))
                    .putString("label", b.optString("label").takeIf { !b.isNull("label") })
                    .putString("preferred", b.optString("preferredAdapter").takeIf { !b.isNull("preferredAdapter") })
                    .putBoolean("testRequested", b.optBoolean("testRequested"))
                    .remove("lastError").apply()
            }
            flushDiagnostics(b.getString("deviceId"))
        } else if (outcome is ApiResult.Failure) {
            prefs.edit().putString("lastError", outcome.errorCode).apply()
        }
        return state().copy(resetApplied = reset)
    }

    private fun checkIn(): ApiResult {
        val snapshot = app.detector.detect()
        val d = snapshot.device
        val saved = app.repository.loadConfiguration(d.installationId)
        val resolution: Resolution = app.resolver.resolve(snapshot, saved, app.repository.profiles())
        val body = JSONObject()
            .put("installationId", d.installationId)
            .put("manufacturer", d.manufacturer).put("brand", d.brand).put("model", d.model).put("product", d.product)
            .put("androidRelease", d.androidRelease).put("sdkInt", d.sdkInt).put("buildId", d.buildId)
            .put("appVersion", d.appVersion)
            .put("capabilities", JSONArray(snapshot.capabilities.map { it.id }))
            .put("resolutionState", resolution.state.name)
            .put("recommendedAdapter", resolution.recommendedAdapterId ?: JSONObject.NULL)
            .put("resolutionReasons", JSONArray(resolution.reasons.map {
                if (it.detail.isEmpty()) it.code.name else "${it.code.name}: ${it.detail}"
            }))
        if (saved != null) {
            body.put("activeAdapter", saved.adapterId)
                .put("validatedAt", Instant.ofEpochMilli(saved.test.testedAtMillis).toString())
        }
        return api.call("POST", "/api/v1/atelys/devices/check-in", body)
    }

    private fun flushDiagnostics(deviceId: String) {
        val pending = synchronized(this) { queue() }
        val kept = JSONArray()
        for (i in 0 until pending.length()) {
            val item = pending.getJSONObject(i)
            val r = api.call("POST", "/api/v1/atelys/devices/$deviceId/diagnostics", item)
            // Kept only when the server was not reached; a refusal will not change on retry.
            if (r is ApiResult.Failure && r.retryable) kept.put(item)
        }
        synchronized(this) {
            // Tests queued meanwhile are kept too.
            val now = queue()
            val sent = (0 until pending.length()).map { pending.getJSONObject(it).getString("id") }.toSet()
            for (i in 0 until now.length()) if (now.getJSONObject(i).getString("id") !in sent) kept.put(now.getJSONObject(i))
            prefs.edit().putString("queue", kept.toString()).apply()
        }
    }

    private fun queue() = JSONArray(prefs.getString("queue", "[]"))

    private fun profiles(array: JSONArray): List<CompatibilityProfile> = (0 until array.length()).mapNotNull { i ->
        val p = array.getJSONObject(i)
        runCatching {
            CompatibilityProfile(
                id = p.getString("id"),
                version = p.getInt("version"),
                scope = ProfileScope.valueOf(p.getString("scope")),
                manufacturer = p.getString("manufacturer"),
                models = p.optJSONArray("models")?.let { m -> (0 until m.length()).map { m.getString(it) }.toSet() }.orEmpty(),
                installationId = p.optString("installationId").takeIf { !p.isNull("installationId") },
                minSdk = p.optInt("minSdk").takeIf { !p.isNull("minSdk") },
                maxSdk = p.optInt("maxSdk").takeIf { !p.isNull("maxSdk") },
                recommendedAdapterId = p.getString("recommendedAdapter"),
                prerequisites = p.optString("prerequisites").takeIf { !p.isNull("prerequisites") && it.isNotBlank() }
                    ?.lines()?.filter { it.isNotBlank() }.orEmpty(),
                confidence = Confidence.valueOf(p.getString("confidence")),
                status = ProfileStatus.valueOf(p.getString("status")),
                testProcedure = p.optString("testProcedure").takeIf { !p.isNull("testProcedure") }.orEmpty(),
            )
        }.getOrNull()
    }
}
