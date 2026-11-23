package com.sotto.app.ui.contacts

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.sotto.app.domain.model.Contact
import com.sotto.app.ui.components.AppSnackbarHost
import com.sotto.app.ui.components.AuroraTextField
import com.sotto.app.ui.components.ShareCodeActions
import com.sotto.app.ui.components.AuroraBackground
import com.sotto.app.ui.components.AuroraStyle
import com.sotto.app.ui.components.GlassCard
import com.sotto.app.ui.components.GradientAvatar
import com.sotto.app.ui.components.GradientButton
import com.sotto.app.ui.components.QrCodeImage
import com.sotto.app.ui.components.SectionLabel
import com.sotto.app.ui.components.SoftButton
import com.sotto.app.ui.components.SoftButtonTone
import com.sotto.app.ui.theme.Aurora

/**
 * @param scannedShareCode a code [ScanQrScreen] handed back, added right away; call
 *   [onScannedShareCodeHandled] once it's taken so it isn't added twice.
 * @param prefilledShareCode a code from an invite link someone sent: only filled in, since a
 *   link can come from anyone and adding someone also sends them your name.
 */
@Composable
fun AddContactScreen(
    onBack: () -> Unit,
    onContactAdded: (conversationId: String, contactId: String) -> Unit,
    onScanQr: () -> Unit,
    scannedShareCode: String?,
    onScannedShareCodeHandled: () -> Unit,
    prefilledShareCode: String? = null,
    onPrefilledShareCodeHandled: () -> Unit = {},
    viewModel: AddContactViewModel = hiltViewModel(),
) {
    val shareCodeInput by viewModel.shareCodeInput.collectAsState()
    val state by viewModel.state.collectAsState()
    val myShareCode by viewModel.myShareCode.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val hasMyName by viewModel.hasMyName.collectAsState()
    val myCode by viewModel.myCode.collectAsState()
    // Two phones held together here add each other, verified in person.
    com.sotto.app.nfc.InPersonNfcEffect(myCode)

    LaunchedEffect(state) {
        if (state is AddContactState.Success) {
            val success = state as AddContactState.Success
            onContactAdded(success.conversationId, success.contact.userId)
            viewModel.resetState()
        }
    }

    LaunchedEffect(scannedShareCode) {
        val code = scannedShareCode ?: return@LaunchedEffect
        onScannedShareCodeHandled()
        // Their full code, read off their phone: added and verified in person at once.
        val inPerson = com.sotto.app.crypto.InPersonCode.parse(code)
        if (inPerson != null) viewModel.addInPerson(inPerson) else viewModel.submitScannedShareCode(code)
    }

    LaunchedEffect(prefilledShareCode) {
        val code = prefilledShareCode ?: return@LaunchedEffect
        onPrefilledShareCodeHandled()
        viewModel.updateShareCode(code)
    }

    AddContactContent(
        shareCodeInput = shareCodeInput,
        state = state,
        myShareCode = myShareCode,
        contacts = contacts,
        onBack = onBack,
        onShareCodeChange = viewModel::updateShareCode,
        onSubmit = viewModel::submitShareCode,
        onScanQr = onScanQr,
        showNameHint = !hasMyName,
        onRemoveContact = { viewModel.removeContact(it.userId) },
        myQr = myCode?.encode(),
        onOpenChat = viewModel::openChat,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactContent(
    shareCodeInput: String,
    state: AddContactState,
    myShareCode: String?,
    contacts: List<Contact>,
    onBack: () -> Unit,
    onShareCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onScanQr: () -> Unit,
    showNameHint: Boolean = false,
    onRemoveContact: (Contact) -> Unit = {},
    /** This phone's in-person code for "Show my QR code"; the share code when not loaded yet. */
    myQr: String? = null,
    /** Opens the chat with someone already added. */
    onOpenChat: (Contact) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var removing by remember { mutableStateOf<Contact?>(null) }
    val nfcContext = LocalContext.current
    val nfcAdapter = remember { android.nfc.NfcAdapter.getDefaultAdapter(nfcContext) }
    var nfcOn by remember { mutableStateOf(nfcAdapter?.isEnabled == true) }
    // NFC may have been turned on in Settings meanwhile.
    val nfcLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(nfcLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) nfcOn = nfcAdapter?.isEnabled == true
        }
        nfcLifecycle.lifecycle.addObserver(observer)
        onDispose { nfcLifecycle.lifecycle.removeObserver(observer) }
    }
    var tapping by rememberSaveable { mutableStateOf(false) }
    // A share code arriving from an invite link opens the field with it.
    var entering by rememberSaveable { mutableStateOf(false) }
    if (shareCodeInput.isNotBlank()) entering = true
    val canSubmit = shareCodeInput.isNotBlank() && state !is AddContactState.Loading
    var showMyQr by rememberSaveable { mutableStateOf(false) }
    // Gives the bar its solid colour once content scrolls under it.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current

    AuroraBackground(Modifier.fillMaxSize(), style = AuroraStyle.Header) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            snackbarHost = { AppSnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = { Text("New chat") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                    ),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            LazyColumn(
                // Edge to edge, the window no longer shrinks for the keyboard: end the list at
                // its top so the focused share code field scrolls above it. (The Scaffold's
                // padding is only content padding here, so it mustn't be consumed first: that
                // left the list 48 dp, the navigation bar, behind the keyboard.)
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                    end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                // How to add someone: the two in-person ways first, since only they verify.
                item(key = "ways") {
                    SectionLabel("Add someone", Modifier.padding(start = 4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val busy = state is AddContactState.Loading
                        if (nfcAdapter != null) {
                            WayRow(
                                icon = Icons.Rounded.Nfc,
                                title = "Tap phones",
                                subtitle = if (nfcOn) {
                                    "Both open New chat and hold the phones back to back."
                                } else {
                                    "NFC is off. Tap to turn it on."
                                },
                                verifies = true,
                                position = RowPosition.FIRST,
                                enabled = !busy,
                                onClick = {
                                    if (nfcOn) {
                                        tapping = true
                                    } else {
                                        runCatching {
                                            nfcContext.startActivity(android.content.Intent(android.provider.Settings.ACTION_NFC_SETTINGS))
                                        }
                                    }
                                },
                            )
                        }
                        WayRow(
                            icon = Icons.Rounded.QrCodeScanner,
                            title = "Scan their QR code",
                            subtitle = "Point your camera at the code on their phone.",
                            verifies = true,
                            position = if (nfcAdapter != null) RowPosition.MIDDLE else RowPosition.FIRST,
                            enabled = !busy,
                            onClick = onScanQr,
                        )
                        WayRow(
                            icon = Icons.Rounded.QrCode2,
                            title = "Show my QR code",
                            subtitle = "For them to scan with their phone.",
                            position = RowPosition.MIDDLE,
                            enabled = myShareCode != null,
                            onClick = { showMyQr = true },
                        )
                        WayRow(
                            icon = Icons.Rounded.Keyboard,
                            title = "Enter a share code",
                            subtitle = "Typed or pasted. They're added as not verified.",
                            position = RowPosition.LAST,
                            enabled = !busy,
                            onClick = { entering = !entering },
                        )
                    }
                    if (entering) {
                        Spacer(Modifier.height(12.dp))
                        ShareCodeField(
                            value = shareCodeInput,
                            onValueChange = onShareCodeChange,
                            errorMessage = (state as? AddContactState.Error)?.message,
                            onSubmit = { if (canSubmit) onSubmit() },
                        )
                        Spacer(Modifier.height(12.dp))
                        GradientButton(
                            text = "Add contact",
                            icon = Icons.Rounded.PersonAdd,
                            onClick = onSubmit,
                            enabled = shareCodeInput.isNotBlank(),
                            loading = state is AddContactState.Loading,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (state is AddContactState.Error) {
                        Row(
                            modifier = Modifier.padding(start = 8.dp, top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                // People already added: tap to open the chat.
                if (contacts.isNotEmpty()) {
                    item(key = "contacts_header") {
                        SectionLabel(
                            "Your contacts",
                            Modifier.padding(start = 4.dp, top = 16.dp),
                            trailing = {
                                Text(
                                    contacts.size.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                    // Namespaced: user ids come from the relay, and one equal to a fixed
                    // key above would crash the list (duplicate key) on every open.
                    itemsIndexed(contacts, key = { _, contact -> "contact:${contact.userId}" }) { index, contact ->
                        ContactRow(
                            contact,
                            first = index == 0,
                            last = index == contacts.lastIndex,
                            onOpen = { onOpenChat(contact) },
                            onRemove = { removing = contact },
                        )
                    }
                }

                item(key = "my_code") {
                    Spacer(Modifier.height(16.dp))
                    MyShareCodeCard(myShareCode, snackbarHostState, showNameHint)
                }
            }
        }
    }

    if (tapping) {
        TapPhonesDialog(adding = state is AddContactState.Loading, onDismiss = { tapping = false })
    }

    if (showMyQr && myShareCode != null) {
        ShareCodeQrDialog(shareCode = myShareCode, qrContent = myQr ?: myShareCode, onDismiss = { showMyQr = false })
    }

    removing?.let { contact ->
        RemoveContactDialog(
            name = contact.displayName,
            onConfirm = {
                removing = null
                onRemoveContact(contact)
            },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun MyShareCodeCard(
    myShareCode: String?,
    snackbarHostState: SnackbarHostState,
    showNameHint: Boolean,
) {
    val aurora = Aurora.colors
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.5.dp, Brush.linearGradient(aurora.brand.map { it.copy(alpha = 0.8f) })),
        contentPadding = PaddingValues(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.QrCode2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Your share code",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = myShareCode ?: "Loading...",
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Someone can add you with it from anywhere, but then you're not verified until you meet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showNameHint) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Set your name in Settings so new contacts see who you are.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (myShareCode != null) {
            Spacer(Modifier.height(16.dp))
            ShareCodeActions(shareCode = myShareCode, snackbarHostState = snackbarHostState)
        }
    }
}

/** The user's own share code as a large QR, for someone to scan with "Scan QR code". */
@Composable
internal fun ShareCodeQrDialog(shareCode: String, onDismiss: () -> Unit, qrContent: String = shareCode) {
    val activity = LocalContext.current as? Activity
    // Full brightness makes the code easier to read from another phone's camera.
    DisposableEffect(activity) {
        val window = activity?.window
        val previous = window?.attributes?.screenBrightness
        window?.attributes = window?.attributes?.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }
        onDispose {
            if (window != null && previous != null) {
                window.attributes = window.attributes.apply { screenBrightness = previous }
            }
        }
    }
    val aurora = Aurora.colors
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.5.dp, Brush.linearGradient(aurora.brand.map { it.copy(alpha = 0.8f) })),
            fill = MaterialTheme.colorScheme.surface,
            contentPadding = PaddingValues(24.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                // A Dialog gives its content no text colour of its own: say it, or dark mode shows dark on dark.
                Text("Your QR code", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(20.dp))
                QrCodeImage(content = qrContent, size = 240.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = shareCode,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "On their phone: New chat → Scan QR code. Scanning it in person also verifies you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                SoftButton(text = "Done", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ShareCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    errorMessage: String?,
    onSubmit: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AuroraTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = "Paste share code",
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        errorMessage = errorMessage,
        shape = RoundedCornerShape(22.dp),
        minHeight = 56.dp,
        contentPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
        trailing = {
            IconButton(
                onClick = { clipboard.getText()?.text?.trim()?.let { onValueChange(it) } },
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste", tint = MaterialTheme.colorScheme.primary)
            }
        },
    )
}

@Composable
private fun ContactRow(contact: Contact, first: Boolean, last: Boolean, onOpen: () -> Unit = {}, onRemove: () -> Unit) {
    val aurora = Aurora.colors
    val radius = 26.dp
    val small = 6.dp
    val shape = RoundedCornerShape(
        topStart = if (first) radius else small,
        topEnd = if (first) radius else small,
        bottomStart = if (last) radius else small,
        bottomEnd = if (last) radius else small,
    )
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (last) 0.dp else 2.dp),
        shape = shape,
        fill = aurora.glass,
        onClick = onOpen,
        // The ⋮ button is 48 dp tall: less padding keeps the row its old height.
        contentPadding = PaddingValues(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradientAvatar(name = contact.displayName, seed = contact.userId, size = 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = contact.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val (label, tint) = when {
                    contact.verifiedInPerson -> "Verified in person" to aurora.success
                    contact.verifiedByCall -> "Verified by phone only" to MaterialTheme.colorScheme.primary
                    else -> "Not verified" to aurora.warning
                }
                Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
            }
            Box {
                var menu by remember { mutableStateOf(false) }
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Options for ${contact.displayName}")
                }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    shape = RoundedCornerShape(16.dp),
                ) {
                    DropdownMenuItem(
                        text = { Text("Remove contact") },
                        leadingIcon = {
                            Icon(Icons.Rounded.PersonRemove, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = {
                            menu = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

private enum class RowPosition { FIRST, MIDDLE, LAST }

/** One way to add someone: an icon, what it is, and whether it verifies them in person. */
@Composable
private fun WayRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    position: RowPosition,
    onClick: () -> Unit,
    verifies: Boolean = false,
    enabled: Boolean = true,
) {
    val radius = 26.dp
    val small = 6.dp
    val top = if (position == RowPosition.FIRST) radius else small
    val bottom = if (position == RowPosition.LAST) radius else small
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
        onClick = if (enabled) onClick else null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (verifies) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Rounded.VerifiedUser,
                    contentDescription = "Verifies in person",
                    tint = Aurora.colors.success,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** "Tap phones": what to do, while this screen answers and reads taps (InPersonNfcEffect). */
@Composable
private fun TapPhonesDialog(adding: Boolean, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Nfc, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(if (adding) "Adding…" else "Hold your phones together") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Ask them to open New chat too. Then hold the backs of the phones together for a " +
                        "moment. You'll be added to each other, verified in person.",
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                androidx.compose.material3.CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
