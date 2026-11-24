package com.sotto.app.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sotto.app.R
import com.sotto.app.ui.components.GradientButton
import com.sotto.app.ui.components.QrCodeImage
import com.sotto.app.ui.components.SoftButton
import com.sotto.app.ui.text.UiText
import com.sotto.app.ui.text.resolve
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** An invite ready to hand over: its one link (also in the QR) and until when it works. */
data class InviteShare(val link: String, val expiresAt: String) {
    /**
     * "26 Nov" ([pattern], the language's way of writing a day: settings_invite_date_pattern),
     * in the phone's time zone; the raw value if it can't be read.
     */
    fun expiresLabel(pattern: String): String = runCatching {
        DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(Instant.parse(expiresAt))
    }.getOrDefault(expiresAt)

    /**
     * A message for any chat app: the link alone does it all. With Sotto installed it opens the
     * app; otherwise the relay's page offers the download, then opens the invite in Sotto.
     * In the sender's language, with the last day as [expiresLabel] wrote it.
     */
    fun shareText(expiresLabel: String): UiText = UiText.res(R.string.settings_invite_share_text, link, expiresLabel)
}

/** A fresh invite: its QR for any camera, and Copy link / Share for sending it as a message. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteSheet(invite: InviteShare, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val expires = invite.expiresLabel(stringResource(R.string.settings_invite_date_pattern))
    // Fully open: half-open, the code and the buttons sat below the screen's edge.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.settings_invite_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_invite_sheet_text, expires),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .background(Color.White, RoundedCornerShape(18.dp))
                    .padding(14.dp),
            ) {
                QrCodeImage(content = invite.link, size = 220.dp)
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SoftButton(
                    text = stringResource(R.string.settings_invite_copy_link),
                    icon = Icons.Rounded.ContentCopy,
                    onClick = { clipboard.setText(AnnotatedString(invite.link)) },
                    modifier = Modifier.weight(1f),
                )
                GradientButton(
                    text = stringResource(R.string.common_share),
                    icon = Icons.Rounded.Share,
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, invite.shareText(expires).resolve(context))
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.settings_invite_send_chooser)))
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
