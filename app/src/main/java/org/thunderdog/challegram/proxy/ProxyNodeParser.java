package org.thunderdog.challegram.proxy;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.thunderdog.challegram.tool.Strings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses share links (vless://, hysteria2://) and subscription payloads
 * into sing-box outbounds. No server URLs are stored in code.
 */
public final class ProxyNodeParser {
  public static final class Node {
    public final String tag;
    public final JSONObject outbound;
    public Node (String tag, JSONObject outbound) {
      this.tag = tag;
      this.outbound = outbound;
    }
  }

  private ProxyNodeParser () { }

  public static boolean isSupportedLink (String text) {
    return text != null && (text.trim().startsWith("vless://") || text.trim().startsWith("hysteria2://") || text.trim().startsWith("hy2://"));
  }

  public static @Nullable Node parseLink (String link) {
    if (link == null) return null;
    link = link.trim();
    try {
      if (link.startsWith("vless://")) {
        return parseVless(link);
      } else if (link.startsWith("hysteria2://") || link.startsWith("hy2://")) {
        return parseHysteria2(link);
      }
    } catch (Throwable ignored) { }
    return null;
  }

  private static @Nullable Node parseVless (String link) throws Exception {
    String body = link.substring("vless://".length());
    int hash = body.indexOf('#');
    String tag = hash != -1 ? URLDecoder.decode(body.substring(hash + 1), "UTF-8") : "vless";
    if (hash != -1) body = body.substring(0, hash);
    int query = body.indexOf('?');
    Map<String, String> params = query != -1 ? parseQuery(body.substring(query + 1)) : new LinkedHashMap<>();
    if (query != -1) body = body.substring(0, query);
    int at = body.indexOf('@');
    if (at == -1) return null;
    String uuid = body.substring(0, at);
    String hostPort = body.substring(at + 1);
    String server; int port;
    int colon = hostPort.lastIndexOf(':');
    if (colon == -1) return null;
    server = hostPort.substring(0, colon);
    port = Integer.parseInt(hostPort.substring(colon + 1));

    JSONObject tls = new JSONObject();
    JSONObject reality = new JSONObject();
    boolean realityEnabled = "reality".equals(params.get("security"));
    boolean tlsEnabled = realityEnabled || "tls".equals(params.get("security")) || params.containsKey("sni");
    if (tlsEnabled) {
      tls.put("enabled", true);
      String sni = params.get("sni");
      if (sni != null) tls.put("server_name", sni);
      String fp = params.get("fp");
      if (fp != null) {
        JSONObject utls = new JSONObject();
        utls.put("enabled", true);
        utls.put("fingerprint", fp);
        tls.put("utls", utls);
      }
      if (realityEnabled) {
        reality.put("enabled", true);
        String pbk = params.get("pbk");
        if (pbk != null) reality.put("public_key", pbk);
        String sid = params.get("sid");
        if (sid != null && !sid.isEmpty()) reality.put("short_id", sid);
        tls.put("reality", reality);
      }
      String insecure = params.get("allowInsecure");
      if ("1".equals(insecure) || "true".equals(insecure)) tls.put("insecure", true);
    }

    JSONObject outbound = new JSONObject();
    outbound.put("type", "vless");
    outbound.put("tag", tag);
    outbound.put("server", server);
    outbound.put("server_port", port);
    outbound.put("uuid", uuid);
    String flow = params.get("flow");
    if (flow != null && !flow.isEmpty()) outbound.put("flow", flow);
    if (tlsEnabled) outbound.put("tls", tls);

    String type = params.getOrDefault("type", "tcp");
    if (!"tcp".equals(type)) {
      JSONObject transport = new JSONObject();
      transport.put("type", type);
      String path = params.get("path");
      if (path != null) transport.put("path", path);
      String host = params.get("host");
      if (host != null) {
        JSONArray hosts = new JSONArray();
        hosts.put(host);
        transport.put("headers", new JSONObject().put("Host", hosts));
      }
      String serviceName = params.get("serviceName");
      if (serviceName != null) transport.put("service_name", serviceName);
      outbound.put("transport", transport);
    }
    return new Node(tag, outbound);
  }

  private static @Nullable Node parseHysteria2 (String link) throws Exception {
    String body = link.substring(link.startsWith("hy2://") ? 6 : "hysteria2://".length());
    int hash = body.indexOf('#');
    String tag = hash != -1 ? URLDecoder.decode(body.substring(hash + 1), "UTF-8") : "hysteria2";
    if (hash != -1) body = body.substring(0, hash);
    int query = body.indexOf('?');
    Map<String, String> params = query != -1 ? parseQuery(body.substring(query + 1)) : new LinkedHashMap<>();
    if (query != -1) body = body.substring(0, query);
    // password@host:port
    int at = body.indexOf('@');
    if (at == -1) return null;
    String password = body.substring(0, at);
    String hostPort = body.substring(at + 1);
    String server; int port;
    int colon = hostPort.lastIndexOf(':');
    if (colon == -1) return null;
    server = hostPort.substring(0, colon);
    port = Integer.parseInt(hostPort.substring(colon + 1));

    JSONObject tls = new JSONObject();
    tls.put("enabled", true);
    String sni = params.get("sni");
    if (sni != null) tls.put("server_name", sni);
    String insecure = params.get("insecure");
    if ("1".equals(insecure)) tls.put("insecure", true);

    JSONObject outbound = new JSONObject();
    outbound.put("type", "hysteria2");
    outbound.put("tag", tag);
    outbound.put("server", server);
    outbound.put("server_port", port);
    outbound.put("password", password);
    outbound.put("tls", tls);
    String obfsPass = params.get("obfs-password");
    String obfsType = params.get("obfs");
    if (obfsPass != null && obfsType != null) {
      JSONObject obfs = new JSONObject();
      obfs.put("type", obfsType);
      obfs.put("password", obfsPass);
      outbound.put("obfs", obfs);
    }
    return new Node(tag, outbound);
  }

  private static Map<String, String> parseQuery (String query) throws Exception {
    Map<String, String> map = new LinkedHashMap<>();
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq == -1) continue;
      map.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
    }
    return map;
  }

  /** Fetches a subscription. Server decides the format by User-Agent. */
  public static @Nullable String fetchSubscription (String url) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    try {
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(20000);
      connection.setRequestProperty("User-Agent", "sing-box/1.14.4 (TelegramCh)");
      int code = connection.getResponseCode();
      if (code != 200) throw new IllegalStateException("HTTP " + code);
      InputStream in = connection.getInputStream();
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      int read;
      while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);
      in.close();
      return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    } finally {
      connection.disconnect();
    }
  }

  /** Validates & extracts the mixed inbound port from a sing-box config. */
  public static int findMixedPort (@NonNull JSONObject config) {
    try {
      JSONArray inbounds = config.getJSONArray("inbounds");
      for (int i = 0; i < inbounds.length(); i++) {
        JSONObject inb = inbounds.getJSONObject(i);
        if ("mixed".equals(inb.optString("type")) || "socks".equals(inb.optString("type"))) {
          return inb.optInt("listen_port", 0);
        }
      }
    } catch (Throwable ignored) { }
    return 0;
  }

  /** Detects whether a payload is a base64 share list (v2rayNG style). */
  public static @Nullable List<Node> parseBase64Subscription (String payload) {
    if (payload == null) return null;
    String trimmed = payload.trim();
    if (trimmed.startsWith("{") || trimmed.startsWith("vless://") || trimmed.startsWith("hysteria2://")) return null;
    List<Node> nodes = new ArrayList<>();
    try {
      String decoded;
      try {
        decoded = new String(Base64.getMimeDecoder().decode(trimmed), StandardCharsets.UTF_8);
      } catch (IllegalArgumentException bad) {
        return null;
      }
      for (String line : decoded.split("\n")) {
        Node node = parseLink(line);
        if (node != null) nodes.add(node);
      }
      return nodes;
    } catch (Throwable t) {
      return null;
    }
  }

  /** Builds the config file JSON for manual links: mixed inbound + parsed outbounds + selector. */
  public static @Nullable JSONObject buildConfigForLinks (List<Node> nodes, int listenPort) {
    if (nodes == null || nodes.isEmpty()) return null;
    try {
      JSONArray inbounds = new JSONArray();
      JSONObject mixed = new JSONObject();
      mixed.put("type", "mixed");
      mixed.put("tag", "mixed-in");
      mixed.put("listen", "127.0.0.1");
      mixed.put("listen_port", listenPort);
      inbounds.put(mixed);

      JSONArray outbounds = new JSONArray();
      List<String> tags = new ArrayList<>();
      for (Node node : nodes) {
        outbounds.put(node.outbound);
        tags.add(node.tag);
      }
      JSONObject selector = new JSONObject();
      selector.put("type", "selector");
      selector.put("tag", "proxy");
      JSONArray out = new JSONArray();
      for (String tag : tags) out.put(tag);
      selector.put("outbounds", out);
      selector.put("default", tags.get(0));
      outbounds.put(selector);

      JSONObject direct = new JSONObject();
      direct.put("type", "direct");
      direct.put("tag", "direct");
      outbounds.put(direct);

      JSONObject route = new JSONObject();
      route.put("final", "proxy");
      route.put("auto_detect_interface", true);

      JSONObject config = new JSONObject();
      config.put("inbounds", inbounds);
      config.put("outbounds", outbounds);
      config.put("route", route);
      return config;
    } catch (Throwable t) {
      return null;
    }
  }

  @SuppressWarnings("unused")
  private static boolean validPort (int port) {
    return port > 0 && port < 65536 && Strings.length(Integer.toString(port)) <= 5;
  }
}
