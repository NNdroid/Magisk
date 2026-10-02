#!/usr/bin/env python3

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    file = ROOT / path
    if not file.is_file():
        raise AssertionError(f"missing required file: {path}")
    return file.read_text(encoding="utf-8")


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise AssertionError(f"{label}: expected {needle!r}")


def forbid(text: str, needle: str, label: str) -> None:
    if needle in text:
        raise AssertionError(f"{label}: forbidden {needle!r}")


def main() -> int:
    manifest = read("app/apk/src/main/AndroidManifest.xml")
    shared_manifest = read("app/shared/src/main/AndroidManifest.xml")
    main_activity = read("app/apk/src/main/java/com/topjohnwu/magisk/ui/MainActivity.kt")
    main_screen = read("app/apk/src/main/java/com/topjohnwu/magisk/ui/MainScreen.kt")
    theme = read("app/apk/src/main/java/com/topjohnwu/magisk/ui/MagiskTheme.kt")
    resource_theme = read("app/apk/src/main/res/values/themes.xml")
    log_screen = read("app/apk/src/main/java/com/topjohnwu/magisk/ui/log/LogScreen.kt")
    module_screen = read("app/apk/src/main/java/com/topjohnwu/magisk/ui/module/ModuleScreen.kt")
    tv_file_picker = read("app/apk/src/main/java/com/topjohnwu/magisk/ui/install/TvFilePicker.kt")
    config = read("app/core/src/main/java/com/topjohnwu/magisk/core/Config.kt")
    webui_manager = read("app/apk/src/main/java/com/topjohnwu/magisk/webui/WebUiManager.kt")
    webui_network = read("app/apk/src/main/java/com/topjohnwu/magisk/webui/WebUiNetwork.kt")
    webui_service = read("app/apk/src/main/java/com/topjohnwu/magisk/webui/WebUiService.kt")
    webui_server = read("app/apk/src/main/java/com/topjohnwu/magisk/webui/WebUiServer.kt")
    webui_installer = read("app/apk/src/main/java/com/topjohnwu/magisk/webui/WebUiInstaller.kt")
    webui_html = read("app/apk/src/main/assets/webui/index.html")
    webui_css = read("app/apk/src/main/assets/webui/app.css")
    webui_js = read("app/apk/src/main/assets/webui/app.js")
    ci_prop = read(".github/ci.prop")

    require(manifest, 'android:name="android.software.leanback"', "manifest")
    require(manifest, 'android:required="true"', "manifest")
    require(manifest, "android.intent.category.LEANBACK_LAUNCHER", "manifest")
    require(manifest, 'android:screenOrientation="landscape"', "manifest")
    require(manifest, 'android:banner="@drawable/tv_banner"', "manifest")
    require(manifest, 'android.permission.ACCESS_LOCAL_NETWORK', "Android 17 WebUI LAN permission")
    require(manifest, 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE', "persistent WebUI permission")
    require(manifest, 'android:name=".webui.WebUiService"', "persistent WebUI service")
    require(manifest, 'android:foregroundServiceType="specialUse"', "persistent WebUI service")
    require(manifest, 'android:stopWithTask="false"', "persistent WebUI service")
    require(shared_manifest, 'android.permission.FOREGROUND_SERVICE', "persistent WebUI base permission")
    forbid(shared_manifest, 'android.permission.FOREGROUND_SERVICE"\n        android:maxSdkVersion="33"', "persistent WebUI base permission")
    forbid(manifest, "android.intent.category.LAUNCHER", "manifest")

    require(main_activity, "Manifest.permission.ACCESS_LOCAL_NETWORK", "Android 17 WebUI LAN permission")
    require(main_activity, "requestLocalNetworkPermission.launch", "Android 17 WebUI LAN permission")
    require(main_activity, "WebUiManager.reportError", "WebUI LAN permission error reporting")

    require(main_screen, "NavigationRail(", "main screen")
    require(main_screen, ".tvFocusFrame(", "main screen")
    require(main_screen, "BackHandler {", "main screen remote navigation")
    require(main_screen, "DOUBLE_BACK_EXIT_TIMEOUT_MS = 2_000L", "main screen double-Back exit")
    require(main_screen, "rootActivity?.finish()", "main screen double-Back exit")
    require(main_screen, "tv_press_back_again_to_exit", "main screen double-Back prompt")
    require(main_screen, ".focusGroup()", "main screen remote navigation")
    require(main_screen, "WebUiCard(", "Home WebUI QR entry")
    forbid(main_screen, "ShortNavigationBar", "main screen")
    forbid(main_screen, "HorizontalPager", "main screen")
    forbid(main_screen, "isTelevision", "main screen")

    require(theme, "typography = MagiskTvTypography", "theme")
    forbid(theme, "if (tv)", "theme")

    require(resource_theme, 'style name="SplashTheme" parent="Theme.SplashScreen.IconBackground"', "TV splash theme")
    require(resource_theme, '<item name="windowSplashScreenAnimatedIcon">@drawable/ic_magisk</item>', "TV splash theme")
    require(resource_theme, '<item name="windowSplashScreenBackground">#101716</item>', "TV splash theme")
    require(resource_theme, '<item name="postSplashScreenTheme">@style/Main</item>', "TV splash theme")

    forbid(log_screen, "HorizontalPager", "log screen")
    forbid(log_screen, "rememberPagerState", "log screen")

    require(module_screen, "TvFilePickerDialog(", "module TV file picker")
    require(module_screen, "showLocalFilePicker", "module TV file picker")
    require(module_screen, 'allowedExtensions = moduleExtensions', "module TV file picker")
    require(tv_file_picker, "desiredEntryPath", "TV file picker focus restore")
    require(tv_file_picker, "rootFocusIndex", "TV file picker focus restore")

    require(config, "var webUiPort by preference(Key.WEBUI_PORT, 18091)", "WebUI default port")
    require(config, "var webUiAuthMode by preference(Key.WEBUI_AUTH_MODE, Value.WEBUI_AUTH_RANDOM_TOKEN)", "WebUI secure default")
    require(config, "WEBUI_THEME_SYSTEM", "WebUI theme modes")
    require(config, "WEBUI_THEME_LIGHT", "WebUI theme modes")
    require(config, "WEBUI_THEME_DARK", "WebUI theme modes")

    require(webui_manager, '"$base/#token=${Uri.encode(token)}"', "WebUI QR token fragment")
    require(webui_manager, "IPV6_ANY", "WebUI IPv6-first listener")
    require(webui_manager, "boundPort", "WebUI bound-port ownership")
    require(webui_manager, "canConnectEventually(IPV4_LOOPBACK, port)", "WebUI dual-stack capability probe")
    require(webui_manager, "error.isAddressAlreadyInUse()", "WebUI dual-stack EADDRINUSE handling")
    require(webui_manager, "awaitServersStopped(servers)", "WebUI bounded listener teardown")
    require(webui_manager, "if (ipv4Probe != true)", "WebUI IPv4 fallback listener")
    require(webui_manager, "context !is WebUiService", "WebUI service ownership")
    require(webui_manager, "WebUiService.start", "WebUI service ownership")
    require(webui_manager, "WebUiNetworkMonitor", "WebUI network monitor")
    forbid(webui_manager, "if (!ipv4Ok && !ipv6Ok)", "WebUI probe-driven listener teardown")

    require(webui_network, "ConnectivityManager", "WebUI Android network discovery")
    require(webui_network, "getLinkProperties", "WebUI LinkProperties discovery")
    require(webui_network, "registerDefaultNetworkCallback", "WebUI network change refresh")
    require(webui_network, "is Inet6Address -> \"[$rawHost]\"", "WebUI bracketed IPv6 URL")
    require(webui_network, "substringBefore('%')", "WebUI remote IPv6 scope handling")
    require(webui_network, "NetworkInterface.getNetworkInterfaces", "WebUI address fallback")
    forbid(webui_network, 'return listOf("http://127.0.0.1:$port")', "WebUI remote URL fallback")

    require(webui_service, "START_STICKY", "persistent WebUI restart policy")
    require(webui_service, "startForegroundService", "persistent WebUI start")
    require(webui_service, "FOREGROUND_SERVICE_TYPE_SPECIAL_USE", "persistent WebUI type")
    require(webui_server, ") : NanoHTTPD(bindHost, port)", "WebUI explicit address-family binding")
    require(webui_server, 'authorization.startsWith("Bearer "', "WebUI bearer auth")
    require(webui_server, 'path == "/api/install/module"', "WebUI module upload API")
    require(webui_server, 'path == "/api/install/patch"', "WebUI patch upload API")
    require(webui_installer, "FlashZip(Uri.fromFile(upload.file)", "WebUI module installer reuse")
    require(webui_installer, "MagiskInstaller.Patch(Uri.fromFile(upload.file)", "WebUI patch installer reuse")
    require(webui_html, 'data-page="install"', "WebUI install page")
    require(webui_html, 'data-page="webui"', "WebUI settings page")
    require(webui_css, 'data-theme="dark"', "WebUI dark theme")
    require(webui_js, "setTheme(data.theme)", "WebUI runtime theme")
    require(webui_js, "'/api/install/module'", "WebUI module upload frontend")
    require(webui_js, "'/api/install/patch'", "WebUI patch upload frontend")

    require(ci_prop, "abiList=arm64-v8a", "CI ABI configuration")

    print("Android TV fork contract: OK")
    print("- Leanback-only launcher")
    print("- landscape TV activity")
    print("- TV navigation rail and double-Back exit")
    print("- visible TV focus UI")
    print("- TV splash icon and dark handoff")
    print("- built-in TV picker for patching and module installs")
    print("- TV file picker focus restoration")
    print("- WebUI Home QR entry on port 18091")
    print("- WebUI Android 17 local-network runtime permission")
    print("- WebUI adaptive IPv4/IPv6 listener ownership and probing")
    print("- WebUI LinkProperties address discovery and network refresh")
    print("- WebUI sticky special-use foreground service")
    print("- WebUI random-token secure default")
    print("- WebUI system/light/dark themes")
    print("- WebUI browser module installation and image patching")
    print("- no phone pager/bottom navigation")
    print("- arm64-v8a CI build")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"Android TV fork contract failed: {error}", file=sys.stderr)
        raise SystemExit(1)
