-dontwarn org.conscrypt.**
-dontwarn okhttp3.internal.platform.ConscryptPlatform


# ML Kit, which recognizes speech on the phone, creates its component registrars by reflection from manifest
# metadata. R8 full mode otherwise drops their no-argument constructors.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }

# Ktor, which Koog's agent modules bring, detects an attached JVM debugger through
# java.lang.management. Android has no such API and the check is never reached on a phone.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
