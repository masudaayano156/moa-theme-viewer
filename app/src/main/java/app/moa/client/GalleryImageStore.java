package app.moa.client;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.UUID;

/** A new image owned by this save operation. Never opens or replaces existing photos. */
final class GalleryImageStore {
    private GalleryImageStore() { }

    static Pending begin(Context context, ImageContextMenu.Metadata metadata) throws IOException {
        return new Pending(context.getContentResolver(), metadata);
    }

    static final class Pending {
        private final ContentResolver resolver;
        private final ImageContextMenu.Metadata metadata;
        private final String displayName;
        private final boolean scoped = Build.VERSION.SDK_INT >= 29;
        private Uri row;
        private File temporary, destination;
        private boolean destinationCreated;

        private Pending(ContentResolver resolver, ImageContextMenu.Metadata metadata) throws IOException {
            this.resolver = resolver;
            this.metadata = metadata;
            String extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(metadata.mime);
            if (extension == null || !extension.matches("[a-z0-9]{1,10}")) throw new IOException("Unsupported image type");
            displayName = "Moa_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "") + "." + extension;
            if (scoped) {
                ContentValues values = values();
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Moa");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                row = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
                if (row == null) throw new IOException("MediaStore did not create a destination");
            } else {
                File pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getCanonicalFile();
                File folder = new File(pictures, "Moa").getCanonicalFile();
                if (!pictures.equals(folder.getParentFile()) || !folder.isDirectory() && !folder.mkdirs())
                    throw new IOException("Cannot create the image folder");
                // Hidden temporary bytes are not presented as a gallery image.
                // The final name is unique and Files.move never replaces another file.
                temporary = File.createTempFile(".moa-pending-", ".tmp", folder);
                destination = new File(folder, displayName);
            }
        }

        OutputStream open() throws IOException {
            OutputStream output = scoped ? resolver.openOutputStream(row, "w") : new FileOutputStream(temporary);
            if (output == null) throw new IOException("Cannot open the new image");
            return output;
        }

        void publish() throws IOException {
            if (scoped) {
                ContentValues ready = new ContentValues(); ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (resolver.update(row, ready, null, null) != 1) throw new IOException("Cannot publish the image");
            } else {
                Files.move(temporary.toPath(), destination.toPath());
                destinationCreated = true;
                temporary = null;
                ContentValues values = values(); values.put(MediaStore.Images.Media.DATA, destination.getAbsolutePath());
                row = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (row == null) throw new IOException("Cannot register the image");
            }
        }

        private ContentValues values() {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
            values.put(MediaStore.Images.Media.MIME_TYPE, metadata.mime);
            values.put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000);
            return values;
        }

        void abort() {
            if (row != null) {
                try { resolver.delete(row, null, null); } catch (RuntimeException ignored) { }
                row = null;
            }
            // These references are only files created by this operation.
            if (temporary != null) { temporary.delete(); temporary = null; }
            if (destinationCreated && destination != null) { destination.delete(); destination = null; }
        }
    }
}
