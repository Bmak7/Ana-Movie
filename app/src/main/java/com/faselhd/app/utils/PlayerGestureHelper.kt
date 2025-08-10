package com.faselhd.app.utils

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.view.GestureDetectorCompat
import kotlin.math.abs

class PlayerGestureHelper(
    context: Context,
    private val callbacks: PlayerGestureCallbacks
) {

    interface PlayerGestureCallbacks {
        fun onSingleTap()
        fun onDoubleTap()
        fun onLongPress()
        fun onLongPressEnd()
        fun onHorizontalScroll(distanceX: Float, totalDistance: Float)
        fun onVerticalScrollLeft(distanceY: Float, totalDistance: Float)
        fun onVerticalScrollRight(distanceY: Float, totalDistance: Float)
        fun onScrollEnd()
    }

    private val gestureDetector: GestureDetectorCompat
    private var isLongPressing = false
    private var totalHorizontalDistance = 0f
    private var totalVerticalDistance = 0f
    private var isScrolling = false

    init {
        gestureDetector = GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {
            
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                callbacks.onSingleTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                callbacks.onDoubleTap()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!isLongPressing) {
                    isLongPressing = true
                    callbacks.onLongPress()
                }
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (!isScrolling) {
                    isScrolling = true
                    totalHorizontalDistance = 0f
                    totalVerticalDistance = 0f
                }

                val absDistanceX = abs(distanceX)
                val absDistanceY = abs(distanceY)

                if (absDistanceX > absDistanceY) {
                    // Horizontal scroll - seeking
                    totalHorizontalDistance += distanceX
                    callbacks.onHorizontalScroll(distanceX, totalHorizontalDistance)
                } else {
                    // Vertical scroll - brightness/volume
                    totalVerticalDistance += distanceY
                    val screenWidth = (e2.source as? View)?.width ?: 1080
                    
                    if (e2.x < screenWidth / 2) {
                        // Left side - brightness
                        callbacks.onVerticalScrollLeft(distanceY, totalVerticalDistance)
                    } else {
                        // Right side - volume
                        callbacks.onVerticalScrollRight(distanceY, totalVerticalDistance)
                    }
                }
                return true
            }
        })
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isLongPressing) {
                    isLongPressing = false
                    callbacks.onLongPressEnd()
                }
                if (isScrolling) {
                    isScrolling = false
                    callbacks.onScrollEnd()
                }
            }
        }
        return gestureDetector.onTouchEvent(event)
    }

    fun setEnabled(enabled: Boolean) {
        gestureDetector.setIsLongpressEnabled(enabled)
    }
}

