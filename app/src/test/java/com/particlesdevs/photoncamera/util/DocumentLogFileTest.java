package com.particlesdevs.photoncamera.util;

import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class DocumentLogFileTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private final Map<Long,String> names = new LinkedHashMap<>();
    private final Map<Long,File> files = new LinkedHashMap<>();
    private long next;
    private boolean refuseRename;
    private boolean disambiguateBase;
    private long deniedRow;

    private Context storage() throws Exception {
        Context context = mock(Context.class);
        ContentResolver resolver = mock(ContentResolver.class);
        when(context.getContentResolver()).thenReturn(resolver);
        when(context.getPackageManager()).thenReturn(mock(android.content.pm.PackageManager.class));
        when(resolver.query(any(Uri.class), any(String[].class), nullable(String.class), nullable(String[].class), nullable(String.class)))
                .thenAnswer(call -> {
                    Uri uri=call.getArgument(0); String[] columns=call.getArgument(1), args=call.getArgument(3);
                    MatrixCursor result=new MatrixCursor(columns);
                    for (Long id : names.keySet()) {
                        if (!uri.getLastPathSegment().equals("file") && !uri.getLastPathSegment().equals(Long.toString(id))) continue;
                        if (args != null && args.length == 2 && !args[1].equals(names.get(id))) continue;
                        Object[] row=new Object[columns.length];
                        for(int i=0;i<columns.length;i++) row[i]=columns[i].equals(MediaStore.MediaColumns._ID)?id:
                                columns[i].equals(MediaStore.MediaColumns.DISPLAY_NAME)?names.get(id):files.get(id).length();
                        result.addRow(row);
                    }
                    return result;
                });
        when(resolver.insert(any(Uri.class), any(ContentValues.class))).thenAnswer(call -> {
            long id=++next;ContentValues values=call.getArgument(1);
            assertEquals(DocumentLogFile.RELATIVE, values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
            names.put(id,values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
            if(disambiguateBase && names.get(id).equals("SCAMERA_Log_2026-11-02.txt")) names.put(id,"SCAMERA_Log_2026-11-02 (1).txt");
            files.put(id,temp.newFile("row-"+id));
            return android.content.ContentUris.withAppendedId((Uri)call.getArgument(0),id);
        });
        when(resolver.openFileDescriptor(any(Uri.class), anyString())).thenAnswer(call -> {
            long id=android.content.ContentUris.parseId(call.getArgument(0));
            if(id==deniedRow) throw new java.io.FileNotFoundException("Earlier install owns this row");
            return ParcelFileDescriptor.open(files.get(id),ParcelFileDescriptor.parseMode(call.getArgument(1)));
        });
        when(resolver.update(any(Uri.class), any(ContentValues.class), isNull(), isNull())).thenAnswer(call -> {
            if(refuseRename) return 0;
            long id=android.content.ContentUris.parseId(call.getArgument(0));ContentValues values=call.getArgument(1);
            names.put(id,values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));return 1;
        });
        when(resolver.delete(any(Uri.class), isNull(), isNull())).thenAnswer(call -> {
            long id=android.content.ContentUris.parseId(call.getArgument(0));
            names.remove(id);File file=files.remove(id);if(file!=null)file.delete();return 1;
        });
        return context;
    }

    @Test public void appendsThroughTheProviderDescriptorWithoutAFilePathPermission() throws Exception {
        Context context=storage();
        DocumentLogFile first=new DocumentLogFile(context,"SCAMERA_Log_2026-11-02");
        first.write("first\n");first.close();
        DocumentLogFile second=new DocumentLogFile(context,"SCAMERA_Log_2026-11-02");
        second.write("second\n");second.flush();
        assertEquals(1,names.size());
        assertEquals("first\nsecond\n",new String(Files.readAllBytes(files.get(1L).toPath()),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(13,second.size());second.close();
    }

    @Test public void sizeRotationPreservesTheFullPreviousFile() throws Exception {
        Context context=storage();
        DocumentLogFile first=new DocumentLogFile(context,"SCAMERA_Log_2026-11-02");
        first.write("first\n");first.close();
        try(java.io.RandomAccessFile large=new java.io.RandomAccessFile(files.get(1L),"rw")) {
            large.setLength(RotatingLogFile.CAP_BYTES);
        }
        DocumentLogFile second=new DocumentLogFile(context,"SCAMERA_Log_2026-11-02");
        second.write("new\n");second.close();
        assertEquals("SCAMERA_Log_2026-11-02.1.txt",names.get(1L));
        assertEquals(RotatingLogFile.CAP_BYTES,files.get(1L).length());
        assertEquals("SCAMERA_Log_2026-11-02.txt",names.get(2L));
        assertEquals(4,files.get(2L).length());
    }

    @Test public void refusedRotationNeverTruncatesTheExistingLog() throws Exception {
        Context context=storage();
        DocumentLogFile first=new DocumentLogFile(context,"SCAMERA_Log_2026-11-02");
        first.write("owner history\n");first.close();
        try(java.io.RandomAccessFile large=new java.io.RandomAccessFile(files.get(1L),"rw")) {
            large.setLength(RotatingLogFile.CAP_BYTES);
        }
        refuseRename=true;
        DocumentLogFile next=new DocumentLogFile(context,"SCAMERA_Log_2026-11-02");
        try { next.write("replacement"); fail("rotation must fail safely"); }
        catch(java.io.IOException expected) { }
        assertEquals(RotatingLogFile.CAP_BYTES,files.get(1L).length());
        byte[] prefix=new byte[14];try(java.io.FileInputStream input=new java.io.FileInputStream(files.get(1L))) { assertEquals(14,input.read(prefix)); }
        assertEquals("owner history\n",new String(prefix,java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void installTime(Context context) throws Exception {
        when(context.getPackageName()).thenReturn("com.scamera.app");
        android.content.pm.PackageInfo info=new android.content.pm.PackageInfo();info.firstInstallTime=1793624400000L;
        when(context.getPackageManager().getPackageInfo("com.scamera.app",0)).thenReturn(info);
    }

    @Test public void aPerInstallAlternateIsRediscoveredAfterRestart() throws Exception {
        Context context=storage();installTime(context);
        DocumentLogFile owner=new DocumentLogFile(context,"SCAMERA_Log_2026-11-02");owner.write("history\n");owner.close();
        deniedRow=1;
        DocumentLogFile current=new DocumentLogFile(context,"SCAMERA_Log_2026-11-02");current.write("alternate\n");current.close();
        DocumentLogFile restarted=new DocumentLogFile(context,"SCAMERA_Log_2026-11-02");
        assertTrue(restarted.find());assertEquals(current.uri(),restarted.uri());
        restarted.write("continued\n");restarted.close();
        assertEquals(2,names.size());
        assertEquals("alternate\ncontinued\n",new String(Files.readAllBytes(files.get(2L).toPath()),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("history\n",new String(Files.readAllBytes(files.get(1L).toPath()),java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test public void providerSuffixDoesNotCreateAnUndiscoverableFileEverySession() throws Exception {
        Context context=storage();installTime(context);disambiguateBase=true;
        DocumentLogFile first=new DocumentLogFile(context,"SCAMERA_Log_2026-11-02");first.write("first\n");first.close();
        DocumentLogFile second=new DocumentLogFile(context,"SCAMERA_Log_2026-11-02");second.write("second\n");second.close();
        assertEquals(first.uri(),second.uri());assertEquals(1,names.size());
        assertTrue(second.name().startsWith("SCAMERA_Log_2026-11-02-"));
        assertEquals("first\nsecond\n",new String(Files.readAllBytes(files.values().iterator().next().toPath()),java.nio.charset.StandardCharsets.UTF_8));
    }
}
