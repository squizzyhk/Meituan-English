package com.mtenglish.app

import android.app.Application
import com.mtenglish.app.translate.TranslationEngine

class MtEnglishApp : Application() {
    lateinit var engine: TranslationEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        engine = TranslationEngine.create(this)
    }

    companion object {
        lateinit var instance: MtEnglishApp
            private set
    }
}
