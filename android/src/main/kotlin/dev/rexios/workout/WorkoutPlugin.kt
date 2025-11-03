package dev.rexios.workout

import android.os.SystemClock
import androidx.concurrent.futures.await
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.BatchingMode
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
import kotlin.math.abs

/** WorkoutPlugin*/
class WorkoutPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, ExerciseUpdateCallback {
    private lateinit var channel: MethodChannel
    private lateinit var lifecycleScope: CoroutineScope
    private lateinit var exerciseClient: ExerciseClient

    private var lastUpdateTime = 0L
    private var minUpdateIntervalMillis: Long = 1000L

    // Optimización adicional: tiempo mínimo entre checks
    private var lastCheckTime = 0L
    private val minCheckIntervalMillis = 100L // No procesar callbacks más frecuentes que esto

    // Cache ultra-optimizado
    private val lastSentValues = mutableMapOf<String, Double>()
    private val lastSentTimestamps = mutableMapOf<String, Long>()
    private var bootInstantMillis: Long = 0L

    // Contador de callbacks ignorados (para debugging)
    private var droppedCallbacks = 0L
    private var processedCallbacks = 0L

    // Pool de maps reutilizables para evitar GC
    private val healthDataPool = mutableMapOf<String, Map<String, Any>>()

    companion object {
        private val DATA_TYPE_MAP = mapOf(
            DataType.HEART_RATE_BPM to "heartRate",
            DataType.CALORIES_TOTAL to "calories",
            DataType.STEPS_TOTAL to "steps",
            DataType.DISTANCE_TOTAL to "distance",
            DataType.SPEED to "speed",
        )

        // Umbrales más agresivos
        private val THRESHOLDS = mapOf(
            "heartRate" to 2.0,    // ±2 BPM (era 1.0)
            "calories" to 2.0,     // ±2 kcal (era 1.0)
            "steps" to 2.0,        // ±2 pasos (era 1.0)
            "distance" to 10.0,    // ±10 metros (era 5.0)
            "speed" to 0.3,        // ±0.3 m/s (era 0.2)
        )

        // Tiempo mínimo entre actualizaciones por tipo de dato
        private val MIN_UPDATE_INTERVALS = mapOf(
            "heartRate" to 2000L,  // HR cada 2s mínimo
            "calories" to 5000L,   // Calorías cada 5s
            "steps" to 3000L,      // Pasos cada 3s
            "distance" to 5000L,   // Distancia cada 5s
            "speed" to 2000L,      // Velocidad cada 2s
        )
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "workout")
        channel.setMethodCallHandler(this)
        exerciseClient = HealthServices.getClient(flutterPluginBinding.applicationContext).exerciseClient
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getSupportedExerciseTypes" -> getSupportedExerciseTypes(result)
            "start" -> start(call.arguments as Map<String, Any>, result)
            "stop" -> {
                stop()
                result.success(null)
            }
            "getStats" -> {
                // Método de debugging
                result.success(mapOf(
                    "processed" to processedCallbacks,
                    "dropped" to droppedCallbacks,
                    "efficiency" to if (processedCallbacks + droppedCallbacks > 0) {
                        (droppedCallbacks * 100.0 / (processedCallbacks + droppedCallbacks))
                    } else 0.0
                ))
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

    private fun dataTypeToString(type: DataType<*, *>): String {
        return DATA_TYPE_MAP[type] ?: "unknown"
    }

    private fun dataTypeFromString(string: String): DataType<*, *> {
        return DATA_TYPE_MAP.entries.firstOrNull { it.value == string }?.key
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

        // Forzar mínimo de 1000ms
        minUpdateIntervalMillis = when (val interval = arguments["updateIntervalMillis"]) {
            is Int -> interval.toLong().coerceAtLeast(1000L)
            is Long -> interval.coerceAtLeast(1000L)
            else -> 2000L // Default 2 segundos para mejor batería
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

            // Configuración ultra-optimizada
            val config = ExerciseConfig.builder(exerciseType)
                .setDataTypes(requestedSupportedDataTypes)
                .setIsAutoPauseAndResumeEnabled(false)
                .setIsGpsEnabled(enableGps)
                .apply {
                    // Siempre usar batching cuando sea posible
                    when {
                        minUpdateIntervalMillis >= 5000 ->
                            setBatchingModeOverride(BatchingMode.HEART_RATE_5_SECONDS)
                        minUpdateIntervalMillis >= 3000 ->
                            setBatchingModeOverride(BatchingMode.HEART_RATE_5_SECONDS)
                    }
                }
                .build()

            exerciseClient.startExerciseAsync(config).await()

            // Reset completo
            lastUpdateTime = 0L
            lastCheckTime = 0L
            lastSentValues.clear()
            lastSentTimestamps.clear()
            healthDataPool.clear()
            bootInstantMillis = 0L
            droppedCallbacks = 0L
            processedCallbacks = 0L

            result.success(mapOf(
                "unsupportedFeatures" to requestedUnsupportedDataTypes.map { dataTypeToString(it) }
            ))
        }
    }

    override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
        val currentTime = System.currentTimeMillis()

        // FILTRO #0: Rate limiting ultra-agresivo - evitar sobrecarga de callbacks
        if (currentTime - lastCheckTime < minCheckIntervalMillis) {
            droppedCallbacks++
            return
        }
        lastCheckTime = currentTime

        // FILTRO #1: Throttling temporal global
        if (lastUpdateTime != 0L && currentTime - lastUpdateTime < minUpdateIntervalMillis) {
            droppedCallbacks++
            return
        }

        // Calcular bootInstant solo una vez (en millis para mejor performance)
        if (bootInstantMillis == 0L) {
            bootInstantMillis = currentTime - SystemClock.elapsedRealtime()
        }

        // FILTRO #2: Verificación rápida de datos vacíos
        val metrics = update.latestMetrics
        if (metrics.sampleDataPoints.isEmpty() && metrics.cumulativeDataPoints.isEmpty()) {
            droppedCallbacks++
            return
        }

        // Reutilizar map si es posible
        healthDataPool.clear()
        var hasSignificantChanges = false
        var hasAnyData = false

        // Procesar sample data points (optimizado)
        for (dataPoint in metrics.sampleDataPoints) {
            val dataType = dataTypeToString(dataPoint.dataType)
            val value = (dataPoint.value as Number).toDouble()

            // FILTRO #3: Intervalo mínimo por tipo de dato
            val lastTimestamp = lastSentTimestamps[dataType] ?: 0L
            val minInterval = MIN_UPDATE_INTERVALS[dataType] ?: minUpdateIntervalMillis

            if (currentTime - lastTimestamp < minInterval) {
                continue // Skip este tipo de dato
            }

            if (hasSignificantChange(dataType, value)) {
                val timestamp = bootInstantMillis + dataPoint.timeDurationFromBoot.toMillis()
                healthDataPool[dataType] = mapOf("value" to value, "timestamp" to timestamp)
                lastSentValues[dataType] = value
                lastSentTimestamps[dataType] = currentTime
                hasSignificantChanges = true
                hasAnyData = true
            }
        }

        // Procesar cumulative data points (optimizado)
        for (dataPoint in metrics.cumulativeDataPoints) {
            val dataType = dataTypeToString(dataPoint.dataType)
            val total = dataPoint.total.toDouble()

            // FILTRO #3: Intervalo mínimo por tipo de dato
            val lastTimestamp = lastSentTimestamps[dataType] ?: 0L
            val minInterval = MIN_UPDATE_INTERVALS[dataType] ?: minUpdateIntervalMillis

            if (currentTime - lastTimestamp < minInterval) {
                continue // Skip este tipo de dato
            }

            if (hasSignificantChange(dataType, total)) {
                val timestamp = dataPoint.end.toEpochMilli()
                healthDataPool[dataType] = mapOf("value" to total, "timestamp" to timestamp)
                lastSentValues[dataType] = total
                lastSentTimestamps[dataType] = currentTime
                hasSignificantChanges = true
                hasAnyData = true
            }
        }

        // FILTRO #4: Solo enviar si hay cambios significativos Y datos
        if (hasSignificantChanges && hasAnyData && healthDataPool.isNotEmpty()) {
            lastUpdateTime = currentTime
            processedCallbacks++

            // Crear una copia para enviar (evitar que Flutter modifique el pool)
            val dataToSend = healthDataPool.toMap()
            channel.invokeMethod("dataReceived", dataToSend)
        } else {
            droppedCallbacks++
        }
    }

    /**
     * Determina si hay un cambio significativo (ultra-optimizado)
     * Usa early returns para máxima eficiencia
     */
    private fun hasSignificantChange(dataType: String, newValue: Double): Boolean {
        val lastValue = lastSentValues[dataType]

        // Primera lectura siempre se envía
        if (lastValue == null) return true

        // Optimización: si es exactamente igual, skip
        if (newValue == lastValue) return false

        val threshold = THRESHOLDS[dataType] ?: 0.5
        return abs(newValue - lastValue) >= threshold
    }

    override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
    override fun onRegistered() {}
    override fun onRegistrationFailed(throwable: Throwable) {}
    override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}

    private fun stop() {
        exerciseClient.endExerciseAsync()
        lastSentValues.clear()
        lastSentTimestamps.clear()
        healthDataPool.clear()
        lastUpdateTime = 0L
        lastCheckTime = 0L
        bootInstantMillis = 0L

        // Log final de estadísticas
        val total = processedCallbacks + droppedCallbacks
        if (total > 0) {
            val efficiency = (droppedCallbacks * 100.0 / total)
            android.util.Log.d("WorkoutPlugin",
                "Session stats - Processed: $processedCallbacks, Dropped: $droppedCallbacks, Efficiency: ${String.format("%.1f", efficiency)}%")
        }

        droppedCallbacks = 0L
        processedCallbacks = 0L
    }
}