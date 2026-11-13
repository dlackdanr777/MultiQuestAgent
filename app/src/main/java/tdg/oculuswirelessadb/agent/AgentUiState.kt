package tdg.oculuswirelessadb.agent

/** Agent가 소유한 전면 Activity 상태입니다. */
object AgentUiState {

    @Volatile
    var isMainActivityVisible: Boolean = false

    @Volatile
    var isCapturePermissionActivityAlive: Boolean = false

    @Volatile
    var isIdleShieldVisible: Boolean = false

    /** 실제 설정/권한 UI만 포함하며 IdleShield는 제외합니다. */
    val isAnyAgentActivityVisible: Boolean
        get() =
            isMainActivityVisible ||
                    isCapturePermissionActivityAlive
}