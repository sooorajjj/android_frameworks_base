/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.hardware.usb;

import android.hardware.usb.UsbAccessory;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/** @hide */
interface IUsbManager
{
    /* Returns the currently attached USB accessory */
    UsbAccessory getCurrentAccessory();

    /* Returns a file descriptor for communicating with the USB accessory.
     * This file descriptor can be used with standard Java file operations.
     */
    ParcelFileDescriptor openAccessory(in UsbAccessory accessory);

    /* Sets the default package for a USB accessory
     * (or clears it if the package name is null)
     */
    void setAccessoryPackage(in UsbAccessory accessory, String packageName);

    /* Grants permission for the given UID to access the accessory */
    void grantAccessoryPermission(in UsbAccessory accessory, int uid);

    /* Returns true if the USB manager has default preferences or permissions for the package */
    boolean hasDefaults(String packageName, int uid);

    /* Clears default preferences and permissions for the package */
    oneway void clearDefaults(String packageName, int uid);
}
