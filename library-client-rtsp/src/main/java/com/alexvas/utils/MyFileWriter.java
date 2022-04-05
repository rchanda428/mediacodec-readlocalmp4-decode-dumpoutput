package com.alexvas.utils;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import androidx.annotation.RequiresApi;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;

public class MyFileWriter {

    static final Object sync = new Object();
    static OutputStream outputStream;

    public static void createFile(String fileName, Context context) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, MimeTypeMap.getSingleton().getMimeTypeFromExtension("txt"));
        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/RTSP/");
        Uri uri = context.getContentResolver().insert(MediaStore.Files.getContentUri("external"), contentValues);
        synchronized(sync) {
            try {
                outputStream =context.getContentResolver().openOutputStream(uri,"wa");
//                String s = String.format("%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", "Time_stamp", "sens_type","a_x","a_y","a_z","g_x","g_y","g_z","m_x","m_y","m_z");
//                String s = String.format("%s %s %s %s %s %s %s %s %s %s\n", "Time stamp,", "a_x,", "a_y,", "a_z,", "g_x,", "g_y,","g_z,", "m_x,", "m_y,", "m_z,");
                outputStream.write("".getBytes());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @SuppressLint("Range")
    public static void appendData(String s) {
//        String s = String.format("%s\t%d\t%f\t%f\t%f\t%f\t%f\t%f\t%f\t%f\t%f\n", timestamp, sens_type, a_x, a_y, a_z,g_x,g_y,g_z,m_x,m_y,m_z);
//        String s = String.format("%s %s %s %s %s %s %s %s %s %s\n", timestamp+",", a_x+",", a_y+",", a_z+",", g_x+",", g_y+",",g_z+",",m_x+",", m_y+",", m_z+",");
        if (outputStream != null){
            synchronized(sync) {
                try {
                    outputStream.write(s.getBytes());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static void closeFile() {
        if (outputStream != null){
            try {
                outputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    public static void saveBitmap(final Bitmap bitmap, final String filename, Context context){
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StreamedImages/");
        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
        synchronized(sync) {
            try {
                outputStream = context.getContentResolver().openOutputStream(uri);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }

    }
}
