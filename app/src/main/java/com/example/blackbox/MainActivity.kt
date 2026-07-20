package com.example.blackbox

import android.Manifest
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import android.util.Rational
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private var latitude by mutableDoubleStateOf(0.0)
    private var longitude by mutableDoubleStateOf(0.0)
    private var speedKmH by mutableFloatStateOf(0f)
    private var isInPip by mutableStateOf(false)
    private var isRecording by mutableStateOf(false)

    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null

    private var srtWriter: OutputStreamWriter? = null
    private var srtSubtitleIndex = 1
    private var recordingStartTimeMs = 0L
    private var srtTimer: Timer? = null

    private var orientationEventListener: OrientationEventListener? = null

    companion object {
        private const val VIDEO_FPS = 30
    }

    private val telemetryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            latitude = intent?.getDoubleExtra("lat", 0.0) ?: 0.0
            longitude = intent?.getDoubleExtra("lon", 0.0) ?: 0.0
            speedKmH = intent?.getFloatExtra("speed", 0f) ?: 0f
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false

        if (!cameraGranted || !locationGranted) {
            Toast.makeText(this, "Autorizza i permessi per continuare.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        checkAndRequestPermissions()
        setupOrientationListener()

        ContextCompat.registerReceiver(
            this,
            telemetryReceiver,
            IntentFilter("BLACKBOX_TELEMETRY_UPDATE"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    MainScreen(
                        latitude = latitude,
                        longitude = longitude,
                        speedKmH = speedKmH,
                        isRecording = isRecording,
                        isInPip = isInPip,
                        onVideoCaptureReady = { capture -> videoCapture = capture },
                        onStartRecording = {
                            startVideoRecording()
                            val serviceIntent = Intent(this, BlackboxService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(serviceIntent)
                            } else {
                                startService(serviceIntent)
                            }
                            isRecording = true
                        },
                        onStopRecording = {
                            stopVideoRecording()
                            val serviceIntent = Intent(this, BlackboxService::class.java).apply {
                                action = BlackboxService.ACTION_STOP_SERVICE
                            }
                            startService(serviceIntent)
                            isRecording = false
                        },
                        onEnterPip = { enterPipMode() }
                    )
                }
            }
        }
    }

    // LISTENER PER AGGIORNARE LA ROTAZIONE DELLA CAMERA IN BASE AL GIROSCOPIO
    private fun setupOrientationListener() {
        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                val rotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }

                videoCapture?.targetRotation = rotation
            }
        }
    }

    override fun onResume() {
        super.onResume()
        orientationEventListener?.enable()
    }

    override fun onPause() {
        super.onPause()
        orientationEventListener?.disable()
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            requestPermissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startVideoRecording() {
        val capture = videoCapture ?: return

        checkStorageAndOverwriteOldest()

        val timeStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        val baseFileName = "BLACKBOX_$timeStamp"

        createSrtFile(baseFileName)

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseFileName.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Blackbox")
        }

        val outputOptions = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        try {
            recordingStartTimeMs = System.currentTimeMillis()
            srtSubtitleIndex = 1
            startSrtTimer()

            currentRecording = capture.output
                .prepareRecording(this, outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                    if (recordEvent is VideoRecordEvent.Finalize) {
                        stopSrtTimer()
                        closeSrtWriter()
                        if (!recordEvent.hasError()) {
                            Toast.makeText(this, "Salvato: $baseFileName.mp4", Toast.LENGTH_SHORT).show()
                        } else {
                            Log.e("Blackbox", "Errore salvataggio: ${recordEvent.error}")
                        }
                    }
                }
        } catch (e: SecurityException) {
            Log.e("Blackbox", "Errore avvio registrazione", e)
        }
    }

    private fun stopVideoRecording() {
        stopSrtTimer()
        currentRecording?.stop()
        closeSrtWriter()
    }

    private fun checkStorageAndOverwriteOldest() {
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Blackbox")
            if (!dir.exists()) dir.mkdirs()

            val stat = StatFs(dir.path)
            val bytesAvailable = stat.availableBlocksLong * stat.blockSizeLong
            val megabytesAvailable = bytesAvailable / (1024 * 1024)

            if (megabytesAvailable < 800) {
                val files = dir.listFiles()
                if (!files.isNullOrEmpty()) {
                    val oldestFile = files.minByOrNull { it.lastModified() }
                    oldestFile?.let { fileToDelete ->
                        val baseName = fileToDelete.nameWithoutExtension
                        File(dir, "$baseName.mp4").delete()
                        File(dir, "$baseName.srt").delete()
                        Log.w("Blackbox", "Memoria quasi piena, file eliminato: $baseName")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Blackbox", "Errore verifica memoria", e)
        }
    }

    private fun createSrtFile(baseName: String) {
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Blackbox")
            if (!dir.exists()) dir.mkdirs()
            val srtFile = File(dir, "$baseName.srt")

            val fos = FileOutputStream(srtFile, false)
            srtWriter = OutputStreamWriter(fos, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e("Blackbox", "Errore creazione SRT", e)
        }
    }

    private fun startSrtTimer() {
        stopSrtTimer()
        srtTimer = Timer()
        srtTimer?.schedule(object : TimerTask() {
            override fun run() {
                appendSrtEntry()
            }
        }, 0L, 1000L)
    }

    private fun stopSrtTimer() {
        srtTimer?.cancel()
        srtTimer = null
    }

    private fun appendSrtEntry() {
        val writer = srtWriter ?: return
        try {
            val elapsedMs = System.currentTimeMillis() - recordingStartTimeMs
            val startStr = formatSrtTime(elapsedMs)
            val endStr = formatSrtTime(elapsedMs + 990)

            val timecodeFrame = formatTimecodeFrame(elapsedMs)

            val gpsText = if (latitude == 0.0 && longitude == 0.0) {
                "GPS: In cerca di segnale..."
            } else {
                "LAT: %.5f LON: %.5f".format(latitude, longitude)
            }
            val teleText = "TC: $timecodeFrame | VEL: ${speedKmH.toInt()} km/h | $gpsText"

            writer.write("$srtSubtitleIndex\r\n")
            writer.write("$startStr --> $endStr\r\n")
            writer.write("$teleText\r\n\r\n")
            writer.flush()

            srtSubtitleIndex++
        } catch (e: Exception) {
            Log.e("Blackbox", "Errore scrittura frame SRT", e)
        }
    }

    private fun formatSrtTime(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = (ms / (1000 * 60 * 60))
        val millis = ms % 1000
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }

    private fun formatTimecodeFrame(ms: Long): String {
        val totalFrames = (ms * VIDEO_FPS) / 1000
        val frames = totalFrames % VIDEO_FPS
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600

        return "%02d:%02d:%02d:%02d".format(hours, minutes, seconds, frames)
    }

    private fun closeSrtWriter() {
        try {
            srtWriter?.flush()
            srtWriter?.close()
            srtWriter = null
        } catch (_: Exception) {}
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip = isInPictureInPictureMode
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVideoRecording()
        unregisterReceiver(telemetryReceiver)
    }
}

@Composable
fun MainScreen(
    latitude: Double,
    longitude: Double,
    speedKmH: Float,
    isRecording: Boolean,
    isInPip: Boolean,
    onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onEnterPip: () -> Unit
) {
    var currentTime by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            kotlinx.coroutines.delay(1000)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreviewContainer(onVideoCaptureReady = onVideoCaptureReady)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isInPip) 2.dp else 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_logo),
                        contentDescription = "Logo Blackbox",
                        modifier = Modifier
                            .size(if (isInPip) 20.dp else 28.dp)
                            .padding(end = 8.dp)
                    )
                    Text(
                        text = if (isRecording) "● REC (TC 30FPS)" else "PRONTO",
                        color = if (isRecording) Color.Red else Color.Green,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isInPip) 9.sp else 15.sp
                    )
                }

                Text(
                    text = currentTime,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (isInPip) 9.sp else 14.sp
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(8.dp)
            ) {
                Text(
                    text = "VELOCITÀ: ${speedKmH.toInt()} km/h",
                    color = Color(0xFF00FF00),
                    fontSize = if (isInPip) 11.sp else 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = if (latitude == 0.0 && longitude == 0.0) "GPS: In cerca di segnale..." else "LAT: %.5f | LON: %.5f".format(latitude, longitude),
                    color = Color.White,
                    fontSize = if (isInPip) 8.sp else 12.sp,
                    fontFamily = FontFamily.Monospace
                )

                if (!isInPip) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!isRecording) {
                            Button(
                                onClick = onStartRecording,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("AVVIA REGISTRAZIONE", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = onStopRecording,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("FERMA REGISTRAZIONE", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = onEnterPip,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF33CCFF)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("MODE PiP", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreviewContainer(onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .build()

                val videoCapture = VideoCapture.withOutput(recorder)
                onVideoCaptureReady(videoCapture)

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}