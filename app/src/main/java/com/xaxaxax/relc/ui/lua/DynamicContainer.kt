package com.xaxaxax.relc.ui.lua

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

interface DynamicContainer : MutableList<DynamicElement>

class DynamicContainerImpl(elements: SnapshotStateList<DynamicElement> = mutableStateListOf()) :
    DynamicContainer,
    MutableList<DynamicElement> by elements
