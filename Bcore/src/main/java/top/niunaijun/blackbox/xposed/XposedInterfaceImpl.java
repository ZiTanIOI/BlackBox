package top.niunaijun.blackbox.xposed;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.error.HookFailedError;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Slog;

/**
 * XposedInterface 的 BlackBox 实现，底层复用 Pine 引擎（与容器内 de.robv 旧 API 同一条 hook 通路）。
 * <p>
 * 每个 Executable 只挂一个 Pine hook（CompositeHook），其上按 priority 降序维护 hooker 列表；
 * ChainImpl.proceed 依次推进到下一个 hooker，链尾通过 Pine 的 backup 方法调用原始实现，
 * 因此多个 hooker 之间是真正的链式关系，unhook/替换在注册表上做快照级修改，不影响进行中的调用。
 */
public class XposedInterfaceImpl implements XposedInterface {
    private static final String TAG = "LibXposed";

    private static final ConcurrentHashMap<Executable, CompositeHook> sHooks = new ConcurrentHashMap<>();

    private final String mModulePackage;
    private final ApplicationInfo mModuleAppInfo;
    private final ExceptionMode mDefaultExceptionMode;

    public XposedInterfaceImpl(String modulePackage, ApplicationInfo moduleAppInfo, String defaultExceptionMode) {
        mModulePackage = modulePackage;
        mModuleAppInfo = moduleAppInfo;
        mDefaultExceptionMode = "passthrough".equals(defaultExceptionMode) ? ExceptionMode.PASSTHROUGH : ExceptionMode.PROTECTIVE;
    }

    @NonNull
    @Override
    public String getFrameworkName() {
        return "BlackBox";
    }

    @NonNull
    @Override
    public String getFrameworkVersion() {
        try {
            PackageInfo pi = BlackBoxCore.getPackageManager().getPackageInfo(BlackBoxCore.getHostPkg(), 0);
            return pi.versionName;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    @Override
    public long getFrameworkVersionCode() {
        try {
            PackageInfo pi = BlackBoxCore.getPackageManager().getPackageInfo(BlackBoxCore.getHostPkg(), 0);
            return pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public long getFrameworkProperties() {
        return PROP_CAP_REMOTE;
    }

    @Override
    public void log(int priority, @Nullable String tag, @NonNull String msg) {
        Log.println(priority, tag != null ? tag : "XposedModule", msg);
    }

    @Override
    public void log(int priority, @Nullable String tag, @NonNull String msg, @Nullable Throwable tr) {
        Log.println(priority, tag != null ? tag : "XposedModule", msg + "\n" + Log.getStackTraceString(tr));
    }

    @NonNull
    @Override
    public HookBuilder hook(@NonNull Executable origin) {
        return new HookBuilderImpl(origin);
    }

    @NonNull
    @Override
    public HookBuilder hookClassInitializer(@NonNull Class<?> origin) {
        // Pine 的 before/after 桥没有 <clinit> 入口，暂不支持
        throw new HookFailedError("hookClassInitializer is not supported by BlackBox");
    }

    @Override
    public boolean deoptimize(@NonNull Executable executable) {
        try {
            return Pine.decompile(executable, true);
        } catch (Throwable t) {
            Slog.e(TAG, "deoptimize failed for " + executable, t);
            return false;
        }
    }

    @NonNull
    @Override
    public Invoker<?, Method> getInvoker(@NonNull Method method) {
        return new MethodInvokerImpl(method);
    }

    @NonNull
    @Override
    public <T> CtorInvoker<T> getInvoker(@NonNull Constructor<T> constructor) {
        return new CtorInvokerImpl<>(constructor);
    }

    @NonNull
    @Override
    public ApplicationInfo getModuleApplicationInfo() {
        return mModuleAppInfo;
    }

    @NonNull
    @Override
    public SharedPreferences getRemotePreferences(@NonNull String group) {
        // BlackBox 是内嵌框架：远端配置落在宿主私有目录的 SharedPreferences 里，
        // 只有容器进程内可访问，模块 App 自身（libxposed/service）暂不打通
        String name = "xposed_remote_" + mModulePackage + "_" + sanitizeName(group);
        return BlackBoxCore.getContext().getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    @NonNull
    @Override
    public String[] listRemoteFiles() {
        String[] files = remoteDir().list();
        return files != null ? files : new String[0];
    }

    @NonNull
    @Override
    public ParcelFileDescriptor openRemoteFile(@NonNull String name) throws FileNotFoundException {
        if (!isValidFileName(name)) {
            throw new FileNotFoundException("Illegal remote file name: " + name);
        }
        File file = new File(remoteDir(), name);
        if (!file.isFile()) {
            throw new FileNotFoundException("Remote file not found: " + name);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private File remoteDir() {
        return new File(BlackBoxCore.getContext().getApplicationInfo().dataDir, "xposed_remote/" + mModulePackage);
    }

    private static String sanitizeName(String group) {
        return group == null || group.isEmpty() ? "default" : group.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private static boolean isValidFileName(String name) {
        return name != null && !name.isEmpty() && !name.contains("/") && !name.contains("\\")
                && !".".equals(name) && !"..".equals(name);
    }

    private static final class Entry {
        final String moduleKey;
        final String id;
        final int priority;
        final ExceptionMode mode;
        final Hooker hooker;

        Entry(String moduleKey, String id, int priority, ExceptionMode mode, Hooker hooker) {
            this.moduleKey = moduleKey;
            this.id = id;
            this.priority = priority;
            this.mode = mode;
            this.hooker = hooker;
        }
    }

    private static final class CompositeHook extends MethodHook {
        final Executable target;
        final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();
        volatile MethodHook.Unhook pineUnhook;

        static CompositeHook ensure(Executable target) throws Throwable {
            CompositeHook hook = sHooks.get(target);
            if (hook != null) return hook;
            hook = new CompositeHook(target);
            CompositeHook prev = sHooks.putIfAbsent(target, hook);
            if (prev != null) return prev;
            try {
                hook.pineUnhook = Pine.hook(target, hook);
            } catch (Throwable t) {
                sHooks.remove(target);
                throw t;
            }
            return hook;
        }

        private CompositeHook(Executable target) {
            this.target = target;
        }

        void add(Entry entry) {
            // priority 降序插入（同优先级保持注册顺序）
            int i = 0;
            for (Entry e : entries) {
                if (e.priority < entry.priority) break;
                i++;
            }
            entries.add(i, entry);
        }

        Entry findById(String moduleKey, String id) {
            for (Entry e : entries) {
                if (id.equals(e.id) && moduleKey.equals(e.moduleKey)) return e;
            }
            return null;
        }

        void replaceEntry(Entry oldEntry, Entry newEntry) {
            int idx = entries.indexOf(oldEntry);
            if (idx < 0) throw new IllegalStateException("hook handle is no longer valid");
            entries.set(idx, newEntry);
        }

        void remove(Entry entry) {
            entries.remove(entry);
            if (entries.isEmpty()) {
                MethodHook.Unhook unhook = pineUnhook;
                if (unhook != null) unhook.unhook();
                sHooks.remove(target);
            }
        }

        @Override
        public void beforeCall(Pine.CallFrame frame) {
            Entry[] snapshot = entries.toArray(new Entry[0]);
            if (snapshot.length == 0) return;
            ChainImpl chain = new ChainImpl(frame, snapshot);
            try {
                frame.setResult(chain.run());
            } catch (Throwable t) {
                frame.setThrowable(t);
            }
        }
    }

    private static final class ChainImpl implements Chain {
        private final Pine.CallFrame mFrame;
        private final Entry[] mEntries;
        private Object mThisObject;
        private Object[] mArgs;
        private int mIndex;
        private boolean mProceedRan;
        private Object mLastProceedResult;
        private Throwable mLastProceedThrowable;

        ChainImpl(Pine.CallFrame frame, Entry[] entries) {
            mFrame = frame;
            mEntries = entries;
            mThisObject = frame.thisObject;
            mArgs = frame.args;
        }

        Object run() throws Throwable {
            return callEntry(mEntries[0]);
        }

        private Object callEntry(Entry entry) throws Throwable {
            Object savedResult = mLastProceedResult;
            Throwable savedThrowable = mLastProceedThrowable;
            boolean savedRan = mProceedRan;
            mLastProceedResult = null;
            mLastProceedThrowable = null;
            mProceedRan = false;
            try {
                return entry.hooker.intercept(this);
            } catch (Throwable t) {
                if (entry.mode == ExceptionMode.PASSTHROUGH) throw t;
                if (mProceedRan) {
                    // hooker 在 proceed 之后才抛异常：沿用 proceed 的结果/异常
                    if (mLastProceedThrowable != null) throw mLastProceedThrowable;
                    return mLastProceedResult;
                }
                // hooker 在 proceed 之前抛异常：跳过它继续执行链（PROTECTIVE）
                Slog.w(TAG, "Hooker " + entry.hooker.getClass().getName() + " threw before proceed, continue without it", t);
                return doProceed();
            } finally {
                mLastProceedResult = savedResult;
                mLastProceedThrowable = savedThrowable;
                mProceedRan = savedRan;
            }
        }

        private Object doProceed() throws Throwable {
            mProceedRan = true;
            if (mIndex >= mEntries.length) {
                // 链尾：Pine backup 直接调原始实现，绕过所有 hook
                try {
                    return mFrame.invokeOriginalMethod(mThisObject, mArgs);
                } catch (InvocationTargetException e) {
                    throw e.getTargetException();
                }
            }
            Entry entry = mEntries[mIndex];
            mIndex++;
            try {
                return callEntry(entry);
            } finally {
                mIndex--;
            }
        }

        @Override
        public Object proceed() throws Throwable {
            return doProceed();
        }

        @Override
        public Object proceed(@NonNull Object[] args) throws Throwable {
            if (args == null) throw new IllegalArgumentException("args must not be null");
            Object[] saved = mArgs;
            mArgs = args;
            try {
                return doProceed();
            } finally {
                mArgs = saved;
            }
        }

        @Override
        public Object proceedWith(@NonNull Object thisObject) throws Throwable {
            Object saved = mThisObject;
            mThisObject = thisObject;
            try {
                return doProceed();
            } finally {
                mThisObject = saved;
            }
        }

        @Override
        public Object proceedWith(@NonNull Object thisObject, @NonNull Object[] args) throws Throwable {
            if (args == null) throw new IllegalArgumentException("args must not be null");
            Object savedThis = mThisObject;
            Object[] savedArgs = mArgs;
            mThisObject = thisObject;
            mArgs = args;
            try {
                return doProceed();
            } finally {
                mThisObject = savedThis;
                mArgs = savedArgs;
            }
        }

        @NonNull
        @Override
        public Executable getExecutable() {
            return (Executable) mFrame.method;
        }

        @Override
        public Object getThisObject() {
            return mThisObject;
        }

        @NonNull
        @Override
        public List<Object> getArgs() {
            return Collections.unmodifiableList(Arrays.asList(mArgs));
        }

        @Override
        public Object getArg(int index) {
            return mArgs[index];
        }
    }

    private final class HookBuilderImpl implements HookBuilder {
        private final Executable mOrigin;
        private int mPriority = PRIORITY_DEFAULT;
        private ExceptionMode mMode = ExceptionMode.DEFAULT;
        private String mId;

        HookBuilderImpl(Executable origin) {
            mOrigin = origin;
        }

        @Override
        public HookBuilder setPriority(int priority) {
            mPriority = priority;
            return this;
        }

        @Override
        public HookBuilder setExceptionMode(@NonNull ExceptionMode mode) {
            if (mode == null) throw new IllegalArgumentException("mode must not be null");
            mMode = mode;
            return this;
        }

        @Override
        public HookBuilder setId(@Nullable String id) {
            mId = id;
            return this;
        }

        @NonNull
        @Override
        public HookHandle intercept(@NonNull Hooker hooker) {
            if (hooker == null) throw new IllegalArgumentException("hooker must not be null");
            ExceptionMode mode = mMode == ExceptionMode.DEFAULT ? mDefaultExceptionMode : mMode;
            try {
                CompositeHook composite = CompositeHook.ensure(mOrigin);
                if (mId != null) {
                    Entry existing = composite.findById(mModulePackage, mId);
                    if (existing != null) {
                        Entry replaced = new Entry(mModulePackage, mId, mPriority, mode, hooker);
                        composite.replaceEntry(existing, replaced);
                        return new HookHandleImpl(composite, replaced);
                    }
                }
                Entry entry = new Entry(mModulePackage, mId, mPriority, mode, hooker);
                composite.add(entry);
                return new HookHandleImpl(composite, entry);
            } catch (Throwable t) {
                throw new HookFailedError("Failed to hook " + mOrigin, t);
            }
        }
    }

    private static final class HookHandleImpl implements HookHandle {
        final CompositeHook mComposite;
        Entry mEntry;
        boolean mUnhooked;

        HookHandleImpl(CompositeHook composite, Entry entry) {
            mComposite = composite;
            mEntry = entry;
        }

        @NonNull
        @Override
        public Executable getExecutable() {
            return mComposite.target;
        }

        @Override
        public void unhook() {
            if (mUnhooked) return;
            mUnhooked = true;
            mComposite.remove(mEntry);
        }

        @Override
        public String getId() {
            return mEntry.id;
        }

        @NonNull
        @Override
        public HookHandle replaceHook(@NonNull Hooker hooker) {
            if (hooker == null) throw new IllegalArgumentException("hooker must not be null");
            Entry replaced = new Entry(mEntry.moduleKey, mEntry.id, mEntry.priority, mEntry.mode, hooker);
            mComposite.replaceEntry(mEntry, replaced);
            mEntry = replaced;
            return this;
        }
    }

    private static final class MethodInvokerImpl implements Invoker<MethodInvokerImpl, Method> {
        private final Method mMethod;

        MethodInvokerImpl(Method method) {
            mMethod = method;
            method.setAccessible(true);
        }

        @Override
        public MethodInvokerImpl setType(@NonNull Invoker.Type type) {
            // Origin/Chain 类型不区分：BlackBox 的 Invoker 走普通反射，不经过 hook 链
            return this;
        }

        @Override
        public Object invoke(Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            return mMethod.invoke(thisObject, args);
        }

        @Override
        public Object invokeSpecial(@NonNull Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            // 反射没有非虚调用语义，这里退化为普通 invoke
            return mMethod.invoke(thisObject, args);
        }
    }

    private static final class CtorInvokerImpl<T> implements CtorInvoker<T> {
        private final Constructor<T> mCtor;

        CtorInvokerImpl(Constructor<T> constructor) {
            mCtor = constructor;
            constructor.setAccessible(true);
        }

        @Override
        public CtorInvoker<T> setType(@NonNull Invoker.Type type) {
            return this;
        }

        @Override
        public Object invoke(Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            try {
                return mCtor.newInstance(args);
            } catch (InstantiationException e) {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public Object invokeSpecial(@NonNull Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            try {
                // 退化为重新构造一个实例，无法真正初始化传入的未初始化对象
                return mCtor.newInstance(args);
            } catch (InstantiationException e) {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public T newInstance(Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException {
            return mCtor.newInstance(args);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <U> U newInstanceSpecial(@NonNull Class<U> subClass, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException {
            // Android 反射没有“对已分配对象执行 <init>”的公开入口；仅支持 subClass 即声明类的场景
            if (subClass != mCtor.getDeclaringClass()) {
                throw new UnsupportedOperationException(
                        "newInstanceSpecial with a subclass is not supported by BlackBox");
            }
            @SuppressWarnings("unchecked")
            U instance = (U) (Object) mCtor.newInstance(args);
            return instance;
        }
    }
}
