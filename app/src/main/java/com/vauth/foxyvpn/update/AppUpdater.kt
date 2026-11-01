package com.vauth.foxyvpn.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.vauth.foxyvpn.BuildConfig
import com.vauth.foxyvpn.data.awaitResponse
import com.vauth.foxyvpn.data.readBoundedUtf8
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

private const val RELEASE_API = com.vauth.foxyvpn.data.AppLinks.RELEASE_API
private const val MAX_APK_BYTES = 512L * 1024 * 1024
private const val FAILED_CHECK_BACKOFF_MS = 15 * 60 * 1000L
private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

internal sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object Current : UpdateState

    data class Available(val release: AppRelease) : UpdateState

    data class Downloading(val bytes: Long, val total: Long) : UpdateState

    data class Ready(val file: File, val version: String, val sha256: String) : UpdateState

    data class Failed(val message: String) : UpdateState
}

internal class AppUpdater(private val context: Context) {
    private val prefs = context.getSharedPreferences("foxyvpn_updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .followSslRedirects(false)
            .build()
    private val directory = File(context.cacheDir, "updates").apply { mkdirs() }
    private var operation: Job? = null
    private val publicationLock = Any()
    private var generation = 0L
    private val mutablePromptVisible = MutableStateFlow(false)
    val promptVisible: StateFlow<Boolean> = mutablePromptVisible

    fun showPrompt() {
        mutablePromptVisible.value = true
    }

    fun hidePrompt() {
        mutablePromptVisible.value = false
    }

    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState
    var automaticChecks: Boolean
        get() = prefs.getBoolean("automatic_checks", true)
        set(value) {
            prefs.edit().putBoolean("automatic_checks", value).apply()
        }

    init {
        directory.listFiles()?.forEach { it.delete() }
    }

    fun check(automatic: Boolean = false) {
        if (operation?.isActive == true) return
        if (mutableState.value is UpdateState.Ready) {
            if (automatic) return
            cancel()
        }

        val now = System.currentTimeMillis()
        if (
            automatic &&
                (!automaticChecks ||
                    now - prefs.getLong("last_successful_check", 0) in 0 until CHECK_INTERVAL_MS ||
                    now - prefs.getLong("last_check_attempt", 0) in 0 until FAILED_CHECK_BACKOFF_MS)
        )
            return
        prefs.edit().putLong("last_check_attempt", now).apply()
        val previousTag = (mutableState.value as? UpdateState.Available)?.release?.tag
        operation =
            scope.launch {
                mutableState.value = UpdateState.Checking
                try {
                    val release =
                        withContext(Dispatchers.IO) {
                            discoverRelease(allowSameVersionName = !automatic)
                        }
                    prefs
                        .edit()
                        .putLong("last_successful_check", System.currentTimeMillis())
                        .apply()
                    mutableState.value =
                        if (release == null) UpdateState.Current else UpdateState.Available(release)
                    if (release != null && (!automatic || release.tag != previousTag)) showPrompt()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    mutableState.value =
                        if (automatic) UpdateState.Idle
                        else UpdateState.Failed(error.message ?: "Update check failed")
                }
            }
    }

    fun download(release: AppRelease) {
        if (operation?.isActive == true) return
        val myGeneration = synchronized(publicationLock) { ++generation }
        operation =
            scope.launch {
                val file = File(directory, "${UUID.randomUUID()}.apk")
                var retain = false
                try {
                    mutableState.value = UpdateState.Downloading(0, release.apk.size)
                    val expectedHash = withContext(Dispatchers.IO) { expectedHash(release) }
                    downloadAsset(release.apk, file, myGeneration)
                    val version = withContext(Dispatchers.IO) { validateApk(file, expectedHash) }
                    if (version == null) mutableState.value = UpdateState.Current
                    else {
                        retain = true
                        mutableState.value = UpdateState.Ready(file, version, expectedHash)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    mutableState.value =
                        UpdateState.Failed(error.message ?: "Update download failed")
                } finally {
                    if (!retain) file.delete()
                }
            }
    }

    fun cancel() {
        operation?.cancel()
        operation = null
        synchronized(publicationLock) {
            generation++
            (mutableState.value as? UpdateState.Ready)?.file?.delete()
            mutableState.value = UpdateState.Idle
        }
    }

    suspend fun installationIntent(ready: UpdateState.Ready): Intent {
        if (!context.packageManager.canRequestPackageInstalls()) {
            return Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
        }
        withContext(Dispatchers.IO) {
            check(validateApk(ready.file, ready.sha256) != null) {
                "This update is no longer newer than the installed app"
            }
        }
        val uri =
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", ready.file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private suspend fun discoverRelease(allowSameVersionName: Boolean): AppRelease? {
        val request =
            Request.Builder()
                .url(RELEASE_API)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
        val json =
            client.newCall(request).awaitResponse().use { response ->
                check(response.code != 404) {
                    "No public release is available. Publish signed APKs in ${com.vauth.foxyvpn.data.AppLinks.REPOSITORY} releases."
                }
                check(response.isSuccessful) {
                    "Release check failed: HTTP ${response.code}. Try again later."
                }
                JSONObject(
                    response.body?.byteStream()?.use { it.readBoundedUtf8(1_048_576) }
                        ?: error("Empty release response")
                )
            }
        return parseAppRelease(
            json,
            BuildConfig.VERSION_NAME,
            Build.SUPPORTED_ABIS.toList(),
            allowSameVersionName,
        )
    }

    private suspend fun expectedHash(release: AppRelease): String {
        release.apk.digest
            ?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
            ?.let {
                return it.lowercase()
            }
        val sums =
            release.checksums
                ?: error("Release integrity metadata is missing (SHA-256 digest or SHA256SUMS)")
        return client.newCall(Request.Builder().url(sums.url).build()).awaitResponse().use {
            response ->
            check(response.isSuccessful) { "Cannot fetch release checksums" }
            val lines =
                response.body?.byteStream()?.use { it.readBoundedUtf8(131_072) }
                    ?: error("Empty checksum file")
            lines
                .lineSequence()
                .mapNotNull { line ->
                    val match = Regex("^([a-fA-F0-9]{64}) [ *](.+)$").matchEntire(line.trim())
                    match
                        ?.takeIf { it.groupValues[2] == release.apk.name }
                        ?.groupValues
                        ?.get(1)
                        ?.lowercase()
                }
                .singleOrNull() ?: error("No unique SHA-256 checksum for this APK")
        }
    }

    private suspend fun downloadAsset(asset: UpdateAsset, file: File, myGeneration: Long): Unit =
        suspendCancellableCoroutine { continuation ->
            if (
                !trustedAssetUrl(asset.url) ||
                    android.os.StatFs(directory.absolutePath).availableBytes <
                        asset.size + 16 * 1024 * 1024
            ) {
                continuation.resumeWithException(
                    IOException("Untrusted download URL or insufficient storage")
                )
                return@suspendCancellableCoroutine
            }
            val call = client.newCall(Request.Builder().url(asset.url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                check(response.isSuccessful) {
                                    "Download failed: HTTP ${response.code}"
                                }
                                val body = response.body ?: error("Empty APK response")
                                var total = 0L
                                var published = 0L
                                body.byteStream().use { input ->
                                    file.outputStream().use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            if (!continuation.isActive)
                                                throw IOException("Download cancelled")
                                            val n = input.read(buffer)
                                            if (n < 0) break
                                            total += n
                                            check(total <= asset.size && total <= MAX_APK_BYTES) {
                                                "APK exceeds release size"
                                            }
                                            output.write(buffer, 0, n)
                                            if (
                                                total - published >= 256 * 1024 ||
                                                    total == asset.size
                                            ) {
                                                published = total
                                                synchronized(publicationLock) {
                                                    if (
                                                        continuation.isActive &&
                                                            generation == myGeneration
                                                    )
                                                        mutableState.value =
                                                            UpdateState.Downloading(
                                                                total,
                                                                asset.size,
                                                            )
                                                }
                                            }
                                        }
                                    }
                                }
                                check(total == asset.size) { "Incomplete APK download" }
                            }
                            if (continuation.isActive) continuation.resume(Unit) else file.delete()
                        } catch (error: Exception) {
                            file.delete()
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            )
        }

    @Suppress("DEPRECATION")
    private fun validateApk(file: File, expectedHash: String): String? {
        check(file.isFile && file.canonicalFile.parentFile == directory.canonicalFile) {
            "Update file is missing"
        }
        val hash =
            MessageDigest.getInstance("SHA-256")
                .apply {
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            update(buffer, 0, n)
                        }
                    }
                }
                .digest()
                .joinToString("") { "%02x".format(it) }
        check(hash.equals(expectedHash, true)) { "APK integrity check failed" }
        val pm = context.packageManager
        val flags =
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val downloaded =
            pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Invalid or unsigned APK")
        val installed = pm.getPackageInfo(context.packageName, flags)
        val nativeAbis =
            ZipFile(file).use { zip -> nativeCoreAbis(zip.entries().asSequence().map { it.name }) }
        fun code(info: PackageInfo) =
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val candidate =
            ApkIdentity(
                downloaded.packageName,
                code(downloaded),
                downloaded.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE,
                signers(downloaded),
                nativeAbis,
            )
        val current =
            ApkIdentity(installed.packageName, code(installed), 26, signers(installed), emptySet())
        if (
            !verifyUpdateIdentity(
                candidate,
                current,
                Build.SUPPORTED_ABIS.toList(),
                Build.VERSION.SDK_INT,
            )
        )
            return null
        return downloaded.versionName ?: code(downloaded).toString()
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val certificates =
            if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
            else info.signatures
        return certificates
            .orEmpty()
            .map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString(
                    ""
                ) {
                    "%02x".format(it)
                }
            }
            .toSet()
    }
}
