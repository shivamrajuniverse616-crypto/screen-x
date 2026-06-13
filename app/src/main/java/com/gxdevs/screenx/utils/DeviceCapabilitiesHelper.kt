package com.gxdevs.screenx.utils

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.WindowManager

object DeviceCapabilitiesHelper {

    fun getMaxSupportedResolution(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = android.util.DisplayMetrics()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

    fun getMaxSupportedFps(context: Context): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { context.display } catch (e: Exception) { null }
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        val refreshRate = display?.refreshRate?.toInt() ?: 60
        return if (refreshRate > 60) refreshRate else 60
    }

    fun getMaxSupportedBitrate(): Int {
        var maxBitrate = 15000000 // Default fallback 15 Mbps
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)) {
                        val caps = info.getCapabilitiesForType(type)
                        val videoCaps = caps.videoCapabilities
                        if (videoCaps != null) {
                            val codecMax = videoCaps.bitrateRange.upper
                            if (codecMax > maxBitrate) {
                                maxBitrate = codecMax
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return maxBitrate
    }

    fun getResolutionOptions(context: Context): List<String> {
        val options = mutableListOf("480p", "720p", "1080p")
        val maxRes = getMaxSupportedResolution(context)
        val longSide = maxOf(maxRes.first, maxRes.second)
        if (longSide >= 2560 && !options.contains("1440p")) {
            options.add("1440p")
        }
        if (longSide >= 3840 && !options.contains("4K")) {
            options.add("4K")
        }
        if (!options.contains("Original")) {
            options.add("Original")
        }
        return options
    }

    fun getFpsOptions(context: Context): List<String> {
        val options = mutableListOf("15", "30", "45", "60")
        val maxFps = getMaxSupportedFps(context)
        if (maxFps >= 90 && !options.contains("90")) {
            options.add("90")
        }
        if (maxFps >= 120 && !options.contains("120")) {
            options.add("120")
        }
        if (maxFps >= 144 && !options.contains("144")) {
            options.add("144")
        }
        // In case max refresh rate is something else (like 90 or 120 not standard, or 144)
        if (maxFps > 60 && !options.contains(maxFps.toString())) {
            options.add(maxFps.toString())
        }
        // Sort fps options numerically
        options.sortBy { it.toIntOrNull() ?: 0 }
        return options
    }

    fun getBitrateOptions(): List<String> {
        val options = mutableListOf("2 Mbps", "4 Mbps", "8 Mbps", "12 Mbps", "15 Mbps")
        val maxBitrateBps = getMaxSupportedBitrate()
        val maxBitrateMbps = maxBitrateBps / 1000000
        if (maxBitrateMbps > 15) {
            val extraOptions = listOf(20, 25, 30, 40, 50, 60, 80, 100)
            for (opt in extraOptions) {
                if (opt <= maxBitrateMbps) {
                    options.add("$opt Mbps")
                }
            }
        }
        return options
    }
}
