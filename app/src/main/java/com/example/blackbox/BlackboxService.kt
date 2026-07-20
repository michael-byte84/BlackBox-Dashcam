package com.example.blackbox

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.*

class BlackboxService : LifecycleService(), SensorEventListener, LocationListener {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager

    @Volatile private var currentLat = 0.0
    @Volatile private var currentLon = 0.0
    @Volatile private var currentSpeedKmH = 0f

    companion object {
        private const val NOTIFICATION_ID = 888
        private const val CHANNEL_ID = "blackbox_service_channel"
        const val ACTION_STOP_SERVICE = "com.example.blackbox.STOP_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        setupNotificationAndStartForeground()
        startHybridLocationUpdates()
        setupCrashDetector()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun setupNotificationAndStartForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Scatola Nera", NotificationManager.IMPORTANCE_LOW)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Blackbox Attiva")
            .setContentText("Tracciamento GPS in corso...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    private fun startHybridLocationUpdates() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                loc?.let { updateCoordinates(it) }
            }
        } catch (_: Exception) {}

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500)
            .setMinUpdateDistanceMeters(0f)
            .build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { updateCoordinates(it) }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
        } catch (e: Exception) {
            Log.e("Blackbox", "Errore Fused GPS", e)
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, this)
            }
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            }
        } catch (e: Exception) {
            Log.e("Blackbox", "Errore LocationManager", e)
        }
    }

    private fun updateCoordinates(location: Location) {
        currentLat = location.latitude
        currentLon = location.longitude
        currentSpeedKmH = if (location.hasSpeed()) location.speed * 3.6f else 0f

        val intent = Intent("BLACKBOX_TELEMETRY_UPDATE").apply {
            putExtra("lat", currentLat)
            putExtra("lon", currentLon)
            putExtra("speed", currentSpeedKmH)
        }
        sendBroadcast(intent)
    }

    override fun onLocationChanged(location: Location) {
        updateCoordinates(location)
    }

    private fun setupCrashDetector() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val x = it.values[0]
            val y = it.values[1]
            val z = it.values[2]
            val gForce = Math.sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH

            if (gForce > 2.5) {
                Log.w("Blackbox", "URTO RILEVATO! Forza G: $gForce")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {}
        sensorManager.unregisterListener(this)
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}