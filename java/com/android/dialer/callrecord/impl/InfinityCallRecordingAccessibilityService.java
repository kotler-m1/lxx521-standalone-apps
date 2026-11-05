package com.android.dialer.callrecord.impl;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * User-enabled service for the optional call microphone-capture path.
 * It deliberately does not inspect screen contents or perform gestures.
 */
public final class InfinityCallRecordingAccessibilityService
    extends AccessibilityService {

  private static final String TAG = "InfinityCallAccess";

  @Override
  protected void onServiceConnected() {
    super.onServiceConnected();
    Log.i(TAG, "Infinity call recording accessibility service enabled");
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    // No screen text or window content is collected.
  }

  @Override
  public void onInterrupt() {
    Log.i(TAG, "Accessibility service interrupted");
  }
}
