package com.vintechafrica.jvinbuddy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object Net {
    /** True only when Android has confirmed the network actually reaches the internet. */
    fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
