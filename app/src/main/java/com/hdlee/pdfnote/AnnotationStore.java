package com.hdlee.pdfnote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class AnnotationStore {
    static final class Mark {
        int page;
        float left, top, right, bottom;
        int color;
        String note;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("page", page).put("left", left).put("top", top)
                    .put("right", right).put("bottom", bottom)
                    .put("color", color).put("note", note == null ? "" : note);
            return o;
        }

        static Mark fromJson(JSONObject o) {
            Mark m = new Mark();
            m.page = o.optInt("page");
            m.left = (float) o.optDouble("left");
            m.top = (float) o.optDouble("top");
            m.right = (float) o.optDouble("right");
            m.bottom = (float) o.optDouble("bottom");
            m.color = o.optInt("color", 0x66FFEB3B);
            m.note = o.optString("note", "");
            return m;
        }
    }

    private final SharedPreferences prefs;
    private String key;
    final List<Mark> marks = new ArrayList<>();
    final Set<Integer> bookmarks = new HashSet<>();

    AnnotationStore(Context context) {
        prefs = context.getSharedPreferences("pdf_note_data", Context.MODE_PRIVATE);
    }

    void open(Uri uri) {
        key = "doc_" + sha256(uri.toString());
        marks.clear();
        bookmarks.clear();
        try {
            JSONObject root = new JSONObject(prefs.getString(key, "{}"));
            JSONArray a = root.optJSONArray("marks");
            if (a != null) for (int i = 0; i < a.length(); i++) marks.add(Mark.fromJson(a.getJSONObject(i)));
            JSONArray b = root.optJSONArray("bookmarks");
            if (b != null) for (int i = 0; i < b.length(); i++) bookmarks.add(b.getInt(i));
        } catch (JSONException ignored) { }
    }

    void save() {
        if (key == null) return;
        try {
            JSONObject root = new JSONObject();
            JSONArray a = new JSONArray();
            for (Mark m : marks) a.put(m.toJson());
            JSONArray b = new JSONArray();
            for (int page : bookmarks) b.put(page);
            root.put("marks", a).put("bookmarks", b);
            prefs.edit().putString(key, root.toString()).apply();
        } catch (JSONException ignored) { }
    }

    String exportJson(Uri uri, String title) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("format", "PDF Note annotations v1");
        root.put("document", title);
        root.put("uri", uri.toString());
        JSONArray a = new JSONArray();
        for (Mark m : marks) a.put(m.toJson());
        JSONArray b = new JSONArray();
        for (int page : bookmarks) b.put(page);
        root.put("marks", a).put("bookmarks", b);
        return root.toString(2);
    }

    private static String sha256(String input) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
