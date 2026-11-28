-dontwarn org.conscrypt.**
-dontwarn okhttp3.internal.platform.ConscryptPlatform


# ML Kit, which runs Gemini Nano, creates its component registrars by reflection from manifest
# metadata. R8 full mode otherwise drops their no-argument constructors.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
