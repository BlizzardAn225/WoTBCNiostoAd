package com.blizzard225.wotbiosad;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * sauth 平台伪装钩子 —— 把网易 UniSDK 组装 sauth 时的 platform 强制改写为目标平台（默认 ios）。
 *
 * 背景（见研究记录）：
 *  - 登录链路：网易 UniSDK 登录 → 产出 sauth JSON（含 platform / source_platform / sessionid ...）
 *    → native 把 sauth_json POST 给 WGNI /id/api/v2/account/credentials/verify/external/netease/
 *    → WGNI 按平台维度下发不同 WGNI 令牌（同一网易账号 iOS/安卓对应不同车库）。
 *  - 安卓包实测 sauth 中 "platform":"ad"、"source_platform":"ad"；UniSDK 常量表
 *    PLATFORM_AD / PLATFORM_IOS / PLATFORM_HM / PLATFORM_PC / PLATFORM_WEB 对应短码
 *    ad / ios / hm / pc / web。
 *  - native 侧通过 JNI 字面量 "getSauthJson" 取 sauth（GetMethodID），该方法名不受混淆，
 *    因此可按方法名精确挂钩；UniSDK 类（com.netease.ntunisdk.base.*）在易盾脱壳后 / 插件
 *    模块（_ntcfgss*.dat → SdkMgr 动态加载）中出现，ClassLoader 不定，需按类名扫描兜底。
 *
 * 三层拦截（由外到内，越靠内越精确）：
 *  [A] org.json.JSONObject.put / putOpt —— 组装期改写。step/step2 若是组装后对字段值
 *      计算的散列，此层改写可让散列与最终值保持一致（完整性最安全）。
 *  [B] org.json.JSONObject.toString / toString(int) —— 序列化期兜底。覆盖
 *      new JSONObject(Map) 等不走 put 的组装路径。
 *  [C] ClassLoader.loadClass 扫描 + 按方法名挂 getSauthJson / getJFSauthJson / getJFSauth /
 *      completeSauthJson / processUniSDKSauthJson —— 直接在 sauth 产出/加工点改写，
 *      对 String 参数与返回值、JSONObject 参数与返回值都生效。
 *
 * 用法（MainHook 已接入）：
 *  - handleLoadPackage 呼叫 installEarlyHooks()（框架层 A/B + 类扫描器 C）；
 *  - installHooks(cl) 呼叫 installSdkHooks(cl)（对已知类名直接挂 C 层）。
 *
 * 风险与验证：
 *  - 改写后若 WGNI 拒绝（step/step2 之类完整性字段失败），把 HOOK_JSON_TOSTRING 关掉
 *    只留 [A]（组装期改写，散列自洽），再试；
 *  - 若仍失败，说明 sessionid 内 GAS 签名绑定平台，需走"游戏实例标识"维度（gameid/appid），
 *    用 EXTRA_OVERRIDES 追加改写字段做实验。
 */
public final class SauthPlatformHook {

    // ============================ 配置区 ============================

    /** 总开关 */
    public static volatile boolean ENABLED = true;

    /** 目标平台码：ios / hm / pc / web（UniSDK PLATFORM_* 短码） */
    public static volatile String TARGET_PLATFORM = "ios";

    /** 命中改写的源值（sauth 中出现这些值才替换） */
    public static volatile String[] SOURCE_VALUES = new String[] {"ad", "android", "AD", "Android"};

    /** 参与平台改写的字段名 */
    public static volatile String[] REWRITE_KEYS = new String[] {"platform", "source_platform"};

    /**
     * 额外字段强制覆盖（key -> 目标值）。默认空；实验时可加 "gameid" 等：
     *   SauthPlatformHook.addOverride("gameid", "xxxx");
     * 注意：sessionid 内含 GAS 签名（内层 JSON 的 "s" 字段），改不了也不要去改。
     */
    public static final Map<String, String> EXTRA_OVERRIDES = new LinkedHashMap<>();

    // ---- 分层开关（排障用）----
    /** [A] JSONObject.put / putOpt 组装期改写 */
    public static volatile boolean HOOK_JSON_PUT = true;
    /** [B] JSONObject.toString 序列化期改写 */
    public static volatile boolean HOOK_JSON_TOSTRING = true;
    /** [C] getSauthJson 族方法改写 */
    public static volatile boolean HOOK_SAUTH_METHODS = true;
    /** [C'] ClassLoader.loadClass 晚加载类扫描（插件 ClassLoader 兜底） */
    public static volatile boolean HOOK_CLASS_SCANNER = true;

    /** 改写日志去重（避免刷屏） */
    private static final Set<String> LOGGED = new HashSet<>();

    // ============================ 安装入口 ============================

    private static final AtomicBoolean EARLY_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean SCANNER_INSTALLED = new AtomicBoolean(false);

    /** 框架层安装：JSONObject 钩子（A/B）+ 晚加载类扫描器（C'）。可在 handleLoadPackage 直接调。 */
    public static void installEarlyHooks() {
        if (!ENABLED || !EARLY_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        if (HOOK_JSON_PUT) {
            hookJsonPut();
        }
        if (HOOK_JSON_TOSTRING) {
            hookJsonToString();
        }
        if (HOOK_CLASS_SCANNER) {
            installClassScanner();
        }
        log("installEarlyHooks: put=" + HOOK_JSON_PUT + " toString=" + HOOK_JSON_TOSTRING
                + " scanner=" + HOOK_CLASS_SCANNER + " target=" + TARGET_PLATFORM);
    }

    /**
     * 对指定 ClassLoader 内的已知 UniSDK 类直接挂 [C] 层。
     * 类不在（易盾未脱壳 / 插件未加载）时静默跳过，由扫描器兜底。
     */
    public static void installSdkHooks(ClassLoader cl) {
        if (!ENABLED || !HOOK_SAUTH_METHODS || cl == null) {
            return;
        }
        for (String clsName : SDK_CLASS_CANDIDATES) {
            Class<?> clazz = XposedHelpers.findClassIfExists(clsName, cl);
            if (clazz == null) {
                continue;
            }
            int n = hookSauthMethodsOfClass(clazz);
            log("installSdkHooks: " + clsName + " hooked=" + n);
        }
    }

    public static void addOverride(String key, String value) {
        EXTRA_OVERRIDES.put(key, value);
        log("override added: " + key + " -> " + value);
    }

    // ============================ [A] JSONObject.put / putOpt ============================

    private static void hookJsonPut() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args == null || param.args.length < 2) {
                        return;
                    }
                    if (!(param.args[0] instanceof String) || !(param.args[1] instanceof String)) {
                        return;
                    }
                    String key = (String) param.args[0];
                    String val = (String) param.args[1];
                    String nv = mapValue(key, val);
                    if (nv != null && !nv.equals(val)) {
                        param.args[1] = nv;
                        logOnce("put " + key + ": " + val + " -> " + nv);
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(JSONObject.class, "put", String.class, Object.class, hook);
        } catch (Throwable t) {
            log("hookJsonPut put err: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(JSONObject.class, "putOpt", String.class, Object.class, hook);
        } catch (Throwable t) {
            log("hookJsonPut putOpt err: " + t);
        }
    }

    // ============================ [B] JSONObject.toString ============================

    private static void hookJsonToString() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object r = param.getResult();
                    if (!(r instanceof String)) {
                        return;
                    }
                    String s = (String) r;
                    String ns = rewriteJsonString(s);
                    if (ns != null) {
                        param.setResult(ns);
                        logOnce("toString rewritten (" + s.length() + " -> " + ns.length() + " chars)");
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(JSONObject.class, "toString", hook);
        } catch (Throwable t) {
            log("hookJsonToString err: " + t);
        }
    }

    // ============================ [C] sauth 方法族 ============================

    /** 候选类：易盾脱壳后的 app ClassLoader 中可能出现的 UniSDK 类 */
    private static final String[] SDK_CLASS_CANDIDATES = new String[] {
            "com.netease.ntunisdk.base.JfGas",
            "com.netease.ntunisdk.base.NtUniSdk",
            "com.netease.ntunisdk.base.SdkBase",
            "com.netease.ntunisdk.base.SdkMgr",
            "com.netease.ntunisdk.base.AccountInfo",
            "com.netease.ntunisdk.base.UniSdkUtils",
            "com.netease.ntunisdk.NtUniSdk",
    };

    /** JNI / SDK 内按名字引用的 sauth 产出与加工方法名（不受混淆影响） */
    private static final String[] SAUTH_METHOD_NAMES = new String[] {
            "getSauthJson",        // native JNI GetMethodID 取 sauth 的入口
            "getJFSauthJson",
            "getJFSauth",
            "completeSauthJson",
            "processUniSDKSauthJson",
            "cleanUniSDKSauthJsonKeys",
    };

    /** 已挂过的类#方法，防重复 */
    private static final Set<String> HOOKED_CLASS_METHODS = new HashSet<>();
    /** loadClass 重入保护 */
    private static final ThreadLocal<Boolean> IN_SCANNER = new ThreadLocal<>();

    /** 对一个类内所有目标方法名挂 [C] 层钩子，返回挂上的数量 */
    private static int hookSauthMethodsOfClass(Class<?> clazz) {
        int hooked = 0;
        Set<String> names = new HashSet<>();
        for (String n : SAUTH_METHOD_NAMES) {
            names.add(n);
        }
        try {
            for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                if (!names.contains(m.getName())) {
                    continue;
                }
                String key = clazz.getName() + "#" + m.getName();
                synchronized (HOOKED_CLASS_METHODS) {
                    if (HOOKED_CLASS_METHODS.contains(key)) {
                        continue;
                    }
                    HOOKED_CLASS_METHODS.add(key);
                }
                try {
                    XposedBridge.hookAllMethods(clazz, m.getName(), SAUTH_METHOD_HOOK);
                    hooked++;
                } catch (Throwable t) {
                    log("hookAllMethods " + key + " err: " + t);
                }
            }
        } catch (Throwable t) {
            log("hookSauthMethodsOfClass " + clazz.getName() + " err: " + t);
        }
        return hooked;
    }

    private static final XC_MethodHook SAUTH_METHOD_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null) {
                    return;
                }
                for (int i = 0; i < param.args.length; i++) {
                    Object a = param.args[i];
                    if (a instanceof String) {
                        String ns = rewriteJsonString((String) a);
                        if (ns != null) {
                            param.args[i] = ns;
                            logOnce("method arg[" + i + "] rewritten");
                        }
                    } else if (a instanceof JSONObject) {
                        if (rewriteJsonObject((JSONObject) a)) {
                            logOnce("method arg[" + i + "] JSONObject rewritten");
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object r = param.getResult();
                if (r instanceof String) {
                    String ns = rewriteJsonString((String) r);
                    if (ns != null) {
                        param.setResult(ns);
                        logOnce("method result String rewritten");
                    }
                } else if (r instanceof JSONObject) {
                    if (rewriteJsonObject((JSONObject) r)) {
                        logOnce("method result JSONObject rewritten");
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    };

    // ============================ [C'] 晚加载类扫描 ============================

    private static void installClassScanner() {
        if (!SCANNER_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        XC_MethodHook scan = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_SCANNER.get())) {
                    return;
                }
                try {
                    if (!(param.args[0] instanceof String) || !(param.getResult() instanceof Class)) {
                        return;
                    }
                    String name = (String) param.args[0];
                    // 严格按类名过滤，把开销压到最低
                    if (name == null || !(name.contains("ntunisdk") || name.contains("JfGas")
                            || name.contains("Sauth") || name.contains("sauth"))) {
                        return;
                    }
                    IN_SCANNER.set(Boolean.TRUE);
                    try {
                        Class<?> clazz = (Class<?>) param.getResult();
                        int n = hookSauthMethodsOfClass(clazz);
                        if (n > 0) {
                            log("scanner hooked " + name + " x" + n);
                        }
                    } finally {
                        IN_SCANNER.remove();
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass",
                    String.class, boolean.class, scan);
        } catch (Throwable t) {
            log("installClassScanner err: " + t);
        }
    }

    // ============================ 改写核心 ============================

    /** 值映射：命中返回新值，未命中返回 null */
    private static String mapValue(String key, String val) {
        if (val == null) {
            return null;
        }
        if (EXTRA_OVERRIDES.containsKey(key)) {
            String nv = EXTRA_OVERRIDES.get(key);
            return nv.equals(val) ? null : nv;
        }
        for (String k : REWRITE_KEYS) {
            if (k.equals(key)) {
                if (isSource(val)) {
                    return TARGET_PLATFORM;
                }
                return null;
            }
        }
        return null;
    }

    private static boolean isSource(String v) {
        for (String s : SOURCE_VALUES) {
            if (s.equals(v)) {
                return true;
            }
        }
        return false;
    }

    /** 就地改写 JSONObject 中的目标字段，返回是否有改动 */
    private static boolean rewriteJsonObject(JSONObject o) {
        if (o == null) {
            return false;
        }
        boolean changed = false;
        try {
            for (String key : REWRITE_KEYS) {
                if (!o.has(key)) {
                    continue;
                }
                String v = o.optString(key, null);
                if (v != null && isSource(v)) {
                    o.put(key, TARGET_PLATFORM);
                    changed = true;
                    logOnce("json.put " + key + ": " + v + " -> " + TARGET_PLATFORM);
                }
            }
            for (Map.Entry<String, String> e : EXTRA_OVERRIDES.entrySet()) {
                if (!o.has(e.getKey())) {
                    continue;
                }
                String v = o.optString(e.getKey(), null);
                if (v != null && !v.equals(e.getValue())) {
                    o.put(e.getKey(), e.getValue());
                    changed = true;
                    logOnce("json.put " + e.getKey() + ": " + v + " -> " + e.getValue());
                }
            }
        } catch (Throwable ignored) {
        }
        return changed;
    }

    /** 字符串级 JSON 字段改写；无改动返回 null。覆盖 "platform":"ad" 与带空白变体。 */
    private static String rewriteJsonString(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        // 快速路径：不含目标字段名直接跳过
        boolean maybe = false;
        for (String k : REWRITE_KEYS) {
            if (s.contains(k)) {
                maybe = true;
                break;
            }
        }
        if (!maybe && EXTRA_OVERRIDES.isEmpty()) {
            return null;
        }
        String out = s;
        for (String key : REWRITE_KEYS) {
            for (String src : SOURCE_VALUES) {
                Pattern p = Pattern.compile(
                        "(\"" + Pattern.quote(key) + "\"\\s*:\\s*\")" + Pattern.quote(src) + "(\")");
                String rep = "$1" + TARGET_PLATFORM + "$2";
                String n = p.matcher(out).replaceAll(rep);
                if (!n.equals(out)) {
                    logOnce("str " + key + ": " + src + " -> " + TARGET_PLATFORM);
                    out = n;
                }
            }
        }
        for (Map.Entry<String, String> e : EXTRA_OVERRIDES.entrySet()) {
            Pattern p = Pattern.compile(
                    "(\"" + Pattern.quote(e.getKey()) + "\"\\s*:\\s*\")[^\"]*(\")");
            String rep = "$1" + Matcher_quote(e.getValue()) + "$2";
            String n = p.matcher(out).replaceAll(rep);
            if (!n.equals(out)) {
                logOnce("str override " + e.getKey() + " -> " + e.getValue());
                out = n;
            }
        }
        return out.equals(s) ? null : out;
    }

    /** 替换串里的 $ \ 转义（replaceAll 的 replacement 语义） */
    private static String Matcher_quote(String s) {
        return s.replace("\\", "\\\\").replace("$", "\\$");
    }

    // ============================ 日志 ============================

    private static void log(String msg) {
        MainHook.log("[Sauth] " + msg);
    }

    private static void logOnce(String msg) {
        synchronized (LOGGED) {
            if (!LOGGED.add(msg)) {
                return;
            }
        }
        log(msg);
    }

    private SauthPlatformHook() {
    }
}
