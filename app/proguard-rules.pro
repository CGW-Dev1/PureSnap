# PureSnap ProGuard / R8 Rules
-keep class com.puresnap.app.model.** { *; }
-keep class com.puresnap.app.data.local.** { *; }
-keep class com.puresnap.app.data.preferences.** { *; }

# Keep Gson generic type signatures and annotations
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# Keep WebView JavascriptInterface methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
