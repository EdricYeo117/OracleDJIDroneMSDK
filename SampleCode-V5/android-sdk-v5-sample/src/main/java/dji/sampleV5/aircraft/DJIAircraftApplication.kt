package dji.sampleV5.aircraft

import android.content.Context
import android.util.Log

/**
 * Class Description
 *
 * @author Hoker
 * @date 2022/3/2
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class DJIAircraftApplication : DJIApplication() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)     // MUST be first
        try {
            com.cySdkyc.clx.Helper.install(this) // or base, depending on their API
        } catch (t: Throwable) {
            Log.e("DJIApp", "Helper.install failed", t)
        }
    }
}