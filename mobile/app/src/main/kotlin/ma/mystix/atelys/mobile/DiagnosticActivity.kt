package ma.mystix.atelys.mobile

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ma.mystix.mobile.devicehub.ConfiguredBy
import ma.mystix.mobile.devicehub.DiagnosticOutcome
import ma.mystix.mobile.devicehub.DiagnosticResult
import ma.mystix.mobile.devicehub.Resolution
import ma.mystix.mobile.devicehub.ResolutionState
import ma.mystix.mobile.devicehub.ScanConsumer
import ma.mystix.mobile.devicehub.ScanEvent
import ma.mystix.mobile.devicehub.ScannerAdapter
import ma.mystix.mobile.devicehub.ScannerDiagnosticService
import ma.mystix.mobile.devicehub.ScannerException

/**
 * Lot B diagnostic screen: what Android declares, what the resolver decides and why, a scan test per adapter.
 * A configuration is saved only after a passed, non-simulated read on this terminal.
 */
class DiagnosticActivity : Activity(), ScanConsumer {
    private val app get() = application as AtelysMobileApp
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var deviceText: TextView
    private lateinit var resolutionText: TextView
    private lateinit var resultText: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var portalText: TextView
    private lateinit var portalForm: LinearLayout
    private var run: ScannerDiagnosticService.Run? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.diag_title)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        deviceText = TextView(this)
        resolutionText = TextView(this)
        resultText = TextView(this)
        buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(section(R.string.diag_device))
        column.addView(deviceText)
        column.addView(section(R.string.diag_resolution))
        column.addView(resolutionText)
        column.addView(buttons)
        column.addView(resultText)
        column.addView(section(R.string.portal_title))
        portalText = TextView(this)
        portalForm = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(portalText)
        column.addView(portalForm)
        column.addView(TextView(this).apply { setText(R.string.diag_hardware_note) })
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onStart() {
        super.onStart()
        app.scanner.attach(this)
        refresh(activate = true)
        renderPortal()
        syncInBackground()
    }

    override fun onStop() {
        run?.cancel()
        run = null
        handler.removeCallbacksAndMessages(null)
        app.scanner.detach(this)
        app.scanner.release()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        WedgeKeySource.dispatch(event) || super.dispatchKeyEvent(event)

    override fun onScan(event: ScanEvent) {
        runOnUiThread {
            resultText.text = getString(R.string.diag_last_scan, event.data, event.symbology ?: "?")
        }
        crossCheck(event.data)
    }

    /** Asks the portal what this barcode is: product of the environment and open product reference. */
    private fun crossCheck(code: String) {
        if (app.sync.api.signedInAs == null || code.any { !it.isDigit() }) return
        app.network.execute {
            val r = app.sync.api.call("GET", "/api/v1/wms/products/by-gtin/" + java.net.URLEncoder.encode(code, "UTF-8"), null)
            val text = when (r) {
                is ApiResult.Ok -> {
                    val ref = r.body.optJSONObject("reference")
                    val product = r.body.optJSONObject("product")
                    val match = ref?.optJSONArray("matches")?.optJSONObject(0)
                    when {
                        ref?.optBoolean("valid") != true -> getString(R.string.scan_invalid)
                        product != null -> getString(R.string.scan_product, product.optString("name"), product.optString("sku"))
                        match != null -> getString(R.string.scan_reference, match.optString("name"), match.optString("brands"), match.optString("sourceLabel"))
                        else -> getString(R.string.scan_unknown)
                    }
                }
                is ApiResult.Failure -> getString(R.string.portal_error, r.errorCode)
            }
            runOnUiThread { if (!isFinishing) resultText.append("\n" + text) }
        }
    }

    override fun onScannerError(error: ScannerException) {
        runOnUiThread { resultText.text = error.code.name }
    }

    private fun refresh(activate: Boolean) {
        val snapshot = app.detector.detect()
        val d = snapshot.device
        val saved = app.repository.loadConfiguration(d.installationId)
        val resolution = app.resolver.resolve(snapshot, saved, app.repository.profiles())
        deviceText.text = listOf(
            "${d.manufacturer} / ${d.brand} / ${d.model} (${d.product})",
            "Android ${d.androidRelease} · API ${d.sdkInt} · build ${d.buildId}",
            "Atelys ${d.appVersion} · installation ${d.installationId.take(8)}",
            "${getString(R.string.diag_saved)} : ${saved?.adapterId ?: getString(R.string.diag_none)}",
        ).joinToString("\n")
        resolutionText.text = describe(resolution)
        buttons.removeAllViews()
        app.registry.all().forEach { adapter ->
            buttons.addView(Button(this).apply {
                text = getString(R.string.diag_test, adapter.support.displayName)
                setOnClickListener { startTest(adapter) }
            })
        }
        buttons.addView(Button(this).apply {
            setText(R.string.diag_reset)
            setOnClickListener {
                app.scanner.release()
                app.repository.clearConfiguration(d.installationId)
                refresh(activate = false)
            }
        })
        // Only a configuration already validated here is started without asking.
        if (activate && resolution.state == ResolutionState.AUTO_SELECTED) {
            resolution.recommendedAdapterId?.let { app.scanner.switchTo(it) }
        }
    }

    private fun describe(r: Resolution): String = buildList {
        add("${r.state} · ${r.recommendedAdapterId ?: "—"} · ${r.confidence}")
        r.reasons.forEach { add("• ${it.code}${if (it.detail.isNotEmpty()) " (${it.detail})" else ""}") }
        r.missingPrerequisites.forEach { add("! $it") }
        if (r.alternatives.isNotEmpty()) add("→ ${r.alternatives.joinToString()}")
        r.pendingRecommendation?.let { add("↻ $it") }
    }.joinToString("\n")

    private fun startTest(adapter: ScannerAdapter) {
        run?.cancel()
        when (val started = app.diagnostics.start(adapter, timeoutMillis = TEST_TIMEOUT_MS)) {
            ScannerDiagnosticService.StartResult.TransactionInProgress -> resultText.setText(R.string.diag_busy)
            is ScannerDiagnosticService.StartResult.Started -> {
                run = started.run
                resultText.text = getString(R.string.diag_waiting, (TEST_TIMEOUT_MS / 1000).toInt())
                poll(started.run)
            }
        }
    }

    private fun poll(current: ScannerDiagnosticService.Run) {
        val result = current.poll()
        if (result == null) {
            handler.postDelayed({ if (run === current) poll(current) }, 250)
            return
        }
        run = null
        show(result)
    }

    private fun show(result: DiagnosticResult) {
        val device = app.detector.detect().device
        val config = app.diagnostics.toValidatedConfiguration(result, device, ConfiguredBy.OPERATOR, null)
        val verdict = when {
            result.simulated -> getString(R.string.diag_simulated)
            config != null -> getString(R.string.diag_saved_ok)
            else -> getString(R.string.diag_not_saved)
        }
        if (config != null) app.repository.saveConfiguration(config)
        app.sync.queueDiagnostic(result)
        syncInBackground()
        val detail = buildString {
            append(result.outcome)
            result.symbology?.let { append(" · $it") }
            result.dataLength?.let { append(" · $it car.") }
            result.errorCode?.let { append(" · $it") }
        }
        refresh(activate = result.outcome == DiagnosticOutcome.PASSED)
        resultText.text = getString(R.string.diag_result, detail, verdict)
    }

    /** Sign-in and the portal requests. The password is never stored, only the session token. */
    private fun renderPortal() {
        val state = app.sync.state()
        val api = app.sync.api
        portalText.text = buildList {
            add(api.signedInAs?.let { getString(R.string.portal_signed_in, it, api.serverUrl) } ?: getString(R.string.portal_signed_out))
            state.deviceId?.let { add(getString(R.string.portal_registered, state.label ?: it.take(8))) }
            state.preferredAdapter?.let { add(getString(R.string.portal_preferred, it)) }
            if (state.testRequested) add(getString(R.string.portal_test_requested))
            if (state.pendingDiagnostics > 0) add(getString(R.string.portal_pending, state.pendingDiagnostics))
            state.lastError?.let { add(getString(R.string.portal_error, it)) }
        }.joinToString("\n")
        portalForm.removeAllViews()
        if (api.signedInAs == null) {
            val server = EditText(this).apply { setText(api.serverUrl); hint = getString(R.string.portal_server); inputType = InputType.TYPE_TEXT_VARIATION_URI }
            val email = EditText(this).apply { hint = getString(R.string.portal_email); inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
            val password = EditText(this).apply { hint = getString(R.string.portal_password); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            listOf(server, email, password).forEach(portalForm::addView)
            portalForm.addView(Button(this).apply {
                setText(R.string.portal_sign_in)
                setOnClickListener {
                    val secret = password.text.toString().toCharArray()
                    password.setText("")
                    api.serverUrl = server.text.toString()
                    val login = email.text.toString()
                    portalText.setText(R.string.portal_connecting)
                    app.network.execute {
                        val r = api.signIn(login, secret)
                        val state2 = if (r is ApiResult.Ok) app.sync.sync() else null
                        runOnUiThread {
                            renderPortal()
                            if (r is ApiResult.Failure) portalText.append("\n" + getString(R.string.portal_error, r.errorCode))
                            if (state2?.resetApplied == true) refresh(activate = false)
                        }
                    }
                }
            })
        } else {
            portalForm.addView(Button(this).apply {
                setText(R.string.portal_sync)
                setOnClickListener { syncInBackground() }
            })
            portalForm.addView(Button(this).apply {
                setText(R.string.portal_sign_out)
                setOnClickListener { app.network.execute { api.signOut(); runOnUiThread { renderPortal() } } }
            })
        }
    }

    private fun syncInBackground() {
        if (app.sync.api.signedInAs == null) return
        app.network.execute {
            val state = app.sync.sync()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                renderPortal()
                if (state.resetApplied) refresh(activate = false)
            }
        }
    }

    private fun section(title: Int) = TextView(this).apply {
        setText(title)
        textSize = 18f
        setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 15_000L
    }
}
