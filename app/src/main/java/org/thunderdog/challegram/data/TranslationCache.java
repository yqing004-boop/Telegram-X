package org.thunderdog.challegram.data;

import org.json.JSONArray;
import org.json.JSONObject;
import org.thunderdog.challegram.tool.UI;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global persistent translation cache: "srcText|targetLang" -> translated text.
 * Survives message-object recreation and app restarts. LRU capped.
 */
public final class TranslationCache {
  private static final int MAX_ENTRIES = 2000;
  private static volatile TranslationCache sInstance;

  private final Map<String, String> cache = new LinkedHashMap<>();
  private final File file;
  private boolean dirty;

  public static TranslationCache instance () {
    if (sInstance == null) {
      synchronized (TranslationCache.class) {
        if (sInstance == null) {
          sInstance = new TranslationCache();
        }
      }
    }
    return sInstance;
  }

  private TranslationCache () {
    File dir = UI.getContext(null) != null
      ? UI.getContext(null).getFilesDir()
      : new File("/data/local/tmp");
    this.file = new File(dir, "tgx_translation_cache.json");
    load();
  }

  private static String key (String sourceText, String targetLanguage) {
    return targetLanguage + "|" + sourceText;
  }

  public static String get (String sourceText, String targetLanguage) {
    if (sourceText == null || sourceText.isEmpty()) return null;
    synchronized (TranslationCache.class) {
      return instance().cache.get(key(sourceText, targetLanguage));
    }
  }

  public static void put (String sourceText, String targetLanguage, String translated) {
    if (sourceText == null || translated == null || sourceText.isEmpty()) return;
    synchronized (TranslationCache.class) {
      TranslationCache inst = instance();
      String k = key(sourceText, targetLanguage);
      // LRU: re-insert to end
      inst.cache.remove(k);
      inst.cache.put(k, translated);
      while (inst.cache.size() > MAX_ENTRIES) {
        Iterator<String> it = inst.cache.keySet().iterator();
        if (!it.hasNext()) break;
        it.next();
        it.remove();
      }
      inst.dirty = true;
      inst.scheduleSave();
    }
  }

  private void scheduleSave () {
    // save on UI thread lazily; cheap enough for 2000 entries
    if (UI.getContext(null) != null) {
      UI.getContext(null).getWindow().getDecorView().postDelayed(this::save, 3000);
    }
  }

  private void save () {
    synchronized (TranslationCache.class) {
      if (!dirty) return;
      try {
        JSONObject root = new JSONObject();
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, String> e : cache.entrySet()) {
          JSONArray pair = new JSONArray();
          pair.put(e.getKey());
          pair.put(e.getValue());
          arr.put(pair);
        }
        root.put("v", 1);
        root.put("items", arr);
        try (FileOutputStream out = new FileOutputStream(file)) {
          out.write(root.toString().getBytes(StandardCharsets.UTF_8));
        }
        dirty = false;
      } catch (Throwable ignored) { }
    }
  }

  private void load () {
    synchronized (TranslationCache.class) {
      try {
        if (!file.exists()) return;
        byte[] buffer = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
          int read = 0, n;
          while ((n = in.read(buffer, read, buffer.length - read)) > 0) read += n;
        }
        JSONObject root = new JSONObject(new String(buffer, 0, read, StandardCharsets.UTF_8));
        JSONArray arr = root.optJSONArray("items");
        if (arr != null) {
          for (int i = 0; i < arr.length() && cache.size() < MAX_ENTRIES; i++) {
            JSONArray pair = arr.optJSONArray(i);
            if (pair != null && pair.length() == 2) {
              cache.put(pair.optString(0), pair.optString(1));
            }
          }
        }
      } catch (Throwable ignored) { }
    }
  }
}
