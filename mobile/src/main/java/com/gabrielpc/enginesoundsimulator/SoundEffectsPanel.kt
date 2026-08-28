package com.gabrielpc.enginesoundsimulator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gabrielpc.enginesoundsimulator.drive.DriveSnapshot
import com.gabrielpc.enginesoundsimulator.drive.SoundEffectOption

private val FxBackground = Color(0xF7060606)
private val FxPanel = Color(0xFF0B1925)
private val FxLine = Color(0xFF1A3C4A)
private val FxCyan = Color(0xFF35E8F2)
private val FxGreen = Color(0xFF38E58C)
private val FxWhite = Color(0xFFF5FAFD)
private val FxMuted = Color(0xFF88A2B2)

@Composable
internal fun SoundEffectsPanel(
    state: DriveSnapshot,
    onEffectChange: (String, Boolean) -> Unit,
    onSoloChange: (Boolean) -> Unit,
    onAuditionPopsAndBangs: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FxBackground)
            .padding(horizontal = 64.dp, vertical = 42.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(FxPanel, RoundedCornerShape(22.dp))
                .border(1.dp, FxLine, RoundedCornerShape(22.dp))
                .padding(34.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("CAR SOUND EFFECTS", color = FxWhite, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text(
                        state.selectedCarName.uppercase(),
                        color = FxCyan,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                    )
                }
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = FxLine),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("CLOSE", color = FxCyan, fontWeight = FontWeight.Black)
                }
            }

            Spacer(Modifier.height(26.dp))
            Text(
                "Only verified powertrain sounds from this car's own sample bank are listed. " +
                    "Tires, wind, chassis, doors and collision sounds are excluded.",
                color = FxMuted,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(24.dp))

            if (state.soundEffects.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.24f), RoundedCornerShape(16.dp))
                        .border(1.dp, FxLine.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
                        .padding(28.dp),
                ) {
                    Column {
                        Text("ENGINE LOOPSET ONLY", color = FxWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "This bank does not expose additional cabin effects with a trustworthy sample mapping.",
                            color = FxMuted,
                            fontSize = 13.sp,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SoloEffectsCheckbox(state.soloSoundEffects, onSoloChange)
                    state.soundEffects.forEach { effect ->
                        EffectCheckbox(effect, onEffectChange)
                    }
                    if (state.popsAndBangsAuditionAvailable) {
                        Button(
                            onClick = onAuditionPopsAndBangs,
                            colors = ButtonDefaults.buttonColors(containerColor = FxCyan.copy(alpha = 0.18f)),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                "AUDITION POPS, BANGS & CRACKS",
                                color = FxCyan,
                                fontWeight = FontWeight.Black,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            Text(
                "Effects are mixed into the same source-rate stereo program and routed through the same AudioTrack as the engine.",
                color = FxMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SoloEffectsCheckbox(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(78.dp)
            .background(FxCyan.copy(alpha = if (enabled) 0.13f else 0.05f), RoundedCornerShape(16.dp))
            .border(1.dp, if (enabled) FxCyan else FxLine, RoundedCornerShape(16.dp))
            .clickable { onChange(!enabled) }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = enabled,
            onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(
                checkedColor = FxCyan,
                checkmarkColor = Color.Black,
                uncheckedColor = FxMuted,
            ),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text("SOLO CHECKED EFFECTS", color = FxWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Mute engine and transmission sounds; hear only the effects checked below",
                color = FxMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun EffectCheckbox(
    effect: SoundEffectOption,
    onEffectChange: (String, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(78.dp)
            .background(Color.Black.copy(alpha = 0.24f), RoundedCornerShape(16.dp))
            .border(
                1.dp,
                if (effect.enabled) FxGreen.copy(alpha = 0.55f) else FxLine,
                RoundedCornerShape(16.dp),
            )
            .clickable { onEffectChange(effect.id, !effect.enabled) }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = effect.enabled,
            onCheckedChange = { checked -> onEffectChange(effect.id, checked) },
            colors = CheckboxDefaults.colors(
                checkedColor = FxGreen,
                checkmarkColor = Color.Black,
                uncheckedColor = FxMuted,
            ),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(effect.displayName, color = FxWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(effect.description, color = FxMuted, fontSize = 12.sp)
        }
    }
}
