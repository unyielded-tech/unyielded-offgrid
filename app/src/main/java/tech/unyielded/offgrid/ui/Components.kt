package tech.unyielded.offgrid.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun OffgridCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(OffgridDimens.CardRadius))
            .background(OffgridColors.Surface)
            .border(1.dp, OffgridColors.Border, RoundedCornerShape(OffgridDimens.CardRadius))
            .padding(16.dp)
    ) {
        content()
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(OffgridDimens.ButtonRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = OffgridColors.Accent,
            contentColor = OffgridColors.Ink,
            disabledContainerColor = OffgridColors.SurfaceElevated,
            disabledContentColor = OffgridColors.TextTertiary
        )
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(OffgridDimens.ButtonRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = OffgridColors.SurfaceElevated,
            contentColor = OffgridColors.TextPrimary,
            disabledContainerColor = OffgridColors.Surface,
            disabledContentColor = OffgridColors.TextTertiary
        )
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp
        )
    }
}

@Composable
fun StatusDot(active: Boolean, color: Color = OffgridColors.Accent) {
    Box(
        modifier = Modifier
            .size(OffgridDimens.StatusDot)
            .background(
                if (active) color else OffgridColors.TextTertiary,
                CircleShape
            )
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = OffgridColors.TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp
    )
}

@Composable
fun ScanningIndicator() {
    val infinite = rememberInfiniteTransition(label = "scan")
    val angle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotate"
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .rotate(angle)
            .border(2.dp, OffgridColors.Accent.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(OffgridColors.Accent, CircleShape)
                .align(Alignment.TopCenter)
        )
    }
}

@Composable
fun MessageBubble(
    text: String,
    isMine: Boolean,
    isSystem: Boolean = false
) {
    if (isSystem) {
        Text(
            text = text,
            color = OffgridColors.TextTertiary,
            fontSize = 11.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        )
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .background(
                    if (isMine) OffgridColors.BubbleMine else OffgridColors.BubblePeer,
                    RoundedCornerShape(14.dp)
                )
                .border(
                    1.dp,
                    if (isMine) OffgridColors.AccentMuted else OffgridColors.Border,
                    RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = if (isMine) "YOU" else "PEER",
                color = OffgridColors.Accent,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = text,
                color = OffgridColors.TextPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
fun DeviceCard(
    name: String,
    address: String,
    onConnect: () -> Unit,
    enabled: Boolean = true
) {
    OffgridCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = name,
                color = OffgridColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = address,
                color = OffgridColors.TextTertiary,
                fontSize = 11.sp
            )
            SecondaryButton(
                text = "CONNECT",
                onClick = onConnect,
                enabled = enabled
            )
        }
    }
}

@Composable
fun FingerprintDisplay(fingerprint: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(OffgridColors.AccentMuted.copy(alpha = 0.35f))
            .border(1.dp, OffgridColors.Accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(vertical = 16.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = fingerprint,
            color = OffgridColors.Accent,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
    }
}
