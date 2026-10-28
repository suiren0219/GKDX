package io.github.gkdhide.gkdx;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ContentResolver;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;

/**
 * 对外隐藏 GKD 的无障碍服务状态（原 GKDHide 模块的能力，已并入本模块）：
 * - 服务列表类查询：过滤掉目标服务（默认 li.songe.gkd，始终包含）
 * - 全局开关类查询：strict 模式下伪装成"无障碍未开启"
 * - Settings.Secure 设置表：enabled_accessibility_services / accessibility_enabled 等
 * GKD 本体进程不受影响，其无障碍服务保持正常开启与工作。
 */
public class HideHook {

    private static final String SELF_PACKAGE = BuildConfig.APPLICATION_ID;
    private static final String GKD_PACKAGE = "li.songe.gkd";

    /** 模块自身、GKD 本体、LSPosed 管理器、系统设置、SystemUI、system_server 中不做隐藏 */
    private static final Set<String> ALWAYS_SKIP = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            SELF_PACKAGE, GKD_PACKAGE, "org.lsposed.manager",
            "com.android.settings", "com.android.systemui", "android")));

    /** 配置缓存有效期，作用域应用进程内的配置修改靠它热生效 */
    private static final long CONFIG_TTL_MS = 3000;

    private static XSharedPreferences prefs;
    private static volatile Config cached;
    private static volatile long cacheTime;

    private static final class Config {
        boolean master = true;
        boolean strict = true;
        boolean hideInstalled = false;
        boolean log = false;
        Set<String> targets = new HashSet<>(Collections.singletonList(GKD_PACKAGE));
        Set<String> excludes = new HashSet<>();
    }

    private static Config config() {
        long now = SystemClock.elapsedRealtime();
        Config c = cached;
        if (c != null && now - cacheTime < CONFIG_TTL_MS) return c;
        c = new Config();
        try {
            if (prefs == null) {
                prefs = new XSharedPreferences(SELF_PACKAGE, "config");
            }
            prefs.reload();
            c.master = prefs.getBoolean("hide_master", true);
            c.strict = prefs.getBoolean("strict", true);
            c.hideInstalled = prefs.getBoolean("hide_installed", false);
            c.log = prefs.getBoolean("log", false);
            Set<String> t = parseList(prefs.getString("targets", ""));
            // GKD 是保护对象，始终包含，防止误配置后失去隐藏
            t.add(GKD_PACKAGE);
            c.targets = t;
            c.excludes = parseList(prefs.getString("excludes", ""));
        } catch (Throwable tr) {
            // 读不到配置时使用默认值，模块保持可用
        }
        cached = c;
        cacheTime = now;
        return c;
    }

    private static Set<String> parseList(String s) {
        Set<String> out = new HashSet<>();
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("[\\n,;]+")) {
            line = line.trim();
            if (!line.isEmpty()) out.add(line);
        }
        return out;
    }

    /** 当前进程此刻是否处于"隐藏生效"状态（配置可热切换，故每次 Hook 调用时判断） */
    private static boolean active(String hostPkg) {
        Config c = config();
        return c.master && !c.excludes.contains(hostPkg);
    }

    /** 由 EngineHook 在每个作用域应用进程启动时调用 */
    public static void install(final String hostPkg) {
        if (ALWAYS_SKIP.contains(hostPkg)) return;
        Config c = config();
        if (!c.master || c.excludes.contains(hostPkg)) return;

        // 各 Hook 独立安装，某一个失败不影响其余
        hookServiceListQueries(hostPkg);
        hookGlobalSwitch(hostPkg);
        hookSettingsSecure(hostPkg);

        if (c.log) SafeLog.INSTANCE.log("[GKDX] 无障碍隐藏已安装: " + hostPkg);
    }

    /** 1) AccessibilityManager 服务列表查询：把目标服务从返回列表中过滤掉 */
    private static void hookServiceListQueries(final String hostPkg) {
        try {
            XC_MethodHook enabledListFilter = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (active(hostPkg)) filterResult(param);
                }
            };
            XposedHelpers.findAndHookMethod(AccessibilityManager.class,
                    "getEnabledAccessibilityServiceList", int.class, enabledListFilter);

            XC_MethodHook installedListFilter = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Config c = config();
                    if (c.master && c.hideInstalled && !c.excludes.contains(hostPkg)) {
                        filterResult(param);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(AccessibilityManager.class,
                    "getInstalledAccessibilityServiceList", installedListFilter);
            XposedHelpers.findAndHookMethod(AccessibilityManager.class,
                    "getAccessibilityServiceList", installedListFilter);
        } catch (Throwable tr) {
            SafeLog.INSTANCE.log("[GKDX] 服务列表 Hook 失败: " + hostPkg);
            SafeLog.INSTANCE.log(tr);
        }
    }

    private static void filterResult(XC_MethodHook.MethodHookParam param) {
        Config c = config();
        Object result = param.getResult();
        if (!(result instanceof List)) return;
        List<?> list = (List<?>) result;
        if (list.isEmpty()) return;
        List<Object> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof AccessibilityServiceInfo
                    && shouldHide(c, ((AccessibilityServiceInfo) item).getId())) {
                continue;
            }
            out.add(item);
        }
        if (out.size() != list.size()) param.setResult(out);
    }

    /** 2) strict 模式：把无障碍全局开关伪装成关闭 */
    private static void hookGlobalSwitch(final String hostPkg) {
        try {
            XC_MethodHook fakeFalse = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Config c = config();
                    if (c.master && c.strict && !c.excludes.contains(hostPkg)) {
                        param.setResult(false);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(AccessibilityManager.class, "isEnabled", fakeFalse);
            XposedHelpers.findAndHookMethod(AccessibilityManager.class,
                    "isTouchExplorationEnabled", fakeFalse);
        } catch (Throwable tr) {
            SafeLog.INSTANCE.log("[GKDX] 全局开关 Hook 失败: " + hostPkg);
            SafeLog.INSTANCE.log(tr);
        }
    }

    /**
     * 3) Settings.Secure 设置表：Settings.Secure.getInt 基于 getString 实现，一并覆盖；
     *    getStringForUser 是部分应用绕开 getString 的调用路径。
     */
    private static void hookSettingsSecure(final String hostPkg) {
        try {
            XC_MethodHook secureHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Config c = config();
                    if (!c.master || c.excludes.contains(hostPkg)) return;
                    if (!(param.args[1] instanceof String)) return;
                    String key = (String) param.args[1];
                    if (Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES.equals(key)) {
                        String v = (String) param.getResult();
                        if (v == null || v.isEmpty()) return;
                        param.setResult(c.strict ? "" : filterSettingList(c, v));
                    } else if (Settings.Secure.ACCESSIBILITY_ENABLED.equals(key)
                            || Settings.Secure.TOUCH_EXPLORATION_ENABLED.equals(key)) {
                        if (c.strict) param.setResult("0");
                    }
                }
            };
            XposedHelpers.findAndHookMethod(Settings.Secure.class, "getString",
                    ContentResolver.class, String.class, secureHook);
            XposedHelpers.findAndHookMethod(Settings.Secure.class, "getStringForUser",
                    ContentResolver.class, String.class, int.class, secureHook);
        } catch (Throwable tr) {
            SafeLog.INSTANCE.log("[GKDX] Settings Hook 失败: " + hostPkg);
            SafeLog.INSTANCE.log(tr);
        }
    }

    /** serviceId 形如 "pkg/cls"，也支持直接配置包名做前缀匹配 */
    private static boolean shouldHide(Config c, String serviceId) {
        if (serviceId == null || serviceId.isEmpty()) return false;
        String pkg = serviceId;
        int slash = serviceId.indexOf('/');
        if (slash >= 0) pkg = serviceId.substring(0, slash);
        for (String t : c.targets) {
            if (pkg.equals(t) || serviceId.equals(t) || serviceId.startsWith(t + "/")) return true;
        }
        return false;
    }

    /** 过滤 Settings.Secure 里冒号分隔的服务组件串 */
    private static String filterSettingList(Config c, String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (String comp : value.split(":")) {
            if (comp.isEmpty()) continue;
            String pkg = comp;
            int slash = comp.indexOf('/');
            if (slash >= 0) pkg = comp.substring(0, slash);
            boolean hide = false;
            for (String t : c.targets) {
                if (pkg.equals(t) || comp.startsWith(t + "/")) {
                    hide = true;
                    break;
                }
            }
            if (!hide) {
                if (sb.length() > 0) sb.append(':');
                sb.append(comp);
            }
        }
        return sb.toString();
    }
}
