/**
 * Information about the app and control over it: version details, lifecycle state, the URL it was opened
 * with, opening other apps, and events for state changes, incoming URLs, low memory and the Android back
 * button.
 */

import { definePlugin, registerHook } from "@wefterjs/core";

/**
 * Name, identifier, version and build of the installed app. Install and update times are milliseconds since
 * the epoch.
 */
export interface AppInfo {
  name: string;
  id: string;
  version: string;
  build: string;
  debug: boolean;
  firstInstallTime: number;
  lastUpdateTime: number;
}

/** Whether the app is in the foreground, briefly interrupted, or in the background. */
export type AppLifecycleState = "active" | "inactive" | "background";

/** The app's lifecycle state. */
export interface AppState {
  isActive: boolean;
  state: AppLifecycleState;
}

/** The URL the app was opened with, or `null`. */
export interface AppLaunchUrl {
  url: string | null;
}

/** Sent when the app's lifecycle state changes. */
export type AppStateChangeEvent = AppState;

/** Sent when the app is opened with a URL (deep link) while running. */
export interface AppUrlOpenEvent {
  url: string;
  iosSourceApplication?: string;
}

/** Sent when the Android back button is pressed while interception is on. */
export interface AppBackButtonEvent {
  canGoBack: boolean;
}

/** Sent when the system is low on memory. `level` is the Android trim level, or -1 on iOS. */
export interface AppLowMemoryEvent {
  level: number;
}

/** Returned by successful actions. */
export interface AppActionResult {
  success: true;
}

/** Whether some installed app can open a URL. */
export interface AppCanOpenUrlResult {
  value: boolean;
}

/** Returned by event listeners; `remove()` stops listening. */
export interface Subscription {
  remove(): void;
}

/** Native methods of the plugin. */
const native = definePlugin<{
  getInfo: () => Promise<AppInfo>;
  getState: () => Promise<AppState>;
  getLaunchUrl: () => Promise<AppLaunchUrl>;
  minimizeApp: () => Promise<AppActionResult>;
  exitApp: () => Promise<AppActionResult>;
  relaunchApp: () => Promise<AppActionResult>;
  openUrl: (options: { url: string }) => Promise<AppActionResult>;
  canOpenUrl: (options: { url: string }) => Promise<AppCanOpenUrlResult>;
  setBackButtonInterceptEnabled: (options: { enabled: boolean }) => Promise<AppActionResult>;
}>("app", {
  getInfo: true,
  getState: true,
  getLaunchUrl: true,
  minimizeApp: true,
  exitApp: true,
  relaunchApp: true,
  openUrl: true,
  canOpenUrl: true,
  setBackButtonInterceptEnabled: true,
});

/** The App plugin. */
export const App = {
  /** Name, identifier, version and install times of the app. */
  getInfo(): Promise<AppInfo> {
    return native.getInfo();
  },

  /** Whether the app is in the foreground. */
  getState(): Promise<AppState> {
    return native.getState();
  },

  /** The URL the app was launched with, if any. */
  getLaunchUrl(): Promise<AppLaunchUrl> {
    return native.getLaunchUrl();
  },

  /**
   * Sends the app to the background (Android). iOS does not allow this, so it rejects with `UNIMPLEMENTED`.
   */
  minimizeApp(): Promise<AppActionResult> {
    return native.minimizeApp();
  },

  /** Closes the app. */
  exitApp(): Promise<AppActionResult> {
    return native.exitApp();
  },

  /**
   * Restarts the app (Android). iOS apps cannot relaunch themselves, so this rejects with `UNIMPLEMENTED`.
   */
  relaunchApp(): Promise<AppActionResult> {
    return native.relaunchApp();
  },

  /** Opens a URL in the app that handles it (browser, mail, maps, another app). */
  openUrl(url: string): Promise<AppActionResult> {
    return native.openUrl({ url });
  },

  /** Whether some installed app can open `url`. */
  canOpenUrl(url: string): Promise<boolean> {
    return native.canOpenUrl({ url }).then((r) => r.value);
  },

  /** Calls `callback` when the app moves between foreground and background. */
  onStateChange(callback: (state: AppStateChangeEvent) => void): Subscription {
    return registerHook("app:stateChange", callback as (data: unknown) => void);
  },

  /** Calls `callback` when the app is opened with a URL while running. */
  onUrlOpen(callback: (event: AppUrlOpenEvent) => void): Subscription {
    return registerHook("app:urlOpen", callback as (data: unknown) => void);
  },

  /** Calls `callback` when the system is low on memory. */
  onLowMemory(callback: (event: AppLowMemoryEvent) => void): Subscription {
    return registerHook("app:lowMemory", callback as (data: unknown) => void);
  },

  /** Calls `callback` when the Android back button is pressed, while interception is on (the default). */
  onBackButton(callback: (event: AppBackButtonEvent) => void): Subscription {
    return registerHook("app:backButton", callback as (data: unknown) => void);
  },

  /**
   * Turns Android back button interception on or off. It is on by default: the button then no longer
   * navigates or closes the app, and `App.onBackButton` fires instead. Has no effect on iOS.
   */
  setBackButtonInterceptEnabled(enabled: boolean): Promise<AppActionResult> {
    return native.setBackButtonInterceptEnabled({ enabled });
  },
};
