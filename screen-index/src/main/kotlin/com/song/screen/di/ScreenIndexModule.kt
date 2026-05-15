package com.song.screen.di

import com.intellij.openapi.project.Project
import com.song.screen.ClassTargetResolver
import com.song.screen.ScreenIndexService
import com.song.screen.ScreenIndexStore
import com.song.screen.activity.ActivityRootResolver
import com.song.screen.host.HostedChildExtractor
import com.song.screen.host.LayoutXmlHostScanner
import com.song.screen.host.NavGraphHostScanner
import com.song.screen.tree.LayerTreeBuilder
import com.song.screen.tree.TreeConfig
import com.song.screen.tree.UnitClassifier
import org.koin.core.module.Module
import org.koin.dsl.module

fun screenIndexModule(): Module = module {
    single { ClassTargetResolver(get<Project>()) }
    single { ActivityRootResolver(get<Project>(), get()) }

    single { LayoutXmlHostScanner(get<Project>()) }
    single { NavGraphHostScanner(get()) }
    single { HostedChildExtractor(get(), get()) }

    single { UnitClassifier(get()) }
    single { TreeConfig() }
    single { LayerTreeBuilder(get(), get(), get(), get()) }

    single { ScreenIndexStore() }
    single { ScreenIndexService(get<Project>(), get(), get(), get()) }
}
