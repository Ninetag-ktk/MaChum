package com.ninetag.machum.entity

import kotlinx.serialization.Serializable

@Serializable
data class HeaderNode(
    val level: Int,
    val title: String,
    var description: String = "",
    val children: MutableList<HeaderNode> = mutableListOf(),
    var parent: HeaderNode? = null,
)