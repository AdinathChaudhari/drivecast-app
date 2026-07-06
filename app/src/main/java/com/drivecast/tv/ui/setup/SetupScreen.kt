package com.drivecast.tv.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.drivecast.tv.LocalAppContainer
import com.drivecast.tv.data.DiscoveredServer
import com.drivecast.tv.ui.theme.Accent
import com.drivecast.tv.ui.theme.Background
import com.drivecast.tv.ui.theme.ErrorRed
import com.drivecast.tv.ui.theme.SurfaceVariant
import com.drivecast.tv.ui.theme.TextPrimary
import com.drivecast.tv.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

private enum class SetupStep { DISCOVER, TOKEN }

@Composable
fun SetupScreen(onPaired: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(SetupStep.DISCOVER) }
    var scanning by remember { mutableStateOf(true) }
    var discovered by remember { mutableStateOf<List<DiscoveredServer>>(emptyList()) }
    var manualIp by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        scanning = true
        discovered = runCatching { container.discovery.scan() }.getOrDefault(emptyList())
        scanning = false
    }

    fun connectManual() {
        val ip = manualIp.trim()
        if (ip.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            val server = container.discovery.probe(ip)
            busy = false
            if (server != null) {
                baseUrl = server.baseUrl
                step = SetupStep.TOKEN
            } else {
                error = "No drivecast server answered at $ip:8737."
            }
        }
    }

    fun pair() {
        val base = baseUrl ?: return
        val t = token.trim()
        if (t.isEmpty()) {
            error = "Enter the access token from the drivecast Remote Access screen."
            return
        }
        busy = true
        error = null
        scope.launch {
            container.repository.configure(base, t)
            val result = runCatching { container.repository.validateRemote() }
            busy = false
            result.onSuccess { resp ->
                when {
                    resp.isSuccessful -> {
                        container.configStore.save(base, t)
                        onPaired()
                    }
                    resp.code() == 403 ->
                        error = "Remote access is off. Turn it on in drivecast's Remote Access settings."
                    resp.code() == 401 -> error = "Wrong token. Check the code in drivecast and re-enter it."
                    else -> error = "Server rejected the pairing (HTTP ${resp.code()})."
                }
            }.onFailure {
                error = "Couldn't reach $base. Is the server running on this network?"
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 64.dp, vertical = 48.dp),
        ) {
            Text("drivecast", style = MaterialTheme.typography.displaySmall, color = Accent)
            Spacer(Modifier.height(4.dp))
            Text(
                when (step) {
                    SetupStep.DISCOVER -> "Find your server"
                    SetupStep.TOKEN -> "Enter the access token"
                },
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
            )
            Spacer(Modifier.height(24.dp))

            when (step) {
                SetupStep.DISCOVER -> DiscoverStep(
                    scanning = scanning,
                    discovered = discovered,
                    manualIp = manualIp,
                    busy = busy,
                    onManualIpChange = { manualIp = it },
                    onPick = { server ->
                        baseUrl = server.baseUrl
                        step = SetupStep.TOKEN
                        error = null
                    },
                    onConnectManual = ::connectManual,
                    onRescan = {
                        scope.launch {
                            scanning = true
                            discovered = runCatching { container.discovery.scan() }.getOrDefault(emptyList())
                            scanning = false
                        }
                    },
                )

                SetupStep.TOKEN -> TokenStep(
                    baseUrl = baseUrl.orEmpty(),
                    token = token,
                    busy = busy,
                    onTokenChange = { token = it },
                    onPair = ::pair,
                    onBack = { step = SetupStep.DISCOVER; error = null },
                )
            }

            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = ErrorRed, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun DiscoverStep(
    scanning: Boolean,
    discovered: List<DiscoveredServer>,
    manualIp: String,
    busy: Boolean,
    onManualIpChange: (String) -> Unit,
    onPick: (DiscoveredServer) -> Unit,
    onConnectManual: () -> Unit,
    onRescan: () -> Unit,
) {
    if (scanning) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.width(28.dp).height(28.dp))
            Spacer(Modifier.width(12.dp))
            Text("Scanning your network…", color = TextSecondary)
        }
        return
    }

    if (discovered.isNotEmpty()) {
        Text("Servers found", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().height(220.dp),
        ) {
            items(discovered) { server ->
                Button(
                    onClick = { onPick(server) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${server.ip}:${server.port}")
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    } else {
        Text("No servers found automatically.", color = TextSecondary)
        Spacer(Modifier.height(16.dp))
    }

    Text("Or enter the server IP", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TvTextField(
            value = manualIp,
            onValueChange = onManualIpChange,
            placeholder = "192.168.1.50",
            keyboardType = KeyboardType.Uri,
            modifier = Modifier.width(280.dp),
        )
        Spacer(Modifier.width(12.dp))
        Button(onClick = onConnectManual, enabled = !busy) { Text("Connect") }
        Spacer(Modifier.width(12.dp))
        Button(onClick = onRescan, enabled = !busy) { Text("Rescan") }
    }
}

@Composable
private fun TokenStep(
    baseUrl: String,
    token: String,
    busy: Boolean,
    onTokenChange: (String) -> Unit,
    onPair: () -> Unit,
    onBack: () -> Unit,
) {
    Text(baseUrl, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))
    TvTextField(
        value = token,
        onValueChange = onTokenChange,
        placeholder = "Access token",
        keyboardType = KeyboardType.Password,
        modifier = Modifier.width(360.dp),
    )
    Spacer(Modifier.height(20.dp))
    Row {
        Button(onClick = onPair, enabled = !busy) { Text(if (busy) "Pairing…" else "Pair") }
        Spacer(Modifier.width(12.dp))
        Button(onClick = onBack, enabled = !busy) { Text("Back") }
    }
}

/** A minimal focusable text field (foundation only) suited to D-pad + on-screen keyboard. */
@Composable
private fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceVariant)
            .border(1.dp, Accent, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.isEmpty()) {
            Text(placeholder, color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
