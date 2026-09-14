package com.echotrace.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/** Small in-app motion kit. App-side only - widgets (RemoteViews) never animate. */
object Anim {

    /** Staggered fade-and-rise entrance for a layout's direct children. */
    fun entrance(root: ViewGroup, staggerMs: Long = 60L) {
        for (i in 0 until root.childCount) {
            val ch = root.getChildAt(i)
            ch.alpha = 0f
            ch.translationY = 28f
            ch.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(380)
                .setStartDelay(80 + i * staggerMs)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /** Playful pop for a single view (the pairing code). */
    fun pop(v: View) {
        v.scaleX = 0.75f; v.scaleY = 0.75f; v.alpha = 0f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(520).setStartDelay(150)
            .setInterpolator(OvershootInterpolator(1.6f))
            .start()
    }

    /** Tactile press: shrink a touch, spring back on release. Keeps the view's own click handling. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressScale(v: View) {
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(110).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
            false
        }
    }

    /** Horizontal shake for rejected input. */
    fun shake(v: View) {
        ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, -16f, 16f, -11f, 11f, -6f, 6f, 0f)
            .setDuration(420).start()
    }

    /** Gentle breathing loop (1.0 -> 1.035 and back). Cancel with the returned animator. */
    fun pulse(v: View): ValueAnimator {
        return ValueAnimator.ofFloat(1f, 1.035f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val s = it.animatedValue as Float
                v.scaleX = s; v.scaleY = s
            }
            start()
        }
    }

    /** One celebratory beat: quick grow, settle back. */
    fun celebrate(v: View, done: () -> Unit = {}) {
        v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(160).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(220).withEndAction { done() }.start()
        }.start()
    }
}
