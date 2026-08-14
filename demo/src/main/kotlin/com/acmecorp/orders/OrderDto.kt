package com.acmecorp.orders

/**
 * Demo Kotlin target for Bean Copy Companion screenshots. `customer`
 * is deliberately named differently from [Order.getCustomerName] --
 * this is the field that should come back as an honest
 * `// TODO(bean-copy): ...` comment in the generated copier instead of
 * a guess, showing off the plugin's actual design promise in one shot.
 */
data class OrderDto(
    val id: Long,
    val customer: String,
    val total: Double,
)
