package no.lionflow.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import no.lionflow.app.data.AppSettings
import no.lionflow.app.route.RouteService
import no.lionflow.app.ui.AppViewModel
import no.lionflow.app.ui.components.Divider
import no.lionflow.app.ui.components.GroupCard
import no.lionflow.app.ui.components.SectionTitle
import no.lionflow.app.ui.components.ToggleRow
import no.lionflow.app.ui.l10n.Text
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type

/** Settings: record routes with the phone's GPS while connected to the car over Bluetooth. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RouteTrackingSettings(vm: AppViewModel, s: AppSettings) {
    val c = LocalLf.current
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) } // re-read permissions after each request
    var pick by remember { mutableStateOf(false) }
    fun set(block: (AppSettings) -> AppSettings) = vm.settings.update(block)

    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        if (RouteService.hasLocation(ctx) && !RouteService.hasBackgroundLocation(ctx) && Build.VERSION.SDK_INT >= 29)
            bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (RouteService.hasBluetooth(ctx) && s.routeBtAddr.isEmpty()) pick = true
    }
    fun askAll() {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        launcher.launch(perms.toTypedArray())
    }

    tick.let { } // read so permission state recomposes
    val loc = RouteService.hasLocation(ctx)
    val bg = RouteService.hasBackgroundLocation(ctx)
    val bt = RouteService.hasBluetooth(ctx)

    SectionTitle("Route tracking")
    GroupCard {
        // Scrolled into view and flashed when another screen sends you here ("Open Route tracking in Settings").
        val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
        val flash = remember { androidx.compose.animation.core.Animatable(0f) }
        var rowSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        val density = androidx.compose.ui.platform.LocalDensity.current
        val target = vm.focusSetting
        LaunchedEffect(target) {
            if (target != "routeTracking") return@LaunchedEffect
            kotlinx.coroutines.delay(350)   // let the screen finish drawing first
            val pad = with(density) { 140.dp.toPx() }
            bring.bringIntoView(androidx.compose.ui.geometry.Rect(0f, -pad, rowSize.width.toFloat(), rowSize.height + pad))
            // Highlight for 2 seconds: quick fade in, then a slow fade out.
            flash.snapTo(0f)
            flash.animateTo(1f, androidx.compose.animation.core.tween(250))
            flash.animateTo(0f, androidx.compose.animation.core.tween(1750))
            // Cleared last: clearing it changes the key above, which would cancel this effect half way.
            vm.focusSetting = null
        }
        Box(Modifier.fillMaxWidth().bringIntoViewRequester(bring).onSizeChanged { rowSize = it }
            .drawBehind { drawRect(c.accent.copy(alpha = 0.45f * flash.value)) }) {
            ToggleRow("Record routes", "Uses your phone's GPS while it's connected to the car over Bluetooth. Routes stay on this phone.", s.routeTracking) { on ->
                set { it.copy(routeTracking = on) }
                if (on) askAll() else RouteService.stop(ctx)
            }
        }
        if (s.routeTracking) {
            Column(Modifier.fillMaxWidth().clickable { if (bt) pick = true else askAll() }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Car's Bluetooth", style = Type.label, color = c.text)
                Text(s.routeBtName.ifEmpty { "Tap to choose your car" }, style = Type.small, color = if (s.routeBtName.isEmpty()) c.accentInk else c.muted)
            }
            val missing = listOfNotNull(
                "location".takeIf { !loc },
                "location all the time".takeIf { loc && !bg },
                "nearby devices".takeIf { !bt },
            )
            if (missing.isNotEmpty()) {
                Divider()
                Text("Needs permission: ${missing.joinToString(", ")} — tap to fix", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (loc && !bg && Build.VERSION.SDK_INT >= 30) {
                            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } else askAll()
                    }.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            Divider()
            Text("Delete all recorded routes", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                modifier = Modifier.fillMaxWidth().clickable { no.lionflow.app.route.Routes.deleteAll(ctx); vm.reloadRoutes() }.padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }

    if (pick) BluetoothPicker(onDismiss = { pick = false }) { addr, name ->
        set { it.copy(routeBtAddr = addr, routeBtName = name) }
        pick = false
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun BluetoothPicker(onDismiss: () -> Unit, onPick: (String, String) -> Unit) {
    val c = LocalLf.current
    val ctx = LocalContext.current
    val devices = remember {
        try {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .map { it.address to (it.name ?: it.address) }.sortedBy { it.second.lowercase() }
        } catch (_: Exception) { emptyList() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose your car") },
        text = {
            if (devices.isEmpty()) {
                Text("No paired Bluetooth devices. Pair your phone with the car first.", style = Type.small, color = c.muted)
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(devices, key = { it.first }) { (address, name) ->
                        Text(
                            name,
                            style = Type.body,
                            color = c.text,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onPick(address, name) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
