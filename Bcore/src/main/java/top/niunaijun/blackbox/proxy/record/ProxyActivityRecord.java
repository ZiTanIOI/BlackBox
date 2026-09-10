package top.niunaijun.blackbox.proxy.record;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.IBinder;

import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BundleCompat;

/**
 * Created by Milk on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ProxyActivityRecord {
    /** 目标 Intent 的 token（主通道，见 {@link PendingTargetIntents}）。 */
    public static final String KEY_TARGET_TOKEN = "_B_|_target_token_";
    /** 目标 Intent 本体（回退通道，仅在 token 取不到时使用）。 */
    public static final String KEY_TARGET = "_B_|_target_";

    public int mUserId;
    public ActivityInfo mActivityInfo;
    public Intent mTarget;
    public IBinder mActivityRecord;

    public ProxyActivityRecord(int userId, ActivityInfo activityInfo, Intent target, IBinder activityRecord) {
        mUserId = userId;
        mActivityInfo = activityInfo;
        mTarget = target;
        mActivityRecord = activityRecord;
    }

    public static void saveStub(Intent shadow, Intent target, ActivityInfo activityInfo, IBinder activityRecord, int userId) {
        shadow.putExtra("_B_|_user_id_", userId);
        shadow.putExtra("_B_|_activity_info_", activityInfo);
        // 主通道：Intent 本体走容器内部 binder，代理 Intent 里只放 token。
        // 代理 Intent 必然要经过 system_server，而部分 ROM 会主动递归解包 nested extras；
        // 一旦把分身 Intent 本体放进去，分身自定义的 Parcelable 会在 system_server 里
        // ClassNotFound 被解成 null，目标 Activity 就拿到残缺 Intent。
        String token = PendingTargetIntents.stash(target);
        if (token != null) {
            shadow.putExtra(KEY_TARGET_TOKEN, token);
        } else {
            // 回退通道：暂存失败（极少见）才退回老方案——把 Intent 本体当 Parcelable 传，
            // 此时可能丢掉分身自定义的 Parcelable，但至少不会完全起不来。
            shadow.putExtra(KEY_TARGET, target);
        }
        BundleCompat.putBinder(shadow, "_B_|_activity_record_v_", activityRecord);
    }

    public static ProxyActivityRecord create(Intent intent) {
        int userId = intent.getIntExtra("_B_|_user_id_", 0);
        ActivityInfo activityInfo = intent.getParcelableExtra("_B_|_activity_info_");
        String token = intent.getStringExtra(KEY_TARGET_TOKEN);
        Intent target = PendingTargetIntents.retrieve(token);
        boolean stashHit = target != null;
        if (target == null) {
            target = intent.getParcelableExtra(KEY_TARGET);
        }
        Slog.d("ProxyActivityRecord", "create: token=" + token + " stashHit=" + stashHit
                + " target=" + (target == null ? null : target.getComponent()));
        IBinder activityRecord = BundleCompat.getBinder(intent, "_B_|_activity_record_v_");
        return new ProxyActivityRecord(userId, activityInfo, target, activityRecord);
    }
}
