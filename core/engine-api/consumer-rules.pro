# Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program. If not, see <https://gnu.org>.

# Keep SecurityArena JNI methods for libengine_api.so binding
-keep class com.omnireader.core.engine.SecurityArena {
    public static final com.omnireader.core.engine.SecurityArena INSTANCE;
    public static <methods>;
    native <methods>;
}

# Keep dynamic engine factory contract
-keep interface com.omnireader.core.engine.DynamicEngineFactory { *; }
-keep class * implements com.omnireader.core.engine.DynamicEngineFactory {
    public <init>();
    public *** canHandle(...);
    public *** createSession(...);
}

# Keep archive session contract and implementations
-keep interface com.omnireader.core.engine.modules.ArchiveSession {
    public *** getSanitizedFileEntries();
    public *** openVirtualFile(java.lang.String);
}
-keep class * implements com.omnireader.core.engine.modules.ArchiveSession {
    public *** getSanitizedFileEntries();
    public *** openVirtualFile(java.lang.String);
}

# Keep DocumentSession implementation names
-keepnames class * implements com.omnireader.core.engine.DocumentSession

# Keep generated AIDL IPC interfaces, stubs and proxies
-keep class com.omnireader.core.engine.IIsolatedEngineService* { *; }
-keep class com.omnireader.core.engine.ISessionStateCallback* { *; }
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}