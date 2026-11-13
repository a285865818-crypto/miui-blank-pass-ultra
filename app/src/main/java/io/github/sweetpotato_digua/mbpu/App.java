package io.github.sweetpotato_digua.mbpu;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 进程级单例：尽早注册 libxposed service 监听，并缓存 IXposedService 与 RemotePreferences。
 * 同时记录存活 Activity，用于语言切换后统一重建。
 */
public class App extends Application {

    public interface Listener {
        void onServiceChanged();
    }

    private static volatile XposedService service;
    private static volatile SharedPreferences remote;
    private static Store store;
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final List<Activity> activities = new CopyOnWriteArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        // 高强度安全校验：签名校验 + 反调试 + 反注入。
        // 校验失败 abort()，进程直接崩溃，不给逆向者继续分析的机会。
        // 必须放在最前面：防止后续初始化逻辑在被调试时被执行。
        Security.verify(this);
        // 主题模式必须在使用前就位，否则首帧会用错配色再跳变
        store = new Store(this);
        AppThemeState.INSTANCE.init(store);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                activities.add(activity);
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityResumed(Activity activity) {
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                activities.remove(activity);
            }
        });

        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService s) {
                    service = s;
                    try {
                        remote = s.getRemotePreferences(Config.GROUP);
                    } catch (Throwable t) {
                        remote = null;
                    }
                    store.setRemote(remote);
                    fire();
                }

                @Override
                public void onServiceDied(XposedService s) {
                    if (service != s) return;
                    service = null;
                    remote = null;
                    store.setRemote(null);
                    fire();
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * 语言变更后重建所有存活页面。
     * <p>
     * 只重建当前 Activity 不够：MainActivity 还在返回栈里，它的 Resource 仍是旧语言，
     * 回到主界面就会看到一半新语言一半旧语言。
     */
    public static void recreateActivities() {
        for (Activity activity : activities) {
            if (activity.isFinishing() || activity.isDestroyed()) continue;
            try {
                activity.recreate();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void fire() {
        for (Listener l : listeners) {
            try {
                l.onServiceChanged();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void addListener(Listener l) {
        listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    public static Store store() {
        return store;
    }

    public static XposedService service() {
        return service;
    }

    public static SharedPreferences remotePrefs() {
        return remote;
    }
}
