package com.song.inspection.di

import com.song.inspection.*
import org.koin.core.module.Module
import org.koin.dsl.module

fun inspectionModule(): Module = module {
    single { JetBrainsInspectRunner() }
    single { JetBrainsInspectionReportParser() }
    single { InspectionFilter() }
    single { InspectionPrioritizer() }
    single { InspectionPromptBuilder() }
    single { InspectionCommitService(get()) }
    single { InspectionBranchService(get()) }
    single { InspectionScanService(get(), get(), get()) }
    single { InspectionFixService(get(), get(), get(), get()) }
    single { InspectionWorkflowRunner(get(), get(), get(), get(), get(), get()) }
}
