/*
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

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.io.Serializable;

public class SpecialCarrierAppInfo implements Serializable {
    /**
     * The app package name.
     */
    @NonNull
    public String mPackageName;
    /**
     * The operator codes to match, separated by comma.
     */
    @NonNull
    public String mOperatorCodesToMatch;
    /**
     * The optional action intent to broadcast after enabling the app.
     */
    @Nullable
    public String mActionIntentToBroadcast;
    /**
     * The matched UICC slot id, or <code>-1</code> if not matched.
     */
    public int mUiccSlotId;
    /**
     * The carrier name as described by the matched UICC, or <code>null</code>.
     */
    @Nullable
    public String mCarrierName;

    public SpecialCarrierAppInfo(@NonNull String packageName, @NonNull String operatorCodesToMatch,
            String actionIntentToBroadcast) {
        this(packageName, operatorCodesToMatch, actionIntentToBroadcast, -1, null);
    }

    private SpecialCarrierAppInfo(@NonNull String packageName, @NonNull String operatorCodesToMatch,
            String actionIntentToBroadcast, int uiccSlotId, String carrierName) {
        mPackageName = packageName;
        mOperatorCodesToMatch = operatorCodesToMatch;
        mActionIntentToBroadcast = actionIntentToBroadcast;
        mUiccSlotId = uiccSlotId;
        mCarrierName = carrierName;
    }

    /**
     * @return All operator codes to match, or an empty array if none.
     */
    @NonNull
    public String[] getAllOperatorCodesToMatch() {
        return mOperatorCodesToMatch.split(",");
    }

    public boolean matchesAgainstOperator(@Nullable String operatorCode) {
        for (String operatorCodeToMatch : getAllOperatorCodesToMatch()) {
            if (operatorCodeToMatch.equals(operatorCode)) {
                return true;
            }
        }

        return false;
    }

    public boolean hasMatched() {
        return mUiccSlotId != -1;
    }
}
