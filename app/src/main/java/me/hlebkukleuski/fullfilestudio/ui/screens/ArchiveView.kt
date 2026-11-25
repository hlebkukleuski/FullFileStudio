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

package me.hlebkukleuski.fullfilestudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppPalette
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppStrings

private data class ParsedArchiveUiEntry(
    val rawEntry: String,
    val displayName: String,
    val displaySize: String
)

private val ARCHIVE_UI_SIZE_REGEX = Regex(""" \((\d+ B|unknown size)\)$""", RegexOption.IGNORE_CASE)

private fun parseArchiveUiEntry(entry: String): ParsedArchiveUiEntry {
    val match = ARCHIVE_UI_SIZE_REGEX.find(entry)
    if (match != null) {
        return ParsedArchiveUiEntry(
            rawEntry = entry,
            displayName = entry.substring(0, match.range.first).trim(),
            displaySize = match.groupValues[1]
        )
    }
    return ParsedArchiveUiEntry(
        rawEntry = entry,
        displayName = entry,
        displaySize = ""
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveView(
    entries: List<String>,
    onEntryClick: (String) -> Unit,
    onBackClick: (() -> Unit)? = null,
    onOpenSettingsClick: (() -> Unit)? = null
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current

    val parsedEntries = remember(entries) {
        entries.asSequence()
            .filter { it.isNotBlank() }
            .map { parseArchiveUiEntry(it) }
            .toList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .navigationBarsPadding()
    ) {
        TopAppBar(
            title = {
                Text(
                    text = strings.archiveContentsTitle,
                    color = palette.primaryText,
                    fontWeight = FontWeight.SemiBold
                )
            },
            navigationIcon = {
                if (onBackClick != null) {
                    TextButton(onClick = onBackClick) {
                        Text(strings.backButton, color = palette.accent, fontWeight = FontWeight.Bold)
                    }
                }
            },
            actions = {
                if (onOpenSettingsClick != null) {
                    TextButton(onClick = onOpenSettingsClick) {
                        Text("⚙", color = palette.primaryText)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.surface)
        )

        if (parsedEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = strings.archiveEmptyMessage,
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(
                    items = parsedEntries,
                    key = { index, item -> "$index:${item.rawEntry}" }
                ) { _, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEntryClick(item.rawEntry) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.displayName,
                                color = palette.primaryText,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (item.displaySize.isNotEmpty()) {
                                Text(
                                    text = item.displaySize,
                                    color = palette.secondaryText,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = palette.border)
                }
            }
        }
    }
}
