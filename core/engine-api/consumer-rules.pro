# Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
# SPDX-License-Identifier: GPL-3.0-or-later

-keep interface me.hlebkukleuski.fullfilestudio.core.engine.DynamicEngineFactory { *; }
-keep class * implements me.hlebkukleuski.fullfilestudio.core.engine.DynamicEngineFactory {
    public <init>();
    public *** canHandle(...);
    public *** createSession(...);
    public *** createSessionWithPassword(...);
}
-keep interface me.hlebkukleuski.fullfilestudio.core.engine.bridge.GplCoreEngineBridge { *; }
-keep class * implements me.hlebkukleuski.fullfilestudio.core.engine.bridge.GplCoreEngineBridge {
    public <init>();
    public *** createDjvuSession(...);
    public *** createMobiSession(...);
    public *** createAccessSession(...);
    public *** renderDwg(...);
}
-keep interface me.hlebkukleuski.fullfilestudio.core.engine.modules.ArchiveSession {
    public *** getSanitizedFileEntries();
    public *** openVirtualFile(java.lang.String);
}
-keep class * implements me.hlebkukleuski.fullfilestudio.core.engine.modules.ArchiveSession {
    public *** getSanitizedFileEntries();
    public *** openVirtualFile(java.lang.String);
}
-keepnames class * implements me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
-keep class me.hlebkukleuski.fullfilestudio.core.engine.IIsolatedEngineService* { *; }
-keep class me.hlebkukleuski.fullfilestudio.core.engine.ISessionStateCallback* { *; }
