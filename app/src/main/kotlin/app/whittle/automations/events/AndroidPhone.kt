package app.whittle.automations.events

import android.Manifest
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import app.whittle.automations.EventsConfig
import app.whittle.automations.Trigger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.coroutines.resume

/** Reads the phone with Android's own services. A source whose permission isn't granted reads as unknown. */
class AndroidPhoneReader(private val context: Context) : PhoneReader {
    override fun charging(): Boolean? {
        // The sticky broadcast is the current state; no receiver is registered.
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    @Suppress("DEPRECATION") // connectionInfo is the one call that works on every version from 26 on without a callback
    override fun wifi(): String? = try {
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = manager.connectionInfo
        if (info == null || info.networkId == -1) null else wifiName(info.ssid)
    } catch (_: SecurityException) {
        null
    }

    override suspend fun location(): Pair<Double, Double>? {
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return try {
            val fix = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) {
                    suspendCancellableCoroutine<Location?> { done ->
                        manager.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, { it.run() }) { done.resume(it) }
                    }
                }
            } else {
                manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
            fix?.let { it.latitude to it.longitude }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null // no network provider on this phone
        }
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val LOCATION_TIMEOUT_MILLIS = 30_000L
    }
}

/** An SSID as Android returns it: in quotes, or "<unknown ssid>" when it won't say. */
fun wifiName(ssid: String?): String? {
    val name = ssid?.removeSurrounding("\"")
    return if (name.isNullOrEmpty() || name == "<unknown ssid>") null else name
}

/** The last [PhoneState] as a small JSON file in the app's files folder. */
class FilePhoneStateStore(private val file: File) : PhoneStateStore {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): PhoneState? =
        runCatching { json.decodeFromString(PhoneState.serializer(), file.readText()) }.getOrNull()

    override suspend fun save(state: PhoneState?) {
        if (state == null) file.delete() else file.writeText(json.encodeToString(PhoneState.serializer(), state))
    }
}

/**
 * Arms Android to wake Whittle only for what is needed: a job that waits for the charger, a network callback for
 * Wi-Fi, a proximity alert per place, and one periodic job that catches every leaving and anything missed. A source
 * whose permission isn't granted is skipped.
 */
class AndroidPhoneScheduler(
    private val context: Context,
    private val config: EventsConfig,
    private val reader: PhoneReader,
) : PhoneScheduler {
    private val jobs = context.getSystemService(JobScheduler::class.java)
    private var wifiRegistered: Boolean? = null
    private var placesArmed: List<Trigger.Place>? = null
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun arm(needs: PhoneNeeds) {
        periodic(needs.any)
        chargingJob(needs.charging)
        wifiCallback(needs.wifi)
        proximityAlerts(needs.places)
    }

    private fun periodic(wanted: Boolean) {
        if (!wanted) return jobs.cancel(PERIODIC_JOB)
        val interval = config.placeCheckMinutes * 60_000L
        // Scheduling again would restart the wait, so a job that's already pending is left alone.
        if (jobs.getPendingJob(PERIODIC_JOB)?.intervalMillis == interval) return
        jobs.schedule(job(PERIODIC_JOB).setPeriodic(interval).setPersisted(true).build())
    }

    /** One run when the charger is plugged in; while it already is, a job would run at once and again, so it waits for the next check. */
    private fun chargingJob(wanted: Boolean) {
        if (!wanted || reader.charging() == true) return jobs.cancel(CHARGING_JOB)
        if (jobs.getPendingJob(CHARGING_JOB) != null) return
        jobs.schedule(job(CHARGING_JOB).setRequiresCharging(true).setPersisted(true).build())
    }

    private fun job(id: Int) = JobInfo.Builder(id, ComponentName(context, PhoneStateJob::class.java))

    /**
     * Registering delivers the current network at once, and each delivery runs a check that arms again, so it is done
     * once per process (a restart of the app or phone loses it) and not on every arm.
     */
    private fun wifiCallback(wanted: Boolean) {
        if (wanted == wifiRegistered) return
        wifiRegistered = wanted
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(context, 0, Intent(context, NetworkReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            connectivity.unregisterNetworkCallback(pending)
        } catch (_: IllegalArgumentException) {
            // None was registered.
        }
        if (!wanted) return
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        try {
            connectivity.registerNetworkCallback(request, pending)
        } catch (_: SecurityException) {
            // Without the permission, the periodic check still sees the change.
        }
    }

    private fun proximityAlerts(places: List<Trigger.Place>) {
        if (places == placesArmed) return
        placesArmed = places
        val locations = context.getSystemService(LocationManager::class.java) ?: return
        val armed = prefs.getStringSet(ARMED_PLACES, emptySet()).orEmpty()
        (armed - places.map { it.name }.toSet()).forEach { locations.removeProximityAlert(placeIntent(it)) }
        val allowed = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val now = if (allowed) places else emptyList()
        // Adding an alert with the same address replaces the one before it, so a moved place needs no removal.
        now.forEach { place ->
            try {
                locations.addProximityAlert(place.latitude, place.longitude, place.radiusMeters.toFloat(), NO_EXPIRY, placeIntent(place.name))
            } catch (_: SecurityException) {
                // Permission was taken away since the check.
            }
        }
        (armed - now.map { it.name }.toSet()).forEach { locations.removeProximityAlert(placeIntent(it)) }
        prefs.edit().putStringSet(ARMED_PLACES, now.map { it.name }.toSet()).apply()
    }

    /** The place's name is in the address, since extras don't count when Android matches one alert to another. */
    private fun placeIntent(name: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, PlaceReceiver::class.java)
            .setData(Uri.Builder().scheme("whittle-place").authority(Uri.encode(name)).build())
            .putExtra(PlaceReceiver.EXTRA_NAME, name),
        PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val PERIODIC_JOB = 6001
        const val CHARGING_JOB = 6002
        const val NO_EXPIRY = -1L
        const val PREFS = "phone-state-watch"
        const val ARMED_PLACES = "armedPlaces"
    }
}
