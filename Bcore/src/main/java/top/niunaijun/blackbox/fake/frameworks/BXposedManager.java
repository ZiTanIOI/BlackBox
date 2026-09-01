package top.niunaijun.blackbox.fake.frameworks;

import android.os.RemoteException;

import java.util.Collections;
import java.util.List;

import top.niunaijun.blackbox.core.system.ServiceManager;
import top.niunaijun.blackbox.core.system.pm.IBXposedManagerService;
import top.niunaijun.blackbox.entity.pm.InstalledModule;

/**
 * Created by Milk on 5/2/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BXposedManager extends BlackManager<IBXposedManagerService> {
    private static final BXposedManager sXposedManager = new BXposedManager();

    public static BXposedManager get() {
        return sXposedManager;
    }

    @Override
    protected String getServiceName() {
        return ServiceManager.XPOSED_MANAGER;
    }

    public boolean isXPEnable() {
        try {
            return getService().isXPEnable();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    public void setXPEnable(boolean enable) {
        try {
            getService().setXPEnable(enable);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public boolean isModuleEnable(String packageName) {
        try {
            return getService().isModuleEnable(packageName);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    public void setModuleEnable(String packageName, boolean enable) {
        try {
            getService().setModuleEnable(packageName, enable);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public List<InstalledModule> getInstalledModules() {
        try {
            return getService().getInstalledModules();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return Collections.emptyList();
    }

    /**
     * 杀掉全部容器应用进程，下次拉起重新 bind、加载最新配置
     */
    public void killAllProcesses() {
        try {
            getService().killAllProcesses();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /**
     * 杀掉指定应用的容器进程，用于配置（如 libc hook 禁用）变更后生效
     */
    public void killPackageAsUser(String packageName, int userId) {
        try {
            getService().killPackageAsUser(packageName, userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
}
