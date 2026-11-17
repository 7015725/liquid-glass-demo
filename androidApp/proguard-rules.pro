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
# LSPosed module entry + status probe
-keep class com.flowglass.demo.xposed.** { *; }
-dontwarn de.robv.android.xposed.**
-dontwarn io.github.libxposed.**

# Modern API 101 entry is loaded by name from META-INF/xposed/java_init.list. Keep the class name
# in the list intact; Java resources under META-INF are not Android resources.
-keep,allowobfuscation class com.flowglass.demo.xposed.Api101Entry { public <init>(); }
-adaptresourcefilecontents META-INF/xposed/java_init.list

# Rhino loads interpreter classes reflectively; keep the engine intact in release builds.
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
