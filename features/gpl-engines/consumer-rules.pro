-keep interface me.hlebkukleuski.fullfilestudio.core.engine.bridge.GplCoreEngineBridge { *; }
-keep class * implements me.hlebkukleuski.fullfilestudio.core.engine.bridge.GplCoreEngineBridge {
    public <init>();
    public *** createDjvuSession(...);
    public *** createMobiSession(...);
    public *** createAccessSession(...);
    public *** renderDwg(...);
}
-keep interface me.hlebkukleuski.fullfilestudio.feature.cadvector.GplCad3dBridge { *; }
-keep class * implements me.hlebkukleuski.fullfilestudio.feature.cadvector.GplCad3dBridge {
    public <init>();
    public *** parseBlend(...);
    public *** parseFbx(...);
}
