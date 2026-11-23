package com.example.calculator

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

    private fun cleanReleaseNotes(body: String): String {
        if (body.isBlank()) return ""
        val lines = body.lines()
        val cleanedLines = mutableListOf<String>()

        val fullChangelogRegex = Regex("""(?i)^\s*\*{0,2}Full Changelog\*{0,2}:.*$""")
        val hrRegex = Regex("""^\s*---\s*$""")
        val redundantHeaderRegex = Regex("""(?i)^\s*(#+\s*)?(Что нового:?|MB-CAL\s+v[\d.]+.*|Релиз\s+v[\d.]+.*)\s*$""")

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (fullChangelogRegex.matches(trimmed) || hrRegex.matches(trimmed)) {
                continue
            }
            if (redundantHeaderRegex.matches(trimmed)) {
                continue
            }

            var line = trimmed
            line = line.replace("#", "")
            line = line.replace("**", "")
            line = line.replace("`", "")
            line = line.replace(Regex("""^\s*[-*]\s+"""), "• ")

            val cleanedLine = line.trim()
            if (cleanedLine.isNotBlank()) {
                cleanedLines.add(cleanedLine)
            }
        }

        val joined = cleanedLines.joinToString("\n")
        return if (joined.length > 900) {
            joined.substring(0, 900).trimEnd() + "..."
        } else {
            joined
        }
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

            val cleanNotes = cleanReleaseNotes(body)

            return ReleaseUpdateInfo(
                tagName = tagName,
                versionName = remoteVersionName,
                releaseNotes = cleanNotes,
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
        val isDark = ThemeManager.isDarkTheme(activity)
        val dp = activity.resources.displayMetrics.density

        val panelBg = if (isDark) ThemeManager.COLOR_DARK_PANEL else ThemeManager.COLOR_LIGHT_PANEL
        val borderCol = if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER
        val inputBg = if (isDark) ThemeManager.COLOR_DARK_INPUT else ThemeManager.COLOR_LIGHT_INPUT
        val textPrimary = if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY
        val textSecondary = if (isDark) ThemeManager.COLOR_DARK_TEXT_SECONDARY else ThemeManager.COLOR_LIGHT_TEXT_SECONDARY
        val emeraldColor = if (isDark) Color.parseColor("#10B981") else Color.parseColor("#059669")
        val secondaryBlue = if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")

        // Outer Card
        val outerCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f * dp
                setColor(panelBg)
                setStroke((1f * dp).toInt(), borderCol)
            }
        }

        // 1. Header Row
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Icon Badge: 40dp x 40dp, 12dp rounded, #2610B981 tint, bold emerald "↑" (20sp)
        val iconBadge = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply {
                marginEnd = (12 * dp).toInt()
            }
            gravity = Gravity.CENTER
            text = "↑"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(emeraldColor)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * dp
                setColor(Color.parseColor("#2610B981"))
            }
        }
        headerRow.addView(iconBadge)

        // Header Right Column
        val headerTextCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTitle = TextView(activity).apply {
            text = "Доступно обновление"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        }
        headerTextCol.addView(tvTitle)

        // Version transition pill
        val tvVersionPill = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * dp).toInt()
            }
            text = "v$currentVersion  →  ${info.tagName}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(emeraldColor)
            setPadding((8 * dp).toInt(), (3 * dp).toInt(), (8 * dp).toInt(), (3 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * dp
                setColor(Color.parseColor("#1F10B981"))
            }
        }
        headerTextCol.addView(tvVersionPill)
        headerRow.addView(headerTextCol)
        outerCard.addView(headerRow)

        // 2. Changelog Box (if releaseNotes is not blank)
        if (info.releaseNotes.isNotBlank()) {
            val tvChangelogLabel = TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (16 * dp).toInt()
                    bottomMargin = (6 * dp).toInt()
                }
                text = "Что нового:"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textSecondary)
            }
            outerCard.addView(tvChangelogLabel)

            // Recessed rounded container
            val changelogContainer = LinearLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.VERTICAL
                setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f * dp
                    setColor(inputBg)
                    setStroke((1f * dp).toInt(), borderCol)
                }
            }

            val scrollView = object : ScrollView(activity) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val maxH = (220 * dp).toInt()
                    val newHeightSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
                    super.onMeasure(widthMeasureSpec, newHeightSpec)
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                isVerticalScrollBarEnabled = true
            }

            val notesListLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val lines = info.releaseNotes.lines().filter { it.isNotBlank() }
            for ((index, line) in lines.withIndex()) {
                val tvLine = TextView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (index < lines.size - 1) {
                            bottomMargin = (4 * dp).toInt()
                        }
                    }
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    setLineSpacing(0f, 1.2f)

                    if (line.startsWith("• ") && line.contains(":")) {
                        val colonIdx = line.indexOf(':')
                        val ssb = SpannableStringBuilder(line)

                        // Dot "• "
                        ssb.setSpan(
                            ForegroundColorSpan(emeraldColor),
                            0, 2,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        // Feature title before ':'
                        ssb.setSpan(
                            StyleSpan(Typeface.BOLD),
                            2, colonIdx,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        ssb.setSpan(
                            ForegroundColorSpan(textPrimary),
                            2, colonIdx,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        // Rest after ':'
                        if (colonIdx < line.length) {
                            ssb.setSpan(
                                ForegroundColorSpan(textSecondary),
                                colonIdx, line.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                        text = ssb
                    } else if (line.startsWith("• ")) {
                        val ssb = SpannableStringBuilder(line)
                        ssb.setSpan(
                            ForegroundColorSpan(emeraldColor),
                            0, 2,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        ssb.setSpan(
                            ForegroundColorSpan(textSecondary),
                            2, line.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        text = ssb
                    } else {
                        setTextColor(textSecondary)
                        text = line
                    }
                }
                notesListLayout.addView(tvLine)
            }

            scrollView.addView(notesListLayout)
            changelogContainer.addView(scrollView)
            outerCard.addView(changelogContainer)
        }

        // Create Dialog instance
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        // 3. Primary Action Button
        val btnDownload = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (46 * dp).toInt()
            ).apply {
                topMargin = (18 * dp).toInt()
            }
            gravity = Gravity.CENTER
            text = "Скачать ${info.tagName}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * dp
                setColor(emeraldColor)
            }
            bindCrispKey(isHeavy = true) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl))
                    activity.startActivity(intent)
                } catch (_: Exception) {}
                dialog.dismiss()
            }
        }
        outerCard.addView(btnDownload)

        // 4. Secondary Actions Row
        val secondaryRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * dp).toInt()
            }
        }

        val btnSkip = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, (38 * dp).toInt(), 1f)
            gravity = Gravity.CENTER
            text = "Пропустить"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textSecondary)
            bindCrispKey {
                prefs.edit().putString("skipped_update_version", info.versionName).apply()
                dialog.dismiss()
            }
        }

        val btnLater = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, (38 * dp).toInt(), 1f)
            gravity = Gravity.CENTER
            text = "Позже"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(secondaryBlue)
            bindCrispKey {
                dialog.dismiss()
            }
        }

        secondaryRow.addView(btnSkip)
        secondaryRow.addView(btnLater)
        outerCard.addView(secondaryRow)

        dialog.setContentView(outerCard)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val targetWidth = minOf(
                (activity.resources.displayMetrics.widthPixels * 0.90).toInt(),
                (380 * dp).toInt()
            )
            window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        dialog.show()
    }
}
