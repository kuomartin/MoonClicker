package com.xaxaxax.relc.overlay

import androidx.compose.runtime.Composable
import com.xaxaxax.relc.lua.LuaNative
import com.xaxaxax.relc.script.ScriptCodeType
import com.xaxaxax.relc.ui.lua.LuaUiManagerView

@Composable
fun OverlayContent(
    codeType: ScriptCodeType,
    simpleUiState: EditorControlUiState,
    onSimpleStartStop: () -> Unit,
    onSimpleAddTap: () -> Unit,
    onSimpleRecordSwipe: () -> Unit,
    onSimpleRemove: () -> Unit,
    onSimpleSave: () -> Unit,
    onSimpleClose: () -> Unit,
    onSimpleToggleCollapse: () -> Unit,
    onSimpleMore: () -> Unit,
    onSimpleDrag: (Float, Float) -> Unit
) {
    if (codeType == ScriptCodeType.SIMPLE) {
        EditorControlBar(
            uiState = simpleUiState,
            onStartStopClick = onSimpleStartStop,
            onAddTapClick = onSimpleAddTap,
            onRecordSwipeClick = onSimpleRecordSwipe,
            onRemoveClick = onSimpleRemove,
            onSaveClick = onSimpleSave,
            onCloseClick = onSimpleClose,
            onToggleCollapse = onSimpleToggleCollapse,
            onMoreClick = onSimpleMore,
            onDrag = onSimpleDrag
        )
    } else {
        LuaUiManagerView(
            manager = LuaNative.uiManager,
            luaNative = LuaNative
        )
    }
}
