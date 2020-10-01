/*
 * Copyright (C) 2020 Muntashir Al-Islam
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.muntashirakon.AppManager.crypto;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;

import org.openintents.openpgp.IOpenPgpService2;
import org.openintents.openpgp.OpenPgpError;
import org.openintents.openpgp.util.OpenPgpApi;
import org.openintents.openpgp.util.OpenPgpServiceConnection;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import io.github.muntashirakon.AppManager.AppManager;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.utils.AppPref;
import io.github.muntashirakon.AppManager.utils.IOUtils;

public class OpenPGPCrypto implements Crypto {
    public static final String TAG = "OpenPGPCrypto";

    public static final String GPG_EXT = ".gpg";

    public static final int OPEN_PGP_REQUEST_ENCRYPT = 3;
    public static final int OPEN_PGP_REQUEST_DECRYPT = 4;

    private OpenPgpServiceConnection service;
    private boolean successFlag, errorFlag;
    private File[] files;
    @NonNull
    private List<File> newFiles = new ArrayList<>();
    @NonNull
    private long[] keyIds;
    private final String provider;
    private final Context context;

    public OpenPGPCrypto() {
        context = AppManager.getContext();
        String keyIdsStr = (String) AppPref.get(AppPref.PrefKey.PREF_OPEN_PGP_USER_ID_STR);
        String[] keyIds = keyIdsStr.split(",");
        this.keyIds = new long[keyIds.length];
        for (int i = 0; i < keyIds.length; ++i) this.keyIds[i] = Long.parseLong(keyIds[i]);
        this.provider = (String) AppPref.get(AppPref.PrefKey.PREF_OPEN_PGP_PACKAGE_STR);
        bind();
    }

    @Override
    public void close() {
        // Unbind service
        if (service != null) service.unbindFromService();
    }

    @WorkerThread
    @Override
    public boolean decrypt(@NonNull File[] files) {
        Intent intent = new Intent(OpenPgpApi.ACTION_DECRYPT_VERIFY);
        return handleFiles(context, intent, OPEN_PGP_REQUEST_DECRYPT, files);
    }

    @WorkerThread
    @Override
    public boolean encrypt(@NonNull File[] filesList) {
        Intent intent = new Intent(OpenPgpApi.ACTION_ENCRYPT);
        intent.putExtra(OpenPgpApi.EXTRA_KEY_IDS, keyIds);
        return handleFiles(context, intent, OPEN_PGP_REQUEST_ENCRYPT, filesList);
    }

    private boolean handleFiles(Context context, Intent intent, int requestCode, @NonNull File[] filesList) {
        if (!waitForServiceBound()) return false;
        files = filesList;
        newFiles.clear();
        return doAction(context, intent, requestCode);
    }

    private boolean doAction(Context context, Intent intent, int requestCode) {
        errorFlag = false;
        if (files.length > 0) {  // files is never null here
            for (File file : files) {
                File outputFilename;
                if (requestCode == OPEN_PGP_REQUEST_DECRYPT) {
                    outputFilename = new File(file.getAbsolutePath().substring(0, file.getAbsolutePath().lastIndexOf(GPG_EXT)));
                } else outputFilename = new File(file.getAbsolutePath() + GPG_EXT);
                newFiles.add(outputFilename);
                Log.i(TAG, "Input: " + file + "\nOutput: " + outputFilename);
                try (FileInputStream is = new FileInputStream(file);
                     FileOutputStream os = new FileOutputStream(outputFilename)) {
                    OpenPgpApi api = new OpenPgpApi(context, service.getService());
                    Intent result = api.executeApi(intent, is, os);
                    handleResult(context, result);
                    waitForResult();
                    if (errorFlag) {
                        os.close();
                        IOUtils.deleteSilently(outputFilename);
                        return false;
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error: " + e.toString(), e);
                    return false;
                }
                // Delete unencrypted file
                if (requestCode == OPEN_PGP_REQUEST_ENCRYPT) {
                    if (!file.delete()) {
                        Log.e(TAG, "Couldn't delete old file " + file);
                        return false;
                    }
                }
            }
            // Total success
        } else {
            Log.d(TAG, "No files to de/encrypt");
        }
        return true;
    }

    @NonNull
    @Override
    public File[] getNewFiles() {
        return newFiles.toArray(new File[0]);
    }

    private void bind() {
        service = new OpenPgpServiceConnection(context, provider,
                new OpenPgpServiceConnection.OnBound() {
                    @Override
                    public void onBound(IOpenPgpService2 service) {
                        Log.i(OpenPgpApi.TAG, "Service bound.");
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.e(OpenPgpApi.TAG, "Exception on binding.", e);
                    }
                }
        );
        service.bindToService();
    }

    private boolean waitForServiceBound() {
        int i = 0;
        while (service.getService() == null) {
            try {
                if (i % 20 == 0)
                    Log.i(TAG, "Waiting for openpgp-api service to be bound");
                Thread.sleep(100);
                if (i > 1000)
                    break;
                i++;
            } catch (InterruptedException e) {
                Log.e(TAG, "WaitForServiceBound: interrupted", e);
                Thread.currentThread().interrupt();
            }
        }
        return service.getService() != null;
    }

    private void waitForResult() {
        try {
            int i = 0;
            while (!successFlag && !errorFlag) {
                if (i % 200 == 0) Log.i(TAG, "Waiting for user interaction");
                Thread.sleep(100);
                if (i > 1000)
                    break;
                i++;
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "waitForResult: interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

    private void handleResult(Context context, @NonNull Intent result) {
        successFlag = false;
        switch (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
            case OpenPgpApi.RESULT_CODE_SUCCESS:
                Log.i(TAG, "en/decryption successful.");
                successFlag = true;
                break;
            case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED:
                Log.i(TAG, "User interaction required. Sending intent...");
                PendingIntent pi = result.getParcelableExtra(OpenPgpApi.RESULT_INTENT);
                if (pi == null) {
                    errorFlag = true;
                    return;
                }
                try {
                    context.startIntentSender(pi.getIntentSender(), null, 0, 0, 0);
                } catch (IntentSender.SendIntentException e2) {
                    Log.e(TAG, "handleResult: error " + e2.toString(), e2);
                }
                break;
            case OpenPgpApi.RESULT_CODE_ERROR:
                errorFlag = true;
                OpenPgpError error = result.getParcelableExtra(OpenPgpApi.RESULT_ERROR);
                if (error != null) {
                    Log.e(TAG, "handleResult: (" + error.getErrorId() + ") " + error.getMessage());
                } else Log.e(TAG, "handleResult: Error occurred during en/decryption process");
                break;
        }
    }
}
