package me.rerere.rikkahub.di

import me.rerere.rikkahub.data.knowledge.EmbeddingService
import me.rerere.rikkahub.data.knowledge.KnowledgeDatabase
import me.rerere.rikkahub.data.knowledge.KnowledgeSearchService
import me.rerere.rikkahub.data.knowledge.KnowledgeService
import me.rerere.rikkahub.ui.pages.knowledge.KnowledgeVM
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val knowledgeModule = module {
    single {
        KnowledgeDatabase.getInstance(androidContext())
    }

    single {
        get<KnowledgeDatabase>().knowledgeBaseDao()
    }

    single {
        get<KnowledgeDatabase>().knowledgeDocumentDao()
    }

    single {
        EmbeddingService(
            providerManager = get(),
            settingsStore = get(),
        )
    }

    single {
        KnowledgeSearchService(
            documentDao = get(),
            embeddingService = get(),
        )
    }

    single {
        KnowledgeService(
            knowledgeBaseDao = get(),
            documentDao = get(),
            embeddingService = get(),
            searchService = get(),
        )
    }

    viewModel { KnowledgeVM(androidContext() as android.app.Application, get<me.rerere.rikkahub.data.knowledge.KnowledgeService>(), get<me.rerere.rikkahub.data.datastore.SettingsStore>(), get<me.rerere.rikkahub.data.knowledge.EmbeddingService>()) }
}
