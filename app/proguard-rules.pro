-dontobfuscate
-dontwarn **
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep Android components
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.app.Application
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep BerryBot code
-keep class com.berrybot.** { *; }
-keep class de.mud.terminal.** { *; }

# Keep Trilead SSH core & dynamically loaded ciphers/kex/signatures
-keep class com.trilead.ssh2.Connection { *; }
-keep class com.trilead.ssh2.Session { *; }
-keep class com.trilead.ssh2.ConnectionMonitor { *; }
-keep class com.trilead.ssh2.crypto.cipher.** { *; }
-keep class com.trilead.ssh2.crypto.digest.** { *; }
-keep class com.trilead.ssh2.crypto.dh.** { *; }
-keep class com.trilead.ssh2.crypto.keys.** { *; }
-keep class com.trilead.ssh2.signature.** { *; }
-keep class com.trilead.ssh2.transport.** { *; }
-keep class com.trilead.ssh2.auth.** { *; }
-keep class com.trilead.ssh2.channel.** { *; }
-keep class com.trilead.ssh2.packets.** { *; }
-keep class com.google.crypto.tink.subtle.** { *; }
-keep class com.jcraft.jzlib.** { *; }
