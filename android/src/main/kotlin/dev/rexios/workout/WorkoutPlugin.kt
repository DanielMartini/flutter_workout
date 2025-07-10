package dev.rexios.workout

import android.os.SystemClock
import androidx.concurrent.futures.await
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.lifecycle.FlutterLifecycleAdapter
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant

/** WorkoutPlugin */
class WorkoutPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, ExerciseUpdateCallback {
    private lateinit var channel: MethodChannel
    private lateinit var lifecycleScope: CoroutineScope

    private lateinit var exerciseClient: ExerciseClient

    private var lastUpdateTime = 0L
    private var minUpdateIntervalMillis: Long = 1000L

    // Cache para los últimos datos enviados
    private var lastSentData: Map<String, Map<String, Any>>? = null
    private var callbackCount = 0L

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "workout")
        channel.setMethodCallHandler(this)

        exerciseClient =
            HealthServices.getClient(flutterPluginBinding.applicationContext).exerciseClient
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getSupportedExerciseTypes" -> getSupportedExerciseTypes(result)
            "start" -> start(call.arguments as Map<String, Any>, result)
            "stop" -> {
                stop()
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        stop()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        val lifecycle: Lifecycle = FlutterLifecycleAdapter.getActivityLifecycle(binding)
        lifecycleScope = lifecycle.coroutineScope

        exerciseClient.setUpdateCallback(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {}
    override fun onReattachedToActivityForConfigChanges(p0: ActivityPluginBinding) {}
    override fun onDetachedFromActivity() {}

    private val dataTypeStringMap = mapOf(
        DataType.HEART_RATE_BPM to "heartRate",
        DataType.CALORIES_TOTAL to "calories",
        DataType.STEPS_TOTAL to "steps",
        DataType.DISTANCE_TOTAL to "distance",
        DataType.SPEED to "speed",
    )

    private fun dataTypeToString(type: DataType<*, *>): String {
        return dataTypeStringMap[type] ?: "unknown"
    }

    private fun dataTypeFromString(string: String): DataType<*, *> {
        return dataTypeStringMap.entries.firstOrNull { it.value == string }?.key
            ?: throw IllegalArgumentException("Unknown data type: $string")
    }

    private fun getSupportedExerciseTypes(result: Result) {
        lifecycleScope.launch {
            val capabilities = exerciseClient.getCapabilitiesAsync().await()
            result.success(capabilities.supportedExerciseTypes.map { it.id })
        }
    }

    private fun start(arguments: Map<String, Any>, result: Result) {
        val exerciseTypeId = arguments["exerciseType"] as Int
        val exerciseType = ExerciseType.fromId(exerciseTypeId)

        val typeStrings = arguments["sensors"] as List<String>
        val requestedDataTypes = typeStrings.map { dataTypeFromString(it) }

        val enableGps = arguments["enableGps"] as Boolean

        // Obtener el intervalo de actualización
        minUpdateIntervalMillis = when (val interval = arguments["updateIntervalMillis"]) {
            is Int -> interval.toLong()
            is Long -> interval
            else -> 1000L
        }

        lifecycleScope.launch {
            val capabilities = exerciseClient.getCapabilitiesAsync().await()
            if (exerciseType !in capabilities.supportedExerciseTypes) {
                result.error("ExerciseType $exerciseType not supported", null, null)
                return@launch
            }
            val exerciseCapabilities = capabilities.getExerciseTypeCapabilities(exerciseType)
            val supportedDataTypes = exerciseCapabilities.supportedDataTypes
            val requestedUnsupportedDataTypes = requestedDataTypes.minus(supportedDataTypes)
            val requestedSupportedDataTypes = requestedDataTypes.intersect(supportedDataTypes)

            val config = ExerciseConfig(
                exerciseType = exerciseType,
                dataTypes = requestedSupportedDataTypes,
                isAutoPauseAndResumeEnabled = false,
                isGpsEnabled = enableGps,
            )

            exerciseClient.startExerciseAsync(config).await()

            // Resetear contadores
            lastUpdateTime = 0L
            lastSentData = null
            callbackCount = 0L

            result.success(mapOf("unsupportedFeatures" to requestedUnsupportedDataTypes.map {
                dataTypeToString(it)
            }))
        }
    }

    override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
        callbackCount++
        val currentTime = System.currentTimeMillis()

        // FILTRO TEMPORAL ESTRICTO - aplicar límite de frecuencia
        if (lastUpdateTime != 0L && currentTime - lastUpdateTime < minUpdateIntervalMillis) {
            return
        }

        // Actualizar el timestamp aquí para aplicar throttling
        lastUpdateTime = currentTime

        val healthData = mutableMapOf<String, Any>()
        val bootInstant = Instant.ofEpochMilli(currentTime - SystemClock.elapsedRealtime())

        var hasData = false

        // Procesar sampleDataPoints
        update.latestMetrics.sampleDataPoints.forEach { dataPoint ->
            val dataType = dataTypeToString(dataPoint.dataType)
            val value = (dataPoint.value as Number).toDouble()
            val timestamp = dataPoint.getTimeInstant(bootInstant).toEpochMilli()

            healthData[dataType] = mapOf("value" to value, "timestamp" to timestamp)
            hasData = true
        }

        // Procesar cumulativeDataPoints
        update.latestMetrics.cumulativeDataPoints.forEach { dataPoint ->
            val dataType = dataTypeToString(dataPoint.dataType)
            val total = dataPoint.total.toDouble()
            val timestamp = dataPoint.end.toEpochMilli()

            healthData[dataType] = mapOf("value" to total, "timestamp" to timestamp)
            hasData = true
        }

        // Solo procesar si tenemos datos Y hay cambios relevantes
        if (hasData && shouldSendUpdate(healthData)) {
            lastSentData = healthData.mapValues { it.value as Map<String, Any> }

            // Enviar datos a Flutter
            channel.invokeMethod("dataReceived", healthData)
        }
    }

    /**
     * Verifica si los datos han cambiado significativamente
     */
    private fun shouldSendUpdate(newData: Map<String, Any>): Boolean {
        val lastData = lastSentData

        // Si es la primera vez, enviar
        if (lastData == null) return true

        // Si no hay datos nuevos, no enviar
        if (newData.isEmpty()) return false

        // Comparar cada tipo de dato
        for ((dataType, newValue) in newData) {
            val newValueMap = newValue as Map<String, Any>
            val lastValueMap = lastData[dataType] as? Map<String, Any>

            if (lastValueMap == null) {
                // Nuevo tipo de dato, enviar
                return true
            }

            val newVal = newValueMap["value"] as Double
            val lastVal = lastValueMap["value"] as Double

            // Verificar si hay cambio significativo
            if (hasSignificantChange(dataType, newVal, lastVal)) {
                return true
            }
        }

        return false
    }

    /**
     * Determina si hay un cambio significativo
     */
    private fun hasSignificantChange(dataType: String, newValue: Double, oldValue: Double): Boolean {
        val threshold = when (dataType) {
            "heartRate" -> 1.0    // ±1 BPM
            "calories" -> 0.1     // ±0.1 kcal
            "steps" -> 1.0        // ±1 paso
            "distance" -> 1.0     // ±1 metro
            "speed" -> 0.1        // ±0.1 m/s
            else -> 0.1
        }

        return kotlin.math.abs(newValue - oldValue) >= threshold
    }

    override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
    override fun onRegistered() {}
    override fun onRegistrationFailed(throwable: Throwable) {}
    override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}

    private fun stop() {
        exerciseClient.endExerciseAsync()
        lastSentData = null
        lastUpdateTime = 0L
        callbackCount = 0L
    }
}
