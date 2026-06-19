# ProGuard 规则

# JSch
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# JNA
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# Pty4J
-keep class com.pty4j.** { *; }
-dontwarn com.pty4j.**
