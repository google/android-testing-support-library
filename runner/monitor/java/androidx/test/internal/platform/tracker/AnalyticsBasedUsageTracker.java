/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.test.internal.platform.tracker;

import static androidx.test.internal.util.Checks.checkNotNull;
import static java.net.URLEncoder.encode;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Creates a usage tracker that pings google analytics when infra bits get used. */
public final class AnalyticsBasedUsageTracker implements UsageTracker {
  private static final String TAG = "InfraTrack";

  private static final String UTF_8 = "UTF-8";
  private static final String APP_NAME_PARAM = "an=";
  private static final String SCREEN_NAME_PARAM = "&cd=";
  private static final String APP_VERSION_PARAM = "&av=";
  private static final String TRACKER_ID_PARAM = "&tid=";
  private static final String CLIENT_ID_PARAM = "&cid=";
  private static final String SCREEN_RESOLUTION_PARAM = "&sr=";
  private static final String API_LEVEL_PARAM = "&cd2=";
  private static final String MODEL_NAME_PARAM = "&cd3=";

  private final String trackingId;
  private final String targetPackage;
  private final URL analyticsURI;
  private final String screenResolution;
  private final String apiLevel;
  private final String model;
  private final String userId;

  private final Map<String, String> usageTypeToVersion = new HashMap<>();

  private AnalyticsBasedUsageTracker(Builder builder) {
    this.trackingId = checkNotNull(builder.trackingId);
    this.targetPackage = checkNotNull(builder.targetPackage);
    this.analyticsURI = checkNotNull(builder.analyticsURI);
    this.apiLevel = checkNotNull(builder.apiLevel);
    this.model = checkNotNull(builder.model);
    this.screenResolution = checkNotNull(builder.screenResolution);
    this.userId = checkNotNull(builder.userId);
  }

  /** Builder for AnalyticsBasedUsageTracker. */
  public static class Builder {
    private final Context targetContext;
    private Uri analyticsUri =
        new Uri.Builder()
            .scheme("https")
            .authority("www.google-analytics.com")
            .path("collect")
            .build();
    private String trackingId = "UA-36650409-3";
    private String apiLevel = String.valueOf(Build.VERSION.SDK_INT);
    private String model = Build.MODEL;
    private String targetPackage;
    private URL analyticsURI;
    private String screenResolution;
    private String userId;
    private boolean hashed;

    public Builder(Context targetContext) {
      if (targetContext == null) {
        throw new NullPointerException("Context null!?");
      }
      this.targetContext = targetContext;
    }

    public Builder withTrackingId(String trackingId) {
      this.trackingId = trackingId;
      return this;
    }

    public Builder withAnalyticsUri(Uri analyticsUri) {
      checkNotNull(analyticsUri);
      this.analyticsUri = analyticsUri;
      return this;
    }

    public Builder withApiLevel(String apiLevel) {
      this.apiLevel = apiLevel;
      return this;
    }

    public Builder withScreenResolution(String resolutionVal) {
      this.screenResolution = resolutionVal;
      return this;
    }

    public Builder withUserId(String userId) {
      this.userId = userId;
      return this;
    }

    public Builder withModel(String model) {
      this.model = model;
      return this;
    }

    public Builder withTargetPackage(String targetPackage) {
      hashed = false;
      this.targetPackage = targetPackage;
      return this;
    }

    public UsageTracker buildIfPossible() {
      if (!hasInternetPermission()) {
        Log.d(TAG, "Tracking disabled due to lack of internet permissions");
        return null;
      }

      if (null == targetPackage) {
        withTargetPackage(targetContext.getPackageName());
      }

      if (targetPackage.contains("com.google.analytics")) {
        Log.d(TAG, "Refusing to use analytics while testing analytics.");
        return null;
      }

      try {
        if (targetPackage.startsWith("com.google.")
            || targetPackage.startsWith("com.android.")
            || targetPackage.startsWith("android.support.")) {
          // track usage of google owned packages...
        } else {
          if (!hashed) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.reset();
            digest.update(targetPackage.getBytes(UTF_8));
            BigInteger hashedPackage = new BigInteger(digest.digest());
            targetPackage = "sha256-" + hashedPackage.toString(16);
          }
          hashed = true;
        }
      } catch (NoSuchAlgorithmException nsae) {
        Log.d(TAG, "Cannot hash package name.", nsae);
        return null;
      } catch (UnsupportedEncodingException uee) {
        Log.d(TAG, "Impossible - no utf-8 encoding?", uee);
        return null;
      }

      try {
        analyticsURI = new URL(analyticsUri.toString());
      } catch (MalformedURLException mule) {
        Log.w(TAG, "Tracking disabled bad url: " + analyticsUri.toString(), mule);
        return null;
      }

      if (null == screenResolution) {
        Display display =
            ((WindowManager) targetContext.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();
        // Headless devices don't have a Display.
        if (null == display) {
          screenResolution = "0x0";
        } else {
          screenResolution =
              new StringBuilder()
                  .append(display.getWidth())
                  .append("x")
                  .append(display.getHeight())
                  .toString();
        }
      }

      if (null == userId) {
        userId = UUID.randomUUID().toString();
      }

      return new AnalyticsBasedUsageTracker(this);
    }

    private boolean hasInternetPermission() {
      return PackageManager.PERMISSION_GRANTED
          == targetContext.checkCallingOrSelfPermission("android.permission.INTERNET");
    }
  }

  @Override
  public void trackUsage(String usageType, String version) {
    synchronized (usageTypeToVersion) {
      usageTypeToVersion.put(usageType, version);
    }
  }

  @Override
  public void sendUsages() {
    Map<String, String> myUsages;
    synchronized (usageTypeToVersion) {
      if (usageTypeToVersion.isEmpty()) {
        return;
      }
      myUsages = new HashMap<>(usageTypeToVersion);
      usageTypeToVersion.clear();
    }

    String baseBody = null;
    try {
      baseBody =
          new StringBuilder()
              .append(APP_NAME_PARAM)
              .append(encode(targetPackage, UTF_8))
              .append(TRACKER_ID_PARAM)
              .append(encode(trackingId, UTF_8))
              .append("&v=1") // Protocol Version.
              .append("&z=") // Cache Buster (optional)
              .append(SystemClock.uptimeMillis())
              .append(CLIENT_ID_PARAM)
              .append(encode(userId, UTF_8))
              .append(SCREEN_RESOLUTION_PARAM)
              .append(encode(screenResolution, UTF_8))
              .append(API_LEVEL_PARAM)
              .append(encode(apiLevel, UTF_8))
              .append(MODEL_NAME_PARAM)
              .append(encode(model, UTF_8))
              .append("&t=appview") // Hit type
              .append("&sc=start") // Session Control
              .toString();
    } catch (IOException ioe) {
      Log.w(TAG, "Impossible error happened. analytics disabled.", ioe);
    }

    for (Map.Entry<String, String> usage : myUsages.entrySet()) {
      HttpURLConnection analyticsConnection = null;
      try {
        analyticsConnection = (HttpURLConnection) analyticsURI.openConnection();

        byte[] body =
            new StringBuilder()
                .append(baseBody)
                .append(SCREEN_NAME_PARAM)
                .append(encode(usage.getKey(), UTF_8))
                .append(APP_VERSION_PARAM)
                .append(encode(usage.getValue(), UTF_8))
                .toString()
                // j5 compatibility. this is utf8.
                .getBytes();

        analyticsConnection.setConnectTimeout(3000); // milliseconds
        analyticsConnection.setReadTimeout(5000); // milliseconds
        analyticsConnection.setDoOutput(true);
        analyticsConnection.setFixedLengthStreamingMode(body.length);
        analyticsConnection.getOutputStream().write(body);
        int status = analyticsConnection.getResponseCode();
        if (status / 100 != 2) {
          Log.w(
              TAG,
              "Analytics post: "
                  + usage
                  + " failed. code: "
                  + analyticsConnection.getResponseCode()
                  + " - "
                  + analyticsConnection.getResponseMessage());
        }
      } catch (IOException ioe) {
        Log.w(TAG, "Analytics post: " + usage + " failed. ", ioe);
      } finally {
        if (null != analyticsConnection) {
          analyticsConnection.disconnect();
        }
      }
    }
  }
}
