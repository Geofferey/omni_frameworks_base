/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.systemui.statusbar.car;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.car.Car;
import android.car.trust.CarTrustAgentEnrollmentManager;
import android.car.userlib.CarUserManagerHelper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.view.View;
import android.view.ViewStub;

import androidx.recyclerview.widget.GridLayoutManager;

import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.R;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.SystemUIPrimaryWindowController;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.car.CarTrustAgentUnlockDialogHelper.OnHideListener;
import com.android.systemui.statusbar.car.UserGridRecyclerView.UserRecord;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages the fullscreen user switcher.
 */
@Singleton
public class FullscreenUserSwitcher {
    private static final String TAG = FullscreenUserSwitcher.class.getSimpleName();
    private final Context mContext;
    private final Resources mResources;
    private final UserManager mUserManager;
    private final CarServiceProvider mCarServiceProvider;
    private final CarTrustAgentUnlockDialogHelper mUnlockDialogHelper;
    private final SystemUIPrimaryWindowController mSystemUIPrimaryWindowController;
    private CarStatusBar mCarStatusBar;
    private final int mShortAnimDuration;

    private View mParent;
    private UserGridRecyclerView mUserGridView;
    private CarTrustAgentEnrollmentManager mEnrollmentManager;
    private UserGridRecyclerView.UserRecord mSelectedUser;
    private CarUserManagerHelper mCarUserManagerHelper;
    private final BroadcastReceiver mUserUnlockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "user 0 is unlocked, SharedPreference is accessible.");
            }
            showDialogForInitialUser();
            mContext.unregisterReceiver(mUserUnlockReceiver);
        }
    };

    @Inject
    public FullscreenUserSwitcher(
            Context context,
            @Main Resources resources,
            UserManager userManager,
            CarServiceProvider carServiceProvider,
            CarTrustAgentUnlockDialogHelper carTrustAgentUnlockDialogHelper,
            SystemUIPrimaryWindowController systemUIPrimaryWindowController) {
        mContext = context;
        mResources = resources;
        mUserManager = userManager;
        mCarServiceProvider = carServiceProvider;
        mUnlockDialogHelper = carTrustAgentUnlockDialogHelper;
        mSystemUIPrimaryWindowController = systemUIPrimaryWindowController;

        mShortAnimDuration = mResources.getInteger(android.R.integer.config_shortAnimTime);
    }

    /** Sets the status bar which gives an entry point to dismiss the keyguard. */
    // TODO: Remove this in favor of a keyguard controller.
    public void setStatusBar(CarStatusBar statusBar) {
        mCarStatusBar = statusBar;
    }

    /** Returns {@code true} if the user switcher already has a parent view. */
    public boolean isAttached() {
        return mParent != null;
    }

    /** Sets the {@link ViewStub} to show the user switcher. */
    public void setContainer(ViewStub containerStub) {
        if (isAttached()) {
            return;
        }

        mParent = containerStub.inflate();

        View container = mParent.findViewById(R.id.container);

        // Initialize user grid.
        mUserGridView = container.findViewById(R.id.user_grid);
        GridLayoutManager layoutManager = new GridLayoutManager(mContext,
                mResources.getInteger(R.integer.user_fullscreen_switcher_num_col));
        mUserGridView.setLayoutManager(layoutManager);
        mUserGridView.buildAdapter();
        mUserGridView.setUserSelectionListener(this::onUserSelected);
        mCarUserManagerHelper = new CarUserManagerHelper(mContext);
        mCarServiceProvider.addListener(
                car -> mEnrollmentManager = (CarTrustAgentEnrollmentManager) car.getCarManager(
                        Car.CAR_TRUST_AGENT_ENROLLMENT_SERVICE));

        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        if (mUserManager.isUserUnlocked(UserHandle.USER_SYSTEM)) {
            // User0 is unlocked, switched to the initial user
            showDialogForInitialUser();
        } else {
            // listen to USER_UNLOCKED
            mContext.registerReceiverAsUser(mUserUnlockReceiver,
                    UserHandle.getUserHandleForUid(UserHandle.USER_SYSTEM),
                    filter,
                    /* broadcastPermission= */ null,
                    /* scheduler */ null);
        }
    }

    private void showDialogForInitialUser() {
        int initialUser = mCarUserManagerHelper.getInitialUser();
        UserInfo initialUserInfo = mUserManager.getUserInfo(initialUser);
        mSelectedUser = new UserRecord(initialUserInfo, UserRecord.FOREGROUND_USER);

        // If the initial user has screen lock and trusted device, display the unlock dialog on the
        // keyguard.
        if (hasScreenLock(initialUser) && hasTrustedDevice(initialUser)) {
            mUnlockDialogHelper.showUnlockDialogAfterDelay(initialUser,
                    mOnHideListener);
        } else {
            // If no trusted device, dismiss the keyguard.
            dismissUserSwitcher();
        }
    }

    /**
     * Makes user grid visible.
     */
    public void show() {
        if (!isAttached()) {
            return;
        }
        mParent.setVisibility(View.VISIBLE);
        mSystemUIPrimaryWindowController.setWindowExpanded(true);
    }

    /**
     * Hides the user grid.
     */
    public void hide() {
        if (!isAttached()) {
            return;
        }
        mParent.setVisibility(View.INVISIBLE);
        mSystemUIPrimaryWindowController.setWindowExpanded(false);
    }

    /**
     * @return {@code true} if user grid is visible, {@code false} otherwise.
     */
    public boolean isVisible() {
        if (!isAttached()) {
            return false;
        }
        return mParent.getVisibility() == View.VISIBLE;
    }

    /**
     * Every time user clicks on an item in the switcher, if the clicked user has no trusted device,
     * we hide the switcher, either gradually or immediately.
     *
     * If the user has trusted device, we show an unlock dialog to notify user the unlock state.
     * When the unlock dialog is dismissed by user, we hide the unlock dialog and the switcher.
     *
     * We dismiss the entire keyguard when we hide the switcher if user clicked on the foreground
     * user (user we're already logged in as).
     */
    private void onUserSelected(UserGridRecyclerView.UserRecord record) {
        mSelectedUser = record;
        if (record.mInfo != null) {
            if (hasScreenLock(record.mInfo.id) && hasTrustedDevice(record.mInfo.id)) {
                mUnlockDialogHelper.showUnlockDialog(record.mInfo.id, mOnHideListener);
                return;
            }
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "no trusted device enrolled for uid: " + record.mInfo.id);
            }
        }
        dismissUserSwitcher();
    }

    private void dismissUserSwitcher() {
        if (mSelectedUser == null) {
            Log.e(TAG, "Request to dismiss user switcher, but no user selected");
            return;
        }
        if (mSelectedUser.mType == UserRecord.FOREGROUND_USER) {
            hide();
            mCarStatusBar.dismissKeyguard();
            return;
        }
        // Switching is about to happen, since it takes time, fade out the switcher gradually.
        fadeOut();
    }

    private void fadeOut() {
        mUserGridView.animate()
                .alpha(0.0f)
                .setDuration(mShortAnimDuration)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        hide();
                        mUserGridView.setAlpha(1.0f);
                    }
                });

    }

    private boolean hasScreenLock(int uid) {
        LockPatternUtils lockPatternUtils = new LockPatternUtils(mContext);
        return lockPatternUtils.getCredentialTypeForUser(uid)
                != LockPatternUtils.CREDENTIAL_TYPE_NONE;
    }

    private boolean hasTrustedDevice(int uid) {
        if (mEnrollmentManager == null) { // car service not ready, so it cannot be available.
            return false;
        }
        return !mEnrollmentManager.getEnrolledDeviceInfoForUser(uid).isEmpty();
    }

    private OnHideListener mOnHideListener = new OnHideListener() {
        @Override
        public void onHide(boolean dismissUserSwitcher) {
            if (dismissUserSwitcher) {
                dismissUserSwitcher();
            } else {
                // Re-draw the parent view, otherwise the unlock dialog will not be removed from
                // the screen immediately.
                mParent.invalidate();
            }

        }
    };
}
