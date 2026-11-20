package org.thunderdog.challegram.component.chat;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.data.TGAvatars;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.unsorted.Settings;

import me.vkryl.core.StringUtils;

/**
 * Top banner for chat auto-translation state:
 * [文A icon] 查看原文 / 翻译为简体中文        [⚙ settings]
 * Settings click opens a popup menu with per-chat options.
 */
public class TranslateTopBar extends FrameLayout {
  public interface Listener {
    void onToggleViewOriginal ();
    void onOpenSettings ();
  }

  private final ImageView iconView;
  private final TextView titleView;
  private final ImageView settingsButton;
  private Listener listener;
  private boolean showingOriginal;

  public TranslateTopBar (Context context) {
    super(context);
    setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, Screen.dp(36f)));

    iconView = new ImageView(context);
    iconView.setImageResource(R.drawable.baseline_translate_24);
    LayoutParams iconParams = new LayoutParams(Screen.dp(24f), Screen.dp(24f), Gravity.LEFT | Gravity.CENTER_VERTICAL);
    iconParams.leftMargin = Screen.dp(46f);
    addView(iconView, iconParams);

    titleView = new TextView(context);
    titleView.setTextSize(14f);
    titleView.setTypeface(Typeface.DEFAULT_BOLD);
    LayoutParams titleParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL);
    titleParams.leftMargin = Screen.dp(78f);
    addView(titleView, titleParams);

    settingsButton = new ImageView(context);
    settingsButton.setImageResource(R.drawable.baseline_settings_24);
    LayoutParams settingsParams = new LayoutParams(Screen.dp(34f), Screen.dp(34f), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
    settingsParams.rightMargin = Screen.dp(10f);
    settingsButton.setOnClickListener(v -> {
      if (listener != null) listener.onOpenSettings();
    });
    Views.setClickable(settingsButton);
    addView(settingsButton, settingsParams);

    setOnClickListener(v -> {
      if (listener != null) listener.onToggleViewOriginal();
    });
    Views.setClickable(this);

    updateText();
  }

  public void setListener (Listener listener) {
    this.listener = listener;
  }

  public void setShowingOriginal (boolean showingOriginal) {
    if (this.showingOriginal != showingOriginal) {
      this.showingOriginal = showingOriginal;
      updateText();
    }
  }

  public boolean isShowingOriginal () {
    return showingOriginal;
  }

  private void updateText () {
    String target = Settings.instance().getAdvancedTranslateTarget();
    String targetName;
    if (Settings.ADVANCED_TARGET_APP.equals(target)) {
      targetName = "应用语言";
    } else if ("zh-TW".equals(target)) {
      targetName = "繁體中文";
    } else {
      targetName = "简体中文";
    }
    titleView.setText(showingOriginal ? "查看原文" : "翻译为 " + targetName);
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(Screen.dp(36f), MeasureSpec.EXACTLY));
  }
}
