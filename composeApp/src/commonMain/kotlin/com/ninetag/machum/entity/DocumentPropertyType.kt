package com.ninetag.machum.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DocumentPropertyType {
    @SerialName("text") TEXT,
    @SerialName("list") LIST,
    @SerialName("number") NUMBER,
    @SerialName("boolean") BOOLEAN,
    @SerialName("date") DATE,
    @SerialName("date_time") DATE_TIME,
    @SerialName("tags") TAGS,
    @SerialName("unsupported") UNSUPPORTED,
}
