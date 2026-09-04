-dontwarn io.netty.**
-keep class io.netty.** { *; }

-keep class com.vauth.foxyvpn.vpn.tun.HevSocks5Tunnel { *; }
-keepnames class com.vauth.foxyvpn.vpn.tun.HevSocks5Tunnel
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

-dontwarn io.netty.internal.tcnative.**
-dontwarn io.netty.handler.ssl.OpenSsl**

-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

-dontwarn com.google.errorprone.annotations.**

-dontwarn reactor.blockhound.**
