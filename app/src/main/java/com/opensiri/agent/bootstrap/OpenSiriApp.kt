package com.opensiri.agent.bootstrap

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class — entry point for Hilt's dependency injection graph.
 *
 * @HiltAndroidApp triggers Hilt code generation, creating the application-scoped
 * DI container that all @AndroidEntryPoint activities / @HiltViewModel view models
 * can pull from. Without this annotation, no injection works.
 */
@HiltAndroidApp
class OpenSiriApp : Application()
