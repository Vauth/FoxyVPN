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

# The FxA WebChannel bridge is reached from JS by its registered interface name;
# keep its members so release builds never strip the @JavascriptInterface methods.
-keep class com.vauth.foxyvpn.ui.screens.WebChannelBridge { *; }
