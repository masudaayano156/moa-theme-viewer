package app.moa.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Enables exactly one predeclared launcher identity without clearing any app data. */
public final class LauncherIdentity {
    public static final String DEFAULT_ALIAS = "app.moa.client.LauncherDefault";
    private static volatile String lastError = "";

    private LauncherIdentity() { }

    public static String getLastError() {
        return lastError;
    }

    /**
     * The caller must durably save the desired preference before calling, then
     * restore the previous preference if this returns false. On the next launch,
     * reconcile aliases with that saved preference in case the task restarted.
     * The launcher may cache its old icon/name, and some OEM launchers restart
     * the task despite DONT_KILL_APP, so callers must persist drafts first.
     */
    public static synchronized boolean setTheme(Context context, ThemeSpec theme, boolean matchTheme) {
        lastError = "";
        if (context == null || theme == null) {
            lastError = "아이콘 설정을 적용할 수 없습니다.";
            return false;
        }
        PackageManager manager = context.getPackageManager();
        String target = matchTheme ? theme.launcherAlias : DEFAULT_ALIAS;
        LinkedHashMap<ComponentName, Integer> previous = new LinkedHashMap<>();
        ComponentName targetComponent = new ComponentName(context.getPackageName(), target);
        try {
            previous.put(new ComponentName(context.getPackageName(), DEFAULT_ALIAS),
                    manager.getComponentEnabledSetting(new ComponentName(context.getPackageName(), DEFAULT_ALIAS)));
            for (ThemeSpec candidate : ThemeSpec.values()) {
                ComponentName component = new ComponentName(context.getPackageName(), candidate.launcherAlias);
                previous.put(component, manager.getComponentEnabledSetting(component));
            }
            if (alreadySelected(previous, targetComponent)) return true;
            if (Build.VERSION.SDK_INT >= 33) {
                Api33.apply(manager, previous, targetComponent);
            } else {
                // Never leave the app with no launcher entry on API 26–32.
                manager.setComponentEnabledSetting(targetComponent,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
                for (ComponentName component : previous.keySet()) {
                    if (!component.equals(targetComponent)) {
                        manager.setComponentEnabledSetting(component,
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                    }
                }
            }
            return true;
        } catch (RuntimeException error) {
            // Best effort rollback: enable old visible entries before disabling
            // the new one, so a partial platform failure does not hide the app.
            boolean restoredEnabledEntry = false;
            for (Map.Entry<ComponentName, Integer> old : previous.entrySet()) {
                if (effectivelyEnabled(old.getKey(), old.getValue())) {
                    try {
                        manager.setComponentEnabledSetting(old.getKey(), old.getValue(), PackageManager.DONT_KILL_APP);
                        restoredEnabledEntry = true;
                    } catch (RuntimeException ignored) { }
                }
            }
            if (restoredEnabledEntry) {
                for (Map.Entry<ComponentName, Integer> old : previous.entrySet()) {
                    if (!effectivelyEnabled(old.getKey(), old.getValue())) {
                        try {
                            manager.setComponentEnabledSetting(old.getKey(), old.getValue(), PackageManager.DONT_KILL_APP);
                        } catch (RuntimeException ignored) { }
                    }
                }
            }
            lastError = "홈 화면 이름과 아이콘을 변경하지 못했습니다. 다시 시도해 주세요.";
            return false;
        }
    }

    private static boolean alreadySelected(Map<ComponentName, Integer> current, ComponentName target) {
        for (Map.Entry<ComponentName, Integer> entry : current.entrySet()) {
            if (effectivelyEnabled(entry.getKey(), entry.getValue()) != entry.getKey().equals(target)) return false;
        }
        return true;
    }

    private static boolean effectivelyEnabled(ComponentName component, int state) {
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                && component.getClassName().equals(ThemeSpec.MAIL.launcherAlias));
    }

    /** Isolated to avoid loading API 33-only types on Android 8–12. */
    @android.annotation.TargetApi(33)
    private static final class Api33 {
        static void apply(PackageManager manager, Map<ComponentName, Integer> current, ComponentName target) {
            List<PackageManager.ComponentEnabledSetting> changes = new ArrayList<>();
            for (ComponentName component : current.keySet()) {
                changes.add(new PackageManager.ComponentEnabledSetting(component,
                        component.equals(target) ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP));
            }
            manager.setComponentEnabledSettings(changes);
        }
    }
}
