package com.daffa.cocoaroastscan

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TensorFlowHelper(private val context: Context) {
    
    // Multiple interpreters untuk setiap model
    private var interpreterA: Interpreter? = null // Dikupas/Tidak Dikupas
    private var interpreterB: Interpreter? = null // Durasi (4,5,6,7 menit)
    private var interpreterC: Interpreter? = null // Untuk Tidak Dikupas
    private var interpreterD: Interpreter? = null // Warna (coklat muda, coklat, hitam)
    
    private val inputSize = 256 // Sesuaikan dengan Colab: (256, 256)
    private val pixelSize = 3 // RGB
    private val imageMean = 0f
    private val imageStd = 255f
    
    // Labels untuk setiap model - akan dimuat dari file
    private var labelsA: Array<String> = arrayOf()
    private var labelsB: Array<String> = arrayOf()
    private var labelsC: Array<String> = arrayOf()
    private var labelsD: Array<String> = arrayOf()
    
    data class Recognition(
        val label: String,
        val confidence: Float
    )
    
    data class ScanResult(
        val kulitResult: Recognition,
        val durationResult: Recognition, // Selalu ada, baik dari model B atau C
        val colorResult: Recognition,
        val formattedColor: String, // Warna dalam bahasa Inggris
        val roastingStatus: String, // Status roasting berdasarkan warna
        val averageConfidence: Float // Rata-rata confidence
    )
    
    init {
        setupLabels()
        setupInterpreters()
    }
    
    private fun setupLabels() {
        try {
            labelsA = loadLabelsFromFile("labels_a.txt")
            labelsB = loadLabelsFromFile("labels_b.txt")
            labelsC = loadLabelsFromFile("labels_c.txt")
            labelsD = loadLabelsFromFile("labels_d.txt")
            
            Log.d("TensorFlowHelper", "Labels dimuat: A=${labelsA.size}, B=${labelsB.size}, C=${labelsC.size}, D=${labelsD.size}")
        } catch (e: Exception) {
            Log.e("TensorFlowHelper", "Error memuat labels: ${e.message}")
            // Fallback ke hard-coded labels
            labelsA = arrayOf("dikupas", "tidak_dikupas")
            labelsB = arrayOf("4", "5", "6", "7")
            labelsC = arrayOf("4", "5", "6", "7")
            labelsD = arrayOf("cokelat", "cokelat_muda", "hitam")
        }
    }
    
    private fun loadLabelsFromFile(fileName: String): Array<String> {
        val labels = mutableListOf<String>()
        try {
            val inputStream = context.assets.open(fileName)
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { labels.add(it.trim()) }
            }
            reader.close()
        } catch (e: Exception) {
            Log.e("TensorFlowHelper", "Error membaca file label $fileName: ${e.message}")
        }
        return labels.toTypedArray()
    }
    
    private fun setupInterpreters() {
        try {
            // Load semua model
            interpreterA = Interpreter(loadModelFile("model_a.tflite"))
            interpreterB = Interpreter(loadModelFile("model_b.tflite"))
            interpreterC = Interpreter(loadModelFile("model_c.tflite"))
            interpreterD = Interpreter(loadModelFile("model_d.tflite"))
            
            Log.d("TensorFlowHelper", "Semua model berhasil dimuat")
            
            // Debug log untuk semua model
            interpreterA?.let { interp ->
                val inputTensor = interp.getInputTensor(0)
                val outputTensor = interp.getOutputTensor(0)
                Log.d("TensorFlowHelper", "Model A - Input shape: ${inputTensor.shape().contentToString()}")
                Log.d("TensorFlowHelper", "Model A - Output shape: ${outputTensor.shape().contentToString()}")
            }
        } catch (e: Exception) {
            Log.e("TensorFlowHelper", "Error memuat model: ${e.message}")
            Log.e("TensorFlowHelper", "Pastikan semua file model (model_a.tflite, model_b.tflite, model_c.tflite, model_d.tflite) ada di app/src/main/assets/")
        }
    }
    
    private fun loadModelFile(fileName: String): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(fileName)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    
    // Fungsi untuk mapping warna ke bahasa Inggris
    private fun mapColorToEnglish(originalColor: String): String {
        return when (originalColor) {
            "cokelat_muda" -> "Light Brown"
            "cokelat" -> "Brown"
            "hitam" -> "Dark Brown"
            else -> originalColor
        }
    }
    
    // Fungsi untuk interpretasi status roasting berdasarkan warna
    private fun getRoastingStatus(englishColor: String): String {
        return when (englishColor) {
            "Light Brown" -> "Belum Matang"
            "Brown" -> "Matang"
            "Dark Brown" -> "Terlalu Matang"
            else -> "Status Tidak Diketahui"
        }
    }
    
    // Fungsi utama untuk workflow klasifikasi bertingkat
    fun performFullScan(bitmap: Bitmap): ScanResult? {
        if (interpreterA == null || interpreterB == null || interpreterC == null || interpreterD == null) {
            Log.e("TensorFlowHelper", "Salah satu model belum diinisialisasi")
            return null
        }
        
        try {
            // Step 1: Klasifikasi kulit (Dikupas/Tidak Dikupas) dengan model_a
            val kulitResult = classifyWithModel(bitmap, interpreterA!!, labelsA)
            Log.d("TensorFlowHelper", "Hasil kulit: ${kulitResult.label} (${kulitResult.confidence})")
            
            // Step 2: Berdasarkan hasil kulit, gunakan model_b atau model_c untuk durasi
            val durationResult = if (kulitResult.label == "dikupas") {
                // Jika Dikupas, gunakan model_b untuk durasi
                val result = classifyWithModel(bitmap, interpreterB!!, labelsB)
                Log.d("TensorFlowHelper", "Hasil durasi (dikupas): ${result.label} (${result.confidence})")
                result
            } else {
                // Jika Tidak Dikupas, gunakan model_c untuk durasi
                val result = classifyWithModel(bitmap, interpreterC!!, labelsC)
                Log.d("TensorFlowHelper", "Hasil durasi (tidak dikupas): ${result.label} (${result.confidence})")
                result
            }
            
            // Step 3: Klasifikasi warna dengan model_d
            val colorResult = classifyWithModel(bitmap, interpreterD!!, labelsD)
            Log.d("TensorFlowHelper", "Hasil warna: ${colorResult.label} (${colorResult.confidence})")
            
            // Step 4: Mapping warna dan status roasting
            val formattedColor = mapColorToEnglish(colorResult.label)
            val roastingStatus = getRoastingStatus(formattedColor)
            
            // Step 5: Hitung rata-rata confidence
            val averageConfidence = (kulitResult.confidence + durationResult.confidence + colorResult.confidence) / 3
            
            return ScanResult(
                kulitResult = kulitResult,
                durationResult = durationResult,
                colorResult = colorResult,
                formattedColor = formattedColor,
                roastingStatus = roastingStatus,
                averageConfidence = averageConfidence
            )
            
        } catch (e: Exception) {
            Log.e("TensorFlowHelper", "Error dalam full scan: ${e.message}")
            return null
        }
    }
    
    // Fungsi helper untuk klasifikasi dengan model tertentu
    private fun classifyWithModel(bitmap: Bitmap, interpreter: Interpreter, labels: Array<String>): Recognition {
        // Resize dan konversi bitmap
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val byteBuffer = convertBitmapToByteBuffer(resizedBitmap)
        
        // Siapkan output array
        val outputArray = Array(1) { FloatArray(labels.size) }
        
        // Jalankan inference
        interpreter.run(byteBuffer, outputArray)
        
        // Debug: Log output mentah
        Log.d("TensorFlowHelper", "Raw output: ${outputArray[0].contentToString()}")
        
        // Konversi hasil ke Recognition object
        val results = parseOutput(outputArray[0], labels)
        return results.first() // Return hasil dengan confidence tertinggi
    }
    
    // Backward compatibility untuk classifyImage
    fun classifyImage(bitmap: Bitmap): List<Recognition> {
        if (interpreterA == null) {
            Log.e("TensorFlowHelper", "Interpreter A belum diinisialisasi")
            return emptyList()
        }
        
        try {
            val result = classifyWithModel(bitmap, interpreterA!!, labelsA)
            return listOf(result)
        } catch (e: Exception) {
            Log.e("TensorFlowHelper", "Error dalam klasifikasi: ${e.message}")
            return emptyList()
        }
    }
    
    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * pixelSize)
        byteBuffer.order(ByteOrder.nativeOrder())
        
        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        var pixel = 0
        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                val value = intValues[pixel++]
                
                // Ekstrak nilai RGB tanpa normalisasi dulu (seperti di Colab)
                byteBuffer.putFloat((value shr 16 and 0xFF).toFloat())
                byteBuffer.putFloat((value shr 8 and 0xFF).toFloat())
                byteBuffer.putFloat((value and 0xFF).toFloat())
            }
        }
        
        return byteBuffer
    }
    
    private fun parseOutput(output: FloatArray, labels: Array<String>): List<Recognition> {
        val recognitions = mutableListOf<Recognition>()
        
        for (i in output.indices) {
            val confidence = output[i]
            if (i < labels.size) {
                recognitions.add(Recognition(labels[i], confidence))
            }
        }
        
        // Urutkan berdasarkan confidence tertinggi
        return recognitions.sortedByDescending { it.confidence }
    }
    
    fun close() {
        interpreterA?.close()
        interpreterB?.close()
        interpreterC?.close()
        interpreterD?.close()
        interpreterA = null
        interpreterB = null
        interpreterC = null
        interpreterD = null
    }
} 