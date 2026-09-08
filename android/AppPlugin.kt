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
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.lang.ref.WeakReference
import org.json.JSONObject

class AppPlugin(context: Context, dispatcher: BridgeDispatcher) :
        WefterPlugin(context, dispatcher) {

    private var lifecycleState: String = "active"
    private var currentActivityRef: WeakReference<Activity>? = null
    private var launchUrl: String? = null

    private var backIntercept = true // ON by default — the plugin owns the back button.
    private var backCallback: OnBackPressedCallback? = null
    private var backOwner: WeakReference<ComponentActivity>? = null

    init {
        (context as? Activity)?.let {
            currentActivityRef = WeakReference(it)
            captureLaunchUrl(it.intent)
        }

        val app = context.applicationContext as? Application
        app?.registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityResumed(activity: Activity) {
                        currentActivityRef = WeakReference(activity)
                        if (launchUrl == null) captureLaunchUrl(activity.intent)
                        attachBackCallback(activity)
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

    fun onExternalUrl(url: String?) {
        if (url.isNullOrEmpty()) return
        if (launchUrl == null) launchUrl = url
        safeEmit("app:urlOpen", JSONObject().put("url", url))
    }


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

    @WefterMethod
    fun getState(payload: JSONObject, callback: (Result<Any>) -> Unit) {
        resolve(
                callback,
                JSONObject().put("isActive", lifecycleState == "active").put("state", lifecycleState)
        )
    }

    @WefterMethod
    fun getLaunchUrl(payload: JSONObject, callback: (Result<Any>) -> Unit) {
        resolve(callback, JSONObject().put("url", launchUrl ?: JSONObject.NULL))
    }

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

    @WefterMethod
    fun setBackButtonInterceptEnabled(payload: JSONObject, callback: (Result<Any>) -> Unit) {
        backIntercept = payload.optBoolean("enabled", true)
        resolve(callback, JSONObject().put("success", true))
    }


    private fun attachBackCallback(activity: Activity) {
        val ca = activity as? ComponentActivity ?: return
        if (backOwner?.get() === ca && backCallback != null) return

        backCallback?.remove()
        val cb =
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        val wv = findWebView(ca)
                        val canGoBack = wv?.canGoBack() ?: false
                        if (backIntercept) {
                            try {
                                emit("app:backButton", JSONObject().put("canGoBack", canGoBack))
                                return
                            } catch (e: Exception) {
                                // bridge unreachable — fall through to default so we don't trap
                            }
                        }
                        if (canGoBack) {
                            wv?.goBack()
                        } else {
                            isEnabled = false
                            ca.onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        }
                    }
                }
        ca.onBackPressedDispatcher.addCallback(ca, cb)
        backCallback = cb
        backOwner = WeakReference(ca)
    }

    private fun setState(state: String) {
        if (lifecycleState == state) return
        lifecycleState = state
        safeEmit(
                "app:stateChange",
                JSONObject().put("isActive", state == "active").put("state", state)
        )
    }

    private fun safeEmit(event: String, data: JSONObject) {
        try {
            emit(event, data)
        } catch (e: Exception) {
            // best-effort — never crash on an emit failure
        }
    }

    private fun scheduleProcessDeath() {
        Handler(Looper.getMainLooper()).postDelayed({ Runtime.getRuntime().exit(0) }, 120L)
    }

    private fun captureLaunchUrl(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_VIEW) {
            intent.dataString?.let { launchUrl = it }
        }
    }

    private fun findWebView(activity: Activity): WebView? {
        val root = activity.window?.decorView as? ViewGroup ?: return null
        return findWebView(root)
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findWebView(view.getChildAt(i))?.let {
                    return it
                }
            }
        }
        return null
    }

    companion object {
        private const val RELAUNCH_REQUEST = 0x57454654 // "WEFT"
    }
}
