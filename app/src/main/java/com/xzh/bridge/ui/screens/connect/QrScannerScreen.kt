package com.xzh.bridge.ui.screens.connect

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    onScanned: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    var error by remember { mutableStateOf<String?>(null) }
    var hasScanned by remember { mutableStateOf(false) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val barcodeScanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            barcodeScanner.close()
        }
    }

    LaunchedEffect(Unit) {
        try {
            val provider = ProcessCameraProvider.getInstance(context).await(mainExecutor)
            cameraProvider = provider

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(mainExecutor) { imageProxy ->
                if (hasScanned) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                processFrame(barcodeScanner, imageProxy) { value ->
                    if (!hasScanned) {
                        hasScanned = true
                        onScanned(value)
                    }
                }
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        } catch (ex: Exception) {
            error = ex.message ?: "相机初始化失败"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("扫码") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        ScannerBody(
            padding = padding,
            previewView = previewView,
            error = error
        )
    }
}

@Composable
private fun ScannerBody(
    padding: PaddingValues,
    previewView: PreviewView,
    error: String?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    shape = MaterialTheme.shapes.medium
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "将二维码放入取景框内",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }

        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                        shape = MaterialTheme.shapes.medium
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun processFrame(
    scanner: BarcodeScanner,
    imageProxy: ImageProxy,
    onQr: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(inputImage)
        .addOnSuccessListener { barcodes ->
            val value = barcodes.firstNotNullOfOrNull { it.rawValue }?.trim()
            if (!value.isNullOrBlank()) {
                onQr(value)
            }
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

private suspend fun <T> ListenableFuture<T>.await(executor: Executor): T {
    return suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (ex: Exception) {
                    continuation.resumeWithException(ex)
                }
            },
            executor
        )
        continuation.invokeOnCancellation { cancel(true) }
    }
}
