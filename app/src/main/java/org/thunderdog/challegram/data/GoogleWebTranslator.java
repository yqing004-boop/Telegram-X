package org.thunderdog.challegram.data;

import org.drinkless.tdlib.TdApi;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Free Google web translator endpoint, used when the user picks "Google" as the message translator
 * in the advanced translation settings. Returns either TdApi.FormattedText or TdApi.Error,
 * matching the TDLib callback contract used by TranslationsManager.
 */
public final class GoogleWebTranslator {
  private static final String ENDPOINT = "https://translate.googleapis.com/translate_a/single";

  private GoogleWebTranslator () { }

  public static Object translate (String text, String toLanguage) {
    if (text == null || text.trim().isEmpty()) {
      return new TdApi.FormattedText("", new TdApi.TextEntity[0]);
    }
    HttpURLConnection connection = null;
    try {
      StringBuilder url = new StringBuilder(ENDPOINT)
        .append("?client=gtx&dt=t&sl=auto&tl=").append(URLEncoder.encode(normalize(toLanguage), "UTF-8"));
      String encoded = text.length() > 1800 ? text.substring(0, 1800) : text;
      String query = "q=" + URLEncoder.encode(encoded, "UTF-8");
      URL target = new URL(url.toString());
      connection = (HttpURLConnection) target.openConnection();
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(20000);
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
      connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");
      try (OutputStream out = connection.getOutputStream()) {
        out.write(query.getBytes("UTF-8"));
      }
      int code = connection.getResponseCode();
      if (code != 200) {
        return new TdApi.Error(code, "http " + code);
      }
      String body = readAll(connection.getInputStream());
      String translated = extractTranslation(body);
      if (translated == null) {
        return new TdApi.Error(500, "unexpected response");
      }
      return new TdApi.FormattedText(translated, new TdApi.TextEntity[0]);
    } catch (Throwable t) {
      return new TdApi.Error(500, t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : ""));
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private static String normalize (String language) {
    if (language == null || language.isEmpty()) return "zh-CN";
    return language;
  }

  private static String readAll (InputStream in) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[4096];
    int read;
    while ((read = in.read(chunk)) != -1) {
      buffer.write(chunk, 0, read);
    }
    return new String(buffer.toByteArray(), "UTF-8");
  }

  /** Response shape: [[["translated","source",null,null,10],["more","src2",...]],null,"en",...] */
  private static String extractTranslation (String body) {
    try {
      JSONArray root = new JSONArray(body);
      JSONArray sentences = root.optJSONArray(0);
      if (sentences == null) return null;
      StringBuilder result = new StringBuilder();
      for (int i = 0; i < sentences.length(); i++) {
        JSONArray pair = sentences.optJSONArray(i);
        if (pair == null) continue;
        String piece = pair.optString(0, null);
        if (piece != null) result.append(piece);
      }
      return result.length() > 0 ? result.toString() : null;
    } catch (Throwable ignored) {
      try {
        JSONObject object = new JSONObject(body);
        return object.optString("translatedText", null);
      } catch (Throwable ignored2) {
        return null;
      }
    }
  }
}
