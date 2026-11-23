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

package com.omnireader.core.engine;

import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;
import com.omnireader.core.engine.ISessionStateCallback;

interface IIsolatedEngineService {
    int openSession(in ParcelFileDescriptor pfd, String extension);
    boolean renderTile(int sessionId, int pageIndex, int tileX, int tileY, int zoomPermille, int width, int height, in SharedMemory sharedMemory);
    long search(int sessionId, String query, int startPage);
    void closeSession(int sessionId);
    int getPageCount(int sessionId);
    boolean is3dSession(int sessionId);
    SharedMemory exportMeshScene(int sessionId);
    String getPageText(int sessionId, int pageIndex);
    float getDocumentWidth(int sessionId);
    float getDocumentHeight(int sessionId);

    // State & Structured Error Reporting
    void registerSessionStateCallback(int sessionId, ISessionStateCallback callback);
    long getStateVersion(int sessionId);
    String getLastError(int sessionId);
    String[] getMissingResources(int sessionId);
    boolean provideResource(int sessionId, String name, in ParcelFileDescriptor pfd);
    boolean applyProvidedResources(int sessionId);

    // Archive Support
    boolean isArchiveSession(int sessionId);
    String[] getArchiveEntries(int sessionId);
    ParcelFileDescriptor openVirtualFile(int sessionId, String entryPath);

    // Spreadsheet & Database Support
    String[] getSheetNames(int sessionId);
    int getActiveSheetIndex(int sessionId);
    void setActiveSheetIndex(int sessionId, int index);
    float[] getColumnWidths(int sessionId);
    String getCellText(int sessionId, int row, int col);
    String getSelectedRangeText(int sessionId, int startRow, int startCol, int endRow, int endCol);
    float[] getRowHeights(int sessionId);
    int openSessionWithPassword(in ParcelFileDescriptor pfd, String extension, String password);
    boolean isCopyRestricted(int sessionId);
}