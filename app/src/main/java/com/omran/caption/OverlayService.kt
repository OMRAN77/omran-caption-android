package com.omran.caption

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Draws a small movable, resizable overlay window (translation text only, no buttons)
 * that stays on top of any other app, similar to the desktop overlay.
 */
object OverlayService {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var textView: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    fun show(context: Context) {
        if (overlayView != null) return
        val ctx = context.applicationContext
        windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val inflater = LayoutInflater.from(ctx)
        val view = inflater.inflate(R.layout.overlay_caption, null)
        textView = view.findViewById(R.id.overlayText)
        textView?.text = ctx.getString(R.string.overlay_placeholder)
        val resizeHandle = view.findViewById<View>(R.id.resizeHandle)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val density = ctx.resources.displayMetrics.density
        val defaultWidth = (300 * density).toInt()
        val defaultHeight = (110 * density).toInt()
        val minWidth = (160 * density).toInt()
        val minHeight = (70 * density).toInt()

        val lp = WindowManager.LayoutParams(
            defaultWidth,
            defaultHeight,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 200
        params = lp

        // Drag to move (on the text area)
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        textView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = initialX + (event.rawX - touchX).toInt()
                    lp.y = initialY + (event.rawY - touchY).toInt()
                    windowManager?.updateViewLayout(view, lp)
                    true
                }
                else -> false
            }
        }

        // Drag the bottom-right handle to resize
        var initialWidth = 0
        var initialHeight = 0
        var resizeTouchX = 0f
        var resizeTouchY = 0f
        resizeHandle?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialWidth = lp.width
                    initialHeight = lp.height
                    resizeTouchX = event.rawX
                    resizeTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newWidth = (initialWidth + (event.rawX - resizeTouchX)).toInt()
                    val newHeight = (initialHeight + (event.rawY - resizeTouchY)).toInt()
                    lp.width = newWidth.coerceAtLeast(minWidth)
                    lp.height = newHeight.coerceAtLeast(minHeight)
                    windowManager?.updateViewLayout(view, lp)
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(view, lp)
        overlayView = view
    }

    fun updateText(context: Context, text: String) {
        textView?.post { textView?.text = text }
    }

    fun hide(context: Context) {
        val view = overlayView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
        }
        overlayView = null
        textView = null
    }
}
