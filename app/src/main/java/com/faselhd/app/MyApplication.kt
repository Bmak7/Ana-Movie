package com.faselhd.app // Or your main package name

import android.app.Application
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Setup Injekt
        Injekt.importModule(object : InjektModule { // <-- CORRECT: Implement InjektModule here
            override fun InjektRegistrar.registerInjectables() {
                // This tells Injekt how to create a Json instance whenever one is requested.
                // It will be a singleton, so the same instance is reused everywhere.
                addSingletonFactory {
                    Json {
                        // This is a good practice to prevent crashes if the API adds new fields
                        // that are not in your data classes.
                        ignoreUnknownKeys = true
                    }
                }

                // You can register other dependencies here as well in the future.
            }
        })
    }
}