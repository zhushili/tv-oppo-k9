package com.gathin.tvhome;

import android.view.KeyEvent;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;


/** Explicit row/column navigation with focus restoration from Settings. */
final class FocusNavigator implements AppAdapter.TileListener {

    interface Host {
        void onTileFocused(int position, View card);

        void onEmptyFocus();

        boolean transitionTo(int position, Runnable focus);
    }

    private final Host host;
    private AppAdapter adapter;
    private RecyclerView grid;
    private View settings;
    private int focusRequest;
    private int columns;
    private int libraryColumns;

    private int lastDock;
    private String lastDockKey;

    void returnToDock() {
        int current = positionOf(lastDockKey);
        focus(current >= 0 && current < columns ? current : Math.min(lastDock, columns - 1));
    }

    private int lastFocused;
    private String lastFocusedKey;

    FocusNavigator(Host host) {
        this.host = host;
    }

    void attach(AppAdapter adapter, RecyclerView grid, int columns, int libraryColumns, View settings) {
        this.adapter = adapter;
        this.grid = grid;
        this.columns = columns;
        this.libraryColumns = libraryColumns;
        this.settings = settings;
    }

    int lastPosition() {
        return lastFocused;
    }

    String lastKey() {
        return lastFocusedKey;
    }

    int positionOf(String key) {
        return adapter == null ? -1 : adapter.positionOf(key);
    }

    void focus(int position) {
        int target = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        if (!host.transitionTo(target, () -> focusNow(target))) focusNow(target);
    }

    private void focusNow(int position) {
        int request = ++focusRequest;
        if (adapter == null || adapter.getItemCount() == 0) {
            host.onEmptyFocus();
            return;
        }
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            if (card.isFocused()) {
                onTileFocused(pos, card);
            } else {
                card.requestFocus();
            }
            return;
        }
        // Bring the target into the viewport immediately, then focus it on the next layout
        // pass. An instant jump keeps repeated D-pad presses responsive; the previous smooth
        // scroll left focus on the old tile until the animation had fully settled.
        grid.scrollToPosition(pos);
        grid.post(() -> {
            if (request != focusRequest || !grid.isAttachedToWindow()) return;
            RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
            if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
        });
    }

    @Override
    public void onTileFocused(int position, View card) {
        rememberTile(position);
        host.onTileFocused(position, card);
    }

    @Override
    public boolean onTileKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        if (count == 0) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (position < columns) {
                    if (position == 0) return true;
                    focus(position - 1);
                } else {
                    int rowStart = libraryRowStart(position);
                    if (position == rowStart) return true;
                    focus(position - 1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (position < columns) {
                    if (position == Math.min(columns, count) - 1) return true;
                    focus(position + 1);
                } else {
                    int rowEnd = Math.min(libraryRowStart(position) + libraryColumns - 1, count - 1);
                    if (position >= rowEnd) return true;
                    focus(position + 1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (position < columns) {
                    focusRequest++;
                    settings.requestFocus();
                } else if (position < columns + libraryColumns) {
                    returnToDock();
                } else {
                    focus(position - libraryColumns);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (position < columns) {
                    if (count > columns) {
                        focus(columns + Math.min(position, libraryColumns - 1));
                    }
                } else {
                    int next = position + libraryColumns;
                    if (next < count) focus(next);
                }
                return true;
            default:
                return false;
        }
    }

    boolean onTopKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (adapter.getItemCount() > 0) focus(lastFocused);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return false;
        }
    }

    private int libraryRowStart(int position) {
        return columns + ((position - columns) / libraryColumns) * libraryColumns;
    }

    private void rememberTile(int position) {
        if (position < columns) {
            lastDock = position;
            lastDockKey = adapter.keyAt(position);
        }
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
    }

}
