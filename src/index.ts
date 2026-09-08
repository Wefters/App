import { definePlugin, registerHook } from "@wefterjs/core";

export interface AppInfo {
  name: string;
  id: string;
  version: string;
  build: string;
  debug: boolean;
  firstInstallTime: number;
  lastUpdateTime: number;
}

export type AppLifecycleState = "active" | "inactive" | "background";

export interface AppState {
  isActive: boolean;
  state: AppLifecycleState;
}

export interface AppLaunchUrl {
  url: string | null;
}

export type AppStateChangeEvent = AppState;

export interface AppUrlOpenEvent {
  url: string;
  iosSourceApplication?: string;
}

export interface AppBackButtonEvent {
  canGoBack: boolean;
}

export interface AppLowMemoryEvent {
  level: number;
}

export interface AppActionResult {
  success: true;
}

export interface AppCanOpenUrlResult {
  value: boolean;
}

export interface Subscription {
  remove(): void;
}

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

export const App = {
  getInfo(): Promise<AppInfo> {
    return native.getInfo();
  },

  getState(): Promise<AppState> {
    return native.getState();
  },

  getLaunchUrl(): Promise<AppLaunchUrl> {
    return native.getLaunchUrl();
  },

  minimizeApp(): Promise<AppActionResult> {
    return native.minimizeApp();
  },

  exitApp(): Promise<AppActionResult> {
    return native.exitApp();
  },

  relaunchApp(): Promise<AppActionResult> {
    return native.relaunchApp();
  },

  openUrl(url: string): Promise<AppActionResult> {
    return native.openUrl({ url });
  },

  canOpenUrl(url: string): Promise<boolean> {
    return native.canOpenUrl({ url }).then((r) => r.value);
  },

  onStateChange(callback: (state: AppStateChangeEvent) => void): Subscription {
    return registerHook("app:stateChange", callback as (data: unknown) => void);
  },

  onUrlOpen(callback: (event: AppUrlOpenEvent) => void): Subscription {
    return registerHook("app:urlOpen", callback as (data: unknown) => void);
  },

  onLowMemory(callback: (event: AppLowMemoryEvent) => void): Subscription {
    return registerHook("app:lowMemory", callback as (data: unknown) => void);
  },

  onBackButton(callback: (event: AppBackButtonEvent) => void): Subscription {
    return registerHook("app:backButton", callback as (data: unknown) => void);
  },

  setBackButtonInterceptEnabled(enabled: boolean): Promise<AppActionResult> {
    return native.setBackButtonInterceptEnabled({ enabled });
  },
};
