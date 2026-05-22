package com.screenguard.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.screenguard.ui.MainActivity
import com.screenguard.utils.BlocklistManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DnsVpnService : VpnService() {

    companion object {
        private const val TAG = "DnsVpnService"
        private const val NOTIFICATION_CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.screenguard.START_VPN"
        const val ACTION_STOP = "com.screenguard.STOP_VPN"

        private const val DNS_PORT = 53
        private const val UPSTREAM_DNS = "185.228.168.168"
        private val DNS_ROUTES = arrayOf(
            UPSTREAM_DNS,
            "185.228.169.168",
            "1.1.1.1",
            "1.0.0.1",
            "8.8.8.8",
            "8.8.4.4",
            "9.9.9.9",
            "149.112.112.112"
        )

        private val dnsCache = ConcurrentHashMap<String, Pair<ByteArray, Long>>()
        private const val CACHE_TTL_MS = 30_000L
        private const val MAX_CACHE_SIZE = 500
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var vpnThread: Thread? = null
    private var threadPool: ExecutorService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                START_NOT_STICKY
            }
            else -> {
                startVpn()
                START_STICKY
            }
        }
    }

    @SuppressLint("ForegroundServiceType")
    private fun startVpn() {
        if (running.get()) return

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        threadPool = Executors.newFixedThreadPool(4)
        val builder = Builder()
            .addAddress("10.0.0.2", 32)
            .addDnsServer(UPSTREAM_DNS)
            .setSession("ScreenGuard Filter")
            .setBlocking(true)

        DNS_ROUTES.forEach { builder.addRoute(it, 32) }
        vpnInterface = builder.establish()

        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            threadPool?.shutdownNow()
            threadPool = null
            stopSelf()
            return
        }

        running.set(true)
        vpnThread = Thread({ runVpnLoop() }, "vpn-reader-thread").also { it.start() }
        Log.i(TAG, "VPN started")
    }

    private fun runVpnLoop() {
        val descriptor = vpnInterface?.fileDescriptor ?: return
        val input = FileInputStream(descriptor)
        val output = FileOutputStream(descriptor)
        val buffer = ByteArray(32767)

        while (running.get()) {
            try {
                val bytesRead = input.read(buffer)
                if (bytesRead <= 0) continue

                val packet = buffer.copyOf(bytesRead)
                if (!isUdpDnsPacket(packet)) {
                    Log.d(TAG, "Ignoring non-DNS packet routed to VPN")
                    continue
                }

                threadPool?.execute {
                    try {
                        handleDnsPacket(packet, output)
                    } catch (e: Exception) {
                        Log.e(TAG, "DNS handler error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "VPN loop error: ${e.message}")
            }
        }
    }

    private fun handleDnsPacket(packet: ByteArray, output: FileOutputStream) {
        val dnsPayload = extractDnsPayload(packet)
        val hostname = parseDnsQueryHostname(dnsPayload)

        if (hostname != null && BlocklistManager.shouldBlock(this, hostname)) {
            Log.i(TAG, "BLOCKED: $hostname")
            val blockedResponse = buildBlockedDnsResponse(dnsPayload)
            val responsePacket = wrapInUdpIpPacket(packet, blockedResponse)
            synchronized(output) { output.write(responsePacket) }
            return
        }

        val cached = getCached(hostname, dnsPayload)
        if (cached != null) {
            val responsePacket = wrapInUdpIpPacket(packet, cached)
            synchronized(output) { output.write(responsePacket) }
            return
        }

        val response = forwardDnsQuery(dnsPayload)
        if (response != null) {
            if (hostname != null) putCache(hostname, dnsPayload, response)
            val responsePacket = wrapInUdpIpPacket(packet, response)
            synchronized(output) { output.write(responsePacket) }
        }
    }

    private fun getCached(hostname: String?, dnsPayload: ByteArray): ByteArray? {
        val cacheKey = cacheKey(hostname, dnsPayload) ?: return null
        val entry = dnsCache[cacheKey] ?: return null
        if (System.currentTimeMillis() > entry.second) {
            dnsCache.remove(cacheKey)
            return null
        }

        val patched = entry.first.copyOf()
        patched[0] = dnsPayload[0]
        patched[1] = dnsPayload[1]
        return patched
    }

    private fun putCache(hostname: String, dnsPayload: ByteArray, response: ByteArray) {
        val cacheKey = cacheKey(hostname, dnsPayload) ?: return
        if (dnsCache.size >= MAX_CACHE_SIZE) {
            val oldest = dnsCache.entries.minByOrNull { it.value.second }
            oldest?.let { dnsCache.remove(it.key) }
        }
        dnsCache[cacheKey] = Pair(response.copyOf(), System.currentTimeMillis() + CACHE_TTL_MS)
    }

    private fun cacheKey(hostname: String?, dnsPayload: ByteArray): String? {
        hostname ?: return null
        val questionFooter = questionFooterOffset(dnsPayload) ?: return null
        if (questionFooter + 4 > dnsPayload.size) return null

        val queryType = readUnsignedShort(dnsPayload, questionFooter)
        val queryClass = readUnsignedShort(dnsPayload, questionFooter + 2)
        return "$hostname:$queryType:$queryClass"
    }

    private fun isUdpDnsPacket(packet: ByteArray): Boolean {
        val ipHeaderLen = ipv4HeaderLength(packet) ?: return false
        if (packet.size < ipHeaderLen + 8) return false
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return false

        val destPort = readUnsignedShort(packet, ipHeaderLen + 2)
        return destPort == DNS_PORT
    }

    private fun extractDnsPayload(packet: ByteArray): ByteArray {
        val ipHeaderLen = ipv4HeaderLength(packet) ?: return ByteArray(0)
        val dnsStart = ipHeaderLen + 8
        return packet.copyOfRange(dnsStart, packet.size)
    }

    private fun parseDnsQueryHostname(dns: ByteArray): String? {
        return try {
            if (dns.size < 13) return null
            val sb = StringBuilder()
            var i = 12

            while (i < dns.size) {
                val len = dns[i].toInt() and 0xFF
                if (len == 0) break
                if (len and 0xC0 != 0 || i + len >= dns.size) return null

                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(dns, i + 1, len))
                i += len + 1
            }

            sb.toString().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun questionFooterOffset(dns: ByteArray): Int? {
        if (dns.size < 13) return null

        var i = 12
        while (i < dns.size) {
            val len = dns[i].toInt() and 0xFF
            if (len == 0) return i + 1
            if (len and 0xC0 != 0 || i + len >= dns.size) return null
            i += len + 1
        }

        return null
    }

    private fun buildBlockedDnsResponse(query: ByteArray): ByteArray {
        val response = query.copyOf()
        response[2] = (query[2].toInt() or 0x80).toByte()
        response[3] = ((query[3].toInt() and 0xF0) or 0x03).toByte()

        for (i in 6..11) {
            response[i] = 0
        }

        return response
    }

    private fun forwardDnsQuery(dnsPayload: ByteArray): ByteArray? {
        return try {
            DatagramSocket().use { socket ->
                protect(socket)
                val upstream = InetAddress.getByName(UPSTREAM_DNS)
                val sendPacket = DatagramPacket(dnsPayload, dnsPayload.size, upstream, DNS_PORT)
                socket.soTimeout = 3000
                socket.send(sendPacket)

                val recvBuf = ByteArray(4096)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(recvPacket)
                recvBuf.copyOf(recvPacket.length)
            }
        } catch (e: Exception) {
            Log.e(TAG, "DNS forward failed: ${e.message}")
            null
        }
    }

    private fun wrapInUdpIpPacket(originalQuery: ByteArray, dnsResponse: ByteArray): ByteArray {
        val ipHeaderLen = ipv4HeaderLength(originalQuery) ?: return ByteArray(0)
        val totalLen = ipHeaderLen + 8 + dnsResponse.size
        val result = ByteArray(totalLen)

        System.arraycopy(originalQuery, 0, result, 0, ipHeaderLen)
        System.arraycopy(originalQuery, 16, result, 12, 4)
        System.arraycopy(originalQuery, 12, result, 16, 4)

        result[2] = ((totalLen shr 8) and 0xFF).toByte()
        result[3] = (totalLen and 0xFF).toByte()
        result[10] = 0
        result[11] = 0

        result[ipHeaderLen] = originalQuery[ipHeaderLen + 2]
        result[ipHeaderLen + 1] = originalQuery[ipHeaderLen + 3]
        result[ipHeaderLen + 2] = originalQuery[ipHeaderLen]
        result[ipHeaderLen + 3] = originalQuery[ipHeaderLen + 1]

        val udpLen = 8 + dnsResponse.size
        result[ipHeaderLen + 4] = ((udpLen shr 8) and 0xFF).toByte()
        result[ipHeaderLen + 5] = (udpLen and 0xFF).toByte()
        result[ipHeaderLen + 6] = 0
        result[ipHeaderLen + 7] = 0

        System.arraycopy(dnsResponse, 0, result, ipHeaderLen + 8, dnsResponse.size)
        val checksum = ipv4HeaderChecksum(result, ipHeaderLen)
        result[10] = ((checksum shr 8) and 0xFF).toByte()
        result[11] = (checksum and 0xFF).toByte()

        return result
    }

    private fun ipv4HeaderLength(packet: ByteArray): Int? {
        if (packet.size < 20) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null

        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (headerLength < 20 || packet.size < headerLength) return null
        return headerLength
    }

    private fun ipv4HeaderChecksum(packet: ByteArray, headerLength: Int): Int {
        var sum = 0
        var i = 0

        while (i < headerLength) {
            sum += readUnsignedShort(packet, i)
            while (sum > 0xFFFF) {
                sum = (sum and 0xFFFF) + (sum ushr 16)
            }
            i += 2
        }

        return sum.inv() and 0xFFFF
    }

    private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun stopVpn() {
        running.set(false)
        threadPool?.shutdownNow()
        threadPool = null
        vpnThread?.interrupt()
        vpnThread = null
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Content Filter",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "ScreenGuard content filtering is active" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, DnsVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("ScreenGuard Active")
            .setContentText("Content filtering is on")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }
}
