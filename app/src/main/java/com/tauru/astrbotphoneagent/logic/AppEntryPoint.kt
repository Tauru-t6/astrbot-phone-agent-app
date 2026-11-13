package com.tauru.astrbotphoneagent.logic

/** Only fixed, reviewed Android settings entry points; never an arbitrary Intent URI. */
internal object AppEntryPoint {
    private val settingsActions=mapOf("display_settings" to "android.settings.DISPLAY_SETTINGS",
        "wifi_settings" to "android.settings.WIFI_SETTINGS")
    fun command(packageName:String,entryPoint:String):String {
        if(!UiSnapshotParser.validPackage(packageName))throw UiFailure("bad_args","无效应用包名")
        if(entryPoint=="app_home")return "monkey -p $packageName 1"
        val action=settingsActions[entryPoint]?:throw UiFailure("bad_args","不支持的应用入口")
        if(packageName!="com.android.settings")throw UiFailure("bad_args","此入口不属于目标应用")
        return "am start -W -a $action -p com.android.settings"
    }
}
