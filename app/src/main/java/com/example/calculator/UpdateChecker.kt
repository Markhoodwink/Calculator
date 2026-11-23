package com.example.calculator

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    data class ReleaseUpdateInfo(
        val tagName: String,
        val versionName: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val htmlUrl: String
    )

    fun compareVersions(remote: String, current: String): Int {
        val rClean = remote.trim().removePrefix("v").removePrefix("V").substringBefore("-")
        val cClean = current.trim().removePrefix("v").removePrefix("V").substringBefore("-")

        val rParts = rClean.split(".").map { it.toIntOrNull() ?: 0 }
        val cParts = cClean.split(".").map { it.toIntOrNull() ?: 0 }

        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val rVal = rParts.getOrElse(i) { 0 }
            val cVal = cParts.getOrElse(i) { 0 }
            if (rVal != cVal) {
                return rVal.compareTo(cVal)
            }
        }
        return 0
    }

    fun parseLatestAppRelease(jsonArrayStr: String, currentVersion: String): ReleaseUpdateInfo? {
        val jsonList = PureJsonParser.parse(jsonArrayStr) as? List<*> ?: return null
        val versionRegex = Regex("""^[vV]?\d+(\.\d+)*$""")

        for (itemObj in jsonList) {
            val item = itemObj as? Map<*, *> ?: continue
            val isDraft = item["draft"] as? Boolean ?: false
            val isPrerelease = item["prerelease"] as? Boolean ?: false
            if (isDraft || isPrerelease) continue

            val tagName = item["tag_name"] as? String ?: continue
            if (!versionRegex.matches(tagName)) continue

            val remoteVersionName = tagName.removePrefix("v").removePrefix("V")

            if (compareVersions(remoteVersionName, currentVersion) <= 0) {
                return null
            }

            val htmlUrl = item["html_url"] as? String ?: ""
            val body = item["body"] as? String ?: ""

            var downloadUrl = htmlUrl
            val assets = item["assets"] as? List<*>
            if (assets != null) {
                for (asset in assets) {
                    val assetMap = asset as? Map<*, *> ?: continue
                    val name = assetMap["name"] as? String ?: ""
                    val assetUrl = assetMap["browser_download_url"] as? String
                    if (name.endsWith(".apk", ignoreCase = true) && !assetUrl.isNullOrBlank()) {
                        downloadUrl = assetUrl
                        break
                    }
                }
            }

            val cleanNotes = body
                .replace(Regex("""#+\s*"""), "")
                .replace("**", "")
                .trim()
            val truncatedNotes = if (cleanNotes.length > 450) cleanNotes.substring(0, 450).trimEnd() + "..." else cleanNotes

            return ReleaseUpdateInfo(
                tagName = tagName,
                versionName = remoteVersionName,
                releaseNotes = truncatedNotes,
                downloadUrl = downloadUrl,
                htmlUrl = htmlUrl
            )
        }
        return null
    }

    fun checkForUpdateAsync(activity: AppCompatActivity) {
        val currentVersion = try {
            val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
            pInfo.versionName ?: "0.0"
        } catch (_: Exception) {
            "0.0"
        }

        val prefs = activity.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val skippedVersion = prefs.getString("skipped_update_version", null)

        Thread {
            try {
                val url = URL("https://api.github.com/repos/Markhoodwink/Calculator/releases?per_page=10")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "MB-CAL-Android")

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val info = parseLatestAppRelease(jsonStr, currentVersion)
                    if (info != null && info.versionName != skippedVersion) {
                        activity.runOnUiThread {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                showUpdateDialog(activity, info, currentVersion, prefs)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // Completely silent on offline state, timeout, or errors
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun showUpdateDialog(
        activity: AppCompatActivity,
        info: ReleaseUpdateInfo,
        currentVersion: String,
        prefs: SharedPreferences
    ) {
        val msgBuilder = StringBuilder()
        msgBuilder.append("Установлена версия: v$currentVersion\n")
        msgBuilder.append("Новая версия: ${info.tagName}")
        if (info.releaseNotes.isNotBlank()) {
            msgBuilder.append("\n\nЧто нового:\n${info.releaseNotes}")
        }

        AlertDialog.Builder(activity)
            .setTitle("Доступно обновление ${info.tagName}")
            .setMessage(msgBuilder.toString())
            .setPositiveButton("Скачать") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl))
                    activity.startActivity(intent)
                } catch (_: Exception) {}
            }
            .setNeutralButton("Пропустить") { _, _ ->
                prefs.edit().putString("skipped_update_version", info.versionName).apply()
            }
            .setNegativeButton("Позже", null)
            .show()
    }
}
