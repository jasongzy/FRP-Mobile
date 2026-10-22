package com.jasongzy.frpmobile.core.runtime

data class AutomationCommand(val operation: String, val profileId: String = "", val target: String = "") {
    init {
        require(operation in operations) { "INVALID_OPERATION" }
        if (operation == "stopAll") {
            require(target.isEmpty() && profileId.isEmpty()) { "INVALID_TARGET" }
        } else {
            require(
                (target == "autoStart" && profileId.isEmpty()) ||
                    (target.isEmpty() && profileId.matches(Regex("[a-zA-Z0-9_-]{1,80}"))),
            ) { "INVALID_TARGET" }
        }
    }

    companion object {
        val operations = listOf("toggle", "start", "stop", "restart", "stopAll")
    }

    fun toAdbCommand(packageName: String, token: String): String {
        require(packageName.matches(Regex("[A-Za-z0-9_.]+"))) { "INVALID_PACKAGE" }
        require(token.matches(Regex("[A-Za-z0-9_=-]{32,128}"))) { "INVALID_TOKEN" }
        val targetArgs = when {
            operation == "stopAll" -> ""
            target == "autoStart" -> " --es target autoStart"
            else -> " --es profile_id '$profileId'"
        }
        return "adb shell am broadcast -n $packageName/${AutomationReceiver::class.java.name}" +
            " -a ${AutomationReceiver.ACTION} --es operation $operation$targetArgs --es token '$token'"
    }
}
