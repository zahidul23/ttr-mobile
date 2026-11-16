package org.ttrmobile;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;

/**
 * Shows the game's own content packs folder (private storage) to file managers and the system Files app, so packs
 * can be copied in, copied out or deleted there without a second copy of them anywhere. Only .mf files are listed,
 * and only the system's document picker (MANAGE_DOCUMENTS) can reach this directly.
 */
public final class TtrPackProvider extends DocumentsProvider {
    static final String ROOT_ID = "packs", DOCUMENT_ROOT = "root";
    private static final String[] ROOT_COLUMNS = {Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES};
    private static final String[] DOCUMENT_COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS};
    private final Handler ui = new Handler(Looper.getMainLooper());

    static String authority(Context c) { return c.getPackageName() + ".packs"; }
    /** Tells file managers the list changed (after a download or a delete in the app). */
    static void changed(Context c) {
        try { c.getContentResolver().notifyChange(DocumentsContract.buildChildDocumentsUri(authority(c), DOCUMENT_ROOT), null); } catch (Exception ignored) { }
    }

    @Override public boolean onCreate() { return true; }
    private File directory() { return TtrPacks.folder(getContext()); }
    /** The pack a document id names: a plain .mf file name inside the folder, nothing else. */
    private File file(String id) throws FileNotFoundException {
        if (id == null || DOCUMENT_ROOT.equals(id) || !TtrPacks.isPack(id)) throw new FileNotFoundException("Not a content pack: " + id);
        File f = new File(directory(), id);
        if (!f.isFile()) throw new FileNotFoundException("No such content pack: " + id);
        return f;
    }
    private static String[] columns(String[] projection, String[] fallback) { return projection != null ? projection : fallback; }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(columns(projection, ROOT_COLUMNS));
        Context c = getContext();
        cursor.newRow().add(Root.COLUMN_ROOT_ID, ROOT_ID).add(Root.COLUMN_DOCUMENT_ID, DOCUMENT_ROOT)
            .add(Root.COLUMN_TITLE, c.getApplicationInfo().loadLabel(c.getPackageManager()).toString()).add(Root.COLUMN_SUMMARY, "Content packs")
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY).add(Root.COLUMN_ICON, c.getApplicationInfo().icon)
            .add(Root.COLUMN_AVAILABLE_BYTES, directory().getUsableSpace());
        return cursor;
    }
    private void row(MatrixCursor cursor, String id) throws FileNotFoundException {
        if (DOCUMENT_ROOT.equals(id)) {
            cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, DOCUMENT_ROOT).add(Document.COLUMN_DISPLAY_NAME, "Content packs").add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                .add(Document.COLUMN_SIZE, 0).add(Document.COLUMN_LAST_MODIFIED, directory().lastModified()).add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE);
            return;
        }
        File f = file(id);
        cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, id).add(Document.COLUMN_DISPLAY_NAME, f.getName()).add(Document.COLUMN_MIME_TYPE, "application/octet-stream")
            .add(Document.COLUMN_SIZE, f.length()).add(Document.COLUMN_LAST_MODIFIED, f.lastModified()).add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_WRITE);
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(columns(projection, DOCUMENT_COLUMNS)); row(cursor, id); return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sort) throws FileNotFoundException {
        if (!DOCUMENT_ROOT.equals(parent)) throw new FileNotFoundException("Not a folder: " + parent);
        MatrixCursor cursor = new MatrixCursor(columns(projection, DOCUMENT_COLUMNS));
        File[] files = directory().listFiles((d, n) -> TtrPacks.isPack(n));
        if (files != null) { Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName())); for (File f : files) if (f.isFile()) row(cursor, f.getName()); }
        cursor.setNotificationUri(getContext().getContentResolver(), DocumentsContract.buildChildDocumentsUri(authority(getContext()), DOCUMENT_ROOT));
        return cursor;
    }
    @Override public boolean isChildDocument(String parent, String id) { return DOCUMENT_ROOT.equals(parent) && !DOCUMENT_ROOT.equals(id) && TtrPacks.isPack(id); }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        File f = file(id); int flags = ParcelFileDescriptor.parseMode(mode);
        if (mode.indexOf('w') < 0) return ParcelFileDescriptor.open(f, flags);
        // After something has been written the file must be a real pack; a stray file is removed again.
        try {
            return ParcelFileDescriptor.open(f, flags, ui, (IOException e) -> {
                if (!TtrPacks.looksLikePack(f)) f.delete();
                changed(getContext());
            });
        } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
    }
    @Override public String createDocument(String parent, String mimeType, String displayName) throws FileNotFoundException {
        if (!DOCUMENT_ROOT.equals(parent)) throw new FileNotFoundException("Packs go in the top folder.");
        String name = TtrPackDownload.clean(displayName);
        if (!TtrPacks.isPack(name)) throw new FileNotFoundException("Content packs are .mf files.");
        File f = new File(directory(), name); String stem = name.substring(0, name.length() - 3);
        for (int i = 1; f.exists(); i++) f = new File(directory(), stem + " (" + i + ").mf");
        try { if (!f.createNewFile()) throw new FileNotFoundException("Unable to create " + f.getName()); }
        catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
        changed(getContext()); return f.getName();
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        File f = file(id);
        try { TtrPacks.delete(getContext(), new TtrPacks.Pack(f.getName(), f.length(), f.lastModified())); }
        catch (Exception e) { throw new FileNotFoundException(e.getMessage()); }
        changed(getContext());
    }
}
