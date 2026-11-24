package com.sotto.app.ui.components

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.sotto.app.R
import kotlinx.coroutines.launch

/** Copy and Share buttons for the user's own share code. */
@Composable
fun ShareCodeActions(
    shareCode: String,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.components_share_code_copied)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SoftButton(
            text = stringResource(R.string.common_copy),
            icon = Icons.Rounded.ContentCopy,
            compact = true,
            tone = SoftButtonTone.Accent,
            enabled = enabled,
            onClick = {
                clipboard.setText(AnnotatedString(shareCode))
                // Android 13+ confirms copies itself.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    scope.launch { snackbarHostState.showSnackbar(copied) }
                }
            },
        )
        SoftButton(
            text = stringResource(R.string.common_share),
            icon = Icons.Rounded.Share,
            compact = true,
            tone = SoftButtonTone.Accent,
            enabled = enabled,
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareCode)
                }
                context.startActivity(Intent.createChooser(send, null))
            },
        )
    }
}
