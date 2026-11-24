package no.lionflow.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import no.lionflow.app.api.RemoteControl
import no.lionflow.app.api.StellantisApi
import no.lionflow.app.data.SecureStore
import no.lionflow.app.data.SettingsStore
import no.lionflow.app.work.StatusWorker
import java.util.concurrent.TimeUnit

class LionFlowApp : Application() {
    lateinit var settings: SettingsStore
    lateinit var secure: SecureStore
    lateinit var api: StellantisApi
    lateinit var remote: RemoteControl
    internal val savedFuelTrips by lazy { no.lionflow.app.fuel.SavedFuelTrips(this) }
    internal val fuelLog by lazy { no.lionflow.app.fuel.FuelLog(this) }
    internal val fuelFavorites by lazy { no.lionflow.app.fuel.FuelFavorites(this) }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        no.lionflow.app.ui.l10n.L10n.init(this, settings.value.language)
        no.lionflow.app.ui.l10n.TimeFmt.setPreference(this, settings.value.timeFormat)
        secure = SecureStore(this)
        secure.profile = settings.activeAccount
        api = StellantisApi(this, secure, settings)
        remote = RemoteControl(api, secure)
        no.lionflow.app.model.ChargePower.init(this, settings, api.cachedVehicle()?.vin)
        no.lionflow.app.model.ChargeLog.init(this, api.cachedVehicle()?.vin)

        // "Remember me" off: start every cold launch signed out.
        if (!settings.value.rememberMe && api.isSignedIn) {
            remote.forget()
            api.signOut()
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Car updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Charging, pre-heating, doors, battery and service"
        })
        scheduleBackgroundChecks()
        no.lionflow.app.work.Waker.schedule(this)
        no.lionflow.app.work.SmartSchedule.schedule(this)
    }

    fun scheduleBackgroundChecks() {
        val req = PeriodicWorkRequestBuilder<StatusWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("status-check", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    companion object {
        const val CHANNEL = "car_updates"
    }
}
