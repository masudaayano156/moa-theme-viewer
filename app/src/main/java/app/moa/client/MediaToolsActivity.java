package app.moa.client;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Local photo editing and image-sequence GIF creation. This activity never uploads. */
public final class MediaToolsActivity extends Activity {
    public static final String EXTRA_STANDALONE = "moa.media.standalone";
    public static final String PROVIDER_SUFFIX = ".editedmedia";
    private static final String EXTRA_INPUT = "moa.media.input", EXTRA_OUTPUT = "moa.media.output", EXTRA_GIF = "moa.media.allowGif";
    private static final int PICK_IMAGES = 5101, SAVE_DOCUMENT = 5102;
    private final ArrayList<Uri> sources = new ArrayList<>();
    private final ArrayList<MediaImageOps.Edit> edits = new ArrayList<>();
    private final ArrayList<View> controls = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile int generation;
    private volatile boolean destroyed;
    private Future<?> task;
    private boolean standalone, allowGif, busy, updating, pickerOpened;
    private int selected, formatIndex, delayIndex = 2;
    private boolean loop = true;
    private Bitmap previewBitmap;
    private ImageView preview;
    private TextView status, imageNumber;
    private Spinner crop, size, format, delay;
    private Button previous, next, earlier, later, rotate, reset, photoOutput, gifOutput;
    private ThemeSpec.Palette colors;
    private File pendingOutput;
    private String pendingMime;

    public static Intent createIntent(Context context, ArrayList<Uri> images, boolean allowGif) {
        Intent intent = new Intent(context, MediaToolsActivity.class).putExtra(EXTRA_GIF, allowGif);
        ArrayList<Uri> accepted = new ArrayList<>();
        if (images != null && images.size() <= MediaImageOps.MAX_SELECTION) {
            for (Uri uri : images) {
                if (!MediaImageOps.validInput(uri)) { accepted.clear(); break; }
                accepted.add(uri);
            }
        }
        intent.putParcelableArrayListExtra(EXTRA_INPUT, accepted);
        if (!accepted.isEmpty()) {
            ClipData data = ClipData.newRawUri("선택한 사진", accepted.get(0));
            for (int i = 1; i < accepted.size(); i++) data.addItem(new ClipData.Item(accepted.get(i)));
            intent.setClipData(data); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        return intent;
    }

    public static ArrayList<Uri> outputUris(Intent result) {
        ArrayList<Uri> empty = new ArrayList<>();
        if (result == null) return empty;
        try {
            ArrayList<Uri> values = result.getParcelableArrayListExtra(EXTRA_OUTPUT);
            if (values == null || values.isEmpty() || values.size() > MediaImageOps.MAX_SELECTION) return empty;
            for (Uri uri : values) if (!validOutputUri(uri)) return empty;
            return new ArrayList<>(values);
        } catch (RuntimeException exception) { return empty; }
    }

    static boolean validOutputUri(Uri uri) {
        return uri != null && "content".equals(uri.getScheme()) &&
                "app.moa.client.editedmedia".equals(uri.getAuthority()) && uri.getQuery() == null && uri.getFragment() == null &&
                uri.getPath() != null && uri.getPath().matches("/edited_media/moa-[a-f0-9-]{36}\\.(jpg|png|gif)") &&
                uri.getEncodedPath().equals(uri.getPath());
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); setResult(RESULT_CANCELED);
        standalone = getIntent().getBooleanExtra(EXTRA_STANDALONE, false);
        allowGif = getIntent().getBooleanExtra(EXTRA_GIF, true);
        SharedPreferences preferences = getSharedPreferences("moa_preferences", MODE_PRIVATE);
        colors = ThemeSpec.fromId(preferences.getString("theme", "mail")).palette(preferences.getBoolean("dark", false));
        if (CommunityOptions.accentIndex(this)>=0) colors=colors.withAccent(CommunityOptions.accentColor(this,colors.accent));
        ArrayList<Uri> initial = null;
        try { initial = state == null ? getIntent().getParcelableArrayListExtra(EXTRA_INPUT) : state.getParcelableArrayList("images"); }
        catch (RuntimeException ignored) { }
        if (initial != null && initial.size() <= MediaImageOps.MAX_SELECTION) {
            for (Uri uri : initial) { if (!MediaImageOps.validInput(uri)) { sources.clear(); break; } sources.add(uri); }
        }
        int[] saved = state == null ? null : state.getIntArray("edits");
        for (int i = 0; i < sources.size(); i++) edits.add(saved != null && saved.length == sources.size() * 3 ?
                new MediaImageOps.Edit(saved[i * 3], saved[i * 3 + 1], saved[i * 3 + 2]) : new MediaImageOps.Edit(0, 0, 1920));
        if (state != null) {
            selected = Math.max(0, Math.min(sources.size() - 1, state.getInt("selected")));
            formatIndex = state.getInt("format") == 1 ? 1 : 0;
            delayIndex = Math.max(0, Math.min(4, state.getInt("delay", 2))); loop = state.getBoolean("loop", true);
            pickerOpened = state.getBoolean("pickerOpened");
            String pending = state.getString("pendingOutput");
            if (pending != null) { File file = new File(pending); if (MediaImageOps.ownedFile(this, file) && file.isFile()) { pendingOutput = file; pendingMime = state.getString("pendingMime"); } }
        }
        buildUi(); MediaImageOps.cleanup(this); refreshControls();
        if (sources.isEmpty() && !pickerOpened) { pickerOpened = true; chooseImages(); }
        else if (!sources.isEmpty()) renderPreview();
        if (state != null && state.getBoolean("busy")) status.setText("화면이 바뀌어 처리를 멈췄어요. 설정을 확인하고 다시 실행해주세요.");
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::finish);
    }

    private void buildUi() {
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(colors.background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) { android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()); root.setPadding(bars.left, bars.top, bars.right, bars.bottom); }
            else root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(16), dp(12), dp(16), dp(24));
        body.addView(label("사진 편집 · 움짤 만들기", 23, true));
        body.addView(label("선택한 사진만 기기에서 처리합니다. 원본은 유지하고 새로운 파일을 만듭니다. 사진당 20MB, 최대 20장까지 선택할 수 있어요.", 14, false));
        body.addView(button("사진 선택 / 다시 선택", this::chooseImages));
        preview = new ImageView(this); preview.setScaleType(ImageView.ScaleType.FIT_CENTER); preview.setBackgroundColor(colors.tint); preview.setContentDescription("현재 사진의 편집 미리보기");
        int height = Math.max(160, Math.min(360, getResources().getConfiguration().screenHeightDp / 3));
        body.addView(preview, new LinearLayout.LayoutParams(-1, dp(height)));
        imageNumber = label("선택한 사진이 없어요", 15, true); imageNumber.setGravity(Gravity.CENTER); body.addView(imageNumber);
        previous = button("이전 사진", () -> select(selected - 1)); next = button("다음 사진", () -> select(selected + 1)); body.addView(row(previous, next));
        earlier = button("순서를 앞으로", () -> move(-1)); later = button("순서를 뒤로", () -> move(1)); body.addView(row(earlier, later));
        rotate = button("90° 회전", () -> { MediaImageOps.Edit edit = edits.get(selected); edits.set(selected, new MediaImageOps.Edit(edit.turns + 1, edit.crop, edit.edge)); renderPreview(); });
        reset = button("현재 사진 초기화", () -> { edits.set(selected, new MediaImageOps.Edit(0, 0, 1920)); refreshControls(); renderPreview(); });
        body.addView(row(rotate, reset));
        body.addView(label("자르기 · 현재 사진의 가운데를 기준으로 잘라요", 14, true));
        crop = spinner(new String[]{"원래 비율", "정사각형 1:1", "가로형 16:9"}, position -> updateEdit(position, -1)); body.addView(crop);
        body.addView(label("저장 크기 · 긴 변 기준, 작은 원본은 확대하지 않아요", 14, true));
        size = spinner(new String[]{"1280px", "1920px"}, position -> updateEdit(-1, position == 0 ? 1280 : 1920)); body.addView(size);
        body.addView(label("사진 파일 형식", 14, true));
        format = spinner(new String[]{"JPEG · 용량 절약", "PNG · 투명 영역 보존"}, position -> formatIndex = position); body.addView(format);
        photoOutput = button(standalone ? "현재 사진을 새 파일로 저장" : "편집한 사진 모두 사용", () -> export(false)); body.addView(photoOutput);
        if (allowGif) {
            body.addView(label("움짤 · 사진 순서대로 재생", 19, true));
            body.addView(label("사진 2~20장을 사용합니다. 최대 640px, 256색으로 변환하므로 사진과 색감이 다를 수 있어요. 크기가 다른 사진에는 여백이 생길 수 있습니다.", 14, false));
            delay = spinner(new String[]{"0.1초 간격", "0.2초 간격", "0.4초 간격", "0.8초 간격", "1.2초 간격"}, position -> delayIndex = position); body.addView(delay);
            CheckBox repeat = new CheckBox(this); repeat.setText("계속 반복 재생"); repeat.setTextColor(colors.text); repeat.setButtonTintList(ColorStateList.valueOf(colors.accent)); repeat.setChecked(loop);
            repeat.setOnCheckedChangeListener((button, checked) -> loop = checked); controls.add(repeat); body.addView(repeat);
            gifOutput = button(standalone ? "움짤을 새 파일로 저장" : "움짤 만들어 사용", () -> export(true)); body.addView(gifOutput);
        }
        status = label("사진을 선택한 뒤 자르기와 크기를 조절해주세요.", 14, false); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); body.addView(status);
        Button close = button("닫기", this::finish); controls.remove(close); body.addView(close);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2)); root.addView(scroll, new LinearLayout.LayoutParams(-1, -1)); setContentView(root);
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView label = new TextView(this); label.setText(text); label.setTextColor(bold ? colors.text : colors.mutedText); label.setTextSize(sp);
        label.setPadding(0, dp(8), 0, dp(8)); if (bold) label.setTypeface(null, Typeface.BOLD); return label;
    }
    private Button button(String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false); button.setTextColor(colors.accent);
        button.setBackgroundTintList(ColorStateList.valueOf(colors.tint)); button.setMinimumHeight(dp(48)); button.setOnClickListener(view -> { if (!busy || !controls.contains(button)) action.run(); });
        controls.add(button); return button;
    }
    private LinearLayout row(View first, View second) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(first, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(second, new LinearLayout.LayoutParams(0, -2, 1)); return row;
    }
    private interface Selection { void select(int position); }
    private Spinner spinner(String[] values, Selection selection) {
        Spinner spinner = new Spinner(this); ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter); spinner.setMinimumHeight(dp(48));
        spinner.setBackgroundTintList(ColorStateList.valueOf(colors.accent));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (view instanceof TextView) ((TextView) view).setTextColor(colors.text);
                if (!updating && !busy && parent.getSelectedItemPosition() == position) selection.select(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        }); controls.add(spinner); return spinner;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void refreshControls() {
        boolean hasImage = !sources.isEmpty();
        for (View view : controls) view.setEnabled(!busy);
        previous.setEnabled(!busy && hasImage && selected > 0); earlier.setEnabled(previous.isEnabled());
        next.setEnabled(!busy && hasImage && selected < sources.size() - 1); later.setEnabled(next.isEnabled());
        rotate.setEnabled(!busy && hasImage); reset.setEnabled(!busy && hasImage); crop.setEnabled(!busy && hasImage); size.setEnabled(!busy && hasImage); photoOutput.setEnabled(!busy && hasImage);
        if (gifOutput != null) gifOutput.setEnabled(!busy && sources.size() >= 2);
        imageNumber.setText(hasImage ? (selected + 1) + " / " + sources.size() + "번째 사진" : "선택한 사진이 없어요");
        updating = true;
        if (hasImage) { MediaImageOps.Edit edit = edits.get(selected); crop.setSelection(edit.crop); size.setSelection(edit.edge == 1280 ? 0 : 1); }
        format.setSelection(formatIndex); if (delay != null) delay.setSelection(delayIndex); updating = false;
    }
    private void updateEdit(int cropValue, int edgeValue) {
        if (sources.isEmpty() || busy) return;
        MediaImageOps.Edit edit = edits.get(selected);
        MediaImageOps.Edit next = new MediaImageOps.Edit(edit.turns, cropValue < 0 ? edit.crop : cropValue, edgeValue < 0 ? edit.edge : edgeValue);
        if (edit.crop == next.crop && edit.edge == next.edge) return;
        edits.set(selected, next); renderPreview();
    }
    private void select(int index) { if (index < 0 || index >= sources.size() || busy) return; selected = index; refreshControls(); renderPreview(); }
    private void move(int offset) {
        int destination = selected + offset; if (destination < 0 || destination >= sources.size() || busy) return;
        Collections.swap(sources, selected, destination); Collections.swap(edits, selected, destination); selected = destination; refreshControls(); renderPreview();
    }

    private void chooseImages() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, PICK_IMAGES); }
        catch (RuntimeException exception) { status.setText("사진 선택 앱을 열 수 없어요."); }
    }

    private int newOperation() { generation++; if (task != null) task.cancel(true); return generation; }
    private void checkCanceled(int id) throws IOException { if (destroyed || generation != id || Thread.currentThread().isInterrupted()) throw new IOException("Canceled"); }
    private void renderPreview() {
        if (sources.isEmpty() || busy) return;
        int id = newOperation(); Uri uri = sources.get(selected); MediaImageOps.Edit edit = edits.get(selected);
        status.setText("사진 미리보기를 준비하고 있어요.");
        task = worker.submit(() -> {
            Bitmap decoded = null, rendered = null;
            try {
                checkCanceled(id); decoded = MediaImageOps.decode(getContentResolver(), uri); checkCanceled(id);
                rendered = MediaImageOps.transform(decoded, edit, 960, false); checkCanceled(id);
                Bitmap result = rendered; rendered = null;
                runOnUiThread(() -> {
                    if (destroyed || generation != id) { result.recycle(); return; }
                    Bitmap old = previewBitmap; previewBitmap = result; preview.setImageBitmap(result); if (old != null) old.recycle();
                    status.setText("현재 사진에만 편집이 적용됩니다. 원본은 변경하지 않아요.");
                });
            } catch (IOException | RuntimeException | OutOfMemoryError exception) { showError(id, "사진을 열지 못했어요. 20MB 이하의 JPEG·PNG 등 지원되는 사진을 선택해주세요."); }
            finally { if (decoded != null) decoded.recycle(); if (rendered != null) rendered.recycle(); }
        });
    }

    private void export(boolean gif) {
        if (busy || sources.isEmpty() || gif && (!allowGif || sources.size() < 2)) return;
        int id = newOperation(); busy = true; refreshControls();
        ArrayList<Uri> inputs = new ArrayList<>(sources); ArrayList<MediaImageOps.Edit> settings = new ArrayList<>(edits);
        int currentIndex = selected, photoFormat = formatIndex;
        int frameDelay = new int[]{100, 200, 400, 800, 1200}[delayIndex]; boolean repeat = loop;
        status.setText(gif ? "움짤을 만들고 있어요. 창을 닫으면 작업을 멈춥니다." : "사진을 처리하고 있어요. 창을 닫으면 작업을 멈춥니다.");
        task = worker.submit(() -> {
            ArrayList<File> results = new ArrayList<>(); boolean complete = false;
            try {
                if (gif) {
                    File file = MediaImageOps.outputFile(this, "gif"); results.add(file);
                    writeGif(file, inputs, settings, frameDelay, repeat, id);
                } else {
                    int begin = standalone ? currentIndex : 0, end = standalone ? currentIndex + 1 : inputs.size();
                    for (int i = begin; i < end; i++) {
                        checkCanceled(id); File file = MediaImageOps.outputFile(this, photoFormat == 1 ? "png" : "jpg"); results.add(file);
                        Bitmap decoded = null, output = null;
                        try {
                            decoded = MediaImageOps.decode(getContentResolver(), inputs.get(i)); checkCanceled(id);
                            output = MediaImageOps.transform(decoded, settings.get(i), 1920, photoFormat == 0); checkCanceled(id);
                            try (OutputStream stream = MediaImageOps.boundedOutput(new BufferedOutputStream(new FileOutputStream(file)), MediaImageOps.MAX_INPUT_BYTES)) {
                                if (!output.compress(photoFormat == 1 ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 92, stream)) throw new IOException("Cannot encode image");
                            }
                        } finally { if (decoded != null) decoded.recycle(); if (output != null) output.recycle(); }
                    }
                }
                checkCanceled(id); complete = true;
                String mime = gif ? "image/gif" : photoFormat == 1 ? "image/png" : "image/jpeg";
                runOnUiThread(() -> {
                    if (destroyed || generation != id) { deleteOwn(results); return; }
                    busy = false; refreshControls();
                    if (standalone) { pendingOutput = results.get(0); pendingMime = mime; saveDocument(); }
                    else returnFiles(results, mime);
                });
            } catch (IOException | RuntimeException | OutOfMemoryError exception) { showError(id, "처리하지 못했어요. 사진 수나 크기를 줄인 뒤 다시 시도해주세요."); }
            finally { if (!complete) deleteOwn(results); }
        });
    }

    private void writeGif(File file, ArrayList<Uri> inputs, ArrayList<MediaImageOps.Edit> settings, int interval, boolean repeat, int id) throws IOException {
        try (OutputStream stream = new BufferedOutputStream(new FileOutputStream(file), 65536)) {
            LocalGifEncoder encoder = null; int width = 0, height = 0; int[] pixels = null;
            for (int i = 0; i < inputs.size(); i++) {
                checkCanceled(id); Bitmap decoded = null, edited = null, frame = null;
                try {
                    decoded = MediaImageOps.decode(getContentResolver(), inputs.get(i)); checkCanceled(id);
                    edited = MediaImageOps.transform(decoded, settings.get(i), 640, true);
                    if (encoder == null) { width = edited.getWidth(); height = edited.getHeight(); pixels = new int[width * height]; encoder = new LocalGifEncoder(stream, width, height, interval, repeat); }
                    frame = MediaImageOps.frame(edited, width, height); frame.getPixels(pixels, 0, width, 0, 0, width, height); encoder.addFrame(pixels);
                    int count = i + 1; runOnUiThread(() -> { if (!destroyed && generation == id) status.setText("움짤 처리 중 · " + count + " / " + inputs.size()); });
                } finally { if (decoded != null) decoded.recycle(); if (edited != null) edited.recycle(); if (frame != null) frame.recycle(); }
            }
            if (encoder == null) throw new IOException("No frames"); encoder.finish();
        }
    }

    private void deleteOwn(ArrayList<File> files) { for (File file : files) if (MediaImageOps.ownedFile(this, file)) file.delete(); }
    private void returnFiles(ArrayList<File> files, String mime) {
        try {
            ArrayList<Uri> uris = new ArrayList<>();
            for (File file : files) {
                if (!MediaImageOps.ownedFile(this, file) || !file.isFile()) throw new IOException("Invalid edited file");
                uris.add(FileProvider.getUriForFile(this, getPackageName() + PROVIDER_SUFFIX, file));
            }
            Intent result = new Intent().putParcelableArrayListExtra(EXTRA_OUTPUT, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            result.setDataAndType(uris.get(0), mime); ClipData clip = ClipData.newRawUri("편집한 사진", uris.get(0));
            for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i))); result.setClipData(clip);
            setResult(RESULT_OK, result); finish();
        } catch (IOException | RuntimeException exception) { status.setText("편집한 파일을 연결하지 못했어요. 다시 시도해주세요."); deleteOwn(files); }
    }
    private void saveDocument() {
        if (pendingOutput == null || !MediaImageOps.ownedFile(this, pendingOutput)) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(pendingMime)
                .putExtra(Intent.EXTRA_TITLE, pendingMime.equals("image/gif") ? "moa-animation.gif" : pendingMime.equals("image/png") ? "moa-edited.png" : "moa-edited.jpg");
        try { startActivityForResult(intent, SAVE_DOCUMENT); }
        catch (RuntimeException exception) { status.setText("파일 저장 앱을 열 수 없어요."); }
    }
    private void copySavedDocument(Uri destination) {
        if (!MediaImageOps.validInput(destination) || pendingOutput == null || !MediaImageOps.ownedFile(this, pendingOutput)) { status.setText("저장 위치를 확인하지 못했어요."); return; }
        File source = pendingOutput; pendingOutput = null; int id = newOperation(); busy = true; refreshControls(); status.setText("새 파일로 저장하고 있어요.");
        task = worker.submit(() -> {
            try {
                checkCanceled(id);
                try (InputStream in = new FileInputStream(source); OutputStream raw = getContentResolver().openOutputStream(destination, "w")) {
                    if (raw == null) throw new IOException("Cannot open output document");
                    OutputStream out = MediaImageOps.boundedOutput(raw, LocalGifEncoder.MAX_BYTES); byte[] bytes = new byte[16384]; int read;
                    while ((read = in.read(bytes)) != -1) { checkCanceled(id); out.write(bytes, 0, read); } out.flush();
                }
                runOnUiThread(() -> { if (!destroyed && generation == id) { busy = false; refreshControls(); status.setText("새 파일로 저장했어요. 원본은 그대로입니다."); } });
            } catch (IOException | RuntimeException exception) { showError(id, "저장하지 못했어요. 저장 공간과 선택한 위치를 확인해주세요."); }
            finally { if (MediaImageOps.ownedFile(this, source)) source.delete(); }
        });
    }

    private void showError(int id, String message) { runOnUiThread(() -> { if (!destroyed && generation == id) { busy = false; refreshControls(); status.setText(message); } }); }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SAVE_DOCUMENT) {
            if (result == RESULT_OK && data != null) copySavedDocument(data.getData());
            else { status.setText("파일 저장을 취소했어요."); if (pendingOutput != null && MediaImageOps.ownedFile(this, pendingOutput)) pendingOutput.delete(); pendingOutput = null; }
            return;
        }
        if (request != PICK_IMAGES || result != RESULT_OK || data == null) return;
        ArrayList<Uri> chosen = new ArrayList<>(); ClipData clip = data.getClipData();
        if (clip != null) {
            if (clip.getItemCount() > MediaImageOps.MAX_SELECTION) { status.setText("사진은 최대 20장까지 선택해주세요."); return; }
            for (int i = 0; i < clip.getItemCount(); i++) chosen.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) chosen.add(data.getData());
        if (chosen.isEmpty()) return;
        for (Uri uri : chosen) if (!MediaImageOps.validInput(uri)) { status.setText("선택한 사진의 위치를 확인하지 못했어요."); return; }
        newOperation(); sources.clear(); sources.addAll(chosen); edits.clear();
        for (int i = 0; i < sources.size(); i++) edits.add(new MediaImageOps.Edit(0, 0, 1920));
        selected = 0; refreshControls(); renderPreview();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putParcelableArrayList("images", sources); int[] values = new int[edits.size() * 3];
        for (int i = 0; i < edits.size(); i++) { MediaImageOps.Edit edit = edits.get(i); values[i * 3] = edit.turns; values[i * 3 + 1] = edit.crop; values[i * 3 + 2] = edit.edge; }
        state.putIntArray("edits", values); state.putInt("selected", selected); state.putInt("format", formatIndex); state.putInt("delay", delayIndex);
        state.putBoolean("loop", loop); state.putBoolean("pickerOpened", pickerOpened); state.putBoolean("busy", busy);
        if (pendingOutput != null) { state.putString("pendingOutput", pendingOutput.getAbsolutePath()); state.putString("pendingMime", pendingMime); }
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        destroyed = true; generation++; if (task != null) task.cancel(true); worker.shutdownNow();
        if (preview != null) preview.setImageDrawable(null); if (previewBitmap != null) { previewBitmap.recycle(); previewBitmap = null; }
        super.onDestroy();
    }

    @Override public void finish() {
        generation++; if (task != null) task.cancel(true);
        super.finish();
    }
}
