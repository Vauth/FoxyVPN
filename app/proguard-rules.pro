-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class io.nekohasekai.libbox.** { *; }
-dontwarn io.nekohasekai.libbox.**

-keep class go.** { *; }
-dontwarn go.**

-keep class * implements io.nekohasekai.libbox.PlatformInterface { *; }
-keep class * implements io.nekohasekai.libbox.CommandServerHandler { *; }
-keep class * implements io.nekohasekai.libbox.CommandClientHandler { *; }
-keep class * implements io.nekohasekai.libbox.AutoRedirectHandler { *; }
-keep class com.vauth.foxyvpn.vpn.FoxyPlatformInterface { *; }

-keepattributes JavascriptInterface, *Annotation*

-keep class com.vauth.foxyvpn.ui.screens.WebChannelBridge { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.vauth.foxyvpn.data.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class com.vauth.foxyvpn.vpn.IpLocationInfo { *; }
-keep class com.vauth.foxyvpn.vpn.VpnLiveStats { *; }

