package com.hdlee.pdfnote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.List;

final class PdfPageView extends View {
    interface Listener {
        void onHighlightCreated(AnnotationStore.Mark mark);
        void onMarkTapped(AnnotationStore.Mark mark);
        void onMemoPointRequested(int page, float x, float y);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;
    private Bitmap bitmap;
    private List<AnnotationStore.Mark> marks;
    private int page;
    private boolean highlightMode;
    private boolean memoMode;
    private int highlightColor = 0x66FFEB3B;
    private float startX, startY, currentX, currentY;
    private boolean drawing;
    private float scale = 1f;
    private final Listener listener;

    PdfPageView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setBackgroundColor(0xFFDDDDDD);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                scale = Math.max(1f, Math.min(4f, scale * detector.getScaleFactor()));
                invalidate();
                return true;
            }
        });
    }

    void showPage(Bitmap pageBitmap, int pageNumber, List<AnnotationStore.Mark> allMarks) {
        if (bitmap != null && bitmap != pageBitmap) bitmap.recycle();
        bitmap = pageBitmap;
        page = pageNumber;
        marks = allMarks;
        scale = 1f;
        invalidate();
    }

    void setHighlightMode(boolean enabled, int color) {
        highlightMode = enabled;
        if (enabled) memoMode = false;
        highlightColor = color;
        invalidate();
    }

    void setMemoMode(boolean enabled) {
        memoMode = enabled;
        if (enabled) highlightMode = false;
        invalidate();
    }

    private float highlightHeight(RectF dest) {
        return Math.max(12f, dest.height() * 0.022f);
    }

    private RectF contentRect() {
        if (bitmap == null) return new RectF();
        float base = Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
        float w = bitmap.getWidth() * base * scale;
        float h = bitmap.getHeight() * base * scale;
        return new RectF((getWidth() - w) / 2f, (getHeight() - h) / 2f,
                (getWidth() + w) / 2f, (getHeight() + h) / 2f);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) return;
        RectF dest = contentRect();
        paint.setColor(Color.WHITE);
        canvas.drawRect(dest, paint);
        canvas.drawBitmap(bitmap, null, dest, paint);
        if (marks != null) for (AnnotationStore.Mark m : marks) if (m.page == page) {
            if (!m.noteOnly) {
                paint.setColor(m.color);
                canvas.drawRect(dest.left + m.left * dest.width(), dest.top + m.top * dest.height(),
                        dest.left + m.right * dest.width(), dest.top + m.bottom * dest.height(), paint);
            }
            if (m.noteOnly || (m.note != null && !m.note.isEmpty())) {
                paint.setColor(0xFF1565C0);
                float cx = dest.left + m.right * dest.width();
                float cy = dest.top + m.top * dest.height();
                canvas.drawCircle(cx, cy, 12f, paint);
                paint.setColor(Color.WHITE); paint.setStrokeWidth(2f);
                canvas.drawLine(cx - 5f, cy - 3f, cx + 5f, cy - 3f, paint);
                canvas.drawLine(cx - 5f, cy + 2f, cx + 2f, cy + 2f, paint);
            }
        }
        if (drawing) {
            paint.setColor(highlightColor);
            float centerY = (startY + currentY) / 2f;
            float half = highlightHeight(dest) / 2f;
            canvas.drawRect(Math.min(startX, currentX), centerY - half,
                    Math.max(startX, currentX), centerY + half, paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        if (scaleDetector.isInProgress()) return true;
        RectF dest = contentRect();
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            startX = currentX = e.getX(); startY = currentY = e.getY();
            drawing = highlightMode && dest.contains(startX, startY);
            invalidate(); return true;
        }
        if (e.getAction() == MotionEvent.ACTION_MOVE && drawing) {
            currentX = e.getX(); currentY = e.getY(); invalidate(); return true;
        }
        if (e.getAction() == MotionEvent.ACTION_UP) {
            if (drawing) {
                currentX = Math.max(dest.left, Math.min(dest.right, e.getX()));
                currentY = Math.max(dest.top, Math.min(dest.bottom, e.getY()));
                if (Math.abs(currentX - startX) > 12) {
                    AnnotationStore.Mark m = new AnnotationStore.Mark();
                    m.page = page;
                    m.left = (Math.min(startX, currentX) - dest.left) / dest.width();
                    m.right = (Math.max(startX, currentX) - dest.left) / dest.width();
                    float centerY = (startY + currentY) / 2f;
                    float half = highlightHeight(dest) / 2f;
                    m.top = (Math.max(dest.top, centerY - half) - dest.top) / dest.height();
                    m.bottom = (Math.min(dest.bottom, centerY + half) - dest.top) / dest.height();
                    m.color = highlightColor;
                    listener.onHighlightCreated(m);
                }
                drawing = false; invalidate(); return true;
            }
            if (Math.hypot(e.getX() - startX, e.getY() - startY) < 20 && memoMode && dest.contains(e.getX(), e.getY())) {
                listener.onMemoPointRequested(page, (e.getX() - dest.left) / dest.width(),
                        (e.getY() - dest.top) / dest.height());
                return true;
            }
            if (Math.hypot(e.getX() - startX, e.getY() - startY) < 20 && marks != null) {
                float nx = (e.getX() - dest.left) / dest.width();
                float ny = (e.getY() - dest.top) / dest.height();
                for (int i = marks.size() - 1; i >= 0; i--) {
                    AnnotationStore.Mark m = marks.get(i);
                    if (m.page == page && nx >= m.left && nx <= m.right && ny >= m.top && ny <= m.bottom) {
                        listener.onMarkTapped(m); return true;
                    }
                }
            }
        }
        return true;
    }
}
