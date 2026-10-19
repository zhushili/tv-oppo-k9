package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the tvOS style lighter band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private int shelfSpace;
    private int tailSpace;
    private boolean browsing;

    void setBrowsing(boolean browsing) { this.browsing = browsing; }

    boolean setTailSpace(int space) {
        if (tailSpace == space) return false;
        tailSpace = space;
        return true;
    }

    boolean setShelfSpace(int space) {
        if (shelfSpace == space) return false;
        shelfSpace = space;
        return true;
    }

    int shelfSpace() { return shelfSpace; }

    private final int dockSize;
    private final int libraryColumns;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    DockDecoration(int dockSize, int libraryColumns, float density) {
        this.dockSize = dockSize;
        this.libraryColumns = libraryColumns;
        this.density = density;
        band.setColor(0xFF141416);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(0x1FFFFFFF);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = shelfSpace + Math.round(6 * density);
            outRect.bottom = Math.round(12 * density) + parent.getPaddingBottom();
        } else if (pos >= dockSize) {
            int lastLibrary = Math.max(0, state.getItemCount() - 1 - dockSize);
            if ((pos - dockSize) / libraryColumns == lastLibrary / libraryColumns) {
                // Allow even a single library row to align below its heading.
                outRect.bottom = tailSpace;
            }
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (browsing) return;
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        float pad = 14 * density;
        rect.inset(-pad, -pad);
        float radius = 26 * density;
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
