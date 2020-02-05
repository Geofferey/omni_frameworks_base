/*
 * Copyright 2020 The Android Open Source Project
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
package android.app.blob;

import android.app.blob.IBlobCommitCallback;
import android.os.ParcelFileDescriptor;

/** {@hide} */
interface IBlobStoreSession {
    ParcelFileDescriptor openWrite(long offsetBytes, long lengthBytes);

    void allowPackageAccess(in String packageName, in byte[] certificate);
    void allowSameSignatureAccess();
    void allowPublicAccess();

    boolean isPackageAccessAllowed(in String packageName, in byte[] certificate);
    boolean isSameSignatureAccessAllowed();
    boolean isPublicAccessAllowed();

    long getSize();
    void close();
    void abandon();

    void commit(in IBlobCommitCallback callback);
}