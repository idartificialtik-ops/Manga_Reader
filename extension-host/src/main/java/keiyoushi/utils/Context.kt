package keiyoushi.utils

import android.app.Application
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Real host implementation (mirrors keiyoushi extensions core).
 */
val applicationContext: Application = Injekt.get()
