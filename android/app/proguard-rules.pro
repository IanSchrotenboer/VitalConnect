# Vital Connect: regels voor R8 (verkleinen en optimaliseren van de release-build).
# De meeste bibliotheken (Compose, OkHttp, WorkManager, Health Connect) leveren hun eigen regels mee.

# Credential Manager met Google-inloggen: de Play Services-koppeling wordt via reflectie geladen
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# WorkManager maakt SyncWorker via reflectie aan (constructor met Context en WorkerParameters)
-keep class nl.vitaal.app.SyncWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# OkHttp: optionele TLS-platforms die op Android niet bestaan
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.serialization: de app gebruikt alleen JsonElement (geen @Serializable-klassen), dus geen extra regels nodig

# Leesbare foutmeldingen in Play Console (crashes blijven te herleiden met mapping.txt)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
