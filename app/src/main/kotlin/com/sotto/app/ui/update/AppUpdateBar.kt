package com.sotto.app.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sotto.app.BuildConfig
import com.sotto.app.R
import com.sotto.app.ui.theme.AuroraVioletDeep
import com.sotto.app.update.AppUpdate
import com.sotto.app.update.SourceDrop
import com.sotto.app.ui.text.UiText
import com.sotto.app.ui.text.asString

/** The update is out: Sotto's violet. */
private val ReadyColor = AuroraVioletDeep
/**
 * The relay is ahead and the app isn't out yet: a calm slate, not a warning colour. There is
 * nothing the user can do but wait, so it only informs.
 */
private val OnItsWayColor = Color(0xFF475569)

/**
 * Above every screen while the relay runs a newer Sotto than this app. Once that app is out,
 * a tap opens SourceDrop to install it; until then a tap explains why some things may not
 * work. Nothing when the app is up to date. Like the ongoing-call bar, it draws under the
 * status bar itself when it's the topmost bar ([underStatusBar]).
 */
@Composable
fun AppUpdateBar(update: AppUpdate, underStatusBar: Boolean, modifier: Modifier = Modifier) {
    if (update == AppUpdate.None) return
    val context = LocalContext.current
    var explaining by remember { mutableStateOf(false) }
    val ready = update is AppUpdate.Ready
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (ready) ReadyColor else OnItsWayColor)
            .clickable(onClickLabel = stringResource(if (ready) R.string.update_action_label else R.string.update_why_label)) {
                when (update) {
                    is AppUpdate.Ready -> SourceDrop.open(context, update.version)
                    else -> explaining = true
                }
            }
            .then(if (underStatusBar) Modifier.statusBarsPadding() else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (ready) Icons.Rounded.SystemUpdate else Icons.Rounded.Schedule,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = appUpdateBarText(update)?.asString().orEmpty(),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(if (ready) R.string.update_action else R.string.update_why),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
    if (explaining && update is AppUpdate.OnItsWay) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            title = { Text(stringResource(R.string.update_on_its_way_title)) },
            text = { Text(stringResource(R.string.update_on_its_way_text, update.version, BuildConfig.VERSION_NAME)) },
            confirmButton = { TextButton(onClick = { explaining = false }) { Text(stringResource(R.string.common_ok)) } },
        )
    }
}

/** The bar's one line; null when there is no bar. */
internal fun appUpdateBarText(update: AppUpdate): UiText? = when (update) {
    is AppUpdate.Ready -> UiText.res(R.string.update_ready, update.version)
    is AppUpdate.OnItsWay -> UiText.res(R.string.update_on_its_way, update.version)
    AppUpdate.None -> null
}
