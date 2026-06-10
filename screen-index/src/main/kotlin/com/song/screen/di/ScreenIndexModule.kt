package com.song.screen.di

import com.intellij.openapi.project.Project
import com.song.screen.ClassTargetResolver
import com.song.screen.datasource.*
import com.song.workflow.CommitService
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun screenIndexModule(): Module = module {
    single { ClassTargetResolver() }

    single { UiDataSourceScanStore() }
    single { UiDataSourceScanner(get<Project>(), get()) }
    single { UiDataSourceScanService(get<Project>(), get(), get()) }

    single { UiDataSourceMigrationPromptBuilder() }
    single { MigrationTargetResolver(get<Project>()) }
    single(named("ui-datasource-migration")) {
        CommitService(get(), fallbackMessage = "migrate(ui-datasource): move data-source access into ViewModel")
    }
    single {
        UiDataSourceMigrationService(
            get<Project>(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(named("ui-datasource-migration")),
            get(),
        )
    }
}
