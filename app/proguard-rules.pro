# OkHttp ships optional Conscrypt/BouncyCastle hooks that R8 warns about.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Room generates the database implementation at build time.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# WorkManager instantiates workers reflectively by class name.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
