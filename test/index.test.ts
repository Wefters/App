import { afterEach, describe, expect, it, vi } from "vitest";
import { installMockBridge, uninstallMockBridge } from "@wefterjs/core/testing";
import { WefterBridgeError } from "@wefterjs/core";
import { App } from "../src/index.js";

type Handler = (method: string, payload: unknown) => unknown;

function installAppBridge(overrides: Record<string, unknown> = {}) {
  const calls: { method: string; payload: unknown }[] = [];
  const defaults: Record<string, unknown> = {
    getInfo: {
      name: "Wefter Demo",
      id: "dev.wefter.demo",
      version: "1.2.0",
      build: "42",
      debug: true,
      firstInstallTime: 1_700_000_000_000,
      lastUpdateTime: 1_700_000_500_000,
    },
    getState: { isActive: true, state: "active" },
    getLaunchUrl: { url: null },
    minimizeApp: { success: true },
    exitApp: { success: true },
    relaunchApp: { success: true },
    openUrl: { success: true },
    canOpenUrl: { value: true },
    setBackButtonInterceptEnabled: { success: true },
  };

  const handler: Handler = (method, payload) => {
    calls.push({ method, payload });
    if (method in overrides) return overrides[method];
    if (method in defaults) return defaults[method];
    throw new Error(`Unexpected method "${method}"`);
  };

  installMockBridge({ app: handler });
  return calls;
}

function emitNative(hook: string, data: unknown) {
  window.__wefterNative.emit(hook, JSON.stringify(data));
}

afterEach(() => {
  uninstallMockBridge();
});

describe("App.getInfo", () => {
  it("returns build & package metadata including debug and install times", async () => {
    installAppBridge();
    const info = await App.getInfo();
    expect(info).toMatchObject({
      name: "Wefter Demo",
      id: "dev.wefter.demo",
      version: "1.2.0",
      build: "42",
      debug: true,
    });
    expect(typeof info.firstInstallTime).toBe("number");
    expect(typeof info.lastUpdateTime).toBe("number");
  });
});

describe("App.getState", () => {
  it("returns the tri-state lifecycle phase", async () => {
    installAppBridge({ getState: { isActive: false, state: "background" } });
    expect(await App.getState()).toEqual({ isActive: false, state: "background" });
  });
});

describe("App.getLaunchUrl", () => {
  it("returns null when launched normally", async () => {
    installAppBridge();
    expect(await App.getLaunchUrl()).toEqual({ url: null });
  });

  it("returns the cold-start URL when present", async () => {
    installAppBridge({ getLaunchUrl: { url: "wefter://auth?token=xyz" } });
    expect(await App.getLaunchUrl()).toEqual({ url: "wefter://auth?token=xyz" });
  });
});

describe("App.minimizeApp / App.exitApp", () => {
  it("forwards minimizeApp", async () => {
    const calls = installAppBridge();
    expect(await App.minimizeApp()).toEqual({ success: true });
    expect(calls.map((c) => c.method)).toContain("minimizeApp");
  });

  it("surfaces the iOS UNIMPLEMENTED rejection", async () => {
    installAppBridge({
      minimizeApp: () => {
        throw new Error("not supported on iOS");
      },
    });
    await expect(App.minimizeApp()).rejects.toBeInstanceOf(WefterBridgeError);
  });

  it("forwards exitApp", async () => {
    const calls = installAppBridge();
    expect(await App.exitApp()).toEqual({ success: true });
    expect(calls.map((c) => c.method)).toContain("exitApp");
  });

  it("forwards relaunchApp", async () => {
    const calls = installAppBridge();
    expect(await App.relaunchApp()).toEqual({ success: true });
    expect(calls.map((c) => c.method)).toContain("relaunchApp");
  });
});

describe("App.openUrl / App.canOpenUrl", () => {
  it("forwards the url to openUrl", async () => {
    const calls = installAppBridge();
    await App.openUrl("tel:+15551234567");
    expect(calls.find((c) => c.method === "openUrl")?.payload).toEqual({ url: "tel:+15551234567" });
  });

  it("unwraps canOpenUrl to a boolean", async () => {
    installAppBridge({ canOpenUrl: { value: false } });
    expect(await App.canOpenUrl("weirdscheme://x")).toBe(false);
  });

  it("rejects openUrl when nothing can handle it", async () => {
    installAppBridge({
      openUrl: () => {
        throw new Error("No app can open");
      },
    });
    await expect(App.openUrl("weirdscheme://x")).rejects.toBeInstanceOf(WefterBridgeError);
  });
});

describe("App.onLowMemory", () => {
  it("delivers memory-pressure events", () => {
    installAppBridge();
    const cb = vi.fn();
    const sub = App.onLowMemory(cb);

    emitNative("app:lowMemory", { level: 15 });
    expect(cb).toHaveBeenCalledWith({ level: 15 });

    sub.remove();
    emitNative("app:lowMemory", { level: -1 });
    expect(cb).toHaveBeenCalledTimes(1);
  });
});

describe("App.onStateChange", () => {
  it("delivers emitted state-change events and stops after remove", () => {
    installAppBridge();
    const cb = vi.fn();
    const sub = App.onStateChange(cb);

    emitNative("app:stateChange", { isActive: false, state: "background" });
    emitNative("app:stateChange", { isActive: true, state: "active" });
    expect(cb).toHaveBeenCalledTimes(2);
    expect(cb).toHaveBeenLastCalledWith({ isActive: true, state: "active" });

    sub.remove();
    emitNative("app:stateChange", { isActive: false, state: "inactive" });
    expect(cb).toHaveBeenCalledTimes(2);
  });
});

describe("App.onUrlOpen", () => {
  it("delivers deep-link events with parsed payload", () => {
    installAppBridge();
    const cb = vi.fn();
    const sub = App.onUrlOpen(cb);

    emitNative("app:urlOpen", {
      url: "https://wefter.dev/verify?id=9",
      iosSourceApplication: "com.apple.mobilesafari",
    });
    expect(cb).toHaveBeenCalledWith({
      url: "https://wefter.dev/verify?id=9",
      iosSourceApplication: "com.apple.mobilesafari",
    });

    sub.remove();
  });
});

describe("App.onBackButton", () => {
  it("does not toggle native interception — that is explicit and on by default", () => {
    const calls = installAppBridge();
    const sub = App.onBackButton(vi.fn());
    expect(calls.filter((c) => c.method === "setBackButtonInterceptEnabled")).toHaveLength(0);
    sub.remove();
  });

  it("setBackButtonInterceptEnabled forwards the flag", async () => {
    const calls = installAppBridge();
    await App.setBackButtonInterceptEnabled(false);
    expect(calls.find((c) => c.method === "setBackButtonInterceptEnabled")?.payload).toEqual({
      enabled: false,
    });
  });

  it("delivers back-button events while subscribed", () => {
    installAppBridge();
    const cb = vi.fn();
    const sub = App.onBackButton(cb);

    emitNative("app:backButton", { canGoBack: true });
    expect(cb).toHaveBeenCalledWith({ canGoBack: true });

    sub.remove();
  });
});

describe("error propagation", () => {
  it("surfaces a native rejection as a WefterBridgeError", async () => {
    installMockBridge({
      app: () => {
        throw new Error("Could not read application info.");
      },
    });

    const call = App.getInfo();
    await expect(call).rejects.toBeInstanceOf(WefterBridgeError);
    await expect(call).rejects.toMatchObject({
      code: "MOCK_ERROR",
      message: "Could not read application info.",
    });
  });
});
