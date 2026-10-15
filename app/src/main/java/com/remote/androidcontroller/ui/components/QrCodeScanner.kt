package com.remote.androidcontroller.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.remote.androidcontroller.ui.theme.DarkBorder
import com.remote.androidcontroller.ui.theme.DarkSurface
import com.remote.androidcontroller.ui.theme.EmeraldPrimary
import com.remote.androidcontroller.ui.theme.TextPrimary
import com.remote.androidcontroller.ui.theme.TextSecondary
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrCodeScanner(
    onQrCodeScanned: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
            val isScanned = remember { AtomicBoolean(false) }

            DisposableEffect(Unit) {
                onDispose {
                    cameraExecutor.shutdown()
                }
            }

            var cameraAvailable by remember { mutableStateOf(true) }

            if (cameraAvailable) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                        cameraProviderFuture.addListener({
                            try {
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setTargetResolution(Size(1280, 720))
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()

                                val reader = MultiFormatReader().apply {
                                    setHints(
                                        mapOf(
                                            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                                            DecodeHintType.TRY_HARDER to true,
                                            DecodeHintType.CHARACTER_SET to "UTF-8"
                                        )
                                    )
                                }

                                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                    if (isScanned.get()) {
                                        imageProxy.close()
                                        return@setAnalyzer
                                    }

                                    try {
                                        var decodedText: String? = null

                                        // First attempt: native CameraX toBitmap() with rotation applied
                                        try {
                                            val bitmap = imageProxy.toBitmap()
                                            val bWidth = bitmap.width
                                            val bHeight = bitmap.height
                                            val pixels = IntArray(bWidth * bHeight)
                                            bitmap.getPixels(pixels, 0, bWidth, 0, 0, bWidth, bHeight)
                                            val rgbSource = RGBLuminanceSource(bWidth, bHeight, pixels)
                                            try {
                                                val binaryBitmap = BinaryBitmap(HybridBinarizer(rgbSource))
                                                decodedText = reader.decodeWithState(binaryBitmap)?.text
                                            } catch (_: Exception) {
                                                try {
                                                    val globalBitmap = BinaryBitmap(GlobalHistogramBinarizer(rgbSource))
                                                    decodedText = reader.decodeWithState(globalBitmap)?.text
                                                } catch (_: Exception) {}
                                            }
                                        } catch (_: Exception) {}

                                        // Second attempt: fallback to YUV plane
                                        if (decodedText == null) {
                                            try {
                                                val plane = imageProxy.planes[0]
                                                val buffer: ByteBuffer = plane.buffer
                                                val rowStride = plane.rowStride
                                                val data = ByteArray(buffer.remaining())
                                                buffer.get(data)
                                                val width = imageProxy.width
                                                val height = imageProxy.height
                                                val source = PlanarYUVLuminanceSource(
                                                    data, rowStride, height, 0, 0, width, height, false
                                                )
                                                val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                                                decodedText = reader.decodeWithState(binaryBitmap)?.text
                                            } catch (_: Exception) {}
                                        }

                                        if (!decodedText.isNullOrBlank() && isScanned.compareAndSet(false, true)) {
                                            previewView.post {
                                                onQrCodeScanned(decodedText)
                                            }
                                        }
                                    } catch (_: Exception) {
                                        // Frame does not contain valid barcode
                                    } finally {
                                        reader.reset()
                                        imageProxy.close()
                                    }
                                }

                                val cameraSelector = when {
                                    cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                                    cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                                    else -> null
                                }

                                if (cameraSelector != null) {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        cameraSelector,
                                        preview,
                                        imageAnalysis
                                    )
                                } else {
                                    cameraAvailable = false
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                cameraAvailable = false
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Fallback UI when device or emulator has no physical camera
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Keine Kamera verfügbar",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Im Browser-Emulator oder auf Geräten ohne Kamera kann der QR-Code nicht optisch gescannt werden. Nutze stattdessen die Sofort-Verbindung per PIN.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = { onQrCodeScanned("123456") },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                    ) {
                        Text("Mit Standard-PIN (123456) verbinden", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurface)
                    ) {
                        Text("Abbrechen & PIN manuell tippen", color = TextPrimary)
                    }
                }
            }

            // Scanning Overlay UI
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "QR-Code scannen",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Schließen", tint = Color.White)
                    }
                }

                // Center Frame Target
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .border(3.dp, EmeraldPrimary, RoundedCornerShape(20.dp))
                        .background(EmeraldPrimary.copy(alpha = 0.05f), RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "QR-Code des Zielgeräts\nin den Rahmen halten",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Bottom Instruction
                Text(
                    text = "Verbindung wird nach Erkennung automatisch aufgebaut.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 20.dp)
                )
            }
        } else {
            // Permission Request Card
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Kamera-Berechtigung erforderlich",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Zum Scannen des QR-Codes vom Zielgerät benötigt die App Zugriff auf die Kamera.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) {
                    Text("Berechtigung erteilen", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface)
                ) {
                    Text("Abbrechen", color = TextPrimary)
                }
            }
        }
    }
}
