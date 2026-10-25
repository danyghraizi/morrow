package dev.morrow

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.morrow.calendar.CalendarFeed
import dev.morrow.calendar.CalendarWriter
import dev.morrow.data.Repo
import dev.morrow.data.Settings
import dev.morrow.data.Store
import dev.morrow.domain.Assistant
import dev.morrow.llm.LlamaEngine
import dev.morrow.model.ModelPack
import dev.morrow.notify.Reminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Manual dependency graph. The reference project wires 60+ actions through Hilt;
 * this app has eight objects, so a constructor call per object is clearer and
 * removes an annotation processor from the build.
 */
class AppGraph(context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(context)
    val store = Store(context)
    val repo = Repo(store)
    val modelPack = ModelPack(context, scope)
    val engine = LlamaEngine(scope)
    val calendar = CalendarWriter(context)
    val calendarFeed = CalendarFeed(context, scope)
    val reminders = Reminders(context, settings)
    val assistant = Assistant(repo, engine, settings, calendar, calendarFeed)

    fun start() {
        scope.launch { repo.load() }
        modelPack.ensureReady()
        calendarFeed.refresh()
        scope.launch {
            modelPack.state.collect { state ->
                engine.modelPath = (state as? ModelPack.State.Ready)?.path
            }
        }
        // Alarm set follows the database; debounced so a bulk edit costs one pass.
        scope.launch {
            repo.snapshot.map { it.items }.distinctUntilChanged().debounce(200).collect { items ->
                reminders.sync(items)
            }
        }
    }
}

@OptIn(FlowPreview::class)
class MorrowApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()
        Reminders.ensureChannel(this)

        // Free the weights as soon as the app is no longer on screen.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                graph.engine.release()
            }
        })
    }

    override fun onLowMemory() {
        super.onLowMemory()
        graph.engine.release()
    }
}
