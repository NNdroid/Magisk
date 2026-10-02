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
    main_screen = read("app/apk/src/main/java/com/topjohnwu/magisk/ui/MainScreen.kt")
    theme = read("app/apk/src/main/java/com/topjohnwu/magisk/ui/MagiskTheme.kt")
    resource_theme = read("app/apk/src/main/res/values/themes.xml")
    log_screen = read("app/apk/src/main/java/com/topjohnwu/magisk/ui/log/LogScreen.kt")
    ci_prop = read(".github/ci.prop")

    require(manifest, 'android:name="android.software.leanback"', "manifest")
    require(manifest, 'android:required="true"', "manifest")
    require(manifest, "android.intent.category.LEANBACK_LAUNCHER", "manifest")
    require(manifest, 'android:screenOrientation="landscape"', "manifest")
    require(manifest, 'android:banner="@drawable/tv_banner"', "manifest")
    forbid(manifest, "android.intent.category.LAUNCHER", "manifest")

    require(main_screen, "NavigationRail(", "main screen")
    require(main_screen, ".tvFocusFrame(", "main screen")
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

    require(ci_prop, "abiList=arm64-v8a", "CI ABI configuration")

    print("Android TV fork contract: OK")
    print("- Leanback-only launcher")
    print("- landscape TV activity")
    print("- TV navigation rail and focus UI")
    print("- TV splash icon and dark handoff")
    print("- no phone pager/bottom navigation")
    print("- arm64-v8a CI build")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"Android TV fork contract failed: {error}", file=sys.stderr)
        raise SystemExit(1)
