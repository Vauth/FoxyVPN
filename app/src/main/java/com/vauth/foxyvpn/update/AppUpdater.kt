package com.vauth.foxyvpn.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vauth.foxyvpn.BuildConfig
import com.vauth.foxyvpn.FoxyVpnApp
import com.vauth.foxyvpn.R
import com.vauth.foxyvpn.data.AppLinks
import com.vauth.foxyvpn.data.ControlPlaneHttp
import com.vauth.foxyvpn.data.awaitResponse
import com.vauth.foxyvpn.data.readBoundedUtf8
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.Request
import org.json.JSONObject

private const val FAILED_CHECK_BACKOFF_MS = 15 * 60 * 1000L
private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
private const val UPDATE_NOTIFICATION_ID = 2001
private const val UPDATE_PENDING_INTENT_REQUEST_CODE = 2001

data class ReleaseInfo(
    val tag: String,
    val htmlUrl: String,
)

internal fun isNewerVersion(tag: String, currentVersionName: String): Boolean {
    fun parts(value: String): List<Long>? {
        val base =
            value.trim().removePrefix("v").removePrefix("V")
                .substringBefore('-').substringBefore('+')
        val segments = base.split('.')
        if (segments.isEmpty() || segments.size > 5) return null
        return segments.map { it.toLongOrNull() ?: return null }
    }

    val release = parts(tag)
    val current = parts(currentVersionName)
    if (release != null && current != null) {
        val maxLen = maxOf(release.size, current.size)
        for (i in 0 until maxLen) {
            val r = release.getOrElse(i) { 0L }
            val c = current.getOrElse(i) { 0L }
            if (r != c) return r > c
        }
        return false
    }
    val cleanTag = tag.trim().removePrefix("v").removePrefix("V")
    val cleanCurrent = currentVersionName.trim().removePrefix("v").removePrefix("V")
    return cleanTag.compareTo(cleanCurrent, ignoreCase = true) > 0
}

internal class AppUpdater(private val context: Context) {
    private val prefs = context.getSharedPreferences("foxyvpn_updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val client =
        ControlPlaneHttp.client
            .newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .build()

    private var checkJob: Job? = null

    private val mutableManualUpdate = MutableStateFlow<ReleaseInfo?>(null)
    val manualUpdate: StateFlow<ReleaseInfo?> = mutableManualUpdate

    fun dismissManualUpdate() {
        mutableManualUpdate.value = null
    }

    var automaticChecks: Boolean
        get() = prefs.getBoolean("automatic_checks", true)
        set(value) {
            prefs.edit().putBoolean("automatic_checks", value).apply()
        }

    init {
        try {
            File(context.cacheDir, "updates").deleteRecursively()
        } catch (_: Exception) {}
    }

    fun check(automatic: Boolean = false) {
        if (checkJob?.isActive == true) return

        val now = System.currentTimeMillis()
        if (automatic) {
            if (!automaticChecks) return
            val lastSuccessful = prefs.getLong("last_successful_check", 0L)
            val lastAttempt = prefs.getLong("last_check_attempt", 0L)
            if (now - lastSuccessful in 0 until CHECK_INTERVAL_MS ||
                now - lastAttempt in 0 until FAILED_CHECK_BACKOFF_MS
            ) {
                return
            }
        }

        prefs.edit().putLong("last_check_attempt", now).apply()

        if (!automatic) {
            Toast.makeText(context, R.string.update_checking, Toast.LENGTH_SHORT).show()
        }

        checkJob =
            scope.launch {
                try {
                    val release = withContext(Dispatchers.IO) { discoverRelease() }
                    prefs.edit().putLong("last_successful_check", System.currentTimeMillis()).apply()

                    if (release != null && isNewerVersion(release.tag, BuildConfig.VERSION_NAME)) {
                        if (automatic) {
                            val lastNotifiedTag = prefs.getString("last_notified_tag", null)
                            if (lastNotifiedTag != release.tag) {
                                notifyUpdateAvailable(release)
                                prefs.edit().putString("last_notified_tag", release.tag).apply()
                            }
                        } else {
                            mutableManualUpdate.value = release
                        }
                    } else if (!automatic) {
                        mutableManualUpdate.value = null
                        Toast.makeText(
                            context,
                            context.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!automatic) {
                        mutableManualUpdate.value = null
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.update_check_failed,
                                e.message ?: "Network error",
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
    }

    private suspend fun discoverRelease(): ReleaseInfo? {
        val request =
            Request.Builder()
                .url(AppLinks.RELEASE_API)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()

        return client.newCall(request).awaitResponse().use { response ->
            if (response.code == 404) return null
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val raw =
                response.body?.byteStream()?.use { it.readBoundedUtf8(512 * 1024) }
                    ?: error("Empty release response")
            val json = JSONObject(raw)
            if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) {
                return null
            }
            val tag = json.getString("tag_name")
            val htmlUrl = json.optString("html_url", "${AppLinks.SOURCE}/releases/tag/$tag")
            ReleaseInfo(tag = tag, htmlUrl = htmlUrl)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyUpdateAvailable(release: ReleaseInfo) {
        val viewIntent =
            Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                UPDATE_PENDING_INTENT_REQUEST_CODE,
                viewIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat.Builder(context, FoxyVpnApp.UPDATE_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_update)
                .setContentTitle(context.getString(R.string.update_notification_title))
                .setContentText(context.getString(R.string.update_notification_text, release.tag))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.update_notification_text, release.tag))
                )
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .addAction(
                    0,
                    context.getString(R.string.update_btn_view_release),
                    pendingIntent,
                )
                .build()

        if (hasNotificationPermission()) {
            try {
                NotificationManagerCompat.from(context).notify(UPDATE_NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
            }
        }
    }

    private fun hasNotificationPermission(): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = manager.getNotificationChannel(FoxyVpnApp.UPDATE_NOTIFICATION_CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }
        return true
    }
}
