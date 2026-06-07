package me.rerere.rikkahub.data.ai.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.Executors

data class LocationResult(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double,
    val provider: String,
    val timestamp: Long,
    val address: String? = null,
)

/**
 * 检查位置权限是否已授予
 */
fun hasLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
    ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}

/**
 * 获取当前设备位置
 *
 * 只使用系统标准 [LocationManager.getCurrentLocation] API（API 30+）。
 * 不缓存、不用 lastKnownLocation、不用旧 API 回退。
 *
 * API 26-29 的设备返回空位置（latitude/longitude = 0.0）。
 *
 * @return LocationResult，获取失败时 latitude/longitude 为 0.0
 */
fun getCurrentLocation(
    context: Context,
    eventBus: me.rerere.rikkahub.data.event.AppEventBus? = null
): LocationResult {
    // 权限检查
    if (!hasLocationPermission(context)) {
        val deferred = CompletableDeferred<Boolean>()
        val bus = eventBus ?: me.rerere.rikkahub.data.event.AppEventBus()
        runBlocking { bus.emit(
            me.rerere.rikkahub.data.event.AppEvent.RequestLocationPermission(deferred)
        ) }
        val granted = runBlocking { deferred.await() }
        if (!granted) {
            throw IllegalStateException("Location permission was denied by user.")
        }
    }

    // API < 30 不支持 getCurrentLocation，返回空
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        return LocationResult(
            latitude = 0.0,
            longitude = 0.0,
            accuracy = 0f,
            altitude = 0.0,
            provider = "unsupported",
            timestamp = System.currentTimeMillis(),
            address = null,
        )
    }

    val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: error("Location service not available")

    // 只使用系统标准 getCurrentLocation API
    val location = requestCurrentLocation(locationManager) ?: return LocationResult(
        latitude = 0.0,
        longitude = 0.0,
        accuracy = 0f,
        altitude = 0.0,
        provider = "unavailable",
        timestamp = System.currentTimeMillis(),
        address = null,
    )

    // 逆地理编码
    val address = try {
        if (Geocoder.isPresent()) {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses: List<Address> = geocoder.getFromLocation(
                location.latitude, location.longitude, 1
            ) ?: emptyList()
            addresses.firstOrNull()?.let { addr ->
                buildString {
                    val parts = listOfNotNull(
                        addr.getAddressLine(0),
                        addr.locality,
                        addr.adminArea,
                        addr.countryName,
                    )
                    append(parts.distinct().joinToString(", "))
                }.ifBlank { null }
            }
        } else null
    } catch (_: Exception) {
        null
    }

    return LocationResult(
        latitude = location.latitude,
        longitude = location.longitude,
        accuracy = location.accuracy,
        altitude = location.altitude,
        provider = location.provider ?: "gps",
        timestamp = location.time,
        address = address,
    )
}

/**
 * 使用 [LocationManager.getCurrentLocation]（API 30+）
 * 同时请求 GPS 和 NETWORK provider，谁先返回用谁。
 */
@Suppress("MissingPermission")
private fun requestCurrentLocation(locationManager: LocationManager): android.location.Location? =
    runBlocking {
        val deferred = CompletableDeferred<android.location.Location?>()
        val executor = Executors.newSingleThreadExecutor()
        val cancellationSignals = mutableListOf<CancellationSignal>()

        for (provider in listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )) {
            if (deferred.isCompleted) break
            try {
                val cs = CancellationSignal()
                cancellationSignals.add(cs)
                locationManager.getCurrentLocation(provider, cs, executor) { location ->
                    if (!deferred.isCompleted && location != null) {
                        deferred.complete(location)
                    }
                }
            } catch (_: Exception) {
                // 尝试下一个 provider
            }
        }

        deferred.invokeOnCompletion {
            cancellationSignals.forEach { it.cancel() }
            executor.shutdown()
        }

        try {
            deferred.await()
        } catch (_: Exception) {
            null
        }
    }
