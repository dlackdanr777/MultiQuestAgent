package tdg.oculuswirelessadb.agent

object AgentUiState {

    @Volatile
    var isMainActivityVisible: Boolean = false

    @Volatile
    var isCapturePermissionActivityAlive: Boolean = false

    @Volatile
    var isIdleShieldVisible: Boolean = false

    /**
     * 실제 설정/권한 UI가 표시 중인지 확인합니다.
     * IdleShield는 조작 UI가 아니므로 별도 필드로 관리합니다.
     */
    val isAnyAgentActivityVisible: Boolean
        get() =
            isMainActivityVisible ||
                    isCapturePermissionActivityAlive
}