package com.shadowphone.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.collect

private val Ink = Color(0xFF090E11)
private val Panel = Color(0xFF11191C)
private val Raised = Color(0xFF182226)
private val Lime = Color(0xFFB9F36A)
private val Cyan = Color(0xFF8ADBE2)
private val TextMain = Color(0xFFF1F5EF)
private val Muted = Color(0xFF879397)
private val Danger = Color(0xFFFF7D76)

class MainActivity : ComponentActivity() {
    private var mesh: WifiDirectMeshController? = null
    private val meshState = mutableStateOf<WifiDirectMeshController?>(null)
    private val batteryOptimizationExempt = mutableStateOf(false)
    private lateinit var failureAnnouncer: CallFailureAnnouncer
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val controller = (service as? MeshKeepAliveService.LocalBinder)?.controller()
            mesh = controller
            meshState.value = controller
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mesh = null
            meshState.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        failureAnnouncer = CallFailureAnnouncer(this)
        window.statusBarColor = android.graphics.Color.rgb(9, 14, 17)
        window.navigationBarColor = android.graphics.Color.rgb(9, 14, 17)
        MeshKeepAliveService.start(this)
        serviceBound = try {
            bindService(Intent(this, MeshKeepAliveService::class.java), serviceConnection, android.content.Context.BIND_AUTO_CREATE)
        } catch (_: RuntimeException) { false }
        setContent {
            val activeMesh = meshState.value
            if (activeMesh != null) {
                ShadowPhoneApp(activeMesh, failureAnnouncer, batteryOptimizationExempt.value, ::requestBatteryOptimizationExemption)
            } else {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize(), color = Ink) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("جارٍ تشغيل خدمة الشبكة المحلية…", color = TextMain)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val power = getSystemService(PowerManager::class.java)
        batteryOptimizationExempt.value = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            power?.isIgnoringBatteryOptimizations(packageName) == true
    }

    override fun onPause() {
        mesh?.stopTalking()
        super.onPause()
    }

    override fun onDestroy() {
        if (serviceBound) try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) { }
        failureAnnouncer.close()
        super.onDestroy()
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || batteryOptimizationExempt.value) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: RuntimeException) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: RuntimeException) { }
        }
    }
}

data class Contact(val name: String, val number: String)
private data class ChatMessage(val id: String, val peerNumber: String, val body: String, val timestamp: Long, val outgoing: Boolean, val deliveryState: TextDeliveryState, val wireId: String? = null)
internal val chatStorageLock = Any()
internal const val CHAT_HISTORY_LIMIT = 250

@Composable
private fun ShadowPhoneApp(
    mesh: WifiDirectMeshController,
    failureAnnouncer: CallFailureAnnouncer,
    batteryOptimizationExempt: Boolean,
    onRequestBatteryOptimizationExemption: () -> Unit
) {
    val context = LocalContext.current
    val networkStatus by mesh.status.collectAsState()
    val nearbyDevices by mesh.devices.collectAsState()
    val connected by mesh.connected.collectAsState()
    val callStatus by mesh.callStatus.collectAsState()
    val activePeer by mesh.activePeer.collectAsState()
    val remoteNumbers by mesh.remoteNumbers.collectAsState()
    val prefs = remember { context.getSharedPreferences("shadow_phone", 0) }
    var myNumber by remember { mutableStateOf(prefs.getString("phone_number", null) ?: createAndSaveNumber(prefs)) }
    var chatMessages by remember { mutableStateOf(loadChatMessages(prefs)) }
    var selectedChat by remember { mutableStateOf<String?>(null) }
    var showNewChat by remember { mutableStateOf(false) }
    var newChatNumber by remember { mutableStateOf("") }
    var newChatError by remember { mutableStateOf("") }
    var welcomed by remember { mutableStateOf(prefs.getBoolean("welcomed", false)) }
    var contacts by remember { mutableStateOf(loadContacts(prefs)) }
    var page by remember { mutableStateOf(0) }
    var entered by remember { mutableStateOf("") }
    var callState by remember { mutableStateOf(false) }
    var talking by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<Contact?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf("") }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) mesh.startDiscovery() else mesh.permissionDenied()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) mesh.microphonePermissionDenied()
    }
    LaunchedEffect(myNumber) { mesh.setPhoneNumber(myNumber) }
    LaunchedEffect(callStatus, activePeer) {
        when (callStatus) {
            CallStatus.IDLE -> { callState = false; talking = false }
            CallStatus.DIALING -> { callState = true; talking = false; if (activePeer.isNotBlank()) entered = activePeer }
            CallStatus.CONNECTED -> { callState = true; talking = false; page = 0; if (activePeer.isNotBlank()) entered = activePeer }
            CallStatus.FAILED -> { callState = false; talking = false; failureAnnouncer.announce() }
        }
    }
    LaunchedEffect(mesh) {
        mesh.incomingMessages.collect { incoming ->
            if (isValidNumber(incoming.sender) && incoming.body.isNotBlank() && chatMessages.none { it.id == incoming.id }) {
                chatMessages = (chatMessages + ChatMessage(incoming.id, incoming.sender, incoming.body, incoming.receivedAt, false, TextDeliveryState.DELIVERED, incoming.wireId)).takeLast(CHAT_HISTORY_LIMIT)
                saveChatMessages(prefs, chatMessages)
            }
        }
    }
    LaunchedEffect(mesh) {
        mesh.deliveryUpdates.collect { update ->
            val updated = chatMessages.map { message ->
                if (message.id != update.messageId) message else {
                    val nextState = when {
                        message.deliveryState == TextDeliveryState.DELIVERED -> message.deliveryState
                        message.deliveryState == TextDeliveryState.FAILED && update.state != TextDeliveryState.DELIVERED -> message.deliveryState
                        message.deliveryState == TextDeliveryState.SENT && update.state == TextDeliveryState.QUEUED -> message.deliveryState
                        else -> update.state
                    }
                    message.copy(deliveryState = nextState)
                }
            }
            if (updated != chatMessages) {
                chatMessages = updated
                saveChatMessages(prefs, chatMessages)
            }
        }
    }
    LaunchedEffect(Unit) {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            else add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(needed.toTypedArray())
        } else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    MaterialTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(Modifier.fillMaxSize(), color = Ink) {
                Column(Modifier.fillMaxSize()) {
                    Header(myNumber)
                    when (page) {
                        0 -> CallScreen(
                            myNumber = myNumber,
                            entered = entered,
                            callState = callState,
                            callStatus = callStatus,
                            talking = talking,
                            networkStatus = networkStatus,
                            connected = connected,
                            nearbyDevices = nearbyDevices,
                            remoteNumbers = remoteNumbers,
                            batteryOptimizationExempt = batteryOptimizationExempt,
                            onRequestBatteryOptimizationExemption = onRequestBatteryOptimizationExemption,
                            onDigit = { if (entered.length < 9) entered += it },
                            onDelete = { if (entered.isNotEmpty()) entered = entered.dropLast(1) },
                            onCall = { if (mesh.startCall(entered)) { callState = true; talking = false } },
                            onEnd = { mesh.endCall(); callState = false; talking = false },
                            onTalk = { active ->
                                if (!active) { talking = false; mesh.stopTalking() }
                                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { talking = true; mesh.startTalking() }
                                else microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onConnect = { mesh.connect(it) },
                            onRefresh = { mesh.refresh() },
                            onClear = { entered = "" }
                        )
                        1 -> ContactsScreen(
                            contacts = contacts,
                            editMode = editMode,
                            onAdd = { editor = null; validation = ""; showEditor = true },
                            onToggleEdit = { editMode = !editMode },
                            onContact = { contact -> entered = contact.number; page = 0; if (mesh.startCall(contact.number)) callState = true },
                            onEdit = { contact -> editor = contact; validation = ""; showEditor = true },
                            onDelete = { contact -> contacts = contacts.filterNot { it.number == contact.number }; saveContacts(prefs, contacts) }
                        )
                        else -> if (selectedChat == null) {
                            InboxScreen(
                                messages = chatMessages,
                                contacts = contacts,
                                networkStatus = networkStatus,
                                connected = connected,
                                onNewChat = { newChatNumber = ""; newChatError = ""; showNewChat = true },
                                onOpenChat = { selectedChat = it }
                            )
                        } else {
                            val peer = selectedChat!!
                            ChatScreen(
                                peerNumber = peer,
                                contactName = contacts.firstOrNull { it.number == peer }?.name,
                                messages = chatMessages.filter { it.peerNumber == peer }.sortedBy { it.timestamp },
                                networkStatus = networkStatus,
                                connected = connected,
                                onBack = { selectedChat = null },
                                onSend = { body ->
                                    val messageId = UUID.randomUUID().toString()
                                    if (mesh.sendTextMessage(peer, body, messageId)) {
                                        val outgoing = ChatMessage(messageId, peer, body, System.currentTimeMillis(), true, TextDeliveryState.QUEUED)
                                        chatMessages = (chatMessages + outgoing).takeLast(CHAT_HISTORY_LIMIT)
                                        saveChatMessages(prefs, chatMessages)
                                        true
                                    } else false
                                },
                                onRetry = { messageId, body ->
                                    if (mesh.sendTextMessage(peer, body, messageId)) {
                                        chatMessages = chatMessages.map { if (it.id == messageId) it.copy(deliveryState = TextDeliveryState.QUEUED) else it }
                                        saveChatMessages(prefs, chatMessages)
                                    }
                                }
                            )
                        }
                    }
                    BottomNav(page, onPage = { selected -> page = selected; if (selected != 2) selectedChat = null })
                }
                if (!welcomed) {
                    AlertDialog(
                        onDismissRequest = { welcomed = true; prefs.edit().putBoolean("welcomed", true).apply() },
                        containerColor = Panel,
                        title = { Text("أهلاً بك في Shadow Phone", color = TextMain, fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                Text("رقمك المحلي جاهز", color = Muted)
                                Spacer(Modifier.height(12.dp))
                                Text(myNumber, color = Lime, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                                Spacer(Modifier.height(12.dp))
                                Text("الرقم محفوظ على هذا الجهاز. اسمح بإذن الأجهزة القريبة للاتصال والرسائل؛ إذن الميكروفون يُطلب عند استخدام PTT.", color = Muted, fontSize = 13.sp)
                            }
                        },
                        confirmButton = { TextButton(onClick = { welcomed = true; prefs.edit().putBoolean("welcomed", true).apply() }) { Text("ابدأ", color = Lime) } }
                    )
                }
                if (showEditor) {
                    ContactEditorDialog(
                        initial = editor,
                        error = validation,
                        onDismiss = { showEditor = false },
                        onSave = { name, number ->
                            if (name.isBlank()) validation = "أدخل اسماً لجهة الاتصال"
                            else if (!isValidNumber(number)) validation = "الرقم يجب أن يتكون من 9 خانات ويبدأ بـ 73"
                            else if (contacts.any { it.number == number && it.number != editor?.number }) validation = "هذا الرقم محفوظ مسبقاً"
                            else {
                                val updated = if (editor == null) contacts + Contact(name.trim(), number) else contacts.map { if (it.number == editor!!.number) Contact(name.trim(), number) else it }
                                contacts = updated.sortedBy { it.name.lowercase() }
                                saveContacts(prefs, contacts)
                                showEditor = false
                            }
                        }
                    )
                }
                if (showNewChat) {
                    AlertDialog(
                        onDismissRequest = { showNewChat = false },
                        containerColor = Panel,
                        title = { Text("محادثة جديدة", color = TextMain, fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                Text("أدخل رقم Shadow Phone المكوّن من 9 خانات ويبدأ بـ 73.", color = Muted, fontSize = 12.sp)
                                Spacer(Modifier.height(12.dp))
                                InputField(newChatNumber, { if (it.length <= 9 && it.all(Char::isDigit)) newChatNumber = it }, "73•••••••", KeyboardType.Phone)
                                if (newChatError.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(newChatError, color = Danger, fontSize = 12.sp) }
                                if (!connected) { Spacer(Modifier.height(8.dp)); Text("يمكن فتح المحادثة الآن، لكن الإرسال يتطلب اتصال Wi‑Fi Direct.", color = Muted, fontSize = 11.sp) }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                if (!isValidNumber(newChatNumber) || newChatNumber == myNumber) newChatError = "أدخل رقم جهاز آخر؛ يجب أن يبدأ بـ 73 ويتكون من 9 خانات"
                                else { selectedChat = newChatNumber; page = 2; showNewChat = false }
                            }) { Text("فتح المحادثة", color = Lime, fontWeight = FontWeight.Bold) }
                        },
                        dismissButton = { TextButton(onClick = { showNewChat = false }) { Text("إلغاء", color = Muted) } }
                    )
                }
            }
        }
    }
}

private fun createAndSaveNumber(prefs: android.content.SharedPreferences): String {
    val generated = "73" + (1000000 + SecureRandom().nextInt(9000000)).toString()
    prefs.edit().putString("phone_number", generated).apply()
    return generated
}

private fun isValidNumber(value: String) = value.length == 9 && value.startsWith("73") && value.all(Char::isDigit)

private fun loadContacts(prefs: android.content.SharedPreferences): List<Contact> = runCatching {
    val array = JSONArray(prefs.getString("contacts", "[]"))
    (0 until array.length()).mapNotNull { i ->
        val item = array.getJSONObject(i)
        val name = item.optString("name")
        val number = item.optString("number")
        if (name.isNotBlank() && isValidNumber(number)) Contact(name, number) else null
    }.sortedBy { it.name.lowercase() }
}.getOrDefault(emptyList())

private fun saveContacts(prefs: android.content.SharedPreferences, contacts: List<Contact>) {
    val array = JSONArray()
    contacts.forEach { array.put(JSONObject().put("name", it.name).put("number", it.number)) }
    prefs.edit().putString("contacts", array.toString()).apply()
}

private fun loadChatMessages(prefs: android.content.SharedPreferences): List<ChatMessage> = runCatching {
    val array = JSONArray(prefs.getString("chat_messages", "[]"))
    (0 until array.length()).mapNotNull { index ->
        val item = array.getJSONObject(index)
        val peer = item.optString("peer")
        val body = item.optString("body")
        if (!isValidNumber(peer) || body.isBlank()) null else {
            val outgoing = item.optBoolean("outgoing", false)
            val persistedState = runCatching { TextDeliveryState.valueOf(item.optString("deliveryState")) }
                .getOrElse { if (outgoing) TextDeliveryState.FAILED else TextDeliveryState.DELIVERED }
            val state = if (outgoing && persistedState in setOf(TextDeliveryState.QUEUED, TextDeliveryState.SENT)) TextDeliveryState.FAILED else persistedState
            val wireId = item.optString("wireId").takeIf { it.isNotBlank() }
            ChatMessage(item.optString("id", UUID.randomUUID().toString()), peer, body,
                item.optLong("timestamp", 0L), outgoing, state, wireId)
        }
    }.takeLast(CHAT_HISTORY_LIMIT)
}.getOrDefault(emptyList())

private fun saveChatMessages(prefs: android.content.SharedPreferences, messages: List<ChatMessage>) {
    synchronized(chatStorageLock) {
        val array = JSONArray()
        messages.takeLast(CHAT_HISTORY_LIMIT).forEach { message ->
            val item = JSONObject().put("id", message.id).put("peer", message.peerNumber).put("body", message.body)
                .put("timestamp", message.timestamp).put("outgoing", message.outgoing).put("deliveryState", message.deliveryState.name)
            message.wireId?.let { item.put("wireId", it) }
            array.put(item)
        }
        prefs.edit().putString("chat_messages", array.toString()).apply()
    }
}

@Composable
private fun Header(myNumber: String) {
    Row(
        Modifier.fillMaxWidth().background(Ink).padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(Lime), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Radio, contentDescription = null, tint = Ink, modifier = Modifier.size(27.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("SHADOW PHONE", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp)
                Text("اتصال مباشر. بلا شبكة.", color = Muted, fontSize = 11.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("رقمك", color = Muted, fontSize = 10.sp)
            Text(myNumber, color = Lime, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun ColumnScope.CallScreen(
    myNumber: String,
    entered: String,
    callState: Boolean,
    callStatus: CallStatus,
    talking: Boolean,
    networkStatus: String,
    connected: Boolean,
    nearbyDevices: List<NearbyDevice>,
    remoteNumbers: Set<String>,
    batteryOptimizationExempt: Boolean,
    onRequestBatteryOptimizationExemption: () -> Unit,
    onDigit: (String) -> Unit,
    onDelete: () -> Unit,
    onCall: () -> Unit,
    onEnd: () -> Unit,
    onTalk: (Boolean) -> Unit,
    onConnect: (String) -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit
) {
    Column(Modifier.fillMaxSize().weight(1f).padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(7.dp))
        NetworkStatus(networkStatus, connected)
        if (!batteryOptimizationExempt) {
            TextButton(onClick = onRequestBatteryOptimizationExemption, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("استثناء البطارية اختياري وقد يزيد الاستهلاك", color = Cyan, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).padding(horizontal = 18.dp, vertical = 15.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (callState) "المستلم" else "أدخل رقم Shadow Phone", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text(if (entered.isEmpty()) "73•••••••" else entered, color = if (entered.isEmpty()) Color(0xFF526064) else TextMain, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                Spacer(Modifier.width(9.dp))
                if (entered.isNotEmpty()) Icon(Icons.Filled.Backspace, "مسح خانة", tint = Muted, modifier = Modifier.size(20.dp).clickable { onDelete() })
            }
            Spacer(Modifier.height(6.dp))
            Text("رقمك المحلي  $myNumber", color = Color(0xFF667478), fontSize = 10.sp)
        }
        if (connected && remoteNumbers.isNotEmpty() && !callState) {
            Spacer(Modifier.height(8.dp))
            Text("أرقام Shadow Phone القريبة: ${remoteNumbers.joinToString("، ")}", color = Cyan, fontSize = 10.sp, textAlign = TextAlign.Center)
        }
        if (!connected && !callState) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (nearbyDevices.isEmpty()) "الأجهزة القريبة" else "اختر جهازاً للربط", color = Muted, fontSize = 11.sp)
                Text("تحديث البحث", color = Lime, fontSize = 11.sp, modifier = Modifier.clickable { onRefresh() }.padding(4.dp))
            }
            if (nearbyDevices.isNotEmpty()) {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 3.dp)) {
                    items(nearbyDevices, key = { it.address }) { device ->
                        Text(device.name, color = TextMain, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(13.dp)).background(Raised).clickable { onConnect(device.address) }.padding(horizontal = 12.dp, vertical = 9.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (!callState) {
                val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
                for (row in 0..3) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                        for (col in 0..2) {
                            val key = keys[row * 3 + col]
                            if (key.isEmpty()) Spacer(Modifier.weight(1f).height(54.dp))
                            else DialKey(key, Modifier.weight(1f), onClick = { if (key == "⌫") onDelete() else onDigit(key) })
                        }
                    }
                    if (row < 3) Spacer(Modifier.height(8.dp))
                }
            } else {
                Spacer(Modifier.height(5.dp))
                Box(Modifier.size(76.dp).clip(CircleShape).background(Raised), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Contacts, null, tint = Cyan, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("${entered.ifEmpty { "73•••••••" }}", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (callStatus == CallStatus.DIALING) "جارٍ الاتصال • بانتظار رد الجهاز"
                    else "مكالمة محلية عبر Wi‑Fi Direct • صوت UDP",
                    color = if (callStatus == CallStatus.DIALING) Cyan else Muted,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(14.dp))
                PushToTalk(talking, onTalk, enabled = callStatus == CallStatus.CONNECTED)
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        callStatus == CallStatus.DIALING -> "انتظر إقرار الاتصال"
                        talking -> "أنت تتحدث"
                        else -> "اضغط مطولاً للتحدث"
                    },
                    color = if (talking) Lime else Muted,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!callState) {
                Button(onClick = onCall, enabled = isValidNumber(entered) && connected, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink, disabledContainerColor = Color(0xFF263337), disabledContentColor = Muted)) {
                    Icon(Icons.Filled.Call, null, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("اتصال", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(onClick = onEnd, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Ink)) {
                    Icon(Icons.Filled.PhoneInTalk, null, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("إنهاء المكالمة", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(18.dp)).background(Panel).clickable { onClear() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Close, "مسح الرقم", tint = Muted)
            }
        }
    }
}

@Composable
private fun NetworkStatus(status: String, connected: Boolean) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(if (connected) Color(0xFF1B2A22) else Panel).padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) Lime else Color(0xFF78878A)))
        Spacer(Modifier.width(9.dp))
        Text(status, color = if (connected) Lime else Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.WifiTethering, null, tint = if (connected) Lime else Muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun DialKey(value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(54.dp).clip(RoundedCornerShape(17.dp)).background(Panel).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(value, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PushToTalk(talking: Boolean, onTalk: (Boolean) -> Unit, enabled: Boolean) {
    val activeColor = if (talking) Lime else Color(0xFF202D2D)
    Box(
        Modifier.size(112.dp).clip(CircleShape).background(if (enabled) activeColor else Color(0xFF151D20))
            .border(2.dp, if (enabled && talking) Lime else Color(0xFF344648), CircleShape)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures(onPress = { onTalk(true); try { awaitRelease() } finally { onTalk(false) } })
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Mic, null, tint = if (enabled && talking) Ink else if (enabled) Lime else Muted, modifier = Modifier.size(33.dp))
            Text("PTT", color = if (enabled && talking) Ink else if (enabled) Lime else Muted, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun ColumnScope.ContactsScreen(
    contacts: List<Contact>, editMode: Boolean,
    onAdd: () -> Unit, onToggleEdit: () -> Unit, onContact: (Contact) -> Unit,
    onEdit: (Contact) -> Unit, onDelete: (Contact) -> Unit
) {
    Column(Modifier.fillMaxSize().weight(1f).padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("جهات الاتصال", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("${contacts.size} أسماء محفوظة على جهازك", color = Muted, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(Panel).clickable { onToggleEdit() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Edit, "تحرير القائمة", tint = if (editMode) Lime else Muted, modifier = Modifier.size(20.dp))
                }
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(Lime).clickable { onAdd() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, "إضافة جهة اتصال", tint = Ink, modifier = Modifier.size(23.dp))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (contacts.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(76.dp).clip(CircleShape).background(Panel), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Contacts, null, tint = Muted, modifier = Modifier.size(32.dp)) }
                Spacer(Modifier.height(13.dp))
                Text("دفتر الاتصال فارغ", color = TextMain, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text("أضف أصدقاءك للوصول إليهم بسرعة", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(17.dp))
                TextButton(onClick = onAdd) { Text("إضافة جهة اتصال", color = Lime) }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(contacts, key = { it.number }) { contact ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).background(Panel).clickable { if (!editMode) onContact(contact) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).clip(CircleShape).background(Raised), contentAlignment = Alignment.Center) {
                            Text(contact.name.take(1).uppercase(), color = Lime, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(contact.name, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(contact.number, color = Muted, fontSize = 12.sp, letterSpacing = 1.sp)
                        }
                        if (editMode) {
                            Icon(Icons.Filled.Edit, "تعديل", tint = Cyan, modifier = Modifier.size(20.dp).clickable { onEdit(contact) })
                            Spacer(Modifier.width(15.dp))
                            Icon(Icons.Filled.DeleteOutline, "حذف", tint = Danger, modifier = Modifier.size(21.dp).clickable { onDelete(contact) })
                        } else {
                            Icon(Icons.Filled.Call, "اتصال", tint = Lime, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.InboxScreen(
    messages: List<ChatMessage>,
    contacts: List<Contact>,
    networkStatus: String,
    connected: Boolean,
    onNewChat: () -> Unit,
    onOpenChat: (String) -> Unit
) {
    val previews = messages.groupBy { it.peerNumber }
        .mapNotNull { (_, conversation) -> conversation.maxByOrNull { it.timestamp } }
        .sortedByDescending { it.timestamp }
    Column(Modifier.fillMaxSize().weight(1f).padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(15.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("الرسائل المحلية", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Mesh SMS • تعمل عبر Wi‑Fi Direct", color = Muted, fontSize = 11.sp)
            }
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(Lime).clickable { onNewChat() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Add, "محادثة جديدة", tint = Ink, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        NetworkStatus(networkStatus, connected)
        if (previews.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(76.dp).clip(CircleShape).background(Panel), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.ChatBubbleOutline, null, tint = Muted, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text("لا توجد محادثات بعد", color = TextMain, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text("ابدأ محادثة مع رقم Shadow Phone قريب", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                TextButton(onClick = onNewChat) { Text("محادثة جديدة", color = Lime) }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
                items(previews, key = { it.peerNumber }) { latest ->
                    val contactName = contacts.firstOrNull { it.number == latest.peerNumber }?.name
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).clickable { onOpenChat(latest.peerNumber) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(Raised), contentAlignment = Alignment.Center) {
                            Text((contactName ?: latest.peerNumber.takeLast(2)).take(1).uppercase(), color = Lime, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(contactName ?: latest.peerNumber, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            if (contactName != null) Text(latest.peerNumber, color = Muted, fontSize = 10.sp)
                            Text(latest.body, color = Muted, fontSize = 12.sp, maxLines = 1)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(formatMessageTime(latest.timestamp), color = Muted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ChatScreen(
    peerNumber: String,
    contactName: String?,
    messages: List<ChatMessage>,
    networkStatus: String,
    connected: Boolean,
    onBack: () -> Unit,
    onSend: (String) -> Boolean,
    onRetry: (messageId: String, body: String) -> Unit
) {
    var draft by remember(peerNumber) { mutableStateOf("") }
    var sendError by remember(peerNumber) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().weight(1f).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(Panel).padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Raised).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ArrowBack, "العودة إلى صندوق الوارد", tint = Lime, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text(contactName ?: peerNumber, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (contactName != null) Text(peerNumber, color = Muted, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        NetworkStatus(networkStatus, connected)
        if (messages.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.ChatBubbleOutline, null, tint = Muted, modifier = Modifier.size(38.dp))
                Spacer(Modifier.height(10.dp))
                Text("ابدأ المحادثة", color = TextMain, fontWeight = FontWeight.SemiBold)
                Text("تُرسل الرسائل عبر مجموعة Wi‑Fi Direct المتصلة.", color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), reverseLayout = true, verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
                items(messages.asReversed(), key = { it.id }) { message ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outgoing) Arrangement.Start else Arrangement.End) {
                        Column(Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(18.dp)).background(if (message.outgoing) Lime else Panel).padding(horizontal = 13.dp, vertical = 9.dp)) {
                            Text(message.body, color = if (message.outgoing) Ink else TextMain, fontSize = 14.sp)
                            Spacer(Modifier.height(3.dp))
                            val receiptLabel = when (message.deliveryState) {
                                TextDeliveryState.QUEUED -> "قيد الإرسال"
                                TextDeliveryState.SENT -> "تم الإرسال • بانتظار الإقرار"
                                TextDeliveryState.DELIVERED -> "تم التسليم ✓"
                                TextDeliveryState.FAILED -> "تعذر التسليم"
                            }
                            Text(formatMessageTime(message.timestamp) + if (message.outgoing) " • $receiptLabel" else "", color = if (message.outgoing && message.deliveryState == TextDeliveryState.DELIVERED) Color(0xFF526640) else Muted, fontSize = 9.sp, modifier = Modifier.align(Alignment.End))
                            if (message.outgoing && message.deliveryState == TextDeliveryState.FAILED) {
                                Text("إعادة المحاولة", color = Danger, fontSize = 10.sp, modifier = Modifier.align(Alignment.End).clickable { onRetry(message.id, message.body) }.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
        if (!connected) {
            Text("اربط جهازاً قريباً من شاشة الاتصال قبل الإرسال.", color = Danger, fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp, bottom = 5.dp))
        }
        if (sendError.isNotEmpty()) {
            Text(sendError, color = Danger, fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp, bottom = 5.dp))
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 9.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = draft,
                onValueChange = { value ->
                    if (value.toByteArray(Charsets.UTF_8).size <= 800) { draft = value; sendError = "" }
                    else sendError = "الحد الأقصى للرسالة 800 بايت UTF‑8"
                },
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(17.dp)).background(Panel).padding(horizontal = 14.dp, vertical = 13.dp),
                textStyle = TextStyle(color = TextMain, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                maxLines = 4,
                decorationBox = { inner ->
                    if (draft.isEmpty()) Text("اكتب رسالة محلية…", color = Muted, fontSize = 13.sp)
                    inner()
                }
            )
            Box(Modifier.size(49.dp).clip(RoundedCornerShape(16.dp)).background(if (connected && draft.isNotBlank()) Lime else Raised).clickable(enabled = connected && draft.isNotBlank()) {
                if (onSend(draft)) draft = "" else sendError = "لم تُرسل الرسالة؛ تحقق من الاتصال وحاول مجدداً"
            }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Send, "إرسال", tint = if (connected && draft.isNotBlank()) Ink else Muted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun formatMessageTime(timestamp: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

@Composable
private fun BottomNav(selected: Int, onPage: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        NavItem("الاتصال", Icons.Filled.Call, selected == 0) { onPage(0) }
        NavItem("الأسماء", Icons.Filled.Contacts, selected == 1) { onPage(1) }
        NavItem("الرسائل", Icons.Filled.ChatBubbleOutline, selected == 2) { onPage(2) }
    }
}

@Composable
private fun NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(86.dp).clip(RoundedCornerShape(14.dp)).clickable { onClick() }.padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = if (selected) Lime else Muted, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = if (selected) Lime else Muted, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun ContactEditorDialog(initial: Contact?, error: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var number by remember(initial) { mutableStateOf(initial?.number.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(if (initial == null) "جهة اتصال جديدة" else "تعديل جهة الاتصال", color = TextMain, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("الاسم", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                InputField(name, { name = it }, "مثال: أحمد")
                Spacer(Modifier.height(14.dp))
                Text("رقم Shadow Phone", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                InputField(number, { if (it.length <= 9 && it.all(Char::isDigit)) number = it }, "73•••••••", KeyboardType.Phone)
                Spacer(Modifier.height(7.dp))
                Text("9 خانات، ويبدأ بـ 73", color = Muted, fontSize = 10.sp)
                if (error.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(error, color = Danger, fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, number) }) { Text("حفظ", color = Lime, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء", color = Muted) } }
    )
}

@Composable
private fun InputField(value: String, onValue: (String) -> Unit, placeholder: String, keyboard: KeyboardType = KeyboardType.Text) {
    Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(13.dp)).background(Raised).padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(value, onValue, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard), textStyle = TextStyle(color = TextMain, fontSize = 14.sp), decorationBox = { inner ->
            if (value.isEmpty()) Text(placeholder, color = Color(0xFF667478), fontSize = 13.sp)
            inner()
        })
    }
}
