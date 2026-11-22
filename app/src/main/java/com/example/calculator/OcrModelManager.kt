package com.example.calculator

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val percent: Int,
    val currentFileLabel: String
)

class OcrModelManager(private val context: Context) {

    companion object {
        const val MODEL_DIR_NAME = "ocr_models/pix2text_mfr_1_5"

        val MODEL_FILES = listOf(
            "encoder_model.onnx",
            "decoder_model.onnx",
            "tokenizer.json",
            "config.json",
            "preprocessor_config.json"
        )

        fun getPrimaryUrl(fileName: String): String {
            return "https://github.com/Markhoodwink/Calculator/releases/download/ocr-models-v1/" + fileName
        }

        fun getFallbackUrl(fileName: String): String {
            return "https://huggingface.co/breezedeus/pix2text-mfr-1.5/resolve/main/" + fileName + "?download=true"
        }

        fun getFileLabelRu(fileName: String): String {
            return when (fileName) {
                "encoder_model.onnx" -> "Загрузка энкодера..."
                "decoder_model.onnx" -> "Загрузка декодера..."
                "tokenizer.json" -> "Загрузка словаря..."
                "config.json" -> "Загрузка конфигурации..."
                "preprocessor_config.json" -> "Загрузка препроцессора..."
                else -> "Загрузка файла " + fileName + "..."
            }
        }

        fun getEstimatedFileSize(fileName: String): Long {
            return when (fileName) {
                "encoder_model.onnx" -> 88_000_000L
                "decoder_model.onnx" -> 32_000_000L
                "tokenizer.json" -> 1_500_000L
                "config.json" -> 5_000L
                "preprocessor_config.json" -> 5_000L
                else -> 100_000L
            }
        }
    }

    private val isCancelled = AtomicBoolean(false)

    fun getModelDir(): File {
        return File(context.filesDir, MODEL_DIR_NAME)
    }

    fun isModelInstalled(): Boolean {
        val dir = getModelDir()
        if (!dir.exists()) return false
        val coreFiles = listOf("encoder_model.onnx", "decoder_model.onnx", "tokenizer.json")
        for (fileName in coreFiles) {
            val file = File(dir, fileName)
            if (!file.exists()) return false
            val len = file.length()
            if (fileName.endsWith(".onnx") && len <= 1_000_000L) return false
            if (fileName.endsWith(".json") && len <= 10L) return false
        }
        return true
    }

    fun getInstalledSizeMb(): Double {
        val dir = getModelDir()
        if (!dir.exists()) return 0.0
        var totalBytes = 0L
        for (fileName in MODEL_FILES) {
            val file = File(dir, fileName)
            if (file.exists()) {
                totalBytes += file.length()
            }
        }
        val mb = totalBytes.toDouble() / (1024.0 * 1024.0)
        return (mb * 100.0).roundToInt() / 100.0
    }

    fun deleteModel(): Boolean {
        val dir = getModelDir()
        if (!dir.exists()) return true
        var success = true
        val files = dir.listFiles()
        if (files != null) {
            for (f in files) {
                if (!f.delete()) {
                    success = false
                }
            }
        }
        return success
    }

    fun cancelDownload() {
        isCancelled.set(true)
        cleanupPartFiles()
    }

    private fun cleanupPartFiles() {
        val dir = getModelDir()
        if (!dir.exists()) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.name.endsWith(".part")) {
                f.delete()
            }
        }
    }

    fun downloadModelBlocking(onProgress: (DownloadProgress) -> Unit): Result<File> {
        isCancelled.set(false)
        val dir = getModelDir()
        if (!dir.mkdirs() && !dir.exists()) {
            return Result.failure(IOException("Не удалось создать директорию моделей: " + dir.absolutePath))
        }

        var estimatedTotalBytes = 0L
        for (fileName in MODEL_FILES) {
            val targetFile = File(dir, fileName)
            if (targetFile.exists() && isValidFile(targetFile, fileName)) {
                estimatedTotalBytes += targetFile.length()
            } else {
                estimatedTotalBytes += getEstimatedFileSize(fileName)
            }
        }

        var cumulativeBytesDownloaded = 0L
        for (fileName in MODEL_FILES) {
            val isOptional = fileName in listOf("config.json", "preprocessor_config.json")
            val targetFile = File(dir, fileName)
            if (targetFile.exists() && isValidFile(targetFile, fileName)) {
                cumulativeBytesDownloaded += targetFile.length()
                val pct = ((cumulativeBytesDownloaded * 100) / estimatedTotalBytes).toInt().coerceIn(0, 100)
                onProgress(
                    DownloadProgress(
                        bytesDownloaded = cumulativeBytesDownloaded,
                        totalBytes = estimatedTotalBytes,
                        percent = pct,
                        currentFileLabel = getFileLabelRu(fileName)
                    )
                )
                continue
            }

            if (isCancelled.get()) {
                cleanupPartFiles()
                return Result.failure(CancellationException("Загрузка отменена"))
            }

            val label = getFileLabelRu(fileName)
            val partFile = File(dir, fileName + ".part")
            if (partFile.exists()) {
                partFile.delete()
            }

            try {
                downloadSingleFileWithFallback(
                    fileName = fileName,
                    destPartFile = partFile,
                    onBytesRead = { bytesJustRead ->
                        if (isCancelled.get()) {
                            throw CancellationException("Загрузка отменена")
                        }
                        cumulativeBytesDownloaded += bytesJustRead
                        val pct = ((cumulativeBytesDownloaded * 100) / estimatedTotalBytes).toInt().coerceIn(0, 100)
                        onProgress(
                            DownloadProgress(
                                bytesDownloaded = cumulativeBytesDownloaded,
                                totalBytes = estimatedTotalBytes,
                                percent = pct,
                                currentFileLabel = label
                            )
                        )
                    }
                )

                if (!isValidFile(partFile, fileName)) {
                    partFile.delete()
                    if (!isOptional) {
                        throw IOException("Файл " + fileName + " некорректен или поврежден")
                    }
                } else {
                    if (!partFile.renameTo(targetFile)) {
                        partFile.copyTo(targetFile, overwrite = true)
                        partFile.delete()
                    }
                }
            } catch (e: Exception) {
                if (partFile.exists()) {
                    partFile.delete()
                }
                if (e is CancellationException || isCancelled.get()) {
                    cleanupPartFiles()
                    return Result.failure(CancellationException("Загрузка отменена"))
                }
                if (!isOptional) {
                    cleanupPartFiles()
                    return Result.failure(e)
                }
                // Optional JSONs missing on server are non-fatal
            }
        }

        return Result.success(dir)
    }

    private fun isValidFile(file: File, fileName: String): Boolean {
        val len = file.length()
        if (fileName.endsWith(".onnx")) return len > 1_000_000L
        if (fileName.endsWith(".json")) return len > 10L
        return len > 0L
    }

    private fun downloadSingleFileWithFallback(
        fileName: String,
        destPartFile: File,
        onBytesRead: (Int) -> Unit
    ): Long {
        val primaryUrl = getPrimaryUrl(fileName)
        val fallbackUrl = getFallbackUrl(fileName)

        return try {
            downloadFromUrl(primaryUrl, destPartFile, onBytesRead)
        } catch (e: Exception) {
            if (isCancelled.get()) throw e
            if (destPartFile.exists()) destPartFile.delete()
            downloadFromUrl(fallbackUrl, destPartFile, onBytesRead)
        }
    }

    private fun downloadFromUrl(
        urlString: String,
        destPartFile: File,
        onBytesRead: (Int) -> Unit
    ): Long {
        val conn = openConnectionFollowingRedirects(urlString)
        var bytesWritten = 0L
        try {
            conn.inputStream.use { input ->
                FileOutputStream(destPartFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (isCancelled.get()) {
                            throw CancellationException("Загрузка отменена")
                        }
                        output.write(buffer, 0, read)
                        bytesWritten += read
                        onBytesRead(read)
                    }
                    output.flush()
                }
            }
        } finally {
            conn.disconnect()
        }
        return bytesWritten
    }

    private fun openConnectionFollowingRedirects(urlString: String): HttpURLConnection {
        var currentUrl = urlString
        var hops = 0
        val maxHops = 8

        while (hops < maxHops) {
            val url = URL(currentUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "MB-CAL-Android/1.1")
            conn.instanceFollowRedirects = false

            val status = conn.responseCode
            if (status in listOf(
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308
                )
            ) {
                val location = conn.getHeaderField("Location")
                    ?: throw IOException("HTTP $status перенаправление без заголовка Location")
                conn.disconnect()
                currentUrl = URL(URL(currentUrl), location).toString()
                hops++
            } else if (status == HttpURLConnection.HTTP_OK) {
                return conn
            } else {
                conn.disconnect()
                throw IOException("HTTP ошибка $status при загрузке: $currentUrl")
            }
        }
        throw IOException("Превышено максимальное количество перенаправлений ($maxHops)")
    }
}
