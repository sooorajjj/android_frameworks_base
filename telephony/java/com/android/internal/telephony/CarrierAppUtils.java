/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2017-2020 Fairphone B.V.
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

package com.android.internal.telephony;

import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.RemoteException;
import android.os.UserManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Utilities for handling carrier applications.
 * @hide
 */
public final class CarrierAppUtils {
    private static final String TAG = "CarrierAppUtils";

    private static final boolean DEBUG = false; // STOPSHIP if true

    private static final String SPECIAL_CARRIER_APP_INTENT_SERVICE_CLASS = "com.android.phone.SpecialCarrierAppIntentService";
    private static final String SPECIAL_CARRIER_APP_ACTION_HANDLE_MATCH = "com.android.phone.SpecialCarrierApp.handle_match";
    private static final String SPECIAL_CARRIER_APP_EXTRA_APP_INFO = "com.android.phone.SpecialCarrierApp.app_info";
    private static final String SPECIAL_CARRIER_APP_EXTRA_DEVICE_PROVISIONED = "com.android.phone.SpecialCarrierApp.device_provisioned";

    private CarrierAppUtils() {}

    /**
     * Handle preinstalled carrier apps which should be disabled until a matching SIM is inserted.
     *
     * Evaluates the list of applications in
     * {@link SystemConfig#getDisabledUntilUsedPreinstalledCarrierApps()}. We want to disable each
     * such application which is present on the system image until the user inserts a SIM which
     * causes that application to gain carrier privilege (indicating a "match"), without interfering
     * with the user if they opt to enable/disable the app explicitly.
     *
     * So, for each such app, we either disable until used IFF the app is not carrier privileged AND
     * in the default state (e.g. not explicitly DISABLED/DISABLED_BY_USER/ENABLED), or we enable if
     * the app is carrier privileged and in either the default state or DISABLED_UNTIL_USED.
     *
     * In addition, there is a list of carrier-associated applications in
     * {@link SystemConfig#getDisabledUntilUsedPreinstalledCarrierAssociatedApps}. Each app in this
     * list is associated with a carrier app. When the given carrier app is enabled/disabled per the
     * above, the associated applications are enabled/disabled to match.
     *
     * When enabling a carrier app we also grant it default permissions.
     *
     * This method is idempotent and is safe to be called at any time; it should be called once at
     * system startup prior to any application running, as well as any time the set of carrier
     * privileged apps may have changed.
     */
    public synchronized static void disableCarrierAppsUntilPrivileged(String callingPackage,
            IPackageManager packageManager, TelephonyManager telephonyManager,
            ContentResolver contentResolver, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "disableCarrierAppsUntilPrivileged");
        }
        SystemConfig config = SystemConfig.getInstance();
        ArraySet<String> systemCarrierAppsDisabledUntilUsed =
                config.getDisabledUntilUsedPreinstalledCarrierApps();
        ArrayMap<String, List<String>> systemCarrierAssociatedAppsDisabledUntilUsed =
                config.getDisabledUntilUsedPreinstalledCarrierAssociatedApps();
        disableCarrierAppsUntilPrivileged(callingPackage, packageManager, telephonyManager,
                contentResolver, userId, systemCarrierAppsDisabledUntilUsed,
                systemCarrierAssociatedAppsDisabledUntilUsed);
    }

    /**
     * Like {@link #disableCarrierAppsUntilPrivileged(String, IPackageManager, TelephonyManager,
     * ContentResolver, int)}, but assumes that no carrier apps have carrier privileges.
     *
     * This prevents a potential race condition on first boot - since the app's default state is
     * enabled, we will initially disable it when the telephony stack is first initialized as it has
     * not yet read the carrier privilege rules. However, since telephony is initialized later on
     * late in boot, the app being disabled may have already been started in response to certain
     * broadcasts. The app will continue to run (briefly) after being disabled, before the Package
     * Manager can kill it, and this can lead to crashes as the app is in an unexpected state.
     */
    public synchronized static void disableCarrierAppsUntilPrivileged(String callingPackage,
            IPackageManager packageManager, ContentResolver contentResolver, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "disableCarrierAppsUntilPrivileged");
        }
        SystemConfig config = SystemConfig.getInstance();
        ArraySet<String> systemCarrierAppsDisabledUntilUsed =
                config.getDisabledUntilUsedPreinstalledCarrierApps();


        ArrayMap<String, List<String>> systemCarrierAssociatedAppsDisabledUntilUsed =
                config.getDisabledUntilUsedPreinstalledCarrierAssociatedApps();
        disableCarrierAppsUntilPrivileged(callingPackage, packageManager,
                null /* telephonyManager */, contentResolver, userId,
                systemCarrierAppsDisabledUntilUsed, systemCarrierAssociatedAppsDisabledUntilUsed);
    }

    // Must be public b/c framework unit tests can't access package-private methods.
    @VisibleForTesting
    public static void disableCarrierAppsUntilPrivileged(String callingPackage,
            IPackageManager packageManager, @Nullable TelephonyManager telephonyManager,
            ContentResolver contentResolver, int userId,
            ArraySet<String> systemCarrierAppsDisabledUntilUsed,
            ArrayMap<String, List<String>> systemCarrierAssociatedAppsDisabledUntilUsed) {
        List<ApplicationInfo> candidates = getDefaultCarrierAppCandidatesHelper(packageManager,
                userId, systemCarrierAppsDisabledUntilUsed);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        Map<String, List<ApplicationInfo>> associatedApps = getDefaultCarrierAssociatedAppsHelper(
                packageManager,
                userId,
                systemCarrierAssociatedAppsDisabledUntilUsed);

        List<String> enabledCarrierPackages = new ArrayList<>();

        boolean hasRunOnce = Settings.Secure.getIntForUser(
                contentResolver, Settings.Secure.CARRIER_APPS_HANDLED, 0, userId) == 1;

        try {
            for (ApplicationInfo ai : candidates) {
                String packageName = ai.packageName;
                boolean hasPrivileges = telephonyManager != null &&
                        telephonyManager.checkCarrierPrivilegesForPackageAnyPhone(packageName) ==
                                TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS;

                if (hasPrivileges) {
                    // Only update enabled state for the app on /system. Once it has been
                    // updated we shouldn't touch it.
                    if (!ai.isUpdatedSystemApp()
                            && (ai.enabledSetting ==
                            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                            || ai.enabledSetting ==
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)) {
                        Slog.i(TAG, "Update state(" + packageName + "): ENABLED for user "
                                + userId);
                        packageManager.setApplicationEnabledSetting(
                                packageName,
                                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                                PackageManager.DONT_KILL_APP,
                                userId,
                                callingPackage);
                    }

                    // Also enable any associated apps for this carrier app.
                    List<ApplicationInfo> associatedAppList = associatedApps.get(packageName);
                    if (associatedAppList != null) {
                        for (ApplicationInfo associatedApp : associatedAppList) {
                            if (associatedApp.enabledSetting ==
                                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                                    || associatedApp.enabledSetting ==
                                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                                Slog.i(TAG, "Update associated state(" + associatedApp.packageName
                                        + "): ENABLED for user " + userId);
                                packageManager.setApplicationEnabledSetting(
                                        associatedApp.packageName,
                                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                                        PackageManager.DONT_KILL_APP,
                                        userId,
                                        callingPackage);
                            }
                        }
                    }

                    // Always re-grant default permissions to carrier apps w/ privileges.
                    enabledCarrierPackages.add(ai.packageName);
                } else {  // No carrier privileges
                    // Only update enabled state for the app on /system. Once it has been
                    // updated we shouldn't touch it.
                    if (!ai.isUpdatedSystemApp()
                            && ai.enabledSetting ==
                            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                        Slog.i(TAG, "Update state(" + packageName
                                + "): DISABLED_UNTIL_USED for user " + userId);
                        packageManager.setApplicationEnabledSetting(
                                packageName,
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
                                0,
                                userId,
                                callingPackage);
                    }

                    // Also disable any associated apps for this carrier app if this is the first
                    // run. We avoid doing this a second time because it is brittle to rely on the
                    // distinction between "default" and "enabled".
                    if (!hasRunOnce) {
                        List<ApplicationInfo> associatedAppList = associatedApps.get(packageName);
                        if (associatedAppList != null) {
                            for (ApplicationInfo associatedApp : associatedAppList) {
                                if (associatedApp.enabledSetting
                                        == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                                    Slog.i(TAG,
                                            "Update associated state(" + associatedApp.packageName
                                                    + "): DISABLED_UNTIL_USED for user " + userId);
                                    packageManager.setApplicationEnabledSetting(
                                            associatedApp.packageName,
                                            PackageManager
                                                    .COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
                                            0,
                                            userId,
                                            callingPackage);
                                }
                            }
                        }
                    }
                }
            }

            // Mark the execution so we do not disable apps again.
            if (!hasRunOnce) {
                Settings.Secure.putIntForUser(
                        contentResolver, Settings.Secure.CARRIER_APPS_HANDLED, 1, userId);
            }

            if (!enabledCarrierPackages.isEmpty()) {
                // Since we enabled at least one app, ensure we grant default permissions to those
                // apps.
                String[] packageNames = new String[enabledCarrierPackages.size()];
                enabledCarrierPackages.toArray(packageNames);
                packageManager.grantDefaultPermissionsToEnabledCarrierApps(packageNames, userId);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not reach PackageManager", e);
        }
    }

    /**
     * Handle special carrier apps which should be disabled until a matching UICC is detected.
     *
     * Evaluates the list of applications in config_disabledUntilUsedSpecialCarrierApps. We
     * want to disable each such application which is present on the system image until an UICC
     * exposing the right operator code is found (indicating a "match"), without interfering with
     * the user if they opt to enable/disable the app explicitly.
     *
     * So, for each such app, we either disable until used if -and only if- the app is not matching
     * a carrier AND is in the default state (e.g. not explicitly enabled or disabled by the user),
     * or we ask the adequate service (SpecialCarrierAppIntentService living in the Telephony
     * service) to handle our request if the app is matching a carrier and in either the default
     * state (first boot) or flagged as DISABLED_UNTIL_USED.
     *
     * This method differs from
     * {@link #disableCarrierAppsUntilPrivileged(String, IPackageManager, TelephonyManager, int)} in
     * two ways:
     * - The apps are enabled based on the UICC operator code and the package signature; and
     * - When enabling a carrier app, no permissions are implicitly granted.
     * If the configuration specifies so (per app), an intents is broadcasted after a carrier app
     * has been enabled.
     *
     * This method is idempotent and is safe to be called at any time; it should be called once at
     * system startup prior to any application running, as well as any time the set of carrier
     * privileged apps may have changed.
     */
    public synchronized static void disableSpecialCarrierAppsUntilMatched(Context context,
            IPackageManager packageManager, TelephonyManager telephonyManager, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "disableSpecialCarrierAppsUntilMatched");
        }

        if (!((UserManager) context.getSystemService(Context.USER_SERVICE)).isSystemUser()) {
            Slog.d(TAG, "Current user is not the owner, special carrier apps matching ignored.");
            return;
        }

        String[] specialSystemCarrierAppsDisabledUntilUsed = Resources.getSystem().getStringArray(
                com.android.internal.R.array.config_disabledUntilUsedSpecialCarrierApps);
        List<ApplicationInfo> candidates = getDefaultCarrierAppCandidatesHelper(packageManager,
                userId, new ArraySet<>(Arrays.asList(specialSystemCarrierAppsDisabledUntilUsed)));

        Slog.d(TAG, "Found " + (candidates == null ? 0 : candidates.size()) + " candidate(s) for "
                + specialSystemCarrierAppsDisabledUntilUsed.length + " special carrier app(s)");

        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        String[] simOperatorCodes = new String[telephonyManager.getPhoneCount()];
        for (int i = 0; i < simOperatorCodes.length; i++) {
            simOperatorCodes[i] = telephonyManager.getSimOperatorNumericForPhone(i);
        }

        String[] specialSystemCarrierAppOperatorCodes = Resources.getSystem().getStringArray(
                com.android.internal.R.array.config_disabledUntilUsedSpecialCarrierAppUiccOperators);
        String[] specialSystemCarrierAppIntents = Resources.getSystem().getStringArray(
                com.android.internal.R.array.config_disabledUntilUsedSpecialCarrierAppIntents);

        try {
            for (ApplicationInfo ai : candidates) {
                String operatorCodesToMatch = null, actionIntentToBroadcast = null;

                // Only update enabled state for the app on /system. Once it has been updated we
                // shouldn't touch it.
                if (ai.isUpdatedSystemApp()) {
                    if (DEBUG) {
                        Slog.d(TAG, "Special carrier app candidate '" + ai.packageName
                                + "' is an updated system app, ignoring");
                    }
                    continue;
                }

                // Not so nice look-up for every package name.
                // The impact is mitigated by the low amount of items (a handful) expected.
                for (int i = 0; i < specialSystemCarrierAppsDisabledUntilUsed.length; i++) {
                    if (ai.packageName.equals(specialSystemCarrierAppsDisabledUntilUsed[i])) {
                        operatorCodesToMatch = specialSystemCarrierAppOperatorCodes[i];
                        actionIntentToBroadcast = specialSystemCarrierAppIntents[i];
                        break;
                    }
                }

                // Ignore this candidate if there is no valid operator codes
                if (TextUtils.isEmpty(operatorCodesToMatch)) {
                    Slog.e(TAG, "No valid operator codes found in the configuration for candidate "
                            + ai.packageName);
                    continue;
                } else if (DEBUG) {
                    Slog.d(TAG, "Special carrier app candidate (" + ai.packageName
                            + ") wants to match against " + operatorCodesToMatch);
                }

                SpecialCarrierAppInfo appInfo = new SpecialCarrierAppInfo(ai.packageName,
                        operatorCodesToMatch, actionIntentToBroadcast);

                for (int uiccSlot = 0; uiccSlot < simOperatorCodes.length; uiccSlot++) {
                    if (appInfo.matchesAgainstOperator(simOperatorCodes[uiccSlot])) {
                        appInfo.mUiccSlotId = uiccSlot;
                        appInfo.mCarrierName = TelephonyManager.getDefault().getSimOperatorNameForPhone(uiccSlot);
                        break;
                    }
                }

                if (appInfo.hasMatched()
                        && (ai.enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                        || ai.enabledSetting ==
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)) {
                    if (DEBUG) Slog.d(TAG, "Special carrier app candidate (" + ai.packageName
                            + ") found a match with UICC " + appInfo.mUiccSlotId);

                    boolean isDeviceProvisioned =
                            Settings.Global.getInt(context.getContentResolver(), Settings.Global.DEVICE_PROVISIONED) == 1;

                    /*
                     * Defer to the Telephony service to handle the app match.
                     */
                    context.startService(new Intent()
                            .setClassName(context, SPECIAL_CARRIER_APP_INTENT_SERVICE_CLASS)
                            .setAction(SPECIAL_CARRIER_APP_ACTION_HANDLE_MATCH)
                            .putExtra(SPECIAL_CARRIER_APP_EXTRA_APP_INFO, appInfo)
                            .putExtra(SPECIAL_CARRIER_APP_EXTRA_DEVICE_PROVISIONED, isDeviceProvisioned));
                } else if (!appInfo.hasMatched()
                        && ai.enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                    Slog.i(TAG, "Update state(" + ai.packageName
                            + "): DISABLED_UNTIL_USED for user " + userId);
                    packageManager.setApplicationEnabledSetting(ai.packageName,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED, 0,
                            userId, context.getOpPackageName());
                }
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Could not reach PackageManager", e);
        } catch (Settings.SettingNotFoundException e) {
            Slog.e(TAG, "Could not read the device provision", e);
        }
    }

    /**
     * Returns the list of "default" carrier apps.
     *
     * This is the subset of apps returned by
     * {@link #getDefaultCarrierAppCandidates(IPackageManager, int)} which currently have carrier
     * privileges per the SIM(s) inserted in the device.
     */
    public static List<ApplicationInfo> getDefaultCarrierApps(IPackageManager packageManager,
            TelephonyManager telephonyManager, int userId) {
        // Get all system apps from the default list.
        List<ApplicationInfo> candidates = getDefaultCarrierAppCandidates(packageManager, userId);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        // Filter out apps without carrier privileges.
        // Iterate from the end to avoid creating an Iterator object and because we will be removing
        // elements from the list as we pass through it.
        for (int i = candidates.size() - 1; i >= 0; i--) {
            ApplicationInfo ai = candidates.get(i);
            String packageName = ai.packageName;
            boolean hasPrivileges =
                    telephonyManager.checkCarrierPrivilegesForPackageAnyPhone(packageName) ==
                            TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS;
            if (!hasPrivileges) {
                candidates.remove(i);
            }
        }

        return candidates;
    }

    /**
     * Returns the list of "default" carrier app candidates.
     *
     * These are the apps subject to the hiding/showing logic in
     * {@link CarrierAppUtils#disableCarrierAppsUntilPrivileged(String, IPackageManager,
     * TelephonyManager, ContentResolver, int)}, as well as the apps which should have default
     * permissions granted, when a matching SIM is inserted.
     *
     * Whether or not the app is actually considered a default app depends on whether the app has
     * carrier privileges as determined by the SIMs in the device.
     */
    public static List<ApplicationInfo> getDefaultCarrierAppCandidates(
            IPackageManager packageManager, int userId) {
        ArraySet<String> systemCarrierAppsDisabledUntilUsed =
                SystemConfig.getInstance().getDisabledUntilUsedPreinstalledCarrierApps();
        return getDefaultCarrierAppCandidatesHelper(packageManager, userId,
                systemCarrierAppsDisabledUntilUsed);
    }

    private static List<ApplicationInfo> getDefaultCarrierAppCandidatesHelper(
            IPackageManager packageManager,
            int userId,
            ArraySet<String> systemCarrierAppsDisabledUntilUsed) {
        if (systemCarrierAppsDisabledUntilUsed == null) {
            return null;
        }

        int size = systemCarrierAppsDisabledUntilUsed.size();
        if (size == 0) {
            return null;
        }

        List<ApplicationInfo> apps = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String packageName = systemCarrierAppsDisabledUntilUsed.valueAt(i);
            ApplicationInfo ai =
                    getApplicationInfoIfSystemApp(packageManager, userId, packageName);
            if (ai != null) {
                apps.add(ai);
            }
        }
        return apps;
    }

    private static Map<String, List<ApplicationInfo>> getDefaultCarrierAssociatedAppsHelper(
            IPackageManager packageManager,
            int userId,
            ArrayMap<String, List<String>> systemCarrierAssociatedAppsDisabledUntilUsed) {
        int size = systemCarrierAssociatedAppsDisabledUntilUsed.size();
        Map<String, List<ApplicationInfo>> associatedApps = new ArrayMap<>(size);
        for (int i = 0; i < size; i++) {
            String carrierAppPackage = systemCarrierAssociatedAppsDisabledUntilUsed.keyAt(i);
            List<String> associatedAppPackages =
                    systemCarrierAssociatedAppsDisabledUntilUsed.valueAt(i);
            for (int j = 0; j < associatedAppPackages.size(); j++) {
                ApplicationInfo ai =
                        getApplicationInfoIfSystemApp(
                                packageManager, userId, associatedAppPackages.get(j));
                // Only update enabled state for the app on /system. Once it has been updated we
                // shouldn't touch it.
                if (ai != null && !ai.isUpdatedSystemApp()) {
                    List<ApplicationInfo> appList = associatedApps.get(carrierAppPackage);
                    if (appList == null) {
                        appList = new ArrayList<>();
                        associatedApps.put(carrierAppPackage, appList);
                    }
                    appList.add(ai);
                }
            }
        }
        return associatedApps;
    }

    @Nullable
    private static ApplicationInfo getApplicationInfoIfSystemApp(
            IPackageManager packageManager,
            int userId,
            String packageName) {
        try {
            ApplicationInfo ai = packageManager.getApplicationInfo(packageName,
                    PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS, userId);
            if (ai != null && ai.isSystemApp()) {
                return ai;
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not reach PackageManager", e);
        }
        return null;
    }
}
