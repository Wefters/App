package dev.wefter.bridge

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import org.json.JSONObject

/**
 * App information, lifecycle events and opening other apps. Deep links and back are handled by the shell
 * and `@wefterjs/core` (`onDeepLink`, `onBack`).
 */
class AppPlugin(context: Context, dispatcher: BridgeDispatcher) :
        WefterPlugin(context, dispatcher) {

    private var lifecycleState: String = "active"
    private var currentActivityRef: WeakReference<Activity>? = null

    /** Watches activity lifecycle (for state changes) and memory pressure. */
    init {
        (context as? Activity)?.let { currentActivityRef = WeakReference(it) }

        val app = context.applicationContext as? Application
        app?.registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityResumed(activity: Activity) {
                        currentActivityRef = WeakReference(activity)
                        setState("active")
                    }

                    override fun onActivityPaused(activity: Activity) = setState("inactive")
                    override fun onActivityStopped(activity: Activity) = setState("background")

                    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                    override fun onActivityStarted(activity: Activity) {}
                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                    override fun onActivityDestroyed(activity: Activity) {}
                }
        )

        context.applicationContext.registerComponentCallbacks(
                object : ComponentCallbacks2 {
                    override fun onTrimMemory(level: Int) {
                        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                            safeEmit("app:lowMemory", JSONObject().put("level", level))
                        }
                    }

                    override fun onLowMemory() {
                        safeEmit("app:lowMemory", JSONObject().put("level", -1))
                    }

                    override fun onConfigurationChanged(newConfig: Configuration) {}
                }
        )
    }


    /** Name, package name, version, build, debug flag and install times of the app. */
    @WefterMethod
    fun getInfo(payload: JSONObject, callback: (Result<Any>) -> Unit) {
        try {
            val pm = context.packageManager
            val packageName = context.packageName
            val info = pm.getPackageInfo(packageName, 0)
            val name = pm.getApplicationLabel(context.applicationInfo).toString()
            val versionCode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
                    else @Suppress("DEPRECATION") info.versionCode.toLong()
            val debuggable =
                    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

            resolve(
                    callback,
                    JSONObject()
                            .put("name", name)
                            .put("id", packageName)
                            .put("version", info.versionName ?: "")
                            .put("build", versionCode.toString())
                            .put("debug", debuggable)
                            .put("firstInstallTime", info.firstInstallTime)
                            .put("lastUpdateTime", info.lastUpdateTime)
            )
        } catch (e: Exception) {
            reject(callback, "INFO_FAILED", e.message ?: "Could not read application info.")
        }
    }

    /** The current lifecycle state. */
    @WefterMethod
    fun getState(payload: JSONObject, callback: (Result<Any>) -> Unit) {
        resolve(
                callback,
                JSONObject().put("isActive", lifecycleState == "active").put("state", lifecycleState)
        )
    }

    /** Moves the app's task to the background. */
    @WefterMethod
    fun minimizeApp(payload: JSONObject, callback: (Result<Any>) -> Unit) {
        val activity = currentActivityRef?.get()
        if (activity == null) {
            reject(callback, "NO_ACTIVITY", "No foreground activity to minimize.")
            return
        }
        try {
            activity.moveTaskToBack(true)
            resolve(callback, JSONObject().put("success", true))
        } catch (e: Exception) {
            reject(callback, "MINIMIZE_FAILED", e.message ?: "Could not minimize the app.")
        }
    }

    /** Finishes the task and ends the process shortly after answering. */
    @WefterMethod
    fun exitApp(payload: JSONObject, callback: (Result<Any>) -> Unit) {
        try {
            currentActivityRef?.get()?.let { act -> act.runOnUiThread { act.finishAndRemoveTask() } }
            resolve(callback, JSONObject().put("success", true))
            scheduleProcessDeath()
        } catch (e: Exception) {
            reject(callback, "EXIT_FAILED", e.message ?: "Could not exit the app.")
        }
    }

    /** Schedules the launch activity with an alarm a moment later, then ends the current process. */
    @WefterMethod
    fun relaunchApp(payload: JSONObject, callback: (Result<Any>) -> Unit) {
        try {
            val ctx = context.applicationContext
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            if (launch == null) {
                reject(callback, "RELAUNCH_FAILED", "No launch intent for this package.")
                return
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

            val piFlags =
                    PendingIntent.FLAG_CANCEL_CURRENT or
                            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                                    PendingIntent.FLAG_IMMUTABLE
                            else 0)
            val pending = PendingIntent.getActivity(ctx, RELAUNCH_REQUEST, launch, piFlags)
            val alarm = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 250L, pending)

            currentActivityRef?.get()?.let { act -> act.runOnUiThread { act.finishAndRemoveTask() } }
            resolve(callback, JSONObject().put("success", true))
            scheduleProcessDeath()
        } catch (e: Exception) {
            reject(callback, "RELAUNCH_FAILED", e.message ?: "Could not relaunch the app.")
        }
    }

    /** Opens a URL with whichever app handles it. */
    @WefterMethod
    fun openUrl(payload: JSONObject, callback: (Result<Any>) -> Unit) {
        val url = payload.optString("url", "").trim()
        if (url.isEmpty()) {
            reject(callback, "INVALID_ARGUMENT", "A non-empty 'url' is required.")
            return
        }
        try {
            val intent =
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            (currentActivityRef?.get() ?: context).startActivity(intent)
            resolve(callback, JSONObject().put("success", true))
        } catch (e: android.content.ActivityNotFoundException) {
            reject(callback, "NO_HANDLER", "No installed app can handle: $url")
        } catch (e: Exception) {
            reject(callback, "OPEN_FAILED", e.message ?: "Could not open the URL.")
        }
    }

    /** Whether some installed app can open a URL. */
    @WefterMethod
    fun canOpenUrl(payload: JSONObject, callback: (Result<Any>) -> Unit) {
        val url = payload.optString("url", "").trim()
        if (url.isEmpty()) {
            reject(callback, "INVALID_ARGUMENT", "A non-empty 'url' is required.")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            val can = intent.resolveActivity(context.packageManager) != null
            resolve(callback, JSONObject().put("value", can))
        } catch (e: Exception) {
            reject(callback, "CHECK_FAILED", e.message ?: "Could not check the URL.")
        }
    }

    /** Records the lifecycle state and emits `app:stateChange` when it changes. */
    private fun setState(state: String) {
        if (lifecycleState == state) return
        lifecycleState = state
        safeEmit(
                "app:stateChange",
                JSONObject().put("isActive", state == "active").put("state", state)
        )
    }

    /** Emits an event, ignoring failures (for example before the web view exists). */
    private fun safeEmit(event: String, data: JSONObject) {
        try {
            emit(event, data)
        } catch (e: Exception) {
        }
    }

    /** Ends the process shortly, after the answer has reached JavaScript. */
    private fun scheduleProcessDeath() {
        Handler(Looper.getMainLooper()).postDelayed({ Runtime.getRuntime().exit(0) }, 120L)
    }

    companion object {
        private const val RELAUNCH_REQUEST = 0x57454654
    }
}
