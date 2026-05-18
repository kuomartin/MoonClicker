package com.xaxaxax.relc.ui.lua

import androidx.compose.runtime.mutableStateMapOf
import timber.log.Timber

/**
 * 管理所有由 Lua 腳本生成的 UI 節點
 */
class LuaUiManager {
    // 使用 Compose 的 mutableStateMapOf，確保狀態變化能自動觸發重繪
    private val _elements = mutableStateMapOf<String, DynamicElement>()
    val elements: Map<String, DynamicElement> get() = _elements
    private var rootId = 0

    /**
     * 新增或覆蓋 UI 節點
     */
    fun add(parentId: String?, id: String, jsonExp: String) {
        try {
            val element = DynamicElement.fromJsonExp(id, jsonExp)
            _elements[id] = element
            val parent = elements[parentId]
            when {
                parent is DynamicContainer -> {
                    parent.add(element)
                    Timber.d("LuaUiManager: Added $id (${element.type}) under $parentId")
                }

                parentId == null -> {
                    val newRootId = "__root#${rootId++}"
                    val newRoot = DynamicBox(newRootId)
                    _elements[newRootId] = newRoot
                    Timber.d("LuaUiManager: Create new ${newRoot.id}")
                    newRoot.add(element)
                    Timber.d("LuaUiManager: Added $id (${element.type}) under ${newRoot.id}")
                }

                else -> {
                    Timber.d("LuaUiManager: Failed to add $id (${element.type}) under $parentId, parentId not found")
                    _elements.remove(id)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "LuaUiManager: Failed to add $id")
        }
    }

    /**
     * 更新現有 UI 節點的屬性 (合併更新)
     */
    fun update(id: String, jsonExp: String) {
        val current = _elements[id] ?: return
        try {
            val new = DynamicElement.fromJsonExp(id, jsonExp)
            if (new is DynamicContainer && current is DynamicContainer)
                new.addAll(current)
            _elements[id] = new

            _elements.values.filterIsInstance<DynamicContainer>().forEach { container ->
                val index = container.indexOf(current)
                if (index != -1) {
                    container[index] = new
                }
            }
            // TODO check if it keeps remember state
            Timber.d("LuaUiManager: Updated $id")
        } catch (e: Exception) {
            Timber.e(e, "LuaUiManager: Failed to update $id")
        }
    }

    /**
     * 移除 UI 節點
     */
    fun remove(id: String) {
        val current = _elements.remove(id)
        Timber.d("LuaUiManager: Removed $id")
        if (current != null) {
            if (current is DynamicContainer)
                current.forEach {
                    remove(it.id)
                }
        }
    }

    /**
     * 清空所有節點
     */
    fun clear() {
        _elements.clear()
    }
}
