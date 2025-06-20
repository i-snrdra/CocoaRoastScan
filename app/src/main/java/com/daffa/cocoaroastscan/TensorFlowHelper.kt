package com.daffa.cocoaroastscan

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TensorFlowHelper(private val context: Context) {
    
    private var interpreter: Interpreter? = null
    private val modelPath = "model_a.tflite"
    private val inputSize = 224 // Ukuran input gambar, sesuaikan dengan model Anda
    private val pixelSize = 3 // RGB
    private val imageMean = 0f
    private val imageStd = 255f
    private val maxResults = 2 // Dikupas atau Tidak Dikupas
    
    // Labels untuk klasifikasi
    private val labels = arrayOf("Tidak Dikupas", "Dikupas")
    
    data class Recognition(
        val label: String,
        val confidence: Float
    )
    
    init {
        setupInterpreter()
    }
    
    private fun setupInterpreter() {
        try {
            val model = loadModelFile()
            interpreter = Interpreter(model)
            Log.d("TensorFlowHelper", "Model berhasil dimuat")
        } catch (e: Exception) {
            Log.e("TensorFlowHelper", "Error memuat model: ${e.message}")
        }
    }
    
    private fun loadModelFile(): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    
    fun classifyImage(bitmap: Bitmap): List<Recognition> {
        if (interpreter == null) {
            Log.e("TensorFlowHelper", "Interpreter belum diinisialisasi")
            return emptyList()
        }
        
        try {
            // Resize dan konversi bitmap
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            val byteBuffer = convertBitmapToByteBuffer(resizedBitmap)
            
            // Siapkan output array
            val outputArray = Array(1) { FloatArray(maxResults) }
            
            // Jalankan inference
            interpreter?.run(byteBuffer, outputArray)
            
            // Konversi hasil ke Recognition objects
            return parseOutput(outputArray[0])
            
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
                
                // Ekstrak nilai RGB dan normalisasi
                byteBuffer.putFloat(((value shr 16 and 0xFF) - imageMean) / imageStd)
                byteBuffer.putFloat(((value shr 8 and 0xFF) - imageMean) / imageStd)
                byteBuffer.putFloat(((value and 0xFF) - imageMean) / imageStd)
            }
        }
        
        return byteBuffer
    }
    
    private fun parseOutput(output: FloatArray): List<Recognition> {
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
        interpreter?.close()
        interpreter = null
    }
} 