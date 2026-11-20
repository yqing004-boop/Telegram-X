package org.thunderdog.challegram.proxy;

import android.content.Context;
import android.view.View;
import android.widget.Toast;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TGAvatars;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.ui.DoneButton;
import org.thunderdog.challegram.ui.EditTextController;
import org.thunderdog.challegram.ui.ListItem;
import org.thunderdog.challegram.ui.RecyclerViewController;
import org.thunderdog.challegram.ui.SettingsAdapter;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings page for the bundled proxy core (机场分组):
 * subscription URL / manual share links / connection switch.
 */
public class ProxySettingsController extends RecyclerViewController<Void> implements View.OnClickListener {
  private static final String KEY_SUBSCRIPTION_URL = "proxy_subscription_url";
  private static final String KEY_MANUAL_LINKS = "proxy_manual_links";

  private SettingsAdapter adapter;

  public ProxySettingsController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  protected boolean needPersistentScrollPosition () {
    return false;
  }

  private static String getSubscriptionUrl () {
    return Settings.instance().getString(KEY_SUBSCRIPTION_URL, "");
  }

  private static void setSubscriptionUrl (String url) {
    Settings.instance().putString(KEY_SUBSCRIPTION_URL, url);
  }

  private static String getManualLinks () {
    return Settings.instance().getString(KEY_MANUAL_LINKS, "");
  }

  private static void setManualLinks (String links) {
    Settings.instance().putString(KEY_MANUAL_LINKS, links);
  }

  private String buildStateSubtitle () {
    if (SingBoxService.isRunning()) {
      return "运行中 · 127.0.0.1:" + SingBoxService.getBoundPort();
    }
    String error = SingBoxService.getLastError();
    if (error != null && !error.isEmpty()) {
      return "启动失败: " + error;
    }
    return Lang.getString(R.string.ProxySettingsOff);
  }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new SettingsAdapter(this) {
      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        final int itemId = item.getId();
        if (itemId == R.id.btn_proxyState) {
          view.setData(buildStateSubtitle());
        } else if (itemId == R.id.btn_proxySubscription) {
          String url = getSubscriptionUrl();
          view.setData(url.isEmpty() ? Lang.getString(R.string.ProxySubscriptionEmpty) : shorten(url));
        } else if (itemId == R.id.btn_proxyLinks) {
          String links = getManualLinks();
          int count = countLinks(links);
          view.setData(count == 0 ? Lang.getString(R.string.ProxyLinksEmpty) : Lang.getString(R.string.ProxyLinksCount, count));
        }
      }
    };
    recyclerView.setAdapter(adapter);
    buildItems();
  }

  private static String shorten (String url) {
    if (url.length() > 42) return url.substring(0, 42) + "…";
    return url;
  }

  private static int countLinks (String links) {
    if (links == null || links.isEmpty()) return 0;
    int count = 0;
    for (String line : links.split("\n")) {
      if (ProxyNodeParser.isSupportedLink(line)) count++;
    }
    return count;
  }

  private void buildItems () {
    List<ListItem> items = new ArrayList<>();
    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.ProxyHeader));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_proxyToggle, 0, R.string.ProxyEnable));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_proxySubscription, 0, R.string.ProxySubscription));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_proxyLinks, 0, R.string.ProxyLinks));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_proxyState, 0, R.string.ProxyState));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.ProxyDesc));
    adapter.setItems(items, true);
    adapter.updateAllValuedSettingsById(R.id.btn_proxySubscription);
  }

  @Override
  public void onClick (View v) {
    final int viewId = v.getId();
    if (viewId == R.id.btn_proxyToggle) {
      boolean enable = adapter.toggleView(v);
      if (enable) {
        boolean started = startProxy();
        if (!started) {
          CharSequence msg = SingBoxService.getLastError() != null ? SingBoxService.getLastError() : Lang.getString(R.string.ProxyStartFailed);
          UI.showToast(msg, Toast.LENGTH_SHORT);
          adapter.toggleView(v); // revert
        }
      } else {
        SingBoxService.stopService(getContext());
      }
    } else if (viewId == R.id.btn_proxySubscription) {
      showSubscriptionInput();
    } else if (viewId == R.id.btn_proxyLinks) {
      showLinksInput();
    }
  }

  private boolean startProxy () {
    String url = getSubscriptionUrl().trim();
    if (!url.isEmpty()) {
      if (SingBoxService.startFromSubscription(getContext(), url)) {
        SingBoxService.startService(getContext());
        adapter.updateAllValuedSettingsById(R.id.btn_proxyState);
        return true;
      }
      return false;
    }
    String links = getManualLinks().trim();
    if (!links.isEmpty()) {
      List<ProxyNodeParser.Node> nodes = new ArrayList<>();
      for (String line : links.split("\n")) {
        ProxyNodeParser.Node node = ProxyNodeParser.parseLink(line);
        if (node != null) nodes.add(node);
      }
      org.json.JSONObject config = ProxyNodeParser.buildConfigForLinks(nodes, 7891);
      if (config != null && SingBoxService.startFromConfig(getContext(), config)) {
        SingBoxService.startService(getContext());
        adapter.updateAllValuedSettingsById(R.id.btn_proxyState);
        return true;
      }
      return false;
    }
    SingBoxService.setLastErrorText(Lang.getString(R.string.ProxyNothingConfigured));
    return false;
  }

  private static final int INPUT_SUBSCRIPTION = 1;
  private static final int INPUT_LINKS = 2;

  private void showSubscriptionInput () {
    EditTextController<?> controller = new EditTextController<>(context(), new EditTextController.Delegate() {
      @Override public int getId () { return INPUT_SUBSCRIPTION; }
      @Override public CharSequence getName () { return Lang.getString(R.string.ProxySubscription); }
      @Override public CharSequence getHint () { return "https://..."; }
      @Override public CharSequence getDescription () { return Lang.getString(R.string.ProxySubscriptionDesc); }
      @Override public String getCurrentValue () { return getSubscriptionUrl(); }
      @Override public boolean onDonePressed (EditTextController<?> c, DoneButton button, CharSequence value) {
        setSubscriptionUrl(value != null ? value.toString().trim() : "");
        adapter.updateAllValuedSettingsById(R.id.btn_proxySubscription);
        navigateBack();
        return true;
      }
    });
    navigateTo(controller);
  }

  private void showLinksInput () {
    EditTextController<?> controller = new EditTextController<>(context(), new EditTextController.Delegate() {
      @Override public int getId () { return INPUT_LINKS; }
      @Override public CharSequence getName () { return Lang.getString(R.string.ProxyLinks); }
      @Override public CharSequence getHint () { return "vless://…\nhysteria2://…"; }
      @Override public CharSequence getDescription () { return Lang.getString(R.string.ProxyLinksDesc); }
      @Override public String getCurrentValue () { return getManualLinks(); }
      @Override public int getMaxLength () { return 65535; }
      @Override public boolean onDonePressed (EditTextController<?> c, DoneButton button, CharSequence value) {
        setManualLinks(value != null ? value.toString().trim() : "");
        adapter.updateAllValuedSettingsById(R.id.btn_proxyLinks);
        navigateBack();
        return true;
      }
    });
    navigateTo(controller);
  }
}
