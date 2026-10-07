<img src="https://raw.githubusercontent.com/skylot/jadx/master/jadx-gui/src/main/resources/logos/jadx-logo.png" width="64" align="left" />

## JADX

![Build status](https://img.shields.io/github/actions/workflow/status/skylot/jadx/build-artifacts.yml)
![GitHub contributors](https://img.shields.io/github/contributors/skylot/jadx)
![GitHub all releases](https://img.shields.io/github/downloads/skylot/jadx/total)
![GitHub release (latest by SemVer)](https://img.shields.io/github/downloads/skylot/jadx/latest/total)
![Latest release](https://img.shields.io/github/release/skylot/jadx.svg)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.skylot/jadx-core)](https://search.maven.org/search?q=g:io.github.skylot%20AND%20jadx)
![Java 11+](https://img.shields.io/badge/Java-11%2B-blue)
[![License](http://img.shields.io/:license-apache-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)

**jadx** - Dex to Java decompiler

Command line and GUI tools for producing Java source code from Android Dex and Apk files

> [!WARNING]
> Please note that in most cases **jadx** can't decompile all 100% of the code, so errors will occur.<br />
> Check [Troubleshooting guide](https://github.com/skylot/jadx/wiki/Troubleshooting-Q&A#decompilation-issues) for workarounds.

**Main features:**
- decompile Dalvik bytecode to Java code from APK, dex, aar, aab and zip files
- decode `AndroidManifest.xml` and other resources from `resources.arsc`
- deobfuscator included

**jadx-gui features:**
- view decompiled code with highlighted syntax
- jump to declaration
- find usage
- full text search
- smali debugger, check [wiki page](https://github.com/skylot/jadx/wiki/Smali-debugger) for setup and usage
- privacy impact overview: rank services, receivers and classes by the privacy relevant Android APIs they reach,
  highlight permission-protected API calls in the code, find all code using a permission, see [below](#privacy-impact-overview)

Jadx-gui key bindings can be found [here](https://github.com/skylot/jadx/wiki/JADX-GUI-Key-bindings)

See these features in action here: [jadx-gui features overview](https://github.com/skylot/jadx/wiki/jadx-gui-features-overview)

<img src="https://user-images.githubusercontent.com/118523/142730720-839f017e-38db-423e-b53f-39f5f0a0316f.png" width="700"/>

### Privacy Impact Overview

A jadx-gui window that gives a malware reverse engineer a quick overview of which parts of an Android app
have the largest impact on the privacy of targeted users.
Open it with **Tools → Privacy Impact Overview** (available when an app is loaded).

#### Usage

- **Group by service/receiver** (top bar toggle, on by default): one row per `<service>` and `<receiver>` declared in
  `AndroidManifest.xml`, plus receivers registered at runtime. When off, one row per (top-level) class of the decompiled app.
- Columns:
  - **Name**: class name (deobfuscated alias if renamed)
  - **Score**: privacy score, the table is sorted by it (highest first). The cell is color coded from white (score 0)
    to dark red (highest score among all rows of the current view; text filters don't change the colors)
  - **Permissions / capabilities**: privacy relevant capabilities used, e.g. `Screen capture (MediaProjection) ×2, Location ×1`;
    high impact capabilities (score 2) are bold. With more than 3 capabilities, abbreviations are shown instead
    (e.g. `SCREEN×2  A11Y  LOC  MIC×3`, `×N` omitted for 1; the row tooltip shows the full names with their abbreviations):
    `A11Y` accessibility, `SCREEN` screen capture, `SECSET`/`SYSSET` secure/system settings, `ADMIN` device admin,
    `NOTIF` notification listener, `IME` keyboard, `INSTALL` install packages, `OVERLAY`, `SMS` read SMS, `SMS-IN` intercept SMS,
    `LOC` location, `CAM` camera, `MIC` microphone, `CONTACTS`, `CALLLOG`, `CAL` calendar, `PHONE` phone identifiers,
    `CALL-OUT` outgoing calls, `SMS-OUT` send SMS, `MMS`, `ACCOUNTS`, `KEYGUARD`, `SIP`, `BROWSER`, `FILES` files and media,
    `APPS` installed apps, `BT` bluetooth, `USAGE` app usage;
    `×N` is the number of distinct APIs used for that capability. Hover the row for the permission names and the list of APIs
  - **Type**: Service, Receiver, Receiver (dynamic) or Class
  - **Flags**: `DEX` (loads code at runtime), `REFL` (reflective calls), `MISSING` (class declared in manifest but not found in the code)
- **Evidence** panel (bottom): for the selected row, a tree of capability → API → call sites. Each call site lists the
  shortest call path from the entry point of the service/receiver/class down to the method calling the API.
  Double-click a method to open it in the code view, double-click a table row to open the class.
  The window is not modal, so you can keep it open while browsing the code.
- Filters: **Hide score 0** (on by default), **Hide libraries** (`androidx.`, `com.google.`, `kotlin.`, `okhttp3.`, ...;
  off by default, because malware often hides in such package names), **Direct calls only** (class view only, see below),
  a text filter (matches name and capabilities), and the excluded packages from jadx preferences are also hidden.
- **Re-run analysis** recomputes the results.

#### Highlighting in the code view

In the decompiled code of every class, usages of APIs which require a permission are highlighted:
- **light red**: a privacy relevant permission (a capability of the score table below, counted in the overview);
- **light amber**: any other permission from the [Android permission database](#android-permission-database)
  (e.g. `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, signature permissions).

While the mouse is over a highlight, an annotation is shown above the code line (only while hovering):

```
@RequiresPermission(allOf = {ACCESS_FINE_LOCATION, ACCESS_WIFI_STATE})
Location · privacy score +1 · dangerous · API 33–37 · Android SDK annotations
```

The first line is the requirement in the style of Android's `@RequiresPermission` annotation (`anyOf`: one of them is enough,
`allOf`: all are needed, `.Read`/`.Write` for content URIs). The second line shows the capability and its score
(or "no privacy score"), the protection level, the API levels in which the requirement is documented, `conditional`
if the permission is only needed in some cases (e.g. depending on arguments), and the source of the information.
For the requirement, the most reliable source is chosen (SDK annotations, then provider manifests, curated, rule table, Axplorer),
as documented for the newest API level.
Usages whose permission is not declared in `AndroidManifest.xml` are highlighted too, with the note
"Permission not declared in AndroidManifest.xml (likely unused code)" — they don't count in the overview scores.

Calls to framework methods have no code metadata in jadx, so the usage is detected from the method instructions
and then located in the decompiled text by name (method/field name, class name for constructors, string constant),
restricted to the method which actually contains the call (code of inlined lambdas belongs to the enclosing method).
Occurrences of the same name inside comments of that method may also be highlighted.
Database APIs are matched with their parameter types, so only the overloads which need a permission are highlighted
(if a method calls two overloads, the name search can't tell them apart and both are highlighted).

#### Permission usages

**Ctrl+click** a permission name in `AndroidManifest.xml` (e.g. in `<uses-permission android:name="android.permission.READ_SMS"/>`
or `android:permission="..."`; the name is underlined while Ctrl is pressed), or right-click it and choose
**Find usages of …**, to open the **Permission Usages** window (also in **Tools → Permission Usages**).

- Left: all permissions declared with `<uses-permission>` (plus the clicked one if it isn't declared), with protection level
  and the number of APIs needing it which the code uses. Permissions **declared but not used by any code** are shown in italic:
  for malware this often points to code loaded at runtime or called via reflection.
- Right: for the selected permission
  - manifest entries using it: components protected by it, intent filters for broadcasts which are sent with it
    (e.g. `SMS_RECEIVED` needs `RECEIVE_SMS`);
  - every API needing it which the code uses (with the source of the requirement and the API levels), the methods calling it
    (at most 30 per API), and for each call site the shortest call path from each service/receiver which reaches it
    (same call graph as the overview). "Not reached from a service or receiver" means the call site is only reachable from
    other code (activities, application class, unreferenced code).
- Double-click a method or class to open it.

The call graph is built once per loaded app and shared with the overview window. Usages are attributed per API name:
if only some overloads of a method need the permission, a call of another overload is also listed.

#### Persistence

The results are saved in the jadx project file (`.jadx`), so the analysis doesn't rerun when the project is reopened
(save the project to keep them, as for other project data).
Saved results are discarded and recomputed automatically when the input files changed (modification time), the rules changed
or a different permission database is used.
The call graph itself is not saved: if you open the window from saved results, call paths in the evidence panel
are computed in the background on first selection (this takes a few seconds, no decompilation needed).

#### How it works

1. **Call graph.** The app wide call graph is built from the raw Dalvik instructions of all methods (fast, no decompilation).
   Edges are added for:
   - direct calls to app methods;
   - virtual/interface calls: also to all overriding methods in subclasses (class hierarchy analysis);
   - lambdas and method references (`invoke-custom`);
   - object creation (`new C`): to all methods of `C` (and its app superclasses) which override a framework method.
     This covers asynchronous hand-offs and callbacks: `new Thread(runnable)` / `Executor.execute()` / `Handler.post()` → `run()`,
     `AsyncTask` → `doInBackground()`, registered listeners → `onClick()` etc., dynamically registered receivers → `onReceive()`.
     This over-approximates: the callback is counted even if the object is never actually passed to the framework.
2. **Privacy relevant APIs.** Each method is matched against the rule table
   [`privacy-rules.json`](jadx-gui/src/main/resources/privacy/privacy-rules.json) and the
   [Android permission database](#android-permission-database) by
   - framework method calls (e.g. `LocationManager.getLastKnownLocation`), also when called on an app subclass
     (e.g. `dispatchGesture` called on the app's own `AccessibilityService`); constructors only for the exact class;
   - static field access (e.g. `CallLog.Calls.CONTENT_URI`, `ContactsContract.Contacts.CONTENT_URI`);
   - string constants (content URIs like `content://sms` — the whole authority must match, so `content://mms` doesn't match
     `content://mms-sms` —, intent actions like `android.provider.Telephony.SMS_RECEIVED`, values of permission protected
     framework constants, which the compiler inlines).

   A database API counts for the rule capability of its permission (the capability with the highest score if it needs
   several), e.g. `WifiManager.getScanResults` (needs `ACCESS_FINE_LOCATION`) counts for **Location**.
   APIs which only need other permissions have score 0 and are not shown in the overview
   (but highlighted in code and listed in the permission usages).
   Overloads of a method are one API (counted once per row).
3. **Recursive score.** A row's score is the sum over all privacy APIs **reachable** from it in the call graph
   (computed with strongly connected components, so recursion and cycles are handled).
   Entry points of a service/receiver are all methods of its class, its app superclasses and its inner classes;
   for the class view, all methods of the class and its inner classes.
   Each distinct API counts **once per row**, no matter how many call sites reach it.
   With **Direct calls only** (class view), only the APIs called by the class itself are counted.
4. **Manifest.** The protection permission of a component (e.g. `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`)
   and its intent filter actions (e.g. `SMS_RECEIVED`, `NEW_OUTGOING_CALL`) also count as a usage, at most once per capability per component.
5. **Dead code is ignored.** An API only counts if the app declares what it needs: a `<uses-permission>` for one of the
   capability permissions, or, for bind permissions (accessibility, device admin, notification listener, input method),
   a manifest component protected by it. Such usages without a declaration are most likely unused library code
   and are not shown. Capabilities without a permission (screen capture, listing installed apps) always count.
6. **Not traceable.** Reflection (`Method.invoke`, `Constructor.newInstance`, `Class.newInstance`) and runtime code loading
   (`DexClassLoader`, `PathClassLoader`, `InMemoryDexClassLoader`, `DexFile`) can't be followed. Rows reaching them
   get a `REFL` / `DEX` flag (score 0) as a hint that the real impact may be higher.
   A component declared in the manifest whose class is not in the app gets the `MISSING` flag (often packed or loaded at runtime).
   Inter-component communication (starting another service with an intent) is not followed.

#### Scores

Very high impact capabilities score **2** per API, other privacy relevant capabilities score **1**
(the permission list is based on the dangerous permissions list of [MVT](https://github.com/mvt-project/mvt/blob/c95eeed825405d5b46c23883ad1336f131b52e2c/src/mvt/android/utils.py#L52), extended):

| Score | Capability | Permissions |
|---|---|---|
| 2 | Accessibility: simulated input / read screen content | `BIND_ACCESSIBILITY_SERVICE` |
| 2 | Screen capture (MediaProjection) | none (user consent) |
| 2 | Change secure/global settings | `WRITE_SECURE_SETTINGS` |
| 2 | Change system settings | `WRITE_SETTINGS` |
| 2 | Device admin (lock, wipe, password) | `BIND_DEVICE_ADMIN` |
| 2 | Read notifications of other apps | `BIND_NOTIFICATION_LISTENER_SERVICE` |
| 2 | Keyboard (input method) | `BIND_INPUT_METHOD` |
| 2 | Install packages | `REQUEST_INSTALL_PACKAGES`, `INSTALL_PACKAGES` |
| 2 | Draw over other apps (overlay) | `SYSTEM_ALERT_WINDOW` |
| 2 | Read SMS | `READ_SMS` |
| 2 | Intercept incoming SMS | `RECEIVE_SMS` |
| 1 | Location | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` |
| 1 | Camera | `CAMERA` |
| 1 | Microphone | `RECORD_AUDIO` |
| 1 | Contacts | `READ_CONTACTS` |
| 1 | Call log | `READ_CALL_LOG` |
| 1 | Calendar | `READ_CALENDAR` |
| 1 | Phone identifiers / state | `READ_PHONE_STATE`, `READ_PHONE_NUMBERS` |
| 1 | Monitor outgoing calls | `PROCESS_OUTGOING_CALLS` |
| 1 | Send SMS | `SEND_SMS` |
| 1 | Intercept MMS / WAP push | `RECEIVE_MMS`, `RECEIVE_WAP_PUSH` |
| 1 | Accounts and credentials | `GET_ACCOUNTS`, `USE_CREDENTIALS`, `AUTHENTICATE_ACCOUNTS` |
| 1 | Disable lock screen | `DISABLE_KEYGUARD` |
| 1 | SIP calls | `USE_SIP` |
| 1 | Browser history / bookmarks | `READ_HISTORY_BOOKMARKS` |
| 1 | Files and media | `READ/WRITE/MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_*` |
| 1 | List installed apps | none required (`QUERY_ALL_PACKAGES`) |
| 1 | Bluetooth devices | `BLUETOOTH_CONNECT`, `BLUETOOTH` |
| 1 | App usage / running apps | `PACKAGE_USAGE_STATS`, `GET_TASKS` |
| 0 | Runtime code loading (flag `DEX`), reflection (flag `REFL`) | - |

Example: a service whose `onStartCommand` calls a helper using `LocationManager.getLastKnownLocation` (1)
and starts a `Runnable` which calls `MediaProjectionManager.createScreenCaptureIntent` (2) has a score of 3.

To add or change APIs, edit `privacy-rules.json`: `capabilities` define label, abbreviation (`short`), score, the permissions and
what has to be declared (`requires`: `uses-permission`, `component-permission` or `none`);
`apis` map a `method` (`pkg.Class.method`, `$` for inner classes, `*` for any method), `field` or `string` (prefix) to a capability;
`manifest` maps component permissions and intent actions to capabilities.
Rule table entries take precedence over database entries for the same API.

#### Android permission database

A SQLite database mapping Android framework APIs to the permissions they require
([`android-permissions.db`](jadx-gui/src/main/resources/privacy/android-permissions.db), ~1 MB, ~1450 APIs, ~1100 permission definitions).
It's built by the generator module [`jadx-permission-db`](jadx-permission-db) and bundled with jadx-gui.
To use a newer database without rebuilding jadx, put it at `<jadx config dir>/privacy/android-permissions.db`
(on Linux `~/.config/jadx/privacy/android-permissions.db`).

**Sources** (each row records its source):

| Source | What | Coverage |
|---|---|---|
| `sdk-annotations` | `@RequiresPermission` annotations (`value`/`anyOf`/`allOf`/`conditional`, `.Read`/`.Write`) from `data/annotations.zip` of every stable SDK platform — the same data Android Lint's `MissingPermission` check uses | API 26 – newest; the levels in which a requirement is documented are stored (`min_api`, `max_api`) |
| `provider-uri` | `android.net.Uri` constants of provider contract classes (e.g. `CallLog.Calls.CONTENT_URI`), found in the SDK source stubs, with the read/write permissions of their provider | newest SDK |
| `aosp` | permission definitions (protection level, group, flags) and protected broadcasts from `frameworks/base/core/res/AndroidManifest.xml`; read/write permissions of system content providers from their manifests | newest AOSP release tag |
| `axplorer` | [Axplorer](https://github.com/reddr/axplorer) permission maps: checks found by static analysis of the framework, including undocumented ones | API 16–25 (static, no longer maintained) |
| `curated` | [`supplement.json`](jadx-permission-db/src/main/resources/jadx/permdb/supplement.json): providers checking permissions in code (SMS, MMS, media, voicemail, browser), content URI classes → provider authority, broadcasts sent with a permission (`SMS_RECEIVED` → `RECEIVE_SMS`, `PHONE_STATE`, `BOOT_COMPLETED`, ...), Axplorer noise filter | hand maintained |

Values of String constants (intent actions, extras) come from the SDK source stubs, because the compiler inlines them into app code.
Axplorer lists `BROADCAST_STICKY` for ~90 methods (an artifact of internal framework call paths, e.g. `Service.stopSelf`);
it is only kept for the `*StickyBroadcast*` methods. Inherited duplicates (the same method listed for every subclass) are removed.

**Schema** (`schema_version` 1):

| Table | Columns |
|---|---|
| `api` | `id`, `kind` (`method`/`field`), `class` (binary name, `$` for nested classes), `member` (`<init>` for constructors), `params` (erased parameter types separated by `,`; NULL for fields), `constant` (value of String constants) |
| `api_permission` | `api_id`, `permission`, `mode` (`all`: all permissions of this source and API are needed, `any`: one is enough), `conditional`, `access` (`read`/`write` for content URIs), `min_api`, `max_api`, `source` |
| `permission` | `name`, `protection_level`, `permission_group`, `flags`, `source` |
| `provider_permission` | `authority`, `path`, `path_type` (`path`/`prefix`/`pattern`, empty for the whole provider), `access`, `permission`, `package`, `source` |
| `broadcast` | `action`, `permission` (needed to receive it), `protected` (only the system may send it), `source` |
| `source` | `name`, `url`, `revision` (SDK archive checksum, AOSP tag, Axplorer commit) |
| `meta` | `schema_version`, `content_hash`, `generated`, `latest_api`, `sdk_platforms`, `aosp_tag`, ... |
| view `api_permission_view` | `api` joined with `api_permission` |

Example: `SELECT class, member, params, permission, min_api, max_api FROM api_permission_view WHERE permission = 'android.permission.READ_SMS'`

**Updating.** The generator is designed to be re-run periodically and to pick up new Android releases by itself:

```bash
./gradlew :jadx-permission-db:run                                   # update the bundled database
./gradlew :jadx-permission-db:run --args="--changelog changes.md"   # also write a Markdown change log
./gradlew :jadx-permission-db:run --args="--help"                   # options: --out, --cache, --min-api, --aosp-tag, --offline, --force
```

- **Discovery:** stable SDK platforms are read from the official SDK repository manifest (`repository2-3.xml`, the same list
  Android Studio uses; previews, SDK extension variants and obsolete packages are skipped), the AOSP release is the newest
  `android-X.Y.Z_rN` tag. Nothing in the generator has to change when Android 18 is released.
- **Small downloads:** the SDK platform zips (~65 MB each) are not downloaded: the generator reads the zip central directory
  and only the needed entries with HTTP range requests (~250 KB `annotations.zip` per level, the 8 MB source stubs only for the newest level).
- **Cache:** extracted entries are cached by the archive's SHA-1 checksum, AOSP files by tag, Axplorer files by commit — they never change.
  The SDK manifest is revalidated with `ETag`. A re-run without changes downloads ~400 KB (SDK manifest and AOSP tag list).
- **Change detection:** the content hash covers all rows (not the generation date or source revisions), so the database file is only
  rewritten when the data changed; `--changelog` writes a Markdown summary of added/removed rows (suitable as pull request text).
- **Automation:** the GitHub workflow [`permission-db.yml`](.github/workflows/permission-db.yml) runs the generator weekly
  (and on demand), runs the tests with the new database and opens a pull request with the change log only if the content changed.

The jadx-gui reads the whole database into memory at first use (via the `sqlite-jdbc` driver), so lookups don't hit SQLite.

### Download
- release
  from [github: ![Latest release](https://img.shields.io/github/release/skylot/jadx.svg)](https://github.com/skylot/jadx/releases/latest)
- latest [unstable build ![GitHub commits since tagged version (branch)](https://img.shields.io/github/commits-since/skylot/jadx/latest/master)](https://nightly.link/skylot/jadx/workflows/build-artifacts/master)

After download unpack zip file go to `bin` directory and run:
- `jadx` - command line version
- `jadx-gui` - UI version

On Windows run `.bat` files with double-click\
**Note:** ensure you have installed Java 11 or later 64-bit version.
For Windows, you can download it from [oracle.com](https://www.oracle.com/java/technologies/downloads/#jdk17-windows) (select x64 Installer).

### Install
- Arch Linux
  [![Arch Linux package](https://img.shields.io/archlinux/v/extra/any/jadx)](https://archlinux.org/packages/extra/any/jadx/)
  [![AUR Version](https://img.shields.io/aur/version/jadx-git)](https://aur.archlinux.org/packages/jadx-git)
  ```bash
  sudo pacman -S jadx
  ```
- macOS
  [![homebrew version](https://img.shields.io/homebrew/v/jadx)](https://formulae.brew.sh/formula/jadx)
  ```bash
  brew install jadx
  ```
- Flathub
  [![Flathub Version](https://img.shields.io/flathub/v/com.github.skylot.jadx)](https://flathub.org/apps/com.github.skylot.jadx)
  ```bash
  flatpak install flathub com.github.skylot.jadx
  ```

### Use jadx as a library
You can use jadx in your java projects, check details on [wiki page](https://github.com/skylot/jadx/wiki/Use-jadx-as-a-library)

### Build from source
JDK 17 or higher must be installed:
```
git clone https://github.com/skylot/jadx.git
cd jadx
./gradlew dist
```

(on Windows, use `gradlew.bat` instead of `./gradlew`)

Scripts for run jadx will be placed in `build/jadx/bin`
and also packed to `build/jadx-<version>.zip`

### Usage
```
jadx[-gui] [command] [options] <input files> (.apk, .dex, .jar, .class, .smali, .zip, .aar, .arsc, .aab, .xapk, .apkm, .jadx.kts)
commands (use '<command> --help' for command options):
  plugins	  - manage jadx plugins

options:
  -d, --output-dir                              - output directory
  -ds, --output-dir-src                         - output directory for sources
  -dr, --output-dir-res                         - output directory for resources
  -r, --no-res                                  - do not decode resources
  -s, --no-src                                  - do not decompile source code
  -j, --threads-count                           - processing threads count, default: 16
  --single-class                                - decompile a single class, full name, raw or alias
  --single-class-output                         - file or dir for write if decompile a single class
  --output-format                               - can be 'java' or 'json', default: java
  -e, --export-gradle                           - save as gradle project (set '--export-gradle-type' to 'auto')
  --export-gradle-type                          - Gradle project template for export:
                                                   'auto' - detect automatically
                                                   'android-app' - Android Application (apk)
                                                   'android-library' - Android Library (aar)
                                                   'simple-java' - simple Java
  -m, --decompilation-mode                      - code output mode:
                                                   'auto' - trying best options (default)
                                                   'restructure' - restore code structure (normal java code)
                                                   'simple' - simplified instructions (linear, with goto's)
                                                   'fallback' - raw instructions without modifications
  --show-bad-code                               - show inconsistent code (incorrectly decompiled)
  --no-xml-pretty-print                         - do not prettify XML
  --no-imports                                  - disable use of imports, always write entire package name
  --no-debug-info                               - disable debug info parsing and processing
  --add-debug-lines                             - add comments with debug line numbers if available
  --no-inline-anonymous                         - disable anonymous classes inline
  --no-inline-methods                           - disable methods inline
  --no-move-inner-classes                       - disable move inner classes into parent
  --no-inline-kotlin-lambda                     - disable inline for Kotlin lambdas
  --no-finally                                  - don't extract finally block
  --no-restore-switch-over-string               - don't restore switch over string
  --no-replace-consts                           - don't replace constant value with matching constant field
  --escape-unicode                              - escape non latin characters in strings (with \u)
  --respect-bytecode-access-modifiers           - don't change original access modifiers
  --mappings-path                               - deobfuscation mappings file or directory. Allowed formats: Tiny and Tiny v2 (both '.tiny'), Enigma (.mapping) or Enigma directory
  --mappings-mode                               - set mode for handling the deobfuscation mapping file:
                                                   'read' - just read, user can always save manually (default)
                                                   'read-and-autosave-every-change' - read and autosave after every change
                                                   'read-and-autosave-before-closing' - read and autosave before exiting the app or closing the project
                                                   'ignore' - don't read or save (can be used to skip loading mapping files referenced in the project file)
  --deobf                                       - activate deobfuscation
  --deobf-min                                   - min length of name, renamed if shorter, default: 3
  --deobf-max                                   - max length of name, renamed if longer, default: 64
  --deobf-whitelist                             - space separated list of classes (full name) and packages (ends with '.*') to exclude from deobfuscation, default: android.support.v4.* android.support.v7.* android.support.v4.os.* android.support.annotation.Px androidx.core.os.* androidx.annotation.Px
  --deobf-cfg-file                              - deobfuscation mappings file used for JADX auto-generated names (in the JOBF file format), default: same dir and name as input file with '.jobf' extension
  --deobf-cfg-file-mode                         - set mode for handling the JADX auto-generated names' deobfuscation map file:
                                                   'read' - read if found, don't save (default)
                                                   'read-or-save' - read if found, save otherwise (don't overwrite)
                                                   'overwrite' - don't read, always save
                                                   'ignore' - don't read and don't save
  --deobf-res-name-source                       - better name source for resources:
                                                   'auto' - automatically select best name (default)
                                                   'resources' - use resources names
                                                   'code' - use R class fields names
  --use-source-name-as-class-name-alias         - use source name as class name alias:
                                                   'always' - always use source name if it's available
                                                   'if-better' - use source name if it seems better than the current one
                                                   'never' - never use source name, even if it's available
  --source-name-repeat-limit                    - allow using source name if it appears less than a limit number, default: 10
  --use-kotlin-methods-for-var-names            - use kotlin intrinsic methods to rename variables, values: disable, apply, apply-and-hide, default: apply
  --use-headers-for-detect-resource-extensions  - Use headers for detect resource extensions if resource obfuscated
  --rename-flags                                - fix options (comma-separated list of):
                                                   'case' - fix case sensitivity issues (according to --fs-case-sensitive option),
                                                   'valid' - rename java identifiers to make them valid,
                                                   'printable' - remove non-printable chars from identifiers,
                                                  or single 'none' - to disable all renames
                                                  or single 'all' - to enable all (default)
  --integer-format                              - how integers are displayed:
                                                   'auto' - automatically select (default)
                                                   'decimal' - use decimal
                                                   'hexadecimal' - use hexadecimal
  --type-update-limit                           - type update limit count (per one instruction), default: 10
  --fs-case-sensitive                           - treat filesystem as case sensitive, false by default
  --cfg                                         - save methods control flow graph to dot file
  --raw-cfg                                     - save methods control flow graph (use raw instructions)
  --call-graph                                  - save app call graph in format: 'dot' or 'json', default: none
  -f, --fallback                                - set '--decompilation-mode' to 'fallback' (deprecated)
  --use-dx                                      - use dx/d8 to convert java bytecode
  --comments-level                              - set code comments level, values: error, warn, info, debug, user-only, none, default: info
  --log-level                                   - set log level, values: quiet, progress, error, warn, info, debug, default: progress
  -v, --verbose                                 - verbose output (set --log-level to DEBUG)
  -q, --quiet                                   - turn off output (set --log-level to QUIET)
  --disable-plugins                             - comma separated list of plugin ids to disable
  --config <config-ref>                         - load configuration from file, <config-ref> can be:
                                                   path to '.json' file
                                                   short name - uses file with this name from config directory
                                                   'none' - to disable config loading
  --save-config <config-ref>                    - save current options into configuration file and exit, <config-ref> can be:
                                                   empty - for default config
                                                   path to '.json' file
                                                   short name - file will be saved in config directory
  --print-files                                 - print files and directories used by jadx (config, cache, temp)
  --version                                     - print jadx version
  -h, --help                                    - print this help

Plugin options (-P<name>=<value>):
  dex-input: Load .dex and .apk files
    - dex-input.verify-checksum                 - verify dex file checksum before load, values: [yes, no], default: yes
  java-convert: Convert .class, .jar and .aar files to dex
    - java-convert.mode                         - convert mode, values: [dx, d8, both], default: both
    - java-convert.d8-desugar                   - use desugar in d8, values: [yes, no], default: no
  kotlin-metadata: Use kotlin.Metadata annotation for code generation
    - kotlin-metadata.class-alias               - rename class alias, values: [yes, no], default: yes
    - kotlin-metadata.method-args               - rename function arguments, values: [yes, no], default: yes
    - kotlin-metadata.fields                    - rename fields, values: [yes, no], default: yes
    - kotlin-metadata.companion                 - rename companion object, values: [yes, no], default: yes
    - kotlin-metadata.data-class                - add data class modifier, values: [yes, no], default: yes
    - kotlin-metadata.to-string                 - rename fields using toString, values: [yes, no], default: yes
    - kotlin-metadata.getters                   - rename simple getters to field names, values: [yes, no], default: yes
  kotlin-smap: Use kotlin.SourceDebugExtension annotation for rename class alias
    - kotlin-smap.class-alias-source-dbg        - rename class alias from SourceDebugExtension, values: [yes, no], default: no
  rename-mappings: various mappings support
    - rename-mappings.format                    - mapping format, values: [AUTO, TINY_FILE, TINY_2_FILE, ENIGMA_FILE, ENIGMA_DIR, PROGUARD_FILE, SRG_FILE, XSRG_FILE, JAM_FILE, CSRG_FILE, TSRG_FILE, TSRG_2_FILE, INTELLIJ_MIGRATION_MAP_FILE, RECAF_SIMPLE_FILE, JOBF_FILE], default: AUTO
    - rename-mappings.invert                    - invert mapping on load, values: [yes, no], default: no
  smali-input: Load .smali files
    - smali-input.api-level                     - Android API level, default: 27

Environment variables:
  JADX_DISABLE_XML_SECURITY - set to 'true' to disable all security checks for XML files
  JADX_DISABLE_ZIP_SECURITY - set to 'true' to disable all security checks for zip files
  JADX_DISABLE_ALL_SECURITY_FLAGS - set to 'true' to disable all security flags (xml, string, app package)
  JADX_ZIP_MAX_ENTRIES_COUNT - maximum allowed number of entries in zip files (default: 100 000)
  JADX_CONFIG_DIR - custom config directory, using system by default
  JADX_CACHE_DIR - custom cache directory, using system by default
  JADX_TMP_DIR - custom temp directory, using system by default

Examples:
  jadx -d out classes.dex
  jadx --rename-flags "none" classes.dex
  jadx --rename-flags "valid, printable" classes.dex
  jadx --log-level ERROR app.apk
  jadx -Pdex-input.verify-checksum=no app.apk
```
These options also work in jadx-gui running from command line and override options from preferences' dialog

Usage for `plugins` command
```
usage: plugins [options]
options:
  -i, --install <locationId>      - install plugin with locationId
  -j, --install-jar <path-to.jar> - install plugin from jar file
  -l, --list                      - list installed plugins
  -a, --available                 - list available plugins from jadx-plugins-list (aka marketplace)
  -u, --update                    - update installed plugins
  --uninstall <pluginId>          - uninstall plugin with pluginId
  --disable <pluginId>            - disable plugin with pluginId
  --enable <pluginId>             - enable plugin with pluginId
  --list-all                      - list all plugins including bundled and dropins
  --list-versions <locationId>    - fetch latest versions of plugin from locationId (will download all artefacts, limited to 10)
  -h, --help                      - print this help
```


### Troubleshooting
Please check wiki page [Troubleshooting Q&A](https://github.com/skylot/jadx/wiki/Troubleshooting-Q&A)

### Contributing
To support this project you can:
  - Post thoughts about new features/optimizations that important to you
  - Submit decompilation issues, please read before proceed: [Open issue](CONTRIBUTING.md#Open-Issue)
  - Open pull request, please follow these rules: [Pull Request Process](CONTRIBUTING.md#Pull-Request-Process)

---------------------------------------
*Licensed under the Apache 2.0 License*
