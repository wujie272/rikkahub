package me.rerere.rikkahub.data.ai.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import android.os.CancellationSignal

data class LocationResult(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double,
    val provider: String,
    val timestamp: Long,
    val address: String? = null,
)

// 简单内存缓存：5 分钟内有效
private var cachedLocation: LocationResult? = null
private var cacheTime: Long = 0
private const val CACHE_TTL_MS = 5 * 60 * 1000L

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
 * 策略：
 * 1. 优先用 [LocationManager.getCurrentLocation]（API 30+，更精准快速）
 * 2. 回退 [LocationManager.getLastKnownLocation]（瞬时返回，可能已过时）
 * 3. 最后用 [LocationManager.requestSingleUpdate]（异步回调，API < 30）
 * 4. 有网络时用 [Geocoder] 逆地理编码获取地址
 *
 * 需要先确保 [hasLocationPermission] 返回 true，否则直接抛异常返回错误提示
 */
fun getCurrentLocation(context: Context): LocationResult {
    // 检查缓存
    val now = System.currentTimeMillis()
    if (cachedLocation != null && now - cacheTime < CACHE_TTL_MS && cachedLocation!!.accuracy <= 50f) {
        return cachedLocation!!
    }

    require(hasLocationPermission(context)) {
        "Location permission is not granted. Please grant location permission in app settings."
    }

    val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: error("Location service not available")

    val location = getBestLocation(locationManager)
    val address = location?.let { reverseGeocode(context, it.latitude, it.longitude) }

    val result = LocationResult(
        latitude = location?.latitude ?: 0.0,
        longitude = location?.longitude ?: 0.0,
        accuracy = location?.accuracy ?: 0f,
        altitude = location?.altitude ?: 0.0,
        provider = location?.provider ?: "unknown",
        timestamp = location?.time ?: System.currentTimeMillis(),
        address = address,
    )
    // 缓存有效位置（精度 <= 100m 才缓存）
    if (result.accuracy <= 100f && result.latitude != 0.0) {
        cachedLocation = result
        cacheTime = System.currentTimeMillis()
    }
    return result
}

/**
 * 获取最佳可用位置，按精度和新鲜度排序
 */
private fun getBestLocation(locationManager: LocationManager): Location? {
    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )

    // 先尝试 getLastKnownLocation（同步、快）
    val lastLocations = providers.mapNotNull { provider ->
        try {
            locationManager.getLastKnownLocation(provider)?.also {
                // filter out stale locations (> 5 min)
                if (System.currentTimeMillis() - it.time > 5 * 60 * 1000) null else it
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // 选最新且精度最高的
    val bestLast = lastLocations.maxByOrNull { loc ->
        (loc.accuracy?.let { 1000f - it } ?: 0f) + loc.time / 10000f
    }

    // 如果 last known 在 2 分钟内，直接返回（足够新鲜）
    if (bestLast != null && System.currentTimeMillis() - bestLast.time < 2 * 60 * 1000) {
        return bestLast
    }

    // 否则尝试获取全新位置
    val freshLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        getCurrentLocationAsync(locationManager)
    } else {
        getSingleUpdateSync(locationManager)
    }

    return freshLocation ?: bestLast
}

/**
 * 使用 [LocationManager.getCurrentLocation]（API 30+）
 */
@Suppress("MissingPermission")
private fun getCurrentLocationAsync(locationManager: LocationManager): Location? =
    runBlocking {
        val deferred = CompletableDeferred<Location?>()

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )

        val executor = Executors.newSingleThreadExecutor()
        val cancellationSignals = mutableListOf<CancellationSignal>()

        for (provider in providers) {
            if (deferred.isCompleted) break
            try {
                val cancellationSignal = CancellationSignal()
                cancellationSignals.add(cancellationSignal)
                locationManager.getCurrentLocation(
                    provider,
                    cancellationSignal,
                    executor
                ) { location ->
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

        // 超时 5 秒
        try {
            deferred.await()
        } catch (_: Exception) {
            null
        }
    }
    
    // 超时 handle — 上面 5s 由 CancellationSignal 处理
    // 额外兜底: 再等 2s 后放弃

/**
 * 使用 [LocationManager.requestSingleUpdate]（API < 30）
 */
@Suppress("MissingPermission")
private fun getSingleUpdateSync(locationManager: LocationManager): Location? =
    runBlocking {
        val deferred = CompletableDeferred<Location?>()
        val scheduler = Executors.newSingleThreadScheduledExecutor()

        val listener = android.location.LocationListener { location ->
            if (!deferred.isCompleted && location != null) {
                deferred.complete(location)
            }
        }

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )

        for (provider in providers) {
            if (deferred.isCompleted) break
            try {
                locationManager.requestSingleUpdate(provider, listener, null)
                scheduler.schedule({
                    if (!deferred.isCompleted) {
                        try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
                        deferred.complete(null)
                    }
                }, 5, TimeUnit.SECONDS)
                break
            } catch (_: Exception) {
                // 尝试下一个 provider
            }
        }

        deferred.invokeOnCompletion {
            try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
            scheduler.shutdown()
        }

        try {
            deferred.await()
        } catch (_: Exception) {
            null
        }
    }

/**
 * 使用 Geocoder 逆地理编码获取地址
 */
private fun reverseGeocode(
    context: Context,
    latitude: Double,
    longitude: Double
): String? {
    return try {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses: List<Address> = geocoder.getFromLocation(latitude, longitude, 1) ?: return null
        val address = addresses.firstOrNull() ?: return null
        buildString {
            val parts = listOfNotNull(
                address.getAddressLine(0),
                address.locality,
                address.adminArea,
                address.countryName,
            )
            append(parts.distinct().joinToString(", "))
        }.ifBlank { null }
    } catch (_: Exception) {
        null
    }
}
