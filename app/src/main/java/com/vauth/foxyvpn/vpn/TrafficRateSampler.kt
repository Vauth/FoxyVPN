package com.vauth.foxyvpn.vpn

internal class TrafficRateSampler {
    private var previousUpload = 0L
    private var previousDownload = 0L
    private var previousTime: Long? = null

    @Synchronized
    fun sample(upload: Long, download: Long, nowMillis: Long): VpnLiveStats {
        val up = upload.coerceAtLeast(0)
        val down = download.coerceAtLeast(0)
        val elapsed = previousTime?.let { nowMillis - it }
        val (tx, rx) =
            if (elapsed != null && elapsed > 0 && up >= previousUpload && down >= previousDownload) {
                val txRate = ((up - previousUpload).toDouble() * 1000 / elapsed).toLong()
                val rxRate = ((down - previousDownload).toDouble() * 1000 / elapsed).toLong()
                txRate to rxRate
            } else {
                0L to 0L
            }
        previousUpload = up
        previousDownload = down
        previousTime = nowMillis
        val total = if (up > Long.MAX_VALUE - down) Long.MAX_VALUE else up + down
        return VpnLiveStats(tx, rx, total)
    }
}
