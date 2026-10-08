package com.cheese.app

import android.app.Application
import com.cheese.app.data.CheeseRepository

class CheeseApplication : Application() {
    val repository: CheeseRepository by lazy { CheeseRepository(this) }
}
