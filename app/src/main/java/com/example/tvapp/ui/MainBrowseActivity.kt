package com.example.tvapp.ui

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.example.tvapp.R

class MainBrowseActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_browse)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.browse_container, MainRowsFragment())
                .commit()
        }
    }
}
