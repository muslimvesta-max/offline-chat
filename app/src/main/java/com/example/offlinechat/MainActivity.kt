package com.example.offlinechat

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {
    private val SERVICE_ID = "com.example.offlinechat.SERVICE"
    private var connectionsClient: ConnectionsClient? = null
    private var connectedEndpointId: String? = null
    private val messages = mutableStateListOf<ChatMessage>()
    private var connectionStatus by mutableStateOf("Поиск устройств...")

    data class ChatMessage(val text: String, val isMe: Boolean, val isFile: Boolean = false)

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { sendVideoFile(it) }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionsClient?.acceptConnection(endpointId, payloadCallback)
            connectionStatus = "Подключение..."
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpointId = endpointId
                connectionStatus = "В сети (Подключено)"
                connectionsClient?.stopDiscovery()
                connectionsClient?.stopAdvertising()
            } else {
                connectionStatus = "Ошибка подключения"
            }
        }
        override fun onDisconnected(endpointId: String) {
            connectedEndpointId = null
            connectionStatus = "Соединение разорвано"
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let {
                    messages.add(ChatMessage(String(it, StandardCharsets.UTF_8), isMe = false))
                }
            } else if (payload.type == Payload.Type.FILE) {
                messages.add(ChatMessage("Получен видеофайл", isMe = false, isFile = true))
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectionsClient = Nearby.getConnectionsClient(this)
        checkPermissionsAndStart()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatScreen(
                        status = connectionStatus,
                        messages = messages,
                        onSendMessage = { text ->
                            messages.add(ChatMessage(text, isMe = true))
                            connectedEndpointId?.let {
                                connectionsClient?.sendPayload(it, Payload.fromBytes(text.toByteArray(StandardCharsets.UTF_8)))
                            }
                        },
                        onSendVideoClick = { filePickerLauncher.launch("video/*") }
                    )
                }
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            startDiscoveryAndAdvertising()
        }.launch(permissions.toTypedArray())
    }

    private fun startDiscoveryAndAdvertising() {
        val strategy = Strategy.P2P_CLUSTER
        connectionsClient?.startAdvertising("User_${(0..999).random()}", SERVICE_ID, connectionLifecycleCallback, AdvertisingOptions.Builder(strategy).build())
        connectionsClient?.startDiscovery(SERVICE_ID, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                connectionsClient?.requestConnection("ChatUser", endpointId, connectionLifecycleCallback)
            }
            override fun onEndpointLost(endpointId: String) {}
        }, DiscoveryOptions.Builder(strategy).build())
    }

    private fun sendVideoFile(uri: Uri) {
        try {
            contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
                messages.add(ChatMessage("Отправка видео...", isMe = true, isFile = true))
                connectedEndpointId?.let { connectionsClient?.sendPayload(it, Payload.fromFile(pfd)) }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
}

@Composable
fun ChatScreen(status: String, messages: List<MainActivity.ChatMessage>, onSendMessage: (String) -> Unit, onSendVideoClick: () -> Unit) {
    var textState by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(text = "Offline Chat", style = MaterialTheme.typography.titleMedium)
                Text(text = status, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { msg ->
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (msg.isMe) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(shape = RoundedCornerShape(12.dp), color = if (msg.isMe) Color(0xFFE3F2FD) else Color(0xFFF1F3F4)) {
                        Text(text = msg.text, modifier = Modifier.padding(10.dp), fontSize = 15.sp)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSendVideoClick) { Icon(Icons.Default.VideoFile, "Видео") }
            OutlinedTextField(value = textState, onValueChange = { textState = it }, modifier = Modifier.weight(1f), placeholder = { Text("Сообщение...") })
            IconButton(onClick = { if (textState.isNotBlank()) { onSendMessage(textState); textState = "" } }) {
                Icon(Icons.Default.Send, "Отправить")
            }
        }
    }
}

