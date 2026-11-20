package org.thunderdog.challegram.proxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;
import org.thunderdog.challegram.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Foreground service hosting the bundled sing-box core process.
 * Config JSON is stored in app-private storage; the core binary is
 * packaged as a native library (libsingbox.so) and executed locally.
 */
public class SingBoxService extends Service {
  private static final String CHANNEL_ID = "singbox_proxy";
  private static final int NOTIFICATION_ID = 91;
  private static final int LISTEN_PORT = 7891;

  private static Process sProcess;
  private static int sBoundPort = 0;
  private static String sLastError;

  public static boolean isRunning () {
    try {
      return sProcess != null && sProcess.isAlive();
    } catch (Throwable t) {
      return false;
    }
  }

  public static int getBoundPort () {
    return isRunning() ? sBoundPort : 0;
  }

  public static String getLastError () {
    return sLastError;
  }

  public static boolean startFromSubscription (Context context, String subscriptionUrl) {
    try {
      String payload = ProxyNodeParser.fetchSubscription(subscriptionUrl);
      if (payload == null || payload.trim().isEmpty()) {
        sLastError = "empty subscription";
        return false;
      }
      JSONObject config;
      String trimmed = payload.trim();
      if (trimmed.startsWith("{")) {
        config = new JSONObject(trimmed);
        int port = ProxyNodeParser.findMixedPort(config);
        if (port == 0) {
          // no usable inbound, inject ours
          config = injectMixedInbound(config, LISTEN_PORT);
          port = LISTEN_PORT;
        } else if (port != LISTEN_PORT) {
          sBoundPort = port;
        }
        if (port == LISTEN_PORT) sBoundPort = LISTEN_PORT;
      } else {
        java.util.List<ProxyNodeParser.Node> nodes = ProxyNodeParser.parseBase64Subscription(payload);
        if (nodes == null || nodes.isEmpty()) {
          sLastError = "no nodes in subscription";
          return false;
        }
        config = ProxyNodeParser.buildConfigForLinks(nodes, LISTEN_PORT);
        sBoundPort = LISTEN_PORT;
      }
      if (config == null) {
        sLastError = "config build failed";
        return false;
      }
      return startCore(context, config.toString());
    } catch (Throwable t) {
      sLastError = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
      stopCore();
      return false;
    }
  }

  private static JSONObject injectMixedInbound (JSONObject config, int port) throws Exception {
    org.json.JSONArray inbounds = config.optJSONArray("inbounds");
    if (inbounds == null) inbounds = new org.json.JSONArray();
    JSONObject mixed = new JSONObject();
    mixed.put("type", "mixed");
    mixed.put("tag", "tgx-mixed-in");
    mixed.put("listen", "127.0.0.1");
    mixed.put("listen_port", port);
    // replace tun inbound if present (we don't need TUN in-app)
    org.json.JSONArray newInbounds = new org.json.JSONArray();
    boolean inserted = false;
    for (int i = 0; i < inbounds.length(); i++) {
      JSONObject inb = inbounds.getJSONObject(i);
      if ("tun".equals(inb.optString("type"))) continue;
      if ("mixed".equals(inb.optString("type"))) {
        inb.put("listen", "127.0.0.1");
        inb.put("listen_port", port);
        inserted = true;
      }
      newInbounds.put(inb);
    }
    if (!inserted) newInbounds.put(mixed);
    config.put("inbounds", newInbounds);
    return config;
  }

  private static synchronized boolean startCore (Context context, String configJson) {
    stopCore();
    try {
      File binary = extractBinary(context);
      File configFile = new File(context.getFilesDir(), "singbox_config.json");
      try (OutputStream out = new FileOutputStream(configFile)) {
        out.write(configJson.getBytes("UTF-8"));
      }
      File workDir = context.getFilesDir();
      ProcessBuilder builder = new ProcessBuilder(
        binary.getAbsolutePath(), "run",
        "-c", configFile.getAbsolutePath(),
        "-D", workDir.getAbsolutePath()
      );
      builder.directory(workDir);
      builder.redirectErrorStream(true);
      sProcess = builder.start();
      sLastError = null;
      // give the core a moment, then verify it stayed alive
      try { Thread.sleep(700); } catch (InterruptedException ignored) { }
      if (sProcess == null || !sProcess.isAlive()) {
        sLastError = readProcessOutput();
        sProcess = null;
        return false;
      }
      return true;
    } catch (Throwable t) {
      sLastError = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
      sProcess = null;
      return false;
    }
  }

  private static String readProcessOutput () {
    if (sProcess == null) return "process died";
    try {
      InputStream in = sProcess.getInputStream();
      java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
      byte[] chunk = new byte[4096];
      int read = in.read(chunk);
      if (read > 0) buffer.write(chunk, 0, read);
      return new String(buffer.toByteArray()).trim();
    } catch (Throwable t) {
      return "process died";
    }
  }

  public static synchronized void stopCore () {
    if (sProcess != null) {
      try { sProcess.destroy(); } catch (Throwable ignored) { }
      try { sProcess.waitFor(); } catch (Throwable ignored) { }
      sProcess = null;
    }
    sBoundPort = 0;
  }

  private static File extractBinary (Context context) throws Exception {
    File target = new File(context.getFilesDir(), "sing-box");
    if (!target.exists() || target.length() == 0) {
      // The binary is packaged as libsingbox.so inside the APK's native lib dir
      String nativeDir = context.getApplicationInfo().nativeLibraryDir;
      File source = new File(nativeDir, "libsingbox.so");
      if (!source.exists()) throw new IllegalStateException("sing-box binary missing");
      try (InputStream in = new java.io.FileInputStream(source);
           OutputStream out = new FileOutputStream(target)) {
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) out.write(chunk, 0, read);
      }
    }
    target.setExecutable(true, true);
    return target;
  }

  @Override
  public void onCreate () {
    super.onCreate();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Proxy", NotificationManager.IMPORTANCE_LOW);
      getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
  }

  @Override
  public int onStartCommand (Intent intent, int flags, int startId) {
    startForeground(NOTIFICATION_ID, buildNotification());
    return START_STICKY;
  }

  private Notification buildNotification () {
    NotificationCompat.Builder builder;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      builder = new NotificationCompat.Builder(this, CHANNEL_ID);
    } else {
      builder = new NotificationCompat.Builder(this);
    }
    return builder
      .setSmallIcon(R.drawable.baseline_translate_24)
      .setContentTitle("TelegramCh")
      .setContentText("Proxy running on 127.0.0.1:" + (sBoundPort != 0 ? sBoundPort : LISTEN_PORT))
      .setOngoing(true)
      .build();
  }

  @Nullable
  @Override
  public IBinder onBind (Intent intent) {
    return null;
  }

  @Override
  public void onDestroy () {
    stopCore();
    super.onDestroy();
  }

  public static boolean startFromConfig (Context context, org.json.JSONObject config) {
    try {
      int port = ProxyNodeParser.findMixedPort(config);
      sBoundPort = port != 0 ? port : LISTEN_PORT;
      if (startCore(context, config.toString())) {
        return true;
      }
      return false;
    } catch (Throwable t) {
      sLastError = t.getClass().getSimpleName();
      return false;
    }
  }

  public static void setLastErrorText (String text) {
    sLastError = text;
  }

  public static void startService (Context context) {
    Intent intent = new Intent(context, SingBoxService.class);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.startForegroundService(intent);
    } else {
      context.startService(intent);
    }
  }

  public static void stopService (Context context) {
    stopCore();
    context.stopService(new Intent(context, SingBoxService.class));
  }
}
