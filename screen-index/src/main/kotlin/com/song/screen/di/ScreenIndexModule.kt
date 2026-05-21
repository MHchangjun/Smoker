package com.song.screen.di

import com.intellij.openapi.project.Project
import com.song.screen.ClassTargetResolver
import com.song.screen.ScreenIndexStore
import com.song.screen.activity.ActivityRootResolver
import com.song.screen.datasource.UiDataSourceScanService
import com.song.screen.datasource.UiDataSourceScanStore
import com.song.screen.datasource.UiDataSourceScanner
import org.koin.core.module.Module
import org.koin.dsl.module

fun screenIndexModule(): Module = module {
    single { ClassTargetResolver(get<Project>()) }
    single { ActivityRootResolver(get<Project>(), get()) }
    single { ScreenIndexStore() }

    single { UiDataSourceScanStore() }
    single { UiDataSourceScanner(get<Project>(), get()) }
    single { UiDataSourceScanService(get<Project>(), get(), get()) }
}
