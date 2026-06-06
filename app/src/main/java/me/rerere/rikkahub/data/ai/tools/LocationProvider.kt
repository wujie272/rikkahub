package me.rerere.rikkahub.data.ai.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
 * 线程安全的内存缓存
 * 有效位置（精度 ≤ 100m）缓存 5 分钟
 */
private data class CacheEntry(
    val result: LocationResult,
    val cachedAt: Long,
)

private val locationCache = AtomicReference<CacheEntry?>(null)
private const val CACHE_TTL_MS = 5 * 60 * 1000L
private const val HIGH_ACCURACY_CACHE_TTL_MS = 2 * 60 * 1000L // 高精度（≤20m）缓存 2 分钟
private const val MAX_TIMEOUT_MS = 4000L

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
 * 1. 检查内存缓存（高精度 2min / 普通 5min）
 * 2. [LocationManager.getLastKnownLocation]（瞬时返回，2分钟内新鲜）
 * 3. API 30+：[LocationManager.getCurrentLocation]（精准快速）
 * 4. API < 30：[LocationManager.requestSingleUpdate]（异步回调）
 * 5. 有网络时用 [Geocoder] 逆地理编码获取地址
 *
 * @return LocationResult，当获取失败时 latitude/longitude 为 0.0
 */
fun getCurrentLocation(context: Context, eventBus: me.rerere.rikkahub.data.event.AppEventBus? = null): LocationResult {
    // 线程安全缓存检查
    val cached = locationCache.get()
    val now = System.currentTimeMillis()
    if (cached != null) {
        val ttl = if (cached.result.accuracy <= 20f) HIGH_ACCURACY_CACHE_TTL_MS else CACHE_TTL_MS
        if (now - cached.cachedAt < ttl) {
            return cached.result
        }
    }

    if (!hasLocationPermission(context)) {
        // 权限未授予 → 发送事件到 Activity 发起系统权限请求
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val bus = eventBus ?: me.rerere.rikkahub.data.event.AppEventBus()
        kotlinx.coroutines.runBlocking { bus.emit(
            me.rerere.rikkahub.data.event.AppEvent.RequestLocationPermission(deferred)
        ) }
        val granted = runBlocking { deferred.await() }
        if (!granted) {
            throw IllegalStateException(
                "Location permission was denied by user."
            )
        }
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

    // 只缓存有效位置
    if (result.accuracy <= 100f && result.latitude != 0.0) {
        locationCache.set(CacheEntry(result, System.currentTimeMillis()))
    }

    return result
}

/**
 * 获取最佳可用位置
 */
private fun getBestLocation(locationManager: LocationManager): Location? {
    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )

    // 1. 先尝试 getLastKnownLocation（同步、瞬时）
    val lastLocations = providers.mapNotNull { provider ->
        try {
            locationManager.getLastKnownLocation(provider)?.also {
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

    // 2 分钟内返回，足够新鲜
    if (bestLast != null && System.currentTimeMillis() - bestLast.time < 2 * 60 * 1000) {
        return bestLast
    }

    // 2. 尝试获取全新位置
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
        val executor = Executors.newSingleThreadExecutor()
        val cancellationSignals = mutableListOf<CancellationSignal>()

        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
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

/**
 * 使用 [LocationManager.requestSingleUpdate]（API < 30 回退方案）
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

        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (deferred.isCompleted) break
            try {
                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                scheduler.schedule({
                    if (!deferred.isCompleted) {
                        try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
                        deferred.complete(null)
                    }
                }, MAX_TIMEOUT_MS, TimeUnit.MILLISECONDS)
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
