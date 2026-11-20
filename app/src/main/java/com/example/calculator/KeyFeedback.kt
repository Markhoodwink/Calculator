package com.example.calculator

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

object KeyFeedback {
    @Volatile
    private var cachedVibrator: Vibrator? = null

    @Volatile
    private var vibratorInitialized = false

    private val tickEffect: VibrationEffect? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        } else {
            null
        }
    }

    private val clickEffect: VibrationEffect? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        } else {
            null
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        if (!vibratorInitialized) {
            synchronized(this) {
                if (!vibratorInitialized) {
                    val appContext = context.applicationContext
                    cachedVibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                        vm?.defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    }
                    vibratorInitialized = true
                }
            }
        }
        return cachedVibrator
    }

    fun trigger(view: View, isHeavy: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val vibrator = getVibrator(view.context)
            if (vibrator != null && vibrator.hasVibrator()) {
                val effect = if (isHeavy) clickEffect else tickEffect
                if (effect != null) {
                    try {
                        vibrator.vibrate(effect)
                        return
                    } catch (_: Throwable) {}
                }
            }
        }

        val feedbackConstant = if (isHeavy) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CLOCK_TICK
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
        }

        val performed = view.performHapticFeedback(feedbackConstant)
        if (performed) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                val vibrator = getVibrator(view.context)
                if (vibrator != null && vibrator.hasVibrator()) {
                    val amplitude = if (vibrator.hasAmplitudeControl()) {
                        if (isHeavy) 60 else 35
                    } else {
                        VibrationEffect.DEFAULT_AMPLITUDE
                    }
                    vibrator.vibrate(VibrationEffect.createOneShot(if (isHeavy) 8L else 4L, amplitude))
                }
            } catch (_: Throwable) {}
        }
    }

    fun bindCrispKey(view: View, isHeavy: Boolean = false, onClick: (View) -> Unit) {
        view.bindCrispKey(isHeavy, onClick)
    }
}

fun View.bindCrispKey(isHeavy: Boolean = false, onClick: (View) -> Unit) {
    this.stateListAnimator = null
    this.setOnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                KeyFeedback.trigger(v, isHeavy)
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(35).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(35).start()
            }
        }
        false
    }
    this.setOnClickListener { onClick(it) }
}
