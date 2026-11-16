package org.thunderdog.challegram.data;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.unsorted.Settings;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Automatically translates chat messages into the configured language using Telegram X translation. */
public final class AdvancedChineseTranslation {
  private static final Map<TGMessage, String> REQUESTED =
    Collections.synchronizedMap(new WeakHashMap<>());

  private AdvancedChineseTranslation () { }

  /** Called when a message view is bound to a message, i.e. the message becomes visible. */
  public static void onMessageVisible (TGMessage message) {
    translate(message, false);
  }

  /** Called when the whole loaded chat history should be translated at once. */
  public static void onWholeChatRequested (TGMessage message) {
    if (!Settings.instance().isAdvancedTranslateWholeChatEnabled()) return;
    translate(message, true);
  }

  private static void translate (TGMessage message, boolean ignoreAttachment) {
    if (message == null || message.isNotSent() || message.isSponsoredMessage()
        || message.isEventLog() || message.getChatId() == 0) return;

    Settings settings = Settings.instance();
    if (!settings.isAdvancedAutoTranslateEnabled()
        || settings.getChatTranslateMode() != Settings.TRANSLATE_MODE_INLINE) return;

    TdApi.FormattedText known = message.getTextToTranslate();
    if (known != null && known.text != null && message.getOriginalMessageLanguage() != null) {
      translateIfNeeded(message, ignoreAttachment);
    } else {
      message.checkTranslatableText(() -> translateIfNeeded(message, ignoreAttachment));
    }
  }

  private static void translateIfNeeded (TGMessage message, boolean ignoreAttachment) {
    if (!Settings.instance().isAdvancedAutoTranslateEnabled()
        || Settings.instance().getChatTranslateMode() != Settings.TRANSLATE_MODE_INLINE
        || (!ignoreAttachment && !message.isAttachedToView())
        || !message.isTranslatable()) return;

    String target = Settings.instance().getAdvancedTranslateTarget();
    if (Settings.ADVANCED_TARGET_APP.equals(target)) {
      target = Settings.instance().getLanguage().packInfo.pluralCode;
    }
    String source = message.getOriginalMessageLanguage();
    if (target == null || target.isEmpty()) return;
    if (Settings.instance().isAdvancedSkipChineseEnabled() && isChinese(source)) return;
    if (source != null && source.equalsIgnoreCase(target)) return;

    TdApi.FormattedText text = message.getTextToTranslate();
    if (text == null || text.text == null || text.text.trim().isEmpty()) return;
    String key = target + ":" + text.text;
    synchronized (REQUESTED) {
      if (key.equals(REQUESTED.get(message)) || message.isTranslated()) return;
      REQUESTED.put(message, key);
    }
    message.startTranslated(target);
  }

  private static boolean isChinese (String language) {
    return language != null && (language.equals("zh") || language.startsWith("zh-"));
  }
}
