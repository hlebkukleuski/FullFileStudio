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

package me.hlebkukleuski.fullfilestudio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneUpdateSecurityTest {

    @Test
    fun releaseUrlAllowlistAcceptsOnlyOfficialHttpsGithubApk() {
        assertTrue(
            StandaloneUpdateManager.isAllowedReleaseUrl(
                "https://github.com/hlebkukleuski/FullFileStudio/releases/download/v1.0.0/fullfilestudio-v1.0.0-hashtagfreeinternet-release.apk"
            )
        )
        assertFalse(StandaloneUpdateManager.isAllowedReleaseUrl("http://github.com/hlebkukleuski/FullFileStudio/releases/download/v1.0.0/app.apk"))
        assertFalse(StandaloneUpdateManager.isAllowedReleaseUrl("https://evil.example/app.apk"))
        assertFalse(StandaloneUpdateManager.isAllowedReleaseUrl("https://github.com@evil.example/hlebkukleuski/FullFileStudio/releases/download/v1.0.0/app.apk"))
        assertFalse(StandaloneUpdateManager.isAllowedReleaseUrl("https://github.com/otheruser/repo/releases/download/v1.0.0/app.apk"))
        assertFalse(StandaloneUpdateManager.isAllowedReleaseUrl("https://github.com/hlebkukleuski/FullFileStudio/releases/download/v1.0.0/../../other/app.apk"))
    }

    @Test
    fun redirectAllowlistPermitsOnlyGithubReleaseCdnHosts() {
        assertTrue(StandaloneUpdateManager.isAllowedRedirectUrl("https://objects.githubusercontent.com/github-production-release-asset-123/file"))
        assertTrue(StandaloneUpdateManager.isAllowedRedirectUrl("https://release-assets.githubusercontent.com/asset.apk"))
        assertFalse(StandaloneUpdateManager.isAllowedRedirectUrl("http://objects.githubusercontent.com/asset.apk"))
        assertFalse(StandaloneUpdateManager.isAllowedRedirectUrl("https://attacker.com/malware.apk"))
    }
}
