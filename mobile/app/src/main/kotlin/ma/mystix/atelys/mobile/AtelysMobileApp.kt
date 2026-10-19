package ma.mystix.atelys.mobile

import android.app.Application
import ma.mystix.mobile.devicehub.IntegrationResolver
import ma.mystix.mobile.devicehub.ResolverPolicy
import ma.mystix.mobile.devicehub.ScannerAdapterRegistry
import ma.mystix.mobile.devicehub.ScannerController
import ma.mystix.mobile.devicehub.ScannerDiagnosticService
import ma.mystix.mobile.devicehub.TransactionGuard

/** Wires the Device Hub once per process. WMS screens only ever see [scanner]. */
class AtelysMobileApp : Application() {
    lateinit var detector: AndroidDeviceDetector
        private set
    lateinit var repository: PreferencesDeviceProfileRepository
        private set
    lateinit var registry: ScannerAdapterRegistry
        private set
    lateinit var resolver: IntegrationResolver
        private set
    lateinit var scanner: ScannerController
        private set
    lateinit var diagnostics: ScannerDiagnosticService
        private set

    /** No WMS operation exists in the app yet; the receipt screen will report its own state here. */
    val transactions = TransactionGuard { false }

    override fun onCreate() {
        super.onCreate()
        detector = AndroidDeviceDetector(this)
        repository = PreferencesDeviceProfileRepository(this)
        registry = ScannerAdapterRegistry(listOf(HoneywellDataIntentAdapter(this), KeyboardWedgeAdapter()))
        resolver = IntegrationResolver(registry, ResolverPolicy(minSupportedSdk = 26))
        scanner = ScannerController(registry, transactions)
        diagnostics = ScannerDiagnosticService(scanner, transactions) { System.currentTimeMillis() }
    }
}
