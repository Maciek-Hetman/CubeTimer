# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# --- CubeTimer ---------------------------------------------------------------------------------
# Keep line numbers so release crash stack traces are usable (mapping.txt de-obfuscates names).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# TNoodle requests SecureRandom.getInstance("SHA1PRNG"). AndroidSha1PrngProvider registers the SPI
# by class name, and java.security.Provider instantiates it reflectively through its no-arg
# constructor - which R8 would otherwise remove as unused.
-keep class com.maciekhetman.cubetimer.domain.AndroidSha1PrngProvider { <init>(); }
-keep class com.maciekhetman.cubetimer.domain.AndroidSha1PrngProvider$AndroidSha1PrngSecureRandom { <init>(); }

# Tink (via androidx.security:security-crypto) references errorprone's source-only annotations.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# TNoodle's PuzzleRegistry instantiates every puzzle reflectively (LazySupplier ->
# Class.getConstructor(...).newInstance(...)), which R8 can't see: without this it strips e.g.
# FourByFourCubePuzzle's constructor and scramble generation fails at runtime.
-keep public class * extends org.worldcubeassociation.tnoodle.scrambles.Puzzle {
    public <init>(...);
}
