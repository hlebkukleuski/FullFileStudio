-keep interface com.omnireader.core.engine.impl.GplCoreEngineBridge { *; }
-keep class * implements com.omnireader.core.engine.impl.GplCoreEngineBridge {
    public <init>();
    *;
}
-keep interface me.hlebkukleuski.fullfilestudio.feature.cadvector.GplCad3dBridge { *; }
-keep class * implements me.hlebkukleuski.fullfilestudio.feature.cadvector.GplCad3dBridge {
    public <init>();
    *;
}
