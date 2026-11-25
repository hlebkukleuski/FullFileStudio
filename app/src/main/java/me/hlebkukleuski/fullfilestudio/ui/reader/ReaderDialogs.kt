/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://gnu.org>.
 */

package me.hlebkukleuski.fullfilestudio.ui.reader

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.hlebkukleuski.fullfilestudio.StandaloneReleaseInfo
import me.hlebkukleuski.fullfilestudio.StandaloneUpdateUiState
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppPalette
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppStrings

@Composable
fun EnterpriseErrorDialog(
    badgeText: String,
    badgeColor: Color,
    titleText: String,
    descriptionText: String,
    primaryButtonText: String,
    onPrimaryClick: () -> Unit,
    secondaryButtonText: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
    onDismissRequest: () -> Unit
) {
    val palette = LocalAppPalette.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            Button(
                onClick = onPrimaryClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = badgeColor,
                    contentColor = Color.Black
                )
            ) {
                Text(primaryButtonText, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            if (secondaryButtonText != null && onSecondaryClick != null) {
                TextButton(onClick = onSecondaryClick) {
                    Text(secondaryButtonText, color = palette.secondaryText)
                }
            }
        },
        title = {
            Column {
                Box(
                    modifier = Modifier
                        .background(badgeColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(badgeText, color = badgeColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(titleText, color = palette.primaryText, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surfaceElevated, RoundedCornerShape(12.dp))
                    .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = descriptionText,
                    color = palette.primaryText,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        },
        containerColor = palette.surface,
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
fun PasswordUnlockDialog(
    badgeText: String,
    badgeColor: Color,
    titleText: String,
    descriptionText: String,
    placeholderText: String,
    unlockButtonText: String,
    hexButtonText: String?,
    closeButtonText: String,
    onUnlock: (String) -> Unit,
    onHexClick: (() -> Unit)?,
    onDismissRequest: () -> Unit
) {
    val palette = LocalAppPalette.current
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            Button(
                onClick = { if (password.isNotEmpty()) onUnlock(password) },
                enabled = password.isNotEmpty(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = badgeColor,
                    contentColor = Color.Black
                )
            ) {
                Text(unlockButtonText, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hexButtonText != null && onHexClick != null) {
                    TextButton(onClick = onHexClick) {
                        Text(hexButtonText, color = palette.secondaryText)
                    }
                }
                TextButton(onClick = onDismissRequest) {
                    Text(closeButtonText, color = palette.secondaryText)
                }
            }
        },
        title = {
            Column {
                Box(
                    modifier = Modifier
                        .background(badgeColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(badgeText, color = badgeColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(titleText, color = palette.primaryText, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.surfaceElevated, RoundedCornerShape(12.dp))
                        .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = descriptionText,
                        color = palette.primaryText,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = { Text(placeholderText, color = palette.secondaryText) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = palette.primaryText,
                        unfocusedTextColor = palette.primaryText,
                        focusedBorderColor = badgeColor,
                        unfocusedBorderColor = palette.border,
                        cursorColor = badgeColor
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        containerColor = palette.surface,
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
internal fun StandaloneUpdateDialog(
    release: StandaloneReleaseInfo,
    updateState: StandaloneUpdateUiState,
    onConfirmDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "${strings.updateAvailableTitle} (${release.versionName})",
                color = palette.primaryText,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column {
                if (release.releaseNotes.isNotBlank()) {
                    Text(
                        text = release.releaseNotes,
                        color = palette.secondaryText,
                        fontSize = 14.sp
                    )
                }
                if (updateState.isDownloading) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { updateState.downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = palette.accent
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${strings.downloadingUpdateStatus} ${(updateState.downloadProgress * 100).toInt()}%",
                        color = palette.accent,
                        fontSize = 12.sp
                    )
                }
                updateState.errorText?.let { err ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = err,
                        color = palette.errorBadge,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !updateState.isDownloading,
                onClick = onConfirmDownload,
                colors = ButtonDefaults.buttonColors(containerColor = palette.accent)
            ) {
                Text(
                    text = strings.updateNowButton,
                    color = if (palette.isDark) Color.Black else Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            if (!updateState.isDownloading) {
                TextButton(onClick = onDismiss) {
                    Text(strings.laterButton, color = palette.secondaryText)
                }
            }
        }
    )
}
