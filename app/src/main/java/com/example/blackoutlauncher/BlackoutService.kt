package com.example.blackoutlauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.max
import kotlin.math.min

class BlackoutService : Service() {
    private var bubbleView: View? = null
    private var blackoutView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleDragging = false
    private var bubbleDownX = 0
    private var bubbleDownY = 0
    private var bubbleTouchX = 0f
    private var bubbleTouchY = 0f
    private var blackoutTapCount = 0
    private var blackoutLastTapTime = 0L
    private lateinit var windowManager: WindowManager
    private var previousBrightness: Int? = null
    private var previousBrightnessMode: Int? = null

    private val overlayTapDetector by lazy {
        GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    handleBlackoutTap()
                    return true
                }
            },
        )
    }

    private val bubbleGestureDetector by lazy {
        GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (!bubbleDragging) {
                        showBlackout()
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (!bubbleDragging) {
                        triggerHaptic()
                        stopSelf()
                    }
                }
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        restoreBrightnessIfNeeded()
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubbleView == null) {
            showBubble()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeBlackout()
        removeBubble()
        restoreBrightness()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showBubble() {
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)
        view.isHapticFeedbackEnabled = true
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            bubbleGestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val params = bubbleParams
                    if (params != null) {
                        bubbleDragging = false
                        bubbleDownX = params.x
                        bubbleDownY = params.y
                        bubbleTouchX = event.rawX
                        bubbleTouchY = event.rawY
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val params = bubbleParams
                    if (params != null) {
                        val dx = (event.rawX - bubbleTouchX).toInt()
                        val dy = (event.rawY - bubbleTouchY).toInt()
                        if (!bubbleDragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                            bubbleDragging = true
                        }
                        if (bubbleDragging) {
                            val display = resources.displayMetrics
                            val viewWidth = if (view.width > 0) view.width else dpToPx(BUBBLE_SIZE_DP)
                            val viewHeight = if (view.height > 0) view.height else dpToPx(BUBBLE_SIZE_DP)
                            val maxX = max(0, display.widthPixels - viewWidth)
                            val maxY = max(0, display.heightPixels - viewHeight)
                            params.x = min(max(bubbleDownX + dx, 0), maxX)
                            params.y = min(max(bubbleDownY + dy, 0), maxY)
                            windowManager.updateViewLayout(view, params)
                        }
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    bubbleDragging = false
                }
            }
            true
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val display = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = max(0, display.widthPixels - dpToPx(BUBBLE_SIZE_DP + BUBBLE_MARGIN_DP))
            y = dpToPx(120)
        }

        windowManager.addView(view, params)
        bubbleView = view
        bubbleParams = params
    }

    private fun showBlackout() {
        if (blackoutView != null) {
            return
        }
        removeBubble()
        applyMinimumBrightness()
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_blackout, null)
        view.keepScreenOn = true
        view.setOnTouchListener { _, event ->
            overlayTapDetector.onTouchEvent(event)
            true
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            screenBrightness = 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        windowManager.addView(view, params)
        blackoutView = view
        resetBlackoutTapState()
        view.post { applyImmersiveMode(view) }
    }

    private fun applyImmersiveMode(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.windowInsetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            view.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    private fun applyMinimumBrightness() {
        if (!Settings.System.canWrite(this)) {
            return
        }
        val resolver = contentResolver
        if (previousBrightness == null) {
            previousBrightness = runCatching {
                Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
            }.getOrNull()
        }
        if (previousBrightnessMode == null) {
            previousBrightnessMode = runCatching {
                Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
            }.getOrNull()
        }
        storeBrightnessState(previousBrightness, previousBrightnessMode)
        if (previousBrightnessMode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
        }
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 0)
    }

    private fun restoreBrightness() {
        if (!Settings.System.canWrite(this)) {
            return
        }
        val resolver = contentResolver
        val stored = loadBrightnessState()
        val targetBrightness = previousBrightness ?: stored.brightness
        val targetMode = previousBrightnessMode ?: stored.mode
        if (targetBrightness != null && targetBrightness >= 0) {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, targetBrightness)
        }
        if (targetMode != null && targetMode >= 0) {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, targetMode)
        }
        previousBrightness = null
        previousBrightnessMode = null
        clearBrightnessState()
    }

    private fun exitBlackout() {
        removeBlackout()
        restoreBrightness()
        if (bubbleView == null) {
            showBubble()
        }
    }

    private fun removeBlackout() {
        val view = blackoutView ?: return
        runCatching { windowManager.removeViewImmediate(view) }
        blackoutView = null
    }

    private fun removeBubble() {
        val view = bubbleView ?: return
        runCatching { windowManager.removeViewImmediate(view) }
        bubbleView = null
        bubbleParams = null
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, exitTapThreshold()))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun restoreBrightnessIfNeeded() {
        if (!Settings.System.canWrite(this)) {
            return
        }
        if (preferences().getBoolean(KEY_BRIGHTNESS_ACTIVE, false)) {
            restoreBrightness()
        }
    }

    private fun triggerHaptic() {
        bubbleView?.performHapticFeedback(
            HapticFeedbackConstants.LONG_PRESS,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
        )
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrator.hasVibrator()) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(60)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun handleBlackoutTap() {
        val now = System.currentTimeMillis()
        if (now - blackoutLastTapTime > MULTI_TAP_TIMEOUT_MS) {
            blackoutTapCount = 0
        }
        blackoutTapCount += 1
        blackoutLastTapTime = now
        if (blackoutTapCount >= exitTapThreshold()) {
            blackoutTapCount = 0
            exitBlackout()
        }
    }

    private fun resetBlackoutTapState() {
        blackoutTapCount = 0
        blackoutLastTapTime = 0L
    }

    private fun exitTapThreshold(): Int {
        val prefs = getSharedPreferences(PREFS_SETTINGS_NAME, MODE_PRIVATE)
        val value = prefs.getInt(KEY_EXIT_TAP_COUNT, DEFAULT_EXIT_TAP_COUNT)
        return value.coerceIn(MIN_EXIT_TAP_COUNT, MAX_EXIT_TAP_COUNT)
    }

    private fun preferences() =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun storeBrightnessState(brightness: Int?, mode: Int?) {
        val editor = preferences().edit()
        if (brightness != null) {
            editor.putInt(KEY_BRIGHTNESS, brightness)
        }
        if (mode != null) {
            editor.putInt(KEY_BRIGHTNESS_MODE, mode)
        }
        editor.putBoolean(KEY_BRIGHTNESS_ACTIVE, true)
        editor.apply()
    }

    private fun loadBrightnessState(): BrightnessState {
        val prefs = preferences()
        val brightness = if (prefs.contains(KEY_BRIGHTNESS)) {
            prefs.getInt(KEY_BRIGHTNESS, -1)
        } else {
            null
        }
        val mode = if (prefs.contains(KEY_BRIGHTNESS_MODE)) {
            prefs.getInt(KEY_BRIGHTNESS_MODE, -1)
        } else {
            null
        }
        return BrightnessState(brightness, mode)
    }

    private fun clearBrightnessState() {
        preferences().edit()
            .remove(KEY_BRIGHTNESS)
            .remove(KEY_BRIGHTNESS_MODE)
            .putBoolean(KEY_BRIGHTNESS_ACTIVE, false)
            .apply()
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        private const val NOTIFICATION_CHANNEL_ID = "blackout_service"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "blackout_service_prefs"
        private const val KEY_BRIGHTNESS = "brightness"
        private const val KEY_BRIGHTNESS_MODE = "brightness_mode"
        private const val KEY_BRIGHTNESS_ACTIVE = "brightness_active"
        private const val PREFS_SETTINGS_NAME = "blackout_launcher_prefs"
        private const val KEY_EXIT_TAP_COUNT = "exit_tap_count"
        private const val BUBBLE_SIZE_DP = 56
        private const val BUBBLE_MARGIN_DP = 16
        private const val DEFAULT_EXIT_TAP_COUNT = 2
        private const val MIN_EXIT_TAP_COUNT = 1
        private const val MAX_EXIT_TAP_COUNT = 6
        private const val MULTI_TAP_TIMEOUT_MS = 650L
    }
}

private data class BrightnessState(
    val brightness: Int?,
    val mode: Int?,
)
