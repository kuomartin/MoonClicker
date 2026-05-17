package com.xaxaxax.relc.ui.lua

interface DynamicContainer : MutableList<DynamicElement>

@Suppress("JavaDefaultMethodsNotOverriddenByDelegation")
class DynamicContainerImpl(elements: MutableList<DynamicElement> = mutableListOf()) :
    DynamicContainer,
    MutableList<DynamicElement> by elements