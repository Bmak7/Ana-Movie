package com.faselhd.app.utils

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.core.view.GestureDetectorCompat
import kotlin.math.abs

class GestureControlManager(
    context: Context,
    private val viewWidth: Int, // ✅ Pass the view's width
    private val listener: GestureListener
) {
    interface GestureListener {
        fun onSingleTap()
        fun onDoubleTap(isLeftSide: Boolean)
        fun onLongPress()
        fun onScrollStart(type: ScrollType)
        fun onScroll(type: ScrollType, delta: Float)
        fun onScrollEnd()
    }

    enum class ScrollType { HORIZONTAL, VERTICAL_LEFT, VERTICAL_RIGHT }

    private var isScrolling = false
    private val gestureDetector: GestureDetectorCompat

    init {
        gestureDetector = GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                listener.onSingleTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val isLeftSide = e.x < viewWidth / 2f
                listener.onDoubleTap(isLeftSide)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                listener.onLongPress()
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float
            ): Boolean {
                if (e1 == null) return false

                val dx = e2.x - e1.x
                val dy = e2.y - e1.y

                if (!isScrolling) {
                    isScrolling = true
                    if (abs(dx) > abs(dy)) {
                        listener.onScrollStart(ScrollType.HORIZONTAL)
                    } else {
                        if (e1.x < viewWidth / 2f) {
                            listener.onScrollStart(ScrollType.VERTICAL_LEFT)
                        } else {
                            listener.onScrollStart(ScrollType.VERTICAL_RIGHT)
                        }
                    }
                } else {
                    if (abs(dx) > abs(dy)) {
                        listener.onScroll(ScrollType.HORIZONTAL, -distanceX)
                    } else {
                        if (e1.x < viewWidth / 2f) {
                            listener.onScroll(ScrollType.VERTICAL_LEFT, -distanceY)
                        } else {
                            listener.onScroll(ScrollType.VERTICAL_RIGHT, -distanceY)
                        }
                    }
                }
                return true
            }
        })
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            if (isScrolling) {
                listener.onScrollEnd()
                isScrolling = false
            }
        }
        return true
    }
}
