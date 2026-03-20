package com.ninetag.machum.entity

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class HeaderNode(
    var level: Int,
    var title: String,
    var description: String = "",
    @Transient
    val children: SnapshotStateList<HeaderNode> = mutableStateListOf(),
    @Transient
    var parent: HeaderNode? = null,
) {
    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + level
        return result
    }
    override fun equals(other: Any?) = this === other
    override fun toString(): String {
        return "HeaderNode(level=$level, title='$title', description='$description', children=${children.size}개)"
    }
}