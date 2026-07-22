package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.telephony.TelephonyManager
import android.text.format.Formatter
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.net.Inet4Address

// ─────────────────────────────────────────────
//  Merge device-info queries into one tool
// ─────────────────────────────────────────────

fun getDeviceInfoTool(context: Context): Tool = Tool(
    name = "get_device_info",
    description = """
        Query device information. Specify a type to get specific data, or "all" for everything.
        Types: "battery" (level, charging, temp), "audio" (ringer, music, headphones),
        "telephony" (SIM, carrier, network type; requires READ_PHONE_STATE),
        "wifi" (SSID, IP, signal; requires location permission for SSID),
        "sensors" (list of available sensors), "storage" (internal/external free space).
        "all" returns battery + audio + storage + (telephony/wifi if permissions granted).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("type", buildJsonObject {
                    put("type", "string")
                    put("description", "One of: battery, audio, telephony, wifi, sensors, storage, all")
                })
            },
            required = listOf("type")
        )
    },
    execute = { input ->
        val type = input.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "all"
        val payload = when (type) {
            "battery" -> buildBatteryPayload(context)
            "audio" -> buildAudioPayload(context)
            "telephony" -> buildTelephonyPayload(context)
            "wifi" -> buildWifiPayload(context)
            "sensors" -> buildSensorsListPayload(context)
            "storage" -> buildStoragePayload()
            "all" -> buildJsonObject {
                put("battery", buildBatteryPayload(context))
                put("audio", buildAudioPayload(context))
                put("storage", buildStoragePayload())
                put("telephony", buildTelephonyPayload(context))
                put("wifi", buildWifiPayload(context))
            }
            else -> buildJsonObject { put("error", "unknown type: $type") }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

// ─────────────────────────────────────────────
//  Battery
// ─────────────────────────────────────────────

private fun buildBatteryPayload(context: Context): JsonObject {
    val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    if (intent == null) return buildJsonObject { put("error", "battery status unavailable") }
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    val plugged = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
        else -> "none"
    }
    val health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "unspecified_failure"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        else -> "unknown"
    }
    val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
    val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
    val technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
    return buildJsonObject {
        put("percent", percent)
        put("charging", charging)
        put("plugged", plugged)
        put("health", health)
        if (tempTenths != Int.MIN_VALUE) put("temperature_c", tempTenths / 10.0)
        put("voltage_mv", voltage)
        put("technology", technology ?: "")
    }
}

// ─────────────────────────────────────────────
//  Audio
// ─────────────────────────────────────────────

private fun audioDeviceTypeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "builtin_earpiece"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "builtin_speaker"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
    AudioDeviceInfo.TYPE_HDMI -> "hdmi"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb_accessory"
    AudioDeviceInfo.TYPE_DOCK -> "dock"
    AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
    AudioDeviceInfo.TYPE_LINE_ANALOG -> "line_analog"
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> "line_digital"
    AudioDeviceInfo.TYPE_AUX_LINE -> "aux_line"
    AudioDeviceInfo.TYPE_IP -> "ip"
    AudioDeviceInfo.TYPE_BUS -> "bus"
    AudioDeviceInfo.TYPE_HEARING_AID -> "hearing_aid"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "builtin_speaker_safe"
    else -> "unknown"
}

private fun buildAudioPayload(context: Context): JsonObject {
    val am = context.getSystemService(AudioManager::class.java) ?: return buildJsonObject { put("error", "AudioManager unavailable") }
    val ringer = when (am.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        AudioManager.RINGER_MODE_NORMAL -> "normal"
        else -> "unknown"
    }
    val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val headphoneTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET,
    )
    val headphonesConnected = outputs.any { it.type in headphoneTypes }
    return buildJsonObject {
        put("ringer_mode", ringer)
        put("music_active", am.isMusicActive)
        put("headphones_connected", headphonesConnected)
        put("output_devices", buildJsonArray {
            outputs.forEach { dev ->
                addJsonObject {
                    put("type", dev.type)
                    put("type_name", audioDeviceTypeName(dev.type))
                    put("product_name", dev.productName?.toString() ?: "")
                }
            }
        })
    }
}

// ─────────────────────────────────────────────
//  Telephony
// ─────────────────────────────────────────────

private fun networkTypeName(type: Int): String = when (type) {
    1 -> "GPRS"; 2 -> "EDGE"; 3 -> "UMTS"; 4 -> "CDMA"; 8 -> "HSDPA"
    9 -> "HSUPA"; 10 -> "HSPA"; 13 -> "LTE"; 15 -> "HSPAP"; 18 -> "IWLAN"; 20 -> "NR"
    else -> "unknown"
}

private fun phoneTypeName(type: Int): String = when (type) {
    0 -> "none"; 1 -> "gsm"; 2 -> "cdma"; 3 -> "sip"; else -> "unknown"
}

private fun buildTelephonyPayload(context: Context): JsonObject {
    if (!PermissionHelper.hasRuntime(context, listOf(Manifest.permission.READ_PHONE_STATE))) {
        return buildJsonObject { put("error", "permission READ_PHONE_STATE not granted") }
    }
    val tm = context.getSystemService(TelephonyManager::class.java)
        ?: return buildJsonObject { put("error", "telephony service unavailable") }
    return try {
        val hasSim = tm.simState == TelephonyManager.SIM_STATE_READY
        val networkType = try { tm.dataNetworkType } catch (_: SecurityException) { @Suppress("DEPRECATION") tm.networkType }
        buildJsonObject {
            put("has_sim", hasSim)
            put("sim_operator", tm.simOperator ?: "")
            put("sim_country", tm.simCountryIso ?: "")
            put("network_operator", tm.networkOperator ?: "")
            put("network_country", tm.networkCountryIso ?: "")
            put("network_type", networkTypeName(networkType))
            put("phone_type", phoneTypeName(tm.phoneType))
        }
    } catch (_: SecurityException) {
        buildJsonObject { put("error", "permission READ_PHONE_STATE not granted") }
    }
}

// ─────────────────────────────────────────────
//  Wi-Fi
// ─────────────────────────────────────────────

private fun buildWifiPayload(context: Context): JsonObject {
    if (!PermissionHelper.hasRuntime(context, listOf(Manifest.permission.ACCESS_FINE_LOCATION))) {
        return buildJsonObject { put("error", "permission ACCESS_FINE_LOCATION not granted") }
    }
    val appCtx = context.applicationContext
    val cm = appCtx.getSystemService(ConnectivityManager::class.java)
        ?: return buildJsonObject { put("error", "connectivity service unavailable") }
    val activeNet = cm.activeNetwork
    val caps = activeNet?.let { cm.getNetworkCapabilities(it) }
    val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    if (!onWifi) return buildJsonObject { put("connected", false) }
    val wm = appCtx.getSystemService(WifiManager::class.java)
    return buildJsonObject {
        put("connected", true)
        if (wm == null) { put("note", "wifi service unavailable; details omitted"); return@buildJsonObject }
        try {
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            if (info == null) { put("note", "WifiManager returned no info; details omitted"); return@buildJsonObject }
            putWifiFields(this, info)
        } catch (_: SecurityException) {
            put("note", "WifiManager refused with SecurityException; details omitted")
        }
        val link = activeNet.let { cm.getLinkProperties(it) }
        val ipv4 = link?.linkAddresses?.firstOrNull { (it.address as? Inet4Address) != null && !it.address.isLoopbackAddress }
        if (ipv4 != null) put("ip", ipv4.address.hostAddress ?: "")
    }
}

private fun putWifiFields(builder: JsonObjectBuilder, info: android.net.wifi.WifiInfo) {
    val rawSsid = info.ssid ?: ""
    val ssid = if (rawSsid.startsWith("\"") && rawSsid.endsWith("\"") && rawSsid.length >= 2) rawSsid.substring(1, rawSsid.length - 1) else rawSsid
    val ssidRedacted = ssid.isBlank() || ssid == "<unknown ssid>" || ssid == "0x"
    if (ssidRedacted) builder.put("ssid_redacted", true) else builder.put("ssid", ssid)
    val rawBssid = info.bssid ?: ""
    val bssidRedacted = rawBssid.isBlank() || rawBssid == "02:00:00:00:00:00"
    if (!bssidRedacted) builder.put("bssid", rawBssid)
    @Suppress("DEPRECATION")
    val legacyIp = Formatter.formatIpAddress(info.ipAddress) ?: ""
    if (legacyIp.isNotBlank() && legacyIp != "0.0.0.0") builder.put("ip", legacyIp)
    builder.put("link_speed_mbps", info.linkSpeed)
    builder.put("rssi", info.rssi)
    builder.put("frequency_mhz", info.frequency)
}

// ─────────────────────────────────────────────
//  Sensors (list only — read_sensor is separate)
// ─────────────────────────────────────────────

private val FRIENDLY_TO_TYPE: Map<String, Int> = mapOf(
    "accelerometer" to Sensor.TYPE_ACCELEROMETER,
    "gyroscope" to Sensor.TYPE_GYROSCOPE,
    "light" to Sensor.TYPE_LIGHT,
    "proximity" to Sensor.TYPE_PROXIMITY,
    "magnetic_field" to Sensor.TYPE_MAGNETIC_FIELD,
    "pressure" to Sensor.TYPE_PRESSURE,
    "temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE,
    "humidity" to Sensor.TYPE_RELATIVE_HUMIDITY,
    "step_counter" to Sensor.TYPE_STEP_COUNTER,
    "linear_acceleration" to Sensor.TYPE_LINEAR_ACCELERATION,
    "gravity" to Sensor.TYPE_GRAVITY,
    "rotation_vector" to Sensor.TYPE_ROTATION_VECTOR,
)

private val TYPE_TO_FRIENDLY: Map<Int, String> = FRIENDLY_TO_TYPE.entries.associate { it.value to it.key }

private val UNIT_BY_FRIENDLY: Map<String, String> = mapOf(
    "accelerometer" to "m/s^2", "gravity" to "m/s^2", "linear_acceleration" to "m/s^2",
    "gyroscope" to "rad/s", "magnetic_field" to "uT", "light" to "lx",
    "proximity" to "cm", "pressure" to "hPa", "temperature" to "°C", "humidity" to "%",
)

private fun buildSensorsListPayload(context: Context): JsonObject {
    val sm = context.getSystemService(SensorManager::class.java)
        ?: return buildJsonObject { put("error", "SensorManager unavailable") }
    val sensors = sm.getSensorList(Sensor.TYPE_ALL)
    return buildJsonObject {
        put("sensors", buildJsonArray {
            sensors.forEach { s ->
                addJsonObject {
                    put("name", s.name)
                    put("type", TYPE_TO_FRIENDLY[s.type] ?: s.stringType ?: "type_${s.type}")
                    put("vendor", s.vendor)
                    put("max_range", s.maximumRange)
                    put("resolution", s.resolution)
                }
            }
        })
    }
}

// ─────────────────────────────────────────────
//  Storage
// ─────────────────────────────────────────────

private fun statsFor(path: String): JsonObject {
    val stat = StatFs(path)
    val total = stat.blockSizeLong * stat.blockCountLong
    val free = stat.availableBlocksLong * stat.blockSizeLong
    return buildJsonObject {
        put("total_bytes", total)
        put("free_bytes", free)
        put("used_bytes", total - free)
    }
}

private fun buildStoragePayload(): JsonObject {
    val internal = statsFor(Environment.getDataDirectory().path)
    val external = if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
        statsFor(Environment.getExternalStorageDirectory().path)
    } else null
    return buildJsonObject {
        put("internal", internal)
        put("external", external ?: JsonNull)
    }
}

// ─────────────────────────────────────────────
//  read_sensor — kept separate (has params)
// ─────────────────────────────────────────────

fun readSensorTool(context: Context): Tool = Tool(
    name = "read_sensor",
    description = """
        Read a single value (or short averaged sample) from a named device sensor,
        e.g., accelerometer, gyroscope, light, proximity.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("type", buildJsonObject {
                    put("type", "string")
                    put("description", "Sensor type, e.g. \"accelerometer\"")
                })
                put("duration_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional sample window in ms, default 200, max 5000")
                })
            },
            required = listOf("type")
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val typeName = params["type"]?.jsonPrimitive?.contentOrNull ?: error("type is required")
        val durationMs = (params["duration_ms"]?.jsonPrimitive?.intOrNull ?: 200).coerceIn(1, 5000)
        val typeInt = FRIENDLY_TO_TYPE[typeName]
        val payload = if (typeInt == null) {
            buildJsonObject { put("error", "unknown sensor type: $typeName") }
        } else {
            val sm = context.getSystemService(SensorManager::class.java)
            val sensor = sm?.getDefaultSensor(typeInt)
            if (sm == null || sensor == null) {
                buildJsonObject { put("error", "sensor unavailable on device") }
            } else {
                val lock = Any()
                val sums = mutableListOf<Double>()
                var count = 0
                var lastTimestamp = 0L
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        synchronized(lock) {
                            if (sums.isEmpty()) repeat(event.values.size) { sums.add(0.0) }
                            for (i in event.values.indices) { if (i < sums.size) sums[i] = sums[i] + event.values[i] }
                            count++; lastTimestamp = System.currentTimeMillis()
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                try {
                    sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                    delay(durationMs.toLong())
                } finally { sm.unregisterListener(listener) }
                val (resultValues, resultCount, resultTimestamp) = synchronized(lock) { Triple(sums.toList(), count, lastTimestamp) }
                buildJsonObject {
                    put("type", typeName)
                    put("values", buildJsonArray { if (resultCount > 0) resultValues.forEach { add(it / resultCount) } })
                    UNIT_BY_FRIENDLY[typeName]?.let { put("unit", it) }
                    put("timestamp_ms", if (resultTimestamp != 0L) resultTimestamp else System.currentTimeMillis())
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
