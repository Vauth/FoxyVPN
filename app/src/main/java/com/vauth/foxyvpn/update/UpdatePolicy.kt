package com.vauth.foxyvpn.update

import org.json.JSONObject

internal data class UpdateAsset(
    val name: String,
    val url: String,
    val size: Long,
    val digest: String?,
)

internal data class AppRelease(val tag: String, val apk: UpdateAsset, val checksums: UpdateAsset?)

internal data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val minSdk: Int,
    val signers: Set<String>,
    val nativeAbis: Set<String>,
)

internal fun verifyUpdateIdentity(
    candidate: ApkIdentity,
    installed: ApkIdentity,
    supportedAbis: List<String>,
    sdk: Int,
): Boolean {
    check(candidate.packageName == installed.packageName) { "APK package does not match FoxyVPN" }
    check(candidate.signers.isNotEmpty() && candidate.signers == installed.signers) {
        "APK signing identity differs. Debug builds cannot install release updates; signing-key rotation requires a reviewed migration."
    }
    check(candidate.minSdk <= sdk) { "Update requires a newer Android version" }
    check(candidate.nativeAbis.isNotEmpty() && supportedAbis.any { it in candidate.nativeAbis }) {
        "Update native libraries do not support this device"
    }
    return candidate.versionCode > installed.versionCode
}

internal fun nativeCoreAbis(entries: Sequence<String>): Set<String> =
    entries
        .mapNotNull { name ->
            val parts = name.split('/')
            parts.takeIf { it.size == 3 && it[0] == "lib" && it[2] == "libbox.so" }?.get(1)
        }
        .toSet()

private val knownApkAbis = listOf("armeabi-v7a", "arm64-v8a", "x86_64", "x86", "armeabi", "riscv64")

internal fun apkAssetAbi(name: String): String? {
    val lower = name.lowercase(java.util.Locale.ROOT)
    return knownApkAbis.firstOrNull { abi ->
        Regex("(^|[-_.])${Regex.escape(abi)}([-_.]|$)").containsMatchIn(lower)
    }
}

private fun releaseTagComparison(tag: String, currentVersionName: String): Int? {
    fun parts(value: String): List<Long>? {
        val segments = value.removePrefix("v").split('.')
        if (
            segments.size !in 2..4 ||
                segments.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } }
        )
            return null
        return segments.map { it.toLongOrNull() ?: return null }
    }
    val release = parts(tag) ?: return null
    val current = parts(currentVersionName) ?: return null
    for (index in 0 until maxOf(release.size, current.size)) {
        val comparison = release.getOrElse(index) { 0L }.compareTo(current.getOrElse(index) { 0L })
        if (comparison != 0) return comparison
    }
    return 0
}

internal fun releaseTagIsNotNewer(tag: String, currentVersionName: String): Boolean =
    releaseTagComparison(tag, currentVersionName)?.let { it <= 0 }
        ?: (tag.removePrefix("v") == currentVersionName)

internal fun parseAppRelease(
    json: JSONObject,
    currentVersionName: String,
    supportedAbis: List<String>,
    allowSameVersionName: Boolean = false,
): AppRelease? {
    check(!json.optBoolean("draft") && !json.optBoolean("prerelease")) {
        "No stable release is available"
    }
    val tag = json.getString("tag_name")

    val comparison = releaseTagComparison(tag, currentVersionName)
    if (comparison != null && (comparison < 0 || comparison == 0 && !allowSameVersionName))
        return null
    if (comparison == null && !allowSameVersionName && tag.removePrefix("v") == currentVersionName)
        return null
    val assets = json.getJSONArray("assets")
    val parsed =
        (0 until assets.length()).map { index ->
            val asset = assets.getJSONObject(index)
            UpdateAsset(
                asset.getString("name"),
                asset.getString("browser_download_url"),
                asset.getLong("size"),
                asset
                    .optString("digest")
                    .takeIf { it.startsWith("sha256:") }
                    ?.removePrefix("sha256:"),
            )
        }
    val apks =
        parsed.filter {
            it.name.endsWith(".apk", true) &&
                it.size in 1..512L * 1024 * 1024 &&
                trustedAssetUrl(it.url)
        }
    val apk =
        supportedAbis.firstNotNullOfOrNull { abi ->
            apks.singleOrNull { apkAssetAbi(it.name) == abi }
        }
            ?: apks.singleOrNull {
                apkAssetAbi(it.name) == null &&
                    Regex("(^|[-_.])universal([-_.]|$)")
                        .containsMatchIn(it.name.lowercase(java.util.Locale.ROOT))
            }
            ?: apks.singleOrNull()?.takeIf { apkAssetAbi(it.name) == null }
            ?: error("This release has no unambiguous APK for this device's ABI")
    return AppRelease(
        tag,
        apk,
        parsed.singleOrNull { it.name == "SHA256SUMS" && trustedAssetUrl(it.url) },
    )
}

internal fun trustedAssetUrl(value: String): Boolean =
    runCatching {
            val uri = java.net.URI(value)
            uri.scheme == "https" &&
                uri.host == "github.com" &&
                uri.rawUserInfo == null &&
                uri.rawFragment == null &&
                uri.rawQuery == null &&
                uri.port == -1 &&
                uri.rawPath?.startsWith(com.vauth.foxyvpn.data.AppLinks.RELEASE_DOWNLOAD_PATH) ==
                    true
        }
        .getOrDefault(false)
