package app.moa.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.ColorDrawable;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.widget.TextView;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Image URL and native-menu presentation policy; no page text or credentials are retained here. */
final class ImageContextMenu {
    private ImageContextMenu() { }

    static String imageUrl(String value) {
        if (value == null || value.length() > 8192 || value.matches("(?s).*[\\x00-\\x20\\x7f\\\\].*")) return null;
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getRawFragment() == null
                    ? uri.toASCIIString() : null;
        } catch (Exception invalid) { return null; }
    }

    static boolean readDocument(String value) {
        if (!UrlPolicy.isDcHttps(value) || UrlPolicy.isAuthenticationPage(value)) return false;
        try {
            URI uri = URI.create(value);
            String path = uri.getPath();
            if (path == null || path.matches("(?i).*/(?:write|modify|writemini|writepr|modifymini|modifypr)(?:/.*)?")) return false;
            String query = uri.getRawQuery();
            if (query != null) for (String pair : query.split("&")) {
                String[] field = pair.split("=", 2);
                if (field.length == 2 && URLDecoder.decode(field[0], "UTF-8").matches("(?i)mode|action")
                        && URLDecoder.decode(field[1], "UTF-8").matches("(?i)write|modify|edit")) return false;
            }
            return true;
        } catch (Exception invalid) { return false; }
    }

    static final class Metadata {
        final String mime, disposition;
        Metadata(String mime, String disposition) { this.mime = mime; this.disposition = disposition; }
    }

    static Metadata metadata(String url, String contentType, String disposition) {
        String mime = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        String filename = URLUtil.guessFileName(url, disposition, null);
        if (disposition != null) {
            Matcher extended = Pattern.compile("(?i)(?:^|;)\\s*filename\\*=UTF-8''([^;]+)").matcher(disposition);
            if (extended.find()) try { filename = URLDecoder.decode(extended.group(1).trim().replace("+", "%2B"), "UTF-8"); }
            catch (Exception ignored) { }
        }
        if (!mime.matches("image/[a-z0-9.+-]+")) {
            if (!mime.isEmpty() && !"application/octet-stream".equals(mime) && !"binary/octet-stream".equals(mime)) return null;
            String extension = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime == null || !mime.startsWith("image/")) return null;
        }
        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        if (extension == null || !extension.matches("[a-z0-9]{1,10}")) return null;
        filename = filename.replaceAll("[\\\\/\\x00-\\x1f\\x7f\";]", "_").trim();
        int dot = filename.lastIndexOf('.');
        if (dot > 0) filename = filename.substring(0, dot);
        if (filename.isEmpty() || filename.equals(".") || filename.equals("..")) filename = "image";
        if (filename.length() > 100) filename = filename.substring(0, 100);
        return new Metadata(mime, "attachment; filename=\"" + filename + "." + extension + "\"");
    }

    static AlertDialog create(Activity activity, ThemeSpec.Palette colors, boolean dark, float fontScale,
                              java.util.function.IntConsumer action) {
        AlertDialog dialog = new AlertDialog.Builder(activity, dark ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("이미지").setItems(new String[]{"이미지 저장", "이미지 주소 복사", "브라우저에서 이미지 열기"},
                        (ignored, index) -> action.accept(index)).setNegativeButton("닫기", null).create();
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(colors.surface));
                resize(activity, dialog);
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(colors.accent);
            dialog.getListView().setBackgroundColor(colors.surface);
            Runnable decorateRows = () -> {
                for (int i = 0; i < dialog.getListView().getChildCount(); i++) {
                    if (dialog.getListView().getChildAt(i) instanceof TextView) {
                        TextView row = (TextView) dialog.getListView().getChildAt(i);
                        row.setTextColor(colors.text); row.setTextSize(16 * fontScale);
                    }
                }
            };
            dialog.getListView().addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> decorateRows.run());
            decorateRows.run();
            int titleId = activity.getResources().getIdentifier("alertTitle", "id", "android");
            if (titleId != 0 && dialog.findViewById(titleId) instanceof TextView)
                ((TextView)dialog.findViewById(titleId)).setTextColor(colors.text);
        });
        return dialog;
    }

    static void resize(Activity activity, AlertDialog dialog) {
        if (dialog == null || dialog.getWindow() == null || !dialog.isShowing()) return;
        int width = activity.getWindow().getDecorView().getWidth();
        if (width <= 0) width = activity.getResources().getDisplayMetrics().widthPixels;
        float density = activity.getResources().getDisplayMetrics().density;
        dialog.getWindow().setLayout(Math.max(1, Math.min(Math.round(420 * density), width - Math.round(32 * density))), -2);
    }
}
