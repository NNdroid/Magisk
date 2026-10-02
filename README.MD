![](docs/images/logo.png)

# Magisk for Android TV

[![Android TV Build](https://github.com/NNdroid/MagiskForAndroidTv/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/NNdroid/MagiskForAndroidTv/actions/workflows/build.yml)
[![Upstream](https://img.shields.io/badge/upstream-topjohnwu%2FMagisk-00AF9C)](https://github.com/topjohnwu/Magisk)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

> This repository is an **Android TV-focused fork of Magisk**. It is not an official Magisk release and is not affiliated with or supported by Google.

## Introduction

This fork keeps the Magisk root and module functionality provided by the upstream project, while redesigning the modern Magisk application for **Android TV / Google TV devices operated primarily with a remote control**.

The application UI is intentionally **TV-only**. The phone bottom navigation and swipe-first layouts have been removed from the modern `app/apk` interface.

### TV-specific features

- **Leanback-only launcher** with landscape TV presentation
- **D-pad / remote-first navigation** throughout the main application
- Persistent **left-side navigation rail** instead of phone bottom navigation
- Large TV typography, spacing, cards, dialogs, and focus targets
- Visible focus scaling, border, and background feedback for remote navigation
- TV-optimized Home, Modules, Superuser, Settings, DenyList, Logs, install, flash, and module-action screens
- Direct tab selection instead of swipe-only pagers
- Wider TV dialogs with a predictable default focus target
- TV splash screen with a centered Magisk icon and dark background
- Built-in **Magisk TV file picker** for selecting boot images and patch files without requiring a third-party file manager
- Optional Android system file picker fallback when one is installed
- Browsing support for internal storage, Downloads, and detected USB/SD storage
- Patch-file filtering for `.img`, `.bin`, `.tar`, `.tar.md5`, and `.zip`

## Magisk Features

The underlying Magisk project provides, among other components:

- **MagiskSU**: root access for applications
- **Magisk Modules**: systemless modification through modules
- **MagiskBoot**: unpacking and repacking Android boot images
- **Zygisk**: running code inside Android application processes

For upstream Magisk documentation and behavior that is not specific to this TV fork, refer to the official upstream project.

## Compatibility

This fork targets Android TV devices and requires the Android Leanback feature.

The current fork CI builds artifacts with:

```properties
abiList=arm64-v8a
```

As a result, the automatically produced artifacts are intended primarily for **64-bit ARM Android TV devices**.

The application manifest intentionally exposes `LEANBACK_LAUNCHER` rather than the normal phone launcher entry.

## Selecting and Patching Images on TV

Many Android TV firmware images do not ship with `DocumentsUI` or a conventional file manager. To avoid depending on those components, this fork includes its own remote-friendly file browser.

From **Install → Select and Patch a File**, the built-in picker can browse readable shared storage and removable media. On Android 11 and newer, direct filesystem browsing requires **All files access**. The app provides a TV-friendly entry to the appropriate Android settings screen when this permission is needed.

If the TV has a compatible Android system file picker installed, it remains available as an optional fallback.

## Downloads and CI Artifacts

This fork is built and tested by the repository's **Android TV Build** GitHub Actions workflow.

The fork-specific CI performs:

- Android TV contract validation
- Ubuntu-based `arm64-v8a` native and application builds
- Repository tests
- Build artifact upload after a successful build
- Optional custom JKS signing for trusted `master` and manual builds

The CI intentionally does not run the upstream phone AVD matrix, x86 phone tests, macOS build matrix, or Windows build matrix because this fork targets Android TV and its automated artifacts are arm64-focused.

For **official Magisk releases**, use the upstream project:

- https://github.com/topjohnwu/Magisk/releases

For this TV fork, use this repository's Actions/build artifacts or releases when available. Fork builds are not official Magisk releases.

## GitHub Actions JKS Signing

The Android TV build can sign generated APKs with a private JKS stored entirely in GitHub Actions Secrets. The repository never commits the keystore or signing passwords.

Configure all four repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `TV_SIGNING_JKS_BASE64` | Base64-encoded contents of the `.jks` keystore |
| `TV_SIGNING_STORE_PASSWORD` | Keystore/store password |
| `TV_SIGNING_KEY_ALIAS` | Alias of the signing key in the keystore |
| `TV_SIGNING_KEY_PASSWORD` | Password for the selected key alias |

All four secrets must be configured together. If none are configured, the workflow keeps using the project's default debug signing key. If only some are configured, the build fails early with a clear error instead of producing an incorrectly signed artifact.

On Linux, a JKS can be converted to a single-line Base64 value with:

```bash
base64 -w 0 release.jks
```

A portable alternative is:

```bash
base64 < release.jks | tr -d '\r\n'
```

The resulting text is the value for `TV_SIGNING_JKS_BASE64`.

Custom signing is intentionally enabled only for:

- pushes to `master`
- manually started `workflow_dispatch` builds

Pull-request builds do **not** receive the custom signing secrets. This prevents untrusted or review-stage PR code from accessing the private signing key.

During a signed build, Actions:

1. decodes the JKS into the runner temporary directory;
2. validates the keystore password and alias with `keytool`;
3. injects `keyStore`, `keyStorePass`, `keyAlias`, and `keyPass` into a temporary Magisk build config;
4. builds with Magisk's existing signing configuration;
5. deletes the temporary JKS and signing config before artifact upload.

Only the generated `out/` build artifacts are uploaded.

## Building

Use the repository build script rather than invoking arbitrary Gradle tasks directly:

```bash
python build.py -v -c .github/ci.prop all
```

To build native binaries first and then the application separately:

```bash
python build.py -v -c .github/ci.prop binary
python build.py -v -c .github/ci.prop app
```

Magisk's Android modules expect native binaries to exist before some Gradle application tasks run. If Android Studio reports `syncReleaseJniLibs` / `syncDebugJniLibs` errors asking you to build binaries first, run the `binary` build command above.

## Upstream Documentation

- [Official Magisk repository](https://github.com/topjohnwu/Magisk)
- [Installation instructions](https://topjohnwu.github.io/Magisk/install.html)
- [Building and development](https://topjohnwu.github.io/Magisk/build.html)
- [Magisk documentation](https://topjohnwu.github.io/Magisk/)
- [Zygisk module sample](https://github.com/topjohnwu/zygisk-module-sample)

## Reporting Issues

When reporting a problem with this fork, include enough information to distinguish a TV UI issue from an upstream Magisk core issue.

Useful information includes:

- Android / Android TV version and device model
- CPU architecture
- Debug build version or commit SHA
- Exact screen/action that failed
- Whether the issue can be reproduced with a remote control
- `logcat` for application crashes
- Installation/patch logs for install issues
- Boot image and relevant boot logs when appropriate

For bugs that reproduce on unmodified upstream Magisk and are unrelated to this fork's Android TV changes, consult the upstream Magisk project and its contribution/reporting requirements.

## Translation Contributions

The upstream Magisk string resources remain in their existing locations. This fork also contains TV-specific resources, including the built-in TV file picker strings.

Relevant resources include:

- `app/core/src/main/res/values/strings.xml`
- `app/stub-res/src/main/res/values/strings.xml`
- `app/apk/src/main/res/values/tv_file_picker.xml`

Translations should use the corresponding Android `values-[lang]` resource directories.

## Upstream and Credits

This project is based on [topjohnwu/Magisk](https://github.com/topjohnwu/Magisk). The Magisk core, native components, and substantial portions of the application originate from the upstream Magisk project and its contributors.

The changes in this fork primarily focus on making the modern application usable as a dedicated Android TV application with remote-control navigation.

## License

Magisk and this fork are free software distributed under the GNU General Public License, version 3 or later. See [LICENSE](LICENSE) for the full license text.
