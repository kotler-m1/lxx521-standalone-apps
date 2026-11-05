/*
 * Copyright (C) 2014 The CyanogenMod Project
 * Copyright (C) 2023 The LineageOS Project
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

package com.android.dialer.callrecord.impl;

import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import com.android.dialer.R;
import com.android.dialer.callrecord.CallRecording;
import com.android.dialer.callrecord.ICallRecorderService;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;

public class CallRecorderService extends Service {
  private static final String TAG = "CallRecorderService";
  private static final boolean DBG = false;

  private MediaRecorder mMediaRecorder = null;
  private CallRecording mCurrentRecording = null;

  private final Handler mAmplitudeHandler = new Handler(Looper.getMainLooper());

  private final Runnable mAmplitudeLogger = new Runnable() {
    @Override
    public void run() {
      logRecordingAmplitude();
    }
  };

  private synchronized void logRecordingAmplitude() {
    MediaRecorder recorder = mMediaRecorder;
    if (recorder == null) {
      return;
    }
    try {
      int amplitude = recorder.getMaxAmplitude();
      Log.i(TAG, "InfinityRecorder amplitude=" + amplitude);
    } catch (IllegalStateException e) {
      Log.w(TAG, "InfinityRecorder amplitude read failed", e);
      return;
    }
    if (mMediaRecorder != null) {
      mAmplitudeHandler.postDelayed(mAmplitudeLogger, 1000);
    }
  }

  private final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyyMMdd-HHmmss");

  private final ICallRecorderService.Stub mBinder = new ICallRecorderService.Stub() {
    @Override
    public CallRecording stopRecording() {
      return stopRecordingInternal();
    }

    @Override
    public boolean startRecording(String phoneNumber, long creationTime) throws RemoteException {
      return startRecordingInternal(phoneNumber, creationTime);
    }

    @Override
    public boolean isRecording() throws RemoteException {
      return mMediaRecorder != null;
    }

    @Override
    public CallRecording getActiveRecording() throws RemoteException {
      return mCurrentRecording;
    }
  };

  @Override
  public void onCreate() {
    if (DBG) Log.d(TAG, "Creating CallRecorderService");
  }

  @Override
  public IBinder onBind(Intent intent) {
    return mBinder;
  }

  private int getAudioSource() {
    return getResources().getInteger(R.integer.call_recording_audio_source);
  }

  private boolean isInfinityCallRecordingAccessibilityServiceEnabled() {
    android.view.accessibility.AccessibilityManager manager =
        (android.view.accessibility.AccessibilityManager)
            getSystemService(ACCESSIBILITY_SERVICE);

    if (manager == null || !manager.isEnabled()) {
      return false;
    }

    for (android.accessibilityservice.AccessibilityServiceInfo info :
        manager.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
      android.content.pm.ResolveInfo resolveInfo = info.getResolveInfo();

      if (resolveInfo != null
          && resolveInfo.serviceInfo != null
          && getPackageName().equals(resolveInfo.serviceInfo.packageName)
          && InfinityCallRecordingAccessibilityService.class.getName()
              .equals(resolveInfo.serviceInfo.name)) {
        return true;
      }
    }

    return false;
  }

  private int getAudioFormatChoice() {
    // TEMPORARY TEST: force AAC/MP4 instead of AMR-WB.
    return 1;
  }

  private synchronized boolean startRecordingInternal(String phoneNumber, long creationTime) {
    if (mMediaRecorder != null) {
      if (DBG) {
        Log.d(TAG, "Start called with recording in progress, stopping  current recording");
      }
      stopRecordingInternal();
    }

    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "Record audio permission not granted, can't record call");
      return false;
    }

    if (!isInfinityCallRecordingAccessibilityServiceEnabled()) {
      Log.w(TAG, "Enable Infinity Call Recording in Android Accessibility settings before recording.");
      return false;
    }

    if (DBG) Log.d(TAG, "Starting recording");

    mMediaRecorder = new MediaRecorder(getApplicationContext());
    try {
      int audioSource = getAudioSource();
      int formatChoice = getAudioFormatChoice();
      if (DBG) Log.d(TAG, "Creating media recorder with audio source " + audioSource);
      mMediaRecorder.setAudioSource(audioSource);
      // InfinityRecorder explicit bottom microphone:
      // Prefer the bottom built-in microphone for speakerphone acoustics.
      if (android.os.Build.VERSION.SDK_INT >= 28) {
        android.media.AudioManager routeAudioManager =
            (android.media.AudioManager) getSystemService(
                android.content.Context.AUDIO_SERVICE);

        android.media.AudioDeviceInfo bottomMic = null;

        if (routeAudioManager != null) {
          for (android.media.AudioDeviceInfo input :
              routeAudioManager.getDevices(
                  android.media.AudioManager.GET_DEVICES_INPUTS)) {

            String address = input.getAddress();

            Log.i(TAG, "InfinityRecorder candidate input:"
                + " id=" + input.getId()
                + " type=" + input.getType()
                + " address=" + address
                + " source=" + input.isSource());

            if (input.isSource()
                && input.getType()
                    == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC
                && address != null
                && "bottom".equalsIgnoreCase(address)) {
              bottomMic = input;
              break;
            }
          }
        }

        if (bottomMic != null) {
          boolean accepted = mMediaRecorder.setPreferredDevice(bottomMic);

          Log.i(TAG, "InfinityRecorder explicit bottom microphone"
              + " accepted=" + accepted
              + " id=" + bottomMic.getId()
              + " address=" + bottomMic.getAddress());
        } else {
          Log.w(TAG, "InfinityRecorder bottom microphone not found");
        }
      }

      // Prefer the microphone facing the user during speakerphone capture.
      if (android.os.Build.VERSION.SDK_INT >= 29) {
        boolean directionAccepted =
            mMediaRecorder.setPreferredMicrophoneDirection(
                android.media.MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER);

        boolean fieldAccepted =
            mMediaRecorder.setPreferredMicrophoneFieldDimension(0.0f);

        Log.i(TAG, "InfinityRecorder microphone direction towards-user accepted="
            + directionAccepted + ", neutral field accepted=" + fieldAccepted);
      }

      android.media.AudioManager audioManager =
          (android.media.AudioManager) getSystemService(
              android.content.Context.AUDIO_SERVICE);

      if (audioManager != null) {
        for (android.media.AudioDeviceInfo input :
            audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)) {
          Log.i(TAG, "InfinityRecorder inputDevice"
              + " id=" + input.getId()
              + " type=" + input.getType()
              + " product=" + input.getProductName()
              + " address=" + input.getAddress());
        }
      }

      mMediaRecorder.setOutputFormat(formatChoice == 0
          ? MediaRecorder.OutputFormat.AMR_WB : MediaRecorder.OutputFormat.MPEG_4);
      mMediaRecorder.setAudioEncoder(formatChoice == 0
          ? MediaRecorder.AudioEncoder.AMR_WB : MediaRecorder.AudioEncoder.AAC);

      if (formatChoice == 1) {
        mMediaRecorder.setAudioChannels(1);
        mMediaRecorder.setAudioSamplingRate(44100);
        mMediaRecorder.setAudioEncodingBitRate(128000);

        Log.i(TAG, "InfinityRecorder TEST FORMAT: AAC, MP4, "
            + "44100 Hz, mono, 128 kbps");
      }
    } catch (IllegalStateException e) {
      Log.e(TAG, "Error initializing media recorder", e);
      releaseMediaRecorder();

      return false;
    }

    String fileName = generateFilename(phoneNumber);
    Uri uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            CallRecording.generateMediaInsertValues(fileName, creationTime));

    try {
      ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w");
      if (pfd == null) {
        throw new IOException("Opening file for URI " + uri + " failed");
      }
      mMediaRecorder.setOutputFile(pfd.getFileDescriptor());
      mMediaRecorder.prepare();
      mMediaRecorder.start();
      // Inspect the actual input route after recording starts.
      if (android.os.Build.VERSION.SDK_INT >= 28) {
        android.media.AudioDeviceInfo routed =
            mMediaRecorder.getRoutedDevice();

        if (routed != null) {
          Log.i(TAG, "InfinityRecorder actualRoute"
              + " id=" + routed.getId()
              + " type=" + routed.getType()
              + " product=" + routed.getProductName()
              + " address=" + routed.getAddress());
        } else {
          Log.w(TAG, "InfinityRecorder actualRoute is null");
        }

        try {
          for (android.media.MicrophoneInfo mic :
              mMediaRecorder.getActiveMicrophones()) {
            Log.i(TAG, "InfinityRecorder activeMicrophone"
                + " id=" + mic.getId()
                + " type=" + mic.getType()
                + " address=" + mic.getAddress()
                + " location=" + mic.getLocation()
                + " position=" + mic.getPosition()
                + " orientation=" + mic.getOrientation()
                + " channels=" + mic.getChannelMapping());
          }
        } catch (java.io.IOException e) {
          Log.w(TAG, "Could not inspect active microphones", e);
        }
      }


      mAmplitudeHandler.removeCallbacks(mAmplitudeLogger);
      mAmplitudeHandler.postDelayed(mAmplitudeLogger, 1000);

      long mediaId = Long.parseLong(uri.getLastPathSegment());
      mCurrentRecording = new CallRecording(phoneNumber, creationTime,
              fileName, System.currentTimeMillis(), mediaId);
      return true;
    } catch (IOException | IllegalStateException e) {
      Log.w(TAG, "Could not start recording", e);
      getContentResolver().delete(uri, null, null);
    } catch (RuntimeException e) {
      getContentResolver().delete(uri, null, null);
      // only catch exceptions thrown by the MediaRecorder JNI code
      if (e.getMessage().indexOf("start failed") >= 0) {
        Log.w(TAG, "Could not start recording", e);
      } else {
        throw e;
      }
    }

    releaseMediaRecorder();

    return false;
  }

  private synchronized CallRecording stopRecordingInternal() {
    CallRecording recording = mCurrentRecording;
    if (DBG) Log.d(TAG, "Stopping current recording");
    if (mMediaRecorder != null) {
      mAmplitudeHandler.removeCallbacks(mAmplitudeLogger);
      try {
        mMediaRecorder.stop();
      } catch (IllegalStateException e) {
        Log.e(TAG, "Exception closing media recorder", e);
      }

      releaseMediaRecorder();

      Uri uri = ContentUris.withAppendedId(
          MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mCurrentRecording.mediaId);
      getContentResolver().update(uri, CallRecording.generateCompletedValues(), null, null);

      mCurrentRecording = null;
    }
    return recording;
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    if (DBG) Log.d(TAG, "Destroying CallRecorderService");
  }

  private String generateFilename(String number) {
    String timestamp = DATE_FORMAT.format(new Date());

    if (TextUtils.isEmpty(number)) {
      number = "unknown";
    }

    int formatChoice = getAudioFormatChoice();
    String extension = formatChoice == 0 ? ".amr" : ".m4a";
    return "CallRecord_" + timestamp + "_" + number + extension;
  }

  public static boolean isEnabled(Context context) {
    return context.getResources().getBoolean(R.bool.call_recording_enabled);
  }

  private void releaseMediaRecorder() {
    Objects.requireNonNull(mMediaRecorder);
    try {
      mMediaRecorder.reset();
    } catch (Exception e) {
      Log.e(TAG, "unable to reset media recorder", e);
    }

    mMediaRecorder.release();

    mMediaRecorder = null;
  }
}
