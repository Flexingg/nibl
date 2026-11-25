# Nibl / ByteLog R8 rules
#
# Release builds are shrunk to drop library code the app never uses (icons, ML Kit, Guava...). The app's own
# classes are kept whole and nothing is renamed: Room, kotlinx.serialization and a few reflective lookups keep
# working exactly as in debug builds, and crash reports stay readable.

-dontobfuscate
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

# Everything in the app itself
-keep class com.bytelog.app.** { *; }
-keepclassmembers class * {
    @androidx.room.* *;
}

# kotlinx.serialization: generated serializers and companions
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }

# Optional dependencies some libraries mention but don't need at runtime
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.re2j.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn afu.org.checkerframework.**
-dontwarn kotlinx.serialization.**
