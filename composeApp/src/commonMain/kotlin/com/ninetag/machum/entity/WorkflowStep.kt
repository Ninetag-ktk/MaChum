package com.ninetag.machum.entity

import kotlinx.serialization.Serializable

@Serializable
data class WorkflowStep(
    val numbering: String,
    val title: String,
    val description: String = "",
)