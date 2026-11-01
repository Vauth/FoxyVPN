package com.vauth.foxyvpn.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val isLaunchable: Boolean,
)

private val cacheMutex = Mutex()
@Volatile private var cachedApps: List<InstalledApp>? = null
@Volatile private var cachedAtElapsedMs = 0L
private const val CACHE_TTL_MS = 300_000L

internal fun invalidateInstalledAppsCache() {
    cachedApps = null
    cachedAtElapsedMs = 0L
}

internal suspend fun loadInstalledApps(context: Context, forceReload: Boolean = false): List<InstalledApp> =
    withContext(Dispatchers.IO) {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedApps
        if (!forceReload && cached != null && (now - cachedAtElapsedMs < CACHE_TTL_MS)) {
            return@withContext cached
        }
        cacheMutex.withLock {
            val lockedNow = SystemClock.elapsedRealtime()
            val lockedCached = cachedApps
            if (!forceReload && lockedCached != null && (lockedNow - cachedAtElapsedMs < CACHE_TTL_MS)) {
                return@withLock lockedCached
            }
            val pm = context.applicationContext.packageManager
            val launchablePackages =
                pm.queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                        0,
                    )
                    .mapTo(hashSetOf()) { it.activityInfo.packageName }
            val coroutineContext = currentCoroutineContext()
            val apps =
                pm.getInstalledApplications(0)
                    .asSequence()
                    .filter { it.packageName != context.packageName }
                    .distinctBy { it.packageName }
                    .map { info ->
                        coroutineContext.ensureActive()
                        val label =
                            try {
                                info.loadLabel(pm).toString().ifBlank { info.packageName }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                info.packageName
                            }
                        InstalledApp(
                            packageName = info.packageName,
                            label = label,
                            isSystem =
                                info.flags and
                                    (ApplicationInfo.FLAG_SYSTEM or
                                        ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                            isLaunchable = info.packageName in launchablePackages,
                        )
                    }
                    .toList()
            val collator = Collator.getInstance()
            val sorted = apps.sortedWith { first, second ->
                collator.compare(first.label, second.label).takeIf { it != 0 }
                    ?: first.packageName.compareTo(second.packageName)
            }
            cachedApps = sorted
            cachedAtElapsedMs = lockedNow
            sorted
        }
    }

internal fun filterInstalledApps(
    apps: List<InstalledApp>,
    query: String,
    showSystemApps: Boolean,
    selected: Set<String>,
): List<InstalledApp> {
    val search = query.trim()
    return apps.filter { app ->
        (showSystemApps || !app.isSystem || app.isLaunchable || app.packageName in selected) &&
            (search.isEmpty() ||
                app.label.contains(search, ignoreCase = true) ||
                app.packageName.contains(search, ignoreCase = true))
    }
}
