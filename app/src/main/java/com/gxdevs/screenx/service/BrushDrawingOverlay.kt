package com.gxdevs.screenx.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

class BrushDrawingOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null
    private var lifecycleOwner: CustomLifecycleOwner? = null

    data class DrawingPath(
        val path: Path,
        val color: Color,
        val strokeWidth: Float
    )

    private val layoutParams = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        gravity = Gravity.TOP or Gravity.START
    }

    fun show(onDismiss: () -> Unit) {
        lifecycleOwner = CustomLifecycleOwner().apply { onCreate(); onStart(); onResume() }

        composeView = ComposeView(context).apply {
            // CRITICAL: ComposeView IS the WindowManager root here — set tree owners on it directly
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)

            setContent {
                var selectedColor by remember { mutableStateOf(Color.Red) }
                var selectedWidth by remember { mutableStateOf(8f) }
                
                val paths = remember { mutableStateListOf<DrawingPath>() }
                var currentPathState by remember { mutableStateOf<Path?>(null) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                ) {
                    // Drawing Canvas
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val path = Path().apply {
                                            moveTo(offset.x, offset.y)
                                        }
                                        currentPathState = path
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        currentPathState?.lineTo(change.position.x, change.position.y)
                                        
                                        // Force redraw by resetting state
                                        val path = currentPathState
                                        currentPathState = null
                                        currentPathState = path
                                    },
                                    onDragEnd = {
                                        currentPathState?.let {
                                            paths.add(DrawingPath(it, selectedColor, selectedWidth))
                                        }
                                        currentPathState = null
                                    }
                                )
                            }
                    ) {
                        // Draw finalized paths
                        paths.forEach { drawPath ->
                            drawPath(
                                path = drawPath.path,
                                color = drawPath.color,
                                style = DrawStroke(
                                    width = drawPath.strokeWidth,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }

                        // Draw active path
                        currentPathState?.let { activePath ->
                            drawPath(
                                path = activePath,
                                color = selectedColor,
                                style = DrawStroke(
                                    width = selectedWidth,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }

                    // Bottom Toolbar — must intercept touches BEFORE canvas does
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = Color(0xEE161C1B),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 32.dp)
                            .wrapContentWidth()
                            .height(64.dp)
                            // This stops touch events from falling through to the canvas below
                            .pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent() } } },
                        tonalElevation = 12.dp
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            // Colors
                            listOf(Color.Red, Color.Blue, Color.Green, Color.Yellow).forEach { color ->
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            width = if (selectedColor == color) 2.dp else 0.dp,
                                            color = Color.White,
                                            shape = CircleShape
                                        )
                                        .clickable { selectedColor = color }
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Stroke sizes
                            listOf(6f, 12f, 20f).forEach { size ->
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(if (selectedWidth == size) Color.Gray else Color.Transparent)
                                        .clickable { selectedWidth = size }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size((size * 0.75f).dp.coerceAtMost(20.dp))
                                            .clip(CircleShape)
                                            .background(Color.White)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Undo
                            IconButton(
                                onClick = {
                                    if (paths.isNotEmpty()) {
                                        paths.removeAt(paths.size - 1)
                                    }
                                }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = Color.White)
                            }

                            // Clear
                            IconButton(
                                onClick = {
                                    paths.clear()
                                }
                            ) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear All", tint = Color.White)
                            }

                            // Close / Exit
                            IconButton(
                                onClick = {
                                    onDismiss()
                                }
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Red)
                            }
                        }
                    }
                }
            }
        }

        try {
            windowManager.addView(composeView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun dismiss() {
        lifecycleOwner?.onDestroy()
        composeView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        composeView = null
        lifecycleOwner = null
    }

    private class CustomLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        private val mViewModelStore = ViewModelStore()

        override val lifecycle: Lifecycle = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry = savedStateRegistryController.savedStateRegistry
        override val viewModelStore: ViewModelStore = mViewModelStore

        fun onCreate() {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }

        fun onStart() {
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
        }

        fun onResume() {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun onDestroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            mViewModelStore.clear()
        }
    }
}
