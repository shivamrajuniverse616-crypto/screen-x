package com.gxdevs.screenx.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class CountdownOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var containerView: FrameLayout? = null

    fun show(onFinished: () -> Unit) {
        val layoutParams = WindowManager.LayoutParams().apply {
            type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }

        containerView = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#66000000")) // Semi-transparent overlay
        }

        val textView = TextView(context).apply {
            text = "3"
            setTextColor(Color.WHITE)
            textSize = 96f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val textParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        containerView?.addView(textView, textParams)

        try {
            windowManager.addView(containerView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
            onFinished() // fallback if overlay failed
            return
        }

        val handler = Handler(Looper.getMainLooper())
        var count = 3

        val runnable = object : Runnable {
            override fun run() {
                count--
                if (count > 0) {
                    textView.text = count.toString()
                    handler.postDelayed(this, 1000)
                } else {
                    onFinished()
                }
            }
        }
        handler.postDelayed(runnable, 1000)
    }

    fun dismiss() {
        containerView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        containerView = null
    }
}
