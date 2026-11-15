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

# 1. Keep dynamic module factory interface and implementations (instantiated via Class.forName)
-keep interface com.omnireader.core.engine.DynamicEngineFactory { *; }
-keep class * implements com.omnireader.core.engine.DynamicEngineFactory {
    public <init>();
    public *** canHandle(...);
    public *** createSession(...);
}

# 2. Keep ArchiveSession contract (invoked in IsolatedRenderService)
-keep interface com.omnireader.core.engine.modules.ArchiveSession {
    public *** getSanitizedFileEntries();
    public *** openVirtualFile(java.lang.String);
}
-keep class * implements com.omnireader.core.engine.modules.ArchiveSession {
    public *** getSanitizedFileEntries();
    public *** openVirtualFile(java.lang.String);
}

# 3. Keep class names of DocumentSession implementations (used by UI heuristics in TiledZoomableCanvas)
-keepnames class * implements com.omnireader.core.engine.DocumentSession

# 4. Keep native security arena JNI class (bound to native_security_arena.cpp)
-keep class com.omnireader.core.engine.SecurityArena {
    public static final com.omnireader.core.engine.SecurityArena INSTANCE;
    public static <methods>;
    native <methods>;
}

# 5. Keep generated AIDL IPC interfaces
-keep class * extends android.os.IInterface { *; }

# 6. Keep ONNX Runtime JNI classes & native callbacks (required when android.enableR8.fullMode=true)
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# 7. Suppress R8 warnings for optional Zstandard JNI codec in commons-compress
-dontwarn com.github.luben.zstd.**