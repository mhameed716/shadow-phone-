package com.shadowphone.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.net.wps.WpsInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.UUID

internal data class NearbyDevice(val address: String, val name: String)
internal data class IncomingMeshMessage(val id: String, val sender: String, val body: String, val receivedAt: Long, val wireId: String)
internal enum class TextDeliveryState { QUEUED, SENT, DELIVERED, FAILED }
internal data class TextDeliveryUpdate(val messageId: String, val state: TextDeliveryState)
internal enum class CallStatus { IDLE, DIALING, CONNECTED, FAILED }

/** Wi-Fi Direct discovery/connection and lifecycle owner for the UDP/PCM engine. */
internal class WifiDirectMeshController(context: Context) {
    private val appContext = context.applicationContext
    private val _status = MutableStateFlow("بانتظار إذن الأجهزة القريبة")
    val status: StateFlow<String> = _status.asStateFlow()
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? = manager?.initialize(appContext, Looper.getMainLooper()) {
        setStatus("انقطع اتصال Wi‑Fi Direct؛ أعد فتح التطبيق")
    }
    private val _devices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val devices: StateFlow<List<NearbyDevice>> = _devices.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _callStatus = MutableStateFlow(CallStatus.IDLE)
    val callStatus: StateFlow<CallStatus> = _callStatus.asStateFlow()
    private val _activePeer = MutableStateFlow("")
    val activePeer: StateFlow<String> = _activePeer.asStateFlow()
    private val _remoteNumbers = MutableStateFlow<Set<String>>(emptySet())
    val remoteNumbers: StateFlow<Set<String>> = _remoteNumbers.asStateFlow()
    private val _incomingMessages = MutableSharedFlow<IncomingMeshMessage>(replay = 64, extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<IncomingMeshMessage> = _incomingMessages.asSharedFlow()
    private val _deliveryUpdates = MutableSharedFlow<TextDeliveryUpdate>(replay = 128, extraBufferCapacity = 128)
    val deliveryUpdates: SharedFlow<TextDeliveryUpdate> = _deliveryUpdates.asSharedFlow()
    private val chatPreferences = appContext.getSharedPreferences("shadow_phone", Context.MODE_PRIVATE)

    private var registered = false
    private var deviceCache: List<WifiP2pDevice> = emptyList()
    private var myNumber: String = ""
    private var engine: UdpMeshAudioEngine? = null
    private var activeGroupOwner: String? = null
    private var activeOwnerRole: Boolean? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    setStatus(if (enabled) "Wi‑Fi Direct جاهز" else "فعّل Wi‑Fi للبحث عن أجهزة قريبة")
                    if (enabled) startDiscovery()
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> loadPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
            }
        }
    }

    fun setPhoneNumber(number: String) {
        if (myNumber == number) return
        myNumber = number
        if (_connected.value) requestConnectionInfo()
    }

    @Suppress("DEPRECATION")
    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else appContext.registerReceiver(receiver, filter)
            registered = true
            requestConnectionInfo()
        } catch (e: Exception) {
            setStatus("تعذر بدء اكتشاف Wi‑Fi Direct")
        }
    }

    fun unregister() {
        if (!registered) return
        try { appContext.unregisterReceiver(receiver) } catch (_: Exception) { }
        registered = false
    }

    fun startDiscovery() {
        val p2pManager = manager
        val p2pChannel = channel
        if (p2pManager == null || p2pChannel == null) { setStatus("Wi‑Fi Direct غير مدعوم على هذا الجهاز"); return }
        try {
            p2pManager.discoverPeers(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { setStatus("جارٍ اكتشاف الأجهزة القريبة") }
                override fun onFailure(reason: Int) { setStatus(discoveryError(reason)) }
            })
        } catch (_: SecurityException) { setStatus("اسمح بإذن الأجهزة القريبة لبدء البحث") }
    }

    fun refresh() = startDiscovery()

    fun permissionDenied() { setStatus("اسمح بإذن الأجهزة القريبة لاكتشاف الهواتف") }
    fun microphonePermissionDenied() { setStatus("اسمح بإذن الميكروفون لإرسال الصوت؛ الرسائل لا تحتاجه") }

    fun endCall() {
        stopTalking()
        engine?.endCall()
        _callStatus.value = CallStatus.IDLE
        _activePeer.value = ""
        setStatus(if (_connected.value) "متصل محلياً • جاهز للاتصال" else "غير متصل • ابحث عن جهاز قريب")
    }

    fun connect(address: String) {
        val p2pManager = manager
        val p2pChannel = channel
        if (p2pManager == null || p2pChannel == null) { setStatus("Wi‑Fi Direct غير مدعوم على هذا الجهاز"); return }
        val device = deviceCache.firstOrNull { it.deviceAddress == address }
        if (device == null) { setStatus("لم يعد الجهاز متاحاً؛ حدّث البحث"); return }
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0
        }
        try {
            p2pManager.connect(p2pChannel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { setStatus("جارٍ إنشاء اتصال آمن مع ${device.deviceName}") }
                override fun onFailure(reason: Int) { setStatus(connectionError(reason)) }
            })
        } catch (_: SecurityException) { setStatus("اسمح بإذن الأجهزة القريبة للاتصال") }
    }

    fun startCall(number: String): Boolean {
        if (!_connected.value || engine?.isReady() != true) { setStatus("قناة UDP غير جاهزة؛ أعد اتصال Wi‑Fi Direct"); return false }
        if (!isValidPhoneNumber(number) || number == myNumber) { setStatus("أدخل رقم جهاز آخر صحيحاً؛ 9 خانات تبدأ بـ 73"); return false }
        if (_callStatus.value == CallStatus.DIALING || _callStatus.value == CallStatus.CONNECTED) {
            setStatus("أنه المكالمة الحالية قبل بدء اتصال جديد"); return false
        }
        _activePeer.value = number
        _callStatus.value = CallStatus.DIALING
        val accepted = engine?.startCall(number) { result ->
            if (_callStatus.value == CallStatus.DIALING && _activePeer.value == number) {
                when (result) {
                    CallAttemptResult.CONNECTED -> {
                        engine?.setCallTarget(number)
                        _callStatus.value = CallStatus.CONNECTED
                        setStatus("تم الاتصال بـ $number • اضغط مطولاً للتحدث")
                    }
                    CallAttemptResult.TIMEOUT -> {
                        engine?.endCall()
                        _callStatus.value = CallStatus.FAILED
                        setStatus("انتهت مهلة الاتصال؛ الجهاز غير متصل أو لا يستجيب")
                    }
                }
            }
        } == true
        if (!accepted) {
            _activePeer.value = ""
            _callStatus.value = CallStatus.IDLE
            setStatus("تعذر بدء محاولة الاتصال عبر UDP")
            return false
        }
        setStatus("جارٍ الاتصال بـ $number • بانتظار استجابة الجهاز")
        return true
    }

    private fun handlePeerLost(number: String) {
        _remoteNumbers.update { it - number }
        if (_activePeer.value == number && _callStatus.value == CallStatus.CONNECTED) {
            stopTalking()
            engine?.endCall()
            _activePeer.value = ""
            _callStatus.value = CallStatus.IDLE
            setStatus("انقطع نبض الجهاز $number؛ أُنهي الاتصال")
        }
    }

    private fun acceptIncomingCall(caller: String, callId: String): Boolean {
        if (!isValidPhoneNumber(caller) || caller == myNumber) return false
        val status = _callStatus.value
        if (status == CallStatus.DIALING || status == CallStatus.CONNECTED) return false
        _activePeer.value = caller
        engine?.setCallTarget(caller, callId)
        _callStatus.value = CallStatus.CONNECTED
        setStatus("مكالمة واردة من $caller • اضغط مطولاً للتحدث")
        return true
    }

    private fun remoteCallEnded(caller: String) {
        if (_activePeer.value == caller) {
            _activePeer.value = ""
            _callStatus.value = CallStatus.IDLE
            stopTalking()
            setStatus("أنهى الطرف الآخر المكالمة")
        }
    }

    fun sendTextMessage(recipient: String, body: String, messageId: String): Boolean {
        if (!_connected.value) { setStatus("اتصل بجهاز قريب أولاً لإرسال الرسائل"); return false }
        if (!isValidPhoneNumber(recipient) || recipient == myNumber || body.isBlank()) { setStatus("تحقق من رقم الطرف الآخر ونص الرسالة"); return false }
        val sent = engine?.sendText(recipient, body, messageId) == true
        setStatus(if (sent) "أُدرجت الرسالة للإرسال عبر UDP المحلي" else "تعذر إدراج الرسالة؛ تحقق من الاتصال والحجم أو الرسائل المعلقة")
        return sent
    }

    fun startTalking() {
        if (!_connected.value || engine?.isReady() != true || _callStatus.value != CallStatus.CONNECTED) {
            setStatus("انتظر إقرار اتصال الطرف الآخر قبل التحدث"); return
        }
        try { engine?.startTalking() } catch (_: SecurityException) { setStatus("اسمح بإذن الميكروفون للتحدث") }
    }

    fun stopTalking() { engine?.stopTalking() }

    fun close() {
        stopTalking()
        _callStatus.value = CallStatus.IDLE
        _activePeer.value = ""
        engine?.stop(); engine = null
        activeGroupOwner = null; activeOwnerRole = null
        unregister()
    }

    private fun loadPeers() {
        val p2pManager = manager
        val p2pChannel = channel
        if (p2pManager == null || p2pChannel == null) { setStatus("Wi‑Fi Direct غير مدعوم على هذا الجهاز"); return }
        try {
            p2pManager.requestPeers(p2pChannel) { list ->
                deviceCache = list.deviceList.toList()
                _devices.value = deviceCache.map { NearbyDevice(it.deviceAddress, it.deviceName.ifBlank { "جهاز قريب" }) }
                if (deviceCache.isEmpty() && !_connected.value) setStatus("لم نعثر على أجهزة بعد • قرّب الهاتفين وفعّل Wi‑Fi")
                else if (!_connected.value) setStatus("تم العثور على ${deviceCache.size} جهاز قريب")
            }
        } catch (_: SecurityException) { setStatus("اسمح بإذن الأجهزة القريبة لعرض الأجهزة") }
    }

    private fun requestConnectionInfo() {
        val p2pManager = manager
        val p2pChannel = channel
        if (p2pManager == null || p2pChannel == null) { setStatus("Wi‑Fi Direct غير مدعوم على هذا الجهاز"); return }
        try {
            p2pManager.requestConnectionInfo(p2pChannel) { info: WifiP2pInfo ->
                val ownerAddress = info.groupOwnerAddress
                if (info.groupFormed && ownerAddress != null) {
                    _connected.value = true
                    if (myNumber.isBlank()) setStatus("جارٍ إعداد رقم الجهاز قبل بدء UDP")
                    else {
                        setStatus("تم الاتصال بمجموعة Wi‑Fi Direct • بدء قناة UDP")
                        startUdp(ownerAddress, info.isGroupOwner)
                    }
                } else {
                    _connected.value = false
                    val callWasActive = _callStatus.value == CallStatus.DIALING || _callStatus.value == CallStatus.CONNECTED
                    engine?.stop(); engine = null
                    activeGroupOwner = null; activeOwnerRole = null
                    _remoteNumbers.value = emptySet()
                    _callStatus.value = CallStatus.IDLE
                    _activePeer.value = ""
                    setStatus(if (callWasActive) "انقطع اتصال Wi‑Fi Direct أثناء المكالمة" else "غير متصل • جارٍ البحث عن أجهزة قريبة")
                }
            }
        } catch (_: SecurityException) { setStatus("اسمح بإذن الأجهزة القريبة لإكمال الاتصال") }
    }

    private fun startUdp(ownerAddress: InetAddress, isOwner: Boolean) {
        val ownerHost = ownerAddress.hostAddress
        if (engine?.isReady() == true && activeGroupOwner == ownerHost && activeOwnerRole == isOwner) return
        if (_callStatus.value == CallStatus.DIALING || _callStatus.value == CallStatus.CONNECTED) {
            engine?.endCall()
            stopTalking()
            _callStatus.value = CallStatus.IDLE
            _activePeer.value = ""
        }
        engine?.stop()
        activeGroupOwner = ownerHost
        activeOwnerRole = isOwner
        engine = UdpMeshAudioEngine(
            context = appContext,
            onPeer = { number -> _remoteNumbers.update { it + number } },
            onPeerLost = { number -> handlePeerLost(number) },
            onMessage = { sender, body, deliveryId -> persistIncomingMessage(sender, body, deliveryId) },
            onTextState = { id, state -> _deliveryUpdates.tryEmit(TextDeliveryUpdate(id, state)) },
            onIncomingCall = { caller, callId -> acceptIncomingCall(caller, callId) },
            onRemoteCallEnd = { caller -> remoteCallEnded(caller) },
            onStatus = { setStatus(it) }
        ).also { it.start(myNumber, ownerAddress, isOwner) }
    }

    private fun persistIncomingMessage(sender: String, body: String, deliveryId: String): Boolean {
        if (!isValidPhoneNumber(sender) || body.isBlank() || deliveryId.isBlank()) return false
        val wireId = "$sender:$deliveryId"
        return synchronized(chatStorageLock) {
            try {
                val old = JSONArray(chatPreferences.getString("chat_messages", "[]"))
                for (index in 0 until old.length()) {
                    val existing = old.optJSONObject(index) ?: continue
                    if (existing.optString("wireId") == wireId) {
                        _incomingMessages.tryEmit(IncomingMeshMessage(existing.optString("id"), sender,
                            existing.optString("body", body), existing.optLong("timestamp", 0L), wireId))
                        return@synchronized true
                    }
                }
                val id = UUID.randomUUID().toString()
                val receivedAt = System.currentTimeMillis()
                val updated = JSONArray()
                val first = (old.length() - (CHAT_HISTORY_LIMIT - 1)).coerceAtLeast(0)
                for (index in first until old.length()) updated.put(old.getJSONObject(index))
                updated.put(JSONObject().put("id", id).put("peer", sender).put("body", body).put("wireId", wireId)
                    .put("timestamp", receivedAt).put("outgoing", false).put("deliveryState", TextDeliveryState.DELIVERED.name))
                val saved = chatPreferences.edit().putString("chat_messages", updated.toString()).commit()
                if (saved) _incomingMessages.tryEmit(IncomingMeshMessage(id, sender, body, receivedAt, wireId))
                saved
            } catch (_: Exception) { false }
        }
    }

    private fun discoveryError(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "هذا الجهاز لا يدعم Wi‑Fi Direct"
        WifiP2pManager.BUSY -> "النظام مشغول؛ أعد المحاولة بعد قليل"
        WifiP2pManager.ERROR -> "تعذر البحث؛ تحقق من تفعيل Wi‑Fi"
        else -> "فشل اكتشاف الأجهزة ($reason)"
    }

    private fun connectionError(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "هذا الجهاز لا يدعم Wi‑Fi Direct"
        WifiP2pManager.BUSY -> "النظام مشغول باتصال آخر"
        WifiP2pManager.ERROR -> "فشل الاتصال؛ حدّث البحث وحاول مجدداً"
        else -> "تعذر الاتصال ($reason)"
    }

    private fun setStatus(message: String) { _status.value = message }
}

private fun isValidPhoneNumber(value: String) = value.length == 9 && value.startsWith("73") && value.all { it.isDigit() }
