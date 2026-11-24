package com.sotto.app.ui.contacts

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sotto.app.R
import com.sotto.app.ui.components.AuroraBackground
import com.sotto.app.ui.components.AuroraStyle
import com.sotto.app.ui.components.GlassIconButton
import com.sotto.app.ui.components.GradientButton
import com.sotto.app.ui.components.QrCodeImage
import com.sotto.app.ui.theme.Aurora

/** The two sides of a QR code exchange on New chat. */
enum class QrTab { SCAN, MINE }

/**
 * New chat's one QR code screen: Scan (theirs) and My code (ours, live), with tabs that follow
 * the exchange. After we verify them from their code it turns to My code ("Your turn"); when
 * their request arrives while ours is showing, it turns to Scan ("Now scan their code"), and
 * that scan verifies them and accepts the request. See LiveQrWatch.
 */
@Composable
fun QrExchangeScreen(
    tab: QrTab,
    onTab: (QrTab) -> Unit,
    liveQr: String?,
    myShareCode: String?,
    /** Just verified from their code: who, for "Your turn"; null otherwise. */
    yourTurnFor: String?,
    /** They scanned ours first: this scan answers theirs. */
    scanBack: Boolean,
    onScanned: (String) -> Unit,
    onClose: () -> Unit,
    onDone: () -> Unit,
) {
    when (tab) {
        QrTab.SCAN -> Box(Modifier.fillMaxSize()) {
            ScanQrScreen(
                onBack = onClose,
                onScanned = onScanned,
                accept = { text -> com.sotto.app.crypto.InPersonCode.parse(text)?.encode() ?: shareCodeFromQr(text) },
                title = stringResource(if (scanBack) R.string.contacts_now_scan_their_code else R.string.contacts_scan_their_code),
                hint = stringResource(if (scanBack) R.string.contacts_scan_back_hint else R.string.contacts_scan_hint),
                rejectMessage = stringResource(R.string.contacts_not_a_sotto_code),
            )
            QrTabs(tab, onTab, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp))
        }
        QrTab.MINE -> MyCode(liveQr, myShareCode, yourTurnFor, tab, onTab, onClose, onDone)
    }
}

@Composable
private fun MyCode(
    liveQr: String?,
    myShareCode: String?,
    yourTurnFor: String?,
    tab: QrTab,
    onTab: (QrTab) -> Unit,
    onClose: () -> Unit,
    onDone: () -> Unit,
) {
    // Full brightness: the other phone's camera reads a bright screen more easily.
    val activity = LocalContext.current as? Activity
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
    val colors = MaterialTheme.colorScheme
    AuroraBackground(Modifier.fillMaxSize(), style = AuroraStyle.Header) {
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(icon = Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), onClick = onClose)
            }
            QrTabs(tab, onTab, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
            Column(
                Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (yourTurnFor != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = Aurora.colors.success, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.contacts_name_verified, yourTurnFor), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.contacts_your_turn, yourTurnFor),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(stringResource(R.string.contacts_let_them_scan), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                }
                Spacer(Modifier.height(20.dp))
                if (liveQr != null) {
                    QrCodeImage(content = liveQr, size = 280.dp)
                } else {
                    Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                    }
                }
                if (myShareCode != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        myShareCode,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        color = colors.onSurface,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.contacts_live_code_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (yourTurnFor != null) {
                    Spacer(Modifier.height(24.dp))
                    GradientButton(text = stringResource(R.string.contacts_done_open_chat), onClick = onDone, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Scan | My code, over the camera or the code. */
@Composable
internal fun QrTabs(tab: QrTab, onTab: (QrTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((value, labelRes) in listOf(QrTab.SCAN to R.string.contacts_tab_scan, QrTab.MINE to R.string.contacts_tab_my_code)) {
            val selected = value == tab
            Text(
                stringResource(labelRes),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) Color.Black else Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable { onTab(value) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}
