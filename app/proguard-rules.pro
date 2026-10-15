# OkHttp ships optional Conscrypt/BouncyCastle hooks that R8 warns about.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Room generates the database implementation at build time.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# WorkManager instantiates workers reflectively by class name.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# NewPipeExtractor (YouTube). Rhino runs YouTube's player JavaScript and finds classes reflectively;
# the extractor's own parsers are looked up by name. Keep both whole rather than chase breakage.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
-dontwarn javax.annotation.**
-dontwarn org.jspecify.**
-dontwarn com.google.re2j.**
