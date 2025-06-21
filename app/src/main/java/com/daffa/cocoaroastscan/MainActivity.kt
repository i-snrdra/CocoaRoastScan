package com.daffa.cocoaroastscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.daffa.cocoaroastscan.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var tensorFlowHelper: TensorFlowHelper
    
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    
    private lateinit var imagePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    
    companion object {
        private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
        private const val TAG = "CocoaRoastScan"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        // Inisialisasi
        cameraExecutor = Executors.newSingleThreadExecutor()
        tensorFlowHelper = TensorFlowHelper(this)
        
        setupActivityResultLaunchers()
        setupClickListeners()
        
        // Minta permission kamera
        requestCameraPermission()
    }
    
    private fun setupActivityResultLaunchers() {
        // Launcher untuk memilih gambar dari galeri
        imagePickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri ->
                    handleSelectedImage(uri)
                }
            }
        }
        
        // Launcher untuk permission kamera
        cameraPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "Permission kamera diperlukan untuk menggunakan fitur scan", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun setupClickListeners() {
        binding.btnCapture.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                captureImage()
            } else {
                requestCameraPermission()
            }
        }
        
        binding.btnUpload.setOnClickListener {
            openImagePicker()
        }
    }
    
    private fun requestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED -> {
                startCamera()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(this, CAMERA_PERMISSION) -> {
                Toast.makeText(this, "Permission kamera diperlukan untuk menggunakan fitur scan", Toast.LENGTH_LONG).show()
                cameraPermissionLauncher.launch(CAMERA_PERMISSION)
            }
            else -> {
                cameraPermissionLauncher.launch(CAMERA_PERMISSION)
            }
        }
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                
                // Preview use case
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
                
                // Image capture use case
                imageCapture = ImageCapture.Builder().build()
                
                // Camera selector (back camera)
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                
                // Unbind sebelum rebind
                cameraProvider?.unbindAll()
                
                // Bind use cases ke camera
                camera = cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
                
                // Tampilkan preview kamera, sembunyikan image view
                binding.previewView.visibility = View.VISIBLE
                binding.ivSelectedImage.visibility = View.GONE
                
            } catch (exc: Exception) {
                Log.e(TAG, "Camera start failed", exc)
                Toast.makeText(this, "Gagal memulai kamera", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun captureImage() {
        val imageCapture = imageCapture ?: return
        
        // Capture ke memory
        val contentValues = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "cocoa_scan_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        
        val outputFileOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()
        
        imageCapture.takePicture(
            outputFileOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    output.savedUri?.let { uri ->
                        handleCapturedImage(uri)
                    }
                }
                
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    Toast.makeText(this@MainActivity, "Gagal mengambil gambar", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
    
    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        imagePickerLauncher.launch(intent)
    }
    
    private fun handleCapturedImage(uri: Uri) {
        try {
            val bitmap = getBitmapFromUri(uri)
            bitmap?.let {
                // Sembunyikan preview kamera, tampilkan image
                binding.previewView.visibility = View.GONE
                binding.ivSelectedImage.visibility = View.VISIBLE
                binding.ivSelectedImage.setImageBitmap(it)
                
                // Lakukan klasifikasi
                classifyImage(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling captured image", e)
            Toast.makeText(this, "Gagal memproses gambar", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun handleSelectedImage(uri: Uri) {
        try {
            val bitmap = getBitmapFromUri(uri)
            bitmap?.let {
                // Sembunyikan preview kamera, tampilkan image
                binding.previewView.visibility = View.GONE
                binding.ivSelectedImage.visibility = View.VISIBLE
                binding.ivSelectedImage.setImageBitmap(it)
                
                // Lakukan klasifikasi
                classifyImage(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling selected image", e)
            Toast.makeText(this, "Gagal memproses gambar", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun getBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            // Rotate jika perlu (untuk foto dari kamera)
            rotateBitmapIfNeeded(bitmap, uri)
        } catch (e: IOException) {
            Log.e(TAG, "Error getting bitmap from URI", e)
            null
        }
    }
    
    private fun rotateBitmapIfNeeded(bitmap: Bitmap, uri: Uri): Bitmap {
        // Untuk simplisitas, return bitmap tanpa rotasi
        // Anda bisa menambahkan logic untuk membaca EXIF data jika diperlukan
        return bitmap
    }
    
    private fun classifyImage(bitmap: Bitmap) {
        // Tampilkan loading
        showLoading(true)
        
        lifecycleScope.launch {
            try {
                val scanResult = withContext(Dispatchers.Default) {
                    tensorFlowHelper.performFullScan(bitmap)
                }
                
                // Update UI dengan hasil lengkap
                if (scanResult != null) {
                    displayScanResults(scanResult)
                } else {
                    binding.tvResult.text = "Tidak dapat mengklasifikasi"
                    binding.tvConfidence.text = ""
                    binding.tvResult.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in classification", e)
                binding.tvResult.text = "Error dalam klasifikasi"
                binding.tvConfidence.text = ""
                Toast.makeText(this@MainActivity, "Gagal melakukan klasifikasi", Toast.LENGTH_SHORT).show()
            } finally {
                showLoading(false)
            }
        }
    }
    
    private fun displayScanResults(scanResult: TensorFlowHelper.ScanResult) {
        // Buat teks hasil yang lengkap dengan format yang lebih baik
        val resultText = StringBuilder()
        
        // Header
        resultText.append("🔍 HASIL SCAN BIJI KAKAO\n\n")
        
        // 1. Hasil kulit (Dikupas/Tidak Dikupas)
        val kulitConfidence = (scanResult.kulitResult.confidence * 100).toInt()
        val kulitLabel = when (scanResult.kulitResult.label) {
            "dikupas" -> "Dikupas"
            "tidak_dikupas" -> "Tidak Dikupas"
            else -> scanResult.kulitResult.label
        }
        resultText.append("🌰 Kondisi Kulit:\n")
        resultText.append("   $kulitLabel (${kulitConfidence}%)\n\n")
        
        // 2. Hasil durasi (dari model B atau C)
        val durasiConfidence = (scanResult.durationResult.confidence * 100).toInt()
        resultText.append("⏱️ Durasi Roasting:\n")
        resultText.append("   ${scanResult.durationResult.label} menit (${durasiConfidence}%)\n\n")
        
        // 3. Hasil warna dengan mapping baru
        val colorConfidence = (scanResult.colorResult.confidence * 100).toInt()
        resultText.append("🎨 Warna Biji:\n")
        resultText.append("   ${scanResult.formattedColor} (${colorConfidence}%)\n\n")
        
        // 4. Status roasting berdasarkan warna
        resultText.append("📊 Status Roasting:\n")
        resultText.append("   ${scanResult.roastingStatus}")
        
        // Update UI dengan hasil lengkap
        binding.tvResult.text = resultText.toString()
        
        // Tampilkan rata-rata confidence dan informasi tambahan
        val avgConfidence = (scanResult.averageConfidence * 100).toInt()
        binding.tvConfidence.text = "📈 Tingkat Keyakinan Rata-rata: ${avgConfidence}%\n\n✅ Scan berhasil menggunakan 4 model AI"
        
        // Ubah warna text berdasarkan status roasting
        val color = when (scanResult.roastingStatus) {
            "Matang" -> ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark)
            "Belum Matang" -> ContextCompat.getColor(this@MainActivity, android.R.color.holo_orange_dark)
            "Terlalu Matang" -> ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark)
            else -> ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray)
        }
        binding.tvResult.setTextColor(color)
        
        // Tampilkan toast dengan ringkasan
        val toastMessage = "Biji kakao $kulitLabel - ${scanResult.durationResult.label} menit - ${scanResult.formattedColor} (${scanResult.roastingStatus})"
        Toast.makeText(this@MainActivity, toastMessage, Toast.LENGTH_LONG).show()
    }
    
    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnCapture.isEnabled = !show
        binding.btnUpload.isEnabled = !show
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        tensorFlowHelper.close()
    }
}