package org.olcbox.app.ui.features.home

import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.label_transport
import multiplatform_app.sharedui.generated.resources.room_pick_hint
import multiplatform_app.sharedui.generated.resources.room_selected
import multiplatform_app.sharedui.generated.resources.room_your_seat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import org.olcbox.app.ui.components.kit.PkSectionEyebrow
import org.olcbox.app.ui.components.kit.boardWords
import org.olcbox.app.ui.components.kit.pkDisplaySp
import org.olcbox.app.ui.components.kit.pkMono
import org.olcbox.app.ui.components.kit.pkTied
import org.olcbox.app.ui.components.kit.transportTag
import org.olcbox.app.ui.components.kit.wireShape
import org.olcbox.app.ui.features.home.components.locationDisplayParts
import org.olcbox.app.ui.theme.LocalPkPalette

@Composable
fun RoomDetailPane(
    board: HomeBoard,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPkPalette.current
    val selected = board.model.subscriptionGroups
        .flatMap { it.locations }
        .plus(board.model.customLocations)
        .firstOrNull { it.storageId == board.selectedLocationId }

    if (selected == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.room_pick_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textMuted,
            )
        }
        return
    }

    val (emoji, name) = locationDisplayParts(selected)
    val config = selected.config

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PkSectionEyebrow(stringResource(if (board.isConnected) Res.string.room_your_seat else Res.string.room_selected))
        Row {
            if (emoji.isNotBlank()) {
                Text(
                    text = emoji,
                    style = LocalTextStyle.current.copy(
                        fontSize = pkDisplaySp(26),
                        lineHeight = pkDisplaySp(36),
                        letterSpacing = 0.sp,
                    ),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.alignByBaseline(),
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = pkTied(name),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = pkDisplaySp(28),
                    lineHeight = pkDisplaySp(36),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alignByBaseline(),
            )
        }
        Text(
            text = pkTied(wireShape(config, boardWords()).uppercase()),
            style = pkMono(10, 1.2),
            color = palette.textDim,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(Res.string.label_transport).uppercase(),
                style = pkMono(9, 1.2),
                color = palette.textMuted,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = transportTag(config) ?: "—",
                style = pkMono(15, 0.4).copy(fontWeight = FontWeight.Medium, lineHeight = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
