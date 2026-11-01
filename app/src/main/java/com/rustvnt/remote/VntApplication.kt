package com.rustvnt.remote

import android.app.Application

class VntApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Appearance.apply(this)
    }
}
