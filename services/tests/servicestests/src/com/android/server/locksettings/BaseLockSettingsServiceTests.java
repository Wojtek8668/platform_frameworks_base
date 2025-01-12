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

package com.android.server.locksettings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.trust.TrustManager;
import android.content.ComponentName;
import android.content.pm.UserInfo;
import android.hardware.authsecret.V1_0.IAuthSecret;
import android.os.FileUtils;
import android.os.IProgressListener;
import android.os.RemoteException;
import android.os.UserManager;
import android.os.storage.IStorageManager;
import android.os.storage.StorageManager;
import android.security.KeyStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.ILockSettings;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockSettingsInternal;
import com.android.server.LocalServices;
import com.android.server.locksettings.recoverablekeystore.RecoverableKeyStoreManager;
import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public abstract class BaseLockSettingsServiceTests {
    protected static final int PRIMARY_USER_ID = 0;
    protected static final int MANAGED_PROFILE_USER_ID = 12;
    protected static final int TURNED_OFF_PROFILE_USER_ID = 17;
    protected static final int SECONDARY_USER_ID = 20;

    private static final UserInfo PRIMARY_USER_INFO = new UserInfo(PRIMARY_USER_ID, null, null,
            UserInfo.FLAG_INITIALIZED | UserInfo.FLAG_ADMIN | UserInfo.FLAG_PRIMARY);
    private static final UserInfo SECONDARY_USER_INFO = new UserInfo(SECONDARY_USER_ID, null, null,
            UserInfo.FLAG_INITIALIZED);

    private ArrayList<UserInfo> mPrimaryUserProfiles = new ArrayList<>();

    LockSettingsService mService;
    LockSettingsInternal mLocalService;

    MockLockSettingsContext mContext;
    LockSettingsStorageTestable mStorage;

    LockPatternUtils mLockPatternUtils;
    FakeGateKeeperService mGateKeeperService;
    NotificationManager mNotificationManager;
    UserManager mUserManager;
    FakeStorageManager mStorageManager;
    IActivityManager mActivityManager;
    DevicePolicyManager mDevicePolicyManager;
    DevicePolicyManagerInternal mDevicePolicyManagerInternal;
    KeyStore mKeyStore;
    MockSyntheticPasswordManager mSpManager;
    IAuthSecret mAuthSecretService;
    WindowManagerInternal mMockWindowManager;
    FakeGsiService mGsiService;
    PasswordSlotManagerTestable mPasswordSlotManager;
    RecoverableKeyStoreManager mRecoverableKeyStoreManager;
    protected boolean mHasSecureLockScreen;

    @Before
    public void setUp_baseServices() throws Exception {
        mGateKeeperService = new FakeGateKeeperService();
        mNotificationManager = mock(NotificationManager.class);
        mUserManager = mock(UserManager.class);
        mStorageManager = new FakeStorageManager();
        mActivityManager = mock(IActivityManager.class);
        mDevicePolicyManager = mock(DevicePolicyManager.class);
        mDevicePolicyManagerInternal = mock(DevicePolicyManagerInternal.class);
        mMockWindowManager = mock(WindowManagerInternal.class);
        mGsiService = new FakeGsiService();
        mPasswordSlotManager = new PasswordSlotManagerTestable();
        mRecoverableKeyStoreManager = mock(RecoverableKeyStoreManager.class);

        LocalServices.removeServiceForTest(LockSettingsInternal.class);
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.addService(DevicePolicyManagerInternal.class, mDevicePolicyManagerInternal);
        LocalServices.addService(WindowManagerInternal.class, mMockWindowManager);

        mContext = new MockLockSettingsContext(InstrumentationRegistry.getContext(), mUserManager,
                mNotificationManager, mDevicePolicyManager, mock(StorageManager.class),
                mock(TrustManager.class), mock(KeyguardManager.class));
        mStorage = new LockSettingsStorageTestable(mContext,
                new File(InstrumentationRegistry.getContext().getFilesDir(), "locksettings"));
        File storageDir = mStorage.mStorageDir;
        if (storageDir.exists()) {
            FileUtils.deleteContents(storageDir);
        } else {
            storageDir.mkdirs();
        }

        mHasSecureLockScreen = true;
        mLockPatternUtils = new LockPatternUtils(mContext) {
            @Override
            public ILockSettings getLockSettings() {
                return mService;
            }

            @Override
            public boolean hasSecureLockScreen() {
                return mHasSecureLockScreen;
            }
        };
        mSpManager = new MockSyntheticPasswordManager(mContext, mStorage, mGateKeeperService,
                mUserManager, mPasswordSlotManager);
        mAuthSecretService = mock(IAuthSecret.class);
        mService = new LockSettingsServiceTestable(mContext, mLockPatternUtils, mStorage,
                mGateKeeperService, mKeyStore, setUpStorageManagerMock(), mActivityManager,
                mSpManager, mAuthSecretService, mGsiService, mRecoverableKeyStoreManager);
        when(mUserManager.getUserInfo(eq(PRIMARY_USER_ID))).thenReturn(PRIMARY_USER_INFO);
        mPrimaryUserProfiles.add(PRIMARY_USER_INFO);
        installChildProfile(MANAGED_PROFILE_USER_ID);
        installAndTurnOffChildProfile(TURNED_OFF_PROFILE_USER_ID);
        for (UserInfo profile : mPrimaryUserProfiles) {
            when(mUserManager.getProfiles(eq(profile.id))).thenReturn(mPrimaryUserProfiles);
        }
        when(mUserManager.getUserInfo(eq(SECONDARY_USER_ID))).thenReturn(SECONDARY_USER_INFO);

        final ArrayList<UserInfo> allUsers = new ArrayList<>(mPrimaryUserProfiles);
        allUsers.add(SECONDARY_USER_INFO);
        when(mUserManager.getUsers(anyBoolean())).thenReturn(allUsers);

        when(mActivityManager.unlockUser(anyInt(), any(), any(), any())).thenAnswer(
                new Answer<Boolean>() {
            @Override
            public Boolean answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                mStorageManager.unlockUser((int)args[0], (byte[])args[2],
                        (IProgressListener) args[3]);
                return true;
            }
        });

        // Adding a fake Device Owner app which will enable escrow token support in LSS.
        when(mDevicePolicyManager.getDeviceOwnerComponentOnAnyUser()).thenReturn(
                new ComponentName("com.dummy.package", ".FakeDeviceOwner"));
        mLocalService = LocalServices.getService(LockSettingsInternal.class);
    }

    private UserInfo installChildProfile(int profileId) {
        final UserInfo userInfo = new UserInfo(
            profileId, null, null, UserInfo.FLAG_INITIALIZED | UserInfo.FLAG_MANAGED_PROFILE);
        userInfo.profileGroupId = PRIMARY_USER_ID;
        mPrimaryUserProfiles.add(userInfo);
        when(mUserManager.getUserInfo(eq(profileId))).thenReturn(userInfo);
        when(mUserManager.getProfileParent(eq(profileId))).thenReturn(PRIMARY_USER_INFO);
        when(mUserManager.isUserRunning(eq(profileId))).thenReturn(true);
        when(mUserManager.isUserUnlocked(eq(profileId))).thenReturn(true);
        return userInfo;
    }

    private UserInfo installAndTurnOffChildProfile(int profileId) {
        final UserInfo userInfo = installChildProfile(profileId);
        userInfo.flags |= UserInfo.FLAG_QUIET_MODE;
        when(mUserManager.isUserRunning(eq(profileId))).thenReturn(false);
        when(mUserManager.isUserUnlocked(eq(profileId))).thenReturn(false);
        return userInfo;
    }

    private IStorageManager setUpStorageManagerMock() throws RemoteException {
        final IStorageManager sm = mock(IStorageManager.class);

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                mStorageManager.addUserKeyAuth((int) args[0] /* userId */,
                        (int) args[1] /* serialNumber */,
                        (byte[]) args[2] /* token */,
                        (byte[]) args[3] /* secret */);
                return null;
            }
        }).when(sm).addUserKeyAuth(anyInt(), anyInt(), any(), any());

        doAnswer(
                new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                mStorageManager.fixateNewestUserKeyAuth((int) args[0] /* userId */);
                return null;
            }
        }).when(sm).fixateNewestUserKeyAuth(anyInt());
        return sm;
    }

    @After
    public void tearDown_baseServices() throws Exception {
        mStorage.closeDatabase();
        File db = InstrumentationRegistry.getContext().getDatabasePath("locksettings.db");
        assertTrue(!db.exists() || db.delete());

        File storageDir = mStorage.mStorageDir;
        assertTrue(FileUtils.deleteContents(storageDir));

        mPasswordSlotManager.cleanup();
    }

    protected void flushHandlerTasks() {
        mService.mHandler.runWithScissors(() -> { }, 0 /*now*/); // Flush runnables on handler
    }

    protected void assertNotEquals(long expected, long actual) {
        assertTrue(expected != actual);
    }

    protected static void assertArrayEquals(byte[] expected, byte[] actual) {
        assertTrue(Arrays.equals(expected, actual));
    }

    protected static void assertArrayNotEquals(byte[] expected, byte[] actual) {
        assertFalse(Arrays.equals(expected, actual));
    }
}
