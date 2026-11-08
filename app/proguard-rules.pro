# Keep app classes (manifest/meta-data referenced, e.g. Cast OptionsProvider) and keep stack traces readable.
-dontobfuscate
-keep class com.mk.iptv.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature, SourceFile, LineNumberTable
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
