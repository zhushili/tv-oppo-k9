package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Build;
import android.util.Log;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * tvOS style home: top shelf, a pinned dock and the app grid. The focus engine lives in
 * {@link FocusNavigator}; menus and the top-shelf animation are separate controllers.
 */
public class MainActivity extends Activity implements FocusNavigator.Host {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    static final int LIBRARY_COLUMNS = 6;
    private static final long MINUTE_MS = 60_000L;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private HeroController hero;
    private TextView greeting;
    private View btnSettings;
    private DockDecoration dockDecoration;
    private View libraryHeader;
    private View dockHint;
    private TextView appCount;
    private boolean browsing;
    private boolean transitioning;
    private int libraryTop;
    private boolean firstLoadDone;
    private int navigationVersion;
    private final ExecutorService appWorker = Executors.newSingleThreadExecutor();
    private AppRefresh<List<AppCatalog.Entry>> appRefresh;
    private boolean receiverRegistered;
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            String pkg = intent.getData() == null ? null : intent.getData().getSchemeSpecificPart();
            appRefresh.invalidate(pkg);
        }
    };
    private FocusNavigator navigator;
    private MenuController menus;
    private float density;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, Math.min(8, getResources().getInteger(R.integer.columns)));

        root = findViewById(R.id.root);
        hero = new HeroController(findViewById(R.id.heroName), findViewById(R.id.ambient), density);
        greeting = findViewById(R.id.greeting);
        btnSettings = findViewById(R.id.btnSettings);
        libraryHeader = findViewById(R.id.libraryHeader);
        dockHint = findViewById(R.id.dockHint);
        appCount = findViewById(R.id.appCount);
        libraryTop = getResources().getDimensionPixelSize(R.dimen.library_header_height)
                + Math.round(16 * density);

        grid = findViewById(R.id.grid);
        int spanTotal = COLUMNS * LIBRARY_COLUMNS / gcd(COLUMNS, LIBRARY_COLUMNS);
        GridLayoutManager layout = new GridLayoutManager(this, spanTotal) {
            @Override
            public boolean onRequestChildFocus(RecyclerView parent, RecyclerView.State state,
                                               View child, View focused) {
                // FocusNavigator owns scrolling; RecyclerView must not start a competing scroll.
                return true;
            }
        };
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position < COLUMNS ? spanTotal / COLUMNS : spanTotal / LIBRARY_COLUMNS;
            }
        });
        grid.setLayoutManager(layout);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        dockDecoration = new DockDecoration(COLUMNS, LIBRARY_COLUMNS, density);
        grid.addItemDecoration(dockDecoration);
        navigator = new FocusNavigator(this);
        adapter = new AppAdapter(this, navigator, COLUMNS, LIBRARY_COLUMNS);
        grid.setAdapter(adapter);
        AppCatalog catalog = new AppCatalog(getApplicationContext());
        appRefresh = new AppRefresh<>(appWorker, handler::post, catalog::load, this::applyApps,
                error -> Log.w("TvHome", "Could not refresh apps", error));
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        // Keep listening while another app is open; defer loading until Home is visible.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, packages, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packages);
        }
        receiverRegistered = true;

        // Put the shelf space in the first row decoration, not viewport padding.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int inner = r - l - grid.getPaddingLeft() - grid.getPaddingRight();
            int width = AppAdapter.tileWidthPx(inner, COLUMNS, density);
            int dockRow = AppAdapter.itemHeightPx(width, density) + Math.round(18 * density);
            int space = Math.max(0, b - t - grid.getPaddingTop()
                    - grid.getPaddingBottom() - dockRow);
            boolean changed = dockDecoration.setShelfSpace(space);
            int libraryWidth = AppAdapter.tileWidthPx(inner, LIBRARY_COLUMNS, density);
            int tail = Math.max(0, b - t - libraryTop - grid.getPaddingBottom()
                    - AppAdapter.itemHeightPx(libraryWidth, density));
            changed |= dockDecoration.setTailSpace(tail);
            if (changed) grid.invalidateItemDecorations();
        });

        menus = new MenuController(this, root, density, this::open);
        btnSettings.setOnClickListener(v -> menus.showSettingsMenu());

        btnSettings.setOnKeyListener((v, keyCode, event) -> {
            boolean direction = keyCode == KeyEvent.KEYCODE_DPAD_UP
                    || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (!direction) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) navigator.onTopKey(keyCode);
            return true;
        });
        navigator.attach(adapter, grid, COLUMNS, LIBRARY_COLUMNS, btnSettings);
    }

    @Override
    protected void onResume() {
        super.onResume();
        appRefresh.refresh();
        appRefresh.resume();
        if (firstLoadDone && getCurrentFocus() == null) restoreFocus();
        updateGreeting();
        handler.postDelayed(minuteTick, MINUTE_MS);
    }

    private void applyApps(List<AppCatalog.Entry> apps) {
        boolean firstLoad = !firstLoadDone;
        int navigation = navigationVersion;
        // Snapshot focus before DiffUtil rebinding can change the remembered app.
        String key = navigator.lastKey();
        int slot = navigator.lastPosition();
        View focused = getCurrentFocus();
        boolean restore = (firstLoad || grid.hasFocus() || focused == null
                || focused.getId() == R.id.card) && (menus == null || !menus.isOpen());
        boolean changed = adapter.submit(apps);
        firstLoadDone = true;
        updateLibraryChrome();
        if (!changed && !firstLoad) return;
        if (navigator.positionOf(key) < 0) hero.hide();
        grid.post(() -> {
            if (isDestroyed() || isFinishing() || !appRefresh.isActive()
                    || navigation != navigationVersion) return;
            int position = navigator.positionOf(key);
            // `navigation` above guarantees no key-driven focus change happened while this
            // refresh was pending; restoring also covers focus that the framework moved to a
            // pill when the focused app disappeared.
            if (restore && (menus == null || !menus.isOpen())) {
                navigator.focus(position >= 0 ? position : slot);
            }
        });
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (appRefresh != null) appRefresh.invalidate(null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        appRefresh.pause();
        grid.animate().cancel();
        libraryHeader.animate().cancel();
        dockHint.animate().cancel();
        transitioning = false;
        grid.setAlpha(1f);
        libraryHeader.setAlpha(1f);
        dockHint.setAlpha(1f);
        handler.removeCallbacks(minuteTick);
        if (menus != null) menus.dismiss();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (receiverRegistered) {
            unregisterReceiver(packageReceiver);
            receiverRegistered = false;
        }
        appRefresh.close();
        appWorker.shutdownNow();
        if (menus != null) menus.dismiss();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (transitioning && (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_BACK)) return true;
        if (event.getAction() == KeyEvent.ACTION_DOWN) navigationVersion++;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (browsing) navigator.returnToDock();
    }

    /**
     * Repairs focus after a resume without disturbing a valid state. Focus on a top-bar pill is
     * kept (the user came back from Settings opened there); focus on a tile is kept unless the app
     * list changed underneath it.
     */
    private void restoreFocus() {
        View focused = getCurrentFocus();
        if (focused != null && focused == btnSettings) return;
        if (focused != null && focused.getId() == R.id.card
                && focused.isAttachedToWindow()) {
            return;
        }
        int pos = navigator.positionOf(navigator.lastKey());
        navigator.focus(pos >= 0 ? pos : navigator.lastPosition());
    }

    // ---- focus -----------------------------------------------------------------------------------

    @Override
    public boolean transitionTo(int position, Runnable focus) {
        if (transitioning) return true;
        if (browsing == (position >= COLUMNS) || adapter.getItemCount() == 0) return false;
        transitioning = true;
        grid.stopScroll();
        hero.hide();
        libraryHeader.animate().alpha(0f).setDuration(90).start();
        dockHint.animate().alpha(0f).setDuration(90).start();
        // Change scroll position only while invisible, then reveal the settled destination.
        grid.animate().alpha(0f).setDuration(90).withEndAction(() -> {
            focus.run();
            grid.postOnAnimation(() -> {
                if (!appRefresh.isActive()) return;
                libraryHeader.animate().alpha(1f).setDuration(160).start();
                dockHint.animate().alpha(1f).setDuration(160).start();
                grid.animate().alpha(1f).setDuration(160)
                        .withEndAction(() -> transitioning = false).start();
            });
        }).start();
        return true;
    }

    @Override
    public void onTileFocused(int position, View card) {
        boolean nextBrowsing = position >= COLUMNS;
        grid.stopScroll();
        if (browsing != nextBrowsing) {
            browsing = nextBrowsing;
            adapter.setBrowsing(browsing, grid);
            dockDecoration.setBrowsing(browsing);
            updateLibraryChrome();
            grid.invalidate();

        }
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - Math.round(6 * density) - dockDecoration.shelfSpace();
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.scrollBy(0, dy);
            hero.show(adapter.labelAt(position), adapter.ambientColorAt(position), r.top - dy);
        } else {
            hero.hide();
            // Keep the library below its fixed heading, including focus-scale clearance.
            int top = item.getTop();
            int bottom = top + item.getHeight();
            int viewportBottom = grid.getHeight() - grid.getPaddingBottom();
            int dy = position < COLUMNS * 2 || top < libraryTop ? top - libraryTop
                    : Math.max(0, bottom - viewportBottom);
            if (dy != 0) grid.scrollBy(0, dy);
        }
    }

    @Override
    public void onEmptyFocus() {
        hero.hide();
        btnSettings.requestFocus();
    }

    private void updateLibraryChrome() {
        libraryHeader.setVisibility(browsing ? View.VISIBLE : View.GONE);
        dockHint.setVisibility(!browsing && adapter.getItemCount() > COLUMNS ? View.VISIBLE : View.GONE);
        int count = Math.max(0, adapter.getItemCount() - COLUMNS);
        appCount.setText(getResources().getQuantityString(R.plurals.app_count, count, count));
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return Math.max(1, a);
    }

}
