package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.gathin.tvhome.AppCatalog.Entry;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the most-used dock,
 * the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for a D-pad direction on a tile. Return true when handled. */
        boolean onTileKey(int position, int keyCode);
    }

    static final float FOCUS_SCALE = 1.08f;
    /** Cached interpolators: focus changes happen on every D-pad step, don't allocate there. */
    private static final Interpolator FOCUS_IN = new OvershootInterpolator(1.15f);
    private static final Interpolator FOCUS_OUT = new DecelerateInterpolator(2f);

    static int tileWidthPx(int innerWidth, int columns, float density) {
        int design = Math.round(180 * density);
        if (innerWidth <= 0) return design;
        int fit = (int) ((innerWidth / columns - 12 * density) / FOCUS_SCALE);
        return Math.max(1, Math.min(design, fit));
    }

    static int tileHeightPx(int width) {
        return Math.round(width * 101f / 180f);
    }

    static int itemHeightPx(int width, float density) {
        return tileHeightPx(width) + Math.round(45 * density);
    }

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    private final int libraryColumns;
    private boolean browsing;

    void setBrowsing(boolean browsing, RecyclerView grid) {
        this.browsing = browsing;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            int pos = grid.getChildAdapterPosition(child);
            child.setAlpha(browsing && pos >= 0 && pos < dockSize ? 0f : 1f);
        }
    }

    private final List<Entry> items = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, int dockSize, int libraryColumns) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.libraryColumns = libraryColumns;
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    /**
     * Applies a background-loaded snapshot on the UI thread.
     *
     * @return true when the list changed; false when the same apps are still installed, in which
     *         case the RecyclerView is left completely untouched (no rebind, no scroll, no
     *         entrance animation). This is what keeps Home still when returning from Settings.
     */
    boolean submit(List<Entry> fresh) {
        if (sameAs(fresh)) return false;

        // Diff instead of notifyDataSetChanged(): keeps existing holders and focus stable, and only
        // animates the rows that actually changed if the user installed or removed an app.
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return items.size();
            }

            @Override
            public int getNewListSize() {
                return fresh.size();
            }

            @Override
            public boolean areItemsTheSame(int oldPos, int newPos) {
                return items.get(oldPos).component.equals(fresh.get(newPos).component);
            }

            @Override
            public boolean areContentsTheSame(int oldPos, int newPos) {
                return sameContent(items.get(oldPos), fresh.get(newPos));
            }
        }, true);
        items.clear();
        items.addAll(fresh);
        diff.dispatchUpdatesTo(this);
        return true;
    }

    private boolean sameAs(List<Entry> fresh) {
        if (fresh.size() != items.size()) return false;
        for (int i = 0; i < fresh.size(); i++) {
            if (!sameContent(items.get(i), fresh.get(i))) return false;
        }
        return true;
    }

    private static boolean sameContent(Entry a, Entry b) {
        // Reused entries are unchanged; replacements must refresh artwork even if labels match.
        return a == b;
    }

    @Override
    public int getItemViewType(int position) {
        return position < dockSize ? 0 : 1;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float density = ctx.getResources().getDisplayMetrics().density;
        int inner = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        int width = tileWidthPx(inner, viewType == 0 ? dockSize : libraryColumns, density);
        int height = tileHeightPx(width);
        View card = v.findViewById(R.id.card);
        card.getLayoutParams().width = width;
        card.getLayoutParams().height = height;
        android.widget.FrameLayout.LayoutParams nameParams =
                (android.widget.FrameLayout.LayoutParams) v.findViewById(R.id.name).getLayoutParams();
        nameParams.width = width;
        nameParams.topMargin = height + Math.round(7 * density);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.itemView.setAlpha(browsing && position < dockSize ? 0f : 1f);
        h.card.setContentDescription(e.label);
        float density = ctx.getResources().getDisplayMetrics().density;
        h.card.animate().cancel();
        h.name.animate().cancel();
        h.name.setText(e.label);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0.65f);
        h.name.setTranslationY(0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        h.card.setTranslationZ(focused ? 14 * density : 0f);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        });
    }

    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
            android.content.SharedPreferences usage = ctx.getSharedPreferences("app_usage", Context.MODE_PRIVATE);
            long count = usage.getLong(e.pkg, 0L);
            usage.edit().putLong(e.pkg, count == Long.MAX_VALUE ? count : count + 1).apply();
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = density * 16f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_UP
                        && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    return true;
                }
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                return listener != null && listener.onTileKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                float lift = focused ? 14 * density : 0f;
                view.animate().cancel();
                view.animate().scaleX(s).scaleY(s).translationZ(lift)
                        .setDuration(focused ? 220 : 160)
                        .setInterpolator(focused ? FOCUS_IN : FOCUS_OUT)
                        .start();
                name.animate().cancel();
                name.animate().alpha(focused ? 1f : 0.65f)
                        .setDuration(180)
                        .setInterpolator(FOCUS_OUT)
                        .start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
