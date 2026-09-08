# @wefterjs/app

Official Wefter plugin for application lifecycle management, build metadata, deep link handling, hardware back button control, and external URL dispatch.

## Features

- Query application identifiers, versions, build numbers, and installation timestamps.
- Monitor lifecycle transitions between active, inactive, and background states.
- Receive custom scheme URLs and Universal Links on cold start and runtime navigation.
- Open external URLs in third-party applications (browsers, maps, telephone dialers).
- Respond to operating system low memory pressure warnings.
- Intercept the Android hardware back button gesture to control in-app routing.
- Control application process state (minimize, exit, or relaunch).

## Installation and setup

Install the plugin package in your Wefter application:

```bash
wefter add @wefterjs/app
wefter sync
```

## JavaScript API reference

Import the `App` namespace from `@wefterjs/app`:

```ts
import { App } from "@wefterjs/app";
```

### Application metadata

Retrieve display names, bundle identifiers, and build versions:

```ts
const info = await App.getInfo();
console.log(info.name);             // Application display name, e.g. "Wefter Demo"
console.log(info.id);               // Package or bundle ID, e.g. "dev.wefter.demo"
console.log(info.version);          // Version string, e.g. "1.0.0"
console.log(info.build);            // Build counter string, e.g. "42"
console.log(info.debug);            // Boolean flag indicating debug compilation
console.log(info.firstInstallTime); // Epoch milliseconds (Android only, 0 on iOS)
console.log(info.lastUpdateTime);   // Epoch milliseconds (Android only, 0 on iOS)
```

### Lifecycle state

Check the current active state of the application:

```ts
const { isActive, state } = await App.getState();
// state is "active", "inactive", or "background"
```

Lifecycle states indicate:

- `active`: The app is in the foreground and receiving user events.
- `inactive`: The app is partially visible but not receiving input (for example, during an incoming phone call or native system prompt).
- `background`: The app is completely hidden from view.

### Deep links

Retrieve the URL used to launch the app on cold start:

```ts
const { url } = await App.getLaunchUrl();
if (url) {
  console.log("App opened from link:", url);
}
```

Subscribe to deep link openings that occur while the application is already running:

```ts
const linkSub = App.onUrlOpen(({ url, iosSourceApplication }) => {
  console.log("Incoming deep link:", url);
  if (iosSourceApplication) {
    console.log("Source app:", iosSourceApplication);
  }
});

// To stop listening:
linkSub.remove();
```

### External URL dispatch

Check whether an external application can open a given URL scheme, and hand off execution:

```ts
if (await App.canOpenUrl("tel:+15551234567")) {
  await App.openUrl("tel:+15551234567");
}
```

To display web pages within your app using Chrome Custom Tabs or SFSafariViewController, use `@wefterjs/browser` instead.

### Process controls

```ts
// Minimize the app (moves task to background on Android, unsupported on iOS)
await App.minimizeApp();

// Terminate the process (Android and enterprise iOS kiosks)
await App.exitApp();

// Restart the application (Android only)
await App.relaunchApp();
```

### Event listeners

```ts
// Lifecycle transitions
const stateSub = App.onStateChange(({ state }) => {
  console.log("New state:", state);
});

// OS memory pressure warnings
const memorySub = App.onLowMemory(({ level }) => {
  console.warn("Low memory warning level:", level);
});

// Android hardware back button
const backSub = App.onBackButton(({ canGoBack }) => {
  if (canGoBack) {
    history.back();
  } else {
    App.exitApp();
  }
});

// Remove listeners when unmounting
stateSub.remove();
memorySub.remove();
backSub.remove();
```

When at least one listener is registered with `onBackButton`, the Android shell delegates hardware back gestures to your JavaScript handler instead of executing default WebView page backward navigation.

## Platform events

| Event | Payload | Platforms |
| --- | --- | --- |
| `app:stateChange` | `{ isActive: boolean, state: "active" \| "inactive" \| "background" }` | Android, iOS |
| `app:urlOpen` | `{ url: string, iosSourceApplication?: string }` | Android, iOS |
| `app:backButton` | `{ canGoBack: boolean }` | Android |
| `app:lowMemory` | `{ level: number }` | Android, iOS |

## Error codes

| Code | Cause |
| --- | --- |
| `INFO_FAILED` | Failed to read package metadata from the operating system. |
| `NO_ACTIVITY` | An operation requiring a foreground activity was called without one. |
| `MINIMIZE_FAILED` | Failed to move Android activity to background. |
| `EXIT_FAILED` | Application termination call failed. |
| `RELAUNCH_FAILED` | AlarmManager scheduling failed during relaunch attempt on Android. |
| `INVALID_ARGUMENT` | The provided URL string was empty or malformed. |
| `NO_HANDLER` | No external application is installed to handle the specified URL scheme. |
| `UNIMPLEMENTED` | The method is not supported on the target platform (such as minimizeApp on iOS). |

## Platform implementation notes

### Android

- **Lifecycle tracking**: Implemented using `Application.ActivityLifecycleCallbacks` on the host application.
- **Memory warnings**: Uses `ComponentCallbacks2.onTrimMemory` and `onLowMemory`.
- **Back button**: Intercepted via `OnBackPressedCallback` registered on the host `ComponentActivity`.
- **Deep links**: Routed from `Intent.ACTION_VIEW` in `MainActivity.onCreate` and `onNewIntent`.

### iOS

- **Lifecycle tracking**: Listens to system notifications (`UIApplication.didBecomeActiveNotification`, `willResignActiveNotification`, and `didEnterBackgroundNotification`).
- **Memory warnings**: Listens to `UIApplication.didReceiveMemoryWarningNotification`.
- **Deep links**: Forwarded through `UIWindowSceneDelegate` open URL and Universal Link handler callbacks.

## License

[MIT](LICENSE) © 2026 Sandip Ghimire
