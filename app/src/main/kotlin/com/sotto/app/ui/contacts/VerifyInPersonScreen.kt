package com.sotto.app.ui.contacts

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sotto.app.crypto.InPersonResult
import com.sotto.app.ui.components.AuroraBackground
import com.sotto.app.ui.components.AuroraStyle
import com.sotto.app.ui.components.GlassCard
import com.sotto.app.ui.components.GradientAvatar
import com.sotto.app.ui.components.QrCodeImage
import com.sotto.app.ui.components.SectionLabel
import com.sotto.app.ui.components.SoftButton
import com.sotto.app.ui.components.SoftButtonTone
import com.sotto.app.ui.theme.Aurora

/** Where the scanner hands a scanned "Verify in person" code back to this screen. */
const val SCANNED_VERIFY_CODE_KEY = "scanned_verify_code"

/**
 * Verifying a contact face to face: hold the two phones together (NFC, both phones at once), or
 * scan each other's QR codes (each phone checks the one it scanned). Only this marks a contact
 * verified in person; see InPersonVerifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyInPersonScreen(
    onBack: () -> Unit,
    onScan: () -> Unit,
    scannedCode: String?,
    onScannedCodeHandled: () -> Unit,
    /** Can't meet: call them, and the emoji check opens once the call connects (ADR-31). */
    onVerifyByPhone: (contactId: String, name: String) -> Unit = { _, _ -> },
    viewModel: VerifyInPersonViewModel = hiltViewModel(),
) {
    val contact by viewModel.contact.collectAsState()
    val myQr by viewModel.myQr.collectAsState()
    val outcome by viewModel.outcome.collectAsState()
    val checking by viewModel.checking.collectAsState()
    val name = contact?.displayName ?: "your contact"
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val nfcAdapter = remember { NfcAdapter.getDefaultAdapter(context) }
    var nfcOn by remember { mutableStateOf(nfcAdapter?.isEnabled == true) }

    LaunchedEffect(scannedCode) {
        scannedCode?.let {
            viewModel.onScanned(it)
            onScannedCodeHandled()
        }
    }

    // In front: answer and read NFC taps, and full brightness so the QR code reads easily
    // from the other phone's camera.
    val myCode by viewModel.myCode.collectAsState()
    com.sotto.app.nfc.InPersonNfcEffect(myCode)
    // The QR code changes every second while it's on screen, as BankID's does.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.refreshQr()
                kotlinx.coroutines.delay(1000)
            }
        }
    }
    DisposableEffect(lifecycleOwner, activity) {
        val window = activity?.window
        val previousBrightness = window?.attributes?.screenBrightness
        fun restore() {
            if (window != null && previousBrightness != null) {
                window.attributes = window.attributes.apply { screenBrightness = previousBrightness }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    nfcOn = nfcAdapter?.isEnabled == true
                    window?.attributes = window?.attributes?.apply {
                        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                    }
                }
                Lifecycle.Event.ON_PAUSE -> restore()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            restore()
        }
    }

    outcome?.let { OutcomeDialog(it, name, onDismiss = viewModel::dismissOutcome, onDone = onBack) }

    val colors = MaterialTheme.colorScheme
    AuroraBackground(Modifier.fillMaxSize(), style = AuroraStyle.Header) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = { Text("Verify in person") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatusCard(
                    name = name,
                    seed = viewModel.contactId,
                    verified = contact?.verifiedInPerson == true,
                    byCall = contact?.verifiedByCall == true,
                )

                Column {
                    SectionLabel("Hold your phones together", Modifier.padding(start = 4.dp))
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Nfc, contentDescription = null, tint = colors.primary, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(
                                text = when {
                                    nfcAdapter == null -> "This phone has no NFC. Scan each other's codes below instead."
                                    !nfcOn -> "NFC is off. Turn it on to tap phones, or scan each other's codes below."
                                    else -> "On $name's phone, open your chat and Verify in person too. Then hold the " +
                                        "backs of the phones together: one tap checks both phones."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (nfcAdapter != null && !nfcOn) {
                            Spacer(Modifier.height(12.dp))
                            SoftButton(
                                text = "Turn on NFC",
                                onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                Column {
                    SectionLabel("Or scan each other's codes", Modifier.padding(start = 4.dp))
                    GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            myQr?.let { QrCodeImage(content = it, size = 220.dp) }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "$name scans this code with Verify in person on their phone. " +
                                    "Then you scan theirs. It changes every second, so a picture of it won't do.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(16.dp))
                            SoftButton(
                                text = "Scan $name's code",
                                onClick = onScan,
                                icon = Icons.Rounded.QrCodeScanner,
                                tone = SoftButtonTone.Accent,
                                enabled = !checking,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                if (contact?.verifiedInPerson != true) {
                    Column {
                        SectionLabel("Can't meet?", Modifier.padding(start = 4.dp))
                        GlassCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.PhoneInTalk,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(28.dp),
                                )
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    "Call $name and read seven emoji to each other. It's weaker than meeting: " +
                                        "the chat will say \"Verified by phone only\".",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            SoftButton(
                                text = "Verify $name by phone",
                                onClick = { onVerifyByPhone(viewModel.contactId, name) },
                                icon = Icons.Rounded.PhoneInTalk,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                Text(
                    text = "Only do this with $name in front of you. A code sent as a picture or through " +
                        "another app could come from anyone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun StatusCard(name: String, seed: String, verified: Boolean, byCall: Boolean) {
    val tint = when {
        verified -> Aurora.colors.success
        byCall -> MaterialTheme.colorScheme.primary
        else -> Aurora.colors.warning
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradientAvatar(name = name, seed = seed, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when {
                            verified -> Icons.Rounded.VerifiedUser
                            byCall -> Icons.Rounded.PhoneInTalk
                            else -> Icons.Rounded.GppMaybe
                        },
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            verified -> "Verified in person"
                            byCall -> "Verified by phone only"
                            else -> "Not verified"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = tint,
                    )
                }
            }
        }
        if (verified || byCall) {
            Spacer(Modifier.height(10.dp))
            Text(
                if (verified) {
                    "You've checked $name's keys face to face. You can check again any time."
                } else {
                    "You verified $name by phone. Meeting is stronger proof: verify in person when you can."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OutcomeDialog(outcome: VerifyOutcome, name: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (icon: ImageVector, tint: Color, title: String, body: String) = when (outcome.result) {
        InPersonResult.Verified -> Quad(
            Icons.Rounded.VerifiedUser,
            Aurora.colors.success,
            "$name is verified in person",
            "The keys on their phone are the ones this chat uses. " + if (outcome.viaNfc) {
                "Their phone checked yours with the same tap."
            } else {
                "Let them scan your code too, so their phone trusts you as well."
            },
        )
        InPersonResult.OtherAccount -> Quad(
            Icons.Rounded.GppBad,
            colors.error,
            "This is someone else's code",
            "It belongs to a different Sotto account from this chat. If this really is $name's phone, " +
                "this chat isn't with them: don't trust it. Otherwise, check that each phone opened the " +
                "other person's chat.",
        )
        InPersonResult.KeysDiffer -> Quad(
            Icons.Rounded.GppBad,
            colors.error,
            "The keys don't match",
            "$name's phone has different keys from the ones this chat uses, so someone may be in the " +
                "middle, such as the relay. Don't trust this chat. Remove the contact, then add $name " +
                "again by scanning their share code.",
        )
        InPersonResult.CantCheck -> Quad(
            Icons.Rounded.WifiOff,
            Aurora.colors.warning,
            "Couldn't check right now",
            "$name's keys may have changed since your phone last saw them, and the relay can't be " +
                "reached to confirm that. Connect to the internet and try again.",
        )
        InPersonResult.NotLive -> Quad(
            Icons.Rounded.GppMaybe,
            Aurora.colors.warning,
            "That code isn't live",
            "It's the right person, but the code is a picture of their screen, not the code on their " +
                "phone right now (it changes every second). Scan the code on their phone while you're together.",
        )
        InPersonResult.OlderApp -> Quad(
            Icons.Rounded.GppMaybe,
            Aurora.colors.warning,
            "Their Sotto is older",
            "Their keys match, but their Sotto doesn't make the live code yet, so a scan can't prove " +
                "it's their phone. Update both phones to the latest Sotto, or hold the phones together.",
        )
        InPersonResult.NotAContact -> Quad(
            Icons.Rounded.GppMaybe,
            Aurora.colors.warning,
            "$name isn't a contact",
            "This phone has no keys for $name to check against.",
        )
    }
    val done = outcome.result == InPersonResult.Verified
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null, tint = tint) },
        title = { Text(title, textAlign = TextAlign.Center) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); if (done) onDone() }) { Text(if (done) "Done" else "OK") }
        },
        dismissButton = if (done && !outcome.viaNfc) {
            { TextButton(onClick = onDismiss) { Text("Show my code") } }
        } else {
            null
        },
    )
}

private data class Quad(val icon: ImageVector, val tint: Color, val title: String, val body: String)
