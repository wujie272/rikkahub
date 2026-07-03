package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.rag.EmbeddingService
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository

import me.rerere.rikkahub.data.repository.GroupRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import org.koin.dsl.module
import androidx.room.Room
import me.rerere.rikkahub.data.grove.GroveDatabase
import me.rerere.rikkahub.data.grove.GroveIndexService
import me.rerere.rikkahub.data.grove.GroveRepository
import me.rerere.rikkahub.data.grove.GroveSearchService
import java.io.File

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        EmbeddingService(get(), get())
    }

    single {
        FolderRepository(get(), get())
    }

    single {
        MemoryRepository(get(), get(), get<AppScope>())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        val context: Context = get()
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
                extraBindMounts = listOf(
                    WorkspaceBindMount(
                        source = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() },
                        target = "/skills",
                    ),
                    WorkspaceBindMount(
                        source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                        target = "/tool_outputs",
                    ),
                    WorkspaceBindMount(
                        source = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() },
                        target = "/upload",
                    ),
                ),
            )
        )
    }

    single {
        RootfsInstaller(get())
    }

    single {
        WorkspaceRepository(get(), get(), get(), get())
    }

    single {
        GroupRepository(get())
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SkillManager(get(), get())
    }

    single {
        Room.databaseBuilder(
            get<android.content.Context>(),
            GroveDatabase::class.java,
            "rikka_hub_grove"
        ).build()
    }

    single {
        get<GroveDatabase>().documentDao()
    }

    single {
        GroveIndexService(get(), get())
    }

    single {
        GroveSearchService(get(), get())
    }

    single {
        GroveRepository(get(), get())
    }

}
