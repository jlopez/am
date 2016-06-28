/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.am;

import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.util.ProgressReporter;
import com.android.server.UiThread;

import java.util.List;

/**
 * Simple broadcaster that sends {@link Intent#ACTION_PRE_BOOT_COMPLETED} to all
 * system apps that register for it. Override {@link #onFinished()} to handle
 * when all broadcasts are finished.
 */
public abstract class PreBootBroadcaster extends IIntentReceiver.Stub {
    private static final String TAG = "PreBootBroadcaster";

    private final ActivityManagerService mService;
    private final int mUserId;
    private final ProgressReporter mProgress;

    private final Intent mIntent;
    private final List<ResolveInfo> mTargets;

    private int mIndex = 0;

    public PreBootBroadcaster(ActivityManagerService service, int userId,
            ProgressReporter progress) {
        mService = service;
        mUserId = userId;
        mProgress = progress;

        mIntent = new Intent(Intent.ACTION_PRE_BOOT_COMPLETED);
        mIntent.addFlags(Intent.FLAG_RECEIVER_BOOT_UPGRADE | Intent.FLAG_DEBUG_TRIAGED_MISSING);

        mTargets = mService.mContext.getPackageManager().queryBroadcastReceiversAsUser(mIntent,
                MATCH_SYSTEM_ONLY, UserHandle.of(userId));

        mHandler.obtainMessage(MSG_SHOW).sendToTarget();
    }

    public void sendNext() {
        if (mIndex >= mTargets.size()) {
            mHandler.obtainMessage(MSG_HIDE).sendToTarget();
            onFinished();
            return;
        }

        if (!mService.isUserRunning(mUserId, 0)) {
            Slog.i(TAG, "User " + mUserId + " is no longer running; skipping remaining receivers");
            mHandler.obtainMessage(MSG_HIDE).sendToTarget();
            onFinished();
            return;
        }

        final ResolveInfo ri = mTargets.get(mIndex++);
        final ComponentName componentName = ri.activityInfo.getComponentName();

        if (mProgress != null) {
            final CharSequence label = ri.activityInfo
                    .loadLabel(mService.mContext.getPackageManager());
            mProgress.setProgress(mIndex, mTargets.size(),
                    mService.mContext.getString(R.string.android_preparing_apk, label));
        }

        Slog.i(TAG, "Pre-boot of " + componentName.toShortString() + " for user " + mUserId);
        EventLogTags.writeAmPreBoot(mUserId, componentName.getPackageName());

        mIntent.setComponent(componentName);
        mService.broadcastIntentLocked(null, null, mIntent, null, this, 0, null, null, null,
                AppOpsManager.OP_NONE, null, true, false, ActivityManagerService.MY_PID,
                Process.SYSTEM_UID, mUserId);
    }

    @Override
    public void performReceive(Intent intent, int resultCode, String data, Bundle extras,
            boolean ordered, boolean sticky, int sendingUser) {
        sendNext();
    }

    private static final int MSG_SHOW = 1;
    private static final int MSG_HIDE = 2;

    private Handler mHandler = new Handler(UiThread.get().getLooper(), null, true) {
        @Override
        public void handleMessage(Message msg) {
            final Context context = mService.mContext;
            final NotificationManager notifManager = context
                    .getSystemService(NotificationManager.class);

            switch (msg.what) {
                case MSG_SHOW:
                    final CharSequence title = context
                            .getText(R.string.android_upgrading_notification_title);
                    final CharSequence message = context
                            .getText(R.string.android_upgrading_notification_body);
                    final Notification notif = new Notification.Builder(mService.mContext)
                            .setSmallIcon(R.drawable.stat_sys_adb)
                            .setWhen(0)
                            .setOngoing(true)
                            .setTicker(title)
                            .setDefaults(0)
                            .setPriority(Notification.PRIORITY_MAX)
                            .setColor(context.getColor(
                                    com.android.internal.R.color.system_notification_accent_color))
                            .setContentTitle(title)
                            .setContentText(message)
                            .setVisibility(Notification.VISIBILITY_PUBLIC)
                            .build();
                    notifManager.notifyAsUser(TAG, 0, notif, UserHandle.of(mUserId));
                    break;

                case MSG_HIDE:
                    notifManager.cancelAsUser(TAG, 0, UserHandle.of(mUserId));
                    break;
            }
        }
    };

    public abstract void onFinished();
}
