package com.ninetag.machum

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform