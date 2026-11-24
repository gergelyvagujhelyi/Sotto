package com.sotto.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sotto.app.R
import com.sotto.app.domain.model.ContactTrust
import com.sotto.app.ui.theme.Aurora

/** The colour of a contact's trust: green in person, purple by phone, yellow not verified. */
@Composable
fun trustColor(trust: ContactTrust): Color = when (trust) {
    ContactTrust.IN_PERSON -> Aurora.colors.success
    ContactTrust.BY_PHONE -> MaterialTheme.colorScheme.primary
    ContactTrust.NONE -> Aurora.colors.warning
}

/** How far a contact is verified, in words: "Verified in person", "Verified by phone only", "Not verified". */
@Composable
fun trustLabel(trust: ContactTrust): String = stringResource(
    when (trust) {
        ContactTrust.IN_PERSON -> R.string.components_trust_in_person
        ContactTrust.BY_PHONE -> R.string.components_trust_by_phone
        ContactTrust.NONE -> R.string.components_trust_none
    },
)

/**
 * How far a contact is verified, as one mark used everywhere (chat list, chat header, contacts,
 * the verify screen): a shield with a check in person; the same shield in purple with a small
 * phone badge by phone (verified, but only by a call: a bare phone icon read as "a call"); a
 * shield with "!" not verified.
 */
@Composable
fun TrustIcon(trust: ContactTrust, size: Dp, modifier: Modifier = Modifier) {
    val tint = trustColor(trust)
    val said = trustLabel(trust)
    Box(modifier.size(size)) {
        Icon(
            if (trust == ContactTrust.NONE) Icons.Rounded.GppMaybe else Icons.Rounded.VerifiedUser,
            contentDescription = said,
            tint = tint,
            modifier = Modifier.size(size),
        )
        if (trust == ContactTrust.BY_PHONE) {
            // The badge: a small phone on a dot, in the shield's corner.
            val badge = size * 0.62f
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = size * 0.22f, y = size * 0.18f)
                    .size(badge)
                    .clip(CircleShape)
                    .background(tint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Phone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(badge).padding(badge * 0.18f),
                )
            }
        }
    }
}
