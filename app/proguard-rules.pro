# Room entities and ML Kit model classes are referenced reflectively.
-keep class com.intrusivethots.mosaic.data.** { *; }
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# AuthorizationClient is called through Play services and must survive release shrinking.
-keep class com.google.android.gms.auth.api.identity.** { *; }

# EncryptedSharedPreferences reaches Tink through reflection.
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
