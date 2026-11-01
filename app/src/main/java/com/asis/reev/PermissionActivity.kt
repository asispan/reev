package com.asis.reev

import android.Manifest
import android.app.Activity
import android.os.Bundle

/** IMEs can't request runtime permissions; this invisible activity does it for them.
 *  No toasts or other floating UI — Reev only ever draws inside the keyboard. */
class PermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }
}
