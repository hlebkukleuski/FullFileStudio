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

import android.net.Uri
import androidx.compose.runtime.Immutable
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession

internal data class SessionStateFrame(
    val session: DocumentSession,
    val isArchive: Boolean,
    val archiveEntries: List<String>,
    val activeSessionId: Int,
    val savedPageIndex: Int = 0,
    val savedSheetIndex: Int = 0
)

enum class UiErrorCategory {
    ACCESS_OR_SECURITY,
    CLOSED_LICENSE,
    PASSWORD_REQUIRED,
    FORMAT_STRUCTURE
}

@Immutable
data class StructuredUiError(
    val category: UiErrorCategory,
    val message: String,
    val allowHexFallback: Boolean = true
)

@Immutable
data class ReaderUiState(
    val currentSession: DocumentSession? = null,
    val isArchiveSession: Boolean = false,
    val archiveEntries: List<String> = emptyList(),
    val activeSessionId: Int = -1,
    val hasSessionHistory: Boolean = false,
    val pendingUri: Uri? = null,
    val lastOpenedUri: Uri? = null,
    val structuredError: StructuredUiError? = null,
    val isBufferingFile: Boolean = false,
    val bufferingProgress: Float = 0f,
    val hasVirtualHexBackup: Boolean = false,
    val savedPageIndex: Int = 0,
    val savedSheetIndex: Int = 0
)

sealed interface ReaderUiAction {
    data class OpenUri(val uri: Uri, val password: String? = null) : ReaderUiAction
    data class OpenArchiveEntry(val entryName: String) : ReaderUiAction
    data class OpenHexFallback(val uri: Uri) : ReaderUiAction
    data object NavigateBack : ReaderUiAction
    data object DismissError : ReaderUiAction
    data class PageChanged(val pageIndex: Int) : ReaderUiAction
    data class SheetChanged(val sheetIndex: Int) : ReaderUiAction
}
