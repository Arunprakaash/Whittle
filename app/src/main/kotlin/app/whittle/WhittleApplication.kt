package app.whittle

import android.app.Application

class WhittleApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}
