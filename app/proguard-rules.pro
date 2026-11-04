# TDLib's generated TdApi classes are part of the JNI contract. Keep them intact.
-keep class org.drinkless.tdlib.** { *; }
-keepclassmembers class * implements org.drinkless.tdlib.Client$ResultHandler {
    public void onResult(org.drinkless.tdlib.TdApi$Object);
}
