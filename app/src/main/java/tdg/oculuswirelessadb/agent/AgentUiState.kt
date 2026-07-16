package tdg.oculuswirelessadb.agent

object AgentUiState {
    @Volatile
    var isMainActivityVisible: Boolean = false

    // 아래 변수는 삭제하세요.
    // var isKeepAwakeActivityVisible: Boolean = false

    val isAnyAgentActivityVisible: Boolean
        get() = isMainActivityVisible
}