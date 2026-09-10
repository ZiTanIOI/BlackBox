package top.niunaijun.blackbox.proxy.record;

import android.content.Intent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Slog;

/**
 * 分身 Activity 目标 Intent 的暂存区。
 *
 * <p>容器启动一个分身 Activity 时，需要把「分身自己发起的那个 Intent」从发起进程一路带到
 * 真正实例化 Activity 的进程。老实现是把它当 Parcelable extra 塞进代理 Intent
 * （{@code _B_|_target_}），而代理 Intent 必须先交给 system_server：部分 ROM（实测
 * HyperOS 的 "IntentRedirect Hardening"）会主动递归解包 nested extras，system_server 里
 * 当然没有分身 APK 的类，于是分身自定义的 Parcelable 被解成 null，目标 Activity 拿到的是
 * 残缺 Intent。logcat 表现为：</p>
 *
 * <pre>
 * E/Parcel: Class not found when unmarshalling: com.example.sdk.LoginRequest
 * W/Bundle: android.os.BadParcelableException: ClassNotFoundException when unmarshalling: ...
 * </pre>
 *
 * <p>这里改成「代理 Intent 里只放一个 String token，Intent 本体走容器内部 binder」：
 * 代理 Intent 中不再出现任何分身类，system_server 无从破坏；Intent 本体经 binder 传递时
 * extras 始终是 raw bundle，直到真正回到分身进程（类加载器可用）才解包。</p>
 */
public final class PendingTargetIntents {
    private static final String TAG = "PendingTargetIntents";

    /** 暂存条目存活时间。正常链路在毫秒级内就会被取走，这里只做兜底清理。 */
    private static final long TTL_MS = 60_000L;
    private static final int MAX_ENTRIES = 64;

    private static final Map<String, Entry> sPending = new HashMap<>();

    private PendingTargetIntents() {
    }

    private static final class Entry {
        final Intent intent;
        final long time;

        Entry(Intent intent) {
            this.intent = intent;
            this.time = System.currentTimeMillis();
        }
    }

    // ---------------- 服务端（:black 进程） ----------------

    public static String putLocal(Intent intent) {
        if (intent == null) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        synchronized (sPending) {
            trimLocked();
            sPending.put(token, new Entry(intent));
        }
        return token;
    }

    public static Intent getLocal(String token) {
        if (token == null) {
            return null;
        }
        synchronized (sPending) {
            Entry entry = sPending.get(token);
            if (entry == null) {
                return null;
            }
            if (System.currentTimeMillis() - entry.time > TTL_MS) {
                sPending.remove(token);
                return null;
            }
            // 非破坏性读取：同一次启动可能被多处置询（HCallback / ProxyActivity）
            return entry.intent;
        }
    }

    private static void trimLocked() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Entry>> it = sPending.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().time > TTL_MS) {
                it.remove();
            }
        }
        while (sPending.size() >= MAX_ENTRIES) {
            Iterator<Map.Entry<String, Entry>> oldest = sPending.entrySet().iterator();
            if (!oldest.hasNext()) {
                break;
            }
            oldest.next();
            oldest.remove();
        }
    }

    // ---------------- 客户端（任意进程） ----------------

    /**
     * 暂存 Intent 并返回 token。服务端进程直接落本地 map，其余进程走容器内部 binder。
     * 失败返回 null，调用方回退到旧的 Parcelable 方案。
     */
    public static String stash(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (isServerSide()) {
            return putLocal(intent);
        }
        try {
            return BlackBoxCore.getBActivityManager().putPendingTargetIntent(intent);
        } catch (Throwable e) {
            Slog.d(TAG, "stash failed: " + e);
            return null;
        }
    }

    /** 按 token 取回 Intent；取不到返回 null（调用方回退到旧的 Parcelable 方案）。 */
    public static Intent retrieve(String token) {
        if (token == null) {
            return null;
        }
        if (isServerSide()) {
            return getLocal(token);
        }
        try {
            return BlackBoxCore.getBActivityManager().getPendingTargetIntent(token);
        } catch (Throwable e) {
            Slog.d(TAG, "retrieve failed: " + e);
            return null;
        }
    }

    private static boolean isServerSide() {
        try {
            return BlackBoxCore.get().isServerProcess();
        } catch (Throwable t) {
            return false;
        }
    }
}
