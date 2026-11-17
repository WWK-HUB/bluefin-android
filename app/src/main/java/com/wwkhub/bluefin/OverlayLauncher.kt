package com.wwkhub.bluefin

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Shows, hides, and guards the system overlay.
 *
 * Denying the permission, or choosing 「先在应用内用」, leaves the in-app
 * preview in place and does not ask again until the user retries.
 */
internal object OverlayLauncher {
    const val ACTION_OVERLAY_GUIDE = "com.wwkhub.bluefin.action.OVERLAY_GUIDE"

    private const val TAG = "BluefinOverlay"

    fun interface Listener {
        fun onOverlayVisibilityChanged(showing: Boolean)
    }

    private var session = OverlayPermissionSession()
    private var sessionLoaded = false
    private val listeners = LinkedHashSet<Listener>()
    private val handler = Handler(Looper.getMainLooper())
    // Application/window context, released in hide(). Not an Activity.
    @SuppressLint("StaticFieldLeak")
    private var window: OverlayWindow? = null
    private var guideShowing = false
    private var appContext: Context? = null
    private var watching = false
    private var reattachAttempts = 0
    private var reattachGeneration = 0

    private val opListener = AppOpsManager.OnOpChangedListener { _, _ ->
        handler.post {
            val context = appContext ?: return@post
            if (window != null && !Settings.canDrawOverlays(context)) {
                onPermissionRevoked(context)
            }
        }
    }

    fun isShowing(): Boolean = window?.isAttached() == true

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun toggle(activity: Activity) {
        when (OverlayLayout.decideToggle(isShowing(), Settings.canDrawOverlays(activity))) {
            OverlayLayout.Toggle.Hide -> hide(activity, returnToApp = false, toast = true)
            OverlayLayout.Toggle.Show -> show(activity, toast = true)
            OverlayLayout.Toggle.NeedPermission -> showGuide(activity)
        }
    }

    fun onNotificationToggle(context: Context) {
        Log.i(TAG, "notification toggle showing=${isShowing()}")
        when (OverlayLayout.decideToggle(isShowing(), Settings.canDrawOverlays(context))) {
            OverlayLayout.Toggle.Hide -> hide(context, returnToApp = false, toast = false, updateNotification = false)
            OverlayLayout.Toggle.Show -> show(context, toast = true, updateNotification = false)
            OverlayLayout.Toggle.NeedPermission -> {
                if (session(context).shouldOfferGuide()) openGuideFromBackground(context)
                else bringAppForward(context)
            }
        }
    }

    fun onResume(activity: Activity) {
        appContext = activity.applicationContext
        val revokedNow = window != null && !Settings.canDrawOverlays(activity)
        if (revokedNow) onPermissionRevoked(activity)
        when (session(activity).onResume(Settings.canDrawOverlays(activity))) {
            OverlayPermissionSession.Result.Granted -> {
                persistSession(activity)
                show(activity, toast = true)
            }
            OverlayPermissionSession.Result.Denied -> {
                persistSession(activity)
                BluefinForegroundService.refresh(activity)
                if (!revokedNow) {
                    Toast.makeText(activity, R.string.overlay_permission_denied, Toast.LENGTH_LONG).show()
                }
                dispatch()
            }
            OverlayPermissionSession.Result.Idle -> Unit
        }
    }

    fun isPromptDismissed(context: Context): Boolean = session(context).promptDismissed

    fun resetPrompt(activity: Activity) {
        showGuide(activity, force = true)
    }

    fun restoreIfEnabled(context: Context) {
        if (isShowing()) return
        val app = context.applicationContext
        appContext = app
        val wanted = prefs(app).getBoolean(BluefinStore.KEY_OVERLAY, false)
        if (!wanted || !Settings.canDrawOverlays(app)) return
        show(app, toast = false, updateNotification = false)
    }

    fun show(context: Context, toast: Boolean = true, updateNotification: Boolean = true): Boolean {
        cancelReattach()
        return showWindow(context, toast, updateNotification)
    }

    private fun showWindow(context: Context, toast: Boolean, updateNotification: Boolean): Boolean {
        if (isShowing()) return true
        window?.close()
        window = null
        val app = context.applicationContext
        appContext = app
        if (!Settings.canDrawOverlays(app)) return false
        var created: OverlayWindow? = null
        return try {
            val candidate = OverlayWindow(
                app,
                onHideRequested = { hide(app, returnToApp = false, toast = false) },
                onReturnToAppRequested = { returnToApp(app) },
                onUnexpectedDetach = { onWindowDetached(app) },
            )
            created = candidate
            candidate.show()
            window = candidate
            Log.i(TAG, "overlay shown")
            session(app).onResetPrompt()
            prefs(app).edit()
                .putBoolean(BluefinStore.KEY_OVERLAY, true)
                .putBoolean(BluefinStore.KEY_OVERLAY_AWAITING, false)
                .putBoolean(BluefinStore.KEY_OVERLAY_PROMPT_DISMISSED, false)
                .apply()
            startWatching(app)
            dispatch()
            if (updateNotification) BluefinForegroundService.refresh(app)
            if (toast) {
                Toast.makeText(app, R.string.overlay_open_hint, Toast.LENGTH_SHORT).show()
            }
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "cannot show overlay", error)
            window = null
            created?.close()
            stopWatching()
            if (toast) {
                Toast.makeText(app, R.string.overlay_open_failed, Toast.LENGTH_LONG).show()
            }
            false
        }
    }

    fun hide(
        context: Context,
        returnToApp: Boolean = false,
        toast: Boolean = true,
        updateNotification: Boolean = true,
    ) {
        val app = context.applicationContext
        appContext = app
        cancelReattach()
        val current = window
        window = null
        stopWatching()
        current?.close()
        Log.i(TAG, "overlay hidden hadWindow=${current != null}")
        prefs(app).edit().putBoolean(BluefinStore.KEY_OVERLAY, false).apply()
        dispatch()
        if (updateNotification) BluefinForegroundService.refresh(app)
        if (toast && current != null) {
            Toast.makeText(app, R.string.overlay_closed, Toast.LENGTH_SHORT).show()
        }
        if (returnToApp) bringAppForward(app)
    }

    fun showGuide(activity: Activity, force: Boolean = false) {
        if (activity.isFinishing || activity.isDestroyed) return
        val current = session(activity)
        if (force) {
            current.onResetPrompt()
            persistSession(activity)
        } else if (!current.shouldOfferGuide()) {
            return
        }
        if (Settings.canDrawOverlays(activity)) {
            show(activity, toast = true)
            return
        }
        if (guideShowing) return
        guideShowing = true
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.overlay_permission_title)
            .setMessage(R.string.overlay_permission_message)
            .setNegativeButton(R.string.overlay_permission_later) { _, _ ->
                declinePrompt(activity)
            }
            .setPositiveButton(R.string.overlay_permission_go) { _, _ ->
                openSettings(activity)
            }
            .setOnCancelListener { declinePrompt(activity) }
            .setOnDismissListener { guideShowing = false }
            .show()
    }

    fun setSettingsVisible(visible: Boolean) {
        if (OverlayAppVisibility.settingsVisible == visible) return
        OverlayAppVisibility.settingsVisible = visible
        window?.onSettingsVisibilityChanged()
    }

    fun sync(context: Context) {
        window?.syncFromPrefs()
    }

    private fun onWindowDetached(context: Context) {
        val current = window ?: return
        val app = context.applicationContext
        appContext = app
        if (!Settings.canDrawOverlays(app)) {
            onPermissionRevoked(app)
            return
        }
        // A full-screen overlay can be detached while the shade opens.
        // That is not the user closing it, and it must not flip the
        // notification to 显示.
        Log.i(TAG, "overlay detached while permission remains, reattaching")
        window = null
        current.close()
        if (OverlayReattachPolicy.onDetach(reattachAttempts) == OverlayReattachPolicy.Detach.Exhausted) {
            Log.w(TAG, "overlay left down after repeated system detaches")
            disableAfterDetach(app)
            return
        }
        scheduleReattach(app, reattachAttempts)
    }

    private fun scheduleReattach(app: Context, attempt: Int) {
        reattachAttempts = attempt + 1
        val generation = ++reattachGeneration
        handler.postDelayed(
            { retryReattach(app, generation, attempt) },
            OverlayReattachPolicy.delayForAttempt(attempt),
        )
    }

    private fun retryReattach(app: Context, generation: Int, attempt: Int) {
        if (generation != reattachGeneration) return
        val enabled = prefs(app).getBoolean(BluefinStore.KEY_OVERLAY, false)
        when (
            OverlayReattachPolicy.onRetry(
                enabled = enabled,
                permissionGranted = Settings.canDrawOverlays(app),
                showing = isShowing(),
            )
        ) {
            OverlayReattachPolicy.Retry.PermissionLost -> {
                Log.w(TAG, "overlay permission lost before reattach")
                onPermissionRevoked(app)
            }
            OverlayReattachPolicy.Retry.Skip -> {
                if (!enabled || isShowing()) reattachAttempts = 0
            }
            OverlayReattachPolicy.Retry.Reattach -> {
                if (showWindow(app, toast = false, updateNotification = false)) {
                    handler.postDelayed({
                        if (generation == reattachGeneration) reattachAttempts = 0
                    }, OverlayReattachPolicy.STABLE_WINDOW_MS)
                    return
                }
                if (!Settings.canDrawOverlays(app)) {
                    onPermissionRevoked(app)
                    return
                }
                val next = attempt + 1
                if (OverlayReattachPolicy.onDetach(next) == OverlayReattachPolicy.Detach.Exhausted) {
                    Log.w(TAG, "overlay left down after repeated system detaches")
                    disableAfterDetach(app)
                } else {
                    scheduleReattach(app, next)
                }
            }
        }
    }

    private fun disableAfterDetach(app: Context) {
        cancelReattach()
        stopWatching()
        prefs(app).edit().putBoolean(BluefinStore.KEY_OVERLAY, false).apply()
        dispatch()
        BluefinForegroundService.refresh(app)
    }

    private fun cancelReattach() {
        reattachGeneration += 1
        reattachAttempts = 0
    }

    private fun onPermissionRevoked(context: Context) {
        val app = context.applicationContext
        appContext = app
        cancelReattach()
        val current = window
        window = null
        stopWatching()
        current?.close()
        prefs(app).edit().putBoolean(BluefinStore.KEY_OVERLAY, false).apply()
        dispatch()
        BluefinForegroundService.refresh(app)
        Toast.makeText(app, R.string.overlay_permission_revoked, Toast.LENGTH_LONG).show()
    }

    private fun openSettings(activity: Activity) {
        val direct = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}"),
        )
        try {
            activity.startActivity(direct)
            markAwaitingSettings(activity)
        } catch (error: RuntimeException) {
            Log.d(TAG, "package overlay settings unavailable, trying generic", error)
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                markAwaitingSettings(activity)
            } catch (fallback: RuntimeException) {
                Log.w(TAG, "overlay settings page unavailable", fallback)
                Toast.makeText(activity, R.string.overlay_open_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openGuideFromBackground(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OVERLAY_GUIDE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            context.startActivity(intent)
        } catch (error: RuntimeException) {
            Log.w(TAG, "overlay guide could not open from the background", error)
            Toast.makeText(context.applicationContext, R.string.overlay_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    fun returnToApp(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = BluefinForegroundService.ACTION_RETURN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            context.startActivity(intent)
        } catch (error: RuntimeException) {
            Log.w(TAG, "could not return to the app", error)
        }
    }

    private fun bringAppForward(context: Context) = returnToApp(context)

    private fun startWatching(context: Context) {
        if (watching) return
        try {
            val ops = context.getSystemService(AppOpsManager::class.java)
            ops.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.packageName, opListener)
            watching = true
        } catch (error: SecurityException) {
            Log.w(TAG, "cannot watch overlay permission", error)
        }
    }

    private fun stopWatching() {
        if (!watching) return
        val context = appContext ?: return
        watching = false
        try {
            context.getSystemService(AppOpsManager::class.java).stopWatchingMode(opListener)
        } catch (error: SecurityException) {
            Log.w(TAG, "cannot stop watching overlay permission", error)
        }
    }

    private fun dispatch() {
        val showing = isShowing()
        listeners.toList().forEach { it.onOverlayVisibilityChanged(showing) }
    }

    private fun declinePrompt(activity: Activity) {
        session(activity).onDeclinedInApp()
        persistSession(activity)
        BluefinForegroundService.refresh(activity)
    }

    private fun markAwaitingSettings(activity: Activity) {
        session(activity).onOpenSettings()
        persistSession(activity)
    }

    private fun session(context: Context): OverlayPermissionSession {
        if (!sessionLoaded) {
            val stored = prefs(context)
            session = OverlayPermissionSession(
                awaiting = stored.getBoolean(BluefinStore.KEY_OVERLAY_AWAITING, false),
                dismissed = stored.getBoolean(BluefinStore.KEY_OVERLAY_PROMPT_DISMISSED, false),
            )
            sessionLoaded = true
        }
        return session
    }

    private fun persistSession(context: Context) {
        val current = session(context)
        prefs(context).edit()
            .putBoolean(BluefinStore.KEY_OVERLAY_AWAITING, current.awaitingSettings)
            .putBoolean(BluefinStore.KEY_OVERLAY_PROMPT_DISMISSED, current.promptDismissed)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)
}
