package org.thunderdog.challegram.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.FactorAnimator;

/**
 * Draggable fast-scroll thumb for RecyclerView, with a floating label bubble
 * (position / section name) shown while dragging.
 */
public class FastScrollThumb extends View implements FactorAnimator.Target {
  private static final int STATE_IDLE = 0;
  private static final int STATE_SHOWING = 1;
  private static final int STATE_DRAGGING = 2;

  private static final long FADE_OUT_DELAY = 1600L;

  private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint bubbleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.LINEAR_TEXT_FLAG);
  private final RectF thumbRect = new RectF();

  private final float thumbWidth = Screen.dp(4f);
  private final float thumbWidthDragging = Screen.dp(6f);
  private final float thumbHeight = Screen.dp(56f);
  private final float thumbMargin = Screen.dp(3f);
  private final float bubbleRadius = Screen.dp(18f);

  private int state = STATE_IDLE;
  private float dragY;
  private float touchStartY;
  private boolean startInThumb;
  private float thumbFactor; // 0..1 position
  private FactorAnimator thumbAnimator;
  private long lastActivity;
  private final int touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

  private @Nullable RecyclerView recyclerView;
  private @Nullable ScrollLabelProvider labelProvider;
  private @Nullable LoadMoreRequester loadMoreRequester;
  private String currentLabel = "";

  public interface ScrollLabelProvider {
    @Nullable String provideLabel (RecyclerView recyclerView, float thumbFactor);
  }

  public interface LoadMoreRequester {
    void onRequestLoadMore (RecyclerView recyclerView, float thumbFactor);
  }

  public FastScrollThumb (Context context) {
    super(context);
    thumbAnimator = new FactorAnimator(0, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 180L);
    setVisibility(GONE);
  }

  public void attach (RecyclerView recyclerView) {
    this.recyclerView = recyclerView;
  }

  public void setLabelProvider (ScrollLabelProvider provider) {
    this.labelProvider = provider;
  }

  public void setLoadMoreRequester (LoadMoreRequester requester) {
    this.loadMoreRequester = requester;
  }

  private void show () {
    lastActivity = System.currentTimeMillis();
    if (state != STATE_DRAGGING) {
      state = STATE_SHOWING;
      thumbAnimator.animateTo(1f);
      removeCallbacks(fadeOutRunnable);
      postDelayed(fadeOutRunnable, FADE_OUT_DELAY);
      setVisibility(VISIBLE);
    }
  }

  private final Runnable fadeOutRunnable = () -> {
    if (state == STATE_SHOWING) {
      thumbAnimator.animateTo(0f);
    }
  };

  public void onScrolled () {
    if (state != STATE_DRAGGING) {
      syncThumbToScroll();
      show();
    }
  }

  private void syncThumbToScroll () {
    RecyclerView recycler = recyclerView;
    if (recycler == null || recycler.getAdapter() == null) return;
    LinearLayoutManager manager = getLinearManager();
    if (manager == null) return;
    int itemCount = recycler.getAdapter().getItemCount();
    if (itemCount == 0) return;
    int first = manager.findFirstVisibleItemPosition();
    int last = manager.findLastVisibleItemPosition();
    if (last < first) return;
    // visible span ratio maps to thumb factor
    float factor = first / (float) Math.max(1, itemCount - (last - first + 1));
    if (first + (last - first + 1) >= itemCount) factor = 1f;
    thumbFactor = Math.max(0f, Math.min(1f, factor));
    invalidate();
  }

  private LinearLayoutManager getLinearManager () {
    RecyclerView.LayoutManager lm = recyclerView != null ? recyclerView.getLayoutManager() : null;
    return lm instanceof LinearLayoutManager ? (LinearLayoutManager) lm : null;
  }

  private void applyThumbFactor (float factor, boolean fromUser) {
    thumbFactor = Math.max(0f, Math.min(1f, factor));
    RecyclerView recycler = recyclerView;
    if (recycler != null && recycler.getAdapter() != null) {
      int itemCount = recycler.getAdapter().getItemCount();
      if (itemCount > 0) {
        LinearLayoutManager manager = getLinearManager();
        if (manager != null) {
          int span = Math.max(1, manager.findLastVisibleItemPosition() - manager.findFirstVisibleItemPosition() + 1);
          int target = (int) (thumbFactor * (itemCount - span));
          target = Math.max(0, Math.min(itemCount - 1, target));
          LinearLayoutManager lm = manager;
          int first = lm.findFirstVisibleItemPosition();
          int delta = target - first;
          // smooth-ish: direct scroll for big jumps, precise for small
          if (Math.abs(delta) > span * 6) {
            recycler.scrollToPosition(target);
          } else {
            recycler.smoothScrollBy(0, (int) (thumbFactor * recycler.computeVerticalScrollRange() - recycler.computeVerticalScrollOffset()));
          }
        }
        if (thumbFactor >= 0.98f && loadMoreRequester != null) {
          loadMoreRequester.onRequestLoadMore(recycler, thumbFactor);
        }
      }
    }
    if (labelProvider != null) {
      String label = labelProvider.provideLabel(recyclerView, thumbFactor);
      if (label != null && !label.equals(currentLabel)) {
        currentLabel = label;
      }
    }
    invalidate();
  }

  @Override
  public boolean onTouchEvent (@NonNull MotionEvent e) {
    switch (e.getAction()) {
      case MotionEvent.ACTION_DOWN: {
        float x = e.getX();
        startInThumb = x >= getWidth() - thumbWidthDragging - thumbMargin * 2;
        if (!startInThumb && state != STATE_DRAGGING) {
          return false;
        }
        touchStartY = e.getY();
        if (startInThumb) {
          state = STATE_DRAGGING;
          getParent().requestDisallowInterceptTouchEvent(true);
        }
        lastActivity = System.currentTimeMillis();
        return true;
      }
      case MotionEvent.ACTION_MOVE: {
        if (state == STATE_DRAGGING) {
          dragY = e.getY();
          float usable = getMeasuredHeight() - thumbHeight - thumbMargin * 2;
          float factor = usable > 0 ? (dragY - thumbMargin - (touchStartY - (thumbMargin + thumbFactor * usable))) / usable : 0f;
          if (!startInThumb) {
            factor = (dragY - thumbMargin) / usable;
          }
          applyThumbFactor(factor, true);
          lastActivity = System.currentTimeMillis();
          return true;
        }
        return false;
      }
      case MotionEvent.ACTION_CANCEL:
      case MotionEvent.ACTION_UP: {
        if (state == STATE_DRAGGING) {
          state = STATE_SHOWING;
          getParent().requestDisallowInterceptTouchEvent(false);
          removeCallbacks(fadeOutRunnable);
          postDelayed(fadeOutRunnable, FADE_OUT_DELAY);
        }
        return true;
      }
    }
    return super.onTouchEvent(e);
  }

  @Override
  protected void onDraw (@NonNull Canvas c) {
    if (thumbFactor <= 0f && state == STATE_IDLE) return;
    float factor = thumbAnimator.getFactor();
    if (factor <= 0f) return;
    int alpha = (int) (255f * factor * (state == STATE_DRAGGING ? 1f : 0.65f));

    int color = Theme.getColor(org.thunderdog.challegram.theme.ColorId.iconLight);

    thumbPaint.setColor(color);
    thumbPaint.setAlpha(alpha);

    float usable = getMeasuredHeight() - thumbHeight - thumbMargin * 2;
    float top = thumbMargin + thumbFactor * Math.max(0f, usable);
    float left = getMeasuredWidth() - thumbMargin - (state == STATE_DRAGGING ? thumbWidthDragging : thumbWidth);
    float right = getMeasuredWidth() - thumbMargin;
    float bottom = top + thumbHeight;
    float radius = (state == STATE_DRAGGING ? thumbWidthDragging : thumbWidth) / 2f;
    thumbRect.set(left, top, right, bottom);
    c.drawRoundRect(thumbRect, radius, radius, thumbPaint);

    // bubble
    if (state == STATE_DRAGGING && currentLabel != null && !currentLabel.isEmpty()) {
      bubblePaint.setColor(color);
      bubbleTextPaint.setColor(0xFF222222);
      bubbleTextPaint.setTextSize(Screen.dp(13f));
      float textWidth = bubbleTextPaint.measureText(currentLabel);
      float bubbleW = textWidth + Screen.dp(24f);
      float bubbleH = bubbleRadius * 2f;
      float bx = right - bubbleW - Screen.dp(10f);
      float by = top + thumbHeight / 2f - bubbleH / 2f;
      bubblePaint.setAlpha(255);
      c.drawRoundRect(bx, by, bx + bubbleW, by + bubbleH, bubbleRadius, bubbleRadius, bubblePaint);
      c.drawText(currentLabel, bx + Screen.dp(12f), by + bubbleH / 2f - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) / 2f, bubbleTextPaint);
    }
  }

  @Override
  public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
    invalidate();
    if (factor <= 0f && state == STATE_SHOWING) {
      setVisibility(GONE);
      state = STATE_IDLE;
    }
  }
}
