package com.hdlee.pdfnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements PdfPageView.Listener {
    private static final int OPEN_PDF = 10;
    private static final int EXPORT_JSON = 11;
    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private Uri documentUri;
    private String documentTitle = "PDF";
    private int currentPage;
    private PdfPageView pageView;
    private TextView titleView, pageLabel;
    private Button highlightButton, bookmarkButton;
    private AnnotationStore store;
    private int selectedColor = 0x66FFEB3B;
    private boolean highlightMode;
    private SharedPreferences recentPrefs;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new AnnotationStore(this);
        recentPrefs = getSharedPreferences("recent_documents", MODE_PRIVATE);
        buildUi();
        Uri incoming = getIntent().getData();
        if (incoming != null) openPdf(incoming);
        else showWelcome();
    }

    private Button button(String text, View.OnClickListener action) {
        Button b = new Button(this);
        b.setText(text); b.setAllCaps(false); b.setOnClickListener(action);
        b.setMinWidth(0); b.setMinimumWidth(0);
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(8, 4, 8, 4);
        header.setBackgroundColor(0xFF0D47A1);
        Button open = button("열기", v -> choosePdf());
        open.setTextColor(Color.WHITE); header.addView(open);
        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE); titleView.setTextSize(17); titleView.setSingleLine(true);
        titleView.setPadding(12, 0, 8, 0);
        header.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        Button menu = button("⋮", v -> showTools()); menu.setTextColor(Color.WHITE); header.addView(menu);
        root.addView(header);

        pageView = new PdfPageView(this, this);
        root.addView(pageView, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER); tools.setPadding(4, 3, 4, 3);
        tools.addView(button("◀", v -> showPage(currentPage - 1)));
        pageLabel = new TextView(this); pageLabel.setGravity(Gravity.CENTER); pageLabel.setTextSize(15);
        tools.addView(pageLabel, new LinearLayout.LayoutParams(0, -2, 1));
        tools.addView(button("▶", v -> showPage(currentPage + 1)));
        highlightButton = button("형광펜", v -> toggleHighlight()); tools.addView(highlightButton);
        tools.addView(button("색상", v -> chooseColor()));
        bookmarkButton = button("☆", v -> toggleBookmark()); tools.addView(bookmarkButton);
        root.addView(tools);
        setContentView(root);
    }

    private void showWelcome() {
        titleView.setText("PDF Note"); pageLabel.setText("PDF를 열어 시작하세요");
        String uri = recentPrefs.getString("last_uri", null);
        if (uri != null) new AlertDialog.Builder(this).setTitle("최근 문서")
                .setMessage(recentPrefs.getString("last_title", "최근 PDF") + "을 다시 여시겠습니까?")
                .setPositiveButton("열기", (d, w) -> openPdf(Uri.parse(uri)))
                .setNegativeButton("다른 문서", (d, w) -> choosePdf()).show();
    }

    private void choosePdf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/pdf");
        startActivityForResult(i, OPEN_PDF);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == OPEN_PDF) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { }
            openPdf(uri);
        } else if (request == EXPORT_JSON) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out != null) out.write(store.exportJson(documentUri, documentTitle).getBytes());
                toast("주석을 내보냈습니다");
            } catch (Exception e) { toast("내보내기 실패: " + e.getMessage()); }
        }
    }

    private void openPdf(Uri uri) {
        closePdf();
        try {
            descriptor = getContentResolver().openFileDescriptor(uri, "r");
            if (descriptor == null) throw new IOException("파일을 읽을 수 없습니다");
            renderer = new PdfRenderer(descriptor);
            documentUri = uri; documentTitle = queryName(uri);
            titleView.setText(documentTitle); store.open(uri);
            recentPrefs.edit().putString("last_uri", uri.toString()).putString("last_title", documentTitle).apply();
            showPage(0);
        } catch (Exception e) {
            closePdf(); toast("PDF 열기 실패: " + e.getMessage()); showWelcome();
        }
    }

    private String queryName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) { }
        return uri.getLastPathSegment() == null ? "PDF" : uri.getLastPathSegment();
    }

    private void showPage(int index) {
        if (renderer == null || index < 0 || index >= renderer.getPageCount()) return;
        currentPage = index;
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            int maxWidth = Math.max(1080, getResources().getDisplayMetrics().widthPixels * 2);
            float ratio = Math.min(2.5f, (float) maxWidth / page.getWidth());
            Bitmap image = Bitmap.createBitmap((int)(page.getWidth() * ratio), (int)(page.getHeight() * ratio), Bitmap.Config.ARGB_8888);
            image.eraseColor(Color.WHITE);
            Matrix renderScale = new Matrix();
            renderScale.postScale(ratio, ratio);
            page.render(image, null, renderScale, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            pageView.showPage(image, index, store.marks);
            pageLabel.setText((index + 1) + " / " + renderer.getPageCount());
            bookmarkButton.setText(store.bookmarks.contains(index) ? "★" : "☆");
        }
    }

    private void toggleHighlight() {
        if (renderer == null) return;
        highlightMode = !highlightMode;
        highlightButton.setText(highlightMode ? "형광펜 ✓" : "형광펜");
        pageView.setHighlightMode(highlightMode, selectedColor);
        toast(highlightMode ? "강조할 영역을 손가락으로 드래그하세요" : "형광펜을 종료했습니다");
    }

    private void chooseColor() {
        String[] names = {"노랑", "초록", "분홍", "파랑"};
        int[] colors = {0x66FFEB3B, 0x6666BB6A, 0x66EC407A, 0x6642A5F5};
        new AlertDialog.Builder(this).setTitle("형광펜 색상").setItems(names, (d, which) -> {
            selectedColor = colors[which]; pageView.setHighlightMode(highlightMode, selectedColor);
        }).show();
    }

    private void toggleBookmark() {
        if (renderer == null) return;
        if (!store.bookmarks.add(currentPage)) store.bookmarks.remove(currentPage);
        store.save(); bookmarkButton.setText(store.bookmarks.contains(currentPage) ? "★" : "☆");
    }

    @Override public void onHighlightCreated(AnnotationStore.Mark mark) {
        store.marks.add(mark); store.save(); pageView.invalidate();
        new AlertDialog.Builder(this).setMessage("메모도 추가하시겠습니까?")
                .setPositiveButton("메모 추가", (d, w) -> editMark(mark))
                .setNegativeButton("나중에", null).show();
    }

    @Override public void onMarkTapped(AnnotationStore.Mark mark) { editMark(mark); }

    private void editMark(AnnotationStore.Mark mark) {
        EditText input = new EditText(this); input.setHint("메모를 입력하세요"); input.setText(mark.note);
        input.setPadding(40, 20, 40, 20);
        new AlertDialog.Builder(this).setTitle("페이지 " + (mark.page + 1) + " 메모").setView(input)
                .setPositiveButton("저장", (d, w) -> { mark.note = input.getText().toString().trim(); store.save(); pageView.invalidate(); })
                .setNeutralButton("강조 삭제", (d, w) -> { store.marks.remove(mark); store.save(); pageView.invalidate(); })
                .setNegativeButton("취소", null).show();
    }

    private void showTools() {
        String[] tools = {"메모·형광펜 목록", "즐겨찾기 목록", "페이지로 이동", "주석 백업(JSON)", "사용법"};
        new AlertDialog.Builder(this).setTitle("도구").setItems(tools, (d, which) -> {
            if (which == 0) showMarkList();
            else if (which == 1) showBookmarks();
            else if (which == 2) goToPage();
            else if (which == 3) exportAnnotations();
            else showHelp();
        }).show();
    }

    private void showMarkList() {
        List<AnnotationStore.Mark> items = new ArrayList<>(store.marks);
        if (items.isEmpty()) { toast("저장된 형광펜이나 메모가 없습니다"); return; }
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) labels[i] = "p." + (items.get(i).page + 1) + "  " + (items.get(i).note.isEmpty() ? "(메모 없음)" : items.get(i).note);
        new AlertDialog.Builder(this).setTitle("메모·형광펜").setItems(labels, (d, i) -> { showPage(items.get(i).page); editMark(items.get(i)); }).show();
    }

    private void showBookmarks() {
        if (store.bookmarks.isEmpty()) { toast("즐겨찾기한 페이지가 없습니다"); return; }
        List<Integer> pages = new ArrayList<>(store.bookmarks); java.util.Collections.sort(pages);
        String[] labels = new String[pages.size()];
        for (int i = 0; i < pages.size(); i++) labels[i] = "페이지 " + (pages.get(i) + 1);
        new AlertDialog.Builder(this).setTitle("즐겨찾기").setItems(labels, (d, i) -> showPage(pages.get(i))).show();
    }

    private void goToPage() {
        if (renderer == null) return;
        EditText input = new EditText(this); input.setInputType(2); input.setHint("1 ~ " + renderer.getPageCount());
        new AlertDialog.Builder(this).setTitle("페이지로 이동").setView(input).setPositiveButton("이동", (d, w) -> {
            try { showPage(Integer.parseInt(input.getText().toString()) - 1); } catch (Exception ignored) { toast("올바른 페이지를 입력하세요"); }
        }).setNegativeButton("취소", null).show();
    }

    private void exportAnnotations() {
        if (documentUri == null) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT); i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, documentTitle.replaceAll("(?i)\\.pdf$", "") + "_annotations.json");
        startActivityForResult(i, EXPORT_JSON);
    }

    private void showHelp() {
        new AlertDialog.Builder(this).setTitle("PDF Note 사용법").setMessage(
                "• 열기: 기기에서 PDF 선택\n• 형광펜: 버튼을 누르고 영역 드래그\n• 메모: 형광펜 영역을 탭\n• 확대: 두 손가락으로 핀치\n• 즐겨찾기: 별 버튼\n• 목록·이동·백업: 오른쪽 위 메뉴\n\n주석은 원본 PDF를 변경하지 않고 앱에 자동 저장됩니다.")
                .setPositiveButton("확인", null).show();
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private void closePdf() {
        if (renderer != null) { renderer.close(); renderer = null; }
        if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
        descriptor = null;
    }

    @Override protected void onDestroy() { closePdf(); super.onDestroy(); }
}
