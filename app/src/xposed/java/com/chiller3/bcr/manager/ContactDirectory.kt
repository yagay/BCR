/* SPDX-License-Identifier: GPL-3.0-only */
package com.chiller3.bcr.manager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract

internal data class ContactProfile(val displayName: String?, val photo: Bitmap?)

/** Optional contact enhancement: no READ_CONTACTS permission means no lookup. */
internal class ContactDirectory(private val context: Context) {
    private val cache = LinkedHashMap<String, ContactProfile>()

    fun lookup(number: String): ContactProfile? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED) return null
        val phone = dialablePhoneNumber(number) ?: return null
        synchronized(cache) { cache[phone]?.let { return it } }
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phone),
        )
        val columns = arrayOf(
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
        )
        val result = runCatching {
            context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = cursor.getString(0)
                val photoUri = cursor.getString(1)
                val photo = photoUri?.let { raw ->
                    runCatching {
                        context.contentResolver.openInputStream(Uri.parse(raw))?.use { stream ->
                            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                                inSampleSize = 2
                            })
                        }
                    }.getOrNull()
                }
                ContactProfile(name, photo)
            }
        }.getOrNull()
        if (result != null) synchronized(cache) {
            if (cache.size >= 128) cache.remove(cache.keys.first())
            cache[phone] = result
        }
        return result
    }
}
