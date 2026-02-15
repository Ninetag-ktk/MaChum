package com.ninetag.machum.entity

import kotlinx.serialization.Serializable

@Serializable
data class ProjectConfig(
    val workflow: String,
    val workflowLastModified: Long?,
)