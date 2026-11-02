package com.ptrfarhan.kontrolpompa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Menyalakan lagi pemantau notifikasi setelah ponsel menyala atau aplikasi diperbarui, bila pengaturannya masih aktif. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) PantauService.mulaiAtauBerhenti(context);
    }
}
