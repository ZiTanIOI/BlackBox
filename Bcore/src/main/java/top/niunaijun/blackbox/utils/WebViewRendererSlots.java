package top.niunaijun.blackbox.utils;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Process;

import top.niunaijun.blackbox.app.BActivityThread;

/**
 * WebView 渲染进程槽位分配。
 *
 * <p>Android 的 WebView provider 在 manifest 里声明了一池渲染服务
 * {@code org.chromium.content.app.SandboxedProcessService0 .. N}（本机实测 40 个），
 * Chromium 的 {@code ConnectionAllocator} 会在<b>自己的进程内</b>挑一个空闲索引去 bind。
 * 问题在于它每个进程都从 0 号开始挑：容器里多个 guest 进程（各自是独立的 host 子进程）
 * 于是全都 bind 到同一个 {@code SandboxedProcessService0}，而渲染进程里的
 * {@code ChildProcessService} 一次只服务一个客户端，第二个直接被打回：</p>
 *
 * <pre>
 * E/cr_ChildProcessService: Service is already bound by pid 1480, cannot bind for pid 2289
 * E/cr_ChildProcLauncher  : ChildProcessConnection.start failed, trying again   (无限重试)
 * </pre>
 *
 * <p>结果是第二个 guest 进程拿不到渲染进程，界面画不出来（WebView 类登录页会直接白屏 /
 * 立刻 finish）。这里在容器侧按 guest 进程给索引加一段独立偏移，让各 guest 各占一段槽位。</p>
 */
public final class WebViewRendererSlots {
    private static final String TAG = "WebViewRendererSlots";

    private static final String SERVICE_PREFIX = "org.chromium.content.app.SandboxedProcessService";

    /** provider 声明的服务总数（保守取 40，本机 WebView 恰好是这个数量）。 */
    private static final int POOL_SIZE = 40;
    /** 每个 guest 进程预留的槽位数。40 / 4 = 最多 10 个 guest 进程各自独占一段。 */
    private static final int SLOTS_PER_PROCESS = 4;

    private static final Object sLock = new Object();

    private WebViewRendererSlots() {
    }

    /**
     * 若 Intent 指向 WebView 渲染服务，则把服务索引按当前 guest 进程重新分配；
     * 否则原样返回。返回 null 表示无需改动。
     */
    public static ComponentName remapComponent(ComponentName componentName) {
        if (componentName == null) {
            return null;
        }
        String className = componentName.getClassName();
        if (className == null || !className.startsWith(SERVICE_PREFIX)) {
            return null;
        }
        int index;
        try {
            index = Integer.parseInt(className.substring(SERVICE_PREFIX.length()));
        } catch (Throwable t) {
            return null;
        }
        if (index < 0 || index >= POOL_SIZE) {
            return null;
        }
        int mapped = slotForCurrentProcess() * SLOTS_PER_PROCESS + index;
        if (mapped < 0 || mapped >= POOL_SIZE || mapped == index) {
            return null;
        }
        Slog.d(TAG, "remap webview renderer service " + index + " -> " + mapped
                + " (pid " + Process.myPid() + ")");
        return new ComponentName(componentName.getPackageName(), SERVICE_PREFIX + mapped);
    }

    /**
     * 在 bindService 钩子里按需改写目标组件。
     *
     * <p>返回一个<b>新的 Intent</b>（组件已改写），调用方应把它替换进参数里；无需改写时返回
     * null。必须是副本——Chromium 在渲染进程启动失败时会<b>重用同一个 Intent 对象</b>重试，
     * 若就地改写，重试会一路 0→4→8→12 漂移，永远绑不到正确槽位。</p>
     */
    public static Intent remapIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        try {
            ComponentName mapped = remapComponent(intent.getComponent());
            if (mapped == null) {
                return null;
            }
            Intent copy = new Intent(intent);
            copy.setComponent(mapped);
            return copy;
        } catch (Throwable t) {
            Slog.d(TAG, "remapIntent failed: " + t);
            return null;
        }
    }

    private static int slotForCurrentProcess() {
        // 注意：容器框架类在每个 guest 进程里各有一份，statics 不共享，
        // 所以不能用自己的计数器分配槽位（每个进程都会从 0 开始）。
        // 必须用容器在 :black 里统一分配的 guest 进程编号 bpid，它跨进程唯一。
        try {
            int bpid = BActivityThread.getAppPid();
            if (bpid >= 0) {
                return bpid % (POOL_SIZE / SLOTS_PER_PROCESS);
            }
        } catch (Throwable ignored) {
        }
        // 兜底：bpid 尚未初始化时按 pid 取模，至少不会所有进程都撞在 0 号。
        return Process.myPid() % (POOL_SIZE / SLOTS_PER_PROCESS);
    }
}
