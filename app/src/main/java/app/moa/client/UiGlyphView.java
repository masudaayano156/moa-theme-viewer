package app.moa.client;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small original navigation glyphs, drawn at any density without font dependencies. */
final class UiGlyphView extends View {
    private final String kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    UiGlyphView(Context context, String kind, int color) {
        super(context);
        this.kind = kind;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.7f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(getWidth() / 24f, getHeight() / 24f);
        Path path = new Path();
        switch (kind) {
            case "back":
                path.moveTo(11, 4); path.lineTo(3, 12); path.lineTo(11, 20);
                canvas.drawPath(path, paint); canvas.drawLine(3, 12, 21, 12, paint);
                break;
            case "bookmark":
                path.moveTo(6, 3); path.lineTo(18, 3); path.lineTo(18, 21);
                path.lineTo(12, 17); path.lineTo(6, 21); path.close();
                canvas.drawPath(path, paint);
                break;
            case "folder":
                path.moveTo(2, 7); path.lineTo(2, 4); path.lineTo(9, 4);
                path.lineTo(12, 7); path.lineTo(22, 7); path.lineTo(22, 20);
                path.lineTo(2, 20); path.close(); canvas.drawPath(path, paint);
                break;
            case "grid":
                canvas.drawRect(3, 3, 21, 21, paint);
                canvas.drawLine(3, 9, 21, 9, paint); canvas.drawLine(3, 15, 21, 15, paint);
                canvas.drawLine(9, 3, 9, 21, paint); canvas.drawLine(15, 3, 15, 21, paint);
                break;
            case "refresh":
                canvas.drawArc(new RectF(4, 4, 20, 20), 25, 300, false, paint);
                path.moveTo(15, 3); path.lineTo(21, 4); path.lineTo(20, 10); canvas.drawPath(path, paint);
                break;
            case "page":
                canvas.drawRoundRect(new RectF(5, 2, 19, 22), 2, 2, paint);
                canvas.drawLine(8, 7, 16, 7, paint); canvas.drawLine(8, 12, 16, 12, paint);
                canvas.drawLine(8, 17, 14, 17, paint);
                break;
            case "share":
                canvas.drawCircle(18, 4, 3, paint); canvas.drawCircle(5, 12, 3, paint);
                canvas.drawCircle(18, 20, 3, paint); canvas.drawLine(8, 10, 15, 6, paint);
                canvas.drawLine(8, 14, 15, 18, paint);
                break;
            case "account":
                canvas.drawCircle(12, 12, 10, paint);
                canvas.drawCircle(12, 9, 3, paint);
                path.moveTo(5, 19); path.cubicTo(6, 12, 18, 12, 19, 19);
                canvas.drawPath(path, paint);
                break;
            case "menu":
                canvas.drawLine(4, 6, 20, 6, paint);
                canvas.drawLine(4, 12, 20, 12, paint);
                canvas.drawLine(4, 18, 20, 18, paint);
                break;
            case "inbox":
                path.moveTo(5, 4); path.lineTo(19, 4); path.lineTo(22, 15);
                path.lineTo(22, 20); path.lineTo(2, 20); path.lineTo(2, 15); path.close();
                canvas.drawPath(path, paint);
                path.reset(); path.moveTo(2, 15); path.lineTo(8, 15); path.lineTo(10, 18);
                path.lineTo(14, 18); path.lineTo(16, 15); path.lineTo(22, 15);
                canvas.drawPath(path, paint);
                break;
            case "add":
                canvas.drawRoundRect(new RectF(3, 3, 21, 21), 4, 4, paint);
                canvas.drawLine(7, 12, 17, 12, paint);
                canvas.drawLine(12, 7, 12, 17, paint);
                break;
            case "book":
                path.moveTo(12, 5); path.cubicTo(8, 2, 4, 3, 2, 4); path.lineTo(2, 20);
                path.cubicTo(5, 19, 8, 18, 12, 21); path.cubicTo(16, 18, 19, 19, 22, 20);
                path.lineTo(22, 4); path.cubicTo(19, 3, 16, 2, 12, 5); path.close();
                canvas.drawPath(path, paint); canvas.drawLine(12, 5, 12, 21, paint);
                break;
            case "home":
                path.moveTo(3, 10); path.lineTo(12, 3); path.lineTo(21, 10);
                path.moveTo(5, 9); path.lineTo(5, 21); path.lineTo(10, 21);
                path.lineTo(10, 15); path.lineTo(14, 15); path.lineTo(14, 21);
                path.lineTo(19, 21); path.lineTo(19, 9);
                canvas.drawPath(path, paint);
                break;
            case "search":
                canvas.drawCircle(10.5f, 10.5f, 6.8f, paint);
                canvas.drawLine(15.6f, 15.6f, 21, 21, paint);
                break;
            case "edit":
                canvas.drawRoundRect(new RectF(3, 5, 19, 21), 3, 3, paint);
                path.moveTo(10, 14); path.lineTo(11, 10); path.lineTo(19, 2);
                path.lineTo(22, 5); path.lineTo(14, 13); path.close();
                canvas.drawPath(path, paint);
                canvas.drawLine(17, 4, 20, 7, paint);
                break;
            default:
                canvas.drawLine(4, 6, 20, 6, paint);
                canvas.drawLine(4, 12, 20, 12, paint);
                canvas.drawLine(4, 18, 20, 18, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(9, 6, 2.6f, paint);
                canvas.drawCircle(16, 12, 2.6f, paint);
                canvas.drawCircle(10, 18, 2.6f, paint);
                paint.setStyle(Paint.Style.STROKE);
        }
        canvas.restore();
    }
}
