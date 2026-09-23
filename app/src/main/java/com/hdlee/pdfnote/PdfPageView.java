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
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;
    private Bitmap bitmap;
    private List<AnnotationStore.Mark> marks;
    private int page;
    private boolean highlightMode;
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
        highlightColor = color;
        invalidate();
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
            paint.setColor(m.color);
            canvas.drawRect(dest.left + m.left * dest.width(), dest.top + m.top * dest.height(),
                    dest.left + m.right * dest.width(), dest.top + m.bottom * dest.height(), paint);
            if (m.note != null && !m.note.isEmpty()) {
                paint.setColor(0xFF1565C0);
                canvas.drawCircle(dest.left + m.right * dest.width(), dest.top + m.top * dest.height(), 9f, paint);
            }
        }
        if (drawing) {
            paint.setColor(highlightColor);
            canvas.drawRect(Math.min(startX, currentX), Math.min(startY, currentY),
                    Math.max(startX, currentX), Math.max(startY, currentY), paint);
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
                if (Math.abs(currentX - startX) > 12 && Math.abs(currentY - startY) > 8) {
                    AnnotationStore.Mark m = new AnnotationStore.Mark();
                    m.page = page;
                    m.left = (Math.min(startX, currentX) - dest.left) / dest.width();
                    m.top = (Math.min(startY, currentY) - dest.top) / dest.height();
                    m.right = (Math.max(startX, currentX) - dest.left) / dest.width();
                    m.bottom = (Math.max(startY, currentY) - dest.top) / dest.height();
                    m.color = highlightColor;
                    listener.onHighlightCreated(m);
                }
                drawing = false; invalidate(); return true;
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
