package top.niunaijun.blackbox.utils.compat;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstalledModule;
import top.niunaijun.blackbox.utils.CloseUtils;

/**
 * Created by Milk on 5/2/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class XposedParserCompat {

    public static InstalledModule parseModule(ApplicationInfo applicationInfo) {
        try {
            PackageManager packageManager = BlackBoxCore.getPackageManager();
            InstalledModule module = new InstalledModule();
            module.packageName = applicationInfo.packageName;
            module.enable = false;
            module.desc = readDescription(packageManager, applicationInfo);
            module.name = applicationInfo.loadLabel(packageManager).toString();
            module.main = readMain(applicationInfo.sourceDir);
            return module;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 旧 API 模块的描述在 meta-data "xposeddescription"；
     * 新版 libxposed API 模块用 android:description 资源（ApplicationInfo.descriptionRes）。
     */
    private static String readDescription(PackageManager packageManager, ApplicationInfo applicationInfo) {
        if (applicationInfo.metaData != null) {
            String desc = applicationInfo.metaData.getString("xposeddescription");
            if (desc != null) return desc;
        }
        if (applicationInfo.descriptionRes != 0) {
            try {
                return packageManager.getResourcesForApplication(applicationInfo)
                        .getText(applicationInfo.descriptionRes).toString();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public static boolean isXPModule(String file) {
        try {
            String s = readMain(file);
            return s != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String readMain(String apk) {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(new File(apk));
            // 旧 API 模块入口在 assets/xposed_init；新版 libxposed API 模块在 META-INF/xposed/java_init.list
            ZipEntry entry = zipFile.getEntry("META-INF/xposed/java_init.list");
            if (entry == null) {
                entry = zipFile.getEntry("assets/xposed_init");
            }
            if (entry == null) {
                throw new RuntimeException();
            }
            return getInputStreamContent(zipFile.getInputStream(entry)).trim();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            CloseUtils.close(zipFile);
        }
        return null;
    }

    private static String getInputStreamContent(InputStream stream) {
        BufferedReader reader = null;
        StringBuilder builder = new StringBuilder();
        try {
            reader = new BufferedReader(new InputStreamReader(stream));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#"))
                    continue;
                builder.append(line).append("\n");
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            CloseUtils.close(reader);
        }
        return builder.toString();
    }
}
