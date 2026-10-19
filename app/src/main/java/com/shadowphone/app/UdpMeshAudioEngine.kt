package com.shadowphone.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.Process
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.LinkedHashMap
import java.util.UUID
import java.util.zip.CRC32
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/** UDP voice and text frames with bounded flood-forwarding inside a Wi-Fi Direct group. */
internal enum class CallAttemptResult { CONNECTED, TIMEOUT }

internal class UdpMeshAudioEngine(
    private val context: Context,
    private val onPeer: (String) -> Unit,
    private val onPeerLost: (String) -> Unit,
    private val onMessage: (sender: String, body: String, deliveryId: String) -> Boolean,
    private val onTextState: (messageId: String, state: TextDeliveryState) -> Unit,
    private val onIncomingCall: (callerNumber: String, callId: String) -> Boolean,
    private val onRemoteCallEnd: (callerNumber: String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val PORT = 45871
        private const val MAGIC = 0x53485031 // SHP1
        private const val PRESENCE: Byte = 1
        private const val AUDIO: Byte = 2
        private const val TEXT: Byte = 3
        private const val ACK: Byte = 4
        private const val CALL: Byte = 5
        private const val CALL_END: Byte = 6
        private const val CALL_TIMEOUT_MS = 12_000L
        private const val CALL_RETRY_INTERVAL_MS = 750L
        private const val CALL_END_TIMEOUT_MS = 4_000L
        private const val CALL_END_RETRY_INTERVAL_MS = 500L
        private const val PRESENCE_INTERVAL_MS = 30_000L
        private const val PEER_TIMEOUT_MS = 90_000L
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val MAX_TEXT_BYTES = 800
        private const val TEXT_ACK_RETRY_INTERVAL_MS = 1_000L
        private const val MAX_AUDIO_RETRIES = 2
        private const val AUDIO_RETRY_INTERVAL_MS = 80L
        private const val AUDIO_DEADLINE_MS = 260L
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_BYTES = 640 // 20 ms, mono, PCM16
        private const val MAX_PACKET = 1200
        private const val TTL = 8
    }

    private val running = AtomicBoolean(false)
    private val transmitting = AtomicBoolean(false)
    private val sequence = AtomicLong(SecureRandom().nextLong())
    private val seen = object : LinkedHashMap<String, Long>(1024, 0.75f, true) {}
    private val processedContents = object : LinkedHashMap<String, Long>(1024, 0.75f, true) {}
    private val peers = LinkedHashMap<String, Long>()
    private val endedCalls = LinkedHashMap<String, Long>()
    private val pendingTexts = ConcurrentHashMap<Long, PendingText>()
    private val pendingAudio = ConcurrentHashMap<Long, PendingAudio>()
    private val pendingCalls = ConcurrentHashMap<Long, PendingCall>()
    private val pendingCallEnds = ConcurrentHashMap<Long, PendingCallEnd>()
    private val retryLock = ReentrantLock()
    private val retryCondition = retryLock.newCondition()
    private var retryGeneration = 0L
    private val playbackQueue = ArrayBlockingQueue<ByteArray>(64)
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var callWakeLock: PowerManager.WakeLock? = null
    private val wakeLockMonitor = Any()
    @Volatile private var ownerAddress: InetAddress? = null
    @Volatile private var isGroupOwner = false
    @Volatile private var ownNumber = ""
    @Volatile private var targetNumber = ""
    @Volatile private var activeCallId = ""
    @Volatile private var callActive = false
    private var nextPeerExpiryCheckAt = 0L
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var playbackThread: Thread? = null

    fun start(myNumber: String, groupOwner: InetAddress, groupOwnerRole: Boolean) {
        stop()
        ownNumber = myNumber
        ownerAddress = groupOwner
        isGroupOwner = groupOwnerRole
        running.set(true)
        try {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifiManager?.createMulticastLock("ShadowPhoneUdp")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Exception) {
                multicastLock = null
                onStatus("تعذر تفعيل استقبال البث المحلي؛ تابع الاتصال مع قيود النظام")
            }
            val udp = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(PORT))
                soTimeout = SOCKET_TIMEOUT_MS
            }
            socket = udp
            playbackThread = thread(name = "shadow-audio-playback", isDaemon = true) { playbackLoop() }
            thread(name = "shadow-udp-rx", isDaemon = true) { receiveLoop(udp) }
            thread(name = "shadow-udp-retry", isDaemon = true) { retryLoop() }
            thread(name = "shadow-presence", isDaemon = true) {
                while (running.get()) {
                    send(Packet(PRESENCE, TTL, sequence.incrementAndGet(), ownNumber, "", ByteArray(0)))
                    try { Thread.sleep(PRESENCE_INTERVAL_MS) } catch (_: InterruptedException) { break }
                }
            }
            onStatus("اتصال Wi‑Fi Direct جاهز • UDP مفعّل")
        } catch (e: Exception) {
            running.set(false)
            socket?.close()
            socket = null
            releaseMulticastLock()
            onStatus("تعذر تشغيل UDP: ${e.localizedMessage ?: "خطأ شبكة"}")
        }
    }

    fun isReady(): Boolean = running.get() && socket?.isClosed == false

    private fun wakeRetryLoop() {
        retryLock.withLock {
            retryGeneration++
            retryCondition.signalAll()
        }
    }

    fun startCall(recipient: String, onResult: (CallAttemptResult) -> Unit): Boolean {
        if (!isReady() || recipient.isBlank() || pendingCalls.isNotEmpty()) return false
        targetNumber = recipient
        callActive = false
        activeCallId = UUID.randomUUID().toString()
        val packet = Packet(CALL, TTL, sequence.incrementAndGet(), ownNumber, recipient, ByteArray(0), deliveryId = activeCallId)
        pendingCalls[packet.sequence] = PendingCall(packet, onResult, System.currentTimeMillis() + CALL_TIMEOUT_MS)
        acquireCallWakeLock()
        wakeRetryLoop()
        return true
    }

    fun setCallTarget(number: String, callId: String? = null) {
        targetNumber = number
        if (number.isBlank()) {
            activeCallId = ""
            releaseCallWakeLock()
        } else {
            if (!callId.isNullOrBlank()) activeCallId = callId
            acquireCallWakeLock()
        }
        callActive = number.isNotBlank()
    }

    fun endCall() {
        val previousTarget = targetNumber
        if (running.get() && previousTarget.isNotBlank() && activeCallId.isNotBlank()) {
            val packet = Packet(CALL_END, TTL, sequence.incrementAndGet(), ownNumber, previousTarget, ByteArray(0), deliveryId = activeCallId)
            pendingCallEnds[packet.sequence] = PendingCallEnd(packet, System.currentTimeMillis() + CALL_END_TIMEOUT_MS)
            send(packet)
            wakeRetryLoop()
        }
        pendingCalls.clear()
        targetNumber = ""
        activeCallId = ""
        callActive = false
        releaseCallWakeLock()
        wakeRetryLoop()
    }

    private fun releaseMulticastLock() {
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (_: RuntimeException) { }
        multicastLock = null
    }

    private fun acquireCallWakeLock() {
        synchronized(wakeLockMonitor) {
            if (callWakeLock?.isHeld == true) return
            try {
                val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                callWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShadowPhone:ActiveCall").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: RuntimeException) {
                callWakeLock = null
                onStatus("تعذر منع سكون المعالج أثناء المكالمة")
            }
        }
    }

    private fun releaseCallWakeLock() {
        synchronized(wakeLockMonitor) {
            try { callWakeLock?.let { if (it.isHeld) it.release() } } catch (_: RuntimeException) { }
            callWakeLock = null
        }
    }

    fun sendText(recipient: String, body: String, messageId: String): Boolean {
        val payload = body.toByteArray(StandardCharsets.UTF_8)
        if (!running.get() || recipient.isBlank() || messageId.isBlank() || body.isBlank() || payload.size > MAX_TEXT_BYTES || pendingTexts.size >= 100) return false
        val packet = Packet(TEXT, TTL, sequence.incrementAndGet(), ownNumber, recipient, payload, deliveryId = messageId)
        pendingTexts[packet.sequence] = PendingText(packet, messageId)
        wakeRetryLoop()
        onTextState(messageId, TextDeliveryState.QUEUED)
        return true
    }

    fun startTalking() {
        if (!running.get() || !transmitting.compareAndSet(false, true)) return
        thread(name = "shadow-audio-tx", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try {
                val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuffer <= 0) throw IllegalStateException("AudioRecord غير مدعوم")
                val mic = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minBuffer, FRAME_BYTES * 4))
                    .build()
                recorder = mic
                mic.startRecording()
                val frame = ByteArray(FRAME_BYTES)
                while (transmitting.get() && running.get()) {
                    val count = mic.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                    if (count > 0) {
                        val bytes = if (count == frame.size) frame.copyOf() else frame.copyOf(count)
                        val packet = Packet(AUDIO, TTL, sequence.incrementAndGet(), ownNumber, targetNumber, bytes)
                        val shouldWakeRetryLoop = pendingAudio.isEmpty()
                        pendingAudio[packet.sequence] = PendingAudio(packet)
                        if (shouldWakeRetryLoop) wakeRetryLoop()
                        send(packet)
                    }
                }
            } catch (e: Exception) {
                onStatus("تعذر استخدام الميكروفون: ${e.localizedMessage ?: "تحقق من الإذن"}")
            } finally {
                val activeRecorder = recorder
                try { activeRecorder?.stop() } catch (_: Exception) { }
                try { activeRecorder?.release() } catch (_: Exception) { }
                if (recorder === activeRecorder) recorder = null
                transmitting.set(false)
            }
        }
    }

    fun stopTalking() {
        transmitting.set(false)
        try { recorder?.stop() } catch (_: Exception) { }
    }

    private fun retryLoop() {
        while (running.get()) {
            val observedGeneration = retryLock.withLock { retryGeneration }
            val now = System.currentTimeMillis()
            var nextWakeAt = Long.MAX_VALUE

            pendingCalls.entries.forEach { (sequenceId, pending) ->
                synchronized(pending) {
                    if (now >= pending.expiresAt) {
                        if (pendingCalls.remove(sequenceId, pending)) pending.onResult(CallAttemptResult.TIMEOUT)
                    } else {
                        if (now >= pending.nextAttemptAt) {
                            send(pending.packet.copy(attempt = pending.attempts.coerceAtMost(255)))
                            pending.attempts++
                            pending.nextAttemptAt = now + CALL_RETRY_INTERVAL_MS
                        }
                        nextWakeAt = minOf(nextWakeAt, pending.expiresAt, pending.nextAttemptAt)
                    }
                }
            }
            pendingCallEnds.entries.forEach { (sequenceId, pending) ->
                synchronized(pending) {
                    if (now >= pending.expiresAt) pendingCallEnds.remove(sequenceId, pending)
                    else {
                        if (now >= pending.nextAttemptAt && pending.attempts < 8) {
                            send(pending.packet.copy(attempt = pending.attempts.coerceAtMost(255)))
                            pending.attempts++
                            pending.nextAttemptAt = now + CALL_END_RETRY_INTERVAL_MS
                        }
                        nextWakeAt = minOf(nextWakeAt, pending.expiresAt)
                        if (pending.attempts < 8) nextWakeAt = minOf(nextWakeAt, pending.nextAttemptAt)
                    }
                }
            }
            pendingTexts.entries.forEach { (sequenceId, pending) ->
                synchronized(pending) {
                    if (pendingTexts[sequenceId] === pending) {
                        if (now >= pending.nextAttemptAt) {
                            val sent = send(pending.packet.copy(attempt = pending.attempts.coerceAtMost(255)))
                            if (pending.attempts < 255) pending.attempts++
                            if (sent && !pending.sentOnce && pendingTexts[sequenceId] === pending) {
                                pending.sentOnce = true
                                onTextState(pending.messageId, TextDeliveryState.SENT)
                            }
                            // Retry at a bounded one-second cadence; do not poll while idle.
                            pending.nextAttemptAt = now + TEXT_ACK_RETRY_INTERVAL_MS
                        }
                        nextWakeAt = minOf(nextWakeAt, pending.nextAttemptAt)
                    }
                }
            }
            pendingAudio.entries.forEach { (sequenceId, pending) ->
                synchronized(pending) {
                    if (now - pending.createdAt >= AUDIO_DEADLINE_MS) pendingAudio.remove(sequenceId, pending)
                    else {
                        if (pending.retries < MAX_AUDIO_RETRIES && now >= pending.nextRetryAt) {
                            pending.retries++
                            pending.nextRetryAt = pending.createdAt + AUDIO_RETRY_INTERVAL_MS * (pending.retries + 1)
                            send(pending.packet.copy(attempt = pending.retries))
                        }
                        nextWakeAt = minOf(nextWakeAt, pending.createdAt + AUDIO_DEADLINE_MS)
                        if (pending.retries < MAX_AUDIO_RETRIES) nextWakeAt = minOf(nextWakeAt, pending.nextRetryAt)
                    }
                }
            }

            try {
                retryLock.withLock {
                    if (running.get() && retryGeneration == observedGeneration) {
                        if (nextWakeAt == Long.MAX_VALUE) {
                            retryCondition.await()
                        } else {
                            val delayMs = (nextWakeAt - System.currentTimeMillis()).coerceAtLeast(1L)
                            retryCondition.awaitNanos(TimeUnit.MILLISECONDS.toNanos(delayMs))
                        }
                    }
                }
            } catch (_: InterruptedException) {
                if (!running.get()) break
            }
        }
    }

    private fun receiveLoop(udp: DatagramSocket) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buffer = ByteArray(MAX_PACKET)
        while (running.get() && !udp.isClosed) {
            try {
                val datagram = DatagramPacket(buffer, buffer.size)
                udp.receive(datagram)
                expirePeers()
                val packet = decode(datagram.data, datagram.length) ?: continue
                if (packet.sender == ownNumber) continue
                val isForThisPhone = packet.target == ownNumber

                // ACK every received voice frame, including duplicate retries. The sender
                // uses a short deadline so retransmission does not turn live audio into a queue.
                if (packet.type == AUDIO && isForThisPhone) sendAcknowledgement(packet)

                if (packet.type == CALL && isForThisPhone) {
                    if (isCallCancelled(packet)) continue
                    if (hasContent(packet)) {
                        if (!markNew(packet)) { sendAcknowledgement(packet); continue }
                    } else {
                        val accepted = runCatching { onIncomingCall(packet.sender, packet.deliveryId) }.getOrDefault(false)
                        if (!accepted) continue
                        markContentOnce(packet)
                        markNew(packet)
                    }
                    sendAcknowledgement(packet)
                    if (packet.ttl > 0) relay(packet.copy(ttl = packet.ttl - 1))
                    continue
                }

                if (packet.type == CALL_END && isForThisPhone) {
                    if (!markNew(packet)) { sendAcknowledgement(packet); continue }
                    synchronized(endedCalls) {
                        val now = System.currentTimeMillis()
                        endedCalls["${packet.sender}:${packet.deliveryId}"] = now
                        while (endedCalls.size > 256) endedCalls.remove(endedCalls.entries.first().key)
                        endedCalls.entries.removeAll { now - it.value > 60_000L }
                    }
                    if (targetNumber == packet.sender && activeCallId == packet.deliveryId) {
                        pendingCalls.entries.removeAll { it.value.packet.deliveryId == packet.deliveryId }
                        targetNumber = ""
                        activeCallId = ""
                        callActive = false
                        releaseCallWakeLock()
                        onRemoteCallEnd(packet.sender)
                    }
                    sendAcknowledgement(packet)
                    if (packet.ttl > 0) relay(packet.copy(ttl = packet.ttl - 1))
                    continue
                }

                if (packet.type == TEXT && isForThisPhone) {
                    if (hasContent(packet)) {
                        if (!markNew(packet)) { sendAcknowledgement(packet); continue }
                    } else {
                        val body = String(packet.payload, StandardCharsets.UTF_8)
                        val stored = body.isNotBlank() && runCatching { onMessage(packet.sender, body, packet.deliveryId) }.getOrDefault(false)
                        if (!stored) { onStatus("تعذر حفظ الرسالة؛ لم يُرسل إقرار التسليم"); continue }
                        markContentOnce(packet)
                        markNew(packet)
                    }
                    sendAcknowledgement(packet)
                    if (packet.ttl > 0) relay(packet.copy(ttl = packet.ttl - 1))
                    continue
                }

                if (!markNew(packet)) continue
                if (packet.type == ACK && isForThisPhone) processAcknowledgement(packet)
                if (packet.sender.isNotBlank() && packet.type == PRESENCE) {
                    synchronized(peers) { peers[packet.sender] = System.currentTimeMillis() }
                    onPeer(packet.sender)
                }
                if (packet.ttl > 0) relay(packet.copy(ttl = packet.ttl - 1))
                if (callActive && packet.type == AUDIO && isForThisPhone && markContentOnce(packet)) play(packet.payload)
            } catch (_: SocketTimeoutException) {
                expirePeers()
            } catch (_: Exception) {
                if (running.get()) onStatus("انقطع استقبال حزم الصوت")
            }
        }
    }

    private fun sendAcknowledgement(received: Packet) {
        val payload = ByteBuffer.allocate(9).put(received.type).putLong(received.sequence).array()
        send(Packet(ACK, TTL, sequence.incrementAndGet(), ownNumber, received.sender, payload))
    }

    private fun processAcknowledgement(packet: Packet) {
        if (packet.payload.size != 9) return
        val buffer = ByteBuffer.wrap(packet.payload)
        val acknowledgedType = buffer.get()
        val acknowledgedSequence = buffer.long
        when (acknowledgedType) {
            TEXT -> pendingTexts.remove(acknowledgedSequence)?.let { acknowledged ->
                pendingTexts.entries.forEach { (key, pending) ->
                    if (pending.messageId == acknowledged.messageId) pendingTexts.remove(key, pending)
                }
                onTextState(acknowledged.messageId, TextDeliveryState.DELIVERED)
            }
            AUDIO -> pendingAudio.remove(acknowledgedSequence)
            CALL -> pendingCalls.remove(acknowledgedSequence)?.let {
                it.onResult(if (System.currentTimeMillis() < it.expiresAt) CallAttemptResult.CONNECTED else CallAttemptResult.TIMEOUT)
            }
            CALL_END -> pendingCallEnds.remove(acknowledgedSequence)
        }
        if (acknowledgedType != AUDIO) wakeRetryLoop()
    }

    private fun packetKey(packet: Packet) = "${packet.sender}:${packet.type}:${packet.sequence}:${packet.attempt}"
    private fun contentKey(packet: Packet) = "${packet.sender}:${packet.type}:${if ((packet.type == TEXT || packet.type == CALL) && packet.deliveryId.isNotBlank()) packet.deliveryId else packet.sequence}"

    private fun isCallCancelled(packet: Packet): Boolean = synchronized(endedCalls) {
        endedCalls.containsKey("${packet.sender}:${packet.deliveryId}")
    }

    private fun hasContent(packet: Packet): Boolean = synchronized(processedContents) { processedContents.containsKey(contentKey(packet)) }

    private fun markContentOnce(packet: Packet): Boolean = synchronized(processedContents) {
        val key = contentKey(packet)
        val now = System.currentTimeMillis()
        if (processedContents.containsKey(key)) false else {
            processedContents[key] = now
            while (processedContents.size > 2_000) processedContents.remove(processedContents.entries.first().key)
            if (processedContents.size % 128 == 0) processedContents.entries.removeAll { now - it.value > 60_000 }
            true
        }
    }

    private fun markNew(packet: Packet): Boolean = synchronized(seen) {
        val key = packetKey(packet)
        val now = System.currentTimeMillis()
        if (seen.containsKey(key)) false else {
            seen[key] = now
            while (seen.size > 2_000) seen.remove(seen.entries.first().key)
            if (seen.size % 128 == 0) seen.entries.removeAll { now - it.value > 30_000 }
            true
        }
    }

    private fun expirePeers() {
        val now = System.currentTimeMillis()
        if (now < nextPeerExpiryCheckAt) return
        nextPeerExpiryCheckAt = now + 1_000L
        val expired = synchronized(peers) {
            peers.filterValues { now - it > PEER_TIMEOUT_MS }.keys.toList().also { numbers ->
                numbers.forEach { peer -> peers.remove(peer) }
            }
        }
        expired.forEach(onPeerLost)
    }

    private fun relay(packet: Packet) {
        // Wi-Fi Direct creates one IP subnet. Members send to the group owner; the owner
        // broadcasts into the group. Packet IDs and TTL prevent forwarding loops.
        send(packet)
    }

    private fun send(packet: Packet): Boolean {
        val udp = socket ?: return false
        return try {
            val bytes = encode(packet)
            val destination = if (isGroupOwner) InetAddress.getByName("192.168.49.255") else ownerAddress
            if (destination == null) false else {
                udp.send(DatagramPacket(bytes, bytes.size, destination, PORT))
                true
            }
        } catch (_: Exception) {
            if (running.get()) onStatus("تعذر إرسال حزمة UDP")
            false
        }
    }

    private fun play(pcm: ByteArray) {
        if (!playbackQueue.offer(pcm)) {
            playbackQueue.poll()
            playbackQueue.offer(pcm)
        }
    }

    private fun playbackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var track: AudioTrack? = null
        try {
            val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) throw IllegalStateException("AudioTrack غير مدعوم")
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minBuffer, FRAME_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            player = track
            track.play()
            while (running.get()) {
                val pcm = playbackQueue.take()
                track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            if (running.get()) onStatus("تعذر تشغيل الصوت المستقبَل")
        } finally {
            try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) { }
            player = null
        }
    }

    fun stop() {
        transmitting.set(false)
        running.set(false)
        wakeRetryLoop()
        releaseCallWakeLock()
        try { recorder?.stop() } catch (_: Exception) { }
        playbackQueue.clear()
        playbackThread?.interrupt(); playbackThread = null
        socket?.close(); socket = null
        releaseMulticastLock()
        pendingTexts.values.forEach { onTextState(it.messageId, TextDeliveryState.FAILED) }
        pendingTexts.clear(); pendingAudio.clear(); pendingCalls.clear(); pendingCallEnds.clear()
        synchronized(peers) { peers.clear() }
        synchronized(endedCalls) { endedCalls.clear() }
        synchronized(seen) { seen.clear() }
        synchronized(processedContents) { processedContents.clear() }
        targetNumber = ""
        activeCallId = ""
        callActive = false
    }

    private data class Packet(val type: Byte, val ttl: Int, val sequence: Long, val sender: String, val target: String, val payload: ByteArray, val attempt: Int = 0, val deliveryId: String = "")
    private class PendingText(val packet: Packet, val messageId: String) {
        @Volatile var attempts = 0
        @Volatile var nextAttemptAt = 0L
        @Volatile var sentOnce = false
    }
    private class PendingAudio(val packet: Packet, val createdAt: Long = System.currentTimeMillis()) {
        @Volatile var retries = 0
        @Volatile var nextRetryAt = createdAt + AUDIO_RETRY_INTERVAL_MS
    }
    private class PendingCall(val packet: Packet, val onResult: (CallAttemptResult) -> Unit, val expiresAt: Long) {
        @Volatile var attempts = 0
        @Volatile var nextAttemptAt = 0L
    }
    private class PendingCallEnd(val packet: Packet, val expiresAt: Long) {
        private val createdAt = System.currentTimeMillis()
        @Volatile var attempts = 1
        @Volatile var nextAttemptAt = createdAt + CALL_END_RETRY_INTERVAL_MS
    }

    private fun encode(packet: Packet): ByteArray {
        val output = ByteArrayOutputStream(packet.payload.size + 128)
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC); data.writeByte(packet.type.toInt()); data.writeByte(packet.ttl); data.writeByte(packet.attempt)
            data.writeLong(packet.sequence); data.writeUTF(packet.sender.take(32)); data.writeUTF(packet.target.take(32))
            data.writeUTF(packet.deliveryId.take(48))
            data.writeShort(packet.payload.size)
            val crc = CRC32().apply { update(packet.payload) }
            data.writeInt(crc.value.toInt())
            data.write(packet.payload)
        }
        return output.toByteArray()
    }

    private fun decode(bytes: ByteArray, length: Int): Packet? {
        return try {
            DataInputStream(ByteArrayInputStream(bytes, 0, length)).use { data ->
                if (data.readInt() != MAGIC) return null
                val type = data.readByte()
                val ttl = data.readUnsignedByte()
                val attempt = data.readUnsignedByte()
                val seq = data.readLong()
                val sender = data.readUTF()
                val target = data.readUTF()
                val deliveryId = data.readUTF()
                val size = data.readUnsignedShort()
                val maxSize = when (type) {
                    PRESENCE -> 0
                    AUDIO -> FRAME_BYTES
                    TEXT -> MAX_TEXT_BYTES
                    ACK -> 9
                    CALL, CALL_END -> 0
                    else -> return null
                }
                if ((type == TEXT || type == CALL || type == CALL_END) && (deliveryId.isBlank() || deliveryId.length > 48)) return null
                if (size > maxSize || data.available() < size + 4) return null
                val expectedCrc = data.readInt().toLong() and 0xFFFF_FFFFL
                val payload = ByteArray(size); data.readFully(payload)
                val actualCrc = CRC32().apply { update(payload) }.value
                if (actualCrc != expectedCrc) return null
                Packet(type, ttl, seq, sender, target, payload, attempt, deliveryId)
            }
        } catch (_: Exception) { null }
    }
}
