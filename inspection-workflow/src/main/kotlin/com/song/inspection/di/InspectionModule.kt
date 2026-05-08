package com.song.inspection.di

import com.song.inspection.InspectionFixService
import com.song.inspection.InspectionPromptBuilder
import com.song.inspection.InspectionRunContextFactory
import com.song.inspection.InspectionScanService
import com.song.inspection.InspectionSummaryPrinter
import com.song.inspection.InspectionWorkflowRunner
import com.song.workflow.BranchService
import com.song.workflow.CommitService
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun inspectionModule(): Module = module {
    single { InspectionRunContextFactory() }
    single { InspectionScanService(get()) }
    single { InspectionPromptBuilder() }
    single { InspectionSummaryPrinter() }
    single(named("inspection")) {
        CommitService(get(), fallbackMessage = "fix(inspection): apply IDE inspection fixes")
    }
    single(named("inspection")) {
        BranchService(get(), branchPrefix = "ideainspection")
    }
    single {
        InspectionFixService(
            get(),                            // InspectionPromptBuilder
            get(named("inspection")),         // CommitService
            get(),                            // GitCli
            get(),                            // CodeSmellAgent
            get(),                            // EditorSessionManager
            get(),                            // WorkflowProgressListener
        )
    }
    single {
        InspectionWorkflowRunner(
            get(),                            // InspectionRunContextFactory
            get(),                            // RepositorySyncService
            get(),                            // InspectionScanService
            get(),                            // InspectionSummaryPrinter
            get(named("inspection")),         // BranchService
            get(),                            // InspectionFixService
            get(),                            // BuildValidationService
            get(),                            // PullRequestPublishService
        )
    }
}
