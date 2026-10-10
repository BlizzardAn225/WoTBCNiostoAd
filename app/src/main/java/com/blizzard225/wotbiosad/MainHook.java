package com.blizzard225.wotbiosad;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * WoT Blitz NetEase（com.netease.wotb）"iOS转安卓"模块入口。
 *
 * 功能：sauth 平台伪装 —— 网易渠道登录时把 sauth 的 platform 改写为 ios，
 * 使 WGNI 按 iOS 平台维度下发令牌，从而在安卓端进入苹果侧车库。
 * 实现见 {@link SauthPlatformHook}（三层拦截：JSONObject.put / toString / getSauthJson 族方法）。
 *
 * 网易壳（StubApp）动态装 DEX，UniSDK 类出现时机不定：
 *  - handleLoadPackage 立即装框架层钩子（org.json + ClassLoader 扫描器，不依赖脱壳）；
 *  - StubApp.attachBaseContext 后用真实 ClassLoader 直挂 UniSDK sauth 方法族（快路径）。
 */
public class MainHook implements IXposedHookLoadPackage {

    public static final String TARGET_PKG = "com.netease.wotb";
    public static final String TAG = "WotB-IOS2A";
    /** 游戏进程可写的应用私有目录（模块跑在游戏进程内，UID=com.netease.wotb） */
    public static final String TRACE_PATH = "/data/data/com.netease.wotb/files/wotb_ios2a.log";

    static {
        appendTrace("static-init");
    }

    static synchronized void appendTrace(String msg) {
        String line = System.currentTimeMillis() + " pid=" + android.os.Process.myPid() + " " + msg + "\n";
        // HyperOS 可能吞 I 级日志：E 级 + 无连字符 tag 更容易进 logcat
        try {
            android.util.Log.e("WotB-IOS2A", msg);
            android.util.Log.e("WotBIOS2A", msg);
        } catch (Throwable ignored) {
        }
        try {
            XposedBridge.log("[" + TAG + "] " + msg);
        } catch (Throwable ignored) {
        }
        for (String path : new String[] {
                TRACE_PATH,
                "/data/local/tmp/wotb_ios2a_trace.txt",
        }) {
            try {
                File f = new File(path);
                File dir = f.getParentFile();
                if (dir != null) {
                    dir.mkdirs();
                }
                try (FileOutputStream fos = new FileOutputStream(f, true)) {
                    fos.write(line.getBytes("UTF-8"));
                    fos.flush();
                }
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    static void log(String msg) {
        appendTrace(msg);
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        appendTrace("handleLoadPackage pkg=" + lpparam.packageName
                + " process=" + lpparam.processName
                + " first=" + lpparam.isFirstApplication);
        if (!lpparam.packageName.equals(TARGET_PKG)) {
            return;
        }
        appendTrace("TARGET matched, installing hooks");
        log("hooks loaded for " + lpparam.packageName + " pid=" + android.os.Process.myPid());

        // 框架层：JSONObject.put/toString 改写 + ClassLoader.loadClass 扫描器（兜底晚加载的插件类）
        SauthPlatformHook.installEarlyHooks();

        // 快路径：网易壳脱壳装 DEX 后，用真实 ClassLoader 直挂 UniSDK sauth 方法族
        try {
            XposedHelpers.findAndHookMethod("com.netease.android.protect.StubApp", lpparam.classLoader,
                    "attachBaseContext", android.content.Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                android.content.Context ctx = (android.content.Context) param.args[0];
                                if (ctx != null) {
                                    SauthPlatformHook.installSdkHooks(ctx.getClassLoader());
                                }
                            } catch (Throwable t) {
                                log("attachBaseContext installSdkHooks err: " + t);
                            }
                        }
                    });
            log("Hooked StubApp.attachBaseContext for deferred install");
        } catch (Throwable t) {
            log("StubApp.attachBaseContext hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod("com.netease.android.protect.StubApp", lpparam.classLoader,
                    "onCreate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                SauthPlatformHook.installSdkHooks(
                                        param.thisObject.getClass().getClassLoader());
                            } catch (Throwable t) {
                                log("StubApp.onCreate installSdkHooks err: " + t);
                            }
                        }
                    });
            log("Hooked StubApp.onCreate for deferred install");
        } catch (Throwable t) {
            log("StubApp.onCreate hook failed: " + t);
        }

        // 兜底重试：前 40s 每 4s 用 Application 的 ClassLoader 再挂一次（幂等，已挂的跳过）
        final Handler retryHandler = new Handler(Looper.getMainLooper());
        for (int i = 1; i <= 10; i++) {
            retryHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        Class<?> at = Class.forName("android.app.ActivityThread");
                        android.app.Application app = (android.app.Application)
                                XposedHelpers.callStaticMethod(at, "currentApplication");
                        if (app != null) {
                            SauthPlatformHook.installSdkHooks(app.getClassLoader());
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, i * 4000L);
        }
    }
}
