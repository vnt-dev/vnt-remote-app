package com.rustvnt.remote

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Release tags use SemVer, optionally prefixed with v. Build metadata does not affect ordering. */
internal data class ReleaseVersion(val numbers: List<java.math.BigInteger>, val pre: List<String>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        numbers.zip(other.numbers).forEach { (a, b) -> a.compareTo(b).let { if (it != 0) return it } }
        if (pre.isEmpty() || other.pre.isEmpty()) return when {
            pre == other.pre -> 0
            pre.isEmpty() -> 1
            else -> -1
        }
        pre.zip(other.pre).forEach { (a, b) ->
            val an = a.toBigIntegerOrNull(); val bn = b.toBigIntegerOrNull()
            val compared = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (compared != 0) return compared
        }
        return pre.size.compareTo(other.pre.size)
    }

    companion object {
        private val pattern = Regex("^[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
        fun parse(value: String): ReleaseVersion? {
            val match = pattern.matchEntire(value.trim()) ?: return null
            val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (pre.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
            return ReleaseVersion((1..3).map { match.groupValues[it].toBigInteger() }, pre)
        }
    }
}

internal data class ReleaseAsset(val name: String, val url: String)
internal data class AppRelease(val tag: String, val notes: String, val assets: List<ReleaseAsset>) {
    fun apk(abis: List<String>): ReleaseAsset? =
        abis.firstNotNullOfOrNull { abi -> assets.find { it.name == "VNTRemote-$abi-release.apk" } }
            ?: assets.find { it.name == "VNTRemote-universal-release.apk" }
}

internal object GitHubReleases {
    const val PAGE = "https://github.com/vnt-dev/vnt-remote-app/releases"
    private const val API = "https://api.github.com/repos/vnt-dev/vnt-remote-app/releases/latest"

    fun latest(): AppRelease? {
        val connection = URL(API).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "VNTRemote-Android")
            when (connection.responseCode) {
                404 -> return null
                403, 429 -> throw IOException("GitHub 暂时限制了请求，请稍后重试。")
                200 -> Unit
                else -> throw IOException("GitHub 返回 HTTP ${connection.responseCode}，请稍后重试。")
            }
            val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val tag = json.getString("tag_name")
            if (ReleaseVersion.parse(tag) == null) throw IOException("发布版本号无法识别：$tag")
            val assets = json.getJSONArray("assets")
            return AppRelease(tag, json.optString("body"), (0 until assets.length()).mapNotNull { index ->
                val asset = assets.getJSONObject(index)
                val url = asset.optString("browser_download_url")
                // Only open APK URLs published under this repository, over HTTPS.
                if (asset.optString("state") == "uploaded" && url.startsWith("$PAGE/download/"))
                    ReleaseAsset(asset.getString("name"), url) else null
            })
        } finally {
            connection.disconnect()
        }
    }
}
