package com.sotto.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ConversationItem(
    displayName: String,
    lastMessage: String?,
    timestamp: String?,
    unreadCount: Int,
    isEncrypted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    avatarSeed: String = displayName,
    /** How far the contact is verified: a shield in place of the lock. Null shows the lock. */
    trust: com.sotto.app.domain.model.ContactTrust? = null,
) {
    val colors = MaterialTheme.colorScheme
    val unread = unreadCount > 0
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GradientAvatar(name = displayName, seed = avatarSeed, size = 54.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (trust != null) {
                        Spacer(Modifier.width(6.dp))
                        val (icon, tint, said) = when (trust) {
                            com.sotto.app.domain.model.ContactTrust.IN_PERSON ->
                                Triple(Icons.Rounded.VerifiedUser, com.sotto.app.ui.theme.Aurora.colors.success, "Verified in person")
                            com.sotto.app.domain.model.ContactTrust.BY_PHONE ->
                                Triple(Icons.Rounded.PhoneInTalk, colors.primary, "Verified by phone only")
                            com.sotto.app.domain.model.ContactTrust.NONE ->
                                Triple(Icons.Rounded.GppMaybe, com.sotto.app.ui.theme.Aurora.colors.warning, "Not verified")
                        }
                        Icon(imageVector = icon, contentDescription = said, modifier = Modifier.size(14.dp), tint = tint)
                    } else if (isEncrypted) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = "Encrypted",
                            modifier = Modifier.size(12.dp),
                            tint = colors.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
                if (timestamp != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = timestamp,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (unread) colors.primary else colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = lastMessage.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unread) colors.onSurface.copy(alpha = 0.9f) else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread) {
                    Spacer(Modifier.width(10.dp))
                    UnreadBadge(unreadCount)
                }
            }
        }
    }
}
