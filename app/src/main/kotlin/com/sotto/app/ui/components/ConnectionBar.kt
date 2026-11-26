package com.sotto.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sotto.app.R

/** What the bar above every screen says while Sotto can't reach its relay. */
enum class ConnectionProblem { NO_INTERNET, CONNECTING }

/** A short reconnect (coming back to the app, a network switch) shows nothing. */
private const val GRACE_MS = 3_000L

/** Calm slate, as the update bar's "on its way": it informs, nothing is wrong with what the user did. */
private val BarColor = Color(0xFF475569)

/**
 * The problem to show, once it has lasted a few seconds; null while connected (or within those
 * seconds). [online]: the phone has a network at all. Without one it's "no internet" even while
 * the socket still counts as open: a socket whose network went away only shows as dead once its
 * next ping goes unanswered, up to half a minute later.
 */
@Composable
fun rememberConnectionProblem(connected: Boolean, online: Boolean): ConnectionProblem? {
    val reachable = connected && online
    var overdue by remember { mutableStateOf(false) }
    LaunchedEffect(reachable) {
        overdue = false
        if (!reachable) {
            kotlinx.coroutines.delay(GRACE_MS)
            overdue = true
        }
    }
    return when {
        reachable || !overdue -> null
        !online -> ConnectionProblem.NO_INTERNET
        else -> ConnectionProblem.CONNECTING
    }
}

/**
 * Above every screen while Sotto can't reach its relay: messages wait (and go once it's back),
 * new ones don't come in. Draws under the status bar itself when it's the topmost bar.
 */
@Composable
fun ConnectionBar(problem: ConnectionProblem?, underStatusBar: Boolean, modifier: Modifier = Modifier) {
    problem ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(BarColor)
            .then(if (underStatusBar) Modifier.statusBarsPadding() else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (problem == ConnectionProblem.NO_INTERNET) {
            Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        } else {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(
                if (problem == ConnectionProblem.NO_INTERNET) R.string.connection_no_internet else R.string.connection_connecting,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}
