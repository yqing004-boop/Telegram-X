package org.thunderdog.challegram.data;

import org.drinkless.tdlib.TdApi;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * OpenAI-compatible chat completion translator.
 * Endpoint / key / model are configured in Settings (proxy-free by design).
 */
public final class AiTranslator {
  private AiTranslator () { }

  public static TdApi.Object translate (String text, String target, String endpoint, String apiKey, String model) {
    if (text == null || text.trim().isEmpty()) {
      return new TdApi.FormattedText("", new TdApi.TextEntity[0]);
    }
    HttpURLConnection connection = null;
    try {
      String url = endpoint.endsWith("/") ? endpoint + "chat/completions" : endpoint + "/chat/completions";
      JSONObject body = new JSONObject();
      body.put("model", model != null && !model.isEmpty() ? model : "gpt-4o-mini");
      JSONArray messages = new JSONArray();
      JSONObject system = new JSONObject();
      system.put("role", "system");
      system.put("content", "You are a translator. Translate the user message into " + target + ". Output ONLY the translation, no explanations, no quotes.");
      messages.put(system);
      JSONObject user = new JSONObject();
      user.put("role", "user");
      user.put("content", text);
      messages.put(user);
      body.put("messages", messages);
      body.put("temperature", 0);
      body.put("stream", false);

      connection = (HttpURLConnection) new URL(url).openConnection();
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(30000);
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json");
      if (apiKey != null && !apiKey.isEmpty()) {
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
      }
      try (OutputStream out = connection.getOutputStream()) {
        out.write(body.toString().getBytes(StandardCharsets.UTF_8));
      }
      int code = connection.getResponseCode();
      if (code != 200) {
        String err = code >= 400 ? readAll(connection.getErrorStream()) : "";
        return new TdApi.Error(code, "http " + code + (err.isEmpty() ? "" : ": " + trim(err)));
      }
      String response = readAll(connection.getInputStream());
      JSONObject root = new JSONObject(response);
      JSONArray choices = root.optJSONArray("choices");
      if (choices == null || choices.length() == 0) {
        return new TdApi.Error(500, "empty choices");
      }
      JSONObject message = choices.getJSONObject(0).optJSONObject("message");
      if (message == null) {
        return new TdApi.Error(500, "no message");
      }
      String translated = message.optString("content", "").trim();
      return new TdApi.FormattedText(translated, new TdApi.TextEntity[0]);
    } catch (Throwable t) {
      return new TdApi.Error(500, t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : ""));
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private static String trim (String s) {
    return s.length() > 200 ? s.substring(0, 200) : s;
  }

  private static String readAll (InputStream in) throws Exception {
    if (in == null) return "";
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int read;
    while ((read = in.read(chunk)) != -1) {
      buffer.write(chunk, 0, read);
    }
    in.close();
    return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
  }
}
